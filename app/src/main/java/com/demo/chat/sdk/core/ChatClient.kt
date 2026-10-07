package com.demo.chat.sdk.core

import android.content.Context
import com.demo.chat.data.local.SessionManager
import com.demo.chat.data.model.*
import com.demo.chat.data.remote.*
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class ChatClient private constructor(
    val session: SessionManager,
    val auth: AuthRepository,
    val chat: ChatRepository,
    val media: MediaRepository,
    val socket: WebSocketClientManager
) {

    // Asynchronous streams exposed directly
    val connectionState: StateFlow<ConnectionState> = socket.connectionState
    val messageStream: SharedFlow<ChatMessage> = socket.messageFlow

    // --- Authentication Operations ---

    suspend fun register(name: String, email: String, password: String): Result<AuthData> {
        return auth.register(name, email, password)
    }

    suspend fun login(email: String, password: String): Result<AuthData> {
        return auth.login(email, password)
    }

    suspend fun getCurrentUser(): Result<User> {
        return auth.getCurrentUser()
    }

    fun logout() {
        socket.disconnect()
        auth.logout()
    }

    fun isLoggedIn(): Boolean = auth.isLoggedIn()

    fun getAuthToken(): String? = auth.getAuthToken()

    fun getCachedUser(): User? = auth.getCachedUser()

    // --- REST Operations ---

    suspend fun getUsers(search: String? = null): Result<List<User>> {
        return chat.getUsers(search)
    }

    suspend fun getUserById(userId: Int): Result<User> {
        return chat.getUserById(userId)
    }

    suspend fun createOrGetChat(recipientId: Int): Result<Chat> {
        return chat.createOrGetChat(recipientId)
    }

    suspend fun getConversations(): Result<List<Chat>> {
        return chat.getConversations()
    }

    suspend fun getChatDetails(chatId: Int): Result<Chat> {
        return chat.getChatDetails(chatId)
    }

    suspend fun getChatMessages(chatId: Int, page: Int = 1, limit: Int = 50): Result<List<ChatMessage>> {
        return chat.getChatMessages(chatId, page, limit)
    }

    suspend fun sendTextMessageRest(chatId: Int, message: String): Result<ChatMessage> {
        return chat.sendTextMessage(chatId, message)
    }

    // --- Media & Photo Upload Operations ---

    suspend fun uploadPhoto(
        chatId: Int,
        file: File,
        mimeType: String = "image/jpeg",
        description: String? = null
    ): Result<AttachmentUploadResponse> {
        return media.uploadPhoto(chatId, file, mimeType, description)
    }

    /**
     * Combined flow: Multipart photo upload successfully uploads image and transmits image URL over WebSocket.
     */
    suspend fun uploadPhotoAndSendOverSocket(
        chatId: Int,
        imageFile: File,
        caption: String = "",
        mimeType: String = "image/jpeg"
    ): Result<ChatMessage> {
        // 1. Upload photo via multipart REST endpoint
        val uploadResult = media.uploadPhoto(chatId, imageFile, mimeType, caption)
        if (uploadResult.isFailure) {
            return Result.failure(uploadResult.exceptionOrNull() ?: Exception("Photo upload failed"))
        }

        val attachment = uploadResult.getOrThrow()

        // 2. Transmit image URL and metadata over WebSocket
        val sentOverSocket = socket.sendImageMessage(
            chatId = chatId,
            imageUrl = attachment.url,
            caption = caption,
            metadata = attachment.metadata
        )

        // 3. Construct confirmed ChatMessage object
        val chatMessage = ChatMessage(
            chatId = chatId,
            senderId = session.getUserId(),
            message = caption,
            type = ChatMessage.TYPE_IMAGE,
            mediaUrl = attachment.url,
            metadata = attachment.metadata,
            createdAt = System.currentTimeMillis().toString(),
            sender = session.getUser(),
            status = if (sentOverSocket) ChatMessage.STATUS_SENT else ChatMessage.STATUS_SENDING
        )

        return Result.success(chatMessage)
    }

    // --- WebSocket Operations ---

    fun connectWebSocket(chatId: Int? = null) {
        socket.connect(chatId)
    }

    fun joinChat(chatId: Int): Boolean {
        return socket.joinChat(chatId)
    }

    fun leaveChat(chatId: Int): Boolean {
        return socket.leaveChat(chatId)
    }

    fun sendTyping(chatId: Int): Boolean {
        return socket.sendTyping(chatId)
    }

    fun sendStopTyping(chatId: Int): Boolean {
        return socket.sendStopTyping(chatId)
    }

    fun sendSeen(chatId: Int, messageId: Int? = null): Boolean {
        return socket.sendSeen(chatId, messageId)
    }

    fun sendDelivered(chatId: Int, messageId: Int? = null): Boolean {
        return socket.sendDelivered(chatId, messageId)
    }

    fun sendTextMessageSocket(chatId: Int, message: String): Boolean {
        return socket.sendTextMessage(chatId, message)
    }

    fun sendImageMessageSocket(
        chatId: Int,
        imageUrl: String,
        caption: String = "",
        metadata: MediaMetadata? = null
    ): Boolean {
        return socket.sendImageMessage(chatId, imageUrl, caption, metadata)
    }

    fun sendPing(): Boolean {
        return socket.sendPing()
    }

    fun disconnectWebSocket() {
        socket.disconnect()
    }

    // --- Factory & Builder ---

    companion object {
        @Volatile
        private var instance: ChatClient? = null

        fun getInstance(): ChatClient {
            return instance ?: throw IllegalStateException(
                "ChatClient is not initialized. Call ChatClient.initialize(context) first."
            )
        }

        fun initialize(
            context: Context,
            baseUrl: String? = null,
            wsUrl: String? = null
        ): ChatClient {
            return instance ?: synchronized(this) {
                instance ?: Builder(context).apply {
                    baseUrl?.let { setBaseUrl(it) }
                    wsUrl?.let { setWebSocketUrl(it) }
                }.build().also { instance = it }
            }
        }

        fun reset() {
            synchronized(this) {
                instance?.disconnectWebSocket()
                instance = null
            }
        }
    }

    class Builder(private val context: Context) {
        private var baseUrl: String? = null
        private var wsUrl: String? = null

        fun setBaseUrl(url: String) = apply { this.baseUrl = url }
        fun setWebSocketUrl(url: String) = apply { this.wsUrl = url }

        fun build(): ChatClient {
            val sessionManager = SessionManager(context.applicationContext)
            baseUrl?.let { sessionManager.setBaseUrl(it) }
            wsUrl?.let { sessionManager.setWebSocketUrl(it) }

            val networkProvider = NetworkClientProvider(sessionManager)
            val apiService = networkProvider.createApiService()

            val authRepo = AuthRepository(apiService, sessionManager)
            val chatRepo = ChatRepository(apiService, sessionManager)
            val mediaRepo = MediaRepository(apiService, sessionManager)
            val socketManager = WebSocketClientManager(
                okHttpClient = networkProvider.okHttpClient,
                sessionManager = sessionManager,
                gson = networkProvider.gson
            )

            return ChatClient(
                session = sessionManager,
                auth = authRepo,
                chat = chatRepo,
                media = mediaRepo,
                socket = socketManager
            )
        }
    }
}
