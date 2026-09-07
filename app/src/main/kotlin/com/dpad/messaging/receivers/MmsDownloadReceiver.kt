package com.dpad.messaging.receivers

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import com.dpad.messaging.App
import com.dpad.messaging.BuildConfig
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.helpers.AppCoroutineScopes
import com.dpad.messaging.helpers.MmsHelper
import com.dpad.messaging.helpers.NotificationHelper
import com.dpad.messaging.helpers.SmsWhitelistManager
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import androidx.core.net.toUri


/**
 * Receives the result PendingIntent from SmsManager.downloadMultimediaMessage().
 *
 * On RESULT_OK the MMS content has been written to content://mms by the system.
 * We query for the new row, resolve the sender, show a notification, and fire
 * EventBus refresh events so ThreadActivity and MainActivity update.
 */
class MmsDownloadReceiver : BroadcastReceiver() {

    private class MmsRowObserver(
        handler: Handler,
        private val onChanged: () -> Unit
    ) : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            onChanged()
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            onChanged()
        }
    }

    private inline fun d(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d("DPAD_MSG", message())
    }

    private inline fun w(message: () -> String) {
        if (BuildConfig.DEBUG) Log.w("DPAD_MSG", message())
    }

    private inline fun w(message: () -> String, t: Throwable) {
        if (BuildConfig.DEBUG) Log.w("DPAD_MSG", message(), t)
    }

    private inline fun e(message: () -> String, t: Throwable) {
        if (BuildConfig.DEBUG) Log.e("DPAD_MSG", message(), t)
    }

    companion object {
        const val ACTION       = "com.dpad.messaging.MMS_DOWNLOADED"
        const val EXTRA_MMS_ID = "extra_mms_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val result = resultCode
        val msgId  = intent.getLongExtra(EXTRA_MMS_ID, -1L)
        d { "MmsDownloadReceiver.onReceive() resultCode=$result msgId=$msgId" }

        if (result != Activity.RESULT_OK) {
            w { "MmsDownloadReceiver: download failed with resultCode=$result" }
            // Delete the placeholder row so it doesn't linger as a ghost entry.
            if (msgId > 0L) {
                try {
                    context.contentResolver.delete(Uri.parse("content://mms/$msgId"), null, null)
                    d { "MmsDownloadReceiver: deleted placeholder row msgId=$msgId" }
                } catch (e: Exception) {
                    w({ "MmsDownloadReceiver: could not delete placeholder row" }, e)
                }
            }
            EventBus.getDefault().post(RefreshConversations())
            return
        }

        val pendingResult = goAsync()
        AppCoroutineScopes.io.launch {
            try {
                processDownloadedMms(context, msgId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processDownloadedMms(context: Context, knownMsgId: Long) {
        val changeSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
        val observer = MmsRowObserver(Handler(Looper.getMainLooper())) {
            if (!changeSignal.isCompleted) changeSignal.complete(Unit)
        }
        context.contentResolver.registerContentObserver(Uri.parse("content://mms"), true, observer)
        try {
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(1500L) { changeSignal.await() }
            }

            val mmsUri = Uri.parse("content://mms")
            val proj   = arrayOf("_id", "thread_id", "date", "sub", "m_type", "msg_box")

            // ── Diagnostic dump of all recent rows (debug only) ─────────────────
            val cutoffSecs = System.currentTimeMillis() / 1000L - 120L
            if (BuildConfig.DEBUG) {
                try {
                    context.contentResolver.query(
                        mmsUri, proj, "date > ?", arrayOf(cutoffSecs.toString()), "date DESC"
                    )?.use { cursor ->
                        d { "MmsDownloadReceiver: ${cursor.count} recent MMS row(s) in provider:" }
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
                            val tid = cursor.getLong(cursor.getColumnIndexOrThrow("thread_id"))
                            val mType = cursor.getInt(cursor.getColumnIndexOrThrow("m_type"))
                            val box = cursor.getInt(cursor.getColumnIndexOrThrow("msg_box"))
                            val label = when (mType) {
                                130 -> "M-Notification-Ind"
                                132 -> "M-Retrieve-Conf(DOWNLOADED)"
                                else -> "type=$mType"
                            }
                            d { "  _id=$id thread_id=$tid msg_box=$box m_type=$label" }
                        }
                    }
                } catch (e: Exception) {
                    e({ "MmsDownloadReceiver: diagnostic query failed" }, e)
                }
            }

            var msgId = -1L
            var threadId = -1L
            var subject = ""

            if (knownMsgId > 0L) {
                try {
                    for (attempt in 0 until 4) {
                        var found = false
                        context.contentResolver.query(
                            Uri.parse("content://mms/$knownMsgId"),
                            proj,
                            null,
                            null,
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                msgId = cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
                                threadId = cursor.getLong(cursor.getColumnIndexOrThrow("thread_id"))
                                subject = cursor.getString(cursor.getColumnIndexOrThrow("sub")) ?: ""
                                val mType = cursor.getInt(cursor.getColumnIndexOrThrow("m_type"))
                                val box = cursor.getInt(cursor.getColumnIndexOrThrow("msg_box"))
                                d { "MmsDownloadReceiver: direct query msgId=$msgId mType=$mType msg_box=$box threadId=$threadId" }

                                if (box != 1) {
                                    try {
                                        val cv = ContentValues().apply { put("msg_box", 1) }
                                        context.contentResolver.update(Uri.parse("content://mms/$msgId"), cv, null, null)
                                        d { "MmsDownloadReceiver: corrected msg_box $box -> 1" }
                                    } catch (e: Exception) {
                                        w({ "MmsDownloadReceiver: could not correct msg_box" }, e)
                                    }
                                }
                                found = true
                            }
                        }

                        if (found) break
                        if (attempt < 3) kotlinx.coroutines.delay(300L)
                    }
                } catch (e: Exception) {
                    e({ "MmsDownloadReceiver: direct row query failed" }, e)
                }
            }

            if (msgId < 0L) {
                w { "MmsDownloadReceiver: direct query missed - falling back to date scan" }
                try {
                    context.contentResolver.query(
                        mmsUri,
                        proj,
                        "(m_type = 132 OR m_type = 130) AND date > ?",
                        arrayOf(cutoffSecs.toString()),
                        "date DESC"
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            msgId = cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
                            threadId = cursor.getLong(cursor.getColumnIndexOrThrow("thread_id"))
                            subject = cursor.getString(cursor.getColumnIndexOrThrow("sub")) ?: ""
                            d { "MmsDownloadReceiver: fallback found msgId=$msgId threadId=$threadId" }
                        }
                    }
                } catch (e: Exception) {
                    e({ "MmsDownloadReceiver: fallback query failed" }, e)
                }
            }

            if (msgId < 0L) {
                w { "MmsDownloadReceiver: still no MMS row - posting RefreshConversations only" }
                EventBus.getDefault().post(RefreshConversations())
                return
            }

            if (BuildConfig.DEBUG) {
                try {
                    context.contentResolver.query(
                        "content://mms/$msgId/part".toUri(),
                        arrayOf("_id", "ct", "cl"),
                        null,
                        null,
                        null
                    )?.use { c ->
                        d { "MmsDownloadReceiver: parts for msgId=$msgId count=${c.count}" }
                        while (c.moveToNext()) {
                            d { "  part _id=${c.getLong(0)} ct='${c.getString(1)}' cl='${c.getString(2)}'" }
                        }
                    }
                } catch (e: Exception) {
                    e({ "MmsDownloadReceiver: parts query failed" }, e)
                }
            }

            val address = getMmsFromAddress(context, msgId)
            val body = MmsHelper.getMmsDisplayBody(context, msgId, subject)
            d { "MmsDownloadReceiver: address='$address' body='${body.take(40)}'" }

            val resolvedThreadId = resolveThreadId(context, address, threadId)
            if (resolvedThreadId != threadId && resolvedThreadId > 0L) {
                try {
                    val cv = ContentValues().apply { put(Telephony.Mms.THREAD_ID, resolvedThreadId) }
                    context.contentResolver.update(Uri.withAppendedPath(mmsUri, msgId.toString()), cv, null, null)
                    d { "MmsDownloadReceiver: corrected thread_id $threadId -> $resolvedThreadId" }
                    threadId = resolvedThreadId
                } catch (e: Exception) {
                    w({ "MmsDownloadReceiver: could not correct thread_id" }, e)
                }
            }

            // Replace the existing keyword-check block with a whitelist check added.
            val filterResult = SmsWhitelistManager.check(context, address)
            if (!filterResult.allowed) {
                Log.i("DPAD_MSG", "MmsDownloadReceiver: dropped MMS from $address — ${filterResult.reason}")
                // optionally delete the placeholder row
                runCatching { context.contentResolver.delete(Uri.withAppendedPath(mmsUri, msgId.toString()), null, null) }
                return
            }

            val keywords = App.get().database.blockedKeywordsDao().getAll()
            val blockedNumbers = App.get().database.blockedNumbersDao().getAll()
            val normalizedAddrDigits = address.filter { it.isDigit() }

            val isBlockedByNumber = blockedNumbers.any { bn ->
                val ndigits = bn.number.filter { it.isDigit() }
                bn.number == address || ndigits == normalizedAddrDigits
            }

            val isBlockedByKeyword = keywords.any { kw ->
                body.contains(kw.keyword, ignoreCase = true)
            }

            val isBlocked = isBlockedByNumber || isBlockedByKeyword

            if (!isBlocked && address.isNotBlank()) {
                val senderName = App.get().contactHelper.getDisplayName(address)
                NotificationHelper.showIncomingNotification(context, threadId, senderName, address, body)
            }

            EventBus.getDefault().post(RefreshConversations())
            if (threadId > 0L) {
                EventBus.getDefault().post(RefreshMessages(threadId))
            }
        } finally {
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
    }

    /** Reads the FROM address for an MMS message (PduHeaders.FROM = type 137). */
    private fun getMmsFromAddress(context: Context, msgId: Long): String {
        val addrUri = "content://mms/$msgId/addr".toUri()
        try {
            context.contentResolver.query(
                addrUri, arrayOf("address"), "type = ?", arrayOf("137"), null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val addr = cursor.getString(0)
                    if (!addr.isNullOrBlank() && addr != "insert-address-token") return addr
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    /**
     * Attempts to find the correct thread_id for the given sender address.
     * Falls back to scanning the canonical-address table (same logic as SmsReceiver).
     */
    @SuppressLint("UseKtx")
    private fun resolveThreadId(context: Context, address: String, fallback: Long): Long {
        if (fallback > 0L) return fallback
        if (address.isBlank()) return fallback

        val digitsOnly = address.filter { it.isDigit() }
        val candidates = buildList {
            add(address)
            if (digitsOnly != address) add(digitsOnly)
            if (digitsOnly.startsWith("1") && digitsOnly.length > 10) add(digitsOnly.removePrefix("1"))
        }

        // Fast path: threadID URI
        for (cand in candidates) {
            try {
                val uri = Uri.withAppendedPath("content://mms-sms/threadID".toUri(), Uri.encode(cand))
                context.contentResolver.query(uri, arrayOf("_id"), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
            } catch (_: Exception) {}
        }

        // Fallback: scan canonical-address table
        val normalizedSet = candidates.map { it.filter { ch -> ch.isDigit() } }.toSet()
        try {
            val convUri = "content://mms-sms/conversations?simple=true".toUri()
            context.contentResolver.query(
                convUri, arrayOf(Telephony.Threads._ID, Telephony.Threads.RECIPIENT_IDS),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val tid = cursor.getLong(0)
                    val recipientIds = cursor.getString(1) ?: continue
                    recipientIds.trim().split(" ").mapNotNull { it.trim().toLongOrNull() }.forEach { cid ->
                        try {
                            context.contentResolver.query(
                                "content://mms-sms/canonical-address/$cid".toUri(),
                                arrayOf("address"), null, null, null
                            )?.use { c2 ->
                                if (c2.moveToFirst()) {
                                    val stored = c2.getString(0).orEmpty()
                                    if (stored.isNotBlank() && stored.filter { it.isDigit() } in normalizedSet) {
                                        return tid
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}

        return fallback
    }
}
