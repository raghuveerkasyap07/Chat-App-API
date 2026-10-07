package com.demo.chat

import com.demo.chat.data.local.SessionManager
import com.demo.chat.data.model.ChatMessage
import com.demo.chat.data.model.ConnectionState
import com.demo.chat.data.remote.AuthRepository
import com.demo.chat.data.remote.ChatRepository
import com.demo.chat.data.remote.MediaRepository
import com.demo.chat.data.remote.WebSocketClientManager
import com.demo.chat.sdk.core.ChatClient
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChatClientIntegrationTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var chatClient: ChatClient
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val context = TestContext()
        sessionManager = SessionManager(context, InMemorySharedPreferences())
        sessionManager.setBaseUrl(mockWebServer.url("/").toString())
        sessionManager.setWebSocketUrl(mockWebServer.url("/ws").toString().replace("http://", "ws://"))

        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val apiService = retrofit.create(com.demo.chat.data.remote.ChatApiService::class.java)
        val authRepo = AuthRepository(apiService, sessionManager)
        val chatRepo = ChatRepository(apiService, sessionManager)
        val mediaRepo = MediaRepository(apiService, sessionManager)
        val socketManager = WebSocketClientManager(
            okHttpClient = OkHttpClient.Builder().build(),
            sessionManager = sessionManager
        )

        val constructor = ChatClient::class.java.getDeclaredConstructor(
            SessionManager::class.java,
            AuthRepository::class.java,
            ChatRepository::class.java,
            MediaRepository::class.java,
            WebSocketClientManager::class.java
        )
        constructor.isAccessible = true
        chatClient = constructor.newInstance(sessionManager, authRepo, chatRepo, mediaRepo, socketManager)
    }

    @After
    fun tearDown() {
        chatClient.disconnectWebSocket()
        mockWebServer.shutdown()
    }

    private fun awaitConnected(client: ChatClient, timeoutSec: Long = 5) {
        val latch = CountDownLatch(1)
        val job = CoroutineScope(Dispatchers.Default).launch {
            client.connectionState.collect { state ->
                if (state is ConnectionState.Connected) {
                    latch.countDown()
                }
            }
        }
        assertTrue("ChatClient failed to reach Connected state within ${timeoutSec}s", latch.await(timeoutSec, TimeUnit.SECONDS))
        job.cancel()
    }

    @Test
    fun testLoginAndTokenPersistenceAcrossAppLaunches() = runBlocking {
        val loginResponseJson = """
            {
                "success": true,
                "data": {
                    "token": "persisted_jwt_token_999",
                    "user": {
                        "id": 99,
                        "name": "Raghuveer Tester",
                        "email": "raghuveer@example.com"
                    }
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(loginResponseJson))

        val result = chatClient.login("raghuveer@example.com", "Password123")
        assertTrue(result.isSuccess)

        // Assert token is saved
        assertTrue(chatClient.isLoggedIn())
        assertEquals("persisted_jwt_token_999", chatClient.getAuthToken())

        // Even across app launches, token persists in SessionManager
        assertEquals("persisted_jwt_token_999", sessionManager.getAuthToken())
        assertEquals("Raghuveer Tester", chatClient.getCachedUser()?.name)
    }

    @Test
    fun testMultipartPhotoUploadAndTransmitOverWebSocket() = runBlocking {
        // 1. Set up WebSocket server upgrade
        val socketReceivedFrames = mutableListOf<String>()
        val socketFrameLatch = CountDownLatch(1)

        mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = Gson().fromJson(text, JsonObject::class.java)
                if (obj.get("event")?.asString == "send_image") {
                    socketReceivedFrames.add(text)
                    socketFrameLatch.countDown()
                }
            }
        }))

        // Connect WebSocket first
        chatClient.connectWebSocket(chatId = 101)
        awaitConnected(chatClient)

        // 2. Enqueue MockWebServer response for the HTTP multipart upload
        val uploadResponseJson = """
            {
                "success": true,
                "data": {
                    "url": "http://localhost:5000/uploads/my_photo.jpg",
                    "file_name": "my_photo.jpg",
                    "file_size": 1024,
                    "mime_type": "image/jpeg",
                    "width": 640,
                    "height": 480
                }
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(uploadResponseJson))

        // Create sample photo file
        val tempPhoto = File.createTempFile("camera_upload", ".jpg").apply {
            writeBytes(ByteArray(1024) { 0xFF.toByte() })
            deleteOnExit()
        }

        // Execute combined flow: upload photo and transmit URL over WebSocket
        val sendResult = chatClient.uploadPhotoAndSendOverSocket(
            chatId = 101,
            imageFile = tempPhoto,
            caption = "Here is my diagnosis photo"
        )

        assertTrue(sendResult.isSuccess)
        val chatMessage = sendResult.getOrThrow()

        // 1. Verify returned ChatMessage
        assertEquals(101, chatMessage.chatId)
        assertEquals("Here is my diagnosis photo", chatMessage.message)
        assertEquals(ChatMessage.TYPE_IMAGE, chatMessage.type)
        assertEquals("http://localhost:5000/uploads/my_photo.jpg", chatMessage.mediaUrl)
        assertTrue(chatMessage.isImage)

        // 2. Verify WebSocket transmission
        assertTrue("Expected image frame over WebSocket", socketFrameLatch.await(5, TimeUnit.SECONDS))
        val wsFrameJson = socketReceivedFrames.first()
        val json = Gson().fromJson(wsFrameJson, JsonObject::class.java)

        assertEquals("send_image", json.get("event").asString)
        assertEquals("TYPE_IMAGE", json.get("type").asString)
        assertEquals("http://localhost:5000/uploads/my_photo.jpg", json.get("imageUrl").asString)
        assertEquals("Here is my diagnosis photo", json.get("message").asString)
    }
}
