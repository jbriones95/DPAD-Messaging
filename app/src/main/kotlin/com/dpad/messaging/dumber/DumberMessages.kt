package com.dpad.messaging.dumber

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import com.dpad.messaging.App
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.helpers.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.json.JSONArray
import org.json.JSONObject

object DumberMessages {
    private val storeLock = Mutex()
    private val sendLock = Mutex()
    private val syncLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val phoneCache = java.util.concurrent.ConcurrentHashMap<String,String>()
    private val peerCache = java.util.concurrent.ConcurrentHashMap<String,String>()

    suspend fun sendKnown(context: Context, pending: JSONObject) = withContext(Dispatchers.IO) {
        val account = pending.getString("account")
        check(DumberAccount.enabled(context, account) && DumberCore.available()) { "Reconnect dumber.chat to retry encrypted delivery" }
        val recipient = pending.getString("phone")
        check(SmsWhitelistManager.check(context, recipient).allowed) { "Message blocked by policy" }
        val body = pending.getString("body")
        val threadId = pending.getLong("thread")
        val subscriptionId = pending.getInt("subscription")
        val scheduledId = if (pending.has("scheduled")) pending.getLong("scheduled") else null
        sendLock.withLock {
            check(DumberAccount.enabled(context, account)) { "Reconnect dumber.chat to retry encrypted delivery" }
            val request = DumberCore.request(context, "open_chat", account)
            val own = request.getJSONObject("session").getString("user_id")
            check(own == pending.getString("account")) { "Sign in to the original account to retry" }
            val found = pending.getString("peer")
            check(found != own) { "Choose another recipient" }
            val room = pending.optString("room").ifBlank {
                DumberCore.call(JSONObject(request.toString()).put("user_id",found)).getString("room_id").also {
                    pending.put("room", it)
                    DumberOutbox(context).use { queue -> queue.save(pending) }
                }
            }
            val previouslyUncertain = pending.optBoolean("uncertain")
            pending.put("uncertain", true)
            DumberOutbox(context).use { it.save(pending) }
            check(DumberAccount.enabled(context, account)) { "Reconnect dumber.chat to retry encrypted delivery" }
            val result = try {
                DumberCore.call(JSONObject(request.toString()).put("command", "send").put("room_id",room).put("body",body).put("transaction_id",pending.getString("id")))
            } catch (e: DumberException) {
                // A definite rejection proves only this attempt was not accepted.
                if (e.recipientUnavailable && !previouslyUncertain) pending.put("uncertain", false)
                throw e
            }
            // A successful response from this SDK confirms an encrypted Matrix event.
            check(result.optBoolean("e2ee")) { "Encryption confirmation missing" }
            pending.put("confirmed", true)
            runCatching { DumberOutbox(context).use { it.save(pending) } }
            val event = JSONObject().put("event_id",result.getString("event_id")).put("type","m.room.message")
                .put("sender",own).put("e2ee",true).put("origin_server_ts",System.currentTimeMillis())
                .put("content",JSONObject().put("msgtype","m.text").put("body",body))
            // From here on, never downgrade to SMS due to a local storage/notification error.
            runCatching {
                storeLock.withLock {
                    DumberInbox(context, account).use { queue ->
                        queue.enqueue(room,event)
                        store(context,queue,room,event,recipient,false,account,threadId,subscriptionId)
                    }
                }
            }
            if (scheduledId != null) runCatching { App.get().database.messagesDao().deleteMessage(scheduledId) }
            runCatching { publish(threadId) }
        }
    }

