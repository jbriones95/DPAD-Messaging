package com.dpad.messaging.helpers

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.dpad.messaging.BuildConfig
import com.google.android.mms.MMSPart
import com.klinker.android.send_message.Settings as KlinkerSettings
import com.klinker.android.send_message.Transaction as KlinkerTransaction
import com.dpad.messaging.extensions.getOwnPhoneNumbers
import com.dpad.messaging.receivers.MmsSentReceiver
import java.io.ByteArrayOutputStream

/**
 * Sends MMS messages by composing the PDU, persisting it to the system MMS provider,
 * and delegating transport to the platform via `SmsManager.sendMultimediaMessage()`.
 *
 * The send path deliberately performs no MMSC resolution of its own. MMSC URL, WAP
 * gateway, proxy host/port, the `mms` PDN and per-carrier `mms_config.xml` overrides
 * are all resolved by the platform telephony stack at send time, so the same code
 * works on every carrier. Guessing an endpoint here is what previously broke sending
 * on non-AT&T networks.
 *
 * Flow:
 *  1. Filter own numbers from the recipient list
 *  2. Read and downscale each attachment into media parts
 *  3. Persist the PDU to `content://mms/outbox`
 *  4. Hand the persisted row to the platform and await the MmsSentReceiver callback
 *
 * Must be called from a background thread — performs file I/O and provider writes.
 */
object MmsSender {

    private const val TAG = "DPAD_MSG"

    const val ACTION_MMS_SENT = "com.dpad.messaging.MMS_SENT"
    const val EXTRA_THREAD_ID = "extra_thread_id"

