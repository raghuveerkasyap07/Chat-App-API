package com.demo.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.demo.chat.data.model.*
import com.demo.chat.sdk.core.ChatClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(val chatClient: ChatClient) : ViewModel() {

    // Connection state from WebSocket manager
    val connectionState: StateFlow<ConnectionState> = chatClient.connectionState

    // Live incoming messages from WebSocket
    val incomingMessages: SharedFlow<ChatMessage> = chatClient.messageStream

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
    private val _activeChatId = MutableStateFlow(1)
    val activeChatId: StateFlow<Int> = _activeChatId.asStateFlow()

    // Active Recipient Partner
    private val _currentRecipient = MutableStateFlow<User?>(null)
    val currentRecipient: StateFlow<User?> = _currentRecipient.asStateFlow()

    // Registered Contacts list
    private val _registeredUsers = MutableStateFlow<List<User>>(emptyList())
    val registeredUsers: StateFlow<List<User>> = _registeredUsers.asStateFlow()

    val isLoggedIn: Boolean
        get() = chatClient.isLoggedIn()

    val currentUser: User?
        get() = chatClient.getCachedUser()

    init {
        // Collect real-time messages from WebSocket and append to state
        viewModelScope.launch {
            chatClient.messageStream.collect { msg ->
                if (msg.chatId == _activeChatId.value) {
                    _messageList.update { current ->
                        // Deduplicate if already present with same id or matching local pending message
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
    }

    /**
     * Load initial conversation on launch:
     * Discovers active chats or contacts, connects WebSocket to real room ID, and loads history.
     */
    fun loadInitialConversation() {
        viewModelScope.launch {
            _isLoading.value = true
            val convResult = chatClient.getConversations()
            _isLoading.value = false

            convResult.onSuccess { chats ->
                if (chats.isNotEmpty()) {
                    val firstChat = chats[0]
                    val myId = chatClient.session.getUserId()
                    val partner = firstChat.getDisplayPartner(myId)
                    _currentRecipient.value = partner
                    _activeChatId.value = firstChat.id
                    chatClient.connectWebSocket(firstChat.id)
                    loadMessages(firstChat.id)
                } else {
                    // No existing chats, connect with default and load contacts
                    chatClient.connectWebSocket(_activeChatId.value)
                    fetchRegisteredUsers()
                }
            }.onFailure {
                // If conversations failed to fetch, still connect WebSocket
                chatClient.connectWebSocket(_activeChatId.value)
                loadMessages(_activeChatId.value)
            }
        }
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
        viewModelScope.launch {
            _isLoading.value = true
            val result = chatClient.createOrGetChat(recipient.id)
            _isLoading.value = false

            result.onSuccess { chat ->
                _currentRecipient.value = recipient
                _activeChatId.value = chat.id
                _messageList.value = emptyList()

                // Connect WebSocket to specific 1-on-1 chat room
                chatClient.connectWebSocket(chat.id)

                // Load existing history
                loadMessages(chat.id)

                _statusEvent.tryEmit("Chatting with ${recipient.name}")
            }.onFailure { err ->
                _statusEvent.tryEmit("Failed to create chat: ${err.localizedMessage}")
            }
        }
    }

    fun connectWebSocket(chatId: Int) {
        _activeChatId.value = chatId
        chatClient.connectWebSocket(chatId)
    }

    fun disconnectWebSocket() {
        chatClient.disconnectWebSocket()
    }

    fun loadMessages(chatId: Int) {
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
        val sentOverSocket = chatClient.sendTextMessageSocket(chatId, text)

        // Also broadcast via REST to ensure persistence in MySQL
        viewModelScope.launch {
            try {
                chatClient.sendTextMessageRest(chatId, text)
            } catch (_: Exception) {}
        }

        val localMsg = ChatMessage(
            chatId = chatId,
            senderId = chatClient.session.getUserId(),
            message = text,
            type = ChatMessage.TYPE_TEXT,
            createdAt = System.currentTimeMillis().toString(),
            sender = chatClient.getCachedUser(),
            status = if (sentOverSocket) ChatMessage.STATUS_SENT else ChatMessage.STATUS_SENDING
        )
        _messageList.update { current ->
            if (current.none { it.message == text && it.senderId == localMsg.senderId && it.id == null }) {
                current + localMsg
            } else current
        }
    }

    fun uploadAndSendPhoto(chatId: Int, photoFile: File, caption: String = "") {
        viewModelScope.launch {
            _isLoading.value = true
            val result = chatClient.uploadPhotoAndSendOverSocket(chatId, photoFile, caption)
            _isLoading.value = false

            result.onSuccess { msg ->
                _messageList.update { it + msg }
                _statusEvent.tryEmit("Photo sent over WebSocket!")
            }.onFailure {
                _statusEvent.tryEmit("Photo upload/send failed: ${it.localizedMessage}")
            }
        }
    }

    fun logout() {
        chatClient.logout()
    }
}
