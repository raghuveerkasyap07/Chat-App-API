package com.demo.chat.data.model

import com.google.gson.annotations.SerializedName

object SocketEvents {
    const val JOIN_CHAT = "join_chat"
    const val SEND_MESSAGE = "send_message"
    const val SEND_IMAGE = "send_image"
    const val NEW_MESSAGE = "new_message"
    const val MESSAGE = "message"
    const val PING = "ping"
    const val PONG = "pong"
    const val PING_PONG = "ping_pong"
}

data class SocketFrame(
    val event: String,
    @SerializedName("chat_id", alternate = ["chatId"])
    val chatId: Int? = null,
    val type: String? = null,
    val message: String? = null,
    @SerializedName("image_url", alternate = ["imageUrl", "mediaUrl", "media_url", "url"])
    val imageUrl: String? = null,
    val metadata: MediaMetadata? = null,
    @SerializedName("sender_id", alternate = ["senderId", "userId"])
    val senderId: Int? = null,
    val timestamp: Long? = null,
    val data: Any? = null
)
