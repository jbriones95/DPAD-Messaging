package com.dpad.messaging.activities

import android.content.res.ColorStateList
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.os.Bundle
import android.view.KeyEvent
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import com.dpad.messaging.App
import com.dpad.messaging.R
import com.dpad.messaging.databinding.ActivityConversationDetailsBinding
import com.dpad.messaging.helpers.ContactColors
import com.dpad.messaging.helpers.Prefs
import com.dpad.messaging.helpers.ThemeManager
import com.dpad.messaging.models.BlockedNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
    * Conversation details screen.
    * Phase 4: rename persists via Room conversationsDao.setCustomTitle();
    *           block inserts a BlockedNumber row so receivers suppress notifications.
    */
class ConversationDetailsActivity : BaseActivity() {

    private lateinit var binding: ActivityConversationDetailsBinding
    private val participantNameViews = mutableListOf<TextView>()
    private val participantAddViews = mutableListOf<TextView>()
    private val participantCallViews = mutableListOf<ImageButton>()

    private var threadId: Long = -1L
    private var currentTitle: String = ""
    private var colorTargetNumber: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyAccentColor(this)
        binding = ActivityConversationDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        threadId     = intent.getLongExtra(ThreadActivity.EXTRA_THREAD_ID, -1L)
        currentTitle = intent.getStringExtra(ThreadActivity.EXTRA_THREAD_TITLE) ?: ""
        val phoneNumber = intent.getStringExtra(ThreadActivity.EXTRA_PHONE_NUMBER) ?: ""
        val participants = intent.getStringExtra(ThreadActivity.EXTRA_PARTICIPANTS)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        binding.tvContactName.text = currentTitle
        populateParticipants(participants, phoneNumber)

        binding.btnBack.setOnClickListener { finish() }

        // D-Pad focus chain: Back → (Participants) → Color → Rename → Block
        // `populateParticipants` will adjust the exact chain depending on whether participants exist.

        binding.btnRename.setOnClickListener { showRenameDialog() }
        binding.rowColor.setOnClickListener { showColorPicker() }
        val blockTarget = participants.firstOrNull() ?: phoneNumber
        binding.btnBlock.setOnClickListener  { showBlockConfirmation(blockTarget) }

