package com.dpad.messaging.databases

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dpad.messaging.databases.daos.*
import com.dpad.messaging.models.*

@Database(
    entities = [
        Conversation::class,
        Message::class,
        Attachment::class,
        Draft::class,
        RecycleBinMessage::class,
        BlockedKeyword::class,
        BlockedNumber::class
    ],
    version = 9,
    exportSchema = true
)
abstract class MessagesDatabase : RoomDatabase() {

    abstract fun conversationsDao(): ConversationsDao
    abstract fun messagesDao(): MessagesDao
    abstract fun attachmentsDao(): AttachmentsDao
    abstract fun draftsDao(): DraftsDao
    abstract fun blockedKeywordsDao(): BlockedKeywordsDao
    abstract fun blockedNumbersDao(): BlockedNumbersDao

    companion object {
        private const val DB_NAME = "dpad_messages.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recycle_bin_messages` (
                        `id` INTEGER NOT NULL,
                        `address` TEXT NOT NULL,
                        `sender_name` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `date` INTEGER NOT NULL,
                        `deleted_ts` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `blocked_keywords` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `keyword` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN scheduled_date INTEGER")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `blocked_numbers` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `number` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Check if old whitelisted_numbers table exists and rename it
                val cursor = db.query("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='whitelisted_numbers'")
                cursor.moveToFirst()
                if (cursor.getInt(0) > 0) {
                    cursor.close()
                    db.execSQL("ALTER TABLE `whitelisted_numbers` RENAME TO `blocked_numbers`")
                } else {
                    cursor.close()
                    db.execSQL("CREATE TABLE IF NOT EXISTS `blocked_numbers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `number` TEXT NOT NULL)")
                }
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drafts ADD COLUMN attachment_uris_json TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE drafts ADD COLUMN subscription_id INTEGER NOT NULL DEFAULT -1")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recycle_bin_messages RENAME TO recycle_bin_messages_v6")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recycle_bin_messages` (
                        `id` INTEGER NOT NULL,
                        `provider_id` INTEGER NOT NULL,
                        `thread_id` INTEGER NOT NULL,
                        `is_mms` INTEGER NOT NULL,
                        `is_conversation` INTEGER NOT NULL,
                        `message_type` INTEGER NOT NULL,
                        `was_read` INTEGER NOT NULL,
                        `address` TEXT NOT NULL,
                        `sender_name` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `date` INTEGER NOT NULL,
                        `deleted_ts` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `recycle_bin_messages` (
                        `id`, `provider_id`, `thread_id`, `is_mms`, `is_conversation`,
                        `message_type`, `was_read`, `address`, `sender_name`, `body`, `date`, `deleted_ts`
                    )
                    SELECT `id`, 0, -1, 0, 0, 1, 1, `address`, `sender_name`, `body`, `date`, `deleted_ts`
                    FROM `recycle_bin_messages_v6`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `recycle_bin_messages_v6`")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recycle_bin_messages RENAME TO recycle_bin_messages_v7")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recycle_bin_messages` (
                        `id` INTEGER NOT NULL,
                        `provider_id` INTEGER NOT NULL,
                        `thread_id` INTEGER NOT NULL,
                        `is_mms` INTEGER NOT NULL,
                        `is_conversation` INTEGER NOT NULL,
                        `message_type` INTEGER NOT NULL,
                        `was_read` INTEGER NOT NULL,
                        `participants_json` TEXT NOT NULL,
                        `address` TEXT NOT NULL,
                        `sender_name` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `date` INTEGER NOT NULL,
                        `deleted_ts` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `recycle_bin_messages` (
                        `id`, `provider_id`, `thread_id`, `is_mms`, `is_conversation`,
                        `message_type`, `was_read`, `participants_json`, `address`,
                        `sender_name`, `body`, `date`, `deleted_ts`
                    )
                    SELECT `id`, `provider_id`, `thread_id`, `is_mms`, `is_conversation`,
                        `message_type`, `was_read`, '[]', `address`, `sender_name`,
                        `body`, `date`, `deleted_ts`
                    FROM `recycle_bin_messages_v7`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `recycle_bin_messages_v7`")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN is_encrypted INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var instance: MessagesDatabase? = null

        fun getInstance(context: Context): MessagesDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): MessagesDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                MessagesDatabase::class.java,
                DB_NAME
            )
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .addMigrations(MIGRATION_4_5)
                .addMigrations(MIGRATION_5_6)
                .addMigrations(MIGRATION_6_7)
                .addMigrations(MIGRATION_7_8)
                .addMigrations(MIGRATION_8_9)
                .build()
        }
    }
}
