package com.dpad.messaging.activities

import android.Manifest
import android.app.role.RoleManager
import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.view.KeyEvent
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dpad.messaging.App
import com.dpad.messaging.R
import com.dpad.messaging.adapters.ConversationsAdapter
import com.dpad.messaging.databinding.ActivityMainBinding
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.extensions.getConversationsFromTelephony
import com.dpad.messaging.helpers.ConversationCache
import com.dpad.messaging.extensions.markThreadAsReadInTelephony
import com.dpad.messaging.helpers.Prefs
import com.dpad.messaging.helpers.ThemeManager
import com.dpad.messaging.models.Draft
import com.dpad.messaging.models.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var conversationsAdapter: ConversationsAdapter
    private lateinit var requestRoleLauncher: ActivityResultLauncher<Intent>

    /** Debounce job for search filtering — cancels and reschedules on each keystroke */
    private var searchDebounceJob: Job? = null

    /** Active load job for conversations; cancelled when a newer load starts. */
    private var loadConversationsJob: Job? = null

    /** Thread to focus after list refresh (used when returning from a conversation). */
    private var pendingFocusThreadId: Long? = null

    private val requiredPermissions = buildList {
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.RECEIVE_MMS)
        add(Manifest.permission.RECEIVE_WAP_PUSH)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
        }
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyAccentColor(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Activity Result API for requesting SMS role (Android Q+)
        requestRoleLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // Role granted — re-check and refresh UI
                checkDefaultSmsApp()
                loadConversations()
            } else {
                // User declined — don't ask again
                Prefs.get().defaultSmsDismissed = true
            }
        }

        setupConversationList()
        setupToolbar()
        setupSearch()
        checkPermissions()
        handleExternalComposeIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalComposeIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        EventBus.getDefault().register(this)
        applyAccent()
        refreshConversationList()
        checkDefaultSmsApp()
    }

    private fun refreshConversationList() {
        loadConversations()
    }

    override fun onPause() {
        pendingFocusThreadId = currentFocusedThreadId() ?: pendingFocusThreadId
        loadConversationsJob?.cancel()
        EventBus.getDefault().unregister(this)
        super.onPause()
    }

    // ─── Setup ─────────────────────────────────────────────────────────────

    private fun setupConversationList() {
        conversationsAdapter = ConversationsAdapter(
            onConversationClick = { conversation -> openThread(conversation) },
            onConversationLongClick = { conversation -> showConversationContextMenu(conversation) },
            onConversationMenuClick = { _, conversation -> showConversationContextMenu(conversation) }
        )

        binding.rvConversations.apply {
            adapter = conversationsAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
            // When D-Pad UP leaves the top of the list, move focus to toolbar
            onTopEdgeReached = {
                binding.btnNewConversation.requestFocus()
            }
        }
    }

    private fun setupToolbar() {
        binding.btnNewConversation.setOnClickListener {
            startActivity(Intent(this, NewConversationActivity::class.java))
        }
        binding.btnSearch.setOnClickListener { showSearch() }
        binding.btnOverflow.setOnClickListener { showOverflowMenu() }

        // D-Pad DOWN from any toolbar button → focus first conversation
        val enterList = View.OnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                binding.rvConversations.focusFirstItem()
                true
            } else false
        }
        binding.btnNewConversation.setOnKeyListener(enterList)
        binding.btnSearch.setOnKeyListener(enterList)
        binding.btnOverflow.setOnKeyListener(enterList)

        applyAccent()
    }

    private fun applyAccent() {
        val accent = ThemeManager.accentColor(this)
        val tint = ColorStateList.valueOf(accent)

        listOf(
            binding.btnNewConversation,
            binding.btnSearch,
            binding.btnOverflow,
            binding.btnSearchClose
        ).forEach { button ->
            button.imageTintList = tint
            button.background?.mutate()?.setTintList(tint)
            button.invalidate()
        }
    }

    private fun setupSearch() {
        binding.btnSearchClose.setOnClickListener { hideSearch() }
        
        // TextWatcher with debounce: waits 500ms after user stops typing before searching
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                // Cancel previous search job
                searchDebounceJob?.cancel()
                
                // Schedule a new search after 500ms of no input
                searchDebounceJob = lifecycleScope.launch {
                    delay(500)  // Wait 500ms after user stops typing
                    filterConversations(s?.toString() ?: "")
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        
        // DPAD CENTER: trigger search immediately without waiting for debounce
        binding.etSearch.setOnKeyListener { _, keyCode, event ->
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.action == KeyEvent.ACTION_DOWN -> {
                    // Cancel pending debounce and search immediately
                    searchDebounceJob?.cancel()
                    filterConversations(binding.etSearch.text?.toString() ?: "")
                    true
                }
                keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN -> {
                    // ENTER key also triggers search immediately
                    searchDebounceJob?.cancel()
                    filterConversations(binding.etSearch.text?.toString() ?: "")
                    true
                }
                else -> false
            }
        }
        
        binding.etSearch.setOnEditorActionListener { _, _, _ -> true  // consume — don't navigate away
        }
    }

    // ─── Data loading ───────────────────────────────────────────────────────

    private fun loadConversations(forceRefresh: Boolean = false) {
        if (!hasRequiredPermissions()) return

        if (!forceRefresh) {
            ConversationCache.get()?.let { cached ->
                displayConversations(cached)
            }
        }

        loadConversationsJob?.cancel()
        loadConversationsJob = lifecycleScope.launch {
            val pinnedIds = Prefs.get().getPinnedThreadIds()
            val mutedIds = Prefs.get().getMutedThreadIds()
            val conversations = withContext(Dispatchers.IO) {
                getConversationsFromTelephony(
                    App.get().contactHelper,
                    pinnedIds,
                    mutedThreadIds = mutedIds
                )
            }
            ConversationCache.put(conversations)
            displayConversations(conversations)
        }
    }

    private fun displayConversations(conversations: List<Conversation>) {
        conversationsAdapter.submitList(conversations) {
            binding.tvEmpty.visibility = if (conversations.isEmpty()) View.VISIBLE else View.GONE

            if (isSearchVisible) {
                binding.etSearch.requestFocus()
                return@submitList
            }

            if (conversations.isEmpty()) {
                binding.btnNewConversation.requestFocus()
                pendingFocusThreadId = null
                return@submitList
            }

            val targetPosition = pendingFocusThreadId
                ?.let { threadId -> conversations.indexOfFirst { it.threadId == threadId } }
                ?.takeIf { it >= 0 }

            // If RecyclerView already has a focused child, the user navigated during the
            // async load — don't override with focus restoration.
            if (binding.rvConversations.focusedChild != null) {
                if (targetPosition != null) {
                    binding.rvConversations.scrollToPosition(targetPosition)
                }
                pendingFocusThreadId = null
                return@submitList
            }

            when {
                targetPosition != null -> binding.rvConversations.focusItem(targetPosition)
                !binding.btnNewConversation.isFocused &&
                    !binding.btnSearch.isFocused &&
                    !binding.btnOverflow.isFocused -> {
                    binding.rvConversations.focusFirstItem()
                }
            }
            pendingFocusThreadId = null
        }
    }

    private fun filterConversations(query: String) {
        // Search only executes if query is at least 2 characters (or user pressed DPAD CENTER)
        if (query.length < 2) {
            // Show all conversations when search is cleared or too short
            loadConversations()
            return
        }
        lifecycleScope.launch {
            val pinnedIds = Prefs.get().getPinnedThreadIds()
            val mutedIds = Prefs.get().getMutedThreadIds()
            val all = ConversationCache.get() ?: withContext(Dispatchers.IO) {
                getConversationsFromTelephony(
                    App.get().contactHelper,
                    pinnedIds,
                    mutedThreadIds = mutedIds
                )
            }.also { ConversationCache.put(it) }
            val lower = query.lowercase()
            val filtered = withContext(Dispatchers.Default) {
                all.filter {
                    it.title.lowercase().contains(lower) ||
                    it.snippet.lowercase().contains(lower) ||
                    it.phoneNumber.contains(lower)
                }
            }
            conversationsAdapter.submitList(filtered)
            binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    // ─── Navigation ─────────────────────────────────────────────────────────

    private fun openThread(conversation: Conversation) {
        pendingFocusThreadId = conversation.threadId
        val intent = Intent(this, ThreadActivity::class.java).apply {
            putExtra(ThreadActivity.EXTRA_THREAD_ID, conversation.threadId)
            putExtra(ThreadActivity.EXTRA_THREAD_TITLE, conversation.title)
            putExtra(ThreadActivity.EXTRA_PHONE_NUMBER, conversation.phoneNumber)
            if (conversation.participants.isNotBlank()) {
                putExtra(ThreadActivity.EXTRA_PARTICIPANTS, conversation.participants)
            }
        }
        startActivity(intent)
    }

    private fun handleExternalComposeIntent(incomingIntent: Intent?): Boolean {
        val intent = incomingIntent ?: return false
        val action = intent.action ?: return false
        if (action != Intent.ACTION_SENDTO && action != Intent.ACTION_VIEW) return false

        val data = intent.data ?: return false
        val scheme = data.scheme?.lowercase() ?: return false
        if (scheme !in setOf("sms", "smsto", "mms", "mmsto")) return false

        val prefillBody = extractComposeBodyFromIntent(data, intent)
        val recipients = extractRecipientsFromIntent(data, intent)
        if (recipients.isEmpty()) {
            openNewConversation(prefillBody = prefillBody)
            return true
        }

        lifecycleScope.launch {
            val threadId = withContext(Dispatchers.IO) {
                resolveOrCreateThreadId(recipients)
            }

            if (threadId == null) {
                openNewConversation(recipients, prefillBody)
                return@launch
            }

            if (prefillBody.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    App.get().database.draftsDao().insertDraft(
                        Draft(threadId = threadId, body = prefillBody)
                    )
                }
            }

            val title = withContext(Dispatchers.IO) {
                recipients.joinToString(", ") {
                    App.get().contactHelper.getDisplayName(it)
                }
            }

            val threadIntent = Intent(this@MainActivity, ThreadActivity::class.java).apply {
                putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId)
                putExtra(ThreadActivity.EXTRA_THREAD_TITLE, title)
                putExtra(ThreadActivity.EXTRA_PHONE_NUMBER, recipients.first())
                if (recipients.size > 1) {
                    putExtra(ThreadActivity.EXTRA_PARTICIPANTS, recipients.joinToString(","))
                }
            }
            startActivity(threadIntent)
            finish()
        }
        return true
    }

    private fun extractRecipientsFromIntent(data: Uri, intent: Intent): List<String> {
        val rawFromUri = data.schemeSpecificPart
            ?.removePrefix("//")
            ?.substringBefore('?')
            ?.trim()
            .orEmpty()

        val rawRecipients = if (rawFromUri.isNotBlank()) {
            rawFromUri
        } else {
            intent.getStringExtra("address").orEmpty()
        }

        if (rawRecipients.isBlank()) return emptyList()

        return rawRecipients
            .split(',', ';')
            .map { Uri.decode(it).trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun extractComposeBodyFromIntent(data: Uri, intent: Intent): String {
        val extraBody = intent.getStringExtra("sms_body")
            ?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        if (!extraBody.isNullOrBlank()) return extraBody
        return data.getQueryParameter("body")?.trim().orEmpty()
    }

    private fun openNewConversation(
        recipients: List<String> = emptyList(),
        prefillBody: String = ""
    ) {
        val intent = Intent(this, NewConversationActivity::class.java).apply {
            if (prefillBody.isNotBlank()) {
                putExtra(NewConversationActivity.EXTRA_PREFILL_BODY, prefillBody)
            }
            if (recipients.isNotEmpty()) {
                putStringArrayListExtra(
                    NewConversationActivity.EXTRA_PREFILL_RECIPIENTS,
                    ArrayList(recipients)
                )
            }
        }
        startActivity(intent)
        finish()
    }

    private fun resolveOrCreateThreadId(recipients: List<String>): Long? {
        return runCatching {
            if (recipients.size == 1) {
                Telephony.Threads.getOrCreateThreadId(this, recipients.first())
            } else {
                Telephony.Threads.getOrCreateThreadId(this, recipients.toSet())
            }
        }.getOrNull()
    }

    private fun currentFocusedThreadId(): Long? {
        val focusedChild = binding.rvConversations.focusedChild ?: return null
        val holder = binding.rvConversations.findContainingViewHolder(focusedChild) ?: return null
        val pos = holder.bindingAdapterPosition
        if (pos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return null
        return conversationsAdapter.currentList.getOrNull(pos)?.threadId
    }

    // ─── Search overlay ─────────────────────────────────────────────────────

    private fun showSearch() {
        binding.toolbar.visibility = View.GONE
        binding.searchBar.visibility = View.VISIBLE
        binding.etSearch.requestFocus()
    }

    private fun hideSearch() {
        binding.searchBar.visibility = View.GONE
        binding.toolbar.visibility = View.VISIBLE
        binding.etSearch.text?.clear()
        loadConversations()
        binding.btnSearch.requestFocus()
    }

    private val isSearchVisible get() = binding.searchBar.visibility == View.VISIBLE

    // ─── Context menus ──────────────────────────────────────────────────────

    private fun showConversationContextMenu(conversation: Conversation) {
        // Find the anchor view - use the conversation menu button
        val anchor = binding.rvConversations.findViewWithTag<View>(conversation.threadId)

            val popup = PopupMenu(ThemeManager.popupMenuContext(this), anchor ?: binding.rvConversations)
        popup.menu.apply {
            add(0, 1, 0, if (conversation.read) getString(R.string.mark_as_unread) else getString(R.string.mark_as_read))
            add(0, 2, 1, if (conversation.pinned) getString(R.string.unpin) else getString(R.string.pin))
            add(0, 3, 2, if (conversation.archived) getString(R.string.unarchive) else getString(R.string.archive))
            add(0, 4, 3, if (Prefs.get().isThreadMuted(conversation.threadId)) getString(R.string.unmute_conversation) else getString(R.string.mute_conversation))
            add(0, 5, 4, getString(R.string.copy_number))
            add(0, 6, 5, getString(R.string.move_to_recycle_bin))
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> toggleReadState(conversation)
                2 -> togglePin(conversation)
                3 -> toggleArchive(conversation)
                4 -> toggleMute(conversation)
                5 -> copyNumber(conversation.phoneNumber)
                6 -> moveToRecycleBin(conversation)
            }
            true
        }

        popup.show()
    }

    private fun showOverflowMenu() {
        val popup = PopupMenu(ThemeManager.popupMenuContext(this), binding.btnOverflow)
        popup.menu.apply {
            add(0, 1, 0, getString(R.string.archived))
            add(0, 2, 1, getString(R.string.recycle_bin))
            add(0, 3, 2, getString(R.string.settings))
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> startActivity(Intent(this, ArchivedConversationsActivity::class.java))
                2 -> startActivity(Intent(this, RecycleBinActivity::class.java))
                3 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }

        popup.show()
    }

    // ─── Conversation actions ───────────────────────────────────────────────

    private fun toggleReadState(conversation: Conversation) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = App.get().database.conversationsDao()
            dao.markAsRead(conversation.threadId)
            markThreadAsReadInTelephony(conversation.threadId)
        }
        loadConversations()
    }

    private fun togglePin(conversation: Conversation) {
        Prefs.get().setThreadPinned(conversation.threadId, !conversation.pinned)
        loadConversations()
    }

    private fun toggleArchive(conversation: Conversation) {
        Prefs.get().setThreadArchived(conversation.threadId, !conversation.archived)
        loadConversations()
    }

    private fun toggleMute(conversation: Conversation) {
        val currentlyMuted = Prefs.get().isThreadMuted(conversation.threadId)
        Prefs.get().setThreadMuted(conversation.threadId, !currentlyMuted)
        loadConversations()
    }

    private fun copyNumber(phoneNumber: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("phone", phoneNumber)
        )
    }

    private fun moveToRecycleBin(conversation: Conversation) {
        AlertDialog.Builder(this)
            .setTitle(R.string.move_to_recycle_bin)
            .setMessage(conversation.title)
            .setPositiveButton(R.string.yes) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val threadId = conversation.threadId
                    // If recycle bin is enabled, snapshot all SMS messages before deleting
                    if (Prefs.get().recycleBinEnabled) {
                        val cursor = contentResolver.query(
                            android.net.Uri.parse("content://sms"),
                            arrayOf("_id", "address", "body", "date"),
                            "thread_id = ?",
                            arrayOf(threadId.toString()),
                            "date ASC"
                        )
                        cursor?.use { c ->
                            val idCol   = c.getColumnIndexOrThrow("_id")
                            val addrCol = c.getColumnIndexOrThrow("address")
                            val bodyCol = c.getColumnIndexOrThrow("body")
                            val dateCol = c.getColumnIndexOrThrow("date")
                            while (c.moveToNext()) {
                                val msgId  = c.getLong(idCol)
                                val addr   = c.getString(addrCol) ?: ""
                                val body   = c.getString(bodyCol) ?: ""
                                val date   = c.getLong(dateCol)
                                val name   = App.get().contactHelper.getDisplayName(addr)
                                App.get().database.messagesDao().insertRecycleBinMessage(
                                    com.dpad.messaging.models.RecycleBinMessage(
                                        id         = msgId,
                                        address    = addr,
                                        senderName = name,
                                        body       = body,
                                        date       = date
                                    )
                                )
                            }
                        }
                    }
                    // Delete the conversation from Telephony
                    val uri = android.net.Uri.parse("content://mms-sms/conversations/$threadId")
                    try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                    // Clean up any state prefs for this thread
                    Prefs.get().setThreadArchived(threadId, false)
                    Prefs.get().setThreadPinned(threadId, false)
                    withContext(Dispatchers.Main) { loadConversations() }
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    // ─── Permissions ────────────────────────────────────────────────────────

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) loadConversations()
    }

    private fun checkDefaultSmsApp() {
        if (Prefs.get().defaultSmsDismissed) return

        val isDefault = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        } else {
            Telephony.Sms.getDefaultSmsPackage(this) == packageName
        }

        if (!isDefault) {
            requestDefaultSmsApp()
        }
    }

    private fun requestDefaultSmsApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (!roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                requestRoleLauncher.launch(intent)
            }
        } else {
            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            }
            startActivity(intent)
        }
    }

    // ─── EventBus ──────────────────────────────────────────────────────────

    @Subscribe(threadMode = ThreadMode.MAIN)
    @Suppress("UNUSED_PARAMETER")
    fun onRefreshConversations(event: RefreshConversations) {
        loadConversations(forceRefresh = true)
    }

    // ─── Key handling ───────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                when {
                    isSearchVisible -> { hideSearch(); true }
                    else -> super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_F -> {
                showSearch(); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
        private const val REQUEST_DEFAULT_SMS = 1002
    }
}
