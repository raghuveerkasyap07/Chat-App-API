package com.demo.chat

import com.demo.chat.data.local.SessionManager
import com.demo.chat.data.model.ChatMessage
import com.demo.chat.data.model.User
import com.demo.chat.data.remote.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File

class RepositoriesTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var sessionManager: SessionManager
    private lateinit var apiService: ChatApiService
    private lateinit var authRepository: AuthRepository
    private lateinit var chatRepository: ChatRepository
    private lateinit var mediaRepository: MediaRepository

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val context = TestContext()
        sessionManager = SessionManager(context, InMemorySharedPreferences())
        sessionManager.setBaseUrl(mockWebServer.url("/").toString())

        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(ChatApiService::class.java)
        authRepository = AuthRepository(apiService, sessionManager)
        chatRepository = ChatRepository(apiService, sessionManager)
        mediaRepository = MediaRepository(apiService, sessionManager)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun testAuthRegisterSuccessAndPersistsToken() = runBlocking {
        val registerJson = """
            {
                "success": true,
                "message": "User registered successfully",
                "data": {
                    "token": "jwt_sample_token_abc_123",
                    "user": {
                        "id": 1,
                        "name": "Alice",
                        "email": "alice@example.com"
                    }
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(201).setBody(registerJson))

        val result = authRepository.register("Alice", "alice@example.com", "Password123")
        assertTrue(result.isSuccess)

        val authData = result.getOrThrow()
        assertEquals("jwt_sample_token_abc_123", authData.token)
        assertEquals("Alice", authData.user?.name)

        // Verify token and user are persisted in SessionManager
        assertEquals("jwt_sample_token_abc_123", sessionManager.getAuthToken())
        assertEquals(1, sessionManager.getUser()?.id)
        assertTrue(authRepository.isLoggedIn())

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("/api/auth/register", recordedRequest.path)
        assertEquals("POST", recordedRequest.method)
    }

    @Test
    fun testAuthLoginSuccessAndPersistsToken() = runBlocking {
        val loginJson = """
            {
                "success": true,
                "message": "Login successful",
                "data": {
                    "token": "jwt_login_token_xyz_789",
                    "user": {
                        "id": 2,
                        "name": "Bob",
                        "email": "bob@example.com"
                    }
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(loginJson))

        val result = authRepository.login("bob@example.com", "Password123")
        assertTrue(result.isSuccess)

        assertEquals("jwt_login_token_xyz_789", sessionManager.getAuthToken())
        assertEquals(2, sessionManager.getUser()?.id)

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("/api/auth/login", recordedRequest.path)
    }

    @Test
    fun testGetUsers() = runBlocking {
        val usersJson = """
            {
                "success": true,
                "data": [
                    { "id": 2, "name": "Bob", "email": "bob@example.com" },
                    { "id": 3, "name": "Charlie", "email": "charlie@example.com" }
                ]
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(usersJson))

        val result = chatRepository.getUsers()
        assertTrue(result.isSuccess)
        val users = result.getOrThrow()
        assertEquals(2, users.size)
        assertEquals("Bob", users[0].name)
        assertEquals("Charlie", users[1].name)

        val recordedRequest = mockWebServer.takeRequest()
        assertTrue(recordedRequest.path!!.startsWith("/api/users"))
    }

    @Test
    fun testCreateOrGetChat() = runBlocking {
        val chatJson = """
            {
                "success": true,
                "data": {
                    "chat": {
                        "id": 101,
                        "user1_id": 1,
                        "user2_id": 2,
                        "partner": { "id": 2, "name": "Bob", "email": "bob@example.com" }
                    }
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(chatJson))

        val result = chatRepository.createOrGetChat(recipientId = 2)
        assertTrue(result.isSuccess)
        val chat = result.getOrThrow()
        assertEquals(101, chat.id)
        assertEquals("Bob", chat.partner?.name)

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("/api/chats", recordedRequest.path)
        assertEquals("POST", recordedRequest.method)
    }

    @Test
    fun testGetConversations() = runBlocking {
        val conversationsJson = """
            {
                "success": true,
                "data": [
                    {
                        "id": 101,
                        "user1_id": 1,
                        "user2_id": 2,
                        "last_message": {
                            "id": 501,
                            "chat_id": 101,
                            "sender_id": 2,
                            "message": "See you soon!"
                        }
                    }
                ]
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(conversationsJson))

        val result = chatRepository.getConversations()
        assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(1, list.size)
        assertEquals("See you soon!", list[0].lastMessage?.message)
    }

    @Test
    fun testGetChatMessagesPaginated() = runBlocking {
        val messagesJson = """
            {
                "success": true,
                "data": [
                    {
                        "id": 1,
                        "chat_id": 101,
                        "sender_id": 1,
                        "message": "Hey Bob!",
                        "type": "TYPE_TEXT"
                    },
                    {
                        "id": 2,
                        "chat_id": 101,
                        "sender_id": 2,
                        "message": "Here is the picture",
                        "type": "TYPE_IMAGE",
                        "media_url": "http://localhost:5000/uploads/pic.jpg"
                    }
                ]
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(messagesJson))

        val result = chatRepository.getChatMessages(chatId = 101, page = 1, limit = 50)
        assertTrue(result.isSuccess)
        val messages = result.getOrThrow()
        assertEquals(2, messages.size)
        assertEquals(ChatMessage.TYPE_TEXT, messages[0].type)
        assertEquals(ChatMessage.TYPE_IMAGE, messages[1].type)
        assertEquals("http://localhost:5000/uploads/pic.jpg", messages[1].mediaUrl)
        assertTrue(messages[1].isImage)
    }

    @Test
    fun testMultipartMediaUpload() = runBlocking {
        val uploadResponseJson = """
            {
                "success": true,
                "message": "Photo uploaded successfully",
                "data": {
                    "url": "http://localhost:5000/uploads/test_photo.jpg",
                    "file_name": "test_photo.jpg",
                    "file_size": 2048,
                    "mime_type": "image/jpeg",
                    "width": 800,
                    "height": 600
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(uploadResponseJson))

        // Create a temporary file to upload
        val tempFile = File.createTempFile("sample_img", ".jpg").apply {
            writeBytes(ByteArray(2048) { 1.toByte() })
            deleteOnExit()
        }

        val result = mediaRepository.uploadPhoto(chatId = 101, file = tempFile, mimeType = "image/jpeg", description = "Sample test photo")
        assertTrue(result.isSuccess)

        val uploadData = result.getOrThrow()
        assertEquals("http://localhost:5000/uploads/test_photo.jpg", uploadData.url)
        assertEquals("test_photo.jpg", uploadData.fileName)
        assertEquals(2048L, uploadData.fileSize)
        assertEquals("image/jpeg", uploadData.mimeType)
        assertNotNull(uploadData.metadata)
        assertEquals(800, uploadData.metadata?.width)

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("/api/chats/101/attachments", recordedRequest.path)
        assertEquals("POST", recordedRequest.method)
        assertTrue(recordedRequest.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
    }

    @Test
    fun testMultipartVideoAndAudioUpload() = runBlocking {
        val videoResponseJson = """
            {
                "success": true,
                "message": "Video uploaded successfully",
                "mediaUrl": "http://localhost:5000/uploads/test_video.mp4",
                "mediaType": "video",
                "data": {
                    "url": "http://localhost:5000/uploads/test_video.mp4",
                    "file_name": "test_video.mp4",
                    "file_size": 1048576,
                    "mime_type": "video/mp4",
                    "media_type": "video"
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(videoResponseJson))

        val tempVideoFile = File.createTempFile("sample_video", ".mp4").apply {
            writeBytes(ByteArray(1024) { 2.toByte() })
            deleteOnExit()
        }

        val videoResult = mediaRepository.uploadVideo(chatId = 101, file = tempVideoFile)
        assertTrue(videoResult.isSuccess)
        val videoData = videoResult.getOrThrow()
        assertEquals("http://localhost:5000/uploads/test_video.mp4", videoData.url)
        assertEquals("video", videoData.mediaType)

        val audioResponseJson = """
            {
                "success": true,
                "message": "Audio uploaded successfully",
                "mediaUrl": "http://localhost:5000/uploads/test_audio.mp3",
                "mediaType": "audio",
                "data": {
                    "url": "http://localhost:5000/uploads/test_audio.mp3",
                    "file_name": "test_audio.mp3",
                    "file_size": 512,
                    "mime_type": "audio/mpeg",
                    "media_type": "audio"
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(audioResponseJson))

        val tempAudioFile = File.createTempFile("sample_audio", ".mp3").apply {
            writeBytes(ByteArray(512) { 3.toByte() })
            deleteOnExit()
        }

        val audioResult = mediaRepository.uploadAudio(chatId = 101, file = tempAudioFile)
        assertTrue(audioResult.isSuccess)
        val audioData = audioResult.getOrThrow()
        assertEquals("http://localhost:5000/uploads/test_audio.mp3", audioData.url)
        assertEquals("audio", audioData.mediaType)
    }

    @Test
    fun testChatMessageMediaTypeClassification() {
        val videoMsg = ChatMessage(mediaUrl = "http://example.com/uploads/clip.mp4")
        assertTrue(videoMsg.isVideo)
        assertFalse(videoMsg.isImage)
        assertFalse(videoMsg.isAudio)
        assertFalse(videoMsg.isFile)
        assertEquals(ChatMessage.TYPE_VIDEO, videoMsg.effectiveType)

        val audioMsg = ChatMessage(mediaUrl = "http://example.com/uploads/recording.mp3")
        assertTrue(audioMsg.isAudio)
        assertFalse(audioMsg.isImage)
        assertFalse(audioMsg.isVideo)
        assertFalse(audioMsg.isFile)
        assertEquals(ChatMessage.TYPE_AUDIO, audioMsg.effectiveType)

        val fileMsg = ChatMessage(mediaUrl = "http://example.com/uploads/document.pdf")
        assertTrue(fileMsg.isFile)
        assertFalse(fileMsg.isImage)
        assertFalse(fileMsg.isVideo)
        assertFalse(fileMsg.isAudio)
        assertEquals(ChatMessage.TYPE_FILE, fileMsg.effectiveType)

        val imageMsg = ChatMessage(mediaUrl = "http://example.com/uploads/photo.jpg")
        assertTrue(imageMsg.isImage)
        assertFalse(imageMsg.isVideo)
        assertFalse(imageMsg.isAudio)
        assertFalse(imageMsg.isFile)
        assertEquals(ChatMessage.TYPE_IMAGE, imageMsg.effectiveType)

        val textMsg = ChatMessage(message = "Hello world")
        assertTrue(textMsg.isText)
        assertFalse(textMsg.isImage)
        assertFalse(textMsg.isVideo)
        assertFalse(textMsg.isAudio)
        assertFalse(textMsg.isFile)
        assertEquals(ChatMessage.TYPE_TEXT, textMsg.effectiveType)
    }
}
