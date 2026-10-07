package com.demo.chat.ui.threads

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.demo.chat.data.model.Chat
import com.demo.chat.databinding.ItemThreadBinding

class ThreadAdapter(
    private val currentUserId: Int,
    private val onThreadClick: (Chat, String) -> Unit
) : ListAdapter<Chat, ThreadAdapter.ThreadViewHolder>(ThreadDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThreadViewHolder {
        val binding = ItemThreadBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ThreadViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ThreadViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ThreadViewHolder(
        private val binding: ItemThreadBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(chat: Chat) {
            val partner = chat.getDisplayPartner(currentUserId)
            val partnerName = partner?.name ?: "Chat #${chat.id}"
            binding.tvPartnerName.text = partnerName

            val initials = if (partnerName.isNotBlank()) {
                partnerName.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("").uppercase()
            } else {
                "C"
            }
            binding.tvInitials.text = initials

            val lastMsg = chat.lastMessage
            if (lastMsg != null) {
                val previewText = if (lastMsg.isImage || !lastMsg.mediaUrl.isNullOrBlank()) {
                    "📷 Photo"
                } else {
                    lastMsg.message
                }
                binding.tvLastMessage.text = previewText
                binding.tvTimestamp.text = lastMsg.createdAt ?: ""
            } else {
                binding.tvLastMessage.text = "No messages yet"
                binding.tvTimestamp.text = chat.updatedAt ?: ""
            }

            if (chat.unreadCount > 0) {
                binding.tvUnreadBadge.text = chat.unreadCount.toString()
                binding.tvUnreadBadge.visibility = View.VISIBLE
            } else {
                binding.tvUnreadBadge.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                onThreadClick(chat, partnerName)
            }
        }
    }

    private class ThreadDiffCallback : DiffUtil.ItemCallback<Chat>() {
        override fun areItemsTheSame(oldItem: Chat, newItem: Chat): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Chat, newItem: Chat): Boolean = oldItem == newItem
    }
}
