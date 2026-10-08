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
    @SerializedName("media_type", alternate = ["mediaType"])
    val mediaType: String? = null,
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
        const val TYPE_FILE = "TYPE_FILE"
        const val TYPE_SYSTEM = "TYPE_SYSTEM"

        const val STATUS_SENDING = "sending"
        const val STATUS_SENT = "sent"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_SEEN = "seen"
        const val STATUS_READ = "read"
        const val STATUS_FAILED = "failed"

        fun isVideoUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val clean = url.substringBefore('?').lowercase()
            return clean.endsWith(".mp4") || clean.endsWith(".m4v") || clean.endsWith(".webm") ||
                    clean.endsWith(".mov") || clean.endsWith(".avi") || clean.endsWith(".mkv") ||
                    clean.endsWith(".3gp") || clean.endsWith(".mpeg") || clean.endsWith(".mpg")
        }

        fun isAudioUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val clean = url.substringBefore('?').lowercase()
            return clean.endsWith(".mp3") || clean.endsWith(".wav") || clean.endsWith(".ogg") ||
                    clean.endsWith(".aac") || clean.endsWith(".m4a") || clean.endsWith(".flac") ||
                    clean.endsWith(".amr") || clean.endsWith(".opus") || clean.endsWith(".wma")
        }

        fun isImageUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val clean = url.substringBefore('?').lowercase()
            return clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") ||
                    clean.endsWith(".webp") || clean.endsWith(".gif") || clean.endsWith(".bmp") ||
                    clean.endsWith(".svg") || clean.endsWith(".heic")
        }

        fun isFileUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            return !isVideoUrl(url) && !isAudioUrl(url) && !isImageUrl(url)
        }

        fun determineTypeFromUrl(url: String?): String {
            return when {
                isVideoUrl(url) -> TYPE_VIDEO
                isAudioUrl(url) -> TYPE_AUDIO
                isImageUrl(url) -> TYPE_IMAGE
                !url.isNullOrBlank() -> TYPE_FILE
                else -> TYPE_TEXT
            }
        }
    }

    val text: String
        get() = message ?: ""

    val isVideo: Boolean
        get() = (type ?: "").equals(TYPE_VIDEO, ignoreCase = true) ||
                (mediaType ?: "").equals("video", ignoreCase = true) ||
                isVideoUrl(mediaUrl)

    val isAudio: Boolean
        get() = (type ?: "").equals(TYPE_AUDIO, ignoreCase = true) ||
                (mediaType ?: "").equals("audio", ignoreCase = true) ||
                isAudioUrl(mediaUrl)

    val isImage: Boolean
        get() {
            if (isVideo || isAudio) return false
            if ((type ?: "").equals(TYPE_IMAGE, ignoreCase = true) || (mediaType ?: "").equals("image", ignoreCase = true)) {
                return true
            }
            if (isImageUrl(mediaUrl)) return true
            return false
        }

    val isFile: Boolean
        get() {
            if (isVideo || isAudio || isImage) return false
            if ((type ?: "").equals(TYPE_FILE, ignoreCase = true) ||
                (mediaType ?: "").equals("file", ignoreCase = true) ||
                (mediaType ?: "").equals("document", ignoreCase = true)) {
                return true
            }
            return !mediaUrl.isNullOrBlank() && isFileUrl(mediaUrl)
        }

    val effectiveType: String
        get() = when {
            isVideo -> TYPE_VIDEO
            isAudio -> TYPE_AUDIO
            isImage -> TYPE_IMAGE
            isFile -> TYPE_FILE
            !type.isNullOrBlank() -> type
            else -> TYPE_TEXT
        }

    val effectiveStatus: String
        get() = status?.takeIf { it.isNotBlank() } ?: STATUS_SENT

    val isSeen: Boolean
        get() = (status ?: "").equals("seen", ignoreCase = true) || (status ?: "").equals("read", ignoreCase = true)

    val isDelivered: Boolean
        get() = (status ?: "").equals("delivered", ignoreCase = true)

    val isSent: Boolean
        get() = (status ?: "").equals("sent", ignoreCase = true)

    val isText: Boolean
        get() = !isImage && !isVideo && !isAudio && !isFile

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
