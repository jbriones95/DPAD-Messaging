package com.dpad.messaging.activities

import android.Manifest
import android.database.ContentObserver
import android.app.role.RoleManager
import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.google.android.material.snackbar.Snackbar
import com.dpad.messaging.App
import com.dpad.messaging.BuildConfig
import com.dpad.messaging.R
import com.dpad.messaging.adapters.ConversationsAdapter
import com.dpad.messaging.databinding.ActivityMainBinding
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.extensions.getConversationsFromTelephony
import com.dpad.messaging.helpers.ConversationCache
import com.dpad.messaging.helpers.ContactColors
import com.dpad.messaging.helpers.ExternalComposeIntentParser
import com.dpad.messaging.extensions.markThreadAsReadInTelephony
import com.dpad.messaging.extensions.markThreadAsUnreadInTelephony
import com.dpad.messaging.helpers.Prefs
import com.dpad.messaging.helpers.ThemeManager
import com.dpad.messaging.models.Draft
import com.dpad.messaging.models.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var conversationsAdapter: ConversationsAdapter
    private lateinit var requestRoleLauncher: ActivityResultLauncher<Intent>

    /** Debounce job for search filtering — cancels and reschedules on each keystroke */
    private var searchDebounceJob: Job? = null

    /** Debounce job for RefreshConversations events — coalesces bursts of SMS into one reload. */
    private var refreshDebounceJob: Job? = null

    /** Active load job for conversations; cancelled when a newer load starts. */
    private var loadConversationsJob: Job? = null

    /** Thread to focus after list refresh (used when returning from a conversation). */
    private var pendingFocusThreadId: Long? = null
    private var hasLoadedConversationsOnce = false
    private var conversationLoadError = false
    private val selectedThreadIds = linkedSetOf<Long>()
    private var isActivityResumed = false
    private var conversationDataDirty = true
    private var conversationChangeGeneration = 0L
    private var providerObserverRegistered = false

    private val providerObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            conversationDataDirty = true
            conversationChangeGeneration++
            if (isActivityResumed) scheduleProviderRefresh()
        }
    }

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
        registerProviderObserver()
        checkPermissions()
        if (handleLauncherThreadIntent(intent)) return
        handleExternalComposeIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleLauncherThreadIntent(intent)) return
        handleExternalComposeIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        EventBus.getDefault().register(this)
        applyAccent()
        // Reload only when the telephony/contact providers changed while away.
        if (conversationDataDirty || !hasLoadedConversationsOnce) {
            App.get().contactHelper.clearCache()
        }
        refreshConversationList()
        checkDefaultSmsApp()
    }

    private fun refreshConversationList() {
        if (conversationDataDirty || !hasLoadedConversationsOnce || ConversationCache.get() == null) {
            loadConversations()
        } else {
            ConversationCache.get()?.let { displayConversations(it) }
        }
    }

    override fun onPause() {
        isActivityResumed = false
        pendingFocusThreadId = currentFocusedThreadId() ?: pendingFocusThreadId
        loadConversationsJob?.cancel()
        refreshDebounceJob?.cancel()
        EventBus.getDefault().unregister(this)
        super.onPause()
    }

    override fun onDestroy() {
        if (providerObserverRegistered) {
            contentResolver.unregisterContentObserver(providerObserver)
        }
        super.onDestroy()
    }

    private fun registerProviderObserver() {
        if (providerObserverRegistered) return
        runCatching {
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, providerObserver)
            contentResolver.registerContentObserver(Uri.parse("content://mms"), true, providerObserver)
            contentResolver.registerContentObserver(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                true,
                providerObserver
            )
            providerObserverRegistered = true
        }.onFailure {
            // Permissions may not have been granted on the first launch. Retry
            // from onResume or after the permission callback.
            runCatching { contentResolver.unregisterContentObserver(providerObserver) }
        }
    }

    private fun scheduleProviderRefresh() {
        refreshDebounceJob?.cancel()
        refreshDebounceJob = lifecycleScope.launch {
            delay(REFRESH_DEBOUNCE_MS)
            loadConversations(forceRefresh = true)
        }
    }

    // ─── Setup ─────────────────────────────────────────────────────────────

    private fun setupConversationList() {
        conversationsAdapter = ConversationsAdapter(
            onConversationClick = { conversation ->
                if (selectedThreadIds.isNotEmpty()) toggleSelection(conversation)
                else openThread(conversation)
            },
            onConversationLongClick = { conversation -> toggleSelection(conversation) },
            onConversationMenuClick = { _, conversation -> showConversationContextMenu(conversation) },
            onAvatarLongClick = { conversation -> showContactColorPicker(conversation) },
            isConversationSelected = { selectedThreadIds.contains(it) }
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
        binding.btnRetryConversations.setOnClickListener { loadConversations(forceRefresh = true) }
        binding.btnSelectionClear.setOnClickListener { clearSelection() }
        binding.btnSelectionArchive.setOnClickListener { archiveSelected() }
        binding.btnSelectionRead.setOnClickListener { markSelectedRead() }

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

        var showedCached = false
        if (!forceRefresh) {
            ConversationCache.get()?.let { cached ->
                displayConversations(cached)
                showedCached = true
            }
        }

        val showLoading = (!hasLoadedConversationsOnce && !showedCached) ||
            (forceRefresh && conversationsAdapter.currentList.isEmpty())
        if (showLoading) {
            conversationLoadError = false
            binding.loadingConversations.visibility = View.VISIBLE
            binding.tvEmpty.visibility = View.GONE
            binding.conversationError.visibility = View.GONE
            binding.rvConversations.visibility = View.INVISIBLE
        }

        loadConversationsJob?.cancel()
        loadConversationsJob = lifecycleScope.launch {
            val generationAtStart = conversationChangeGeneration
            try {
                val pinnedIds = Prefs.get().getPinnedThreadIds()
                val mutedIds = Prefs.get().getMutedThreadIds()
                val conversations = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(CONVERSATION_LOAD_TIMEOUT_MS) {
                        getConversationsFromTelephony(
                            App.get().contactHelper,
                            pinnedIds,
                            mutedThreadIds = mutedIds
                        )
                    } ?: throw java.io.IOException("Conversation load timed out")
                }
                if (!isActive) return@launch
                hasLoadedConversationsOnce = true
                ConversationCache.put(conversations)
                if (generationAtStart == conversationChangeGeneration) {
                    conversationDataDirty = false
                }
                displayConversations(conversations)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) android.util.Log.w("DPAD_MSG", "loadConversations failed", e)
                if (!isActive) return@launch
                hasLoadedConversationsOnce = true
                if (conversationsAdapter.currentList.isEmpty()) {
                    conversationLoadError = true
                    binding.conversationError.visibility = View.VISIBLE
                    binding.tvEmpty.visibility = View.GONE
                    binding.rvConversations.visibility = View.INVISIBLE
                } else {
                    Snackbar.make(binding.root, R.string.conversations_load_failed, Snackbar.LENGTH_LONG).show()
                }
            } finally {
                binding.loadingConversations.visibility = View.GONE
                if (!conversationLoadError) binding.rvConversations.visibility = View.VISIBLE
            }
        }
    }

    private fun displayConversations(conversations: List<Conversation>) {
        conversationLoadError = false
        binding.conversationError.visibility = View.GONE
        selectedThreadIds.retainAll(conversations.mapTo(HashSet()) { it.threadId })
        conversationsAdapter.submitList(conversations) {
            binding.tvEmpty.setText(R.string.no_conversations)
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
            binding.tvEmpty.setText(
                if (filtered.isEmpty()) R.string.no_search_results else R.string.no_conversations
            )
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

    private fun showContactColorPicker(conversation: Conversation) {
        val number = conversation.phoneNumber
        if (number.isBlank()) return
        val current = ContactColors.customColor(number)
        ContactColors.showColorPicker(
            context = this,
            title = conversation.title.ifBlank { number },
            currentColor = current,
            onSelected = { selected ->
            if (selected != current) {
                Prefs.get().setContactColor(ContactColors.normalize(number), selected)
                conversationsAdapter.notifyDataSetChanged()
            }
            }
        )
    }

    private fun openThreadById(threadId: Long) {
        if (threadId <= 0L) return
        pendingFocusThreadId = threadId
        startActivity(Intent(this, ThreadActivity::class.java).apply {
            putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId)
        })
    }

    private fun handleLauncherThreadIntent(incomingIntent: Intent?): Boolean {
        val intent = incomingIntent ?: return false

        val directExtra = intent.getLongExtra(ThreadActivity.EXTRA_THREAD_ID, -1L)
        val threadId = if (directExtra > 0L) {
            directExtra
        } else {
            resolveThreadIdFromLauncherIntent(intent)
        }

        if (threadId <= 0L) return false
        openThreadById(threadId)
        return true
    }

    private fun resolveThreadIdFromLauncherIntent(intent: Intent): Long {
        val extras = listOf("thread_id", "threadId", "conversation_id", "conversationId")
        for (key in extras) {
            val value = intent.getLongExtra(key, -1L)
            if (value > 0L) return value
            val raw = intent.extras?.get(key)?.toString()?.toLongOrNull()
            if (raw != null && raw > 0L) return raw
        }

        val data = intent.data ?: return -1L
        return runCatching {
            if (data.isHierarchical) {
                val queryThread = data.getQueryParameter("thread_id")?.toLongOrNull()
                if (queryThread != null && queryThread > 0L) return queryThread
            }
            val scheme = data.scheme?.lowercase() ?: return -1L
            if (scheme == "sms" || scheme == "mms") {
                val number = data.schemeSpecificPart?.trim()
                if (!number.isNullOrBlank()) {
                    return findThreadIdByPhoneNumber(number)
                }
            }
            if (scheme != "content") return -1L

            val authority = data.authority?.lowercase().orEmpty()
            val segments = data.pathSegments

            if (authority == "mms-sms" && segments.firstOrNull() == "conversations") {
                val parsed = segments.getOrNull(1)?.toLongOrNull()
                if (parsed != null && parsed > 0L) return parsed
            }

            if (authority == "mms" || authority == "sms") {
                return queryThreadIdForContentMessageUri(data)
            }

            -1L
        }.getOrDefault(-1L)
    }

    private fun queryThreadIdForContentMessageUri(uri: Uri): Long {
        return runCatching {
            contentResolver.query(uri, arrayOf("thread_id"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(0)
                } else {
                    -1L
                }
            } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun findThreadIdByPhoneNumber(number: String): Long {
        return runCatching {
            Telephony.Threads.getOrCreateThreadId(this, number)
        }.getOrDefault(-1L)
    }

    private fun handleExternalComposeIntent(incomingIntent: Intent?): Boolean {
        val intent = incomingIntent ?: return false
        val action = intent.action ?: return false
        if (action != Intent.ACTION_SENDTO && action != Intent.ACTION_VIEW) return false

        val data = intent.data ?: return false
        if (!ExternalComposeIntentParser.supportsScheme(data)) return false

        val prefillBody = ExternalComposeIntentParser.body(
            data = data,
            extraText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            smsBody = intent.getStringExtra("sms_body"),
            subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        )
        val recipients = ExternalComposeIntentParser.recipients(
            data,
            intent.getStringExtra("address")
        )
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
        }.onFailure { error ->
            android.util.Log.w("DPAD_MSG", "External compose thread resolution failed for $recipients", error)
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
        clearSelection()
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
            add(0, 8, 0, getString(R.string.select_conversation))
            add(0, 1, 1, if (conversation.read) getString(R.string.mark_as_unread) else getString(R.string.mark_as_read))
            add(0, 2, 2, if (conversation.pinned) getString(R.string.unpin) else getString(R.string.pin))
            add(0, 3, 3, if (conversation.archived) getString(R.string.unarchive) else getString(R.string.archive))
            add(0, 4, 4, if (Prefs.get().isThreadMuted(conversation.threadId)) getString(R.string.unmute_conversation) else getString(R.string.mute_conversation))
            add(0, 5, 5, getString(R.string.copy_number))
            add(0, 6, 6, getString(R.string.move_to_recycle_bin))
            add(0, 7, 7, getString(R.string.conversation_details))
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                8 -> toggleSelection(conversation)
                1 -> toggleReadState(conversation)
                2 -> togglePin(conversation)
                3 -> toggleArchive(conversation)
                4 -> toggleMute(conversation)
                5 -> copyNumber(conversation.phoneNumber)
                6 -> moveToRecycleBin(conversation)
                7 -> openConversationDetails(conversation)
            }
            true
        }

        popup.show()
    }

    private fun toggleSelection(conversation: Conversation) {
        if (!selectedThreadIds.add(conversation.threadId)) {
            selectedThreadIds.remove(conversation.threadId)
        }
        updateSelectionUi()
        conversationsAdapter.notifyDataSetChanged()
    }

    private fun clearSelection() {
        if (selectedThreadIds.isEmpty()) return
        selectedThreadIds.clear()
        updateSelectionUi()
        conversationsAdapter.notifyDataSetChanged()
    }

    private fun updateSelectionUi() {
        val selecting = selectedThreadIds.isNotEmpty()
        binding.toolbar.visibility = if (selecting) View.GONE else View.VISIBLE
        binding.selectionToolbar.visibility = if (selecting) View.VISIBLE else View.GONE
        binding.tvSelectionCount.text = getString(R.string.selection_count, selectedThreadIds.size)
    }

    private fun archiveSelected() {
        selectedThreadIds.forEach { Prefs.get().setThreadArchived(it, true) }
        clearSelection()
        loadConversations()
    }

    private fun markSelectedRead() {
        val ids = selectedThreadIds.toList()
        clearSelection()
        lifecycleScope.launch(Dispatchers.IO) {
            val database = App.get().database
            ids.forEach { threadId ->
                database.conversationsDao().markAsRead(threadId)
                database.messagesDao().markThreadRead(threadId)
                markThreadAsReadInTelephony(threadId)
            }
            withContext(Dispatchers.Main) { loadConversations(forceRefresh = true) }
        }
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
        val markUnread = conversation.read   // menu shows "mark as unread" when currently read
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = App.get().database.conversationsDao()
            val messagesDao = App.get().database.messagesDao()
            if (markUnread) {
                dao.markAsUnread(conversation.threadId)
                messagesDao.markThreadUnread(conversation.threadId)
                markThreadAsUnreadInTelephony(conversation.threadId)
            } else {
                dao.markAsRead(conversation.threadId)
                messagesDao.markThreadRead(conversation.threadId)
                markThreadAsReadInTelephony(conversation.threadId)
            }
        }
        loadConversations(forceRefresh = true)
    }

    private fun togglePin(conversation: Conversation) {
        Prefs.get().setThreadPinned(conversation.threadId, !conversation.pinned)
        loadConversations()
    }

    private fun toggleArchive(conversation: Conversation) {
        val archive = !conversation.archived
        Prefs.get().setThreadArchived(conversation.threadId, archive)
        loadConversations()
        if (archive) {
            Snackbar.make(binding.root, R.string.conversation_archived, Snackbar.LENGTH_LONG)
                .setAction(R.string.undo) {
                    Prefs.get().setThreadArchived(conversation.threadId, false)
                    loadConversations()
                }
                .show()
        }
    }

    private fun openConversationDetails(conversation: Conversation) {
        startActivity(Intent(this, ConversationDetailsActivity::class.java).apply {
            putExtra(ThreadActivity.EXTRA_THREAD_ID, conversation.threadId)
            putExtra(ThreadActivity.EXTRA_THREAD_TITLE, conversation.title)
            putExtra(ThreadActivity.EXTRA_PHONE_NUMBER, conversation.phoneNumber)
            if (conversation.participants.isNotBlank()) {
                putExtra(ThreadActivity.EXTRA_PARTICIPANTS, conversation.participants)
            }
        })
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
        if (requestCode == REQUEST_PERMISSIONS) {
            registerProviderObserver()
            loadConversations()
        }
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
        // Coalesce bursts of RefreshConversations (one per received message) into a
        // single force-refresh. Otherwise every incoming SMS cancels/restarts the
        // whole provider scan and the "loading" spinner never settles.
        refreshDebounceJob?.cancel()
        refreshDebounceJob = lifecycleScope.launch {
            delay(REFRESH_DEBOUNCE_MS)
            loadConversations(forceRefresh = true)
        }
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
        private const val CONVERSATION_LOAD_TIMEOUT_MS = 20_000L
        private const val REFRESH_DEBOUNCE_MS = 800L
    }
}
