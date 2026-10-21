package com.dpad.messaging.dumber

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dpad.messaging.R
import kotlinx.coroutines.*

/** One independent receive loop per account, with one foreground notification. */
class DumberSyncService: Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL,"dumber.chat connection",NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this,0,Intent(this,DumberConnectActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_new_message)
            .setContentTitle("dumber.chat").setContentText("Receiving encrypted messages for connected numbers").setContentIntent(open).setOngoing(true).setSilent(true).build()
        startForeground(8090,notification)
        if(!DumberAccount.enabled(this) || Telephony.Sms.getDefaultSmsPackage(this) != packageName) { stopSelf(); return START_NOT_STICKY }
        if(job?.isActive != true) job = scope.launch {
            val receivers = mutableMapOf<String, Job>()
            val simMonitor = DumberSimMonitor()
            DumberDisconnect.enqueue(this@DumberSyncService)
            try {
                while(isActive && DumberAccount.enabled(this@DumberSyncService)) {
                    if(Telephony.Sms.getDefaultSmsPackage(this@DumberSyncService) != packageName) break
                    simMonitor.check(this@DumberSyncService)
                    val accounts = DumberAccount.ids(this@DumberSyncService).filter { DumberAccount.enabled(this@DumberSyncService, it) }
                    receivers.keys.toList().filter { it !in accounts }.forEach { receivers.remove(it)?.cancel() }
                    for (account in accounts) if (receivers[account]?.isActive != true) receivers[account] = launch {
                        var nextStatusCheck = 0L
                        while (isActive && DumberAccount.enabled(this@DumberSyncService, account)) {
                            val sessionToken = DumberAccount.state(this@DumberSyncService, account).optJSONObject("session")?.optString("access_token").orEmpty()
                            try {
                                if (System.currentTimeMillis() >= nextStatusCheck) {
                                    DumberCore.call(DumberCore.request(this@DumberSyncService, "session_status", account))
                                    nextStatusCheck = System.currentTimeMillis() + 60_000
                                }
                                DumberMessages.sync(this@DumberSyncService, account); delay(300)
                            }
                            catch(e: CancellationException) { throw e }
                            catch(e: DumberException) {
                                if (e.httpStatus == 401) DumberAccount.revoked(this@DumberSyncService, account, sessionToken)
                                else { nextStatusCheck = 0L; delay(10_000) }
                            }
                            catch(_: Exception) { nextStatusCheck = 0L; delay(10_000) }
                        }
                    }
                    delay(1000)
                }
            } finally { receivers.values.forEach { it.cancel() }; stopSelf() }
        }
        return START_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        private const val CHANNEL = "dumber_connection"
        fun ensureStarted(context: Context) {
            DumberDisconnect.enqueue(context)
            if(!DumberAccount.enabled(context) || !DumberCore.available()) return
            runCatching { ContextCompat.startForegroundService(context,Intent(context,DumberSyncService::class.java)) }
        }
    }
}
