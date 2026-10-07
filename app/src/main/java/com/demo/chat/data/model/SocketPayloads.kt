package com.demo.chat.data.model

import com.google.gson.annotations.SerializedName

object SocketEvents {
    const val JOIN_CHAT = "join_chat"
    const val LEAVE_CHAT = "leave_chat"
    const val SEND_MESSAGE = "send_message"
    const val SEND_IMAGE = "send_image"
    const val NEW_MESSAGE = "new_message"
    const val MESSAGE = "message"
    const val TYPING = "typing"
    const val STOP_TYPING = "stop_typing"
    const val MESSAGE_SEEN = "message_seen"
    const val MESSAGE_DELIVERED = "message_delivered"
    const val USER_TYPING = "user_typing"
    const val USER_STOP_TYPING = "user_stop_typing"
    const val USER_ONLINE = "user_online"
    const val USER_OFFLINE = "user_offline"
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
