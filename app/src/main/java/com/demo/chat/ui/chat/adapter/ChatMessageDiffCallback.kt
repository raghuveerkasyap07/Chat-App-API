package com.demo.chat.ui.chat.adapter

import androidx.recyclerview.widget.DiffUtil
import com.demo.chat.data.model.ChatMessage

object ChatMessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
    override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
        return if (oldItem.id != null && newItem.id != null) {
            oldItem.id == newItem.id
        } else {
            oldItem.createdAt == newItem.createdAt && oldItem.message == newItem.message && oldItem.senderId == newItem.senderId
        }
    }

    override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
        return oldItem == newItem
    }
}
