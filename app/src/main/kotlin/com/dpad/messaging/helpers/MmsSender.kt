package com.dpad.messaging.helpers

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Telephony
import android.util.Log
import com.dpad.messaging.BuildConfig
import com.klinker.android.send_message.Message as KlinkerMessage
import com.klinker.android.send_message.Settings as KlinkerSettings
import com.klinker.android.send_message.Transaction as KlinkerTransaction
import com.dpad.messaging.extensions.getOwnPhoneNumbers
import com.dpad.messaging.receivers.MmsSentReceiver
import java.io.ByteArrayOutputStream

/**
 * Sends MMS messages via mmslib Transaction for unified, carrier-compatible handling.
 *
 * Phase 2 unified approach:
 *  All MMS sends (text-only group, text-only 1:1, media 1:1, media group) go through
 *  mmslib Transaction which handles:
 *    - PDU composition
 *    - Provider insertion
 *    - System MMS sending via SmsManager.sendMultimediaMessage()
 *    - Sent/delivery callbacks
 *
 * Flow for all sends:
 *  1. Filter own numbers from recipient list
 *  2. Build Message object (text + optional image)
 *  3. Create Transaction with Settings (useSystemSending=true, group=true/false, etc.)
 *  4. Attach explicit broadcast intent for MmsSentReceiver
 *  5. Send via transaction.sendNewMessage()
 *
 * Must be called from a background thread — performs file I/O and network operations.
 */
object MmsSender {

    private const val TAG = "DPAD_MSG"

    const val ACTION_MMS_SENT = "com.dpad.messaging.MMS_SENT"
    const val EXTRA_THREAD_ID = "extra_thread_id"

    private const val MAX_IMAGE_WIDTH = 800
    private const val JPEG_QUALITY    = 85

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Sends an MMS with the given text body and optional image attachment via mmslib.
     *
     * Both text-only and media messages are unified through the mmslib Transaction path,
     * which ensures carrier compatibility and proper provider bookkeeping.
     *
     * @param context        Application context.
     * @param recipients     Recipient phone number(s). Multiple numbers = group MMS.
     * @param body           Text body (may be blank when image-only).
     * @param attachmentUri  Content URI of media/file to attach, or null for text-only MMS.
     * @param threadId       Telephony thread ID — passed to sendNewMessage so the message
     *                       lands in the correct existing thread.
     * @param subscriptionId SIM subscription ID (-1 = system default).
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
        val message = KlinkerMessage(body, recipients.toTypedArray())
        if (recipients.size > 1) {
            message.setSubject("Group message")
        }

        // Add all attachments to a single MMS (multipart/mixed) per library pattern
        for (uri in attachmentUris) {
            val mimeType = context.contentResolver.getType(uri)?.lowercase() ?: "application/octet-stream"
            val bytes = if (mimeType.startsWith("image/")) {
                compressImage(context, uri)
            } else {
                readAttachment(context, uri)
            }

            if (bytes != null) {
                try {
                    val normalizedMime = when {
                        mimeType.startsWith("image/") -> "image/jpeg"
                        mimeType == "text/plain" -> "application/txt"
                        else -> mimeType
                    }
                    val attachmentName = resolveAttachmentName(context, uri, normalizedMime)
                    message.addMedia(bytes, normalizedMime, attachmentName)
                    if (BuildConfig.DEBUG) Log.d(TAG, "MmsSender: added attachment mime=$normalizedMime name=$attachmentName bytes=${bytes.size}")
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "MmsSender: failed to add attachment", e)
                }
            } else {
                if (BuildConfig.DEBUG) Log.w(TAG, "MmsSender: unable to read attachment data from uri=$uri")
            }
        }

        // Create Settings with desired behavior
        val settings = KlinkerSettings().apply {
            setUseSystemSending(true)
            setGroup(recipients.size > 1)
            setDeliveryReports(Prefs.get().deliveryReports)
            if (subscriptionId >= 0) {
                setSubscriptionId(subscriptionId)
            }
        }
        applyCarrierMmsConfig(context, settings)

        // Create Transaction and attach callback
        val transaction = KlinkerTransaction(context, settings)
        val hasImage = attachmentUris.any { uri ->
            runCatching { context.contentResolver.getType(uri)?.startsWith("image/", ignoreCase = true) == true }
                .getOrDefault(false)
        }
        val sentIntent = Intent(ACTION_MMS_SENT, null, context, MmsSentReceiver::class.java).apply {
            putExtra(EXTRA_THREAD_ID, threadId)
            putExtra("extra_has_image", hasImage)
            putExtra("extra_library_sender", true)
            if (scheduledMessageId != null) {
                putExtra("extra_scheduled_message_id", scheduledMessageId)
            }
        }
        transaction.setExplicitBroadcastForSentMms(sentIntent)

        // Send via mmslib — pass threadId so message lands in the correct thread
        try {
            if (BuildConfig.DEBUG) Log.d(TAG, "MmsSender: sending via mmslib recipients=$recipients group=${recipients.size > 1} subId=$subscriptionId threadId=$threadId")
            transaction.sendNewMessage(message)
            if (BuildConfig.DEBUG) Log.d(TAG, "MmsSender: sent successfully")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "MmsSender: send failed", e)
        }
    }

    /**
     * Mirrors the reference smsmms setup: configure MMSC/proxy/port from APN so
     * carrier routing works even on ROMs where defaults are incomplete.
     */
    private fun applyCarrierMmsConfig(context: Context, settings: KlinkerSettings) {
        var mmsc = ""
        var proxy = ""
        var port = ""

        runCatching {
            val projection = arrayOf(
                Telephony.Carriers.MMSC,
                Telephony.Carriers.MMSPROXY,
                Telephony.Carriers.MMSPORT,
                Telephony.Carriers.TYPE,
                Telephony.Carriers.CURRENT
            )
            val selection = "${Telephony.Carriers.CURRENT}=1"

            context.contentResolver.query(
                Telephony.Carriers.CONTENT_URI,
                projection,
                selection,
                null,
                "_id DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val type = cursor.getString(3).orEmpty()
                    if (!type.contains("mms", ignoreCase = true) && type != "*") {
                        continue
                    }
                    mmsc = cursor.getString(0).orEmpty().trim()
                    proxy = cursor.getString(1).orEmpty().trim()
                    port = cursor.getString(2).orEmpty().trim()
                    if (mmsc.isNotBlank()) break
                }
            }
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w(TAG, "MmsSender: unable to query APN table for MMS config", it)
        }

        // Fallback to user-configured proxy when APN table is blocked by OEM policy.
        if (proxy.isBlank()) {
            val prefProxy = Prefs.get().mmsProxyHost.trim()
            if (prefProxy.isNotBlank()) {
                proxy = prefProxy
                val prefPort = Prefs.get().mmsProxyPort
                if (prefPort > 0) {
                    port = prefPort.toString()
                }
            }
        }

        if (mmsc.isNotBlank()) settings.setMmsc(mmsc)
        if (proxy.isNotBlank()) settings.setProxy(proxy)
        if (port.isNotBlank()) settings.setPort(port)

        // Keep headers explicit for stricter carrier gateways.
        settings.setAgent("Android-Mms/2.0")
        settings.setUaProfTagName("x-wap-profile")
        settings.setUserProfileUrl("http://www.google.com/oha/rdf/ua-profile-20080331.xml")

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "MmsSender: carrier config applied mmsc='${mmsc.take(80)}' proxy='$proxy' port='$port'")
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
