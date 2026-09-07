package com.dpad.messaging.adapters

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dpad.messaging.R
import com.dpad.messaging.databinding.ItemConversationBinding
import com.dpad.messaging.helpers.ContactColors
import com.dpad.messaging.helpers.Prefs
import com.dpad.messaging.helpers.ThemeManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Lightweight data class carrying the full message data needed to display
 * a recycle-bin row (fetched from the Telephony CP by ID at load time).
 */
data class RecycledItem(
    val id: Long,
    val senderName: String,
    val phoneNumber: String,
    val body: String,
    val date: Long
)

/**
 * Adapter for RecycleBinActivity — reuses item_conversation.xml.
 * Unread badge and pin indicator are always hidden.
 */
class RecycleBinAdapter(
    private val onItemClick: (RecycledItem) -> Unit,
    private val onItemLongClick: (RecycledItem) -> Unit,
    private val onItemMenuClick: (View, RecycledItem) -> Unit
) : ListAdapter<RecycledItem, RecycleBinAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemConversationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecycledItem) {
            val accent = ThemeManager.accentColor(binding.root.context)
            val tint   = ColorStateList.valueOf(accent)

            binding.tvName.text = item.senderName
            binding.tvName.setTypeface(null, Typeface.NORMAL)

            binding.tvSnippet.text = item.body
            binding.tvSnippet.setTypeface(null, Typeface.NORMAL)
            binding.tvSnippet.setTextColor(
                binding.root.context.getColor(R.color.conversationSnippet)
            )

            binding.tvDate.text = formatDate(item.date)

            // No unread badge, pin or mute in recycle bin
            binding.tvUnreadCount.visibility = View.GONE
            binding.ivPinned.visibility = View.GONE
            binding.ivMuted.visibility = View.GONE

            // Accent tinting
            binding.btnConversationMenu.imageTintList = tint
            binding.btnConversationMenu.backgroundTintList = tint
            binding.conversationClickArea.backgroundTintList = tint

            // Avatar — letter-only (no photo lookup for deleted messages)
            binding.ivAvatar.visibility = View.GONE
            binding.tvAvatarLetter.visibility = View.VISIBLE
            val initial = item.senderName.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
            binding.tvAvatarLetter.text = initial
            binding.tvAvatarLetter.background.setTint(
                ContactColors.resolveColor(item.phoneNumber)
            )

            binding.conversationClickArea.setOnClickListener { onItemClick(item) }
            binding.conversationClickArea.setOnLongClickListener { onItemLongClick(item); true }
            binding.btnConversationMenu.setOnClickListener { onItemMenuClick(it, item) }
        }

        private fun formatDate(timestamp: Long): String {
            if (timestamp == 0L) return ""
            val now = Calendar.getInstance()
            val msg = Calendar.getInstance().apply { timeInMillis = timestamp }
            val prefs = Prefs.get()
            return when {
                isSameDay(now, msg) ->
                    SimpleDateFormat(
                        if (prefs.timeFormat == Prefs.TIME_FORMAT_24H) "HH:mm" else "h:mm a",
                        Locale.getDefault()
                    ).format(Date(timestamp))
                diffDays(now, msg) < 7 ->
                    SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
                else ->
                    SimpleDateFormat(
                        if (prefs.dateFormat == Prefs.DATE_FORMAT_DMY) "dd/MM/yy" else "MM/dd/yy",
                        Locale.getDefault()
                    ).format(Date(timestamp))
            }
        }

        private fun isSameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

        private fun diffDays(now: Calendar, msg: Calendar) =
            ((now.timeInMillis - msg.timeInMillis) / (24 * 60 * 60 * 1000L)).toInt()
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<RecycledItem>() {
            override fun areItemsTheSame(old: RecycledItem, new: RecycledItem) =
                old.id == new.id
            override fun areContentsTheSame(old: RecycledItem, new: RecycledItem) =
                old == new
        }
    }
}
