package com.dpad.messaging.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.content.ContextCompat
import com.dpad.messaging.dumber.DumberSubscriptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receives SMS_DELIVER synchronously and hands the parsed PDU to durable work.
 * If WorkManager cannot enqueue, the same processor writes the message directly
 * before the broadcast is finished.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        var pendingResult: BroadcastReceiver.PendingResult? = null
        val finished = AtomicBoolean(false)
        fun finishOnce() {
            if (finished.compareAndSet(false, true)) pendingResult?.finish()
        }

        try {
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
            val subscriptionId = runCatching {
                DumberSubscriptions.receivedSubscription(intent)
            }.getOrDefault(-1)
            val deliveryId = UUID.randomUUID().toString()

            pendingResult = goAsync()
            val fallbackStarted = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val timeoutRef = java.util.concurrent.atomic.AtomicReference<Runnable>()
            val fallback: (Throwable) -> Unit = { error ->
                if (fallbackStarted.compareAndSet(false, true)) {
                    timeoutRef.get()?.let { handler.removeCallbacks(it) }
                    Log.e(TAG, "WorkManager enqueue failed; writing SMS directly", error)
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        try {
                            SmsReceiveProcessor.process(
                                context,
                                address,
                                body,
                                timestamp,
                                subscriptionId
                            )
                        } catch (fallbackError: Throwable) {
                            Log.e(TAG, "Direct SMS receive fallback failed", fallbackError)
                        } finally {
                            finishOnce()
                        }
                    }
                }
            }

            val timeout = Runnable {
                fallback(TimeoutException("WorkManager enqueue timed out"))
            }
            timeoutRef.set(timeout)
            try {
                val operation = SmsReceiveWorker.enqueue(
                    context,
                    address,
                    body,
                    timestamp,
                    subscriptionId,
                    deliveryId
                )
                handler.postDelayed(timeout, ENQUEUE_TIMEOUT_MS)
                operation.result.addListener(
                    {
                        if (fallbackStarted.get()) return@addListener
                        handler.removeCallbacks(timeout)
                        try {
                            operation.result.get()
                            finishOnce()
                        } catch (error: Throwable) {
                            fallback(error)
                        }
                    },
                    ContextCompat.getMainExecutor(context)
                )
            } catch (error: Throwable) {
                fallback(error)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to process incoming SMS broadcast", error)
            finishOnce()
        }
    }

    private companion object {
        const val TAG = "DPAD_MSG"
        const val ENQUEUE_TIMEOUT_MS = 5_000L
    }
}
