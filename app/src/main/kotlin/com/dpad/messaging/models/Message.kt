package com.dpad.messaging.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Mirrors a row in the system Telephony.Sms / Telephony.Mms tables,
 * cached locally so we can add app-level fields (scheduled, recycle bin, etc.)
 */
@Serializable
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Long,

    @ColumnInfo(name = "thread_id")
    val threadId: Long,

    @ColumnInfo(name = "body")
    val body: String = "",

    /**
     * Telephony message type:
     *   1 = MESSAGE_TYPE_INBOX  (received)
     *   2 = MESSAGE_TYPE_SENT
     *   3 = MESSAGE_TYPE_DRAFT
     *   4 = MESSAGE_TYPE_OUTBOX
     *   5 = MESSAGE_TYPE_FAILED
     *   6 = MESSAGE_TYPE_QUEUED
     */
    @ColumnInfo(name = "type")
    val type: Int,

    @ColumnInfo(name = "date")
    val date: Long = 0L,

    @ColumnInfo(name = "date_sent")
    val dateSent: Long = 0L,

    @ColumnInfo(name = "read")
    val read: Boolean = true,

    /** Phone number of the sender (inbox) or primary recipient (sent) */
    @ColumnInfo(name = "address")
    val address: String = "",

    @ColumnInfo(name = "sender_name")
    val senderName: String = "",

    @ColumnInfo(name = "sender_photo_uri")
    val senderPhotoUri: String = "",

    @ColumnInfo(name = "is_mms")
    val isMms: Boolean = false,

    /**
     * SMS status (from SmsManager):
     *   -1 = STATUS_NONE
     *    0 = STATUS_COMPLETE (delivered)
     *   32 = STATUS_PENDING
     *   64 = STATUS_FAILED
     */
    @ColumnInfo(name = "status")
    val status: Int = -1,

    @ColumnInfo(name = "subscription_id")
    val subscriptionId: Int = -1,

    @ColumnInfo(name = "is_scheduled")
    val isScheduled: Boolean = false,

    @ColumnInfo(name = "scheduled_date")
    val scheduledDate: Long? = null,

    /** JSON array of Attachment objects – populated only for MMS */
    @ColumnInfo(name = "attachments_json")
    val attachmentsJson: String = "[]",

    /** For group MMS: JSON array of participant phone numbers */
    @ColumnInfo(name = "participants_json")
    val participantsJson: String = "[]",

    /** True only for locally recorded, successfully encrypted Matrix events. */
    @ColumnInfo(name = "is_encrypted", defaultValue = "0")
    val isEncrypted: Boolean = false
) {
    val isIncoming: Boolean get() = type == TYPE_INBOX
    val isOutgoing: Boolean get() = !isIncoming

    companion object {
        const val TYPE_INBOX = 1
        const val TYPE_SENT = 2
        const val TYPE_DRAFT = 3
        const val TYPE_OUTBOX = 4
        const val TYPE_FAILED = 5
        const val TYPE_QUEUED = 6

        const val STATUS_NONE = -1
        const val STATUS_COMPLETE = 0
        const val STATUS_PENDING = 32
        const val STATUS_FAILED = 64
    }
}
