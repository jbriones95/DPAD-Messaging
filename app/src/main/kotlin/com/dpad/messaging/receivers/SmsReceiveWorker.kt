package com.dpad.messaging.receivers

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.dpad.messaging.App
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.helpers.MessageCache
import com.dpad.messaging.helpers.NotificationHelper
import com.dpad.messaging.helpers.PhoneNumberMatcher
import com.dpad.messaging.helpers.Prefs
import com.dpad.messaging.helpers.ReactionParser
import com.dpad.messaging.helpers.SmsWhitelistManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus

/**
 * Durable SMS_DELIVER processing. Parsing stays in the manifest receiver so the
 * PDU is not lost; provider writes and notifications survive OEM process/background
 * restrictions through WorkManager.
 */
class SmsReceiveWorker(
    appContext: Context,
    params: androidx.work.WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val address = inputData.getString(KEY_ADDRESS).orEmpty()
        val body = inputData.getString(KEY_BODY).orEmpty()
        val timestamp = inputData.getLong(KEY_TIMESTAMP, System.currentTimeMillis())
        if (address.isBlank()) {
            return@withContext Result.failure()
        }

        val filterResult = try {
            SmsWhitelistManager.check(applicationContext, address)
        } catch (error: Exception) {
            Log.e(TAG, "MDM sender check failed; dropping SMS from $address", error)
            return@withContext Result.failure()
        }
        if (!filterResult.allowed) {
            Log.e(TAG, "DROPPED SMS from $address — ${filterResult.reason}")
            return@withContext Result.success()
        }

        val stored = try {
            persistMessage(address, body, timestamp, resolveThreadId(address))
        } catch (error: Exception) {
            Log.e(TAG, "SMS provider write failed for $address", error)
            return@withContext if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
        val threadId = stored.threadId

        // A provider may have committed the row before returning null, or another
        // SMS app may have written it. Refresh the open thread in every case.
        MessageCache.invalidate(threadId)
        EventBus.getDefault().post(RefreshConversations())
        EventBus.getDefault().post(RefreshMessages(threadId))

        runCatching {
            com.dpad.messaging.dumber.DumberVerification.receive(
                applicationContext,
                body,
                timestamp,
                inputData.getInt(KEY_SUBSCRIPTION_ID, -1)
            )
        }
        if (!stored.inserted) return@withContext Result.success()

        val blockedNumbers = runCatching { App.get().database.blockedNumbersDao().getAll() }.getOrNull()
        val blockedKeywords = runCatching { App.get().database.blockedKeywordsDao().getAll() }.getOrNull()
        if (blockedNumbers == null || blockedKeywords == null) {
            // The provider row is already durable. A filter read failure must not
            // cause another insert; suppress the alert conservatively instead.
            return@withContext Result.success()
        }
        val blocked = blockedNumbers.any { PhoneNumberMatcher.equivalent(it.number, address) } ||
            blockedKeywords.any { body.contains(it.keyword, ignoreCase = true) }
        if (blocked) return@withContext Result.success()

        val senderName = runCatching {
            App.get().contactHelper.getDisplayName(address)
        }.getOrDefault(address)
        runCatching {
            NotificationHelper.showIncomingNotification(
                applicationContext,
                threadId,
                senderName,
                address,
                if (Prefs.get().convertReactions) ReactionParser.displayText(body) else body
            )
        }
        Result.success()
    }

    private data class StoredSms(
        val id: Long,
        val threadId: Long,
        val body: String,
        val inserted: Boolean
    )

    private fun persistMessage(
        address: String,
        body: String,
        timestamp: Long,
        threadId: Long
    ): StoredSms {
        findStoredSms(address, body, timestamp, threadId)?.let { existing ->
            return repairStoredBody(existing, body, inserted = false)
        }

        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, timestamp)
            put(Telephony.Sms.DATE_SENT, timestamp)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            put(Telephony.Sms.THREAD_ID, threadId)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
        }
        val insertedUri = applicationContext.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
        if (insertedUri != null) {
            readStoredSms(insertedUri)?.let { return repairStoredBody(it, body, inserted = true) }
        }

        // Some OEM providers commit and then return null. Read back before retrying
        // so WorkManager cannot create a duplicate on its next attempt.
        findStoredSms(address, body, timestamp, threadId)?.let {
            return repairStoredBody(it, body, inserted = false)
        }
        error("Telephony provider did not store incoming SMS")
    }

    private fun findStoredSms(
        address: String,
        body: String,
        timestamp: Long,
        threadId: Long
    ): StoredSms? {
        val rows = mutableListOf<StoredSms>()
        applicationContext.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.BODY),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.DATE} = ? AND ${Telephony.Sms.ADDRESS} = ?",
            arrayOf(threadId.toString(), timestamp.toString(), address),
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
        return rows.firstOrNull { it.body == body }
            ?: rows.firstOrNull { it.body.isBlank() && body.isNotBlank() }
            ?: rows.firstOrNull()
    }

    private fun readStoredSms(uri: Uri): StoredSms? {
        return applicationContext.contentResolver.query(
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
            StoredSms(
                id = cursor.getLong(idIndex),
                threadId = cursor.getLong(threadIndex),
                body = if (bodyIndex >= 0) cursor.getString(bodyIndex).orEmpty() else "",
                inserted = true
            )
        }
    }

    private fun repairStoredBody(stored: StoredSms, body: String, inserted: Boolean): StoredSms {
        if (body.isBlank() || stored.body.isNotBlank()) return stored.copy(inserted = inserted)
        val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, stored.id)
        val values = ContentValues().apply { put(Telephony.Sms.BODY, body) }
        applicationContext.contentResolver.update(uri, values, null, null)
        val repaired = readStoredSms(uri) ?: stored
        if (repaired.body.isBlank()) error("Telephony provider stored a blank SMS body")
        return repaired.copy(inserted = inserted)
    }

    private fun resolveThreadId(address: String): Long {
        runCatching { Telephony.Threads.getOrCreateThreadId(applicationContext, address) }
            .getOrNull()
            ?.takeIf { it > 0L }
            ?.let { return it }

        return runCatching {
            applicationContext.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID),
                "${Telephony.Sms.ADDRESS} = ?",
                arrayOf(address),
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it > 0L } else null
            }
        }.getOrNull() ?: runCatching {
            // Let strict OEM providers allocate/validate the real thread ID.
            Telephony.Threads.getOrCreateThreadId(applicationContext, setOf(address))
        }.getOrNull()?.takeIf { it > 0L } ?: error("Unable to resolve SMS thread")
    }

    companion object {
        private const val TAG = "DPAD_MSG"
        private const val MAX_ATTEMPTS = 5
        private const val KEY_ADDRESS = "address"
        private const val KEY_BODY = "body"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_SUBSCRIPTION_ID = "subscription_id"
        private const val KEY_DELIVERY_ID = "delivery_id"

        fun enqueue(
            context: Context,
            address: String,
            body: String,
            timestamp: Long,
            subscriptionId: Int,
            deliveryId: String = java.util.UUID.randomUUID().toString()
        ): androidx.work.Operation {
            val request = OneTimeWorkRequestBuilder<SmsReceiveWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(
                    workDataOf(
                        KEY_ADDRESS to address,
                        KEY_BODY to body,
                        KEY_TIMESTAMP to timestamp,
                        KEY_SUBSCRIPTION_ID to subscriptionId,
                        KEY_DELIVERY_ID to deliveryId
                    )
                )
                .build()
            return WorkManager.getInstance(context).enqueueUniqueWork(
                "sms-receive-$deliveryId",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
