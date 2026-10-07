package com.demo.chat.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.demo.chat.data.model.ChatMessage
import com.demo.chat.data.model.ConnectionState
import com.demo.chat.sdk.core.ChatClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class ChatViewModel(
    val chatClient: ChatClient,
    val chatId: Int
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = chatClient.connectionState

    private val _messageList = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messageList: StateFlow<List<ChatMessage>> = _messageList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _statusEvent = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val statusEvent: SharedFlow<String> = _statusEvent.asSharedFlow()

    val currentUserId: Int
        get() = chatClient.session.getUserId()

    init {
        // Connect WebSocket and join room
        chatClient.connectWebSocket(chatId)
        chatClient.joinChat(chatId)

        // Load chat history
        loadMessages()

        // Collect incoming WebSocket messages
        viewModelScope.launch {
            chatClient.messageStream.collect { msg ->
                if (msg.chatId == chatId || msg.chatId == 0) {
                    _messageList.update { current ->
                        val existingIndex = current.indexOfFirst {
                            (msg.id != null && it.id == msg.id) ||
                            (it.id == null && it.message == msg.message && it.senderId == msg.senderId)
                        }
                        if (existingIndex != -1) {
                            current.toMutableList().apply { set(existingIndex, msg) }
                        } else {
                            current + msg
                        }
                    }

                    // Acknowledge delivery and seen for incoming messages from other users
                    if (msg.senderId != currentUserId && msg.id != null) {
                        chatClient.sendDelivered(chatId, msg.id)
                        chatClient.sendSeen(chatId, msg.id)
                    }
                }
            }
        }
    }

    fun leaveChat() {
        chatClient.leaveChat(chatId)
    }

    fun sendTyping() {
        chatClient.sendTyping(chatId)
    }

    fun sendStopTyping() {
        chatClient.sendStopTyping(chatId)
    }

    fun sendSeen(messageId: Int? = null) {
        chatClient.sendSeen(chatId, messageId)
    }

    fun sendDelivered(messageId: Int? = null) {
        chatClient.sendDelivered(chatId, messageId)
    }

    fun loadMessages() {
        viewModelScope.launch {
            _isLoading.value = true
            val result = chatClient.getChatMessages(chatId)
            _isLoading.value = false

            result.onSuccess { messages ->
                _messageList.value = messages
            }.onFailure { err ->
                _statusEvent.emit("Failed to load messages: ${err.localizedMessage}")
            }
        }
    }

    fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            // Optimistic bubble
            val pendingMsg = ChatMessage(
                chatId = chatId,
                senderId = currentUserId,
                message = text,
                type = ChatMessage.TYPE_TEXT,
                createdAt = System.currentTimeMillis().toString(),
                sender = chatClient.getCachedUser(),
                status = ChatMessage.STATUS_SENDING
            )
            _messageList.update { it + pendingMsg }

            // Try sending via WebSocket first
            val sentOverSocket = chatClient.sendTextMessageSocket(chatId, text)
            if (!sentOverSocket) {
                // Fallback to REST
                val restResult = chatClient.sendTextMessageRest(chatId, text)
                restResult.onSuccess { sentMsg ->
                    _messageList.update { list ->
                        list.map { if (it == pendingMsg) sentMsg else it }
                    }
                }.onFailure {
                    _statusEvent.emit("Failed to send message")
                }
            }
        }
    }

    fun sendPhoto(uri: Uri, context: Context) {
        viewModelScope.launch {
            try {
                // 1. Copy uri into cache file
                val file = uriToFile(uri, context) ?: run {
                    _statusEvent.emit("Failed to process selected image")
                    return@launch
                }

                // 2. Optimistic local image bubble
                val optimisticMsg = ChatMessage(
                    chatId = chatId,
                    senderId = currentUserId,
                    message = "Photo",
                    type = ChatMessage.TYPE_IMAGE,
                    mediaUrl = uri.toString(),
                    createdAt = System.currentTimeMillis().toString(),
                    sender = chatClient.getCachedUser(),
                    status = ChatMessage.STATUS_SENDING
                )
                _messageList.update { it + optimisticMsg }

                // 3. Upload photo and broadcast over socket via ChatClient helper
                val result = chatClient.uploadPhotoAndSendOverSocket(chatId, file)
                result.onSuccess { confirmedMsg ->
                    _messageList.update { list ->
                        list.map { if (it == optimisticMsg) confirmedMsg else it }
                    }
                }.onFailure { err ->
                    _statusEvent.emit("Photo upload failed: ${err.localizedMessage}")
                }
            } catch (e: Exception) {
                _statusEvent.emit("Error sending photo: ${e.localizedMessage}")
            }
        }
    }

    private fun uriToFile(uri: Uri, context: Context): File? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val cacheDir = context.cacheDir
            val tempFile = File(cacheDir, "upload_${System.currentTimeMillis()}.jpg")
            val outputStream = FileOutputStream(tempFile)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            tempFile
        } catch (e: Exception) {
            null
        }
    }
}
