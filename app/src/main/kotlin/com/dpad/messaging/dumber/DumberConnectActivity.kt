package com.dpad.messaging.dumber

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import com.dpad.messaging.R
import com.dpad.messaging.helpers.ThemeManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dpad.messaging.activities.BaseActivity
import kotlinx.coroutines.*

/** Verify and link each number explicitly; number detection is only a convenience. */
class DumberConnectActivity: BaseActivity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private var connecting = false
    private var selected = -1
    private var refresh: Job? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { draw() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = savedInstanceState?.getInt("sim", -1)
            ?: DumberAccount.read(this).optJSONObject("verification")?.optInt("subscription", -1) ?: -1
        connecting = savedInstanceState?.getBoolean("connecting", false) ?: false
        draw()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("sim", selected); outState.putBoolean("connecting", connecting); super.onSaveInstanceState(outState) }
    private fun label(text: String, size: Float = 16f): TextView = TextView(this).apply {
        this.text=text;textSize=size;setPadding(0,12,0,12);isFocusable=false;isFocusableInTouchMode=false;root.addView(this)
    }
    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text=text;isAllCaps=false;isFocusable=true;isFocusableInTouchMode=true
        setOnClickListener { action() }
        root.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
    private fun draw() {
        val focusedTag = currentFocus?.tag
        ThemeManager.applyAccentColor(this)
        setContentView(R.layout.activity_dumber_connect)
        root = findViewById(R.id.connect_container)
        val back = findViewById<ImageButton>(R.id.btn_back)
        back.imageTintList = ThemeManager.focusAwareTint(this)
        back.setOnClickListener { goBack() }
        status = label("").apply { visibility = View.GONE }
        label(getString(R.string.dumber_privacy_notice), 13f)
        status.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                status.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        if(!DumberCore.available()) { status.text="This build does not include dumber.chat for this device.";return }
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 26 && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.READ_PHONE_NUMBERS)
        if (missing.isNotEmpty()) button("Allow SIM and phone number detection") { permissions.launch(missing.toTypedArray()) }
        val sims = DumberSubscriptions.active(this)
        val accounts = DumberAccount.ids(this)
        val sim = sims.singleOrNull { it.id == selected }
        if (connecting && sim != null && DumberAccount.forSim(this, sim) == null) {
            drawVerification(sim)
            return
        }
        connecting = false
        for (availableSim in sims) {
            val owner = DumberAccount.forSim(this, availableSim)
                ?: accounts.firstOrNull { DumberAccount.state(this, it).optString("phone") == availableSim.number }
            val number = owner?.let { DumberAccount.state(this, it).optString("phone") }
                ?.takeIf { it.isNotBlank() } ?: availableSim.number ?: availableSim.label
            numberButton(number, owner, availableSim).tag = "sim:${availableSim.id}"
        }
        if (sims.isEmpty()) status.text = "No active SIMs available."
        val previous = focusedTag?.let { root.findViewWithTag<View>(it) }
        (previous ?: (0 until root.childCount).map { root.getChildAt(it) }
            .firstOrNull { it.isFocusable } ?: back).requestFocus()
    }

    private fun numberButton(number: String, account: String?, sim: DumberSubscriptions.Sim): Button {
        val connected = account != null && DumberAccount.enabled(this, account)
        return button("$number (${if (connected) "connected" else "disconnected"})") {
            val actions = if (connected) arrayOf("Disconnect", "Link web app") else arrayOf("Connect")
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(number)
                .setItems(actions) { _, which ->
                    if (connected && account != null) {
                        if (which == 1) linkBrowser(account, number)
                        else androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Disconnect $number?")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Disconnect") { _, _ ->
                                DumberAccount.logout(this, account)
                                DumberDisconnect.enqueue(this)
                                DumberSyncService.ensureStarted(this)
                                draw()
                            }.show()
                    } else if (account != null && DumberAccount.logoutPending(this, account)) {
                        status.text = "Waiting for disconnect to complete…"
                    } else {
                        startConnecting(sim)
                    }
                }.show()
        }
    }

    private fun startConnecting(sim: DumberSubscriptions.Sim) {
        selected = sim.id
        connecting = true
        draw()
    }

    private fun goBack() {
        if (connecting) { connecting = false; draw() } else finish()
    }

    private fun drawVerification(sim: DumberSubscriptions.Sim) {
        val pending=DumberAccount.read(this).optJSONObject("verification")?.takeIf { it.optInt("subscription", -1) == selected }
        val phone=EditText(this).apply {
            hint="Your phone number with +country code";inputType=InputType.TYPE_CLASS_PHONE
            setText(pending?.optString("phone") ?: sim.number.orEmpty());root.addView(this)
        }
        phone.requestFocus()
        val connect=button("Send verification SMS") {}
        connect.setOnClickListener {
            if(Telephony.Sms.getDefaultSmsPackage(this) != packageName) { status.text="Set this app as the default SMS app first.";return@setOnClickListener }
            val number=DumberCore.international(this,phone.text.toString(),sim.id)
            if(number==null || !phone.text.toString().trim().startsWith("+")) { status.text="Enter your phone number with + and country code.";return@setOnClickListener }
            connect.isEnabled=false;status.text="Requesting verification SMS…"
            lifecycleScope.launch {
                try { DumberVerification.begin(this@DumberConnectActivity,number,sim.id);status.text="Waiting for the verification SMS on ${sim.label}…" }
                catch(e: CancellationException) { throw e }
                catch(e: Exception) { status.text=if (e is DumberException && e.httpStatus == 426) "Update this app before connecting to dumber.chat." else "Could not request a code. Check the selected SIM and connection, then try again." }
                finally { connect.isEnabled=true }
            }
        }
        val code=EditText(this).apply { hint="Verification code";inputType=InputType.TYPE_CLASS_NUMBER;root.addView(this) }
        button("Verify code") { DumberVerification.submit(this,code.text.toString().trim(),sim.id);code.text.clear() }
    }
    private fun linkBrowser(account: String, number: String) {
        val code = EditText(this).apply {
            hint = "Eight-digit web app code"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(8))
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Link web app for $number")
            .setView(code).setNegativeButton("Cancel", null)
            .setPositiveButton("Review") { _, _ ->
                val value = code.text.toString().trim()
                if (!Regex("[0-9]{8}").matches(value)) { status.text = "Enter the eight-digit browser code."; return@setPositiveButton }
                lifecycleScope.launch {
                    try {
                        val request = DumberCore.request(this@DumberConnectActivity, "pairing_review", account).put("code", value)
                        val reviewed = withContext(Dispatchers.IO) { DumberCore.call(request) }
                        androidx.appcompat.app.AlertDialog.Builder(this@DumberConnectActivity)
                            .setTitle("Approve browser for $number?")
                            .setMessage("Code: $value\n${reviewed.getString("description")}")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Approve") { _, _ ->
                                lifecycleScope.launch {
                                    try {
                                        // Use the reviewed session, not a session replaced while the dialog was open.
                                        withContext(Dispatchers.IO) { DumberCore.call(request.put("command", "pairing_approve")) }
                                        status.text = "Browser approved. Complete linking in the browser."
                                    } catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { status.text = e.message ?: "Could not approve browser. Try again." }
                                }
                            }.show()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { status.text = e.message ?: "Could not review code. Try again." }
                }
            }.show()
    }
    private fun revision(): String = DumberSubscriptions.active(this).toString() + DumberAccount.read(this).optJSONObject("sim_bindings")?.toString().orEmpty() + DumberAccount.removalWarnings(this).toString() +
        DumberAccount.ids(this).map { listOf(it, DumberAccount.enabled(this, it), DumberAccount.logoutPending(this, it)) }.toString()
    override fun onResume() {
        super.onResume()
        draw()
        refresh=lifecycleScope.launch {
            var revision = revision()
            while(isActive) {
                val current = revision()
                if (current != revision) { draw(); revision = current; DumberSyncService.ensureStarted(this@DumberConnectActivity) }
                val state=DumberAccount.read(this@DumberConnectActivity)
                state.optJSONObject("verification")?.takeIf { connecting && it.optInt("subscription", -1) == selected }?.let {
                    status.text=if(state.has("error")) state.getString("error") else if(System.currentTimeMillis()>it.optLong("expires")) "Code expired. Request a new verification SMS."
                        else if(it.has("code")) "Verifying…" else "Waiting for the verification SMS on ${it.optString("label")}…"
                }
                delay(1000)
            }
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STAR || keyCode == KeyEvent.KEYCODE_BACK) { goBack(); return true }
        return super.onKeyDown(keyCode, event)
    }
    override fun onPause() { refresh?.cancel();super.onPause() }
}
