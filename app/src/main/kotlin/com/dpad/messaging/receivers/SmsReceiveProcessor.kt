package com.dpad.messaging.receivers

import android.content.Context
import com.dpad.messaging.App
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.helpers.MessageCache
import com.dpad.messaging.helpers.NotificationHelper
import com.dpad.messaging.helpers.PhoneNumberMatcher
import com.dpad.messaging.helpers.Prefs
import com.dpad.messaging.helpers.ReactionParser
import com.dpad.messaging.helpers.SmsWhitelistManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus

/** Shared SMS persistence, refresh, and notification path. */
internal object SmsReceiveProcessor {

    suspend fun process(
        context: Context,
        address: String,
        body: String,
        timestamp: Long,
        subscriptionId: Int
    ): Boolean = withContext(Dispatchers.IO) {
        val filter = SmsWhitelistManager.check(context, address)
        if (!filter.allowed) return@withContext false

        val stored = SmsPersistence.store(context, address, body, timestamp, subscriptionId)

        // Persistence is complete. Optional refresh work must never fail the write.
        runCatching {
            MessageCache.invalidate(stored.threadId)
            EventBus.getDefault().post(RefreshConversations())
            EventBus.getDefault().post(RefreshMessages(stored.threadId))
        }

        try {
            com.dpad.messaging.dumber.DumberVerification.receive(
                context,
                body,
                timestamp,
                subscriptionId
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Dumber verification is optional for ordinary SMS delivery.
        }

        val blockedNumbers = runCatching { App.get().database.blockedNumbersDao().getAll() }.getOrNull()
        val blockedKeywords = runCatching { App.get().database.blockedKeywordsDao().getAll() }.getOrNull()
        if (blockedNumbers == null || blockedKeywords == null) return@withContext true

        val blocked = blockedNumbers.any { PhoneNumberMatcher.equivalent(it.number, address) } ||
            blockedKeywords.any { body.contains(it.keyword, ignoreCase = true) }
        if (blocked) return@withContext true

        val senderName = runCatching {
            App.get().contactHelper.getDisplayName(address)
        }.getOrDefault(address)
        runCatching {
            NotificationHelper.showIncomingNotification(
                context,
                stored.threadId,
                senderName,
                address,
                if (Prefs.get().convertReactions) ReactionParser.displayText(body) else body
            )
        }
        true
    }
}
