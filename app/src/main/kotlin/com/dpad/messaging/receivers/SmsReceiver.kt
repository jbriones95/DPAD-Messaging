package com.dpad.messaging.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives SMS_DELIVER synchronously and hands the parsed PDU to durable work.
 * Parsing must happen before onReceive returns; provider writes and notifications
 * are retried by WorkManager on OEM phones that aggressively manage background
 * processes.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val bundle = intent.extras ?: return
        val rawPdus = bundle.get("pdus")
        val pdus = when (rawPdus) {
            is Array<*> -> rawPdus.mapNotNull { it as? ByteArray }
            is java.util.ArrayList<*> -> rawPdus.mapNotNull { it as? ByteArray }
            else -> emptyList()
        }
        if (pdus.isEmpty()) return

        val format = bundle.getString("format") ?: "3gpp"
        val parts = pdus.mapNotNull { pdu ->
            runCatching { SmsMessage.createFromPdu(pdu, format) }.getOrNull()
        }
        if (parts.isEmpty()) return

        val address = parts.first().displayOriginatingAddress ?: return
        val body = parts.joinToString("") { it.displayMessageBody.orEmpty() }
        val timestamp = parts.first().timestampMillis
        val subscriptionId = intent.getIntExtra("subscription", -1)
            .takeIf { it >= 0 }
            ?: android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId()

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                SmsReceiveWorker.enqueue(
                    context,
                    address,
                    body,
                    timestamp,
                    subscriptionId
                ).result.await()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
