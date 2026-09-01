package com.dpad.messaging.helpers

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap

object AttachmentPolicy {
    const val MAX_NON_IMAGE_ATTACHMENT_BYTES = 900L * 1024L

    fun resolveMimeType(context: Context, uri: Uri): String {
        val fromResolver = try {
            context.contentResolver.getType(uri)?.lowercase().orEmpty()
        } catch (_: Exception) {
            ""
        }
        if (fromResolver.isNotBlank()) return fromResolver

        val path = uri.toString().lowercase()
        val extension = MimeTypeMap.getFileExtensionFromUrl(path).orEmpty()
        val fromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        return fromExt?.lowercase().orEmpty()
    }

    fun resolveAttachmentSize(context: Context, uri: Uri): Long? {
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) {
                        val value = cursor.getLong(idx)
                        if (value >= 0L) return value
                    }
                }
            }
        } catch (_: Exception) {
        }

        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length.takeIf { it >= 0L }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isWithinMmsLimit(context: Context, uri: Uri): Boolean {
        val mimeType = resolveMimeType(context, uri)
        if (mimeType.startsWith("image/")) return true
        val size = resolveAttachmentSize(context, uri) ?: return true
        return size <= MAX_NON_IMAGE_ATTACHMENT_BYTES
    }
}
