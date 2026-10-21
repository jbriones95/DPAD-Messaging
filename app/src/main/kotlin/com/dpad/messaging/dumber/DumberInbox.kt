package com.dpad.messaging.dumber

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import org.json.JSONObject

/** Durable encrypted-event queue and tombstones: deletions never resurrect after full sync. */
class DumberInbox(context: Context, account: String? = null): SQLiteOpenHelper(context, File(context.noBackupFilesDir, if (account == null || DumberAccount.storeName(context, account) == "dumber-crypto") "dumber-inbox.db" else DumberAccount.storeName(context, account) + "-inbox.db").absolutePath, null, 2), java.io.Closeable {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events (event_id TEXT PRIMARY KEY NOT NULL, room_id TEXT NOT NULL, payload TEXT NOT NULL, handled INTEGER NOT NULL DEFAULT 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Only obsolete server-pagination state is removed. Local events and
        // handled tombstones must survive the seven-day server retention policy.
        if (oldVersion < 2) db.execSQL("DROP TABLE IF EXISTS history")
    }
    fun enqueue(room: String, event: JSONObject) {
        val id = event.optString("event_id"); if (id.isEmpty()) return
        if (handled(id)) return
        if (!event.optBoolean("e2ee") && !event.optBoolean("unable_to_decrypt")) return
        val existing = readableDatabase.rawQuery("SELECT payload FROM events WHERE event_id=?", arrayOf(id)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (existing != null && !event.optBoolean("e2ee")) return
        writableDatabase.insertWithOnConflict("events", null, ContentValues().apply {
            put("event_id", id); put("room_id", room); put("payload", DumberAccount.seal(event.toString()))
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (event.optBoolean("e2ee")) writableDatabase.execSQL("UPDATE events SET payload=? WHERE event_id=? AND handled=0", arrayOf(DumberAccount.seal(event.toString()), id))
    }
    fun done(id: String) { writableDatabase.execSQL("UPDATE events SET handled=1, payload='' WHERE event_id=?", arrayOf(id)) }
    fun handled(id: String): Boolean = readableDatabase.rawQuery("SELECT handled FROM events WHERE event_id=?", arrayOf(id)).use { it.moveToFirst() && it.getInt(0) == 1 }
    fun pending(): List<Pair<String, JSONObject>> = readableDatabase.rawQuery("SELECT room_id,payload FROM events WHERE handled=0", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0) to JSONObject(DumberAccount.open(cursor.getString(1)))) }
    }
}
