package com.dpad.messaging.dumber

import chat.dumber.sdk.*

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
import org.json.JSONObject

object DumberMessages {
    private val storeLock = Mutex()
    private val sendLock = Mutex()
    private val syncLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

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
            val client = DumberCore.client(context, account)
            val own = requireNotNull(DumberAccount.session(context, account)).accountId
            check(own == pending.getString("account")) { "Sign in to the original account to retry" }
            val found = pending.getString("peer")
            check(found != own) { "Choose another recipient" }
            val room = pending.optString("room").ifBlank {
                client.openChat(recipient, cachedOnly=true).id.also {
                    pending.put("room", it)
                    DumberOutbox(context).use { queue -> queue.save(pending) }
                }
            }
            val previouslyUncertain = pending.optBoolean("uncertain")
            pending.put("uncertain", true)
            DumberOutbox(context).use { it.save(pending) }
            check(DumberAccount.enabled(context, account)) { "Reconnect dumber.chat to retry encrypted delivery" }
            val result = try {
                client.sendText(room, body, pending.getString("id"))
            } catch (e: ChatException) {
                // A definite rejection proves only this attempt was not accepted.
                if (e.recipientUnavailable && !previouslyUncertain) pending.put("uncertain", false)
                throw e
            }
            // A successful response from this SDK confirms an encrypted Matrix event.
            check(result.encrypted) { "Encryption confirmation missing" }
            pending.put("confirmed", true)
            runCatching { DumberOutbox(context).use { it.save(pending) } }
            val event = outgoing(context, account, result.eventId, body, pending.getString("id"))
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

    fun outgoing(context: Context, account: String, id: String, body: String, transaction: String): Message =
        Message(id, Contact(account, DumberAccount.state(context,account).optString("phone")), System.currentTimeMillis(), MessageContent.Text(body), true, transaction)

    suspend fun sync(context: Context, account: String, client: ChatApi = DumberCore.client(context,account)) = withContext(Dispatchers.IO) {
        syncLocks.getOrPut(account) { Mutex() }.withLock {
            if (!DumberAccount.enabled(context, account)) return@withLock
            val cutoff = DumberAccount.state(context, account).getLong("connected_at")
            DumberInbox(context, account).use { queue ->
                val response = client.sync()
                if (!DumberAccount.enabled(context, account)) return@withLock
                val conversations = client.conversations().associateBy { it.id }.toMutableMap()
                for(update in response.conversations) {
                    val info = update.conversation
                    val id = info.id
                    conversations[id] = info
                    if(info.kind == ConversationKind.DIRECT && info.membership == Membership.INVITED) {
                        val phone = info.participants.singleOrNull()?.phone
                        if(phone != null && SmsWhitelistManager.check(context,phone).allowed) client.join(id)
                    }
                    update.messages.filter { it.timestampMs >= cutoff }.forEach { queue.enqueue(id,it) }
                }
                // The core's cursor may already have consumed an invitation before
                // an app update or a local write failure. Reconcile its durable
                // metadata too; an empty incremental sync must not hide invitations.
                for ((id, info) in conversations) {
                    if (info.kind == ConversationKind.GROUP) DumberGroups.observe(context, GroupReference(account,id), info)
                }
                for((id,storedEvent) in queue.pending()) {
                    if(!storedEvent.encrypted) continue
                    when(storedEvent.content) {
                        MessageContent.UnableToDecrypt -> continue
                        is MessageContent.Text -> Unit
                        else -> { queue.done(storedEvent.eventId); continue }
                    }
                    val info = conversations[id] ?: continue
                    val sender = if(storedEvent.sender.phone == null) info.members.firstOrNull { it.contact.id == storedEvent.sender.id }?.contact ?: storedEvent.sender else storedEvent.sender
                    val event = storedEvent.copy(sender=sender)
                    if(info.kind == ConversationKind.GROUP) {
                        if(DumberGroups.received(context,GroupReference(account,id),event)) queue.done(event.eventId)
                        continue
                    }
                    if(info.kind != ConversationKind.DIRECT) continue
                    val outgoing = sender.id == account
                    val peer = if(outgoing) info.participants.singleOrNull() else sender
                    val phone = peer?.phone ?: continue
                    storeLock.withLock { store(context,queue,id,event,phone,!outgoing,account,subscription=DumberAccount.subscription(context,account)) }
                }
            }
        }
    }
    private suspend fun store(context: Context, queue: DumberInbox, room: String, event: Message, phone: String, incoming: Boolean, account: String, requestedThread: Long = -1, subscription: Int = -1) {
        val id = event.eventId
        if(queue.handled(id)) return
        if (!SmsWhitelistManager.check(context,phone).allowed) { queue.done(id); return }
        val marker = eventMarker(account, room, id)
        val existing = context.contentResolver.query(Telephony.Sms.CONTENT_URI,arrayOf(Telephony.Sms._ID),"${Telephony.Sms.SERVICE_CENTER} = ?",arrayOf(marker),null)?.use { it.moveToFirst() } ?: false
        if(existing) { queue.done(id); return }
        val thread = if(requestedThread > 0) requestedThread else Telephony.Threads.getOrCreateThreadId(context,phone)
        val body = (event.content as MessageContent.Text).body
        val timestamp = event.timestampMs
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
    internal fun eventMarker(account: String, room: String, event: String): String =
        DumberProtocol.MARKER + account + "/" + room + "/" + event
    private fun publish(thread: Long) {
        MessageCache.invalidate(thread)
        EventBus.getDefault().post(RefreshConversations())
        if(thread > 0) EventBus.getDefault().post(RefreshMessages(thread))
    }
}
