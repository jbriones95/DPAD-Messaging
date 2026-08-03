package com.dpad.messaging.adapters

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.dpad.messaging.R
import com.dpad.messaging.activities.ImageViewerActivity
import com.dpad.messaging.databinding.ItemMessageFailedBinding
import com.dpad.messaging.databinding.ItemMessageReceivedBinding
import com.dpad.messaging.databinding.ItemMessageSendingBinding
import com.dpad.messaging.databinding.ItemMessageSentBinding
import com.dpad.messaging.databinding.ItemThreadDateBinding
import com.dpad.messaging.helpers.Prefs
import com.dpad.messaging.models.Message
import com.dpad.messaging.models.ThreadItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ThreadAdapter(
    private val onMessageLongClick: (Message) -> Unit
) : ListAdapter<ThreadItem, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_SENT = 1
        private const val VIEW_TYPE_RECEIVED = 2
        private const val VIEW_TYPE_SENDING = 3
        private const val VIEW_TYPE_FAILED = 4

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ThreadItem>() {
            override fun areItemsTheSame(old: ThreadItem, new: ThreadItem): Boolean = when {
                old is ThreadItem.DateHeader && new is ThreadItem.DateHeader ->
                    old.date == new.date
                old is ThreadItem.SentMessage && new is ThreadItem.SentMessage ->
                    old.message.id == new.message.id && old.message.isMms == new.message.isMms
                old is ThreadItem.ReceivedMessage && new is ThreadItem.ReceivedMessage ->
                    old.message.id == new.message.id && old.message.isMms == new.message.isMms
                old is ThreadItem.SendingMessage && new is ThreadItem.SendingMessage ->
                    old.message.id == new.message.id && old.message.isMms == new.message.isMms
                else -> false
            }
            override fun areContentsTheSame(old: ThreadItem, new: ThreadItem) = old == new
        }

        // Reusable calendar for date arithmetic to avoid per-call allocations.
        private val calendar = Calendar.getInstance()

        private val timeFormat12h by lazy {
            SimpleDateFormat("h:mm a", Locale.getDefault())
        }
        private val timeFormat24h by lazy {
            SimpleDateFormat("HH:mm", Locale.getDefault())
        }
        private val dayNameFormat by lazy {
            SimpleDateFormat("EEEE", Locale.getDefault())
        }
        private val fullDateFormat by lazy {
            SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
        }
    }

    override fun getItemViewType(position: Int): Int = when (val item = getItem(position)) {
        is ThreadItem.DateHeader -> VIEW_TYPE_HEADER
        is ThreadItem.SendingMessage -> VIEW_TYPE_SENDING
        is ThreadItem.ReceivedMessage -> VIEW_TYPE_RECEIVED
        is ThreadItem.SentMessage ->
            if (item.message.type == Message.TYPE_FAILED) VIEW_TYPE_FAILED else VIEW_TYPE_SENT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> DateHeaderViewHolder(
                ItemThreadDateBinding.inflate(inf, parent, false)
            )
            VIEW_TYPE_SENT -> SentViewHolder(
                ItemMessageSentBinding.inflate(inf, parent, false)
            )
            VIEW_TYPE_RECEIVED -> ReceivedViewHolder(
                ItemMessageReceivedBinding.inflate(inf, parent, false)
            )
            VIEW_TYPE_SENDING -> SendingViewHolder(
                ItemMessageSendingBinding.inflate(inf, parent, false)
            )
            else -> FailedViewHolder(
                ItemMessageFailedBinding.inflate(inf, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is DateHeaderViewHolder ->
                holder.bind(getItem(position) as ThreadItem.DateHeader)
            is SentViewHolder ->
                holder.bind((getItem(position) as ThreadItem.SentMessage).message)
            is ReceivedViewHolder ->
                holder.bind((getItem(position) as ThreadItem.ReceivedMessage).message)
            is SendingViewHolder ->
                holder.bind((getItem(position) as ThreadItem.SendingMessage).message)
            is FailedViewHolder ->
                holder.bind((getItem(position) as ThreadItem.SentMessage).message)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        val iv = holder.itemView.findViewById<android.widget.ImageView>(R.id.iv_attachment)
        if (iv != null) Glide.with(holder.itemView.context).clear(iv)
    }

    // ─── ViewHolders ───────────────────────────────────────────────────────

    inner class DateHeaderViewHolder(
        private val binding: ItemThreadDateBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ThreadItem.DateHeader) {
            binding.tvDate.text = formatHeaderDate(item.date, binding.root.context)
        }
    }

    inner class SentViewHolder(
        private val binding: ItemMessageSentBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message) {
            binding.tvBody.text = message.body
            binding.tvBody.scrollTo(0, 0)
            binding.tvBody.visibility = if (message.body.isBlank()) View.GONE else View.VISIBLE
            binding.tvTime.text = formatTime(message.date)
            if (message.status == Message.STATUS_COMPLETE) {
                binding.tvStatus.text = binding.root.context.getString(R.string.delivered)
                binding.tvStatus.visibility = View.VISIBLE
            } else {
                binding.tvStatus.visibility = View.GONE
            }
            // MMS image attachment
            if (message.isMms && message.attachmentsJson.startsWith("content://")) {
                binding.ivAttachment.visibility = View.VISIBLE
                val attachmentUri = message.attachmentsJson
                Glide.with(binding.root.context)
                    .load(Uri.parse(attachmentUri))

                    .into(binding.ivAttachment)
                binding.ivAttachment.isFocusable = true
                binding.ivAttachment.isFocusableInTouchMode = true
                binding.ivAttachment.isClickable = true
                binding.ivAttachment.setOnClickListener { openImageViewer(binding.root.context, attachmentUri) }
                binding.bubbleContainer.setOnClickListener { openImageViewer(binding.root.context, attachmentUri) }
            } else {
                binding.ivAttachment.visibility = View.GONE
                binding.ivAttachment.isFocusable = false
                binding.ivAttachment.isFocusableInTouchMode = false
                binding.ivAttachment.isClickable = false
                binding.ivAttachment.setOnClickListener(null)
                binding.bubbleContainer.setOnClickListener(null)
            }
            binding.bubbleContainer.setOnLongClickListener {
                onMessageLongClick(message)
                true
            }
        }
    }

    inner class ReceivedViewHolder(
        private val binding: ItemMessageReceivedBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message) {
            binding.tvBody.text = message.body
            binding.tvBody.scrollTo(0, 0)
            binding.tvBody.visibility = if (message.body.isBlank()) View.GONE else View.VISIBLE
            binding.tvTime.text = formatTime(message.date)
            if (message.senderName.isNotBlank()) {
                binding.tvSenderName.text = message.senderName
                binding.tvSenderName.visibility = View.VISIBLE
            } else {
                binding.tvSenderName.visibility = View.GONE
            }
            // MMS image attachment
            if (message.isMms && message.attachmentsJson.startsWith("content://")) {
                binding.ivAttachment.visibility = View.VISIBLE
                val attachmentUri = message.attachmentsJson
                Glide.with(binding.root.context)
                    .load(Uri.parse(attachmentUri))

                    .into(binding.ivAttachment)
                binding.ivAttachment.isFocusable = true
                binding.ivAttachment.isFocusableInTouchMode = true
                binding.ivAttachment.isClickable = true
                binding.ivAttachment.setOnClickListener { openImageViewer(binding.root.context, attachmentUri) }
                binding.bubbleContainer.setOnClickListener { openImageViewer(binding.root.context, attachmentUri) }
            } else {
                binding.ivAttachment.visibility = View.GONE
                binding.ivAttachment.isFocusable = false
                binding.ivAttachment.isFocusableInTouchMode = false
                binding.ivAttachment.isClickable = false
                binding.ivAttachment.setOnClickListener(null)
                binding.bubbleContainer.setOnClickListener(null)
            }
            binding.bubbleContainer.setOnLongClickListener {
                onMessageLongClick(message)
                true
            }
        }
    }

    inner class SendingViewHolder(
        private val binding: ItemMessageSendingBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message) {
            val bodyText = when {
                message.body.isNotBlank() -> message.body
                message.isMms -> binding.root.context.getString(R.string.attach)
                else -> ""
            }
            binding.tvBody.text = bodyText
            binding.tvBody.scrollTo(0, 0)
            binding.tvBody.visibility = if (bodyText.isBlank()) View.GONE else View.VISIBLE
            if (message.isScheduled) {
                binding.ivScheduled.visibility = View.VISIBLE
                binding.tvState.text = binding.root.context.getString(R.string.sending_later)
            } else {
                binding.ivScheduled.visibility = View.GONE
                binding.tvState.text = binding.root.context.getString(R.string.sending)
            }

            if (message.isMms && message.attachmentsJson.startsWith("content://")) {
                binding.ivAttachment.visibility = View.VISIBLE
                val attachmentUri = message.attachmentsJson
                Glide.with(binding.root.context)
                    .load(Uri.parse(attachmentUri))

                    .into(binding.ivAttachment)
                binding.ivAttachment.isFocusable = true
                binding.ivAttachment.isFocusableInTouchMode = true
                binding.ivAttachment.isClickable = true
                binding.ivAttachment.setOnClickListener { openImageViewer(binding.root.context, attachmentUri) }
                binding.bubbleContainer.setOnClickListener { openImageViewer(binding.root.context, attachmentUri) }
            } else {
                binding.ivAttachment.visibility = View.GONE
                binding.ivAttachment.isFocusable = false
                binding.ivAttachment.isFocusableInTouchMode = false
                binding.ivAttachment.isClickable = false
                binding.ivAttachment.setOnClickListener(null)
                binding.bubbleContainer.setOnClickListener(null)
            }

            binding.bubbleContainer.setOnLongClickListener {
                onMessageLongClick(message)
                true
            }
        }
    }

    private fun openImageViewer(context: android.content.Context, attachmentUri: String) {
        val intent = Intent(context, ImageViewerActivity::class.java)
            .putExtra(ImageViewerActivity.EXTRA_IMAGE_URI, attachmentUri)
        context.startActivity(intent)
    }

    inner class FailedViewHolder(
        private val binding: ItemMessageFailedBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message) {
            binding.tvBody.text = message.body
            binding.tvBody.scrollTo(0, 0)
            binding.tvBody.visibility = if (message.body.isBlank()) View.GONE else View.VISIBLE
            binding.bubbleContainer.setOnLongClickListener {
                onMessageLongClick(message)
                true
            }
        }
    }

    // ─── Formatting ────────────────────────────────────────────────────────

    private fun formatTime(timestamp: Long): String {
        if (timestamp == 0L) return ""
        val fmt = if (Prefs.get().timeFormat == Prefs.TIME_FORMAT_24H) timeFormat24h else timeFormat12h
        return fmt.format(Date(timestamp))
    }

    private fun formatHeaderDate(timestamp: Long, context: android.content.Context): String {
        val now = Calendar.getInstance()
        val msg = Calendar.getInstance().apply { timeInMillis = timestamp }
        return when {
            isSameDay(now, msg) -> context.getString(R.string.today)
            isYesterday(now, msg) -> context.getString(R.string.yesterday)
            diffDays(now, msg) < 7 ->
                dayNameFormat.format(Date(timestamp))
            else ->
                fullDateFormat.format(Date(timestamp))
        }
    }

    private fun isSameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun isYesterday(now: Calendar, msg: Calendar): Boolean {
        val yesterday = calendar.apply {
            timeInMillis = now.timeInMillis
            add(Calendar.DAY_OF_YEAR, -1)
        }
        return isSameDay(yesterday, msg)
    }

    private fun diffDays(now: Calendar, msg: Calendar) =
        ((now.timeInMillis - msg.timeInMillis) / (24 * 60 * 60 * 1000L)).toInt()
}
