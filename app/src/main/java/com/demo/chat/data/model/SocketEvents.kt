package com.demo.chat.data.model

data class ReceiptEvent(
    val chatId: String,
    val messageId: Int?,
    val status: String // "delivered" or "seen"
)

data class TypingEvent(
    val chatId: String,
    val name: String,
    val isTyping: Boolean
)

data class PresenceEvent(
    val userId: Int,
    val isOnline: Boolean
)
