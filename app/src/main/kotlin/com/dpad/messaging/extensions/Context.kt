package com.dpad.messaging.extensions

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.dpad.messaging.App
import com.dpad.messaging.helpers.ContactHelper
import com.dpad.messaging.helpers.MmsHelper
import com.dpad.messaging.helpers.Prefs
import com.dpad.messaging.models.Conversation
import com.dpad.messaging.models.Message

// ─────────────────────────────────────────────────────────────────────────────
// Telephony ContentProvider read helpers
// All functions are synchronous — call from a background coroutine.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Returns the set of own phone numbers (digits only, no +/- formatting) for all
 * active SIM subscriptions.  Used to filter the device's own number out of MMS
 * participant lists.  Requires READ_PHONE_STATE permission; returns empty set if
 * permission is missing or no number is available.
 */
fun Context.getOwnPhoneNumbers(): Set<String> {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
        return emptySet()
    }
    val numbers = mutableSetOf<String>()
    try {
        val subMgr = getSystemService(SubscriptionManager::class.java)
        val subs   = subMgr?.activeSubscriptionInfoList ?: emptyList()
        if (subs.isNotEmpty()) {
            val tm = getSystemService(TelephonyManager::class.java)
            for (sub in subs) {
                val num = tm?.createForSubscriptionId(sub.subscriptionId)
                    ?.line1Number?.filter { it.isDigit() }
                if (!num.isNullOrBlank()) numbers.add(num)
                // Also store last-10-digit form for matching canonical addresses
                if (num != null && num.length > 10) numbers.add(num.takeLast(10))
            }
        } else {
            // Single-SIM fallback
            @Suppress("DEPRECATION")
            val num = (getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
                ?.line1Number?.filter { it.isDigit() }
            if (!num.isNullOrBlank()) {
                numbers.add(num)
                if (num.length > 10) numbers.add(num.takeLast(10))
            }
        }
    } catch (_: Exception) {}
    return numbers
}

/**
 * Reads all non-archived conversations from the system Telephony provider,
 * resolves contact names, and returns them sorted pinned-first then date-desc.
 *
 * @param pinnedThreadIds Set of thread IDs that the user has pinned.
 * @param archivedThreadIds Set of thread IDs to exclude (archived). When null, reads from Prefs.
 */
