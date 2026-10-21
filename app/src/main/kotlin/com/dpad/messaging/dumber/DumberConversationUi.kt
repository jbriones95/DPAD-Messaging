package com.dpad.messaging.dumber

import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dpad.messaging.R
import com.dpad.messaging.helpers.SmsWhitelistManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Owned by the conversation, never by the main screen or contact picker. */
class DumberConversationUi(
    private val activity: AppCompatActivity,
    private val statusButton: Button,
    private val pendingButton: Button,
    private val phone: () -> String,
    private val thread: () -> Long,
    private val subscription: () -> Int,
    private val carrierOnly: () -> Boolean,
    private val engineAvailable: () -> Boolean = { DumberCore.available() },
    private val discover: suspend (String, Boolean, String) -> DumberDirectory.Status = { number, force, account -> DumberDirectory.lookup(activity, number, force, account = account) }
) {
    private var job: Job? = null
    private var checking = false
    private var reason = ""
    private var pending = emptyList<JSONObject>()
    private val prompted = mutableSetOf<String>()
    private var actionRunning = false
    private var lookupKey = ""

    init {
        statusButton.setOnClickListener {
            if (!carrierOnly() && owner() == null) {
                activity.startActivity(android.content.Intent(activity, DumberConnectActivity::class.java))
                return@setOnClickListener
            }
            if (!eligible() || checking) return@setOnClickListener
            AlertDialog.Builder(activity).setTitle(R.string.dumber_recheck)
                .setMessage(reason.ifBlank { activity.getString(R.string.dumber_recheck_detail) })
                .setPositiveButton(R.string.dumber_recheck) { _, _ -> open(force = true) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        pendingButton.setOnClickListener {
            if (pending.size == 1) show(pending.first())
            else if (pending.isNotEmpty()) AlertDialog.Builder(activity).setTitle(R.string.dumber_send_pending)
                .setItems(pending.map { it.getString("body").take(80) }.toTypedArray()) { _, index -> show(pending[index]) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }
    private fun number() = DumberCore.international(activity, phone(), subscription())
    private fun owner() = DumberAccount.forSubscription(activity, subscription())
    private fun eligible() = !carrierOnly() && owner()?.let { DumberAccount.enabled(activity, it) } == true
    fun render() {
        val status = if (eligible()) number()?.let { DumberDirectory.cached(activity, it, account = owner().orEmpty()) } else null
        val statusText = when {
            !carrierOnly() && owner() == null && DumberAccount.needsLegacyLink(activity) -> R.string.dumber_status_link_sim
            !carrierOnly() && DumberAccount.connected(activity) && owner() == null -> R.string.dumber_status_sim_unconnected
            !eligible() -> R.string.dumber_status_sms
            status?.absent == true -> R.string.dumber_status_sms
            !engineAvailable() -> R.string.dumber_status_unknown
            status?.peer != null -> R.string.dumber_status_encrypted
            checking -> R.string.dumber_status_checking
            else -> R.string.dumber_status_unknown
        }
        statusButton.visibility = if (DumberCore.available() && DumberAccount.connected(activity)) View.VISIBLE else View.GONE
        val text = activity.getString(statusText)
        statusButton.text = text + (if (eligible()) " · " + DumberAccount.state(activity, owner().orEmpty()).optString("phone") else "")
        statusButton.contentDescription = statusButton.text.toString() + if (reason.isBlank()) "" else ". $reason"
    }
    fun open(force: Boolean = false) {
        refreshPending()
        val key = "${owner()}|${thread()}|${phone()}|${subscription()}"
        if (checking && lookupKey == key && !force) { render(); return }
        lookupKey = key
        job?.cancel(); checking = false; reason = ""
        render()
        if (!eligible()) return
        if (!engineAvailable()) {
            reason = "Encryption engine unavailable. Your message will wait for a retry or your approval to send SMS."
            render(); return
        }
        val number = number() ?: return
        val account = owner() ?: return
        if (!SmsWhitelistManager.check(activity, number).allowed) return
        checking = true; render()
        job = activity.lifecycleScope.launch {
            val result = discover(number, force, account)
            if (lookupKey == key) {
                checking = false; reason = result.reason; render()
            }
        }
    }
    fun refreshPending(prompt: Boolean = false) {
        val owners = DumberAccount.ids(activity)
        val threadId = thread()
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { DumberOutbox(activity).use { owners.flatMap { account -> it.list(account, threadId) }.filterNot { value -> value.optBoolean("confirmed") } } }
            if (thread() != threadId || DumberAccount.ids(activity) != owners) return@launch
            pending = result
            pendingButton.visibility = if (result.isEmpty()) View.GONE else View.VISIBLE
            pendingButton.text = activity.getString(R.string.dumber_pending_count, result.size)
            if (prompt && !actionRunning) result.firstOrNull { !prompted.contains(it.getString("id")) }?.let { show(it) }
        }
    }
    private fun show(message: JSONObject) {
        if (actionRunning) return
        val id = message.getString("id")
        prompted.add(id)
        val availableAgain = DumberDirectory.cached(activity, message.getString("phone"), account = message.getString("account")).peer != null
        if (message.optBoolean("recipient_unavailable") && !availableAgain) {
            AlertDialog.Builder(activity).setTitle(R.string.dumber_recipient_unavailable)
                .setMessage(origin(message) + "\n\n" + message.getString("body") + "\n\n" +
                    activity.getString(R.string.dumber_recipient_unavailable_sms) + "\n\n" +
                    activity.getString(if (message.optBoolean("uncertain")) R.string.dumber_sms_uncertain else R.string.dumber_sms_confirmation))
                .setPositiveButton(R.string.dumber_send_sms) { _, _ -> action { DumberDelivery.sendSms(activity, id) } }
                .setNegativeButton(android.R.string.cancel, null).show()
            render()
            return
        }
        AlertDialog.Builder(activity).setTitle(R.string.dumber_send_pending)
            .setMessage(origin(message) + "\n\n" + message.getString("body") + "\n\n" + message.optString("reason", activity.getString(R.string.dumber_send_pending_detail)))
            .setPositiveButton(R.string.dumber_retry_encrypted) { _, _ -> action { DumberDelivery.retry(activity, id) } }
            .setNeutralButton(R.string.dumber_send_sms) { _, _ ->
                AlertDialog.Builder(activity).setTitle(R.string.dumber_send_sms)
                    .setMessage(origin(message) + "\n\n" + activity.getString(if (message.optBoolean("uncertain")) R.string.dumber_sms_uncertain else R.string.dumber_sms_confirmation))
                    .setPositiveButton(R.string.dumber_send_sms) { _, _ -> action { DumberDelivery.sendSms(activity, id) } }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun origin(message: JSONObject): String = "From " + DumberAccount.state(activity, message.getString("account")).optString("phone") + " · " + message.optString("sim_label", "original SIM")
    private fun action(block: suspend () -> Unit) {
        actionRunning = true
        activity.lifecycleScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(activity, e.message ?: activity.getString(R.string.dumber_send_pending_detail), Toast.LENGTH_LONG).show() }
            finally { actionRunning = false; refreshPending(); render() }
        }
    }
}
