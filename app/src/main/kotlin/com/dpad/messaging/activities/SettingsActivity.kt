package com.dpad.messaging.activities

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.dpad.messaging.App
import com.dpad.messaging.BuildConfig
import com.dpad.messaging.R
import com.dpad.messaging.databinding.ActivitySettingsBinding
import com.dpad.messaging.helpers.BackupManager
import com.dpad.messaging.helpers.Prefs
import com.dpad.messaging.helpers.ThemeManager
import com.dpad.messaging.helpers.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings screen — Phase 3.
 *
 * All rows are programmatically built using four helpers:
 *   sectionHeader  — non-focusable teal section label
 *   toggleRow      — full-width row with a SwitchCompat on the right; ENTER/CENTER toggles
 *   valueRow       — full-width row with the current option text on the right;
 *                    ENTER/CENTER opens a single-choice dialog
 *   navRow         — full-width row with "›" on the right; ENTER/CENTER invokes onClick
 *
 * Sections:
 *   Messaging: Delivery Reports, Send on Enter, Character Counter
 *   Privacy:   Lock Screen Privacy, Blocked Keywords
 *   Storage:   Use Recycle Bin
 *   Other:     Notifications, About
 */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    // Cached pixel values computed once in onCreate
    private var paddingH = 0
    private var paddingV = 0
    private var rowMinH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyAccentColor(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get()
        paddingH = resources.getDimensionPixelSize(R.dimen.padding_medium)
        paddingV = resources.getDimensionPixelSize(R.dimen.padding_small)
        rowMinH  = resources.getDimensionPixelSize(R.dimen.conversation_item_height)

        binding.btnBack.setOnClickListener { finish() }
        applyAccent()

        buildSettingsRows()
    }

    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.backup_warning_title)
                .setMessage(R.string.backup_warning_message)
                .setPositiveButton(R.string.backup_confirm) { _, _ ->
                    lifecycleScope.launch {
                        try {
                            val json = withContext(Dispatchers.IO) { BackupManager.backup(this@SettingsActivity) }
                            withContext(Dispatchers.IO) {
                                contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                            }
                            Toast.makeText(this@SettingsActivity, R.string.backup_success, Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@SettingsActivity, "${getString(R.string.backup_failed)} ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    val json = withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                    }
                    if (json.isBlank()) {
                        Toast.makeText(this@SettingsActivity, R.string.restore_invalid, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val result = withContext(Dispatchers.IO) { BackupManager.restore(this@SettingsActivity, json) }
                    val msg = if (result.success) R.string.restore_success else R.string.restore_failed
                    Toast.makeText(this@SettingsActivity, getString(msg) + " " + result.message, Toast.LENGTH_LONG).show()
                    if (result.success) recreate()
                } catch (e: Exception) {
                    Toast.makeText(this@SettingsActivity, "${getString(R.string.restore_failed)} ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyAccent()
    }

    private fun applyAccent() {
        val tint = ColorStateList.valueOf(ThemeManager.accentColor(this))
        binding.btnBack.imageTintList = tint
        binding.btnBack.backgroundTintList = tint
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_STAR) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }

    // ─── Row population ───────────────────────────────────────────────────────

    private fun buildSettingsRows() {
        val c = binding.settingsContainer

        // ── General ──────────────────────────────────────────────────────────
        sectionHeader(c, getString(R.string.general))

        valueRow(
            container    = c,
            label        = getString(R.string.app_theme),
            summary      = getString(R.string.app_theme_summary),
            getValue     = { prefs.appThemeMode },
            optionValues = listOf(Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK),
            optionLabels = listOf(
                getString(R.string.theme_system),
                getString(R.string.theme_light),
                getString(R.string.theme_dark)
            ),
            setValue     = {
                prefs.appThemeMode = it
                ThemeManager.applyThemeMode(it)
                recreate()
            }
        )
        valueRow(
            container    = c,
            label        = getString(R.string.accent_color),
            summary      = getString(R.string.accent_color_summary),
            getValue     = { prefs.appAccent },
            optionValues = listOf(
                Prefs.ACCENT_BLUE,
                Prefs.ACCENT_GREEN,
                Prefs.ACCENT_ORANGE,
                Prefs.ACCENT_ROSE
            ),
            optionLabels = listOf(
                getString(R.string.accent_blue),
                getString(R.string.accent_green),
                getString(R.string.accent_orange),
                getString(R.string.accent_rose)
            ),
            setValue     = {
                prefs.appAccent = it
                App.get().refreshNotificationChannels()
                recreate()
            }
        )
        valueRow(
            container    = c,
            label        = getString(R.string.date_format),
            summary      = getString(R.string.date_format_summary),
            getValue     = { prefs.dateFormat },
            optionValues = listOf(Prefs.DATE_FORMAT_MDY, Prefs.DATE_FORMAT_DMY),
            optionLabels = listOf(
                getString(R.string.date_mdy),
                getString(R.string.date_dmy)
            ),
            setValue     = { prefs.dateFormat = it }
        )
        valueRow(
            container    = c,
            label        = getString(R.string.time_format),
            summary      = getString(R.string.time_format_summary),
            getValue     = { prefs.timeFormat },
            optionValues = listOf(Prefs.TIME_FORMAT_12H, Prefs.TIME_FORMAT_24H),
            optionLabels = listOf(
                getString(R.string.time_12h),
                getString(R.string.time_24h)
            ),
            setValue     = { prefs.timeFormat = it }
        )
        valueRow(
            container    = c,
            label        = getString(R.string.ui_scale),
            summary      = getString(R.string.ui_scale_summary),
            getValue     = { prefs.uiScale },
            optionValues = listOf(
                Prefs.UI_SCALE_COMPACT,
                Prefs.UI_SCALE_NORMAL,
                Prefs.UI_SCALE_LARGE,
                Prefs.UI_SCALE_XLARGE
            ),
            optionLabels = listOf(
                getString(R.string.ui_scale_compact),
                getString(R.string.ui_scale_normal),
                getString(R.string.ui_scale_large),
                getString(R.string.ui_scale_xlarge)
            ),
            setValue     = {
                prefs.uiScale = it
                recreate()
            }
        )

        // ── Messaging ────────────────────────────────────────────────────────
        sectionHeader(c, getString(R.string.messaging))

        toggleRow(
            container = c,
            label     = getString(R.string.delivery_reports),
            summary   = getString(R.string.delivery_reports_summary),
            getValue  = { prefs.deliveryReports },
            setValue  = { prefs.deliveryReports = it }
        )
        toggleRow(
            container = c,
            label     = getString(R.string.send_on_enter),
            summary   = getString(R.string.send_on_enter_summary),
            getValue  = { prefs.sendOnEnter },
            setValue  = { prefs.sendOnEnter = it }
        )
        toggleRow(
            container = c,
            label     = getString(R.string.character_counter),
            summary   = getString(R.string.character_counter_summary),
            getValue  = { prefs.characterCounter },
            setValue  = { prefs.characterCounter = it }
        )

        sectionHeader(c, getString(R.string.mms_settings))

        toggleRow(
            container = c,
            label     = getString(R.string.send_group_message_mms),
            summary   = getString(R.string.send_group_message_mms_summary),
            getValue  = { prefs.sendGroupMessageMms },
            setValue  = { prefs.sendGroupMessageMms = it }
        )

        // ── Privacy ──────────────────────────────────────────────────────────
        sectionHeader(c, getString(R.string.privacy))

        valueRow(
            container    = c,
            label        = getString(R.string.lock_screen_privacy),
            summary      = getString(R.string.lock_screen_privacy_summary),
            getValue     = { prefs.lockScreenPrivacy },
            optionValues = listOf(Prefs.PRIVACY_FULL, Prefs.PRIVACY_SENDER_ONLY),
            optionLabels = listOf(
                getString(R.string.show_sender_and_message),
                getString(R.string.show_sender_only)
            ),
            setValue     = { prefs.lockScreenPrivacy = it }
        )
        navRow(
            container = c,
            label     = getString(R.string.manage_blocked_keywords),
            summary   = getString(R.string.blocked_keywords_summary),
            onClick   = {
                startActivity(Intent(this, BlockedKeywordsActivity::class.java))
            }
        )
        navRow(
            container = c,
            label     = getString(R.string.manage_blocked_numbers),
            summary   = getString(R.string.blocked_numbers_summary),
            onClick   = {
                startActivity(Intent(this, BlockedNumbersActivity::class.java))
            }
        )

        // ── Storage ──────────────────────────────────────────────────────────
        sectionHeader(c, getString(R.string.storage))

        toggleRow(
            container = c,
            label     = getString(R.string.recycle_bin_enabled),
            summary   = getString(R.string.recycle_bin_enabled_summary),
            getValue  = { prefs.recycleBinEnabled },
            setValue  = { prefs.recycleBinEnabled = it }
        )

        // ── Backup & Restore ──────────────────────────────────────────────────
        sectionHeader(c, getString(R.string.backup))

        navRow(
            container = c,
            label     = getString(R.string.backup_create),
            summary   = getString(R.string.backup_create_summary),
            onClick   = {
                val datePart = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                backupLauncher.launch("DPAD-Messaging-backup-$datePart.json")
            }
        )
        navRow(
            container = c,
            label     = getString(R.string.backup_restore),
            summary   = getString(R.string.backup_restore_summary),
            onClick   = {
                restoreLauncher.launch(arrayOf("application/json", "*/*"))
            }
        )

        // ── Other ─────────────────────────────────────────────────────────────
        sectionHeader(c, getString(R.string.other))

        navRow(
            container = c,
            label     = getString(R.string.check_for_updates),
            summary   = getString(R.string.check_for_updates_summary),
            onClick   = { checkForUpdates() }
        )
        navRow(
            container = c,
            label     = getString(R.string.notifications),
            summary   = getString(R.string.notifications_summary),
            onClick   = {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            }
        )
        navRow(
            container = c,
            label     = getString(R.string.about),
            summary   = "${getString(R.string.version)} ${BuildConfig.VERSION_NAME}",
            onClick   = {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.app_name))
                    .setMessage("${getString(R.string.version)} ${BuildConfig.VERSION_NAME}")
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        )
    }

    private fun checkForUpdates() {
        Toast.makeText(this, R.string.checking_for_updates, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            when (val result = withContext(Dispatchers.IO) {
                UpdateManager.check(this@SettingsActivity)
            }) {
                UpdateManager.Result.UpToDate -> {
                    Toast.makeText(this@SettingsActivity, R.string.app_up_to_date, Toast.LENGTH_SHORT).show()
                }
                is UpdateManager.Result.Available -> {
                    val notes = result.releaseNotes.ifBlank { getString(R.string.update_available_summary) }
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(getString(R.string.update_available, result.versionName))
                        .setMessage(notes)
                        .setPositiveButton(R.string.install_update) { _, _ -> installUpdate(result.apk) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                is UpdateManager.Result.Error -> {
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.update_check_failed, result.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun installUpdate(apk: java.io.File) {
        val apkUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(installIntent)
        } catch (_: SecurityException) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.update_installer_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    // ─── Row builders ─────────────────────────────────────────────────────────

    private fun sectionHeader(container: LinearLayout, text: String) {
        val tv = TextView(this).apply {
            this.text = text.uppercase()
            setTextColor(ThemeManager.accentColor(this@SettingsActivity))
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.text_size_small)
            )
            setPadding(paddingH, paddingV * 3, paddingH, paddingV)
            isFocusable = false
            isFocusableInTouchMode = false
        }
        container.addView(tv)
    }

    private fun toggleRow(
        container: LinearLayout,
        label: String,
        summary: String,
        getValue: () -> Boolean,
        setValue: (Boolean) -> Unit
    ) {
        val accent = ThemeManager.accentColor(this)
        val gray = ContextCompat.getColor(this, R.color.switchThumbOff)
        val grayTrack = ContextCompat.getColor(this, R.color.switchTrackOff)
        val sw = SwitchCompat(this).apply {
            isChecked = getValue()
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, gray)
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf((accent and 0x00FFFFFF) or 0x80000000.toInt(), grayTrack)
            )
        }

        val textCol = buildTextColumn(label, summary)
        val row = buildRow().apply {
            addView(textCol)
            addView(sw)
            setOnClickListener {
                val newVal = !getValue()
                setValue(newVal)
                sw.isChecked = newVal
            }
            setOnKeyListener { _, keyCode, event ->
                if (isConfirmKey(keyCode) && event.action == KeyEvent.ACTION_DOWN) {
                    performClick(); true
                } else false
            }
        }
        container.addView(row)
    }

    private fun valueRow(
        container: LinearLayout,
        label: String,
        summary: String,
        getValue: () -> String,
        optionValues: List<String>,
        optionLabels: List<String>,
        setValue: (String) -> Unit
    ) {
        val accent = ThemeManager.accentColor(this)
        val tvValue = TextView(this).apply {
            text = labelFor(getValue(), optionValues, optionLabels)
            setTextColor(accent)
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.text_size_small)
            )
            isFocusable = false
        }

        val textCol = buildTextColumn(label, summary)
        val row = buildRow().apply {
            addView(textCol)
            addView(tvValue)
            setOnClickListener {
                val currentIdx = optionValues.indexOf(getValue()).coerceAtLeast(0)
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(label)
                    .setSingleChoiceItems(
                        optionLabels.toTypedArray(),
                        currentIdx
                    ) { dialog, which ->
                        setValue(optionValues[which])
                        tvValue.text = optionLabels[which]
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            setOnKeyListener { _, keyCode, event ->
                if (isConfirmKey(keyCode) && event.action == KeyEvent.ACTION_DOWN) {
                    performClick(); true
                } else false
            }
        }
        container.addView(row)
    }

    private fun navRow(
        container: LinearLayout,
        label: String,
        summary: String,
        onClick: () -> Unit
    ) {
        val tvChevron = TextView(this).apply {
            text = "›"
            setTextColor(accentStateTextColors())
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.text_size_large)
            )
            isFocusable = false
        }

        val textCol = buildTextColumn(label, summary)
        val row = buildRow().apply {
            addView(textCol)
            addView(tvChevron)
            setOnClickListener { onClick() }
            setOnKeyListener { _, keyCode, event ->
                if (isConfirmKey(keyCode) && event.action == KeyEvent.ACTION_DOWN) {
                    performClick(); true
                } else false
            }
        }
        container.addView(row)
    }

    // ─── Shared helpers ───────────────────────────────────────────────────────

    /** Builds a vertical [label / summary] column that takes up available horizontal space. */
    private fun buildTextColumn(label: String, summary: String): LinearLayout {
        val tvLabel = TextView(this).apply {
            text = label
            setTextColor(accentStateTextColors())
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.text_size_normal)
            )
        }
        val tvSummary = TextView(this).apply {
            text = summary
            setTextColor(accentStateTextColors())
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.text_size_small)
            )
            visibility = if (summary.isBlank()) View.GONE else View.VISIBLE
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            addView(tvLabel)
            addView(tvSummary)
        }
    }

    /** Builds the shared focusable row shell (no children yet). */
    private fun buildRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = rowMinH
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setBackgroundResource(R.drawable.item_focusable_bg)
        backgroundTintList = ColorStateList.valueOf(ThemeManager.accentColor(this@SettingsActivity))
        setPadding(paddingH, paddingV, paddingH, paddingV)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun accentStateTextColors(): ColorStateList {
        val accent = ThemeManager.accentColor(this)
        return ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(accent, getColor(R.color.colorOnBackground))
        )
    }

    private fun isConfirmKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER

    private fun labelFor(value: String, values: List<String>, labels: List<String>): String {
        val idx = values.indexOf(value)
        return if (idx >= 0) labels[idx] else labels.firstOrNull() ?: value
    }

    private fun showComingSoon() {
        AlertDialog.Builder(this)
            .setMessage(R.string.coming_soon)
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}