        colorTargetNumber = participants.firstOrNull() ?: phoneNumber
        updateColorSwatch()
    }

    private fun populateParticipants(participants: List<String>, phoneNumber: String) {
        val container = binding.participantList
        container.removeAllViews()
        participantNameViews.clear()
        participantAddViews.clear()
        participantCallViews.clear()
        val accent = ThemeManager.accentColor(this)
        val focusedTextColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(
                ContextCompat.getColor(this, R.color.colorOnPrimary),
                ContextCompat.getColor(this, R.color.colorOnBackground)
            )
        )

        val numbers = if (participants.isEmpty()) {
            if (phoneNumber.isNotBlank()) listOf(phoneNumber) else emptyList()
        } else participants

        if (numbers.isEmpty()) {
            // No participants — leave behavior to Color/Rename/Block
            binding.btnBack.nextFocusDownId = binding.rowColor.id
            binding.rowColor.nextFocusUpId = binding.btnBack.id
            binding.rowColor.nextFocusDownId = binding.btnRename.id
            binding.btnRename.nextFocusUpId = binding.rowColor.id
            return
        }

        val lastIndex = numbers.size - 1
        var prevTvId: Int? = null
        numbers.forEachIndexed { index, num ->
            val info = App.get().contactHelper.resolve(num)
            val display = info?.displayName ?: num

            val row = LinearLayout(this).apply {
                id = View.generateViewId()
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.conversation_item_height)
                ).apply {
                    bottomMargin = resources.getDimensionPixelSize(R.dimen.padding_tiny)
                }
                setBackgroundResource(R.drawable.details_row_bg)
                // Let children receive focus first (so the name and + button are individually reachable)
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                isFocusable = false
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvId = View.generateViewId()
            val tv = TextView(this).apply {
                id = tvId
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setBackgroundResource(R.drawable.details_row_bg)
                setTextColor(focusedTextColors)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_size_normal))
                text = display
                setPadding(
                    resources.getDimensionPixelSize(R.dimen.padding_medium),
                    0,
                    resources.getDimensionPixelSize(R.dimen.padding_medium),
                    0
                )
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener {
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("phone", num))
                    Toast.makeText(this@ConversationDetailsActivity, getString(R.string.message_copied), Toast.LENGTH_SHORT).show()
                }
            }
            row.addView(tv)
            participantNameViews.add(tv)

            if (info != null) {
                val callId = View.generateViewId()
                val callButton = ImageButton(this).apply {
                    id = callId
                    layoutParams = LinearLayout.LayoutParams(
                        resources.getDimensionPixelSize(R.dimen.compose_button_size),
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundResource(R.drawable.details_row_bg)
                    setImageResource(R.drawable.ic_call)
                    imageTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                        intArrayOf(
                            ContextCompat.getColor(this@ConversationDetailsActivity, R.color.colorOnPrimary),
                            accent
                        )
                    )
                    contentDescription = getString(R.string.call)
                    scaleType = ImageView.ScaleType.CENTER
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setOnClickListener {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")))
                    }
                }
                row.addView(callButton)
                participantCallViews.add(callButton)
                tv.nextFocusRightId = callId
                callButton.nextFocusLeftId = tvId
            }

            var addId = -1
            if (info == null) {
                // Unknown number — show Add Contact control
                addId = View.generateViewId()
                val add = TextView(this).apply {
                    id = addId
                    layoutParams = LinearLayout.LayoutParams(
                        resources.getDimensionPixelSize(R.dimen.compose_button_size),
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundResource(R.drawable.details_row_bg)
                    setTextColor(focusedTextColors)
                    text = "+"
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_size_title))
                    gravity = Gravity.CENTER
                    isFocusable = true
                    isFocusableInTouchMode = true
                    contentDescription = getString(R.string.add_recipient)
                    setOnClickListener {
                        val intent = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
                            putExtra(ContactsContract.Intents.Insert.PHONE, num)
                        }
                        startActivity(intent)
                    }
                }
                row.addView(add)
                participantAddViews.add(add)
                // horizontal navigation between name and add button
                tv.nextFocusRightId = addId
                add.nextFocusLeftId = tvId
                // vertical chaining for add button will mirror the tv below
                if (prevTvId != null) {
                    val pid = prevTvId!!
                    findViewById<TextView>(pid).let { prevTv ->
                        add.nextFocusUpId = prevTv.nextFocusUpId
                    }
                }
            }

            // Accessibility on name
            tv.contentDescription = if (info != null) {
                getString(R.string.participants) + ": " + display
            } else {
                getString(R.string.participants) + ": " + num + ", " + getString(R.string.add_recipient)
            }

            container.addView(row)

            // Vertical chaining between tvs
            if (prevTvId == null) {
                binding.btnBack.nextFocusDownId = tvId
                tv.nextFocusUpId = binding.btnBack.id
            } else {
                val prevId = prevTvId!!
                val prevTv = findViewById<TextView>(prevId)
                prevTv.nextFocusDownId = tvId
                tv.nextFocusUpId = prevId
                // mirror for add button if present
                if (addId != -1) {
                    findViewById<TextView>(prevId).nextFocusDownId = tvId
                }
            }

            if (index == lastIndex) {
                tv.nextFocusDownId = binding.rowColor.id
                binding.rowColor.nextFocusUpId = tvId
                binding.rowColor.nextFocusDownId = binding.btnRename.id
                binding.btnRename.nextFocusUpId = binding.rowColor.id
            }

            prevTvId = tvId
        }

        applyAccent()
    }

    override fun onResume() {
        super.onResume()
        applyAccent()
    }

    private fun applyAccent() {
        val accent = ThemeManager.accentColor(this)
        val tint = ColorStateList.valueOf(accent)
        binding.btnBack.imageTintList = tint
        binding.btnBack.backgroundTintList = tint
        binding.btnRename.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(
                    ContextCompat.getColor(this, R.color.colorOnPrimary),
                    ContextCompat.getColor(this, R.color.colorOnBackground)
                )
            )
        )
        binding.btnBlock.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(
                    ContextCompat.getColor(this, R.color.colorOnPrimary),
                    ContextCompat.getColor(this, R.color.statusFailed)
                )
            )
        )

        val onPrimary = ContextCompat.getColor(this, R.color.colorOnPrimary)
        participantNameViews.forEach { nameView ->
            nameView.setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                    intArrayOf(onPrimary, ContextCompat.getColor(this, R.color.colorOnBackground))
                )
            )
        }
        participantAddViews.forEach { addView ->
            addView.setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                    intArrayOf(onPrimary, accent)
                )
            )
        }
        participantCallViews.forEach { callView ->
            callView.imageTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(onPrimary, accent)
            )
        }
    }

    private fun showColorPicker() {
        if (colorTargetNumber.isBlank()) return
        val current = ContactColors.customColor(colorTargetNumber)
        ContactColors.showColorPicker(
            context = this,
            title = getString(R.string.choose_contact_color),
            currentColor = current
        ) { selected ->
            if (selected != current) {
                Prefs.get().setContactColor(ContactColors.normalize(colorTargetNumber), selected)
                updateColorSwatch()
            }
        }
    }

    private fun updateColorSwatch() {
        if (colorTargetNumber.isBlank()) return
        binding.colorSwatch.background.setTint(
            ContactColors.resolveColor(colorTargetNumber)
        )
    }

    private fun showRenameDialog() {
        val input = android.widget.EditText(this).apply {
            setText(currentTitle)
            setSelection(currentTitle.length)
            setTextColor(getColor(R.color.colorOnBackground))
            setBackgroundResource(R.drawable.compose_input_bg)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.rename_conversation)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text?.toString()?.trim()
                if (!newName.isNullOrBlank()) {
                    // Persist in Room
                    lifecycleScope.launch(Dispatchers.IO) {
                        App.get().database.conversationsDao()
                            .setCustomTitle(threadId, newName)
                        withContext(Dispatchers.Main) {
                            currentTitle = newName
                            binding.tvContactName.text = newName
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showBlockConfirmation(phoneNumber: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.block)
            .setMessage(phoneNumber)
            .setPositiveButton(R.string.yes) { _, _ ->
                // Insert the phone number as a blocked keyword so SmsReceiver
                // will be treated as a blocked number and suppressed by receivers.
                lifecycleScope.launch(Dispatchers.IO) {
                    App.get().database.blockedNumbersDao()
                        .insert(BlockedNumber(number = phoneNumber))
                    withContext(Dispatchers.Main) { finish() }
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_STAR) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
