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
    private val currentUserId: Int
) : ListAdapter<ChatMessage, MessageAdapter.MessageViewHolder>(MessageDiffCallback) {

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
                    binding.tvSenderName.text = message.sender.name
                    binding.tvSenderName.visibility = View.VISIBLE
                } else {
                    binding.tvSenderName.visibility = View.GONE
                }
            }
            binding.bubbleLayout.layoutParams = params

            // Bind text
            if (message.message.isNotBlank()) {
                binding.tvMessageBody.text = message.message
                binding.tvMessageBody.visibility = View.VISIBLE
            } else {
                binding.tvMessageBody.visibility = View.GONE
            }

            // Bind photo attachment if TYPE_IMAGE or mediaUrl present
            if (message.isImage && !message.mediaUrl.isNullOrBlank()) {
                binding.ivAttachment.visibility = View.VISIBLE
                binding.ivAttachment.load(message.mediaUrl) {
                    crossfade(true)
                    placeholder(android.R.drawable.ic_menu_gallery)
                    error(android.R.drawable.ic_dialog_alert)
                }
            } else {
                binding.ivAttachment.visibility = View.GONE
            }

            // Timestamp
            binding.tvTimestamp.text = message.createdAt ?: ""
        }
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
