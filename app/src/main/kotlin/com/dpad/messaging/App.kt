package com.dpad.messaging

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.dpad.messaging.databases.MessagesDatabase
import com.dpad.messaging.helpers.AppCoroutineScopes
import com.dpad.messaging.helpers.ContactHelper
import com.dpad.messaging.helpers.MmsSender
import com.dpad.messaging.helpers.Prefs
import com.dpad.messaging.helpers.ScheduledMessageIntegrityChecker
import com.dpad.messaging.helpers.BlockedListsMigration
import com.dpad.messaging.helpers.ThemeManager
import com.dpad.messaging.receivers.RescheduleAlarmsReceiver
import kotlinx.coroutines.launch

class App : Application() {

    lateinit var database: MessagesDatabase
        private set

    lateinit var contactHelper: ContactHelper
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = MessagesDatabase.getInstance(this)
        contactHelper = ContactHelper(this)
        Prefs.init(this)
        MmsSender.initLibraryReceive()
        ThemeManager.applyThemeMode(Prefs.get().appThemeMode)
        refreshNotificationChannels()
        AppCoroutineScopes.io.launch {
            RescheduleAlarmsReceiver.reschedulePending(this@App)
            ScheduledMessageIntegrityChecker.run(this@App)
            // Ensure legacy numeric keywords are migrated to blocked_numbers table.
            BlockedListsMigration.migrate()
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        AppCoroutineScopes.cancelAll()
    }

    fun refreshNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val accent = ThemeManager.accentColor(this)

            // Default incoming message channel
            val incomingChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming SMS and MMS messages"
                enableVibration(true)
                enableLights(true)
                lightColor = accent
            }
            manager.createNotificationChannel(incomingChannel)

            // Send failure channel
            val failureChannel = NotificationChannel(
                CHANNEL_SEND_FAILURE,
                "Send Failures",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications when a message fails to send"
            }
            manager.createNotificationChannel(failureChannel)
        }
    }

    companion object {
        const val CHANNEL_MESSAGES = "dpad_messages_incoming"
        const val CHANNEL_SEND_FAILURE = "dpad_messages_send_failure"

        @Volatile
        private var instance: App? = null

        fun get(): App = instance ?: throw IllegalStateException("App not initialized")
    }
}
