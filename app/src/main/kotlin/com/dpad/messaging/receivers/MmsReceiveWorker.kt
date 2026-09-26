package com.dpad.messaging.receivers

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dpad.messaging.App
import com.dpad.messaging.BuildConfig
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.helpers.MessageCache
import com.dpad.messaging.helpers.MmsHelper
import com.dpad.messaging.helpers.MmsPartCache
import com.dpad.messaging.helpers.NotificationHelper
import com.dpad.messaging.helpers.SmsWhitelistManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus

/** Durable post-download processing for platform-retrieved MMS messages. */
class MmsReceiveWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val requestedId = inputData.getLong(KEY_MESSAGE_ID, -1L)
        val msgId = if (requestedId > 0L) requestedId else findNewestFinalMms()
        if (msgId <= 0L) {
            return@withContext if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }

        val row = queryFinalRow(msgId)
        if (row == null) {
            return@withContext if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }

        runCatching {
            processRow(msgId, row)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                if (BuildConfig.DEBUG) {
                    android.util.Log.e(TAG, "MMS processing failed for $msgId", error)
                }
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }
        )
    }

    private suspend fun processRow(msgId: Long, row: MmsRow) {
        // Older builds used the synthetic Long.MAX_VALUE thread for the
        // notification-ind placeholder. Remove that stale notification when
        // the corresponding final MMS becomes available.
        NotificationHelper.cancelNotification(applicationContext, Long.MAX_VALUE.toInt())

        val from = getFromAddress(msgId)
        val filterResult = SmsWhitelistManager.check(applicationContext, from)
        if (!filterResult.allowed) {
            applicationContext.contentResolver.delete("content://mms/$msgId".toUri(), null, null)
            refresh(row.threadId)
            return
        }

        MmsPartCache.remove(msgId)
        val body = MmsHelper.getMmsDisplayBody(applicationContext, msgId, row.subject)
        val blockedNumbers = App.get().database.blockedNumbersDao().getAll()
        val blockedKeywords = App.get().database.blockedKeywordsDao().getAll()
        val fromDigits = from.filter { it.isDigit() }
        val blockedByNumber = blockedNumbers.any { it.number == from || it.number.filter(Char::isDigit) == fromDigits }
        val blockedByKeyword = blockedKeywords.any { body.contains(it.keyword, ignoreCase = true) }

        if (!blockedByNumber && !blockedByKeyword && from.isNotBlank()) {
            val senderName = App.get().contactHelper.getDisplayName(from)
            NotificationHelper.showIncomingNotification(
                applicationContext,
                row.threadId,
                senderName,
                from,
                body
            )
        }

        MessageCache.invalidate(row.threadId)
        refresh(row.threadId)
        if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "processed msgId=$msgId threadId=${row.threadId} from=$from")
        }
    }

    private fun refresh(threadId: Long) {
        EventBus.getDefault().post(RefreshConversations())
        EventBus.getDefault().post(RefreshMessages(threadId))
    }

    private fun queryFinalRow(msgId: Long): MmsRow? {
        return applicationContext.contentResolver.query(
            Uri.parse("content://mms/$msgId"),
            arrayOf("thread_id", "m_type", "msg_box", "sub"),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val threadId = cursor.getLong(0)
            val type = cursor.getInt(1)
            val box = cursor.getInt(2)
            if (type != MMS_RETRIEVE_CONF || box != MMS_INBOX || threadId <= 0L || threadId == Long.MAX_VALUE) {
                null
            } else {
                MmsRow(threadId, cursor.getString(3).orEmpty())
            }
        }
    }

    private fun findNewestFinalMms(): Long {
        val cutoff = (System.currentTimeMillis() - FALLBACK_WINDOW_MS) / 1000L
        return applicationContext.contentResolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id"),
            "m_type = ? AND msg_box = ? AND thread_id > 0 AND thread_id != ? AND date > ?",
            arrayOf(MMS_RETRIEVE_CONF.toString(), MMS_INBOX.toString(), Long.MAX_VALUE.toString(), cutoff.toString()),
            "date DESC"
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L } ?: -1L
    }

    private fun getFromAddress(msgId: Long): String {
        return applicationContext.contentResolver.query(
            Uri.parse("content://mms/$msgId/addr"),
            arrayOf("address"),
            "type = ?",
            arrayOf(MMS_FROM_TYPE.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty().takeUnless { it == "insert-address-token" }.orEmpty()
            else ""
        } ?: ""
    }

    data class MmsRow(val threadId: Long, val subject: String)

    companion object {
        private const val TAG = "DPAD_MSG"
        private const val KEY_MESSAGE_ID = "message_id"
        private const val MAX_ATTEMPTS = 3
        private const val FALLBACK_WINDOW_MS = 180_000L
        private const val MMS_INBOX = 1
        private const val MMS_RETRIEVE_CONF = 132
        private const val MMS_FROM_TYPE = 137

        fun enqueue(context: Context, messageId: Long) {
            val request = OneTimeWorkRequestBuilder<MmsReceiveWorker>()
                .setInputData(workDataOf(KEY_MESSAGE_ID to messageId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "mms-receive-$messageId",
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun enqueueFallback(context: Context) {
            val request = OneTimeWorkRequestBuilder<MmsReceiveWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
