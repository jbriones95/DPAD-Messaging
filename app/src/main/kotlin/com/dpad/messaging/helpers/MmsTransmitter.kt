package com.dpad.messaging.helpers

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.android.mms.dom.smil.parser.SmilXmlSerializer
import com.google.android.mms.ContentType
import com.google.android.mms.InvalidHeaderValueException
import com.google.android.mms.MMSPart
import com.google.android.mms.MmsException
import com.google.android.mms.pdu_alt.CharacterSets
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.GenericPdu
import com.google.android.mms.pdu_alt.PduBody
import com.google.android.mms.pdu_alt.PduComposer
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.android.mms.pdu_alt.PduPart
import com.google.android.mms.pdu_alt.PduPersister
import com.google.android.mms.pdu_alt.SendReq
import com.google.android.mms.smil.SmilHelper
import com.google.android.mms.util_alt.SqliteWrapper
import com.klinker.android.send_message.SmsManagerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Composes an MMS PDU, persists it to the system MMS provider, then hands the
 * persisted row to the platform transport via [SmsManager.sendMultimediaMessage].
 *
 * All carrier-specific concerns (MMSC URL, WAP gateway, proxy host/port, the `mms`
 * PDN, per-carrier `mms_config.xml` overrides, retry/backoff) are resolved by the
 * platform telephony stack at send time. Nothing here may hardcode or guess a
 * carrier endpoint — a wrong MMSC silently breaks every send on that network.
 *
 * The flow deliberately mirrors the approach used by QUIK and QKSMS:
 *  1. Build a spec-correct `multipart/related` [SendReq] with a leading SMIL part.
 *  2. Persist it into `content://mms/outbox` so the row exists before transmission.
 *  3. Read the row's `_id` and `sub_id` back out; the row identity is the send handle.
 *  4. Write the composed PDU to a cache file exposed through `MmsFileProvider`.
 *  5. Send using a PendingIntent keyed on the row `_id` so concurrent sends cannot
 *     cancel one another.
 *
 * Requires the caller to be the default SMS app (the platform enforces this and
 * reports `SmsManager.RESULT_NO_DEFAULT_SMS_APP` otherwise) and to hold `SEND_SMS`.
 */
object MmsTransmitter {

    private const val TAG = "DPAD_MSG"

    private const val DEFAULT_EXPIRY_TIME = 7L * 24 * 60 * 60
    private const val DEFAULT_PRIORITY = PduHeaders.PRIORITY_NORMAL

    const val EXTRA_CONTENT_URI = "content_uri"
    const val EXTRA_FILE_PATH = "file_path"

    private val MMS_OUTBOX: Uri = Uri.parse("content://mms/outbox")

    /**
     * Persists [parts] as a single MMS and hands it to the platform for delivery.
     *
     * @param context        Application context.
     * @param recipients     Destination numbers.
     * @param body           Text body; may be blank for media-only messages.
     * @param parts          Media parts, in display order.
     * @param subscriptionId SIM to send from, or a negative value for the default.
     * @param groupMms       True when more than one recipient shares one message.
     * @param sentIntent     Base intent for the result callback. The message URI is
     *                       attached as its data and the row `_id` as the request code.
     * @return The persisted `content://mms/<id>` row, or null if nothing was sent.
     */
    fun send(
        context: Context,
        recipients: List<String>,
        body: String,
        parts: List<MMSPart>,
        subscriptionId: Int,
        groupMms: Boolean,
        sentIntent: Intent
    ): Uri? {
        return try {
            sendInternal(context, recipients, body, parts, subscriptionId, groupMms, sentIntent)
        } catch (e: Exception) {
            Log.e(TAG, "MmsTransmitter: send failed", e)
            null
        }
    }

    private fun sendInternal(
        context: Context,
        recipients: List<String>,
        body: String,
        parts: List<MMSPart>,
        subscriptionId: Int,
        groupMms: Boolean,
        sentIntent: Intent
    ): Uri? {
        // Text is appended last so the media parts lead and the text trails, matching
        // the ordering every mainstream MMS client expects.
        val ordered = ArrayList<MMSPart>(parts)
        if (body.isNotBlank()) {
            ordered.add(MMSPart().apply {
                name = "text"
                mimeType = "text/plain"
                data = body.toByteArray()
            })
        }

        val sendReq = buildPdu(context, subscriptionId, recipients, ordered)
        val persister = PduPersister.getPduPersister(context)

        val messageUri = persister.persist(sendReq, MMS_OUTBOX, true, groupMms, null, subscriptionId)
        Log.d(TAG, "MmsTransmitter: persisted $messageUri group=$groupMms subId=$subscriptionId")

        // The platform expects the row to be sitting in the outbox.
        SqliteWrapper.update(
            context,
            context.contentResolver,
            messageUri,
            ContentValues(1).apply { put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX) },
            null,
            null
        )

        val row = readRow(context, messageUri, subscriptionId)
        val messageId = row.first
        val resolvedSubId = row.second

