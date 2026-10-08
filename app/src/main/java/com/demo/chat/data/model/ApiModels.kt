package com.demo.chat.data.model

import com.google.gson.annotations.SerializedName

data class ApiResponse<T>(
    val success: Boolean? = true,
    val message: String? = null,
    val data: T? = null,
    val error: String? = null
)

data class AuthData(
    val token: String? = null,
    val user: User? = null
)

data class RegisterRequest(
    val name: String,
    val email: String,
    val password: String
)

data class LoginRequest(
    val email: String,
    val password: String
)

data class CreateChatRequest(
    val recipientId: Int,
    @SerializedName("recipient_id")
    val recipient_id: Int = recipientId
)

data class CreateChatResponseData(
    val chat: Chat? = null,
    val id: Int? = null
)

data class SendMessageRequest(
    val message: String,
    val type: String = ChatMessage.TYPE_TEXT,
    @SerializedName("media_url", alternate = ["mediaUrl"])
    val mediaUrl: String? = null,
    @SerializedName("media_type", alternate = ["mediaType"])
    val mediaType: String? = null
)

data class AttachmentUploadResponse(
    val url: String = "",
    @SerializedName("file_name", alternate = ["fileName", "filename"])
    val fileName: String? = null,
    @SerializedName("file_size", alternate = ["fileSize", "size"])
    val fileSize: Long? = null,
    @SerializedName("mime_type", alternate = ["mimeType"])
    val mimeType: String? = null,
    @SerializedName("media_type", alternate = ["mediaType"])
    val mediaType: String? = null,
    val metadata: MediaMetadata? = null
)

data class MessagesResponseData(
    val messages: List<ChatMessage>? = null,
    val page: Int? = null,
    val limit: Int? = null,
    val total: Int? = null
)
