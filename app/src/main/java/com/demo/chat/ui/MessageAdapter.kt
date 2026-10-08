package com.demo.chat.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.demo.chat.data.model.ChatMessage
import com.demo.chat.databinding.ItemChatReceivedBinding
import com.demo.chat.databinding.ItemChatReceivedImageBinding
import com.demo.chat.databinding.ItemChatSentBinding
import com.demo.chat.databinding.ItemChatSentImageBinding

class MessageAdapter(
    private val currentUserId: Int,
    private val onImageClick: (String) -> Unit
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(MessageDiffCallback) {

    override fun getItemViewType(position: Int): Int {
        val msg = getItem(position)
        val isOutgoing = msg.isSentBy(currentUserId)
        val effectiveUrl = msg.getEffectiveMediaUrl()
        val isImage = msg.isImage || !effectiveUrl.isNullOrBlank()

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
            else -> ReceivedTextViewHolder(ItemChatReceivedBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is SentImageViewHolder -> holder.bind(message, onImageClick)
            is SentTextViewHolder -> holder.bind(message, currentUserId)
            is ReceivedImageViewHolder -> holder.bind(message, onImageClick)
            is ReceivedTextViewHolder -> holder.bind(message)
        }
    }

    class SentTextViewHolder(
        private val binding: ItemChatSentBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage, currentUserId: Int) {
            binding.tvMessageBody.text = message.message ?: ""
            binding.tvTimestamp.text = message.createdAt ?: ""

            if (message.isSentBy(currentUserId)) {
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

    class SentImageViewHolder(
        private val binding: ItemChatSentImageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage, onImageClick: (String) -> Unit) {
            binding.tvTimestamp.text = message.createdAt ?: ""
            val imageUrl = message.getEffectiveMediaUrl()

            if (!imageUrl.isNullOrBlank()) {
                binding.ivAttachment.visibility = View.VISIBLE
                binding.ivAttachment.load(imageUrl) {
                    crossfade(true)
                    placeholder(android.R.drawable.ic_menu_gallery)
                    error(android.R.drawable.ic_dialog_alert)
                }
                binding.ivAttachment.setOnClickListener {
                    onImageClick(imageUrl)
                }
            } else {
                binding.ivAttachment.visibility = View.GONE
            }

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
        }
    }

    class ReceivedTextViewHolder(
        private val binding: ItemChatReceivedBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.tvMessageBody.text = message.message ?: ""
            binding.tvTimestamp.text = message.createdAt ?: ""

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
        fun bind(message: ChatMessage, onImageClick: (String) -> Unit) {
            binding.tvTimestamp.text = message.createdAt ?: ""

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
                    onImageClick(imageUrl)
                }
            } else {
                binding.ivAttachment.visibility = View.GONE
            }
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
