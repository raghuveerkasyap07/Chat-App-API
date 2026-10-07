package com.demo.chat.ui.threads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.demo.chat.data.model.Chat
import com.demo.chat.data.model.User
import com.demo.chat.sdk.core.ChatClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ThreadListViewModel(val chatClient: ChatClient) : ViewModel() {

    private val _threads = MutableStateFlow<List<Chat>>(emptyList())
    val threads: StateFlow<List<Chat>> = _threads.asStateFlow()

    private val _registeredUsers = MutableStateFlow<List<User>>(emptyList())
    val registeredUsers: StateFlow<List<User>> = _registeredUsers.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _statusEvent = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val statusEvent: SharedFlow<String> = _statusEvent.asSharedFlow()

    private val _openChatEvent = MutableSharedFlow<Pair<Int, String>>(extraBufferCapacity = 16)
    val openChatEvent: SharedFlow<Pair<Int, String>> = _openChatEvent.asSharedFlow()

    val currentUser: User?
        get() = chatClient.getCachedUser()

    fun loadThreads() {
        viewModelScope.launch {
            _isLoading.value = true
            val result = chatClient.getConversations()
            _isLoading.value = false

            result.onSuccess { list ->
                _threads.value = list
            }.onFailure { err ->
                _statusEvent.emit("Failed to load chats: ${err.localizedMessage}")
            }
        }
    }

    fun fetchContacts() {
        viewModelScope.launch {
            val result = chatClient.getUsers()
            result.onSuccess { users ->
                val myId = chatClient.session.getUserId()
                // Filter out current user
                _registeredUsers.value = users.filter { it.id != myId }
            }.onFailure { err ->
                _statusEvent.emit("Failed to fetch contacts: ${err.localizedMessage}")
            }
        }
    }

    fun startChatWithUser(user: User) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = chatClient.createOrGetChat(user.id)
            _isLoading.value = false

            result.onSuccess { chat ->
                _openChatEvent.emit(Pair(chat.id, user.name))
            }.onFailure { err ->
                _statusEvent.emit("Failed to start chat: ${err.localizedMessage}")
            }
        }
    }

    fun logout() {
        chatClient.logout()
    }
}
