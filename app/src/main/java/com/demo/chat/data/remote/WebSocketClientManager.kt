package com.demo.chat.data.remote

import com.demo.chat.data.local.SessionManager
import com.demo.chat.data.model.*
import com.demo.chat.utils.ChatLogger
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WebSocketClientManager(
    private val okHttpClient: OkHttpClient,
    private val sessionManager: SessionManager,
    private val gson: Gson = Gson(),
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    private var webSocket: WebSocket? = null
    private var activeChatId: Int? = null
    private val isManuallyClosed = AtomicBoolean(false)
    private val retryAttempt = AtomicInteger(0)
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null

    // Track whether connecting endpoint operates via Socket.IO engine
    private val isSocketIo = AtomicBoolean(false)

    // Live asynchronous streams
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Non-blocking SharedFlow with buffer capacity for instant delivery without blocking caller or UI
    private val _messageFlow = MutableSharedFlow<ChatMessage>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messageFlow: SharedFlow<ChatMessage> = _messageFlow.asSharedFlow()

    // Status / Pong flow
    private val _pongFlow = MutableSharedFlow<Long>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val pongFlow: SharedFlow<Long> = _pongFlow.asSharedFlow()

    /**
     * Connect to the WebSocket endpoint using JWT authentication.
     */
    @Synchronized
    fun connect(chatId: Int? = null) {
        if (chatId != null) {
            this.activeChatId = chatId
        }

        if (_connectionState.value is ConnectionState.Connected && webSocket != null) {
            ChatLogger.d(TAG, "Already connected to WebSocket")
            activeChatId?.let { joinChat(it) }
            return
        }

        isManuallyClosed.set(false)
        cancelReconnect()

        val token = sessionManager.getAuthToken()
        val rawWsUrl = sessionManager.getWebSocketUrl()

        // Append token query parameter as backup alongside header
        val wsUrl = if (!token.isNullOrBlank() && !rawWsUrl.contains("token=")) {
            val separator = if (rawWsUrl.contains("?")) "&" else "?"
            "$rawWsUrl${separator}token=$token"
        } else {
            rawWsUrl
        }

        val requestBuilder = Request.Builder().url(wsUrl)
        if (!token.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        _connectionState.value = ConnectionState.Connecting
        ChatLogger.d(TAG, "Connecting to WebSocket: $wsUrl")

        try {
            webSocket = okHttpClient.newWebSocket(requestBuilder.build(), createWebSocketListener())
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Failed to initiate WebSocket connection", e)
            _connectionState.value = ConnectionState.Failed(e)
            scheduleReconnect()
        }
    }

    /**
     * Create the OkHttp WebSocketListener lifecycle callbacks.
     */
    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ChatLogger.d(TAG, "WebSocket connected successfully (onOpen)")

                val url = response.request.url.toString()
                if (url.contains("socket.io")) {
                    isSocketIo.set(true)
                    ChatLogger.d(TAG, "Socket.IO transport detected; waiting for handshake packet")
                } else {
                    isSocketIo.set(false)
                    _connectionState.value = ConnectionState.Connected
                    retryAttempt.set(0)
                    activeChatId?.let { joinChat(it) }
                    startPingLoop()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                ChatLogger.d(TAG, "Received frame: $text")

                // Handle Engine.IO / Socket.IO packets
                if (text.startsWith("0")) {
                    // Packet 0: Engine.IO handshake
                    isSocketIo.set(true)
                    val token = sessionManager.getAuthToken()
                    val authPacket = if (!token.isNullOrBlank()) """40{"token":"$token"}""" else "40"
                    webSocket.send(authPacket)
                    return
                }

                if (text.startsWith("40")) {
                    // Packet 40: Socket.IO CONNECT_ACK
                    isSocketIo.set(true)
                    ChatLogger.d(TAG, "Socket.IO connected & authenticated (ACK)")
                    _connectionState.value = ConnectionState.Connected
                    retryAttempt.set(0)
                    activeChatId?.let { joinChat(it) }
                    startPingLoop()
                    return
                }

                if (text == "2") {
                    // Engine.IO PING -> respond with PONG
                    webSocket.send("3")
                    return
                }

                if (text == "3") {
                    // Engine.IO PONG
                    _pongFlow.tryEmit(System.currentTimeMillis())
                    return
                }

                if (text.startsWith("44")) {
                    // Socket.IO Error packet
                    val errorMsg = try {
                        val json = text.substring(2)
                        val obj = gson.fromJson(json, JsonObject::class.java)
                        obj.get("message")?.asString ?: text
                    } catch (_: Exception) { text }
                    ChatLogger.e(TAG, "Socket.IO auth/server error: $errorMsg")
                    _connectionState.value = ConnectionState.Failed(SecurityException(errorMsg), errorMsg)
                    return
                }

                if (text.startsWith("42")) {
                    // Socket.IO Event frame: 42["event", {...}]
                    handleSocketIoEventFrame(text.substring(2))
                    return
                }

                // Fallback: standard / raw WebSocket frame handling
                handleIncomingFrame(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                ChatLogger.d(TAG, "WebSocket closing (code=$code, reason=$reason)")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ChatLogger.d(TAG, "WebSocket closed (code=$code, reason=$reason)")
                stopPingLoop()
                this@WebSocketClientManager.webSocket = null

                if (!isManuallyClosed.get()) {
                    _connectionState.value = ConnectionState.Disconnected
                    scheduleReconnect()
                } else {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ChatLogger.e(TAG, "WebSocket onFailure: code=${response?.code}, message=${t.message}")
                stopPingLoop()
                this@WebSocketClientManager.webSocket = null

                if (response?.code == 401 || response?.code == 403) {
                    val authError = SecurityException("WebSocket authentication failed (${response.code})")
                    _connectionState.value = ConnectionState.Failed(authError, "Unauthorized access")
                    return
                }

                _connectionState.value = ConnectionState.Failed(t)

                // If connecting to /ws failed, switch to Socket.IO endpoint for non-mock remote servers
                val currentWs = sessionManager.getWebSocketUrl()
                if (currentWs.endsWith("/ws") && !currentWs.contains("127.0.0.1") && !currentWs.contains("localhost")) {
                    val fallback = currentWs.removeSuffix("/ws") + "/socket.io/?EIO=4&transport=websocket"
                    sessionManager.setWebSocketUrl(fallback)
                    ChatLogger.d(TAG, "Switching to Socket.IO fallback endpoint: $fallback")
                }

                if (!isManuallyClosed.get()) {
                    scheduleReconnect()
                }
            }
        }
    }

    /**
     * Parse Socket.IO 42["event", data] frame.
     */
    private fun handleSocketIoEventFrame(jsonArrayText: String) {
        try {
            val jsonArray = gson.fromJson(jsonArrayText, JsonArray::class.java) ?: return
            if (jsonArray.size() >= 2) {
                val eventName = jsonArray[0].asString
                val dataElement = jsonArray[1]

                if (dataElement.isJsonObject) {
                    val dataObj = dataElement.asJsonObject
                    val chatMessage = parseMessageFromJson(dataObj, defaultEvent = eventName)
                    if (chatMessage != null) {
                        val emitted = _messageFlow.tryEmit(chatMessage)
                        ChatLogger.d(TAG, "Emitted Socket.IO message [$eventName] to SharedFlow (success=$emitted): id=${chatMessage.id}, text='${chatMessage.message}'")
                    }
                }
            }
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Error handling Socket.IO event frame: $jsonArrayText", e)
        }
    }

    /**
     * Exponential backoff reconnection algorithm:
     * Attempt 1 -> 1s, Attempt 2 -> 2s, Attempt 3 -> 4s, Attempt 4 -> 8s, Attempt 5 -> 16s.
     * Maximum of 5 retries.
     */
    private fun scheduleReconnect() {
        if (isManuallyClosed.get()) return

        val attempt = retryAttempt.incrementAndGet()
        if (attempt > MAX_RETRIES) {
            ChatLogger.w(TAG, "Max reconnection attempts ($MAX_RETRIES) reached. Halting auto-reconnect.")
            _connectionState.value = ConnectionState.Failed(
                IllegalStateException("Reconnection failed after $MAX_RETRIES attempts"),
                "Connection failed. Please check network."
            )
            return
        }

        val delayMillis = BASE_DELAY_MS * (1L shl (attempt - 1))
        ChatLogger.d(TAG, "Scheduling reconnection attempt #$attempt in ${delayMillis}ms")

        _connectionState.value = ConnectionState.Reconnecting(
            attempt = attempt,
            maxAttempts = MAX_RETRIES,
            delayMillis = delayMillis
        )

        cancelReconnect()
        reconnectJob = coroutineScope.launch {
            delay(delayMillis)
            if (!isManuallyClosed.get()) {
                connect(activeChatId)
            }
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun startPingLoop() {
        stopPingLoop()
        pingJob = coroutineScope.launch {
            while (isActive && _connectionState.value is ConnectionState.Connected) {
                delay(PING_INTERVAL_MS)
                if (_connectionState.value is ConnectionState.Connected) {
                    sendPing()
                }
            }
        }
    }

    private fun stopPingLoop() {
        pingJob?.cancel()
        pingJob = null
    }

    /**
     * Socket event: join_chat
     */
    fun joinChat(chatId: Int): Boolean {
        this.activeChatId = chatId
        return if (isSocketIo.get()) {
            val payload = JsonObject().apply {
                addProperty("chatId", chatId)
                addProperty("chat_id", chatId)
            }
            sendFrame("""42["${SocketEvents.JOIN_CHAT}",$payload]""")
        } else {
            val payload = JsonObject().apply {
                addProperty("event", SocketEvents.JOIN_CHAT)
                addProperty("action", SocketEvents.JOIN_CHAT)
                addProperty("chatId", chatId)
                addProperty("chat_id", chatId)
            }
            sendFrame(payload.toString())
        }
    }

    /**
     * Socket event: send_message (TYPE_TEXT)
     */
    fun sendTextMessage(chatId: Int, message: String): Boolean {
        if (message.isBlank()) return false
        val currentUserId = sessionManager.getUserId()

        return if (isSocketIo.get()) {
            val payload = JsonObject().apply {
                addProperty("chatId", chatId)
                addProperty("chat_id", chatId)
                addProperty("senderId", currentUserId)
                addProperty("sender_id", currentUserId)
                addProperty("message", message.trim())
                addProperty("type", ChatMessage.TYPE_TEXT)
                addProperty("timestamp", System.currentTimeMillis())
            }
            sendFrame("""42["${SocketEvents.SEND_MESSAGE}",$payload]""")
        } else {
            val payload = JsonObject().apply {
                addProperty("event", SocketEvents.SEND_MESSAGE)
                addProperty("type", ChatMessage.TYPE_TEXT)
                addProperty("chatId", chatId)
                addProperty("chat_id", chatId)
                addProperty("senderId", currentUserId)
                addProperty("sender_id", currentUserId)
                addProperty("message", message.trim())
                addProperty("timestamp", System.currentTimeMillis())
            }
            sendFrame(payload.toString())
        }
    }

    /**
     * Socket event: send_image (TYPE_IMAGE)
     * Transmits image URL and metadata over WebSocket.
     */
    fun sendImageMessage(
        chatId: Int,
        imageUrl: String,
        caption: String = "",
        metadata: MediaMetadata? = null
    ): Boolean {
        if (imageUrl.isBlank()) return false
        val currentUserId = sessionManager.getUserId()

        return if (isSocketIo.get()) {
            val payload = JsonObject().apply {
                addProperty("chatId", chatId)
                addProperty("chat_id", chatId)
                addProperty("senderId", currentUserId)
                addProperty("sender_id", currentUserId)
                addProperty("message", caption.trim())
                addProperty("mediaUrl", imageUrl)
                addProperty("media_url", imageUrl)
                addProperty("imageUrl", imageUrl)
                addProperty("type", ChatMessage.TYPE_IMAGE)
                addProperty("timestamp", System.currentTimeMillis())
                metadata?.let { add("metadata", gson.toJsonTree(it)) }
            }
            sendFrame("""42["${SocketEvents.SEND_IMAGE}",$payload]""")
        } else {
            val payload = JsonObject().apply {
                addProperty("event", SocketEvents.SEND_IMAGE)
                addProperty("type", ChatMessage.TYPE_IMAGE)
                addProperty("chatId", chatId)
                addProperty("chat_id", chatId)
                addProperty("senderId", currentUserId)
                addProperty("sender_id", currentUserId)
                addProperty("message", caption.trim())
                addProperty("mediaUrl", imageUrl)
                addProperty("media_url", imageUrl)
                addProperty("imageUrl", imageUrl)
                addProperty("image_url", imageUrl)
                addProperty("timestamp", System.currentTimeMillis())
                metadata?.let { add("metadata", gson.toJsonTree(it)) }
            }
            sendFrame(payload.toString())
        }
    }

    fun sendVideoMessage(
        chatId: Int,
        videoUrl: String,
        caption: String = "",
        metadata: MediaMetadata? = null
    ): Boolean {
        if (videoUrl.isBlank()) return false
        val currentUserId = sessionManager.getUserId()

        return if (isSocketIo.get()) {
            val payload = JsonObject().apply {
                addProperty("chatId", chatId)
                addProperty("chat_id", chatId)
                addProperty("senderId", currentUserId)
                addProperty("sender_id", currentUserId)
                addProperty("message", caption.trim())
                addProperty("mediaUrl", videoUrl)
                addProperty("media_url", videoUrl)
                addProperty("imageUrl", videoUrl)
                addProperty("type", com.demo.chat.data.model.ChatMessage.TYPE_VIDEO)
                addProperty("timestamp", System.currentTimeMillis())
                metadata?.let { add("metadata", gson.toJsonTree(it)) }
            }
            sendFrame("""42["send_video",$payload]""")
        } else {
            val payload = JsonObject().apply {
                addProperty("event", "send_video")
                addProperty("type", com.demo.chat.data.model.ChatMessage.TYPE_VIDEO)
                addProperty("chatId", chatId)
                addProperty("chat_id", chatId)
                addProperty("senderId", currentUserId)
                addProperty("sender_id", currentUserId)
                addProperty("message", caption.trim())
                addProperty("mediaUrl", videoUrl)
                addProperty("media_url", videoUrl)
                addProperty("imageUrl", videoUrl)
                addProperty("image_url", videoUrl)
                addProperty("timestamp", System.currentTimeMillis())
                metadata?.let { add("metadata", gson.toJsonTree(it)) }
            }
            sendFrame(payload.toString())
        }
    }

    fun sendAudioMessage(
        chatId: Int,
        audioUrl: String,
        caption: String = "",
        metadata: MediaMetadata? = null
    ): Boolean {
        if (audioUrl.isBlank()) return false
        val currentUserId = sessionManager.getUserId()

        return if (isSocketIo.get()) {
            val payload = JsonObject().apply {
                addProperty("chatId", chatId)
                addProperty("chat_id", chatId)
                addProperty("senderId", currentUserId)
                addProperty("sender_id", currentUserId)
                addProperty("message", caption.trim())
                addProperty("mediaUrl", audioUrl)
                addProperty("media_url", audioUrl)
                addProperty("imageUrl", audioUrl)
                addProperty("type", com.demo.chat.data.model.ChatMessage.TYPE_AUDIO)
                addProperty("timestamp", System.currentTimeMillis())
                metadata?.let { add("metadata", gson.toJsonTree(it)) }
            }
            sendFrame("""42["send_audio",$payload]""")
        } else {
            val payload = JsonObject().apply {
                addProperty("event", "send_audio")
                addProperty("type", com.demo.chat.data.model.ChatMessage.TYPE_AUDIO)
                addProperty("chatId", chatId)
                addProperty("chat_id", chatId)
                addProperty("senderId", currentUserId)
                addProperty("sender_id", currentUserId)
                addProperty("message", caption.trim())
                addProperty("mediaUrl", audioUrl)
                addProperty("media_url", audioUrl)
                addProperty("imageUrl", audioUrl)
                addProperty("image_url", audioUrl)
                addProperty("timestamp", System.currentTimeMillis())
                metadata?.let { add("metadata", gson.toJsonTree(it)) }
            }
            sendFrame(payload.toString())
        }
    }

    /**
     * Socket event: ping_pong
     */
    fun sendPing(): Boolean {
        return if (isSocketIo.get()) {
            sendFrame("2")
        } else {
            val payload = JsonObject().apply {
                addProperty("event", SocketEvents.PING)
                addProperty("timestamp", System.currentTimeMillis())
            }
            sendFrame(payload.toString())
        }
    }

    /**
     * Low-level send method.
     */
    private fun sendFrame(json: String): Boolean {
        val ws = webSocket
        return if (ws != null && _connectionState.value is ConnectionState.Connected) {
            val sent = ws.send(json)
            if (sent) {
                ChatLogger.d(TAG, "Sent WebSocket frame: $json")
            } else {
                ChatLogger.w(TAG, "Failed to send WebSocket frame (queue full)")
            }
            sent
        } else {
            ChatLogger.w(TAG, "Cannot send frame: WebSocket not connected")
            false
        }
    }

    /**
     * Parse incoming WebSocket frame (handles TYPE_TEXT and TYPE_IMAGE)
     * and emit immediately to SharedFlow without blocking caller or UI.
     */
    private fun handleIncomingFrame(text: String) {
        try {
            val jsonObject = gson.fromJson(text, JsonObject::class.java) ?: return

            val event = jsonObject.get("event")?.asString
                ?: jsonObject.get("type")?.asString
                ?: ""

            // Handle ping / pong
            if (event.equals(SocketEvents.PONG, ignoreCase = true) || event.equals("ping_pong", ignoreCase = true)) {
                val timestamp = jsonObject.get("timestamp")?.asLong ?: System.currentTimeMillis()
                _pongFlow.tryEmit(timestamp)
                return
            }

            // Extract message payload
            val messageData = when {
                jsonObject.has("data") && jsonObject.get("data").isJsonObject -> jsonObject.getAsJsonObject("data")
                jsonObject.has("message") && jsonObject.get("message").isJsonObject -> jsonObject.getAsJsonObject("message")
                else -> jsonObject
            }

            val chatMessage = parseMessageFromJson(messageData, defaultEvent = event)
            if (chatMessage != null) {
                val emitted = _messageFlow.tryEmit(chatMessage)
                ChatLogger.d(TAG, "Emitted message [${chatMessage.type}] to SharedFlow (success=$emitted): id=${chatMessage.id}, text='${chatMessage.message}'")
            }
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Error handling incoming WebSocket text frame: $text", e)
        }
    }

    private fun parseMessageFromJson(obj: JsonObject, defaultEvent: String): ChatMessage? {
        return try {
            val messageText = when {
                obj.has("message") && obj.get("message").isJsonPrimitive -> obj.get("message").asString
                obj.has("text") -> obj.get("text").asString
                obj.has("content") -> obj.get("content").asString
                else -> ""
            }

            val imageUrl = when {
                obj.has("media_url") && !obj.get("media_url").isJsonNull -> obj.get("media_url").asString
                obj.has("mediaUrl") && !obj.get("mediaUrl").isJsonNull -> obj.get("mediaUrl").asString
                obj.has("image_url") && !obj.get("image_url").isJsonNull -> obj.get("image_url").asString
                obj.has("imageUrl") && !obj.get("imageUrl").isJsonNull -> obj.get("imageUrl").asString
                obj.has("url") && !obj.get("url").isJsonNull -> obj.get("url").asString
                else -> null
            }

            val mediaType = when {
                obj.has("mediaType") && !obj.get("mediaType").isJsonNull -> obj.get("mediaType").asString.lowercase()
                obj.has("media_type") && !obj.get("media_type").isJsonNull -> obj.get("media_type").asString.lowercase()
                else -> null
            }

            val rawType = obj.get("type")?.asString
            val type = when {
                rawType?.equals(ChatMessage.TYPE_VIDEO, ignoreCase = true) == true || mediaType == "video" -> ChatMessage.TYPE_VIDEO
                rawType?.equals(ChatMessage.TYPE_AUDIO, ignoreCase = true) == true || mediaType == "audio" -> ChatMessage.TYPE_AUDIO
                rawType?.equals(ChatMessage.TYPE_FILE, ignoreCase = true) == true || mediaType == "file" -> ChatMessage.TYPE_FILE
                rawType?.equals(ChatMessage.TYPE_IMAGE, ignoreCase = true) == true || mediaType == "image" -> ChatMessage.TYPE_IMAGE
                defaultEvent.equals(SocketEvents.SEND_IMAGE, ignoreCase = true) -> ChatMessage.TYPE_IMAGE
                !imageUrl.isNullOrBlank() -> ChatMessage.determineTypeFromUrl(imageUrl)
                else -> ChatMessage.TYPE_TEXT
            }

            val id = try { obj.get("id")?.asInt } catch (_: Exception) { null }

            val chatId = try {
                obj.get("chat_id")?.asInt
                    ?: obj.get("chatId")?.asInt
                    ?: obj.get("chatId")?.asString?.toIntOrNull()
                    ?: activeChatId
                    ?: 0
            } catch (_: Exception) {
                obj.get("chatId")?.asString?.toIntOrNull() ?: activeChatId ?: 0
            }

            val senderId = try {
                obj.get("sender_id")?.asInt
                    ?: obj.get("senderId")?.asInt
                    ?: obj.get("userId")?.asInt
                    ?: 0
            } catch (_: Exception) {
                obj.get("senderId")?.asString?.toIntOrNull() ?: 0
            }

            val createdAt = obj.get("created_at")?.asString
                ?: obj.get("createdAt")?.asString
                ?: obj.get("timestamp")?.asString

            val metadata = if (obj.has("metadata") && obj.get("metadata").isJsonObject) {
                gson.fromJson(obj.getAsJsonObject("metadata"), MediaMetadata::class.java)
            } else null

            val sender = if (obj.has("sender") && obj.get("sender").isJsonObject) {
                gson.fromJson(obj.getAsJsonObject("sender"), User::class.java)
            } else null

            ChatMessage(
                id = id,
                chatId = chatId,
                senderId = senderId,
                message = messageText,
                type = type,
                mediaUrl = imageUrl,
                mediaType = mediaType,
                metadata = metadata,
                createdAt = createdAt,
                sender = sender,
                status = ChatMessage.STATUS_SENT
            )
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Error deserializing ChatMessage", e)
            null
        }
    }

    /**
     * Gracefully disconnect and cancel all active timers.
     */
    @Synchronized
    fun disconnect() {
        isManuallyClosed.set(true)
        cancelReconnect()
        stopPingLoop()

        webSocket?.close(1000, "Client disconnected")
        webSocket?.cancel()
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
        ChatLogger.d(TAG, "WebSocket disconnected by client")
    }

    companion object {
        private const val TAG = "WebSocketClientManager"
        const val MAX_RETRIES = 5
        const val BASE_DELAY_MS = 1000L
        const val PING_INTERVAL_MS = 25000L
    }
}
