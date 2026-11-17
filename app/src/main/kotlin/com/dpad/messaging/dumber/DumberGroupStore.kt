package com.dpad.messaging.dumber

import chat.dumber.sdk.*

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.dpad.messaging.models.Conversation as AppConversation
import kotlinx.serialization.Serializable
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

@Serializable
data class GroupReference(val account: String, val room: String) {
    val key: String get() = digest("$account\u0000$room")
}
internal fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

/** Separate from the Telephony cache. All content and identities are Keystore encrypted. */
class DumberGroupStore(private val context: Context) : SQLiteOpenHelper(context, File(context.noBackupFilesDir, "dumber-groups.db").absolutePath, null, 1), java.io.Closeable {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE rooms (id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY NOT NULL, room TEXT NOT NULL, time INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX messages_room ON messages(room, time)")
        db.execSQL("CREATE TABLE outbox (id TEXT PRIMARY KEY NOT NULL, room TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE creations (id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    private fun read(table: String, id: String): JSONObject? = readableDatabase.rawQuery("SELECT payload FROM $table WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst() && it.getString(0).isNotBlank()) JSONObject(DumberAccount.open(it.getString(0))) else null
    }
    private fun write(table: String, id: String, value: Any, extra: ContentValues = ContentValues()) {
        extra.put("id", id); extra.put("payload", DumberAccount.seal(value.toString()))
        writableDatabase.insertWithOnConflict(table, null, extra, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Unable to save group data" } }
    }
    private fun <T> atomic(block: () -> T): T {
        val db = writableDatabase; db.beginTransaction()
        try { val value = block(); db.setTransactionSuccessful(); return value } finally { db.endTransaction() }
    }
    fun rooms(account: String): List<Pair<GroupReference, Conversation>> = readableDatabase.rawQuery("SELECT payload FROM rooms",null).use { c ->
        buildList { while(c.moveToNext()) {
            val row = JSONObject(DumberAccount.open(c.getString(0)))
            if(row.optString("account") == account) row.optString("info").takeIf { it.isNotBlank() }?.let { add(GroupReference(account,row.getString("room")) to ChatStorage.decodeConversation(it)) }
        } }
    }
    fun room(ref: GroupReference): JSONObject? = read("rooms", ref.key)
    fun info(ref: GroupReference): Conversation? = room(ref)?.optString("info")?.takeIf { it.isNotBlank() }?.let(ChatStorage::decodeConversation)
    fun metadata(ref: GroupReference, info: Conversation) = atomic {
        val value = room(ref) ?: JSONObject().put("account", ref.account).put("room", ref.room)
        val previous = value.optString("info").takeIf(String::isNotBlank)?.let(ChatStorage::decodeConversation)
        val encoded = ChatStorage.encodeConversation(info)
        val newInvitation = info.membership == Membership.INVITED &&
            (previous?.membership != Membership.INVITED || !value.has("invitation_received_at"))
        val changed = encoded != value.optString("info") || newInvitation
        if (newInvitation) {
            val receivedAt = System.currentTimeMillis()
            value.put("invitation_received_at", receivedAt).put("invitation_pending", true)
                .put("date", maxOf(value.optLong("date"), receivedAt))
                .put("unread", maxOf(1, value.optInt("unread")))
            // A fresh invitation resurfaces a departed/deleted group. A legacy
            // invitation keeps any explicit archive/hide choice during repair.
            if (previous?.membership != Membership.INVITED) value.put("hidden", false).put("archived", false)
        } else if (info.membership != Membership.INVITED) {
            value.put("invitation_pending", false)
        }
        if (changed) {
            value.put("info", encoded)
            write("rooms", ref.key, value)
        }
        changed
    }
    fun flag(ref: GroupReference, key: String, value: Boolean) = atomic {
        val row = room(ref) ?: return@atomic
        row.put(key, value); write("rooms", ref.key, row)
    }
    fun markRead(ref: GroupReference) = setRead(ref, true)
    fun setRead(ref: GroupReference, read: Boolean) = atomic {
        val row = room(ref) ?: return@atomic
        row.put("unread", if (read) 0 else maxOf(1, row.optInt("unread")))
        if (read) row.put("invitation_pending", false)
        write("rooms", ref.key, row)
    }
    fun messages(ref: GroupReference): List<Message> = readableDatabase.rawQuery("SELECT payload FROM messages WHERE room=? AND deleted=0 ORDER BY time, id", arrayOf(ref.key)).use { c ->
        buildList { while (c.moveToNext()) add(ChatStorage.decodeMessage(DumberAccount.open(c.getString(0)))) }
    }
    fun event(ref: GroupReference, event: Message): Boolean = atomic {
        val id = digest(ref.key + "\u0000" + event.eventId)
        val exists = readableDatabase.rawQuery("SELECT deleted FROM messages WHERE id=?", arrayOf(id)).use { c -> if (c.moveToFirst()) c.getInt(0) else null }
        if (exists == 1) return@atomic false
        write("messages", id, ChatStorage.encodeMessage(event), ContentValues().apply { put("room", ref.key); put("time", event.timestampMs); put("deleted", 0) })
        val transaction = event.transactionId
        if (event.sender.id == ref.account && !transaction.isNullOrBlank()) writableDatabase.delete("outbox", "id=? AND room=?", arrayOf(transaction, ref.key))
        if (exists != null) return@atomic false
        val row = room(ref) ?: JSONObject().put("account",ref.account).put("room",ref.room)
        if (event.timestampMs >= row.optLong("date")) {
            row.put("date", event.timestampMs).put("snippet", (event.content as? MessageContent.Text)?.body ?: "Encrypted message")
        }
        row.put("hidden", false)
        if (event.sender.id != ref.account) row.put("unread", row.optInt("unread") + 1)
        write("rooms", ref.key, row)
        true
    }
    fun deleteHistory(ref: GroupReference) = atomic {
        // Include not-yet-decrypted events: a later key arrival must not restore them.
        DumberInbox(context,ref.account).use { inbox ->
            inbox.pending().filter { it.first == ref.room }.forEach { inbox.done(it.second.eventId) }
        }
        // Keep event IDs as tombstones. Delete is local and does not imply leave.
        writableDatabase.update("messages", ContentValues().apply { put("deleted",1); put("payload","") }, "room=?", arrayOf(ref.key))
        writableDatabase.delete("outbox", "room=?", arrayOf(ref.key))
        val row = room(ref) ?: return@atomic
        row.put("hidden",true).put("snippet","").put("unread",0); write("rooms",ref.key,row)
    }
    fun pending(ref: GroupReference): List<JSONObject> = readableDatabase.rawQuery("SELECT payload FROM outbox WHERE room=? ORDER BY rowid", arrayOf(ref.key)).use { c -> buildList { while(c.moveToNext()) add(JSONObject(DumberAccount.open(c.getString(0)))) } }
    fun savePending(ref: GroupReference, value: JSONObject) = write("outbox", value.getString("id"), value, ContentValues().apply { put("room",ref.key) })
    fun done(id: String) { writableDatabase.delete("outbox", "id=?", arrayOf(id)) }
    fun saveCreation(value: JSONObject) = write("creations", value.getString("account"), value)
    fun creation(account: String) = read("creations", account)
    fun creationDone(account: String) { writableDatabase.delete("creations", "id=?", arrayOf(account)) }
    fun conversations(archived: Boolean = false): List<AppConversation> = readableDatabase.rawQuery("SELECT payload FROM rooms", null).use { c ->
        buildList {
            while(c.moveToNext()) {
                val row = JSONObject(DumberAccount.open(c.getString(0)))
                if(row.optBoolean("hidden") || row.optBoolean("archived") != archived) continue
                val info = ChatStorage.decodeConversation(row.getString("info"))
                val ref = GroupReference(row.getString("account"),row.getString("room"))
                add(AppConversation(threadId = 0, phoneNumber = "", title = info.name?.takeIf { it.isNotBlank() } ?: "Encrypted group",
                    snippet = if(info.membership == Membership.INVITED) "Group invitation" else row.optString("snippet"),
                    date = row.optLong("date"), read = row.optInt("unread") == 0, unreadCount = row.optInt("unread"), isGroupConversation = true,
                    archived = archived, muted = row.optBoolean("muted"), pinned = row.optBoolean("pinned")).apply { group = ref })
            }
        }.sortedWith(compareByDescending<AppConversation> { it.pinned }.thenByDescending { it.date })
    }
}
