package com.dpad.messaging.receivers

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import androidx.core.net.toUri
import com.dpad.messaging.App
import com.dpad.messaging.BuildConfig
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.helpers.AppCoroutineScopes
import com.dpad.messaging.helpers.MmsHelper
import com.dpad.messaging.helpers.NotificationHelper
import com.dpad.messaging.helpers.SmsWhitelistManager
import com.klinker.android.send_message.MmsReceivedReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus

/**
 * Handles MMS download-complete callbacks from Klinker DownloadManager.
 *
 * This is the concrete receiver required by BroadcastUtils routing
 * (taskAffinity == MmsReceivedReceiver.MMS_RECEIVED).
 */
class LibraryMmsReceivedReceiver : MmsReceivedReceiver() {

    override fun onMessageReceived(context: Context, messageUri: Uri) {
        val msgId = messageUri.lastPathSegment?.toLongOrNull() ?: run {
            if (BuildConfig.DEBUG) Log.w(TAG, "LibraryMmsReceivedReceiver: invalid messageUri=$messageUri")
            EventBus.getDefault().post(RefreshConversations())
            return
        }

        val (threadId, subject) = queryThreadAndSubject(context, msgId)
        val from = getMmsFromAddress(context, msgId)

        val filterResult = SmsWhitelistManager.check(context, from)
        if (!filterResult.allowed) {
            Log.i(TAG, "LibraryMmsReceivedReceiver: dropped MMS from $from - ${filterResult.reason}")
            runCatching { context.contentResolver.delete("content://mms/$msgId".toUri(), null, null) }
            EventBus.getDefault().post(RefreshConversations())
            return
        }

        val body = MmsHelper.getMmsDisplayBody(context, msgId, subject)

        // Room DAO calls are suspend — run on IO dispatcher
        val blockedNumbers = runBlocking(Dispatchers.IO) {
            App.get().database.blockedNumbersDao().getAll()
        }
        val blockedKeywords = runBlocking(Dispatchers.IO) {
            App.get().database.blockedKeywordsDao().getAll()
        }

        val fromDigits = from.filter { it.isDigit() }
        val isBlockedByNumber = blockedNumbers.any { bn ->
            val digits = bn.number.filter { it.isDigit() }
            bn.number == from || digits == fromDigits
        }
        val isBlockedByKeyword = blockedKeywords.any { kw ->
            body.contains(kw.keyword, ignoreCase = true)
        }

        if (!isBlockedByNumber && !isBlockedByKeyword && from.isNotBlank()) {
            val senderName = App.get().contactHelper.getDisplayName(from)
            NotificationHelper.showIncomingNotification(context, threadId, senderName, from, body)
        }

        EventBus.getDefault().post(RefreshConversations())
        if (threadId > 0L) {
            EventBus.getDefault().post(RefreshMessages(threadId))
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "LibraryMmsReceivedReceiver: processed msgId=$msgId threadId=$threadId from=$from")
        }
    }

    override fun onError(context: Context, error: String) {
        Log.e(TAG, "LibraryMmsReceivedReceiver error: $error")
        EventBus.getDefault().post(RefreshConversations())
    }

    private fun queryPreferredApnValue(column: String): String {
        val context = App.get()
        val projection = arrayOf(column, Telephony.Carriers.TYPE, Telephony.Carriers.CURRENT)
        val selection = "${Telephony.Carriers.CURRENT}=1"
        val orderBy = "_id DESC"

        return runCatching {
            context.contentResolver.query(
                Telephony.Carriers.CONTENT_URI,
                projection,
                selection,
                null,
                orderBy
            )?.use { cursor ->
                val colIdx = cursor.getColumnIndex(column)
                val typeIdx = cursor.getColumnIndex(Telephony.Carriers.TYPE)
                while (cursor.moveToNext()) {
                    val type = if (typeIdx >= 0) cursor.getString(typeIdx).orEmpty() else ""
                    if (type.contains("mms", ignoreCase = true) || type == "*") {
                        if (colIdx >= 0) {
                            return@runCatching cursor.getString(colIdx).orEmpty().trim()
                        }
                    }
                }
                ""
            } ?: ""
        }.getOrElse {
            ""
        }
    }

    private fun queryThreadAndSubject(context: Context, msgId: Long): Pair<Long, String> {
        return runCatching {
            context.contentResolver.query(
                "content://mms/$msgId".toUri(),
                arrayOf("thread_id", "sub"),
                null,
                null,
                null
            )?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) {
                    val threadId = cursor.getLong(0)
                    val subject = cursor.getString(1).orEmpty()
                    return@runCatching threadId to subject
                }
                -1L to ""
            } ?: (-1L to "")
        }.getOrDefault(-1L to "")
    }

    private fun getMmsFromAddress(context: Context, msgId: Long): String {
        return runCatching {
            context.contentResolver.query(
                "content://mms/$msgId/addr".toUri(),
                arrayOf("address"),
                "type = ?",
                arrayOf("137"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val addr = cursor.getString(0).orEmpty()
                    if (addr.isNotBlank() && addr != "insert-address-token") {
                        return@runCatching addr
                    }
                }
                ""
            } ?: ""
        }.getOrDefault("")
    }

    companion object {
        private const val TAG = "DPAD_MSG"
    }
}
