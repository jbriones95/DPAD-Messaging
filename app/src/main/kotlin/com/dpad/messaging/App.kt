package com.dpad.messaging

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.dpad.messaging.databases.MessagesDatabase
import com.dpad.messaging.helpers.AppCoroutineScopes
import com.dpad.messaging.helpers.ContactHelper
import com.dpad.messaging.helpers.MmsHttpOverrides
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
        com.dpad.messaging.dumber.SimRemovalUi.install(this)
        database = MessagesDatabase.getInstance(this)
        contactHelper = ContactHelper(this)
        Prefs.init(this)
        MmsSender.initLibraryReceive()
        MmsHttpOverrides.install()
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

            createIncomingMessageChannel(
                manager = manager,
                id = CHANNEL_MESSAGES_LONG,
                name = "Messages",
                vibrationPattern = longArrayOf(0L, 1000L),
                accent = accent
            )
            createIncomingMessageChannel(
                manager = manager,
                id = CHANNEL_MESSAGES_DOUBLE,
                name = "Messages (double vibration)",
                vibrationPattern = longArrayOf(0L, 250L, 150L, 250L),
                accent = accent
            )

            val quietIncomingChannel = NotificationChannel(
                CHANNEL_MESSAGES_QUIET,
                "Messages (quiet)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Incoming SMS and MMS messages without heads-up banners"
                enableVibration(false)
                enableLights(false)
            }
            manager.createNotificationChannel(quietIncomingChannel)

            // Send failure channel
            val failureChannel = NotificationChannel(
                CHANNEL_SEND_FAILURE,
                "Send Failures",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications when a message fails to send"
            }
            manager.createNotificationChannel(failureChannel)

            val backupChannel = NotificationChannel(
                CHANNEL_BACKUP,
                "Backup and restore",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Durable app-local backup and restore progress"
            }
            manager.createNotificationChannel(backupChannel)
        }
    }

    private fun createIncomingMessageChannel(
        manager: NotificationManager,
        id: String,
        name: String,
        vibrationPattern: LongArray,
        accent: Int
    ) {
        val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Incoming SMS and MMS messages"
            enableVibration(true)
            this.vibrationPattern = vibrationPattern
            enableLights(true)
            lightColor = accent
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_MESSAGES = "dpad_messages_incoming"
        const val CHANNEL_MESSAGES_LONG = "dpad_messages_incoming_long"
        const val CHANNEL_MESSAGES_DOUBLE = "dpad_messages_incoming_double"
        const val CHANNEL_MESSAGES_QUIET = "dpad_messages_incoming_quiet"

        fun messageChannelId(): String {
            val prefs = Prefs.get()
            return if (prefs.messageNotificationStyle == Prefs.NOTIFICATION_STYLE_QUIET) {
                CHANNEL_MESSAGES_QUIET
            } else if (prefs.messageVibrationStyle == Prefs.VIBRATION_STYLE_DOUBLE) {
                CHANNEL_MESSAGES_DOUBLE
            } else {
                CHANNEL_MESSAGES_LONG
            }
        }
        const val CHANNEL_SEND_FAILURE = "dpad_messages_send_failure"
        const val CHANNEL_BACKUP = "dpad_backup_restore"

        @Volatile
        private var instance: App? = null

        fun get(): App = instance ?: throw IllegalStateException("App not initialized")
    }
}
