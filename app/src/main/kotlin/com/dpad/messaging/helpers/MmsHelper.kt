package com.dpad.messaging.helpers

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Utilities for reading MMS message parts from the system Telephony provider.
 *
 * All functions must be called from a background thread.
 */
object MmsHelper {

    private val IMAGE_MIME_TYPES = setOf(
        "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp"
    )

    // MIME types to skip when looking for "other attachment" labels
    private val SKIP_MIME_TYPES = IMAGE_MIME_TYPES + setOf("text/plain", "application/smil")
    private fun String?.isImageMimeType(): Boolean {
        val mimeType = this?.lowercase() ?: return false
        return mimeType.startsWith("image/") || mimeType in IMAGE_MIME_TYPES
    }

    private fun String?.isVcardMimeType(): Boolean {
        val mimeType = this?.lowercase()?.substringBefore(';')?.trim() ?: return false
        return mimeType == "text/x-vcard" ||
            mimeType == "text/vcard" ||
            mimeType == "text/directory"
    }

    /**
     * Returns the text/plain body of an MMS message, or an empty string if none.
     */
    fun getMmsTextBody(context: Context, msgId: Long): String {
        return getCachedParts(context, msgId).textBody
    }

    /**
     * Returns the content URI string of the first image part found in the MMS message,
     * e.g. "content://mms/part/42", or null if there is no image part.
     */
    fun getMmsImagePartUri(context: Context, msgId: Long): String? {
        return getCachedParts(context, msgId).imagePartUri
    }

    /**
     * Returns the content URI string of the first audio part found in the MMS message,
     * e.g. "content://mms/part/42", or null if there is no audio part.
     */
    fun getMmsAudioPartUri(context: Context, msgId: Long): String? {
        return getCachedParts(context, msgId).audioPartUri
    }

    /**
     * Returns the MIME type of the first non-text, non-image, non-SMIL part, or an
     * empty string if all parts are accounted for by text/images.
     * Useful for showing e.g. "audio/mpeg" or "video/mp4" as a fallback label.
     */
    fun getMmsAttachmentLabel(context: Context, msgId: Long): String {
        return getCachedParts(context, msgId).attachmentLabel
    }

    /**
     * Returns the best available display body for an MMS message:
     *  1. The text/plain part body, if present and non-blank.
     *  2. The subject field, if non-blank.
     *  3. The MIME type of any non-text/non-image attachment (e.g. "audio/mpeg").
     *  4. "MMS" as a last resort.
     */
    fun getMmsDisplayBody(context: Context, msgId: Long, subject: String): String {
        val textBody = getMmsTextBody(context, msgId)
        if (textBody.isNotBlank()) return textBody
        if (subject.isNotBlank()) return subject
        if (getMmsImagePartUri(context, msgId) != null) return ""
        val attachLabel = getMmsAttachmentLabel(context, msgId)
        if (attachLabel.isNotBlank()) return attachLabel
        return "MMS"
    }

    private fun getCachedParts(context: Context, msgId: Long): MmsPartCache.CachedParts {
        val cached = MmsPartCache.get(msgId)
        if (cached != null) return cached

        var textBody = ""
        var imagePartUri: String? = null
        var audioPartUri: String? = null
        var attachmentLabel = ""

        val partsUri = Uri.parse("content://mms/$msgId/part")
        try {
            context.contentResolver.query(
                partsUri,
                arrayOf("_id", "ct", "text"),
                null,
                null,
                null
            )?.use { cursor ->
                val idxId = cursor.getColumnIndex("_id")
                val idxCt = cursor.getColumnIndex("ct")
                val idxText = cursor.getColumnIndex("text")

                while (cursor.moveToNext()) {
                    val ct = cursor.getString(idxCt) ?: continue

                    if (textBody.isBlank() && ct == "text/plain") {
                        textBody = cursor.getString(idxText) ?: ""
                    }

                    if (imagePartUri == null && ct.isImageMimeType()) {
                        val partId = cursor.getLong(idxId)
                        imagePartUri = "content://mms/part/$partId"
                    }

                    if (audioPartUri == null && ct.lowercase().startsWith("audio/")) {
                        val partId = cursor.getLong(idxId)
                        audioPartUri = "content://mms/part/$partId"
                    }

                    if (attachmentLabel.isBlank() && !ct.isImageMimeType() && ct !in SKIP_MIME_TYPES) {
                        attachmentLabel = if (ct.isVcardMimeType() && idxId >= 0) {
                            val partId = cursor.getLong(idxId)
                            readVcardLabel(context, partId)
                        } else {
                            ct
                        }
                    }

                    if (textBody.isNotBlank() && imagePartUri != null && attachmentLabel.isNotBlank()) {
                        break
                    }
                }
            }
        } catch (_: Exception) {}

        return MmsPartCache.CachedParts(
            textBody = textBody,
            imagePartUri = imagePartUri,
            audioPartUri = audioPartUri,
            attachmentLabel = attachmentLabel
        ).also { MmsPartCache.put(msgId, it) }
    }

    private fun readVcardLabel(context: Context, partId: Long): String {
        val uri = Uri.parse("content://mms/part/$partId")
        val raw = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
            }
        } catch (_: Exception) {
            null
        }

        val name = raw?.let { extractVcardName(it) }
        return if (!name.isNullOrBlank()) "Contact: $name" else "Contact card"
    }

    private fun extractVcardName(raw: String): String? {
        val unfolded = raw.replace(Regex("\\r?\\n[ \\t]"), "")

        for (line in unfolded.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("FN", ignoreCase = true)) {
                val value = trimmed.substringAfter(':', "").trim()
                if (value.isNotBlank()) return unescapeVcardValue(value)
            }
        }

        for (line in unfolded.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("N", ignoreCase = true)) {
                val value = trimmed.substringAfter(':', "").trim()
                if (value.isBlank()) continue
                val parts = value.split(';').map { unescapeVcardValue(it.trim()) }
                val joined = listOfNotNull(
                    parts.getOrNull(1)?.takeIf { it.isNotBlank() },
                    parts.getOrNull(2)?.takeIf { it.isNotBlank() },
                    parts.getOrNull(0)?.takeIf { it.isNotBlank() }
                ).joinToString(" ").trim()
                if (joined.isNotBlank()) return joined
            }
        }

        return null
    }

    private fun unescapeVcardValue(value: String): String {
        return value
            .replace("\\\\n", "\n")
            .replace("\\\\N", "\n")
            .replace("\\\\,", ",")
            .replace("\\\\;", ";")
            .replace("\\\\\\\\", "\\")
            .trim()
    }
}