        // Compose from the persisted PDU so the bytes handed to the modem are exactly
        // what was written to the provider.
        val persistedPdu: GenericPdu = try {
            persister.load(messageUri)
        } catch (e: MmsException) {
            Log.e(TAG, "MmsTransmitter: unable to reload persisted PDU", e)
            return null
        }

        val fileName = "send.${UUID.randomUUID()}.dat"
        val pduFile = File(context.cacheDir, fileName)
        val contentUri = Uri.Builder()
            .authority(context.packageName + ".MmsFileProvider")
            .path(fileName)
            .scheme(ContentResolver.SCHEME_CONTENT)
            .build()

        try {
            FileOutputStream(pduFile).use { it.write(PduComposer(context, persistedPdu).make()) }
        } catch (e: IOException) {
            Log.e(TAG, "MmsTransmitter: unable to write PDU file", e)
            return null
        }

        val pendingIntent = buildSentPendingIntent(context, sentIntent, messageUri, messageId, pduFile)

        val configOverrides = Bundle().apply {
            putBoolean(SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED, groupMms)
        }

        SmsManagerFactory.createSmsManager(resolvedSubId).sendMultimediaMessage(
            context,
            contentUri,
            null,
            configOverrides,
            pendingIntent
        )

        Log.d(
            TAG,
            "MmsTransmitter: handed PDU to platform uri=$messageUri id=$messageId subId=$resolvedSubId file=$fileName"
        )
        return messageUri
    }

    /**
     * Builds the result callback. The request code is the provider row `_id` and the
     * intent carries the row URI as its data, so two in-flight MMS broadcasts are
     * distinct PendingIntents. Without both, `FLAG_CANCEL_CURRENT` would let a second
     * send cancel the first and strand its row in the outbox.
     */
    private fun buildSentPendingIntent(
        context: Context,
        baseIntent: Intent,
        messageUri: Uri,
        messageId: Int,
        pduFile: File
    ): PendingIntent {
        val intent = Intent(baseIntent).apply {
            data = messageUri
            putExtra(EXTRA_CONTENT_URI, messageUri.toString())
            putExtra(EXTRA_FILE_PATH, pduFile.path)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, messageId, intent, flags)
    }

    /** Reads `_id` and the effective subscription id back out of the persisted row. */
    private fun readRow(context: Context, messageUri: Uri, fallbackSubId: Int): Pair<Int, Int> {
        var id = -1
        var subId = fallbackSubId
        runCatching {
            context.contentResolver.query(
                messageUri,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.SUBSCRIPTION_ID),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    id = cursor.getInt(0)
                    // PduPersister omits this column when the default subscription was
                    // used, so a NULL here must fall back rather than read as SIM 0.
                    if (!cursor.isNull(1)) {
                        val stored = cursor.getInt(1)
                        if (stored >= 0) subId = stored
                    }
                }
            }
        }.onFailure {
            Log.w(TAG, "MmsTransmitter: unable to read row metadata for $messageUri", it)
        }
        return id to subId
    }

    private fun buildPdu(
        context: Context,
        subscriptionId: Int,
        recipients: List<String>,
        parts: List<MMSPart>
    ): SendReq {
        val req = SendReq()

        req.prepareFromAddress(context, "", subscriptionId)
        recipients.forEach { req.addTo(EncodedStringValue(it)) }
        req.setDate(System.currentTimeMillis() / 1000)

        val body = PduBody()
        var size = 0
        parts.forEach { size += addPart(body, it) }

        // A SMIL document is prepended for compatibility; several carriers and
        // handsets mishandle slide layout without one.
        val smil = ByteArrayOutputStream()
        SmilXmlSerializer.serialize(SmilHelper.createSmilDocument(body), smil)
        body.addPart(0, PduPart().apply {
            setContentId("smil".toByteArray())
            setContentLocation("smil.xml".toByteArray())
            setContentType(ContentType.APP_SMIL.toByteArray())
            setData(smil.toByteArray())
        })

        req.setBody(body)
        req.setMessageSize(size.toLong())
        req.setMessageClass(PduHeaders.MESSAGE_CLASS_PERSONAL_STR.toByteArray())
        req.setExpiry(DEFAULT_EXPIRY_TIME)

        try {
            req.setPriority(DEFAULT_PRIORITY)
            req.setDeliveryReport(PduHeaders.VALUE_NO)
            req.setReadReport(PduHeaders.VALUE_NO)
        } catch (e: InvalidHeaderValueException) {
            Log.w(TAG, "MmsTransmitter: invalid header value while building PDU", e)
        }

        return req
    }

    private fun addPart(body: PduBody, media: MMSPart): Int {
        val name = media.name.ifBlank { "attachment" }
        val part = PduPart().apply {
            if (media.mimeType.startsWith("text")) {
                setCharset(CharacterSets.UTF_8)
            }
            setContentType(media.mimeType.toByteArray())
            setContentLocation(name.toByteArray())
            val dot = name.lastIndexOf(".")
            val contentId = if (dot == -1) name else name.substring(0, dot)
            setContentId(contentId.toByteArray())
            setData(media.data)
        }
        body.addPart(part)
        return media.data?.size ?: 0
    }
}
