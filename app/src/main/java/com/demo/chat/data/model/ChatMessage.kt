package com.demo.chat.data.model

import com.google.gson.annotations.SerializedName

data class ChatMessage(
    val id: Int? = null,
    @SerializedName("chat_id", alternate = ["chatId"])
    val chatId: Int,
    @SerializedName("sender_id", alternate = ["senderId", "userId", "user_id", "from_id"])
    val senderId: Int,
    val message: String = "",
    val type: String? = TYPE_TEXT,
    @SerializedName("media_url", alternate = ["mediaUrl", "imageUrl", "image_url", "url"])
    val mediaUrl: String? = null,
    val metadata: MediaMetadata? = null,
    @SerializedName("created_at", alternate = ["createdAt", "timestamp", "time"])
    val createdAt: String? = null,
    val sender: User? = null,
    val status: String? = STATUS_SENT
) {
    companion object {
        const val TYPE_TEXT = "TYPE_TEXT"
        const val TYPE_IMAGE = "TYPE_IMAGE"
        const val TYPE_VIDEO = "TYPE_VIDEO"
        const val TYPE_AUDIO = "TYPE_AUDIO"
        const val TYPE_SYSTEM = "TYPE_SYSTEM"

        const val STATUS_SENDING = "SENDING"
        const val STATUS_SENT = "SENT"
        const val STATUS_DELIVERED = "DELIVERED"
        const val STATUS_READ = "READ"
        const val STATUS_FAILED = "FAILED"
    }

    val isImage: Boolean
        get() = (type ?: TYPE_IMAGE).equals(TYPE_IMAGE, ignoreCase = true) || (!mediaUrl.isNullOrBlank() && !isVideo && !isAudio)

    val isText: Boolean
        get() = (type ?: TYPE_TEXT).equals(TYPE_TEXT, ignoreCase = true) && mediaUrl.isNullOrBlank()

    val isVideo: Boolean
        get() = (type ?: "").equals(TYPE_VIDEO, ignoreCase = true) || (mediaUrl != null && (mediaUrl.endsWith(".mp4", true) || mediaUrl.endsWith(".mkv", true) || mediaUrl.endsWith(".mov", true) || mediaUrl.endsWith(".avi", true)))

    val isAudio: Boolean
        get() = (type ?: "").equals(TYPE_AUDIO, ignoreCase = true) || (mediaUrl != null && (mediaUrl.endsWith(".mp3", true) || mediaUrl.endsWith(".wav", true) || mediaUrl.endsWith(".m4a", true) || mediaUrl.endsWith(".aac", true) || mediaUrl.endsWith(".ogg", true)))

    fun isSentBy(currentUserId: Int): Boolean {
        return senderId == currentUserId || (sender?.id == currentUserId && currentUserId > 0)
    }
}