fun Context.getConversationsFromTelephony(
    contactHelper: ContactHelper,
    pinnedThreadIds: Set<Long> = emptySet(),
    archivedThreadIds: Set<Long>? = null,
    mutedThreadIds: Set<Long> = emptySet(),
    maxCount: Int = Int.MAX_VALUE
): List<Conversation> {
    val excluded = archivedThreadIds ?: Prefs.get().getArchivedThreadIds()
    val uri = Uri.parse("content://mms-sms/conversations?simple=true")
    val projection = arrayOf(
        Telephony.Threads._ID,
        Telephony.Threads.RECIPIENT_IDS,
        Telephony.Threads.SNIPPET,
        Telephony.Threads.DATE,
        Telephony.Threads.READ,
        Telephony.Threads.MESSAGE_COUNT
    )

    val conversations = mutableListOf<Conversation>()
    val ownNumbers = getOwnPhoneNumbers()
    val canonicalAddressCache = hashMapOf<Long, String?>()

    try {
        contentResolver.query(uri, projection, null, null, "${Telephony.Threads.DATE} DESC")
            ?.use { cursor ->
                val idxId = cursor.getColumnIndex(Telephony.Threads._ID)
                val idxRecipients = cursor.getColumnIndex(Telephony.Threads.RECIPIENT_IDS)
                val idxSnippet = cursor.getColumnIndex(Telephony.Threads.SNIPPET)
                val idxDate = cursor.getColumnIndex(Telephony.Threads.DATE)
                val idxRead = cursor.getColumnIndex(Telephony.Threads.READ)

                while (cursor.moveToNext()) {
                    if (conversations.size >= maxCount) break

                    val threadId = cursor.getLong(idxId)
                    if (threadId in excluded) continue  // skip archived threads
                    val recipientIds = cursor.getString(idxRecipients) ?: continue
                    val snippet = cursor.getString(idxSnippet) ?: ""
                    val date = cursor.getLong(idxDate)
                    val read = cursor.getInt(idxRead) == 1

                    // Filter out own numbers so group participant lists and titles
                    // don't include the device's own phone number.
                    val allNumbers = resolveRecipientIds(recipientIds, canonicalAddressCache)
                    val phoneNumbers = allNumbers.filter { num ->
                        val digits = num.filter { it.isDigit() }
                        digits !in ownNumbers && digits.takeLast(10) !in ownNumbers
                    }.ifEmpty { allNumbers }  // fallback: keep all if filter removes everything
                    if (phoneNumbers.isEmpty()) continue

                    val isGroup = phoneNumbers.size > 1
                    val primaryPhone = phoneNumbers.first()
                    val contactInfo = contactHelper.resolve(primaryPhone)
                    val title = when {
                        isGroup -> phoneNumbers.joinToString(", ") {
                            contactHelper.getDisplayName(it)
                        }
                        contactInfo != null -> contactInfo.displayName
                        else -> primaryPhone
                    }

                    conversations.add(
                        Conversation(
                            threadId = threadId,
                            phoneNumber = primaryPhone,
                            title = title,
                            photoUri = contactInfo?.photoUri ?: "",
                            snippet = snippet,
                            date = date,
                            read = read,
                            isGroupConversation = isGroup,
                            pinned = threadId in pinnedThreadIds,
                            archived = threadId in excluded,
                            muted = threadId in mutedThreadIds,
                            participants = phoneNumbers.joinToString(",")
                        )
                    )
                }
            }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return conversations.sortedWith(
        compareByDescending<Conversation> { it.pinned }.thenByDescending { it.date }
    )
}

/**
 * Resolves a space-separated string of canonical address IDs to phone numbers.
 * e.g. "3 7" → ["+15551234567", "+15559876543"]
 */
private fun Context.resolveRecipientIds(recipientIds: String): List<String> {
    return resolveRecipientIds(recipientIds, hashMapOf())
}

private fun Context.resolveRecipientIds(
    recipientIds: String,
    canonicalAddressCache: MutableMap<Long, String?>
): List<String> {
    return recipientIds.trim().split(" ").mapNotNull { idStr ->
        val id = idStr.trim().toLongOrNull() ?: return@mapNotNull null
        if (canonicalAddressCache.containsKey(id)) {
            return@mapNotNull canonicalAddressCache[id]
        }

        val uri = Uri.parse("content://mms-sms/canonical-address/$id")
        val resolved = try {
            contentResolver.query(uri, arrayOf("address"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        canonicalAddressCache[id] = resolved
        resolved
    }.filter { it.isNotBlank() }
}

/**
 * Reads all SMS messages for a given thread from the Telephony provider,
 * sorted oldest-first (for display in a chat thread).
 */
suspend fun Context.getMessagesForThread(
    threadId: Long,
    contactHelper: ContactHelper,
    limit: Int = Int.MAX_VALUE
): List<Message> {
    val cappedLimit = if (limit <= 0) Int.MAX_VALUE else limit
    val messages = mutableListOf<Message>()

    // ── SMS ──────────────────────────────────────────────────────────────────
    val smsUri = Telephony.Sms.CONTENT_URI
    val smsProjection = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.DATE_SENT,
        Telephony.Sms.TYPE,
        Telephony.Sms.READ,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.STATUS,
        Telephony.Sms.SUBSCRIPTION_ID
    )
    val smsFallbackProjection = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.TYPE,
        Telephony.Sms.READ,
        Telephony.Sms.ADDRESS
    )

    try {
        val smsCursor = try {
            contentResolver.query(
                smsUri,
                smsProjection,
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} DESC"
            )
        } catch (_: Exception) {
            contentResolver.query(
                smsUri,
                smsFallbackProjection,
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} DESC"
            )
        }

        smsCursor?.use { cursor ->
            val idxId = cursor.getColumnIndex(Telephony.Sms._ID)
            val idxBody = cursor.getColumnIndex(Telephony.Sms.BODY)
            val idxDate = cursor.getColumnIndex(Telephony.Sms.DATE)
            val idxDateSent = cursor.getColumnIndex(Telephony.Sms.DATE_SENT)
            val idxType = cursor.getColumnIndex(Telephony.Sms.TYPE)
            val idxRead = cursor.getColumnIndex(Telephony.Sms.READ)
            val idxAddress = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
            val idxStatus = cursor.getColumnIndex(Telephony.Sms.STATUS)
            val idxSubId = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)

            var loadedSms = 0
            while (cursor.moveToNext() && loadedSms < cappedLimit) {
                if (idxId < 0 || idxType < 0) continue

                val address = if (idxAddress >= 0) cursor.getString(idxAddress) ?: "" else ""
                val type = cursor.getInt(idxType)
                val contactInfo = if (type == Message.TYPE_INBOX)
                    contactHelper.resolve(address) else null

                messages.add(
                    Message(
                        id = cursor.getLong(idxId),
                        threadId = threadId,
                        body = if (idxBody >= 0) cursor.getString(idxBody) ?: "" else "",
                        type = type,
                        date = if (idxDate >= 0) cursor.getLong(idxDate) else 0L,
                        dateSent = if (idxDateSent >= 0) cursor.getLong(idxDateSent) else 0L,
                        read = if (idxRead >= 0) cursor.getInt(idxRead) == 1 else true,
                        address = address,
                        senderName = contactInfo?.displayName ?: address,
                        senderPhotoUri = contactInfo?.photoUri ?: "",
                        isMms = false,
                        status = if (idxStatus >= 0) cursor.getInt(idxStatus) else Message.STATUS_NONE,
                        subscriptionId = if (idxSubId >= 0) cursor.getInt(idxSubId) else -1
                    )
                )
                loadedSms++
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // ── MMS ──────────────────────────────────────────────────────────────────
    val mmsUri = Uri.parse("content://mms")
    val mmsProjection = arrayOf("_id", "date", "msg_box", "read", "sub")
    val mmsFallbackProjection = arrayOf("_id", "date", "msg_box", "read")
    try {
        val mmsCursor = try {
            contentResolver.query(
                mmsUri,
                mmsProjection,
                "thread_id = ?",
                arrayOf(threadId.toString()),
                "date DESC"
            )
        } catch (_: Exception) {
            contentResolver.query(
                mmsUri,
                mmsFallbackProjection,
                "thread_id = ?",
                arrayOf(threadId.toString()),
                "date DESC"
            )
        }

        mmsCursor?.use { cursor ->
            val idxId = cursor.getColumnIndex("_id")
            val idxDate = cursor.getColumnIndex("date")
            val idxMsgBox = cursor.getColumnIndex("msg_box")
            val idxRead = cursor.getColumnIndex("read")
            val idxSub = cursor.getColumnIndex("sub")

            var loadedMms = 0
            while (cursor.moveToNext() && loadedMms < cappedLimit) {
                if (idxId < 0 || idxDate < 0 || idxMsgBox < 0) continue

                val id      = cursor.getLong(idxId)
                val date    = cursor.getLong(idxDate) * 1000L   // MMS date is in seconds
                val msgBox  = cursor.getInt(idxMsgBox)
                val read    = if (idxRead >= 0) cursor.getInt(idxRead) == 1 else true
                val subject = if (idxSub >= 0) cursor.getString(idxSub) ?: "" else ""

                // MMS msg_box: 1=inbox, 2=sent, 4=outbox, 5=failed
                val type = when (msgBox) {
                    1    -> Message.TYPE_INBOX
                    2    -> Message.TYPE_SENT
                    4    -> Message.TYPE_OUTBOX
                    5    -> Message.TYPE_FAILED
                    else -> Message.TYPE_INBOX
                }

                // Derive sender address from MMS addr table
                val address     = getMmsAddress(id, msgBox)
                val contactInfo = if (type == Message.TYPE_INBOX)
                    contactHelper.resolve(address) else null

                // Real text body from text/plain part; fallback to subject or "MMS"
                val body = MmsHelper.getMmsDisplayBody(this, id, subject)

                // Store first supported media part URI for ThreadAdapter preview.
                val attachmentsJson = MmsHelper.getMmsImagePartUri(this, id)
                    ?: MmsHelper.getMmsAudioPartUri(this, id)
                    ?: "[]"

                messages.add(
                    Message(
                        id             = id,
                        threadId       = threadId,
                        body           = body,
                        type           = type,
                        date           = date,
                        read           = read,
                        address        = address,
                        senderName     = contactInfo?.displayName ?: address,
                        senderPhotoUri = contactInfo?.photoUri ?: "",
                        isMms          = true,
                        attachmentsJson = attachmentsJson,
                        status         = Message.STATUS_NONE
                    )
                )
                loadedMms++
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // ── Locally queued scheduled messages (Room) ────────────────────────────
    try {
        val queued = App.get().database.messagesDao().getMessagesForThread(threadId)
            .filter { it.isScheduled && it.type == Message.TYPE_QUEUED }
        messages.addAll(queued)
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // Sort all (SMS + MMS + local queued) by date ascending for chat display
    return messages.sortedBy { it.date }
}

/** Get the FROM or TO address for an MMS message. */
private fun Context.getMmsAddress(msgId: Long, msgBox: Int): String {
    val addrUri = Uri.parse("content://mms/$msgId/addr")
    val addrType = if (msgBox == 1) "137" else "151"   // PduHeaders.FROM=137, TO=151
    try {
        contentResolver.query(
            addrUri,
            arrayOf("address"),
            "type = ?",
            arrayOf(addrType),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val addr = cursor.getString(0)
                if (!addr.isNullOrBlank() && addr != "insert-address-token") {
                    return addr
                }
            }
        }
    } catch (e: Exception) {
        // ignore
    }
    return ""
}

/** Mark an entire thread as read in the system telephony provider. */
fun Context.markThreadAsReadInTelephony(threadId: Long) {
    try {
        val values = android.content.ContentValues().apply {
            put(Telephony.Sms.READ, 1)
        }
        contentResolver.update(
            Telephony.Sms.CONTENT_URI,
            values,
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
            arrayOf(threadId.toString())
        )
        // Also mark MMS rows for this thread as read
        try {
            val mmsValues = android.content.ContentValues().apply { put("read", 1) }
            contentResolver.update(
                android.net.Uri.parse("content://mms"),
                mmsValues,
                "thread_id = ? AND read = 0",
                arrayOf(threadId.toString())
            )
        } catch (e: Exception) {
            // Ignore MMS update failures — best-effort
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/** Delete all messages in a thread from the system telephony provider. */
fun Context.deleteThreadInTelephony(threadId: Long) {
    try {
        val uri = Uri.parse("content://mms-sms/conversations/$threadId")
        contentResolver.delete(uri, null, null)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/**
 * Fetches a single SMS message by its Telephony CP row ID.
 * Returns null if the message no longer exists (e.g. already hard-deleted).
 * Must be called from a background thread.
 */
fun Context.getSmsMessageById(id: Long, contactHelper: ContactHelper): Message? {
    val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)
    val projection = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.THREAD_ID,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.DATE_SENT,
        Telephony.Sms.TYPE,
        Telephony.Sms.READ,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.STATUS,
        Telephony.Sms.SUBSCRIPTION_ID
    )
    return try {
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: ""
            val type    = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE))
            val contactInfo = if (type == Message.TYPE_INBOX) contactHelper.resolve(address) else null
            Message(
                id             = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID)),
                threadId       = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)),
                body           = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: "",
                type           = type,
                date           = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)),
                dateSent       = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)),
                read           = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.READ)) == 1,
                address        = address,
                senderName     = contactInfo?.displayName ?: address,
                senderPhotoUri = contactInfo?.photoUri ?: "",
                isMms          = false,
                status         = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.STATUS)),
                subscriptionId = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID))
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
