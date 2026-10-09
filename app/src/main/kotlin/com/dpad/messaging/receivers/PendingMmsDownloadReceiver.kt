package com.dpad.messaging.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dpad.messaging.helpers.MmsDownloadPolicy

/** Starts a deferred platform MMS download after the user taps Download. */
class PendingMmsDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        val subscriptionId = intent.getIntExtra(EXTRA_SUBSCRIPTION_ID, -1)
        if (messageId > 0L) {
            MmsDownloadPolicy.downloadPending(context, messageId, subscriptionId)
        }
    }

    companion object {
        const val EXTRA_MESSAGE_ID = "extra_message_id"
        const val EXTRA_SUBSCRIPTION_ID = "extra_subscription_id"
    }
}
