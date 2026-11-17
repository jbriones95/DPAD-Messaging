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

    private var groupDetails: com.dpad.messaging.dumber.DumberGroupDetails? = null
    private var threadId: Long = -1L
    private var currentTitle: String = ""
    private var colorTargetNumber: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyAccentColor(this)
        binding = ActivityConversationDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val account = intent.getStringExtra("account")
        val room = intent.getStringExtra("room")
        if (account != null && room != null) {
            binding.btnBack.setOnClickListener { finish() }
            groupDetails = com.dpad.messaging.dumber.DumberGroupDetails(
                this, binding, com.dpad.messaging.dumber.GroupReference(account, room)
            ) { info ->
                val labels = info.members.associate { member ->
                    member.contact.id to
                        "${com.dpad.messaging.dumber.DumberGroups.label(member)} · ${member.membership.name.lowercase()}${if (member.admin) " · admin" else ""}"
                }
                val unknown = info.members.filter { it.contact.phone == null }.map { it.contact.id }.toSet()
                val phones = info.members.mapNotNull { member -> member.contact.phone?.let { member.contact.id to it } }.toMap()
                populateParticipants(labels.keys.toList(), "", labels, unknown, phones)
                val firstAction = binding.btnGroupActions
                binding.rowColor.visibility = View.GONE
                binding.appearanceTitle.visibility = View.GONE
                participantNameViews.lastOrNull()?.nextFocusDownId = firstAction.id
                participantCallViews.lastOrNull()?.nextFocusDownId = firstAction.id
                participantAddViews.lastOrNull()?.nextFocusDownId = firstAction.id
                firstAction.nextFocusUpId = participantNameViews.lastOrNull()?.id ?: binding.btnBack.id
                if (participantNameViews.isEmpty()) binding.btnBack.nextFocusDownId = firstAction.id
            }
            binding.btnBack.post { binding.btnBack.requestFocus() }
            return
        }

        threadId     = intent.getLongExtra(ThreadActivity.EXTRA_THREAD_ID, -1L)
        currentTitle = intent.getStringExtra(ThreadActivity.EXTRA_THREAD_TITLE) ?: ""
        val phoneNumber = intent.getStringExtra(ThreadActivity.EXTRA_PHONE_NUMBER) ?: ""
        val participants = intent.getStringExtra(ThreadActivity.EXTRA_PARTICIPANTS)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        binding.tvContactName.text = currentTitle
        binding.tvIdentitySummary.text = getString(
            R.string.participant_count,
            if (participants.isNotEmpty()) participants.size else if (phoneNumber.isNotBlank()) 1 else 0
        )
        binding.tvIdentityAvatar.text = (currentTitle.firstOrNull { !it.isWhitespace() }
            ?: phoneNumber.firstOrNull { it.isDigit() }
            ?: '?').uppercaseChar().toString()
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
        binding.btnBack.post { binding.btnBack.requestFocus() }
    }

    private fun populateParticipants(participants: List<String>, phoneNumber: String, labels: Map<String, String> = emptyMap(), unknown: Set<String> = emptySet(), phoneNumbers: Map<String, String> = emptyMap()) {
        val container = binding.participantList
        container.removeAllViews()
        participantNameViews.clear()
        participantAddViews.clear()
        participantCallViews.clear()
        val accent = ThemeManager.accentColor(this)
        val focusedTextColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(
                ContextCompat.getColor(this, R.color.colorOnBackground),
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

        val participantFocusViews = mutableListOf<Pair<View, View>>()
        numbers.forEach { key ->
            val num = phoneNumbers[key] ?: key
            val hasNumber = key !in unknown
            val info = if (hasNumber) App.get().contactHelper.resolve(num) else null
            val display = labels[key] ?: info?.displayName ?: num

            val row = LinearLayout(this).apply {
                id = View.generateViewId()
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.conversation_item_height)
                ).apply {
                    bottomMargin = resources.getDimensionPixelSize(R.dimen.padding_tiny)
                }
                setBackgroundResource(R.drawable.button_focusable_bg)
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
                    if (!hasNumber) return@setOnClickListener
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
                    setBackgroundResource(R.drawable.button_focusable_bg)
                    setImageResource(R.drawable.ic_call)
                    imageTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                        intArrayOf(
                            ContextCompat.getColor(this@ConversationDetailsActivity, R.color.colorOnBackground),
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
                participantFocusViews.add(tv to callButton)
            }

            if (info == null && hasNumber) {
                // Unknown number — show Add Contact control
                val addId = View.generateViewId()
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
                tv.nextFocusRightId = addId
                add.nextFocusLeftId = tvId
                participantFocusViews.add(tv to add)
            }

            if (!hasNumber) participantFocusViews.add(tv to tv)

            // Accessibility on name
            tv.contentDescription = if (info != null || !hasNumber) {
                getString(R.string.participants) + ": " + display
            } else {
                getString(R.string.participants) + ": " + num + ", " + getString(R.string.add_recipient)
            }

            container.addView(row)
        }

        participantFocusViews.forEachIndexed { index, (nameView, actionView) ->
            val previous = participantFocusViews.getOrNull(index - 1)
            val next = participantFocusViews.getOrNull(index + 1)
            nameView.nextFocusUpId = previous?.first?.id ?: binding.btnBack.id
            actionView.nextFocusUpId = previous?.second?.id ?: binding.btnBack.id
            nameView.nextFocusDownId = next?.first?.id ?: binding.rowColor.id
            actionView.nextFocusDownId = next?.second?.id ?: binding.rowColor.id
        }
        binding.btnBack.nextFocusDownId = participantFocusViews.first().first.id
        binding.rowColor.nextFocusUpId = participantFocusViews.last().first.id
        binding.rowColor.nextFocusDownId = binding.btnRename.id
        binding.btnRename.nextFocusUpId = binding.rowColor.id

        applyAccent()
    }

    override fun onResume() {
        super.onResume()
        groupDetails?.reload()
        applyAccent()
    }

    private fun applyAccent() {
        val accent = ThemeManager.accentColor(this)
        val tint = ThemeManager.focusAwareTint(this)
        binding.btnBack.imageTintList = tint
        binding.btnRename.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(
                    ContextCompat.getColor(this, R.color.focus_content),
                    ContextCompat.getColor(this, R.color.colorOnBackground)
                )
            )
        )
        binding.btnBlock.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(
                    ContextCompat.getColor(this, R.color.focus_content),
                    ContextCompat.getColor(this, R.color.statusFailed)
                )
            )
        )

        val focusedForeground = ContextCompat.getColor(this, R.color.focus_content)
        participantNameViews.forEach { nameView ->
            nameView.setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                    intArrayOf(focusedForeground, ContextCompat.getColor(this, R.color.colorOnBackground))
                )
            )
        }
        participantAddViews.forEach { addView ->
            addView.setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                    intArrayOf(focusedForeground, accent)
                )
            )
        }
        participantCallViews.forEach { callView ->
            callView.imageTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(focusedForeground, accent)
            )
        }
    }

    private fun showColorPicker() {
        if (colorTargetNumber.isBlank()) return
        val current = ContactColors.customColor(colorTargetNumber)
        ContactColors.showColorPicker(
            context = this,
            title = getString(R.string.choose_contact_color),
            currentColor = current,
            onSelected = { selected ->
            if (selected != current) {
                Prefs.get().setContactColor(ContactColors.normalize(colorTargetNumber), selected)
                updateColorSwatch()
            }
            },
            onDismiss = {
            binding.rowColor.requestFocus()
            }
        )
    }

    private fun updateColorSwatch() {
        if (colorTargetNumber.isBlank()) return
        val custom = ContactColors.customColor(colorTargetNumber)
        val resolved = ContactColors.resolveColor(colorTargetNumber)
        binding.colorSwatch.background.setTint(resolved)
        binding.tvIdentityAvatar.background.setTint(resolved)
        binding.tvIdentityAvatar.setTextColor(ContactColors.textColorOn(resolved))
        binding.tvColorValue.text = getString(
            R.string.contact_color_current,
            ContactColors.colorName(custom)
        )
    }

    private fun showRenameDialog() {
        val input = android.widget.EditText(this).apply {
            id = View.generateViewId()
            setText(currentTitle)
            setSelection(currentTitle.length)
            setTextColor(getColor(R.color.colorOnBackground))
            setBackgroundResource(R.drawable.compose_input_bg)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
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
            .create()
        dialog.setOnShowListener {
            val saveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            val cancelButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
            saveButton.isFocusable = true
            saveButton.isFocusableInTouchMode = true
            cancelButton.isFocusable = true
            cancelButton.isFocusableInTouchMode = true
            input.nextFocusDownId = saveButton.id
            saveButton.nextFocusUpId = input.id
            saveButton.nextFocusLeftId = cancelButton.id
            cancelButton.nextFocusRightId = saveButton.id
            cancelButton.nextFocusUpId = input.id
            input.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    saveButton.requestFocus()
                    true
                } else if (
                    event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                    input.selectionEnd >= input.text.length
                ) {
                    saveButton.requestFocus()
                    true
                } else false
            }
            saveButton.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    input.requestFocus()
                    true
                } else false
            }
            cancelButton.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    input.requestFocus()
                    true
                } else false
            }
            input.requestFocus()
        }
        dialog.show()
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
