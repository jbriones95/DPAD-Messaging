package com.dpad.messaging.adapters

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.dpad.messaging.R
import com.dpad.messaging.databinding.ItemConversationBinding
import com.dpad.messaging.helpers.ContactColors
import com.dpad.messaging.helpers.Prefs
import com.dpad.messaging.helpers.ThemeManager
import com.dpad.messaging.models.Conversation
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ConversationsAdapter(
    private val onConversationClick: (Conversation) -> Unit,
    private val onConversationLongClick: (Conversation) -> Unit,
    private val onConversationMenuClick: (View, Conversation) -> Unit,
    private val onAvatarLongClick: ((Conversation) -> Unit)? = null
) : ListAdapter<Conversation, ConversationsAdapter.ConversationViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Conversation>() {
            override fun areItemsTheSame(old: Conversation, new: Conversation) =
                old.threadId == new.threadId

            override fun areContentsTheSame(old: Conversation, new: Conversation) =
                old == new
        }

        private val timeFormat12h by lazy { SimpleDateFormat("h:mm a", Locale.getDefault()) }
        private val timeFormat24h by lazy { SimpleDateFormat("HH:mm", Locale.getDefault()) }
        private val weekdayFormat by lazy { SimpleDateFormat("EEE", Locale.getDefault()) }
        private val shortDateMdy by lazy { SimpleDateFormat("MM/dd/yy", Locale.getDefault()) }
        private val shortDateDmy by lazy { SimpleDateFormat("dd/MM/yy", Locale.getDefault()) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ConversationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ConversationViewHolder) {
        Glide.with(holder.itemView.context).clear(holder.binding.ivAvatar)
        super.onViewRecycled(holder)
    }

    inner class ConversationViewHolder(
        val binding: ItemConversationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(conversation: Conversation) {
            val bold = if (!conversation.read) Typeface.BOLD else Typeface.NORMAL
            val accent = ThemeManager.accentColor(binding.root.context)

            // Title
            binding.tvName.text = conversation.title
            binding.tvName.setTypeface(null, bold)

            // Snippet
            binding.tvSnippet.text = conversation.snippet
            binding.tvSnippet.setTypeface(null, bold)
            binding.tvSnippet.setTextColor(
                if (!conversation.read)
                    binding.root.context.getColor(R.color.conversationTitle)
                else
                    binding.root.context.getColor(R.color.conversationSnippet)
            )

            // Date
            binding.tvDate.text = formatDate(conversation.date)

            // Unread badge
            if (!conversation.read && conversation.unreadCount > 0) {
                binding.tvUnreadCount.visibility = View.VISIBLE
                binding.tvUnreadCount.text = conversation.unreadCount.coerceAtMost(99).toString()
                binding.tvUnreadCount.background?.mutate()?.setTint(accent)
            } else {
                binding.tvUnreadCount.visibility = View.GONE
            }

            // Pin indicator
            binding.ivPinned.visibility =
                if (conversation.pinned) View.VISIBLE else View.GONE
            binding.ivPinned.imageTintList = ColorStateList.valueOf(accent)

            // Mute indicator
            binding.ivMuted.visibility = if (conversation.muted) View.VISIBLE else View.GONE
            binding.ivMuted.imageTintList = ColorStateList.valueOf(accent)

            val tint = ColorStateList.valueOf(accent)
            binding.btnConversationMenu.imageTintList = tint
            binding.btnConversationMenu.backgroundTintList = tint
            binding.conversationClickArea.backgroundTintList = tint

            // Avatar
            bindAvatar(conversation)

            // Interactions
            binding.conversationClickArea.setOnClickListener { onConversationClick(conversation) }
            binding.conversationClickArea.setOnLongClickListener {
                onConversationLongClick(conversation)
                true
            }
            binding.btnConversationMenu.setOnClickListener {
                onConversationMenuClick(it, conversation)
            }
            binding.btnConversationMenu.tag = conversation.threadId
        }

        private fun bindAvatar(conversation: Conversation) {
            val avatarClickListener = onAvatarLongClick?.let { click ->
                View.OnLongClickListener {
                    click(conversation)
                    true
                }
            }

            if (conversation.photoUri.isNotBlank()) {
                binding.ivAvatar.visibility = View.VISIBLE
                binding.tvAvatarLetter.visibility = View.GONE
                Glide.with(binding.ivAvatar.context)
                    .load(Uri.parse(conversation.photoUri))
                    .circleCrop()
                    .placeholder(R.drawable.ic_person)
                    .into(binding.ivAvatar)
                binding.ivAvatar.setOnLongClickListener(avatarClickListener)
            } else {
                binding.ivAvatar.visibility = View.GONE
                binding.tvAvatarLetter.visibility = View.VISIBLE
                val initial = conversation.title.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
                binding.tvAvatarLetter.text = initial
                binding.tvAvatarLetter.background.setTint(
                    ContactColors.resolveColor(conversation.phoneNumber)
                )
                binding.tvAvatarLetter.setOnLongClickListener(avatarClickListener)
            }
        }

        private fun formatDate(timestamp: Long): String {
            if (timestamp == 0L) return ""
            val now = Calendar.getInstance()
            val msg = Calendar.getInstance().apply { timeInMillis = timestamp }
            val prefs = Prefs.get()
            return when {
                isSameDay(now, msg) -> {
                    val fmt = if (prefs.timeFormat == Prefs.TIME_FORMAT_24H) timeFormat24h else timeFormat12h
                    fmt.format(Date(timestamp))
                }
                diffDays(now, msg) < 7 ->
                    weekdayFormat.format(Date(timestamp))
                else -> {
                    val fmt = if (prefs.dateFormat == Prefs.DATE_FORMAT_DMY) shortDateDmy else shortDateMdy
                    fmt.format(Date(timestamp))
                }
            }
        }

        private fun isSameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

        private fun diffDays(now: Calendar, msg: Calendar) =
            ((now.timeInMillis - msg.timeInMillis) / (24 * 60 * 60 * 1000L)).toInt()
    }

}
