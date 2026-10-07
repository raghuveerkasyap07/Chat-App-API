package com.demo.chat.data.model

import com.google.gson.annotations.SerializedName

data class ChatMessage(
    val id: Int? = null,
    @SerializedName("chat_id", alternate = ["chatId"])
    val chatId: Int = 0,
    @SerializedName("sender_id", alternate = ["senderId", "userId", "user_id", "from_id"])
    val senderId: Int = 0,
    val message: String? = "",
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
        const val TYPE_SYSTEM = "TYPE_SYSTEM"

        const val STATUS_SENDING = "sending"
        const val STATUS_SENT = "sent"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_SEEN = "seen"
        const val STATUS_READ = "read"
        const val STATUS_FAILED = "failed"
    }

    val text: String
        get() = message ?: ""

    val effectiveType: String
        get() = if (!mediaUrl.isNullOrBlank()) TYPE_IMAGE else (type?.takeIf { it.isNotBlank() } ?: TYPE_TEXT)

    val effectiveStatus: String
        get() = status?.takeIf { it.isNotBlank() } ?: STATUS_SENT

    val isSeen: Boolean
        get() = (status ?: "").equals("seen", ignoreCase = true) || (status ?: "").equals("read", ignoreCase = true)

    val isDelivered: Boolean
        get() = (status ?: "").equals("delivered", ignoreCase = true)

    val isSent: Boolean
        get() = (status ?: "").equals("sent", ignoreCase = true)

    val isImage: Boolean
        get() = (type ?: "").equals(TYPE_IMAGE, ignoreCase = true) || !mediaUrl.isNullOrBlank()

    val isText: Boolean
        get() = (type == null || type.equals(TYPE_TEXT, ignoreCase = true)) && mediaUrl.isNullOrBlank()

    fun isSentBy(currentUserId: Int): Boolean {
        return (currentUserId > 0 && senderId == currentUserId) || (sender?.id == currentUserId && currentUserId > 0)
    }

    fun getEffectiveMediaUrl(baseUrl: String? = null): String? {
        val url = mediaUrl?.takeIf { it.isNotBlank() } ?: return null
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("content://") || url.startsWith("file://")) {
            return url
        }
        val base = (baseUrl ?: "").trimEnd('/')
        val path = if (url.startsWith("/")) url else "/$url"
        return if (base.isNotBlank()) "$base$path" else url
    }
}
