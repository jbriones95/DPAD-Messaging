package com.dpad.messaging.dumber

import chat.dumber.sdk.*

import android.content.Context
import android.os.SystemClock
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Require stable telephony before asking; absence alone never logs out a session. */
class DumberSimMonitor {
    private var candidate: Set<Int>? = null
    private var since = 0L
    fun check(context: Context, active: Set<Int>? = DumberSubscriptions.stableActiveIds(context),
              now: Long = SystemClock.elapsedRealtime()) {
        if (active == null) { candidate = null; return }
        if (candidate != active) { candidate = active.toSet(); since = now; return }
        if (now - since < 5000) return
        if (DumberAccount.reviewMissing(context, active)) SimRemovalUi.refresh(context)
    }
}

object DumberDisconnect {
    fun enqueue(context: Context) {
        if ((DumberAccount.read(context).optJSONObject("pending_logouts")?.length() ?: 0) == 0) return
        WorkManager.getInstance(context).enqueueUniqueWork("dumber-logout", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<DumberLogoutWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun flush(context: Context, logout: (Session) -> Unit = { DumberCore.client(it).logout() }): Boolean {
        val pending = DumberAccount.read(context).optJSONObject("pending_logouts") ?: return true
        for (id in pending.keys()) {
            val session = ChatStorage.decodeSession(pending.getJSONObject(id).getString("session"))
            try {
                logout(session)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // An expired/revoked token is already disconnected. Retry all other failures.
                if (e !is ChatException || e.kind != ErrorKind.AUTHENTICATION_REQUIRED) continue
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
