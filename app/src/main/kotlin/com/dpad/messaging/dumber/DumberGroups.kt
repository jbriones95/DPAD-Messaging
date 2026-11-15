package com.dpad.messaging.dumber

import chat.dumber.sdk.*

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dpad.messaging.App
import com.dpad.messaging.R
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.helpers.SmsWhitelistManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object DumberGroups {
    private val sendLock = Mutex()
    private const val INVITATION_NOTIFICATION = 51003
    fun clearInvitationNotification(context: Context, ref: GroupReference) {
        context.getSystemService(NotificationManager::class.java).cancel(ref.key, INVITATION_NOTIFICATION)
    }
    fun changed() { EventBus.getDefault().post(RefreshConversations()) }
    enum class Action { JOIN, LEAVE, RENAME, INVITE, REMOVE, GRANT_ADMIN }
    fun active(info: Conversation) = info.members.filter { it.membership in listOf(Membership.JOINED, Membership.INVITED) }
    fun label(member: GroupMember): String = member.contact.phone?.let { App.get().contactHelper.getDisplayName(it) } ?: "Unknown participant (${digest(member.contact.id).take(6)})"
    fun observe(context: Context, ref: GroupReference, info: Conversation) {
        val updated = DumberGroupStore(context).use { store ->
            val updated = store.metadata(ref,info)
            val row = store.room(ref)
            if (info.membership == Membership.INVITED && row?.optBoolean("invitation_pending") == true &&
                !row.optBoolean("muted") && !row.optBoolean("archived") && !row.optBoolean("hidden")) {
                val manager = context.getSystemService(NotificationManager::class.java)
                val channelEnabled = android.os.Build.VERSION.SDK_INT < 26 ||
                    manager.getNotificationChannel(App.CHANNEL_MESSAGES)?.importance != NotificationManager.IMPORTANCE_NONE
                if (androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() && channelEnabled) {
                    val intent = Intent(context,DumberGroupActivity::class.java)
                        .putExtra("account",ref.account).putExtra("room",ref.room)
                        .setData(android.net.Uri.parse("dpad-group://${ref.key}"))
                    val open = PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val notification = NotificationCompat.Builder(context,App.CHANNEL_MESSAGES)
                        .setSmallIcon(R.drawable.ic_new_message)
                        .setContentTitle(info.name ?: context.getString(R.string.dumber_group_details))
                        .setContentText(context.getString(R.string.dumber_group_invited))
                        .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build()
                    runCatching {
                        manager.notify(ref.key, INVITATION_NOTIFICATION, notification)
                        store.flag(ref,"invitation_pending",false)
                    }
                }
            } else if (info.membership != Membership.INVITED) clearInvitationNotification(context, ref)
            updated
        }
        if (updated) changed()
    }
    suspend fun refresh(context: Context, ref: GroupReference): Conversation = withContext(Dispatchers.IO) {
        check(DumberAccount.enabled(context,ref.account)) { "Reconnect the original account" }
        val info = DumberCore.client(context,ref.account).conversationInfo(ref.room)
        observe(context,ref,info)
        info
    }
    suspend fun create(context: Context, account: String, name: String, phones: List<String>): GroupReference = withContext(Dispatchers.IO) {
        check(DumberAccount.enabled(context,account)) { "Connect this account first" }
        check(name.isNotBlank() && name.codePointCount(0,name.length) <= 100) { "Use a group name of 1–100 characters" }
        check(phones.all { SmsWhitelistManager.check(context,it).allowed }) { "Participant blocked by policy" }
        val sorted = phones.distinct().sorted()
        DumberGroupStore(context).use { store ->
            val saved = store.creation(account)
            if(saved != null) check(saved.getString("name") == name && saved.getJSONArray("phones").toString() == JSONArray(sorted).toString()) {
                "An earlier creation is unresolved. Retry its saved name and participants before creating another group."
            }
            val request = saved ?: JSONObject().put("account",account).put("name",name).put("phones",JSONArray(sorted)).put("operation_id",UUID.randomUUID().toString())
            store.saveCreation(request)
            val info = DumberCore.client(context,account).createGroup(name,sorted,request.getString("operation_id"))
            val ref = GroupReference(account, info.id)
            store.metadata(ref,info); store.creationDone(account); changed(); ref
        }
    }
    suspend fun action(context: Context, ref: GroupReference, action: Action, value: String = ""): Conversation = withContext(Dispatchers.IO) {
        check(DumberAccount.enabled(context,ref.account)) { "Reconnect the original account" }
        val client = DumberCore.client(context,ref.account)
        when(action) {
            Action.JOIN -> client.join(ref.room)
            Action.LEAVE -> client.leave(ref.room)
            Action.RENAME -> client.renameGroup(ref.room,value)
            Action.INVITE -> {
                check(SmsWhitelistManager.check(context,value).allowed) { "Participant blocked by policy" }
                client.inviteMember(ref.room,value)
            }
            Action.REMOVE -> client.removeMember(ref.room,value)
            Action.GRANT_ADMIN -> client.grantAdmin(ref.room,value)
        }
        if(action == Action.LEAVE) {
            val info = client.conversations().first { it.id == ref.room }
            observe(context,ref,info); info
        } else refresh(context,ref)
    }
    suspend fun enqueue(context: Context, ref: GroupReference, body: String): JSONObject = withContext(Dispatchers.IO) {
        check(body.isNotBlank()) { "Enter a message" }
        val pending = JSONObject().put("id",UUID.randomUUID().toString()).put("body",body).put("account",ref.account).put("room",ref.room).put("timestamp",System.currentTimeMillis())
        DumberGroupStore(context).use { it.savePending(ref,pending) }
        changed(); pending
    }
    suspend fun retry(context: Context, ref: GroupReference, pending: JSONObject) = withContext(Dispatchers.IO) {
        sendLock.withLock {
            DumberGroupStore(context).use { store ->
                // A synced echo may have already completed this send.
                if(store.pending(ref).none { it.getString("id") == pending.getString("id") }) return@withLock
                try {
                    val info = refresh(context,ref)
                    check(info.kind == ConversationKind.GROUP && info.membership == Membership.JOINED && info.capabilities.send) { "You are no longer a joined member" }
                    for(member in active(info).filter { it.contact.id != ref.account }) {
                        val phone = member.contact.phone
                        check(phone != null) { "Resolve all participants before sending" }
                        check(SmsWhitelistManager.check(context,phone).allowed) { "Participant blocked by policy" }
                    }
                    val result = DumberCore.client(context,ref.account).sendText(ref.room,pending.getString("body"),pending.getString("id"))
                    check(result.encrypted) { "Encryption confirmation missing" }
                    val event = DumberMessages.outgoing(context,ref.account,result.eventId,pending.getString("body"),pending.getString("id"))
                    store.event(ref,event)
                    store.done(pending.getString("id"))
                } catch(e: kotlinx.coroutines.CancellationException) { throw e }
                catch(e: Exception) { pending.put("error",e.message); store.savePending(ref,pending); throw e }
                finally { changed() }
            }
        }
    }
    suspend fun received(context: Context, ref: GroupReference, event: Message): Boolean {
        val info = DumberGroupStore(context).use { it.info(ref) }
        val sender = info?.members?.find { it.contact.id == event.sender.id }
        val phone = sender?.contact?.phone ?: event.sender.phone
        if(event.sender.id != ref.account) {
            val restrictions = context.getSystemService(android.content.RestrictionsManager::class.java)?.applicationRestrictions
            if(phone == null && restrictions?.getString(SmsWhitelistManager.KEY_MODE,"off") in listOf("whitelist","blocklist")) return false
            if(phone != null && !SmsWhitelistManager.check(context,phone).allowed) return true
        }
        val db = App.get().database
        val suppressed = (phone != null && db.blockedNumbersDao().getAll().any { com.dpad.messaging.helpers.PhoneNumberMatcher.equivalent(it.number,phone) }) ||
            db.blockedKeywordsDao().getAll().any { (event.content as? MessageContent.Text)?.body?.contains(it.keyword,true) == true }

        val notify = DumberGroupStore(context).use { store -> store.event(ref,event) && event.sender.id != ref.account && store.room(ref)?.optBoolean("muted") != true }
        changed()
        if(notify && !suppressed) {
            val intent = Intent(context,DumberGroupActivity::class.java).putExtra("account",ref.account).putExtra("room",ref.room).setData(android.net.Uri.parse("dpad-group://${ref.key}"))
            val open = PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val name = DumberGroupStore(context).use { it.info(ref)?.name } ?: "Encrypted group"
            val notification = NotificationCompat.Builder(context,App.CHANNEL_MESSAGES).setSmallIcon(R.drawable.ic_new_message).setContentTitle(name)
                .setContentText("New encrypted group message").setContentIntent(open).setAutoCancel(true).build()
            runCatching { context.getSystemService(NotificationManager::class.java).notify(ref.key,51002,notification) }
        }
        return true
    }
}
