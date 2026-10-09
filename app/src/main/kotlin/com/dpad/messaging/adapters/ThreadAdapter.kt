package com.dpad.messaging.adapters

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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
import com.dpad.messaging.helpers.AttachmentPolicy
import com.dpad.messaging.helpers.ContactColors
import com.dpad.messaging.helpers.MmsAttachmentShare
import com.dpad.messaging.helpers.Prefs
import com.dpad.messaging.models.MmsAttachment
import com.dpad.messaging.models.MmsAttachmentJson
import com.dpad.messaging.models.Message
import com.dpad.messaging.models.ThreadItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ThreadAdapter(
    private val onMessageLongClick: (Message) -> Unit,
    private val onAttachmentAction: (MmsAttachment) -> Unit = {},
    private val threadNumbers: List<String> = emptyList()
) : ListAdapter<ThreadItem, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    private var audioPlayer: MediaPlayer? = null
    private var playingAttachmentUri: String? = null
    private var playingLabel: TextView? = null
    private val audioHandler = Handler(Looper.getMainLooper())
    private val audioProgress = object : Runnable {
        override fun run() {
            val player = audioPlayer ?: return
            val label = playingLabel ?: return
            label.text = formatAudioTime(player.currentPosition, player.duration)
            if (player.isPlaying) audioHandler.postDelayed(this, 500L)
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_SENT = 1
        private const val VIEW_TYPE_RECEIVED = 2
        private const val VIEW_TYPE_SENDING = 3
        private const val VIEW_TYPE_FAILED = 4
        private const val MESSAGE_GROUP_GAP_MS = 5 * 60 * 1000L
        private val attachmentPool = RecyclerView.RecycledViewPool()

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
                holder.bind((getItem(position) as ThreadItem.SentMessage).message, position)
            is ReceivedViewHolder ->
                holder.bind((getItem(position) as ThreadItem.ReceivedMessage).message, position)
            is SendingViewHolder ->
                holder.bind((getItem(position) as ThreadItem.SendingMessage).message, position)
            is FailedViewHolder ->
                holder.bind((getItem(position) as ThreadItem.SentMessage).message, position)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        val rv = holder.itemView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_attachments)
        if (rv != null) {
            // Keep the child adapter and shared pool alive for recycled holders.
            rv.scrollToPosition(0)
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        stopAudio()
        super.onDetachedFromRecyclerView(recyclerView)
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
        fun bind(message: Message, position: Int) {
            binding.tvBody.text = message.body
            binding.bubbleContainer.isFocusable = !binding.tvBody.isFocusable
            binding.bubbleContainer.isFocusableInTouchMode = !binding.tvBody.isFocusableInTouchMode
            binding.tvBody.scrollTo(0, 0)
            binding.tvBody.visibility = if (message.body.isBlank()) View.GONE else View.VISIBLE
            binding.tvTime.text = formatTime(message.date)
            binding.tvTime.visibility = if (shouldShowTime(position, message)) View.VISIBLE else View.GONE
            if (message.status == Message.STATUS_COMPLETE) {
                binding.tvStatus.text = binding.root.context.getString(R.string.delivered)
                binding.tvStatus.visibility = View.VISIBLE
            } else {
                binding.tvStatus.visibility = View.GONE
            }
            bindMessageAttachment(
                message = message,
                bubbleContainer = binding.bubbleContainer
            )
            binding.bubbleContainer.setOnLongClickListener {
                onMessageLongClick(message)
                true
            }
        }
    }

    inner class ReceivedViewHolder(
        private val binding: ItemMessageReceivedBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message, position: Int) {
            binding.tvBody.text = message.body
            binding.bubbleContainer.isFocusable = !binding.tvBody.isFocusable
            binding.bubbleContainer.isFocusableInTouchMode = !binding.tvBody.isFocusableInTouchMode
            binding.tvBody.scrollTo(0, 0)
            binding.tvBody.visibility = if (message.body.isBlank()) View.GONE else View.VISIBLE
            binding.tvTime.text = formatTime(message.date)
            binding.tvTime.visibility = if (shouldShowTime(position, message)) View.VISIBLE else View.GONE
            if (message.senderName.isNotBlank()) {
                binding.tvSenderName.text = message.senderName
                binding.tvSenderName.visibility = View.VISIBLE
            } else {
                binding.tvSenderName.visibility = View.GONE
            }
            applyContactBubbleColor(message)
            bindMessageAttachment(
                message = message,
                bubbleContainer = binding.bubbleContainer
            )
            binding.bubbleContainer.setOnLongClickListener {
                onMessageLongClick(message)
                true
            }
        }

        /** Colors the received bubble with the sender's per-contact color (if set). */
        private fun applyContactBubbleColor(message: Message) {
            val context = binding.root.context
            // MMS addresses read from the MMS addr table are sometimes blank
            // (e.g. "insert-address-token") or formatted differently than the
            // contact's number. For a 1:1 thread, fall back to the thread's
            // contact number so the chosen color applies consistently to both
            // text and image bubbles. (Avoided in group threads so each bubble
            // keeps the color of its actual sender.)
            val singleThreadNumber = threadNumbers
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .singleOrNull()

            val color = ContactColors.customColor(message.address)
                ?: if (singleThreadNumber != null) {
                    ContactColors.customColor(singleThreadNumber)
                } else null

            if (color == null) {
                binding.bubbleContainer.setBackgroundResource(R.drawable.bubble_received)
                binding.tvBody.setTextColor(context.getColor(R.color.bubbleReceivedText))
                binding.tvTime.setTextColor(context.getColor(R.color.messageMetaOnReceived))
                binding.tvSenderName.setTextColor(context.getColor(R.color.colorSecondary))
                return
            }

            binding.bubbleContainer.background = ContactColors.receivedBubbleDrawable(color)
            binding.tvBody.setTextColor(ContactColors.textColorOn(color))
            binding.tvTime.setTextColor(ContactColors.metaColorOn(color))
            binding.tvSenderName.setTextColor(color)
        }
    }

    inner class SendingViewHolder(
        private val binding: ItemMessageSendingBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message, position: Int) {
            val bodyText = when {
                message.body.isNotBlank() -> message.body
                message.isMms -> binding.root.context.getString(R.string.attach)
                else -> ""
            }
            binding.tvBody.text = bodyText
            binding.bubbleContainer.isFocusable = !binding.tvBody.isFocusable
            binding.bubbleContainer.isFocusableInTouchMode = !binding.tvBody.isFocusableInTouchMode
            binding.tvBody.scrollTo(0, 0)
            binding.tvBody.visibility = if (bodyText.isBlank()) View.GONE else View.VISIBLE
            if (message.isScheduled) {
                binding.ivScheduled.visibility = View.VISIBLE
                binding.tvState.text = binding.root.context.getString(R.string.sending_later)
            } else {
                binding.ivScheduled.visibility = View.GONE
                binding.tvState.text = binding.root.context.getString(R.string.sending)
            }

            bindMessageAttachment(
                message = message,
                bubbleContainer = binding.bubbleContainer
            )

            binding.bubbleContainer.setOnLongClickListener {
                onMessageLongClick(message)
                true
            }
        }
    }

    private fun openImageViewer(context: android.content.Context, attachment: MmsAttachment) {
        val intent = Intent(context, ImageViewerActivity::class.java)
            .putExtra(ImageViewerActivity.EXTRA_IMAGE_URI, attachment.uri)
            .putExtra(ImageViewerActivity.EXTRA_MIME_TYPE, attachment.mimeType)
            .putExtra(ImageViewerActivity.EXTRA_FILE_NAME, attachment.fileName)
        context.startActivity(intent)
    }

    private fun openAttachment(context: android.content.Context, attachment: MmsAttachment) {
        val uri = MmsAttachmentShare.createUri(context, attachment)
        if (uri == null) {
            Toast.makeText(context, R.string.attachment_open_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val mimeType = attachment.mimeType
        val type = when {
            mimeType.isNotBlank() -> mimeType
            attachment.fileName.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
            attachment.fileName.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
            attachment.fileName.endsWith(".wav", ignoreCase = true) -> "audio/wav"
            else -> "*/*"
        }

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            val messageRes = if (type.startsWith("audio/")) {
                R.string.audio_playback_unavailable
            } else {
                R.string.attachment_open_unavailable
            }
            Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindMessageAttachment(
        message: Message,
        bubbleContainer: View
    ) {
        if (!message.isMms) {
            bubbleContainer.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_attachments).apply {
                visibility = View.GONE
            }
            return
        }

        val attachments = MmsAttachmentJson.decode(message.attachmentsJson)
        if (attachments.isEmpty()) {
            bubbleContainer.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_attachments).apply {
                visibility = View.GONE
            }
            return
        }

        val context = bubbleContainer.context
        val rvAttachments = bubbleContainer.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_attachments)
        if (rvAttachments.layoutManager == null) {
            rvAttachments.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                context,
                androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
                false
            )
            rvAttachments.setHasFixedSize(true)
            rvAttachments.itemAnimator = null
            rvAttachments.setRecycledViewPool(attachmentPool)
        }
        val attachmentAdapter = rvAttachments.adapter as? AttachmentAdapter
        if (attachmentAdapter == null) {
            rvAttachments.adapter = AttachmentAdapter(attachments, context)
        } else {
            attachmentAdapter.submitAttachments(attachments)
        }
        rvAttachments.visibility = View.VISIBLE
    }

    private inner class AttachmentAdapter(
        private var attachments: List<MmsAttachment>,
        private val context: Context
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<AttachmentViewHolder>() {

        init {
            setHasStableIds(true)
        }

        fun submitAttachments(newAttachments: List<MmsAttachment>) {
            attachments = newAttachments
            notifyDataSetChanged()
        }

        override fun getItemId(position: Int): Long = attachments[position].uri.hashCode().toLong()

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): AttachmentViewHolder {
            val density = parent.context.resources.displayMetrics.density
            val previewDp = when (Prefs.get().attachmentPreviewSize) {
                Prefs.ATTACHMENT_PREVIEW_SMALL -> 80f
                Prefs.ATTACHMENT_PREVIEW_LARGE -> 152f
                else -> 112f
            }
            val root = android.widget.LinearLayout(parent.context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = android.view.ViewGroup.LayoutParams(
                    (density * previewDp).toInt(), (density * previewDp).toInt()
                )
                setPadding(2, 2, 2, 2)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                background = parent.context.getDrawable(R.drawable.item_focusable_bg)
            }
            val iv = android.widget.ImageView(parent.context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (density * (previewDp - 8f)).toInt(),
                    (density * (previewDp - 20f)).toInt()
                )
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            }
            val label = TextView(parent.context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setTextColor(parent.context.getColor(R.color.colorOnSurface))
                textSize = 9f
                gravity = android.view.Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            root.addView(iv)
            root.addView(label)
            return AttachmentViewHolder(root, iv, label)
        }

        override fun onBindViewHolder(holder: AttachmentViewHolder, position: Int) {
            val attachment = attachments[position]
            holder.itemView.contentDescription = attachmentDescription(attachment, position)
            holder.label.text = attachment.fileName.ifBlank { attachment.mimeType.ifBlank { "Attachment" } }
            holder.itemView.setOnLongClickListener {
                onAttachmentAction(attachment)
                true
            }
            holder.icon.apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    Glide.with(context).clear(this)
                    if (attachment.mimeType.startsWith("image/") || attachment.mimeType.isBlank()) {
                        Glide.with(context).load(attachment.contentUri)
                        .override(holder.icon.layoutParams.width, holder.icon.layoutParams.height)
                        .into(this)
                    holder.label.visibility = View.GONE
                } else {
                    setImageResource(
                        if (attachment.mimeType.startsWith("audio/")) {
                            R.drawable.ic_mic
                        } else {
                            R.drawable.ic_attach
                        }
                    )
                    scaleType = android.widget.ImageView.ScaleType.CENTER
                    holder.label.visibility = View.VISIBLE
                }
            }
            holder.itemView.setOnClickListener {
                when {
                    attachment.mimeType.startsWith("image/") || attachment.mimeType.isBlank() ->
                        openImageViewer(context, attachment)
                    attachment.mimeType.startsWith("audio/") ->
                        toggleAudio(attachment, holder)
                    else -> openAttachment(context, attachment)
                }
            }
        }

        override fun getItemCount() = attachments.size

    }

    private class AttachmentViewHolder(
        view: View,
        val icon: ImageView,
        val label: TextView
    ) : RecyclerView.ViewHolder(view)

    private fun shouldShowTime(position: Int, message: Message): Boolean {
        val previous = messageAt(position - 1)
        val next = messageAt(position + 1)
        return previous == null || next == null ||
            !sameMessageGroup(previous, message) || !sameMessageGroup(message, next)
    }

    private fun messageAt(position: Int): Message? {
        if (position !in 0 until itemCount) return null
        return when (val item = getItem(position)) {
            is ThreadItem.SentMessage -> item.message
            is ThreadItem.ReceivedMessage -> item.message
            is ThreadItem.SendingMessage -> item.message
            else -> null
        }
    }

    private fun sameMessageGroup(first: Message, second: Message): Boolean {
        if (first.isIncoming != second.isIncoming) return false
        if (first.isIncoming) {
            val firstSender = first.senderName.ifBlank { first.address }
            val secondSender = second.senderName.ifBlank { second.address }
            if (firstSender != secondSender) return false
        }
        return kotlin.math.abs(first.date - second.date) <= MESSAGE_GROUP_GAP_MS
    }

    private fun toggleAudio(attachment: MmsAttachment, holder: AttachmentViewHolder) {
        if (playingAttachmentUri == attachment.uri && audioPlayer?.isPlaying == true) {
            audioPlayer?.pause()
            audioHandler.removeCallbacks(audioProgress)
            holder.label.text = attachment.fileName.ifBlank { "Audio" }
            return
        }

        stopAudio()
        val player = runCatching {
            MediaPlayer().apply {
                setDataSource(holder.itemView.context, attachment.contentUri)
                setOnPreparedListener {
                    start()
                    audioHandler.post(audioProgress)
                }
                setOnCompletionListener {
                    holder.label.text = attachment.fileName.ifBlank { "Audio" }
                    stopAudio()
                }
                setOnErrorListener { _, _, _ ->
                    holder.label.text = attachment.fileName.ifBlank { "Audio" }
                    stopAudio()
                    Toast.makeText(
                        holder.itemView.context,
                        R.string.audio_playback_unavailable,
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
                prepareAsync()
            }
        }.getOrNull()
        if (player == null) {
            Toast.makeText(holder.itemView.context, R.string.audio_playback_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        audioPlayer = player
        playingAttachmentUri = attachment.uri
        playingLabel = holder.label
    }

    private fun stopAudio() {
        audioHandler.removeCallbacks(audioProgress)
        audioPlayer?.release()
        audioPlayer = null
        playingAttachmentUri = null
        playingLabel = null
    }

    private fun formatAudioTime(positionMs: Int, durationMs: Int): String {
        fun format(ms: Int): String {
            val seconds = (ms / 1000).coerceAtLeast(0)
            return "%d:%02d".format(seconds / 60, seconds % 60)
        }
        return "${format(positionMs)} / ${format(durationMs)}"
    }

    private fun attachmentDescription(attachment: MmsAttachment, position: Int): String {
        val kind = when {
            attachment.mimeType.startsWith("image/") -> "Image"
            attachment.mimeType.startsWith("audio/") -> "Audio"
            attachment.mimeType.startsWith("video/") -> "Video"
            else -> "File"
        }
        return "$kind attachment, ${position + 1}"
    }

    inner class FailedViewHolder(
        private val binding: ItemMessageFailedBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message, @Suppress("UNUSED_PARAMETER") position: Int) {
            binding.tvBody.text = message.body
            binding.bubbleContainer.isFocusable = !binding.tvBody.isFocusable
            binding.bubbleContainer.isFocusableInTouchMode = !binding.tvBody.isFocusableInTouchMode
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