    private const val MAX_IMAGE_WIDTH = 800
    private const val JPEG_QUALITY    = 85

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Initialises the vendored library's receive-side settings.
     *
     * The library couples its send and receive transports behind a single
     * `useSystemSending` flag. Sending no longer uses the library transaction, so
     * enabling this flag selects Android's platform MMS download for receive.
     *
     * This no longer affects sending: [send] no longer goes through the library's
     * `Transaction`, so pinning the flag cannot drag MMS send back onto the
     * in-process HTTP client.
     */
    fun initLibraryReceive() {
        val settings = KlinkerSettings().apply {
            setUseSystemSending(true)
            setGroup(true)
            setDeliveryReports(Prefs.get().deliveryReports)
        }
        KlinkerTransaction.settings = settings
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "MmsSender: initialised library receive settings (send path bypasses library)")
        }
    }

    /**
     * Sends an MMS with the given text body and optional attachments.
     *
     * @param context        Application context.
     * @param recipients     Recipient phone number(s). Multiple numbers = group MMS.
     * @param body           Text body (may be blank when media-only).
     * @param attachmentUri  Content URI of a single media/file to attach, or null.
     * @param attachmentUris All media URIs to attach, in order.
     * @param threadId       Telephony thread ID the message should land in.
     * @param subscriptionId SIM subscription ID (negative = system default).
     * @param scheduledMessageId Room id of the scheduled message, when applicable.
     */
    fun send(
        context: Context,
        recipients: List<String>,
        body: String,
        attachmentUri: Uri?,
        attachmentUris: List<Uri> = emptyList(),
        threadId: Long,
        subscriptionId: Int = -1,
        scheduledMessageId: Long? = null
    ) {
        if (recipients.isEmpty()) return

        val ownNumbers = context.getOwnPhoneNumbers()
        val filteredRecipients = recipients.filter { num ->
            val digits = num.filter { it.isDigit() }
            digits !in ownNumbers && digits.takeLast(10) !in ownNumbers
        }.ifEmpty { recipients }
        val mergedAttachments = LinkedHashSet<Uri>().apply {
            attachmentUris.forEach { add(it) }
            if (attachmentUri != null) add(attachmentUri)
        }.toList()

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "MmsSender.send() recipients=$recipients filtered=$filteredRecipients body='${body.take(20)}' attachments=${mergedAttachments.size} threadId=$threadId"
            )
        }

        sendMmsInternal(
            context = context,
            recipients = filteredRecipients,
            body = body,
            attachmentUris = mergedAttachments,
            threadId = threadId,
            subscriptionId = subscriptionId,
            scheduledMessageId = scheduledMessageId
        )
    }

    private fun sendMmsInternal(
        context: Context,
        recipients: List<String>,
        body: String,
        attachmentUris: List<Uri>,
        threadId: Long,
        subscriptionId: Int,
        scheduledMessageId: Long?
    ) {
        val parts = ArrayList<MMSPart>(attachmentUris.size)
        var hasImage = false

        for (uri in attachmentUris) {
            val rawMimeType = context.contentResolver.getType(uri)?.lowercase() ?: "application/octet-stream"
            val isImage = rawMimeType.startsWith("image/")
            val bytes = if (isImage) compressImage(context, uri) else readAttachment(context, uri)
            if (bytes == null) {
                Log.w(TAG, "MmsSender: unable to read attachment from uri=$uri")
                continue
            }

            hasImage = hasImage || isImage
            val normalizedMime = when {
                isImage -> "image/jpeg"
                rawMimeType == "text/plain" -> "application/txt"
                else -> rawMimeType
            }
            val attachmentName = resolveAttachmentName(context, uri, normalizedMime)
            parts.add(
                MMSPart().apply {
                    name = attachmentName
                    fileName = attachmentName
                    mimeType = normalizedMime
                    data = bytes
                }
            )
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "MmsSender: added attachment mime=$normalizedMime name=$attachmentName bytes=${bytes.size}")
            }
        }

        val sentIntent = Intent(ACTION_MMS_SENT, null, context, MmsSentReceiver::class.java).apply {
            putExtra(EXTRA_THREAD_ID, threadId)
            putExtra("extra_has_image", hasImage)
            if (scheduledMessageId != null) {
                putExtra("extra_scheduled_message_id", scheduledMessageId)
            }
        }

        val messageUri = MmsTransmitter.send(
            context = context,
            recipients = recipients,
            body = body,
            parts = parts,
            subscriptionId = subscriptionId,
            groupMms = recipients.size > 1,
            sentIntent = sentIntent
        )
        if (messageUri == null) {
            Log.e(TAG, "MmsSender: send produced no persisted message; delivery not attempted")
        }
    }

    private fun readAttachment(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "MmsSender: readAttachment failed for $uri", e)
            null
        }
    }

    private fun resolveAttachmentName(context: Context, uri: Uri, mimeType: String): String {
        val displayName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull().orEmpty().trim()

        if (displayName.isNotBlank()) return displayName

        val lastSegment = uri.lastPathSegment.orEmpty().substringAfterLast('/').trim()
        if (lastSegment.isNotBlank()) return lastSegment

        return when (mimeType) {
            "image/jpeg" -> "image.jpg"
            "application/txt" -> "attachment.txt"
            else -> "attachment.bin"
        }
    }

    // ── Image compression ─────────────────────────────────────────────────────

    private fun compressImage(context: Context, uri: Uri): ByteArray? {
        return try {
            val original = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
            if (original == null) return null

            val scaled = if (original.width > MAX_IMAGE_WIDTH) {
                val ratio  = MAX_IMAGE_WIDTH.toFloat() / original.width.toFloat()
                val newH   = (original.height * ratio).toInt()
                val result = Bitmap.createScaledBitmap(original, MAX_IMAGE_WIDTH, newH, true)
                original.recycle()
                result
            } else {
                original
            }

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            scaled.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            // If bitmap decode fails (e.g., provider quirk), fall back to raw bytes.
            if (BuildConfig.DEBUG) Log.w(TAG, "MmsSender: compressImage failed, falling back to raw bytes", e)
            readAttachment(context, uri)
        }
    }
}
