package com.dpad.messaging.dumber

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.dpad.messaging.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Require a stable, trustworthy subscription snapshot before disabling an account. */
class DumberSimMonitor {
    private var candidate: Set<Int>? = null
    private var since = 0L
    fun check(context: Context, active: Set<Int>? = DumberSubscriptions.stableActiveIds(context),
              now: Long = SystemClock.elapsedRealtime()): List<String> {
        if (active == null) { candidate = null; return emptyList() }
        if (candidate != active) { candidate = active.toSet(); since = now; return emptyList() }
        if (now - since < 5000) return emptyList()
        // Avoid rewriting encrypted storage on every service tick.
        val bindings = DumberAccount.read(context).optJSONObject("sim_bindings") ?: JSONObject()
        if (DumberAccount.ids(context).none { account -> DumberAccount.enabled(context, account) &&
                bindings.keys().asSequence().none { it.toIntOrNull() in active &&
                    bindings.getJSONObject(it).optString("account") == account } }) return emptyList()
        val warnings = DumberAccount.disconnectMissing(context, active)
        if (warnings.isNotEmpty()) {
            DumberDisconnect.enqueue(context)
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(
                "dumber_sim_removed", "dumber.chat disconnections", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(context, 8091, Intent(context, DumberConnectActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // The persistent in-app warning also works when notifications are denied.
            runCatching { manager.notify(8091, NotificationCompat.Builder(context, "dumber_sim_removed")
                .setSmallIcon(R.drawable.ic_new_message).setContentTitle("dumber.chat disconnected")
                .setContentText(warnings.joinToString("\n"))
                .setStyle(NotificationCompat.BigTextStyle().bigText(DumberAccount.removalWarnings(context).joinToString("\n")))
                .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()) }
        }
        return warnings
    }
}

object DumberDisconnect {
    fun enqueue(context: Context) {
        if ((DumberAccount.read(context).optJSONObject("pending_logouts")?.length() ?: 0) == 0) return
        WorkManager.getInstance(context).enqueueUniqueWork("dumber-logout", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<DumberLogoutWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun flush(context: Context, call: (JSONObject) -> JSONObject = DumberCore::call): Boolean {
        val pending = DumberAccount.read(context).optJSONObject("pending_logouts") ?: return true
        for (id in pending.keys()) {
            val session = pending.getJSONObject(id).getJSONObject("session")
            try {
                call(JSONObject().put("command", "logout").put("service", DumberCore.SERVICE).put("session", session))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // An expired/revoked token is already disconnected. Retry all other failures.
                if (e !is DumberException || e.httpStatus != 401) continue
            }
            DumberAccount.update(context) { it.optJSONObject("pending_logouts")?.remove(id) }
        }
        return (DumberAccount.read(context).optJSONObject("pending_logouts")?.length() ?: 0) == 0
    }
}

class DumberLogoutWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (DumberDisconnect.flush(applicationContext)) Result.success() else Result.retry()
    }
}