    suspend fun sync(context: Context, account: String = DumberDirectory.account(context), call: (JSONObject) -> JSONObject = DumberCore::call) = withContext(Dispatchers.IO) {
        syncLocks.getOrPut(account) { Mutex() }.withLock {
            if (!DumberAccount.enabled(context, account)) return@withLock
            val own = DumberAccount.state(context, account).getJSONObject("session").getString("user_id")
            val cutoff = DumberAccount.state(context, account).getLong("connected_at")
            DumberInbox(context, account).use { queue ->
                val retry = JSONArray()
                queue.pending().forEach { (room,event) -> event.optJSONObject("encrypted_event")?.let { retry.put(JSONObject().put("room_id",room).put("event",it)) } }
                val response = call(DumberCore.request(context,"sync", account).put("retry_events",retry))
                if (!DumberAccount.enabled(context, account)) return@withLock
                val updates = response.optJSONObject("rooms") ?: JSONObject()
                updates.optJSONObject("invite")?.let { invitations ->
                    for (room in invitations.keys()) {
                        val events = invitations.getJSONObject(room).optJSONObject("invite_state")?.optJSONArray("events") ?: continue
                        for (i in 0 until events.length()) {
                            val event = events.getJSONObject(i)
                            if (event.optString("type") != "m.room.member" || event.optString("state_key") != own) continue
                            val inviter = event.optString("sender")
                            val phone = resolve(context,inviter,account) ?: continue
                            if (SmsWhitelistManager.check(context,phone).allowed) {
                                DumberCore.call(DumberCore.request(context,"join", account).put("room_id",room))
                                peerCache["$account|$room"] = inviter
                            }
                        }
                    }
                }
                updates.optJSONObject("join")?.let { joined ->
                    for (room in joined.keys()) {
                        val update = joined.getJSONObject(room)
                        for (part in listOf("state","timeline")) {
                            val events = update.optJSONObject(part)?.optJSONArray("events") ?: continue
                            for (i in 0 until events.length()) {
                                val event = events.getJSONObject(i)
                                if (event.optString("type") == "m.room.member" && event.optString("state_key") != own && event.optJSONObject("content")?.optString("membership") in listOf("join","invite")) peerCache["$account|$room"] = event.getString("state_key")
                                if (event.optLong("origin_server_ts") >= cutoff) queue.enqueue(room,event)
                            }
                        }
                    }
                }
                // Native sync catches up available messages internally. There is
                // no public history command and no app-owned server cursor.
                for ((room,event) in queue.pending()) {
                    if (!event.optBoolean("e2ee")) continue // retry decryption when its key arrives
                    if (event.optString("type") != "m.room.message" || event.optJSONObject("content")?.optString("msgtype") != "m.text") { queue.done(event.getString("event_id")); continue }
                    val outgoing = event.optString("sender") == own
                    val peer = if(outgoing) peerCache["$account|$room"] else event.optString("sender")
                    if(peer.isNullOrEmpty()) continue
                    val phone = resolve(context,peer,account)
                    if (phone == null) { queue.done(event.getString("event_id")); continue }
                    storeLock.withLock { store(context,queue,room,event,phone,!outgoing, account, subscription = DumberAccount.subscription(context, account)) }
                }
            }
        }
    }
    private fun resolve(context: Context, user: String, account: String): String? {
        phoneCache["$account|$user"]?.let { DumberDirectory.remember(context, account, it, user); return it }
        val phone = DumberCore.call(DumberCore.request(context,"resolve", account).put("user_ids",JSONArray().put(user)))
            .getJSONObject("phones").optString(user).takeIf { it.isNotBlank() } ?: return null
        phoneCache["$account|$user"] = phone
        DumberDirectory.remember(context, account, phone, user)
        return phone
    }
    private suspend fun store(context: Context, queue: DumberInbox, room: String, event: JSONObject, phone: String, incoming: Boolean, account: String, requestedThread: Long = -1, subscription: Int = -1) {
        val id = event.getString("event_id")
        if(queue.handled(id)) return
        if (!SmsWhitelistManager.check(context,phone).allowed) { queue.done(id); return }
        val marker = eventMarker(context, account, room, id)
        val existing = context.contentResolver.query(Telephony.Sms.CONTENT_URI,arrayOf(Telephony.Sms._ID),"${Telephony.Sms.SERVICE_CENTER} = ?",arrayOf(marker),null)?.use { it.moveToFirst() } ?: false
        if(existing) { queue.done(id); return }
        val thread = if(requestedThread > 0) requestedThread else Telephony.Threads.getOrCreateThreadId(context,phone)
        val body = event.getJSONObject("content").getString("body")
        val timestamp = event.getLong("origin_server_ts")
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS,phone); put(Telephony.Sms.BODY,body); put(Telephony.Sms.THREAD_ID,thread)
            put(Telephony.Sms.DATE,timestamp);put(Telephony.Sms.DATE_SENT,timestamp)
            put(Telephony.Sms.TYPE,if(incoming) Telephony.Sms.MESSAGE_TYPE_INBOX else Telephony.Sms.MESSAGE_TYPE_SENT)
            put(Telephony.Sms.READ,if(incoming) 0 else 1);put(Telephony.Sms.SEEN,if(incoming) 0 else 1)
            put(Telephony.Sms.STATUS,-1);put(Telephony.Sms.SERVICE_CENTER,marker);put(Telephony.Sms.SUBSCRIPTION_ID,subscription)
        }
        checkNotNull(context.contentResolver.insert(Telephony.Sms.CONTENT_URI,values)) { "Unable to save encrypted message" }
        queue.done(id)
        publish(thread)
        if(incoming) {
            val db = App.get().database
            val blocked = db.blockedNumbersDao().getAll().any { PhoneNumberMatcher.equivalent(it.number,phone) } || db.blockedKeywordsDao().getAll().any { body.contains(it.keyword,true) }
            if(!blocked) runCatching { NotificationHelper.showIncomingNotification(context,thread,App.get().contactHelper.getDisplayName(phone),phone,body) }
        }
    }
    internal fun eventMarker(context: Context, account: String, room: String, event: String): String {
        // Keep existing markers for the legacy account so an upgrade cannot duplicate its history.
        val owner = if (DumberAccount.storeName(context, account) == "dumber-crypto") "" else "$account/"
        return DumberProtocol.MARKER + owner + room + "/" + event
    }
    private fun publish(thread: Long) {
        MessageCache.invalidate(thread)
        EventBus.getDefault().post(RefreshConversations())
        if(thread > 0) EventBus.getDefault().post(RefreshMessages(thread))
    }
}
