package com.dpad.messaging.receivers

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dpad.messaging.helpers.NotificationHelper
import kotlinx.coroutines.CancellationException

/** Durable SMS_DELIVER processing with an OEM-tolerant provider write. */
class SmsReceiveWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationHelper.receivingNotification(applicationContext)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            )
        } else {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        val address = inputData.getString(KEY_ADDRESS).orEmpty()
        val body = inputData.getString(KEY_BODY).orEmpty()
        val timestamp = inputData.getLong(KEY_TIMESTAMP, System.currentTimeMillis())
        if (address.isBlank()) return Result.failure()

        return try {
            SmsReceiveProcessor.process(
                applicationContext,
                address,
                body,
                timestamp,
                inputData.getInt(KEY_SUBSCRIPTION_ID, -1)
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "SMS processing failed for $address", error)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "DPAD_MSG"
        private const val MAX_ATTEMPTS = 5
        private const val FOREGROUND_NOTIFICATION_ID = 2_000_000_001
        private const val KEY_ADDRESS = "address"
        private const val KEY_BODY = "body"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_SUBSCRIPTION_ID = "subscription_id"
        private const val KEY_DELIVERY_ID = "delivery_id"

        fun enqueue(
            context: Context,
            address: String,
            body: String,
            timestamp: Long,
            subscriptionId: Int,
            deliveryId: String = java.util.UUID.randomUUID().toString()
        ): androidx.work.Operation {
            val request = OneTimeWorkRequestBuilder<SmsReceiveWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(
                    workDataOf(
                        KEY_ADDRESS to address,
                        KEY_BODY to body,
                        KEY_TIMESTAMP to timestamp,
                        KEY_SUBSCRIPTION_ID to subscriptionId,
                        KEY_DELIVERY_ID to deliveryId
                    )
                )
                .build()
            return WorkManager.getInstance(context).enqueueUniqueWork(
                "sms-receive-$deliveryId",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
