package com.dpad.messaging.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey
    @ColumnInfo(name = "thread_id")
    val threadId: Long,

    @ColumnInfo(name = "phone_number")
    val phoneNumber: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "photo_uri")
    val photoUri: String = "",

    @ColumnInfo(name = "snippet")
    val snippet: String = "",

    @ColumnInfo(name = "date")
    val date: Long = 0L,

    @ColumnInfo(name = "read")
    val read: Boolean = true,

    @ColumnInfo(name = "unread_count")
    val unreadCount: Int = 0,

    @ColumnInfo(name = "is_group_conversation")
    val isGroupConversation: Boolean = false,

    @ColumnInfo(name = "archived")
    val archived: Boolean = false,

    @ColumnInfo(name = "pinned")
    val pinned: Boolean = false,

    @ColumnInfo(name = "muted")
    val muted: Boolean = false,

    @ColumnInfo(name = "uses_custom_title")
    val usesCustomTitle: Boolean = false,

    @ColumnInfo(name = "is_scheduled")
    val isScheduled: Boolean = false,

    /** Comma-separated phone numbers for group conversations */
    @ColumnInfo(name = "participants")
    val participants: String = ""
) {
    @androidx.room.Ignore
    var group: com.dpad.messaging.dumber.GroupReference? = null
    val referenceKey: String get() = group?.let { "group:${it.key}" } ?: "carrier:$threadId"
}
