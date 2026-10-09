package com.demo.chat.ui

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.demo.chat.R
import com.demo.chat.data.model.ChatMessage
import com.demo.chat.databinding.ItemChatMessageBinding
import com.demo.chat.databinding.ItemChatReceivedBinding
import com.demo.chat.databinding.ItemChatReceivedImageBinding
import com.demo.chat.databinding.ItemChatSentBinding
import com.demo.chat.databinding.ItemChatSentImageBinding

class MessageAdapter(
    private val currentUserId: Int,
    private val onImageClick: ((String) -> Unit)? = null,
    private val onVideoClick: ((ChatMessage) -> Unit)? = null,
    private val onAudioPlayPauseClick: ((ChatMessage) -> Unit)? = null,
    private val onFileClick: ((ChatMessage) -> Unit)? = null
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(MessageDiffCallback) {

    private var activeAudioUrl: String? = null
    private var isAudioPlaying: Boolean = false
    private var audioProgressPercent: Int = 0
    private var audioDurationFormatted: String = "00:00"

    fun updateAudioState(url: String?, isPlaying: Boolean, progressPercent: Int, durationFormatted: String) {
        this.activeAudioUrl = url
        this.isAudioPlaying = isPlaying
        this.audioProgressPercent = progressPercent
        this.audioDurationFormatted = durationFormatted
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        val msg = getItem(position)
        val isOutgoing = msg.isSentBy(currentUserId)
        val effectiveUrl = msg.getEffectiveMediaUrl()

        if (msg.isVideo || msg.isAudio || msg.isFile) {
            return TYPE_GENERIC_MEDIA
        }

        val isImage = msg.isImage || (!effectiveUrl.isNullOrBlank() && !msg.isVideo && !msg.isAudio && !msg.isFile)
        return when {
            isOutgoing && isImage -> TYPE_SENT_IMAGE
            isOutgoing -> TYPE_SENT_TEXT
            isImage -> TYPE_RECEIVED_IMAGE
            else -> TYPE_RECEIVED_TEXT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SENT_IMAGE -> SentImageViewHolder(ItemChatSentImageBinding.inflate(inflater, parent, false))
            TYPE_SENT_TEXT -> SentTextViewHolder(ItemChatSentBinding.inflate(inflater, parent, false))
            TYPE_RECEIVED_IMAGE -> ReceivedImageViewHolder(ItemChatReceivedImageBinding.inflate(inflater, parent, false))
            TYPE_RECEIVED_TEXT -> ReceivedTextViewHolder(ItemChatReceivedBinding.inflate(inflater, parent, false))
            else -> GenericMediaViewHolder(ItemChatMessageBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is SentImageViewHolder -> holder.bind(message, onImageClick)
            is SentTextViewHolder -> holder.bind(message, currentUserId)
            is ReceivedImageViewHolder -> holder.bind(message, onImageClick)
            is ReceivedTextViewHolder -> holder.bind(message)
            is GenericMediaViewHolder -> holder.bind(
                message = message,
                currentUserId = currentUserId,
                onImageClick = onImageClick,
                onVideoClick = onVideoClick,
                onAudioPlayPauseClick = onAudioPlayPauseClick,
                onFileClick = onFileClick,
                activeAudioUrl = activeAudioUrl,
                isAudioPlaying = isAudioPlaying,
                audioProgressPercent = audioProgressPercent,
                audioDurationFormatted = audioDurationFormatted
            )
        }
    }

    class SentTextViewHolder(
        private val binding: ItemChatSentBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage, currentUserId: Int) {
            binding.tvMessageBody.text = message.message ?: ""
            binding.tvTimestamp.text = formatTimestamp(message.createdAt)

            if (message.isSentBy(currentUserId)) {
                binding.tvStatusTicks.visibility = View.VISIBLE
                when {
                    message.isSeen -> {
                        binding.tvStatusTicks.text = "✓✓"
                        binding.tvStatusTicks.setTextColor(Color.parseColor("#34B7F1"))
                    }
                    message.isDelivered -> {
                        binding.tvStatusTicks.text = "✓✓"
                        binding.tvStatusTicks.setTextColor(Color.parseColor("#888888"))
                    }
                    else -> {
                        binding.tvStatusTicks.text = "✓"
                        binding.tvStatusTicks.setTextColor(Color.parseColor("#888888"))
                    }
                }
            } else {
                binding.tvStatusTicks.visibility = View.GONE
            }
        }
    }

    class SentImageViewHolder(
        private val binding: ItemChatSentImageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage, onImageClick: ((String) -> Unit)?) {
            binding.tvTimestamp.text = formatTimestamp(message.createdAt)
            val imageUrl = message.getEffectiveMediaUrl()

            if (!imageUrl.isNullOrBlank()) {
                binding.ivAttachment.visibility = View.VISIBLE
                binding.ivAttachment.load(imageUrl) {
                    crossfade(true)
                    placeholder(android.R.drawable.ic_menu_gallery)
                    error(android.R.drawable.ic_dialog_alert)
                }
                binding.ivAttachment.setOnClickListener {
                    onImageClick?.invoke(imageUrl)
                }
            } else {
                binding.ivAttachment.visibility = View.GONE
            }

            binding.tvStatusTicks.visibility = View.VISIBLE
            when {
                message.isSeen -> {
                    binding.tvStatusTicks.text = "✓✓"
                    binding.tvStatusTicks.setTextColor(Color.parseColor("#34B7F1"))
                }
                message.isDelivered -> {
                    binding.tvStatusTicks.text = "✓✓"
                    binding.tvStatusTicks.setTextColor(Color.parseColor("#888888"))
                }
                else -> {
                    binding.tvStatusTicks.text = "✓"
                    binding.tvStatusTicks.setTextColor(Color.parseColor("#888888"))
                }
            }
        }
    }

    class ReceivedTextViewHolder(
        private val binding: ItemChatReceivedBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.tvMessageBody.text = message.message ?: ""
            binding.tvTimestamp.text = formatTimestamp(message.createdAt)

            if (message.sender != null) {
                binding.tvSenderName.text = message.sender.displayName
                binding.tvSenderName.visibility = View.VISIBLE
            } else {
                binding.tvSenderName.visibility = View.GONE
            }
        }
    }

    class ReceivedImageViewHolder(
        private val binding: ItemChatReceivedImageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage, onImageClick: ((String) -> Unit)?) {
            binding.tvTimestamp.text = formatTimestamp(message.createdAt)

            if (message.sender != null) {
                binding.tvSenderName.text = message.sender.displayName
                binding.tvSenderName.visibility = View.VISIBLE
            } else {
                binding.tvSenderName.visibility = View.GONE
            }

            val imageUrl = message.getEffectiveMediaUrl()
            if (!imageUrl.isNullOrBlank()) {
                binding.ivAttachment.visibility = View.VISIBLE
                binding.ivAttachment.load(imageUrl) {
                    crossfade(true)
                    placeholder(android.R.drawable.ic_menu_gallery)
                    error(android.R.drawable.ic_dialog_alert)
                }
                binding.ivAttachment.setOnClickListener {
                    onImageClick?.invoke(imageUrl)
                }
            } else {
                binding.ivAttachment.visibility = View.GONE
            }
        }
    }

    class GenericMediaViewHolder(
        private val binding: ItemChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            message: ChatMessage,
            currentUserId: Int,
            onImageClick: ((String) -> Unit)?,
            onVideoClick: ((ChatMessage) -> Unit)?,
            onAudioPlayPauseClick: ((ChatMessage) -> Unit)?,
            onFileClick: ((ChatMessage) -> Unit)?,
            activeAudioUrl: String?,
            isAudioPlaying: Boolean,
            audioProgressPercent: Int,
            audioDurationFormatted: String
        ) {
            val isOutgoing = message.isSentBy(currentUserId)

            binding.messageContainer.gravity = if (isOutgoing) Gravity.END else Gravity.START
            val bgRes = if (isOutgoing) R.color.bubble_outgoing else R.color.bubble_incoming
            binding.bubbleLayout.setBackgroundResource(bgRes)

            if (!isOutgoing && message.sender != null) {
                binding.tvSenderName.text = message.sender.displayName
                binding.tvSenderName.visibility = View.VISIBLE
            } else {
                binding.tvSenderName.visibility = View.GONE
            }

            val mediaUrl = message.getEffectiveMediaUrl()

            // Video Message
            if (message.isVideo) {
                binding.videoContainer.visibility = View.VISIBLE
                binding.ivAttachment.visibility = View.GONE
                binding.audioContainer.visibility = View.GONE
                binding.fileContainer.visibility = View.GONE

                if (!mediaUrl.isNullOrBlank()) {
                    binding.ivVideoThumbnail.load(mediaUrl) {
                        crossfade(true)
                        placeholder(android.R.drawable.ic_menu_gallery)
                        error(android.R.drawable.ic_dialog_alert)
                    }
                }
                binding.videoContainer.setOnClickListener {
                    onVideoClick?.invoke(message)
                }
            }
            // Audio Message
            else if (message.isAudio) {
                binding.audioContainer.visibility = View.VISIBLE
                binding.ivAttachment.visibility = View.GONE
                binding.videoContainer.visibility = View.GONE
                binding.fileContainer.visibility = View.GONE

                val isThisAudioActive = (activeAudioUrl != null && activeAudioUrl == mediaUrl)
                if (isThisAudioActive) {
                    binding.btnAudioPlayPause.setImageResource(
                        if (isAudioPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    binding.audioProgressBar.progress = audioProgressPercent
                    binding.tvAudioDuration.text = audioDurationFormatted
                } else {
                    binding.btnAudioPlayPause.setImageResource(R.drawable.ic_play)
                    binding.audioProgressBar.progress = 0
                    binding.tvAudioDuration.text = "00:00"
                }

                binding.btnAudioPlayPause.setOnClickListener {
                    onAudioPlayPauseClick?.invoke(message)
                }
            }
            // File Message
            else if (message.isFile) {
                binding.fileContainer.visibility = View.VISIBLE
                binding.ivAttachment.visibility = View.GONE
                binding.videoContainer.visibility = View.GONE
                binding.audioContainer.visibility = View.GONE

                val fileName = message.metadata?.fileName ?: mediaUrl?.substringAfterLast('/') ?: "Attachment File"
                val fileSize = message.metadata?.fileSize
                binding.tvFileName.text = fileName
                binding.tvFileSize.text = if (fileSize != null && fileSize > 0) formatFileSize(fileSize) else "Tap to open file"

                binding.fileContainer.setOnClickListener {
                    onFileClick?.invoke(message)
                }
            }
            // Photo Image Message
            else if (message.isImage || !mediaUrl.isNullOrBlank()) {
                binding.ivAttachment.visibility = View.VISIBLE
                binding.videoContainer.visibility = View.GONE
                binding.audioContainer.visibility = View.GONE
                binding.fileContainer.visibility = View.GONE

                binding.ivAttachment.load(mediaUrl) {
                    crossfade(true)
                    placeholder(android.R.drawable.ic_menu_gallery)
                    error(android.R.drawable.ic_dialog_alert)
                }
                binding.ivAttachment.setOnClickListener {
                    mediaUrl?.let { url -> onImageClick?.invoke(url) }
                }
            } else {
                binding.ivAttachment.visibility = View.GONE
                binding.videoContainer.visibility = View.GONE
                binding.audioContainer.visibility = View.GONE
                binding.fileContainer.visibility = View.GONE
            }

            if (!message.message.isNullOrBlank()) {
                binding.tvMessageBody.text = message.message
                binding.tvMessageBody.visibility = View.VISIBLE
            } else {
                binding.tvMessageBody.visibility = View.GONE
            }

            binding.tvTimestamp.text = formatTimestamp(message.createdAt)

            if (isOutgoing) {
                binding.tvStatusTicks.visibility = View.VISIBLE
                when {
                    message.isSeen -> {
                        binding.tvStatusTicks.text = "✓✓"
                        binding.tvStatusTicks.setTextColor(Color.parseColor("#34B7F1"))
                    }
                    message.isDelivered -> {
                        binding.tvStatusTicks.text = "✓✓"
                        binding.tvStatusTicks.setTextColor(Color.parseColor("#888888"))
                    }
                    else -> {
                        binding.tvStatusTicks.text = "✓"
                        binding.tvStatusTicks.setTextColor(Color.parseColor("#888888"))
                    }
                }
            } else {
                binding.tvStatusTicks.visibility = View.GONE
            }
        }

        private fun formatFileSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            return String.format(java.util.Locale.US, "%.1f MB", mb)
        }
    }

    fun appendMessageIfNotExists(message: ChatMessage) {
        val current = currentList.toMutableList()
        val existingIndex = current.indexOfFirst {
            (message.id != null && it.id == message.id) ||
            (it.id == null && it.message == message.message && it.senderId == message.senderId)
        }
        if (existingIndex != -1) {
            current[existingIndex] = message
        } else {
            current.add(message)
        }
        submitList(current)
    }

    fun updateStatus(messageId: Int, status: String) {
        val current = currentList.toMutableList()
        val index = current.indexOfFirst { it.id == messageId }
        if (index != -1) {
            current[index] = current[index].copy(status = status)
            submitList(current)
        }
    }

    fun updateAllSentStatus(status: String) {
        val current = currentList.map { msg ->
            if (msg.isSentBy(currentUserId) && !msg.isSeen) {
                msg.copy(status = status)
            } else {
                msg
            }
        }
        submitList(current)
    }

    companion object MessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        const val TYPE_SENT_TEXT = 1
        const val TYPE_SENT_IMAGE = 2
        const val TYPE_RECEIVED_TEXT = 3
        const val TYPE_RECEIVED_IMAGE = 4
        const val TYPE_GENERIC_MEDIA = 5

        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return if (oldItem.id != null && newItem.id != null) {
                oldItem.id == newItem.id
            } else {
                oldItem.createdAt == newItem.createdAt && oldItem.message == newItem.message
            }
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}

private fun formatTimestamp(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return try {
        val clean = raw.replace("Z", "")
        if (clean.contains("T")) {
            val parts = clean.split("T")
            if (parts.size == 2) {
                val timePart = parts[1].substringBefore(".") // e.g. "10:58:35"
                val tParts = timePart.split(":")
                if (tParts.size >= 2) {
                    val h = tParts[0].toIntOrNull() ?: 0
                    val m = tParts[1]
                    val hour12 = when {
                        h == 0 -> 12
                        h > 12 -> h - 12
                        else -> h
                    }
                    val amPm = if (h >= 12) "PM" else "AM"
                    return String.format(java.util.Locale.getDefault(), "%d:%s %s", hour12, m, amPm)
                }
            }
        }
        raw
    } catch (_: Throwable) {
        raw
    }
}
