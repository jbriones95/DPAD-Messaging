package com.dpad.messaging.receivers

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.dpad.messaging.App
import com.dpad.messaging.BuildConfig
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.helpers.SmsWhitelistManager
import com.dpad.messaging.helpers.AppCoroutineScopes
import com.dpad.messaging.helpers.NotificationHelper
import com.dpad.messaging.helpers.PhoneNumberMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus

/**
 * Receives SMS_DELIVER when the app is the default SMS handler.
 *
 * Responsibilities:
 *  1. Parse all PDUs and concatenate multi-part messages (on-thread, before return).
 *  2. [IO coroutine] Resolve / create the thread ID via the Telephony CP.
 *  3. [IO coroutine] Insert the assembled message into the system Telephony Sms CP.
 *  4. [IO coroutine] Skip notification if any blocked keyword matches the body.
 *  5. [IO coroutine] Show a heads-up notification with Reply + Mark-as-Read actions.
 *  6. [IO coroutine] Fire EventBus events so the conversation list and thread refresh.
 *
 * Uses goAsync() so the coroutine can outlive the BroadcastReceiver's normal 10 s window.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // ── Parse PDUs synchronously before onReceive() returns ───────────────
        val bundle = intent.extras ?: return

        // Bundle.get("pdus") historically returned an Array of ByteArray. On newer
        // API levels the type can vary, so normalize safely to a List<ByteArray>.
        val rawPdus = bundle.get("pdus")
        val pdusList: List<ByteArray> = when (rawPdus) {
            is Array<*> -> rawPdus.mapNotNull { it as? ByteArray }
            is java.util.ArrayList<*> -> rawPdus.mapNotNull { it as? ByteArray }
            else -> emptyList()
        }
        if (pdusList.isEmpty()) return

        val format = bundle.getString("format") ?: "3gpp"
        val smsMessages = pdusList.mapNotNull { pdu ->
            try { SmsMessage.createFromPdu(pdu, format) } catch (_: Exception) { null }
        }
        if (smsMessages.isEmpty()) return

        val address = smsMessages.first().displayOriginatingAddress ?: return
        val body = smsMessages.joinToString("") { it.displayMessageBody ?: "" }
        val timestamp = smsMessages.first().timestampMillis

        // ── Hand off to IO coroutine; keep the receiver alive via PendingResult ─
        val pendingResult = goAsync()
        AppCoroutineScopes.io.launch {
            try {
                runCatching { com.dpad.messaging.dumber.DumberVerification.receive(context, body, timestamp, com.dpad.messaging.dumber.DumberSubscriptions.receivedSubscription(intent)) }
                processMessage(context, address, body, timestamp)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Resolves the Telephony thread id for [address].
     *
     * Primary path is the fast canonical-address lookup. If that throws on a
     * flaky ROM, we fall back to scanning existing SMS rows for the address so
     * an incoming message is NEVER silently dropped on the thread-resolution
     * path. Last resort is a stable synthetic thread id derived from the
     * address, which keeps the message visible in its own thread.
     */
    private suspend fun resolveThreadId(context: Context, address: String): Long {
        try {
            val newId = Telephony.Threads.getOrCreateThreadId(context, address)
            if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "SmsReceiver: getOrCreateThreadId for '$address' -> $newId")
            return newId
        } catch (e: Exception) {
            Log.e("DPAD_MSG", "SmsReceiver: getOrCreateThreadId failed for '$address'", e)
        }

        // Fallback: find the most recent existing thread for this address.
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID),
                "${Telephony.Sms.ADDRESS} = ?",
                arrayOf(address),
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(Telephony.Sms.THREAD_ID)
                    if (index >= 0 && !cursor.isNull(index)) {
                        val existing = cursor.getLong(index)
                        if (existing > 0) {
                            Log.w("DPAD_MSG", "SmsReceiver: fallback thread lookup for '$address' -> $existing")
                            return existing
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DPAD_MSG", "SmsReceiver: fallback thread lookup failed for '$address'", e)
        }

        // Last resort: stable synthetic thread so the message is never lost.
        val synthetic = 100_000_000L + (address.hashCode().toLong() and 0x3FFF_FFFFL)
        Log.w("DPAD_MSG", "SmsReceiver: synthetic thread id for '$address' -> $synthetic")
        return synthetic
    }

    private suspend fun processMessage(
        context: Context,
        address: String,
        body: String,
        timestamp: Long
    ) {
        Log.d("DPAD_MSG", "SmsReceiver.processMessage() address=$address")

        // ── MDM hard-filter — must run first, before any thread/insert side effects ──
        val filterResult = try {
            SmsWhitelistManager.check(context, address)
        } catch (e: Exception) {
            // Keep the policy fail-closed, but make an exceptional drop visible
            // in release logs instead of silently losing the delivery broadcast.
            Log.e("DPAD_MSG", "SmsReceiver: MDM sender check failed; dropping '$address'", e)
            return
        }
        if (!filterResult.allowed) {
            // Always-on (not debug-gated) so missing-SMS reports can be diagnosed
            // from a release-build logcat.
            Log.e("DPAD_MSG", "SmsReceiver: DROPPED message from $address — ${filterResult.reason}")
            return
        }
        // ───────────────────────────────────────────────────────────────────────────

        val threadId = resolveThreadId(context, address)
        if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "SmsReceiver: resolved threadId=$threadId")

        // Insert into Telephony Sms CP so every SMS reader app can see it.
        val cv = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, timestamp)
            put(Telephony.Sms.DATE_SENT, timestamp)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            put(Telephony.Sms.THREAD_ID, threadId)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
        }
        try {
            val insertedUri = context.contentResolver.insert(Telephony.Sms.CONTENT_URI, cv)
            if (BuildConfig.DEBUG) Log.d("DPAD_MSG", "SmsReceiver: inserted SMS row -> $insertedUri")
        } catch (e: Exception) {
            // E-mail-permissioned apps can see the row even when this insert fails, so
            // never drop silently — log loudly and keep the notification/refresh flow.
            Log.e("DPAD_MSG", "SmsReceiver: insert failed for '$address'", e)
            e.printStackTrace()
        }

        // Publish provider changes before optional database/contact/notification work.
        // A slow contacts provider must not hold the visible conversation hostage.
        com.dpad.messaging.helpers.MessageCache.invalidate(threadId)
        EventBus.getDefault().post(RefreshConversations())
        EventBus.getDefault().post(RefreshMessages(threadId))

        // ── Local soft-filter (upstream blocklist + keywords) — suppress notification only ──
        val isBlocked = try {
            val keywords = App.get().database.blockedKeywordsDao().getAll()
            val blockedNumbers = App.get().database.blockedNumbersDao().getAll()
            blockedNumbers.any { bn -> PhoneNumberMatcher.equivalent(bn.number, address) } ||
                keywords.any { kw -> body.contains(kw.keyword, ignoreCase = true) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // A local filter database problem must not silently suppress a new
            // message alert; only a confirmed match should block notification.
            Log.e("DPAD_MSG", "SmsReceiver: could not check local notification filters", e)
            false
        }

        if (!isBlocked) {
            val senderName = try {
                App.get().contactHelper.getDisplayName(address)
            } catch (e: Exception) {
                Log.w("DPAD_MSG", "SmsReceiver: contact lookup failed for '$address'", e)
                address
            }
            try {
                NotificationHelper.showIncomingNotification(context, threadId, senderName, address, body)
            } catch (e: Exception) {
                Log.e("DPAD_MSG", "SmsReceiver: could not post incoming notification", e)
            }
        }

    }

}
