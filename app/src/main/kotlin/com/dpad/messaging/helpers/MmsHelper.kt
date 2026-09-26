package com.dpad.messaging.helpers

import android.content.Context
import android.net.Uri
import android.util.Log
import com.dpad.messaging.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Utilities for reading MMS message parts from the system Telephony provider.
 *
 * All functions must be called from a background thread.
 */
object MmsHelper {

    private const val TAG = "DPAD_MSG"

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
     * Returns a list of content URI strings for all image parts found in the MMS message.
     * Returns empty list if there are no image parts.
     */
    fun getMmsImagePartUris(context: Context, msgId: Long): List<String> {
        return getCachedParts(context, msgId).imagePartUris
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
        if (getMmsImagePartUris(context, msgId).isNotEmpty()) return ""
        val attachLabel = getMmsAttachmentLabel(context, msgId)
        if (attachLabel.isNotBlank()) return attachLabel
        return "MMS"
    }

    private fun getCachedParts(context: Context, msgId: Long): MmsPartCache.CachedParts {
        val cached = MmsPartCache.get(msgId)
        if (cached != null) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "getCachedParts($msgId) CACHE HIT body=${cached.textBody.length} imgs=${cached.imagePartUris.size}")
            }
            return cached
        }

        var textBody = ""
        var imagePartUris = mutableListOf<String>()
        var attachmentLabel = ""
        var rowCount = 0

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
                    rowCount++
                    val rawCt = cursor.getString(idxCt) ?: continue
                    val ct = rawCt.substringBefore(';').trim().lowercase()

                    if (ct == "text/plain") {
                        val partText = cursor.getString(idxText).orEmpty()
                        if (partText.isNotBlank()) {
                            if (textBody.isNotBlank()) textBody += "\n"
                            textBody += partText
                        }
                    }

                    if (ct.isImageMimeType()) {
                        val partId = cursor.getLong(idxId)
                        imagePartUris.add("content://mms/part/$partId")
                    }

                    if (attachmentLabel.isBlank() && !ct.isImageMimeType() && ct !in SKIP_MIME_TYPES) {
                        attachmentLabel = if (ct.isVcardMimeType() && idxId >= 0) {
                            val partId = cursor.getLong(idxId)
                            readVcardLabel(context, partId)
                        } else {
                            ct
                        }
                    }

                    if (textBody.isNotBlank() && imagePartUris.isNotEmpty() && attachmentLabel.isNotBlank()) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getCachedParts($msgId) QUERY FAILED", e)
        }

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "getCachedParts($msgId) rows=$rowCount bodyLen=${textBody.length} " +
                    "imgs=${imagePartUris.size} label=$attachmentLabel"
            )
        }

        return MmsPartCache.CachedParts(
            textBody = textBody,
            imagePartUris = imagePartUris,
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
