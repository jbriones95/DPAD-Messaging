package com.dpad.messaging.dumber

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Separate from Telephony: an unsent encrypted attempt must never look like a sent SMS. */
class DumberOutbox(context: Context) : SQLiteOpenHelper(context, File(context.noBackupFilesDir, "dumber-outbox.db").absolutePath, null, 1), java.io.Closeable {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pending (id TEXT PRIMARY KEY NOT NULL, account TEXT NOT NULL, thread INTEGER NOT NULL, payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun save(value: JSONObject) {
        val inserted = writableDatabase.insertWithOnConflict("pending", null, ContentValues().apply {
            put("id", value.getString("id")); put("account", value.getString("account")); put("thread", value.getLong("thread"))
            put("payload", DumberAccount.seal(value.toString()))
        }, SQLiteDatabase.CONFLICT_REPLACE)
        check(inserted >= 0) { "Unable to save pending message" }
    }
    fun get(id: String): JSONObject? = readableDatabase.rawQuery("SELECT payload FROM pending WHERE id=?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) JSONObject(DumberAccount.open(c.getString(0))) else null
    }
    fun list(account: String, thread: Long): List<JSONObject> = readableDatabase.rawQuery(
        "SELECT payload FROM pending WHERE account=? AND thread=? ORDER BY rowid", arrayOf(account, thread.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(JSONObject(DumberAccount.open(c.getString(0)))) } }
    fun delete(id: String) { writableDatabase.delete("pending", "id=?", arrayOf(id)) }
    companion object {
        fun message(account: String, phone: String, body: String, thread: Long, subscription: Int, scheduled: Long?): JSONObject = JSONObject()
            .put("id", (scheduled?.let { UUID.nameUUIDFromBytes("$account/scheduled/$it".toByteArray()) } ?: UUID.randomUUID()).toString()).put("account", account).put("phone", phone).put("body", body)
            .put("thread", thread).put("subscription", subscription).put("uncertain", false)
            .apply { if (scheduled != null) put("scheduled", scheduled) }
    }
}
