package com.demo.chat.data.remote

import com.demo.chat.data.local.SessionManager
import com.demo.chat.data.model.*
import com.demo.chat.utils.ChatLogger
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class SocketIOManager(
    private val sessionManager: SessionManager
) {
    private var socket: Socket? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _messageFlow = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)
    val messageFlow: SharedFlow<ChatMessage> = _messageFlow.asSharedFlow()

    private val _receiptFlow = MutableSharedFlow<ReceiptEvent>(extraBufferCapacity = 64)
    val receiptFlow: SharedFlow<ReceiptEvent> = _receiptFlow.asSharedFlow()

    private val _typingFlow = MutableSharedFlow<TypingEvent>(extraBufferCapacity = 64)
    val typingFlow: SharedFlow<TypingEvent> = _typingFlow.asSharedFlow()

    private val _presenceFlow = MutableSharedFlow<PresenceEvent>(extraBufferCapacity = 64)
    val presenceFlow: SharedFlow<PresenceEvent> = _presenceFlow.asSharedFlow()

    fun isConnected(): Boolean = socket?.connected() == true

    fun connect(serverUrl: String? = null, token: String? = null) {
        if (socket?.connected() == true) {
            ChatLogger.d(TAG, "Socket.IO already connected")
            _connectionState.value = ConnectionState.Connected
            return
        }

        disconnect()

        val rawUrl = serverUrl ?: sessionManager.getBaseUrl()
        val effectiveUrl = if (rawUrl.endsWith("/")) rawUrl.dropLast(1) else rawUrl
        val jwtToken = token ?: sessionManager.getAuthToken() ?: ""

        _connectionState.value = ConnectionState.Connecting

        try {
            val options = IO.Options.builder()
                .setQuery("token=$jwtToken")
                .setTransports(arrayOf(WebSocket.NAME))
                .setReconnection(true)
                .build()

            val s = IO.socket(effectiveUrl, options)
            this.socket = s

            setupSocketListeners(s)
            s.connect()
            ChatLogger.d(TAG, "Socket.IO connecting to $effectiveUrl")
        } catch (e: Throwable) {
            ChatLogger.e(TAG, "Socket.IO failed to initialize", e)
            _connectionState.value = ConnectionState.Failed(e)
        }
    }

    private fun toJsonObject(arg: Any?): JSONObject? {
        return when (arg) {
            is JSONObject -> arg
            is String -> try { JSONObject(arg) } catch (_: Throwable) { null }
            null -> null
            else -> try { JSONObject(arg.toString()) } catch (_: Throwable) { null }
        }
    }

    private fun setupSocketListeners(s: Socket) {
        s.on(Socket.EVENT_CONNECT) {
            ChatLogger.d(TAG, "Socket.IO connected: ${s.id()}")
            _connectionState.value = ConnectionState.Connected
        }

        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val err = if (args.isNotEmpty() && args[0] is Throwable) {
                args[0] as Throwable
            } else {
                Exception(args.firstOrNull()?.toString() ?: "Unknown connect error")
            }
            ChatLogger.e(TAG, "Socket.IO connect error: ${err.message}", err)
            _connectionState.value = ConnectionState.Failed(err)
        }

        s.on(Socket.EVENT_DISCONNECT) { args ->
            val reason = args.firstOrNull()?.toString() ?: "disconnected"
            ChatLogger.d(TAG, "Socket.IO disconnected: $reason")
            _connectionState.value = ConnectionState.Disconnected
        }

        // --- 4. Receiving Messages (new_message) ---
        s.on("new_message") { args ->
            if (args.isEmpty()) return@on
            try {
                val data = toJsonObject(args[0]) ?: return@on
                val id = if (data.has("id") && !data.isNull("id")) {
                    data.optInt("id", -1).takeIf { it > 0 } ?: data.optString("id").toIntOrNull()
                } else null

                val msgChatId = data.optString("chatId", data.optString("chat_id", "0")).toIntOrNull() ?: 0
                val senderId = data.optInt("senderId", data.optInt("sender_id", -1))
                val senderName = data.optString("senderName", data.optString("sender_name", ""))
                val messageText = data.optString("message", "")
                val mediaUrl = when {
                    data.has("mediaUrl") && !data.isNull("mediaUrl") -> data.optString("mediaUrl").takeIf { it.isNotBlank() }
                    data.has("media_url") && !data.isNull("media_url") -> data.optString("media_url").takeIf { it.isNotBlank() }
                    else -> null
                }
                val status = data.optString("status", ChatMessage.STATUS_SENT)
                val timestamp = data.optString("timestamp", data.optString("created_at", System.currentTimeMillis().toString()))

                val chatMsg = ChatMessage(
                    id = id,
                    chatId = msgChatId,
                    senderId = senderId,
                    message = messageText,
                    type = if (!mediaUrl.isNullOrBlank()) ChatMessage.TYPE_IMAGE else ChatMessage.TYPE_TEXT,
                    mediaUrl = mediaUrl,
                    createdAt = timestamp,
                    sender = if (senderId > 0) User(id = senderId, name = senderName, email = "") else null,
                    status = status
                )
                _messageFlow.tryEmit(chatMsg)
            } catch (e: Throwable) {
                ChatLogger.e(TAG, "Error parsing new_message event", e)
            }
        }

        // --- 5. Delivery & Read Receipts (message_delivered & message_seen) ---
        s.on("message_delivered") { args ->
            if (args.isEmpty()) return@on
            try {
                val data = toJsonObject(args[0]) ?: return@on
                val targetChatId = data.optString("chatId", data.optString("chat_id", ""))
                val messageId = if (data.has("messageId") && !data.isNull("messageId")) {
                    data.optInt("messageId", -1).takeIf { it > 0 } ?: data.optString("messageId").toIntOrNull()
                } else null
                _receiptFlow.tryEmit(ReceiptEvent(chatId = targetChatId, messageId = messageId, status = "delivered"))
            } catch (e: Throwable) {
                ChatLogger.e(TAG, "Error handling message_delivered event", e)
            }
        }

        s.on("message_seen") { args ->
            if (args.isEmpty()) return@on
            try {
                val data = toJsonObject(args[0]) ?: return@on
                val targetChatId = data.optString("chatId", data.optString("chat_id", ""))
                val messageId = if (data.has("messageId") && !data.isNull("messageId")) {
                    data.optInt("messageId", -1).takeIf { it > 0 } ?: data.optString("messageId").toIntOrNull()
                } else null
                _receiptFlow.tryEmit(ReceiptEvent(chatId = targetChatId, messageId = messageId, status = "seen"))
            } catch (e: Throwable) {
                ChatLogger.e(TAG, "Error handling message_seen event", e)
            }
        }

        // --- 6. Typing Indicators (user_typing & user_stop_typing) ---
        s.on("user_typing") { args ->
            if (args.isEmpty()) return@on
            try {
                val data = toJsonObject(args[0]) ?: return@on
                val targetChatId = data.optString("chatId", data.optString("chat_id", ""))
                val name = data.optString("name", "Partner")
                _typingFlow.tryEmit(TypingEvent(chatId = targetChatId, name = name, isTyping = true))
            } catch (e: Throwable) {
                ChatLogger.e(TAG, "Error handling user_typing event", e)
            }
        }

        s.on("user_stop_typing") { args ->
            if (args.isEmpty()) return@on
            try {
                val data = toJsonObject(args[0]) ?: return@on
                val targetChatId = data.optString("chatId", data.optString("chat_id", ""))
                _typingFlow.tryEmit(TypingEvent(chatId = targetChatId, name = "", isTyping = false))
            } catch (e: Throwable) {
                ChatLogger.e(TAG, "Error handling user_stop_typing event", e)
            }
        }

        // --- 7. Online / Offline Presence (user_online & user_offline) ---
        s.on("user_online") { args ->
            if (args.isEmpty()) return@on
            try {
                val data = toJsonObject(args[0]) ?: return@on
                val onlineUserId = data.optInt("userId", data.optInt("user_id", -1))
                if (onlineUserId > 0) {
                    _presenceFlow.tryEmit(PresenceEvent(userId = onlineUserId, isOnline = true))
                }
            } catch (e: Throwable) {
                ChatLogger.e(TAG, "Error handling user_online event", e)
            }
        }

        s.on("user_offline") { args ->
            if (args.isEmpty()) return@on
            try {
                val data = toJsonObject(args[0]) ?: return@on
                val offlineUserId = data.optInt("userId", data.optInt("user_id", -1))
                if (offlineUserId > 0) {
                    _presenceFlow.tryEmit(PresenceEvent(userId = offlineUserId, isOnline = false))
                }
            } catch (e: Throwable) {
                ChatLogger.e(TAG, "Error handling user_offline event", e)
            }
        }
    }

    // --- Room Lifecycle ---

    fun joinChat(chatId: Int): Boolean {
        val s = socket ?: return false
        val joinData = JSONObject().apply { put("chatId", chatId.toString()) }
        s.emit("join_chat", joinData)
        ChatLogger.d(TAG, "Emitted join_chat for $chatId")
        return true
    }

    fun leaveChat(chatId: Int): Boolean {
        val s = socket ?: return false
        val leaveData = JSONObject().apply { put("chatId", chatId.toString()) }
        s.emit("leave_chat", leaveData)
        ChatLogger.d(TAG, "Emitted leave_chat for $chatId")
        return true
    }

    // --- Sending Messages ---

    fun sendMessage(
        chatId: Int,
        recipientId: Int,
        message: String,
        mediaUrl: String? = null
    ): Boolean {
        val s = socket ?: return false
        val messageData = JSONObject().apply {
            put("chatId", chatId.toString())
            if (recipientId > 0) {
                put("recipientId", recipientId)
            }
            put("message", message)
            put("mediaUrl", mediaUrl ?: JSONObject.NULL)
        }
        s.emit("send_message", messageData)
        ChatLogger.d(TAG, "Emitted send_message: chatId=$chatId, recipientId=$recipientId")
        return true
    }

    // --- Delivery & Seen Acknowledgment ---

    fun markMessageDelivered(chatId: Int, messageId: Int? = null): Boolean {
        val s = socket ?: return false
        val deliveredData = JSONObject().apply {
            put("chatId", chatId.toString())
            if (messageId != null) {
                put("messageId", messageId)
            }
        }
        s.emit("message_delivered", deliveredData)
        ChatLogger.d(TAG, "Emitted message_delivered for chat=$chatId, msg=$messageId")
        return true
    }

    fun markMessageSeen(chatId: Int, messageId: Int? = null): Boolean {
        val s = socket ?: return false
        val seenData = JSONObject().apply {
            put("chatId", chatId.toString())
            if (messageId != null) {
                put("messageId", messageId)
            }
        }
        s.emit("message_seen", seenData)
        ChatLogger.d(TAG, "Emitted message_seen for chat=$chatId, msg=$messageId")
        return true
    }

    // --- Typing Indicators ---

    fun emitTyping(chatId: Int): Boolean {
        val s = socket ?: return false
        val data = JSONObject().apply { put("chatId", chatId.toString()) }
        s.emit("typing", data)
        return true
    }

    fun emitStopTyping(chatId: Int): Boolean {
        val s = socket ?: return false
        val data = JSONObject().apply { put("chatId", chatId.toString()) }
        s.emit("stop_typing", data)
        return true
    }

    fun disconnect() {
        try {
            socket?.disconnect()
            socket?.off()
            socket = null
            _connectionState.value = ConnectionState.Disconnected
            ChatLogger.d(TAG, "Socket.IO disconnected and cleared")
        } catch (e: Throwable) {
            ChatLogger.e(TAG, "Error disconnecting Socket.IO", e)
        }
    }

    companion object {
        private const val TAG = "SocketIOManager"
    }
}
