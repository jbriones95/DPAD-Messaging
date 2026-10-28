package com.dpad.messaging.databases

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class MigrationTest {

    private val testDb = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MessagesDatabase::class.java,
        listOf(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate5To6() {
        helper.createDatabase(testDb, 5).close()

        helper.runMigrationsAndValidate(
            testDb,
            6,
            true,
            MessagesDatabase.MIGRATION_5_6
        )
    }

    @Test
    fun migrate8To9KeepsOldMessagesUnencrypted() {
        helper.createDatabase(testDb, 8).apply {
            execSQL("INSERT INTO messages VALUES (1, 2, 'old SMS', 1, 100, 100, 1, '+15555550101', '', '', 0, -1, -1, 0, NULL, '', '')")
            close()
        }
        helper.runMigrationsAndValidate(testDb, 9, true, MessagesDatabase.MIGRATION_8_9).use { db ->
            db.query("SELECT body,is_encrypted FROM messages WHERE id=1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("old SMS", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
        }
    }

    @Test
    fun migrate6To9PreservesRecycleEntries() {
        val oldDb = helper.createDatabase(testDb, 6)
        oldDb.execSQL(
            """INSERT INTO recycle_bin_messages (id, address, sender_name, body, date, deleted_ts)
                VALUES (42, '+15551234567', 'A contact', 'Saved text', 1234, 5678)""".trimIndent()
        )
        oldDb.close()

        val migratedDb = helper.runMigrationsAndValidate(
            testDb,
            9,
            true,
            MessagesDatabase.MIGRATION_6_7,
            MessagesDatabase.MIGRATION_7_8,
            MessagesDatabase.MIGRATION_8_9
        )
        migratedDb.query(
            "SELECT provider_id, thread_id, is_mms, is_conversation, message_type, was_read, participants_json, body FROM recycle_bin_messages WHERE id = 42"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
            assertEquals(-1L, cursor.getLong(1))
            assertEquals(0, cursor.getInt(2))
            assertEquals(0, cursor.getInt(3))
            assertEquals(1, cursor.getInt(4))
            assertEquals(1, cursor.getInt(5))
            assertEquals("[]", cursor.getString(6))
            assertEquals("Saved text", cursor.getString(7))
        }
        migratedDb.close()
    }
}
