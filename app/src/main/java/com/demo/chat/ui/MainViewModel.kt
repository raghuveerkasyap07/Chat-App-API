package com.demo.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.demo.chat.data.model.*
import com.demo.chat.sdk.core.ChatClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(val chatClient: ChatClient) : ViewModel() {

    // Connection state from Socket.IO client manager
    val connectionState: StateFlow<ConnectionState> = chatClient.socketIOConnectionState

    // Live incoming messages from Socket.IO
    val incomingMessages: SharedFlow<ChatMessage> = chatClient.socketIOMessageStream

    // Message list state for UI
    private val _messageList = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messageList: StateFlow<List<ChatMessage>> = _messageList.asStateFlow()

    // Status / feedback events for UI
    private val _statusEvent = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val statusEvent: SharedFlow<String> = _statusEvent.asSharedFlow()

    // Loading indicator
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Active Chat ID
    private val _activeChatId = MutableStateFlow(0)
    val activeChatId: StateFlow<Int> = _activeChatId.asStateFlow()

    // Active Recipient Partner
    private val _currentRecipient = MutableStateFlow<User?>(null)
    val currentRecipient: StateFlow<User?> = _currentRecipient.asStateFlow()

    // Registered Contacts list
    private val _registeredUsers = MutableStateFlow<List<User>>(emptyList())
    val registeredUsers: StateFlow<List<User>> = _registeredUsers.asStateFlow()

    // Partner typing state
    private val _partnerTypingText = MutableStateFlow<String?>(null)
    val partnerTypingText: StateFlow<String?> = _partnerTypingText.asStateFlow()

    // Partner presence state
    private val _partnerIsOnline = MutableStateFlow<Boolean?>(null)
    val partnerIsOnline: StateFlow<Boolean?> = _partnerIsOnline.asStateFlow()

    private var isConversationLoaded = false

    val isLoggedIn: Boolean
        get() = chatClient.isLoggedIn()

    val currentUser: User?
        get() = chatClient.getCachedUser()

    init {
        // 1. Collect real-time messages from Socket.IO and append to state
        viewModelScope.launch {
            chatClient.socketIOMessageStream.collect { msg ->
                val currentChatId = _activeChatId.value
                val currentUserId = chatClient.session.getUserId()

                if (msg.chatId == currentChatId) {
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

                    // Acknowledge delivery & seen if received from partner while chat is active
                    if (msg.senderId != currentUserId && msg.id != null) {
                        chatClient.markMessageDelivered(currentChatId, msg.id)
                        chatClient.markMessageSeen(currentChatId, msg.id)
                    }
                }
            }
        }

        // 2. Also collect legacy socket stream if present for backward-compatibility
        viewModelScope.launch {
            chatClient.messageStream.collect { msg ->
                if (msg.chatId == _activeChatId.value) {
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
                }
            }
        }

        // 3. Collect delivery & read receipts (message_delivered & message_seen)
        viewModelScope.launch {
            chatClient.receiptEvents.collect { receipt ->
                val currentChatId = _activeChatId.value.toString()
                if (receipt.chatId == currentChatId) {
                    _messageList.update { current ->
                        current.map { msg ->
                            val isOutgoing = msg.isSentBy(chatClient.session.getUserId())
                            if (!isOutgoing) return@map msg

                            val matches = if (receipt.messageId != null) {
                                msg.id == receipt.messageId
                            } else {
                                true
                            }

                            if (matches) {
                                msg.copy(
                                    message = msg.message ?: "",
                                    type = msg.type ?: if (!msg.mediaUrl.isNullOrBlank()) ChatMessage.TYPE_IMAGE else ChatMessage.TYPE_TEXT,
                                    status = receipt.status ?: ChatMessage.STATUS_DELIVERED
                                )
                            } else {
                                msg
                            }
                        }
                    }
                }
            }
        }

        // 4. Collect partner typing indicators
        viewModelScope.launch {
            chatClient.typingEvents.collect { typing ->
                val currentChatId = _activeChatId.value.toString()
                if (typing.chatId == currentChatId) {
                    if (typing.isTyping) {
                        _partnerTypingText.value = "${typing.name.ifBlank { "Partner" }} is typing..."
                    } else {
                        _partnerTypingText.value = null
                    }
                }
            }
        }

        // 5. Collect partner presence indicators
        viewModelScope.launch {
            chatClient.presenceEvents.collect { presence ->
                val partnerId = _currentRecipient.value?.id
                if (partnerId != null && presence.userId == partnerId) {
                    _partnerIsOnline.value = presence.isOnline
                }
            }
        }
    }

    /**
     * Load initial conversation on launch:
     * Discovers active chats or contacts, connects Socket.IO, and joins the chat room.
     */
    fun loadInitialConversation() {
        if (isConversationLoaded) return
        isConversationLoaded = true

        viewModelScope.launch {
            // Establish persistent Socket.IO connection
            chatClient.connectSocketIO()

            _isLoading.value = true
            val convResult = chatClient.getConversations()
            _isLoading.value = false

            convResult.onSuccess { chats ->
                if (chats.isNotEmpty()) {
                    val firstChat = chats[0]
                    val myId = chatClient.session.getUserId()
                    val partner = firstChat.getDisplayPartner(myId)
                    _currentRecipient.value = partner
                    joinChat(firstChat.id)
                    loadMessages(firstChat.id)
                } else {
                    // No existing chats, load contacts
                    fetchRegisteredUsers()
                }
            }.onFailure {
                if (_activeChatId.value > 0) {
                    joinChat(_activeChatId.value)
                    loadMessages(_activeChatId.value)
                }
            }
        }
    }

    fun joinChat(chatId: Int) {
        if (chatId <= 0) return
        _activeChatId.value = chatId
        chatClient.joinSocketIOChat(chatId)

        // Mark messages as seen via Socket.IO and REST fallback
        chatClient.markMessageSeen(chatId)
        viewModelScope.launch {
            try {
                chatClient.markChatSeenRest(chatId)
            } catch (_: Throwable) {}
        }
    }

    fun leaveChat(chatId: Int) {
        if (chatId <= 0) return
        chatClient.leaveSocketIOChat(chatId)
    }

    fun emitTyping(chatId: Int) {
        if (chatId <= 0) return
        chatClient.emitTyping(chatId)
    }

    fun emitStopTyping(chatId: Int) {
        if (chatId <= 0) return
        chatClient.emitStopTyping(chatId)
    }

    fun fetchRegisteredUsers() {
        viewModelScope.launch {
            _isLoading.value = true
            val result = chatClient.getUsers()
            _isLoading.value = false

            result.onSuccess { users ->
                val myId = chatClient.session.getUserId()
                _registeredUsers.value = users.filter { it.id != myId }
            }.onFailure { err ->
                _statusEvent.tryEmit("Failed to load contacts: ${err.localizedMessage}")
            }
        }
    }

    fun startChatWithUser(recipient: User) {
        if (recipient.id <= 0) return
        viewModelScope.launch {
            _isLoading.value = true
            val result = chatClient.createOrGetChat(recipient.id)
            _isLoading.value = false

            result.onSuccess { chat ->
                _currentRecipient.value = recipient
                _partnerIsOnline.value = recipient.isOnline
                _partnerTypingText.value = null
                _messageList.value = emptyList()

                joinChat(chat.id)
                loadMessages(chat.id)

                _statusEvent.tryEmit("Chatting with ${recipient.displayName}")
            }.onFailure { err ->
                _statusEvent.tryEmit("Failed to create chat: ${err.localizedMessage}")
            }
        }
    }

    fun loadMessages(chatId: Int) {
        if (chatId <= 0) return
        viewModelScope.launch {
            _isLoading.value = true
            val result = chatClient.getChatMessages(chatId)
            _isLoading.value = false

            result.onSuccess { msgs ->
                _messageList.value = msgs
            }.onFailure {
                _statusEvent.tryEmit("Failed to load messages: ${it.localizedMessage}")
            }
        }
    }

    fun sendTextMessage(chatId: Int, text: String) {
        if (chatId <= 0) {
            _statusEvent.tryEmit("Please select a contact to start chatting")
            return
        }
        val partnerId = _currentRecipient.value?.id ?: 0

        // 1. Emit send_message via Socket.IO
        chatClient.sendSocketIOMessage(
            chatId = chatId,
            recipientId = partnerId,
            message = text
        )

        // 2. Also emit over REST fallback for persistence in MySQL
        viewModelScope.launch {
            try {
                chatClient.sendTextMessageRest(chatId, text)
            } catch (_: Throwable) {}
        }

        // 3. Optimistically add message to state with status = "sent" (single tick ✓)
        val timeStr = try {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        } catch (_: Throwable) {
            System.currentTimeMillis().toString()
        }

        val localMsg = ChatMessage(
            chatId = chatId,
            senderId = chatClient.session.getUserId(),
            message = text,
            type = ChatMessage.TYPE_TEXT,
            createdAt = timeStr,
            sender = chatClient.getCachedUser(),
            status = ChatMessage.STATUS_SENT
        )

        _messageList.update { current ->
            if (current.none { it.message == text && it.senderId == localMsg.senderId && it.id == null }) {
                current + localMsg
            } else current
        }
    }

    fun uploadAndSendPhoto(chatId: Int, photoFile: File, caption: String = "") {
        if (chatId <= 0) {
            _statusEvent.tryEmit("Please select a contact before sending photos")
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            val result = chatClient.uploadPhotoAndSendOverSocket(chatId, photoFile, caption)
            _isLoading.value = false

            result.onSuccess { msg ->
                _messageList.update { current ->
                    if (current.none { it.id == msg.id && msg.id != null }) current + msg else current
                }
                _statusEvent.tryEmit("Photo sent!")
            }.onFailure {
                _statusEvent.tryEmit("Photo upload/send failed: ${it.localizedMessage}")
            }
        }
    }

    fun uploadAndSendVideo(chatId: Int, videoFile: File, caption: String = "") {
        if (chatId <= 0) {
            _statusEvent.tryEmit("Please select a contact before sending videos")
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            val result = chatClient.uploadVideoAndSendOverSocket(chatId, videoFile, caption)
            _isLoading.value = false

            result.onSuccess { msg ->
                _messageList.update { current ->
                    if (current.none { it.id == msg.id && msg.id != null }) current + msg else current
                }
                _statusEvent.tryEmit("Video sent!")
            }.onFailure {
                _statusEvent.tryEmit("Video upload/send failed: ${it.localizedMessage}")
            }
        }
    }

    fun uploadAndSendAudio(chatId: Int, audioFile: File, caption: String = "") {
        if (chatId <= 0) {
            _statusEvent.tryEmit("Please select a contact before sending audio")
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            val result = chatClient.uploadAudioAndSendOverSocket(chatId, audioFile, caption)
            _isLoading.value = false

            result.onSuccess { msg ->
                _messageList.update { current ->
                    if (current.none { it.id == msg.id && msg.id != null }) current + msg else current
                }
                _statusEvent.tryEmit("Audio sent!")
            }.onFailure {
                _statusEvent.tryEmit("Audio upload/send failed: ${it.localizedMessage}")
            }
        }
    }

    fun uploadAndSendFile(chatId: Int, file: File, caption: String = "") {
        if (chatId <= 0) {
            _statusEvent.tryEmit("Please select a contact before sending files")
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            val result = chatClient.uploadFileAndSendOverSocket(chatId, file, caption)
            _isLoading.value = false

            result.onSuccess { msg ->
                _messageList.update { current ->
                    if (current.none { it.id == msg.id && msg.id != null }) current + msg else current
                }
                _statusEvent.tryEmit("File sent!")
            }.onFailure {
                _statusEvent.tryEmit("File upload/send failed: ${it.localizedMessage}")
            }
        }
    }

    fun logout() {
        chatClient.logout()
    }
}
