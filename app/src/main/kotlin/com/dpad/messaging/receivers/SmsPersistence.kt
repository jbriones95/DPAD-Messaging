package com.dpad.messaging.receivers

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony

/** Provider writes shared by WorkManager and the synchronous receive fallback. */
internal object SmsPersistence {

    data class StoredSms(
        val id: Long,
        val threadId: Long,
        val body: String,
        val inserted: Boolean
    )

    fun store(
        context: Context,
        address: String,
        body: String,
        timestamp: Long,
        subscriptionId: Int
    ): StoredSms {
        val resolvedThreadId = resolveThreadId(context, address)
        findStoredSms(context, address, body, timestamp, resolvedThreadId)?.let { existing ->
            return repairStoredBody(context, existing, body, inserted = false)
        }

        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, timestamp)
            put(Telephony.Sms.DATE_SENT, timestamp)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            resolvedThreadId?.let { put(Telephony.Sms.THREAD_ID, it) }
            if (subscriptionId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
        }
        val insertedUri = context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
        if (insertedUri != null) {
            readStoredSms(context, insertedUri)?.let {
                return repairStoredBody(context, it, body, inserted = true)
            }
        }

        // Some OEM providers commit and then return null. Read back before retrying
        // so WorkManager cannot create a duplicate on its next attempt.
        findStoredSms(context, address, body, timestamp, resolvedThreadId)?.let {
            return repairStoredBody(context, it, body, inserted = false)
        }
        error("Telephony provider did not store incoming SMS")
    }

    private fun findStoredSms(
        context: Context,
        address: String,
        body: String,
        timestamp: Long,
        threadId: Long?
    ): StoredSms? {
        val selection = mutableListOf(
            "${Telephony.Sms.DATE} = ?",
            "${Telephony.Sms.ADDRESS} = ?",
            "${Telephony.Sms.TYPE} = ?"
        )
        val args = mutableListOf(timestamp.toString(), address, Telephony.Sms.MESSAGE_TYPE_INBOX.toString())
        threadId?.let {
            selection += "${Telephony.Sms.THREAD_ID} = ?"
            args += it.toString()
        }

        val rows = mutableListOf<StoredSms>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.BODY),
            selection.joinToString(" AND "),
            args.toTypedArray(),
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(Telephony.Sms._ID)
            val threadIndex = cursor.getColumnIndex(Telephony.Sms.THREAD_ID)
            val bodyIndex = cursor.getColumnIndex(Telephony.Sms.BODY)
            while (cursor.moveToNext()) {
                if (idIndex < 0 || threadIndex < 0) continue
                rows += StoredSms(
                    id = cursor.getLong(idIndex),
                    threadId = cursor.getLong(threadIndex),
                    body = if (bodyIndex >= 0) cursor.getString(bodyIndex).orEmpty() else "",
                    inserted = false
                )
            }
        }
        return rows.singleOrNull { it.body == body }
            ?: rows.singleOrNull { it.body.isBlank() && body.isNotBlank() }
    }

    private fun readStoredSms(context: Context, uri: Uri): StoredSms? {
        return context.contentResolver.query(
            uri,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.BODY),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val idIndex = cursor.getColumnIndex(Telephony.Sms._ID)
            val threadIndex = cursor.getColumnIndex(Telephony.Sms.THREAD_ID)
            val bodyIndex = cursor.getColumnIndex(Telephony.Sms.BODY)
            if (idIndex < 0 || threadIndex < 0) return@use null
            val threadId = cursor.getLong(threadIndex)
            if (threadId <= 0L) return@use null
            StoredSms(
                id = cursor.getLong(idIndex),
                threadId = threadId,
                body = if (bodyIndex >= 0) cursor.getString(bodyIndex).orEmpty() else "",
                inserted = true
            )
        }
    }

    private fun repairStoredBody(
        context: Context,
        stored: StoredSms,
        body: String,
        inserted: Boolean
    ): StoredSms {
        if (body.isBlank() || stored.body.isNotBlank()) return stored.copy(inserted = inserted)
        val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, stored.id)
        val values = ContentValues().apply { put(Telephony.Sms.BODY, body) }
        context.contentResolver.update(uri, values, null, null)
        val repaired = readStoredSms(context, uri) ?: stored
        if (repaired.body.isBlank()) error("Telephony provider stored a blank SMS body")
        return repaired.copy(inserted = inserted)
    }

    private fun resolveThreadId(context: Context, address: String): Long? {
        runCatching { Telephony.Threads.getOrCreateThreadId(context, address) }
            .getOrNull()
            ?.takeIf { it > 0L }
            ?.let { return it }

        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID),
                "${Telephony.Sms.ADDRESS} = ?",
                arrayOf(address),
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it > 0L } else null
            }
        }.getOrNull()?.let { return it }

        return runCatching {
            Telephony.Threads.getOrCreateThreadId(context, setOf(address))
        }.getOrNull()?.takeIf { it > 0L }
    }
}
