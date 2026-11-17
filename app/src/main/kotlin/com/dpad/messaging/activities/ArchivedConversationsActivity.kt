package com.dpad.messaging.activities

import com.dpad.messaging.dumber.*

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dpad.messaging.App
import com.dpad.messaging.adapters.ConversationsAdapter
import com.dpad.messaging.databinding.ActivityArchivedBinding
import com.dpad.messaging.extensions.getConversationsFromTelephony
import com.dpad.messaging.helpers.Prefs
import com.dpad.messaging.helpers.ThemeManager
import com.dpad.messaging.models.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArchivedConversationsActivity : BaseActivity() {

    private lateinit var binding: ActivityArchivedBinding
    private lateinit var adapter: ConversationsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyAccentColor(this)
        binding = ActivityArchivedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        adapter = ConversationsAdapter(
            onConversationClick = { openThread(it) },
            onConversationLongClick = { showUnarchiveMenu(binding.root, it) },
            onConversationMenuClick = { view, conversation -> showUnarchiveMenu(view, conversation) }
        )
        binding.rvConversations.apply {
            this.adapter = this@ArchivedConversationsActivity.adapter
            layoutManager = LinearLayoutManager(this@ArchivedConversationsActivity)
            onTopEdgeReached = { binding.btnBack.requestFocus() }
        }

        loadArchivedConversations()
    }

    override fun onResume() {
        super.onResume()
        applyAccent()
        loadArchivedConversations()
    }

    private fun applyAccent() {
        binding.btnBack.imageTintList = ThemeManager.focusAwareTint(this)
    }

    private fun openThread(conversation: Conversation) {
        conversation.group?.let { group ->
            startActivity(Intent(this, DumberGroupActivity::class.java).putExtra("account", group.account).putExtra("room", group.room)); return
        }
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

    private fun loadArchivedConversations() {
        lifecycleScope.launch {
            val archivedIds = Prefs.get().getArchivedThreadIds()
            val archived = withContext(Dispatchers.IO) {
                val recycledConversationIds = App.get().database.messagesDao()
                    .getRecycleBinMessages()
                    .asSequence()
                    .filter { it.isConversation }
                    .map { it.threadId }
                    .toSet()
                // Pass all archived IDs as "pinned" = none; pass archived IDs as archivedThreadIds=none
                // so excluded filter is empty, but set includeOnly to the archived IDs.
                getConversationsFromTelephony(
                    App.get().contactHelper,
                    pinnedThreadIds = Prefs.get().getPinnedThreadIds(),
                    archivedThreadIds = emptySet()   // don't exclude — we WANT archived ones
                ).filter { it.threadId in archivedIds && it.threadId !in recycledConversationIds } +
                    DumberGroupStore(this@ArchivedConversationsActivity).use { it.conversations(archived = true) }
            }
            adapter.submitList(archived)
            binding.tvEmpty.visibility = if (archived.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showUnarchiveMenu(anchor: View, conversation: Conversation) {
        val popup = PopupMenu(ThemeManager.popupMenuContext(this), anchor)
        popup.menu.add(0, 0, 0, getString(com.dpad.messaging.R.string.unarchive))
        popup.setOnMenuItemClickListener {
            if (conversation.group != null) DumberGroupStore(this).use { it.flag(conversation.group!!, "archived", false) } else Prefs.get().setThreadArchived(conversation.threadId, false)
            loadArchivedConversations()
            true
        }
        popup.show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_STAR) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
