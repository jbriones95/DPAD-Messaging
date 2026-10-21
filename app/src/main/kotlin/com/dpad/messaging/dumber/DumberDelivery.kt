package com.dpad.messaging.dumber

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dpad.messaging.App
import com.dpad.messaging.R
import com.dpad.messaging.activities.ThreadActivity
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.helpers.LegacyUnifiedMessageSender
import com.dpad.messaging.helpers.LibraryUnifiedMessageSender
import com.dpad.messaging.helpers.Prefs
import com.dpad.messaging.helpers.SmsWhitelistManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.json.JSONObject

/** No directory calls and no automatic downgrade. A true result means handled, including queued. */
object DumberDelivery {
    private val lock = Mutex()
    suspend fun send(context: Context, phone: String, body: String, thread: Long, subscription: Int, scheduled: Long?): Boolean = withContext(Dispatchers.IO) {
        val sim = DumberSubscriptions.selected(context, subscription)
        check(sim != null || !DumberAccount.connected(context)) { "Select an available SIM and allow SIM access before sending." }
        val account = DumberAccount.forSim(context, sim)
        check(account != null || !DumberAccount.needsLegacyLink(context)) { "Link your existing dumber.chat account to a SIM in Settings before sending." }
        if (account == null) return@withContext false
        if (!DumberAccount.enabled(context, account)) return@withContext false
        val number = DumberCore.international(context, phone, subscription) ?: phone
        val status = DumberDirectory.cached(context, number, account = account)
        val route = deliveryRoute(true, status.peer, status.absent)
        if (route == DeliveryRoute.CARRIER) return@withContext false
        val pending = DumberOutbox.message(account, number, body, thread, sim!!.id, scheduled)
            .put("sim_label", sim.label)
            .put("recipient_unavailable", route == DeliveryRoute.RECIPIENT_UNAVAILABLE)
        status.peer?.let { pending.put("peer", it) }
        lock.withLock {
            DumberOutbox(context).use { if (it.get(pending.getString("id")) == null) it.save(pending) }
        }
        // Ownership transfers to the durable outbox before removing the scheduled row.
        if (scheduled != null) runCatching { App.get().database.messagesDao().deleteMessage(scheduled) }
        retry(context, pending.getString("id"))
        true
    }
    suspend fun retry(context: Context, id: String, send: suspend (Context, JSONObject) -> Unit = { c, p -> DumberMessages.sendKnown(c, p) }): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            DumberOutbox(context).use { queue ->
                val pending = queue.get(id) ?: return@withLock true
                if (pending.optBoolean("confirmed")) {
                    runCatching { queue.delete(id); cancelNotification(context, id); changed(pending) }
                    return@withLock true
                }
                try {
                    check(DumberAccount.enabled(context, pending.getString("account"))) { "Reconnect the original dumber.chat account to retry" }
                    if (!pending.has("peer")) {
                        val peer = DumberDirectory.cached(context, pending.getString("phone"), account = pending.getString("account")).peer
                        check(peer != null) { "Encrypted availability is unknown. Open the conversation to check it, or choose SMS." }
                        pending.put("peer", peer); queue.save(pending)
                    }
                    send(context, pending)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (!pending.optBoolean("confirmed")) {
                        if (e is DumberException && e.recipientUnavailable) {
                            pending.put("recipient_unavailable", true)
                            DumberDirectory.remember(context, pending.getString("account"), pending.getString("phone"), null)
                        }
                        pending.put("reason", e.message ?: "Encrypted send failed")
                        queue.save(pending)
                        notifyPending(context, pending)
                        changed(pending)
                        return@withLock false
                    }
                }
                // Delivery is confirmed; local cleanup must not turn it back into an unsent draft.
                runCatching { queue.delete(id) }
                runCatching { cancelNotification(context, id); changed(pending) }
                true
            }
        }
    }
    /** Called only after the user explicitly confirms the unencrypted transport. */
    suspend fun sendSms(context: Context, id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            DumberOutbox(context).use { queue ->
                val pending = queue.get(id) ?: return@withLock
                check(pending.getString("account") == DumberAccount.forSubscription(context, pending.getInt("subscription"))) { "The original SIM is unavailable or its account changed. Reconnect it before sending SMS." }
                if (!pending.optBoolean("confirmed")) {
                    check(SmsWhitelistManager.check(context, pending.getString("phone")).allowed) { "Message blocked by policy" }
                    val carrier = if (Prefs.get().useLibrarySmsSending) LibraryUnifiedMessageSender else LegacyUnifiedMessageSender
                    carrier.sendSms(context, pending.getString("phone"), pending.getString("body"), pending.getLong("thread"), pending.getInt("subscription"))
                    pending.put("confirmed", true)
                    runCatching { queue.save(pending) }
                }
                runCatching { queue.delete(id) }
                runCatching { cancelNotification(context, id); changed(pending) }
            }
        }
    }
    suspend fun discard(context: Context, id: String) = withContext(Dispatchers.IO) {
        lock.withLock { DumberOutbox(context).use { queue -> queue.get(id)?.let { queue.delete(id); cancelNotification(context, id); changed(it) } } }
    }
    private fun changed(pending: JSONObject) { EventBus.getDefault().post(RefreshMessages(pending.getLong("thread"))) }
    private fun cancelNotification(context: Context, id: String) { context.getSystemService(NotificationManager::class.java).cancel(id, 51001) }
    private fun notifyPending(context: Context, pending: JSONObject) {
        val id = pending.getString("id")
        val intent = Intent(context, ThreadActivity::class.java).apply {
            putExtra(ThreadActivity.EXTRA_THREAD_ID, pending.getLong("thread"))
            putExtra(ThreadActivity.EXTRA_PHONE_NUMBER, pending.getString("phone"))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = android.net.Uri.parse("dpad-pending://message/$id")
        }
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, App.CHANNEL_SEND_FAILURE)
            .setSmallIcon(R.drawable.ic_new_message).setContentTitle(context.getString(R.string.dumber_send_pending))
            .setContentText(context.getString(R.string.dumber_send_pending_detail)).setContentIntent(open).setAutoCancel(true).build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, 51001, notification) }
    }
}
