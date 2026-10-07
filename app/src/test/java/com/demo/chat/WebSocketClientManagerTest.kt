package com.demo.chat

import com.demo.chat.data.local.SessionManager
import com.demo.chat.data.model.ChatMessage
import com.demo.chat.data.model.ConnectionState
import com.demo.chat.data.model.MediaMetadata
import com.demo.chat.data.model.User
import com.demo.chat.data.remote.WebSocketClientManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.onSubscription
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebSocketClientManagerTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var sessionManager: SessionManager
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var manager: WebSocketClientManager

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val context = TestContext()
        sessionManager = SessionManager(context, InMemorySharedPreferences())
        sessionManager.saveAuthToken("test_jwt_token_123")
        sessionManager.saveUser(User(id = 1, name = "Tester", email = "test@example.com"))

        val wsUrl = mockWebServer.url("/ws").toString().replace("http://", "ws://")
        sessionManager.setWebSocketUrl(wsUrl)

        okHttpClient = OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        manager = WebSocketClientManager(
            okHttpClient = okHttpClient,
            sessionManager = sessionManager
        )
    }

    @After
    fun tearDown() {
        manager.disconnect()
        try {
            mockWebServer.shutdown()
        } catch (e: Exception) {
            // Ignored during test cleanup
        }
    }

    private fun awaitConnected(manager: WebSocketClientManager, timeoutSec: Long = 5) {
        val latch = CountDownLatch(1)
        val job = CoroutineScope(Dispatchers.Default).launch {
            manager.connectionState.collect { state ->
                if (state is ConnectionState.Connected) {
                    latch.countDown()
                }
            }
        }
        assertTrue("WebSocket failed to reach Connected state within ${timeoutSec}s", latch.await(timeoutSec, TimeUnit.SECONDS))
        job.cancel()
    }

    @Test
    fun testWebSocketConnectAndOpenState() = runBlocking {
        val serverConnectLatch = CountDownLatch(1)
        var serverReceivedHeaders: Headers? = null

        mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverReceivedHeaders = response.request.headers
                serverConnectLatch.countDown()
            }
        }))

        manager.connect(chatId = 101)

        assertTrue(serverConnectLatch.await(5, TimeUnit.SECONDS))
        awaitConnected(manager)

        assertTrue(manager.connectionState.value is ConnectionState.Connected)
        assertEquals("Bearer test_jwt_token_123", serverReceivedHeaders?.get("Authorization"))
    }

    @Test
    fun testSendTextMessageAndJoinChatEvents() = runBlocking {
        val receivedFrames = mutableListOf<String>()
        val framesLatch = CountDownLatch(2) // 1 for join_chat, 1 for send_message

        mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                receivedFrames.add(text)
                framesLatch.countDown()
            }
        }))

        manager.connect(chatId = 101)
        awaitConnected(manager)

        // Send text message
        manager.sendTextMessage(chatId = 101, message = "Hello real-time!")

        assertTrue(framesLatch.await(5, TimeUnit.SECONDS))
        assertEquals(2, receivedFrames.size)

        val gson = Gson()
        val joinObj = gson.fromJson(receivedFrames[0], JsonObject::class.java)
        assertEquals("join_chat", joinObj.get("event").asString)
        assertEquals(101, joinObj.get("chatId").asInt)

        val msgObj = gson.fromJson(receivedFrames[1], JsonObject::class.java)
        assertEquals("send_message", msgObj.get("event").asString)
        assertEquals("TYPE_TEXT", msgObj.get("type").asString)
        assertEquals("Hello real-time!", msgObj.get("message").asString)
        assertEquals(101, msgObj.get("chatId").asInt)
    }

    @Test
    fun testSendImageMessageEventWithMetadata() = runBlocking {
        val receivedFrames = mutableListOf<String>()
        val imageLatch = CountDownLatch(1)

        mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = Gson().fromJson(text, JsonObject::class.java)
                if (obj.get("event")?.asString == "send_image") {
                    receivedFrames.add(text)
                    imageLatch.countDown()
                }
            }
        }))

        manager.connect(chatId = 101)
        awaitConnected(manager)

        val metadata = MediaMetadata(
            fileName = "sunset.jpg",
            fileSize = 4096L,
            mimeType = "image/jpeg",
            width = 1920,
            height = 1080
        )

        manager.sendImageMessage(
            chatId = 101,
            imageUrl = "http://localhost:5000/uploads/sunset.jpg",
            caption = "Nice sunset view",
            metadata = metadata
        )

        assertTrue(imageLatch.await(5, TimeUnit.SECONDS))
        val sentJson = receivedFrames.first()
        val jsonObj = Gson().fromJson(sentJson, JsonObject::class.java)

        assertEquals("send_image", jsonObj.get("event").asString)
        assertEquals("TYPE_IMAGE", jsonObj.get("type").asString)
        assertEquals("http://localhost:5000/uploads/sunset.jpg", jsonObj.get("imageUrl").asString)
        assertEquals("Nice sunset view", jsonObj.get("message").asString)
        assertTrue(jsonObj.has("metadata"))
        val meta = jsonObj.getAsJsonObject("metadata")
        val fileName = if (meta.has("file_name")) meta.get("file_name").asString else meta.get("fileName").asString
        assertEquals("sunset.jpg", fileName)
    }

    @Test
    fun testIncomingTextAndImageFramesEmittedToSharedFlowInstantly() = runBlocking {
        val serverWsRef = java.util.concurrent.atomic.AtomicReference<WebSocket>()
        val readyLatch = CountDownLatch(1)

        mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverWsRef.set(webSocket)
                readyLatch.countDown()
            }
        }))

        // Start collector FIRST so it's ready
        val receivedMessages = java.util.Collections.synchronizedList(mutableListOf<ChatMessage>())
        val messagesLatch = CountDownLatch(2)

        val subscriberStarted = CountDownLatch(1)
        val collectJob = launch(Dispatchers.Default) {
            manager.messageFlow
                .onSubscription {
                    subscriberStarted.countDown()
                }
                .collect { msg ->
                    receivedMessages.add(msg)
                    messagesLatch.countDown()
                }
        }

        // Wait until collector is subscribed to messageFlow
        assertTrue("Collector failed to subscribe", subscriberStarted.await(5, TimeUnit.SECONDS))

        manager.connect(chatId = 101)
        assertTrue(readyLatch.await(5, TimeUnit.SECONDS))
        awaitConnected(manager)

        // Push incoming text frame from server
        val incomingTextJson = """
            {
                "event": "new_message",
                "id": 901,
                "chat_id": 101,
                "sender_id": 2,
                "message": "Hey Alice, received your text!",
                "type": "TYPE_TEXT",
                "created_at": "2026-10-07T10:00:00Z"
            }
        """.trimIndent()

        // Push incoming image frame from server
        val incomingImageJson = """
            {
                "event": "new_message",
                "id": 902,
                "chat_id": 101,
                "sender_id": 2,
                "message": "Check out this image",
                "type": "TYPE_IMAGE",
                "media_url": "http://localhost:5000/uploads/photo902.jpg",
                "created_at": "2026-10-07T10:00:05Z"
            }
        """.trimIndent()

        val serverWs = serverWsRef.get()
        assertNotNull("Server WebSocket must not be null", serverWs)
        serverWs.send(incomingTextJson)
        serverWs.send(incomingImageJson)

        assertTrue("Expected 2 incoming messages in SharedFlow", messagesLatch.await(5, TimeUnit.SECONDS))
        collectJob.cancel()

        assertEquals(2, receivedMessages.size)

        val textMsg = receivedMessages[0]
        assertEquals(901, textMsg.id)
        assertEquals("Hey Alice, received your text!", textMsg.message)
        assertEquals(ChatMessage.TYPE_TEXT, textMsg.type)
        assertTrue(textMsg.isText)

        val imgMsg = receivedMessages[1]
        assertEquals(902, imgMsg.id)
        assertEquals("Check out this image", imgMsg.message)
        assertEquals(ChatMessage.TYPE_IMAGE, imgMsg.type)
        assertEquals("http://localhost:5000/uploads/photo902.jpg", imgMsg.mediaUrl)
        assertTrue(imgMsg.isImage)
    }

    @Test
    fun testPingPongEvent() = runBlocking {
        var serverWs: WebSocket? = null
        val pingReceivedLatch = CountDownLatch(1)
        val pongReceivedLatch = CountDownLatch(1)

        mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverWs = webSocket
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = Gson().fromJson(text, JsonObject::class.java)
                if (obj.get("event")?.asString == "ping") {
                    pingReceivedLatch.countDown()
                    // Send pong back
                    webSocket.send("""{"event":"pong","timestamp":123456789}""")
                }
            }
        }))

        manager.connect(chatId = 101)
        awaitConnected(manager)

        var recordedPong: Long? = null
        val pongJob = launch(Dispatchers.Default) {
            manager.pongFlow.collect { ts ->
                recordedPong = ts
                pongReceivedLatch.countDown()
            }
        }

        manager.sendPing()
        assertTrue(pingReceivedLatch.await(5, TimeUnit.SECONDS))
        assertTrue(pongReceivedLatch.await(5, TimeUnit.SECONDS))

        pongJob.cancel()
        assertEquals(123456789L, recordedPong)
    }
}
