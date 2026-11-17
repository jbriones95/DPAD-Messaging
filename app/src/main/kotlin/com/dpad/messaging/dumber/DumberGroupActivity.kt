package com.dpad.messaging.dumber

import chat.dumber.sdk.*

import android.content.Intent
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.result.contract.ActivityResultContracts
import com.dpad.messaging.activities.NewConversationActivity
import com.dpad.messaging.activities.ConversationDetailsActivity
import com.dpad.messaging.adapters.ThreadAdapter
import com.dpad.messaging.models.ThreadItem
import com.dpad.messaging.models.Message as DisplayMessage
import android.os.Bundle
import android.view.View
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.dpad.messaging.R
import com.dpad.messaging.helpers.ThemeManager
import com.dpad.messaging.views.DpadRecyclerView
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.dpad.messaging.App
import com.dpad.messaging.activities.BaseActivity
import com.dpad.messaging.events.RefreshConversations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/** Group screens own room/account navigation; they never enter a carrier send path. */
class DumberGroupActivity : BaseActivity() {
    private var ref: GroupReference? = null
    private lateinit var root: LinearLayout
    private lateinit var heading: TextView
    private lateinit var timeline: DpadRecyclerView
    private lateinit var messages: ThreadAdapter
    private val messageIds = mutableMapOf<String, Long>()
    private var rowsById = emptyMap<Long, DumberGroupMessages.Row>()
    private val selectedPhones = mutableListOf<String>()
    private var participantsLabel: Button? = null
    private var creationName: EditText? = null
    private var accountPicker: Spinner? = null
    private val recipientPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedPhones.clear()
            selectedPhones.addAll(result.data?.getStringArrayListExtra(NewConversationActivity.EXTRA_PREFILL_RECIPIENTS).orEmpty())
            updateParticipantsLabel()
        }
    }
    private fun updateParticipantsLabel() {
        participantsLabel?.text = getString(R.string.participant_count, selectedPhones.size)
    }
    private var reloadGeneration = 0
    private var loaded = false
    private lateinit var compose: EditText
    private lateinit var send: ImageButton
    private lateinit var accept: Button
    private lateinit var decline: Button
    private var info = Conversation("")
    private var busy = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loaded = savedInstanceState != null
        selectedPhones.addAll(savedInstanceState?.getStringArrayList("participants").orEmpty())
        ref = intent.getStringExtra("room")?.let { GroupReference(requireNotNull(intent.getStringExtra("account")), it) }
        if (ref == null) {
            root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16,8,16,8) }
            setContentView(root)
            button(root,"Back") { finish() }
            createScreen()
            creationName?.setText(savedInstanceState?.getString("group_name").orEmpty())
            accountPicker?.setSelection(savedInstanceState?.getInt("account_index") ?: 0)
        } else chatScreen()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("participants", ArrayList(selectedPhones))
        outState.putString("group_name", creationName?.text?.toString())
        outState.putInt("account_index", accountPicker?.selectedItemPosition ?: 0)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() { super.onStart(); EventBus.getDefault().register(this); if(ref != null) reload() }
    override fun onStop() { EventBus.getDefault().unregister(this); super.onStop() }
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun changed(event: RefreshConversations) { if(ref != null) reload() }
    private fun button(parent: LinearLayout, text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text; id = View.generateViewId(); setOnClickListener { if(!busy) action() }; parent.addView(this)
    }
    private fun field(parent: LinearLayout, hint: String): EditText = EditText(this).apply {
        this.hint = hint; id = View.generateViewId(); parent.addView(this)
    }
    private fun perform(block: suspend () -> Unit) {
        if(busy) return
        busy = true
        if (ref != null) updateSendState()
        lifecycleScope.launch {
            try { block() } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: Exception) { AlertDialog.Builder(this@DumberGroupActivity).setMessage(e.message ?: "Operation failed").setPositiveButton("OK",null).show() }
            finally { busy = false; if(ref != null) reload() }
        }
    }
    private fun createScreen() {
        if (!com.dpad.messaging.BuildConfig.DUMBER_GROUP_CREATION) {
            root.addView(TextView(this).apply { text = "Group creation is not enabled in this rollout." }); return
        }
        val scroll = ScrollView(this); val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; scroll.addView(form); root.addView(scroll)
        val accounts = DumberAccount.ids(this).filter { DumberAccount.enabled(this,it) }
        if(accounts.isEmpty()) { form.addView(TextView(this).apply { text = "Connect dumber.chat in Settings first." }); return }
        val picker = Spinner(this).apply {
            adapter = ArrayAdapter(this@DumberGroupActivity,android.R.layout.simple_spinner_dropdown_item,accounts.map { DumberAccount.state(this@DumberGroupActivity,it).optString("phone","Connected account") })
        }; form.addView(picker); accountPicker = picker
        val name = field(form,"Group name").also { creationName = it }
        participantsLabel = button(form, "Participants") {
            recipientPicker.launch(Intent(this, NewConversationActivity::class.java)
                .putExtra(NewConversationActivity.EXTRA_PICK_RECIPIENTS, true)
                .putStringArrayListExtra(NewConversationActivity.EXTRA_PREFILL_RECIPIENTS, ArrayList(selectedPhones)))
        }
        updateParticipantsLabel()
        button(form,"Restore unfinished creation") {
            DumberGroupStore(this).use { store -> store.creation(accounts[picker.selectedItemPosition])?.let {
                name.setText(it.getString("name")); val list=it.getJSONArray("phones")
                selectedPhones.clear(); selectedPhones.addAll((0 until list.length()).map { i -> list.getString(i) }); updateParticipantsLabel()
            } }
        }
        form.addView(TextView(this).apply { text = "Create a group now and invite people later, or choose participants above. Members see messages from joining onward. Group texts never use SMS/MMS." })
        button(form,"Create encrypted group") { perform {
            val account = accounts[picker.selectedItemPosition]
            val numbers = selectedPhones.toList().map {
                DumberCore.international(this,it.trim(),DumberAccount.subscription(this,account)) ?: error("Use international numbers, including country code")
            }
            val created = DumberGroups.create(this,account,name.text.toString().trim(),numbers)
            startActivity(Intent(this,DumberGroupActivity::class.java).putExtra("account",created.account).putExtra("room",created.room)); finish()
        } }
    }
    private fun chatScreen() {
        setContentView(R.layout.activity_thread)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        heading = findViewById(R.id.tv_contact_name)
        heading.text = "Encrypted group"
        timeline = findViewById(R.id.rv_messages)
        compose = findViewById(R.id.et_message)
        send = findViewById(R.id.btn_send)
        accept = findViewById(R.id.group_accept)
        decline = findViewById(R.id.group_decline)
        val back = findViewById<ImageButton>(R.id.btn_back)
        val options = findViewById<ImageButton>(R.id.btn_details)
        back.imageTintList = ThemeManager.focusAwareTint(this)
        options.imageTintList = ThemeManager.focusAwareTint(this)
        back.setOnClickListener { finish() }
        options.setOnClickListener { details() }
        findViewById<View>(R.id.btn_call).visibility = View.GONE
        findViewById<View>(R.id.compose_tools_row).visibility = View.GONE
        back.nextFocusRightId = options.id
        options.nextFocusLeftId = back.id
        compose.hint = getString(R.string.dumber_group_message_hint)
        compose.nextFocusUpId = R.id.dumber_status
        compose.nextFocusDownId = send.id
        send.nextFocusUpId = R.id.dumber_status
        send.nextFocusDownId = compose.id
        findViewById<Button>(R.id.dumber_status).apply {
            visibility = View.VISIBLE
            nextFocusRightId = R.id.dumber_pending
            setOnClickListener {
                AlertDialog.Builder(this@DumberGroupActivity)
                    .setMessage(R.string.dumber_group_encryption_info)
                    .setPositiveButton(R.string.dumber_group_refresh) { _, _ -> perform { DumberGroups.refresh(this@DumberGroupActivity, ref!!) } }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        findViewById<Button>(R.id.dumber_pending).setOnClickListener {
            val pending = rowsById.values.filter { it.pendingId != null }
            AlertDialog.Builder(this).setTitle(R.string.dumber_group_pending)
                .setItems(pending.map { it.body.orEmpty() }.toTypedArray()) { _, index -> pendingOptions(pending[index].pendingId!!) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        accept.setOnClickListener { perform { DumberGroups.action(this,ref!!,DumberGroups.Action.JOIN) } }
        decline.setOnClickListener { perform { DumberGroups.action(this,ref!!,DumberGroups.Action.LEAVE) } }
        messages = ThreadAdapter(
            onMessageLongClick = { message -> messageOptions(message) },
            onMessageClick = { message -> rowsById[message.id]?.pendingId?.let(::pendingOptions) },
            messageStateLabel = { message -> rowsById[message.id]?.pendingId?.let { getString(R.string.dumber_group_pending) } },
            onMessageFocused = { _, number, total ->
                findViewById<TextView>(R.id.tv_message_context).apply {
                    text = getString(R.string.message_context_position, number, total)
                    visibility = View.VISIBLE
                }
            }
        )
        timeline.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messages.stateRestorationPolicy = androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        timeline.adapter = messages
        timeline.itemAnimator = null
        timeline.consumeUpAtTop = true
        timeline.onLeftNavigation = { back.requestFocus() }
        timeline.onRightNavigation = { options.requestFocus() }
        timeline.onBottomEdgeReached = { if (compose.isEnabled) compose.requestFocus() else options.requestFocus() }
        listOf(back, options).forEach { control ->
            control.setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                    when {
                        accept.visibility == View.VISIBLE -> accept.requestFocus()
                        messages.itemCount > 0 -> timeline.focusFirstItem()
                        else -> compose.requestFocus()
                    }
                    true
                } else false
            }
        }
        compose.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                findViewById<View>(R.id.dumber_status).requestFocus(); true
            } else false
        }
        listOf(findViewById<View>(R.id.dumber_status), findViewById<View>(R.id.dumber_pending), send).forEach { control ->
        control.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                if (messages.itemCount > 0) timeline.focusLastItem() else options.requestFocus()
                true
            } else false
        }
        }
        compose.doAfterTextChanged { updateSendState() }
        compose.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) { send.performClick(); true } else false
        }
        send.setOnClickListener {
            if (send.isEnabled) {
                val body = compose.text.toString()
                perform {
                    val pending = DumberGroups.enqueue(this,ref!!,body)
                    if (compose.text.toString() == body) compose.setText("")
                    DumberGroups.retry(this,ref!!,pending)
                }
            }
        }
        compose.isEnabled = false
        updateSendState()
    }
    private fun updateSendState() {
        send.isEnabled = compose.isEnabled && compose.text.isNotBlank() && !busy
        send.imageTintList = if (send.isEnabled) ThemeManager.focusAwareTint(this)
            else android.content.res.ColorStateList.valueOf(getColor(R.color.sendButtonDisabled))
    }
    private fun pendingOptions(id: String) {
        val target = ref ?: return
        if (busy) return
        val pending = DumberGroupStore(this).use { store ->
            store.pending(target).firstOrNull { it.getString("id") == id }
        } ?: return
        AlertDialog.Builder(this).setTitle("Pending encrypted message")
            .setMessage(pending.optString("error").takeIf { it.isNotBlank() } ?: "This message has not been sent yet.")
            .setPositiveButton("Retry encrypted") { _, _ -> perform { DumberGroups.retry(this,target,pending) } }
            .setNeutralButton("Discard") { _, _ ->
                DumberGroupStore(this).use { it.done(id) }; reload()
            }
            .setNegativeButton("Cancel",null).show()
    }
    private fun reload() {
        val target = ref ?: return
        val generation = ++reloadGeneration
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { DumberGroupStore(this@DumberGroupActivity).use {
                it.markRead(target); Triple(it.info(target),it.messages(target),it.pending(target))
            } }
            if (generation != reloadGeneration) return@launch
            DumberGroups.clearInvitationNotification(this@DumberGroupActivity, target)
            info = data.first ?: Conversation(target.room)
            heading.text = info.name?.takeIf { it.isNotBlank() } ?: "Encrypted group"
            val connected = DumberAccount.enabled(this@DumberGroupActivity,target.account)
            val invited = info.membership == Membership.INVITED
            findViewById<View>(R.id.group_invitation).visibility = if (invited) View.VISIBLE else View.GONE
            accept.visibility = if(invited) View.VISIBLE else View.GONE
            decline.visibility = accept.visibility
            accept.isEnabled = connected
            decline.isEnabled = connected
            compose.isEnabled = connected && info.membership == Membership.JOINED && info.capabilities.send
            val state = when {
                !connected -> getString(R.string.dumber_group_disconnected)
                invited -> getString(R.string.dumber_group_invited)
                info.membership != Membership.JOINED -> getString(R.string.dumber_group_left)
                !info.capabilities.send -> getString(R.string.dumber_group_read_only)
                else -> getString(R.string.dumber_group_message_hint)
            }
            val phone = DumberAccount.state(this@DumberGroupActivity,target.account).optString("phone")
            findViewById<Button>(R.id.dumber_status).text = "$state · $phone"
            updateSendState()
            val atBottom = messages.itemCount > 0 && !timeline.canScrollVertically(1)
            val firstLoad = !loaded
            loaded = true
            val rows = DumberGroupMessages.rows(target.account,info,data.second,data.third)
            rowsById = rows.associateBy { row -> messageIds.getOrPut(row.key) { messageIds.size.toLong() + 1 } }
            val display = rowsById.map { (id, row) ->
                row.display(id,
                    row.phone?.let { App.get().contactHelper.getDisplayName(it) }
                        ?: "${getString(R.string.dumber_group_unknown_sender)} (${digest(row.senderId).take(6)})",
                    getString(R.string.dumber_group_unable_to_decrypt), getString(R.string.dumber_group_unsupported))
            }
            findViewById<Button>(R.id.dumber_pending).apply {
                val count = rows.count { it.pendingId != null }
                visibility = if (count > 0) View.VISIBLE else View.GONE
                text = getString(R.string.dumber_group_unsent_count, count)
            }
            messages.submitList(ThreadItem.fromMessages(display)) {
                if ((firstLoad || atBottom) && messages.itemCount > 0) timeline.scrollToPosition(messages.itemCount - 1)
            }
        }
    }
    private fun messageOptions(message: DisplayMessage) {
        val row = rowsById[message.id] ?: return
        row.pendingId?.let { pendingOptions(it); return }
        AlertDialog.Builder(this).setItems(arrayOf(getString(android.R.string.copy))) { _, _ ->
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Message", message.body))
        }.show()
    }
    private fun details() {
        val target = ref ?: return
        startActivity(Intent(this, ConversationDetailsActivity::class.java)
            .putExtra("account", target.account).putExtra("room", target.room))
    }
}
