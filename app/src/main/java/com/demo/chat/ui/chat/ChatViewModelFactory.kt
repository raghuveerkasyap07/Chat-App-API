package com.demo.chat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.demo.chat.sdk.core.ChatClient

class ChatViewModelFactory(
    private val chatClient: ChatClient,
    private val chatId: Int
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            return ChatViewModel(chatClient, chatId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
