package com.demo.chat.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.demo.chat.R
import com.demo.chat.data.model.ChatMessage
import com.demo.chat.databinding.ItemChatMessageBinding

class MessageAdapter(
    private val currentUserId: Int,
    var onVideoClick: ((ChatMessage) -> Unit)? = null,
    var onAudioPlayPauseClick: ((ChatMessage) -> Unit)? = null,
    var onFileClick: ((ChatMessage) -> Unit)? = null
) : ListAdapter<ChatMessage, MessageAdapter.MessageViewHolder>(MessageDiffCallback) {

    private var currentlyPlayingUrl: String? = null
    private var isAudioPlaying: Boolean = false
    private var currentAudioProgress: Int = 0
    private var currentAudioDurationFormatted: String = "00:00"

    fun updateAudioState(
        url: String?,
        isPlaying: Boolean,
        progressPercent: Int,
        durationFormatted: String
    ) {
        val oldUrl = currentlyPlayingUrl
        currentlyPlayingUrl = url
        isAudioPlaying = isPlaying
        currentAudioProgress = progressPercent
        currentAudioDurationFormatted = durationFormatted

        val current = currentList
        current.forEachIndexed { index, msg ->
            val effectiveUrl = msg.getEffectiveMediaUrl()
            if (effectiveUrl != null && (effectiveUrl == url || effectiveUrl == oldUrl)) {
                notifyItemChanged(index)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemChatMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MessageViewHolder(
        private val binding: ItemChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessage) {
            val isOutgoing = message.isSentBy(currentUserId)
            val context = binding.root.context

            // Position bubble
            val params = binding.bubbleLayout.layoutParams as LinearLayout.LayoutParams
            if (isOutgoing) {
                params.gravity = Gravity.END
                binding.bubbleLayout.setBackgroundColor(ContextCompat.getColor(context, R.color.bubble_outgoing))
                binding.tvSenderName.visibility = View.GONE
            } else {
                params.gravity = Gravity.START
                binding.bubbleLayout.setBackgroundColor(ContextCompat.getColor(context, R.color.bubble_incoming))
                if (message.sender != null) {
                    binding.tvSenderName.text = message.sender.displayName
                    binding.tvSenderName.visibility = View.VISIBLE
                } else {
                    binding.tvSenderName.visibility = View.GONE
                }
            }
            binding.bubbleLayout.layoutParams = params

            val effectiveMediaUrl = message.getEffectiveMediaUrl()

            // 1. Image Attachment
            if (message.isImage && !effectiveMediaUrl.isNullOrBlank()) {
                binding.ivAttachment.visibility = View.VISIBLE
                binding.ivAttachment.load(effectiveMediaUrl) {
                    crossfade(true)
                    placeholder(android.R.drawable.ic_menu_gallery)
                    error(android.R.drawable.ic_dialog_alert)
                }
            } else {
                binding.ivAttachment.visibility = View.GONE
            }

            // 2. Video Attachment
            if (message.isVideo && !effectiveMediaUrl.isNullOrBlank()) {
                binding.videoContainer.visibility = View.VISIBLE
                binding.ivVideoThumbnail.load(effectiveMediaUrl) {
                    crossfade(true)
                    placeholder(android.R.drawable.ic_media_play)
                    error(android.R.drawable.ic_media_play)
                }
                binding.videoContainer.setOnClickListener {
                    onVideoClick?.invoke(message)
                }
            } else {
                binding.videoContainer.visibility = View.GONE
            }

            // 3. Audio Attachment
            if (message.isAudio && !effectiveMediaUrl.isNullOrBlank()) {
                binding.audioContainer.visibility = View.VISIBLE
                val isThisPlaying = (effectiveMediaUrl == currentlyPlayingUrl) && isAudioPlaying
                binding.btnAudioPlayPause.setImageResource(if (isThisPlaying) R.drawable.ic_pause else R.drawable.ic_play)

                if (effectiveMediaUrl == currentlyPlayingUrl) {
                    binding.audioProgressBar.progress = currentAudioProgress
                    binding.tvAudioDuration.text = currentAudioDurationFormatted
                } else {
                    binding.audioProgressBar.progress = 0
                    binding.tvAudioDuration.text = "Audio"
                }

                binding.btnAudioPlayPause.setOnClickListener {
                    onAudioPlayPauseClick?.invoke(message)
                }
            } else {
                binding.audioContainer.visibility = View.GONE
            }

            // 4. File / Document Attachment
            if (message.isFile && !effectiveMediaUrl.isNullOrBlank()) {
                binding.fileContainer.visibility = View.VISIBLE
                val fileName = message.metadata?.fileName
                    ?: message.message?.takeIf { it.isNotBlank() && !it.startsWith("[") }
                    ?: effectiveMediaUrl.substringAfterLast('/').substringBefore('?')
                binding.tvFileName.text = fileName

                val sizeBytes = message.metadata?.fileSize
                val sizeText = if (sizeBytes != null && sizeBytes > 0) {
                    formatFileSize(sizeBytes)
                } else {
                    "Tap to open"
                }
                binding.tvFileSize.text = sizeText

                binding.fileContainer.setOnClickListener {
                    onFileClick?.invoke(message)
                }
            } else {
                binding.fileContainer.visibility = View.GONE
            }

            // 5. Text message / Caption
            val textContent = message.message ?: ""
            val isGenericPlaceholder = textContent.equals("[Photo]", ignoreCase = true) ||
                    textContent.equals("[Video]", ignoreCase = true) ||
                    textContent.equals("[Audio]", ignoreCase = true) ||
                    textContent.equals("[Attachment]", ignoreCase = true)

            if (textContent.isNotBlank() && (!isGenericPlaceholder || (!message.isImage && !message.isVideo && !message.isAudio && !message.isFile))) {
                binding.tvMessageBody.text = textContent
                binding.tvMessageBody.visibility = View.VISIBLE
            } else {
                binding.tvMessageBody.visibility = View.GONE
            }

            // Timestamp & Status Ticks
            binding.tvTimestamp.text = message.createdAt ?: ""

            if (isOutgoing) {
                binding.tvStatusTicks.visibility = View.VISIBLE
                when {
                    message.isSeen -> {
                        binding.tvStatusTicks.text = "✓✓"
                        binding.tvStatusTicks.setTextColor(android.graphics.Color.parseColor("#34B7F1"))
                    }
                    message.isDelivered -> {
                        binding.tvStatusTicks.text = "✓✓"
                        binding.tvStatusTicks.setTextColor(android.graphics.Color.parseColor("#888888"))
                    }
                    else -> {
                        binding.tvStatusTicks.text = "✓"
                        binding.tvStatusTicks.setTextColor(android.graphics.Color.parseColor("#888888"))
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
