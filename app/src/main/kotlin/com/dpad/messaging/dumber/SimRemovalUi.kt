package com.dpad.messaging.dumber

import android.app.*
import android.content.*
import android.os.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.dpad.messaging.R
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/** Foreground dialogs plus a notification when Android does not allow background UI. */
internal object SimRemovalUi : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var installed = false
    private var resumed = WeakReference<Activity>(null)
    private var dialog: AlertDialog? = null
    private var showing: String? = null
    private val dismissed = mutableSetOf<String>()
    private var generation = 0
    private var resolving = false

    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(this)
        refresh(application)
    }
    fun refresh(context: Context) {
        if (!installed) return
        val app = context.applicationContext
        main.post {
            val ticket = ++generation
            io.execute {
                val prompts = runCatching { SimRemovalChoices.pending(app) }.getOrNull()
                    ?: return@execute
                main.post display@ {
                    if (ticket != generation) return@display
                    notify(app, prompts)
                    if (resolving) return@display
                    val activity = resumed.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                        ?: return@display
                    if (showing != null && prompts.none { it.nonce == showing }) {
                        dialog?.dismiss()
                        dialog = null
                        showing = null
                    }
                    if (dialog != null) return@display
                    val prompt = prompts.firstOrNull { it.nonce !in dismissed } ?: return@display
                    showing = prompt.nonce
                    val current = AlertDialog.Builder(activity)
                        .setTitle("SIM removed or changed")
                        .setMessage("The SIM linked to ${prompt.phone} is no longer available. " +
                            "Keep dumber.chat connected, or deregister this connection?")
                        .setPositiveButton("Keep connection") { _, _ -> decide(app, prompt, true) }
                        .setNegativeButton("Deregister") { _, _ -> decide(app, prompt, false) }
                        .setOnCancelListener { dismissed.add(prompt.nonce) }
                        .create()
                    dialog = current
                    current.setOnDismissListener {
                        if (dialog === current) { dialog = null; showing = null }
                    }
                    current.show()
                }
            }
        }
    }
    private fun decide(context: Context, prompt: RemovalPrompt, keep: Boolean) {
        resolving = true
        io.execute {
            val result = runCatching { SimRemovalChoices.resolve(context, prompt, keep) }
            main.post {
                resolving = false
                result.exceptionOrNull()?.let {
                    resumed.get()?.let { activity ->
                        Toast.makeText(activity, "Couldn't save the choice. Please try again.", Toast.LENGTH_LONG).show()
                    }
                }
                refresh(context)
            }
        }
    }
    private fun notify(context: Context, prompts: List<RemovalPrompt>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (prompts.isEmpty()) { manager.cancel(8091); return }
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel("dumber_sim_choice", "dumber.chat SIM changes", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 8091,
            Intent(context, DumberConnectActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            manager.notify(8091, NotificationCompat.Builder(context, "dumber_sim_choice")
                .setSmallIcon(R.drawable.ic_new_message)
                .setContentTitle("SIM removed or changed")
                .setContentText("Choose whether to keep dumber.chat connected")
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPriority(NotificationCompat.PRIORITY_HIGH).build())
        }
    }
    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        dismissed.clear()
        refresh(activity)
    }
    override fun onActivityPaused(activity: Activity) {
        if (resumed.get() === activity) {
            resumed.clear()
            generation++
            dialog?.dismiss()
            dialog = null
            showing = null
        }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
