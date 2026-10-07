package com.demo.chat

import com.demo.chat.data.local.SessionManager
import com.demo.chat.data.model.User
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SessionManagerTest {

    private lateinit var memoryPrefs: InMemorySharedPreferences
    private lateinit var mockContext: TestContext
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        memoryPrefs = InMemorySharedPreferences()
        mockContext = TestContext()
        sessionManager = SessionManager(mockContext, memoryPrefs)
    }

    @Test
    fun testSaveAndRetrieveAuthToken() {
        val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test"
        sessionManager.saveAuthToken(token)

        assertTrue(sessionManager.hasValidToken())
        assertEquals(token, sessionManager.getAuthToken())
    }

    @Test
    fun testSaveAndRetrieveUser() {
        val user = User(id = 42, name = "Alice Tester", email = "alice@example.com")
        sessionManager.saveUser(user)

        val retrieved = sessionManager.getUser()
        assertNotNull(retrieved)
        assertEquals(42, retrieved?.id)
        assertEquals("Alice Tester", retrieved?.name)
        assertEquals("alice@example.com", retrieved?.email)
        assertEquals(42, sessionManager.getUserId())
    }

    @Test
    fun testClearSession() {
        sessionManager.saveAuthToken("some_token")
        sessionManager.saveUser(User(id = 1, name = "Bob", email = "bob@example.com"))

        assertTrue(sessionManager.hasValidToken())

        sessionManager.clearSession()

        assertFalse(sessionManager.hasValidToken())
        assertNull(sessionManager.getAuthToken())
        assertNull(sessionManager.getUser())
    }

    @Test
    fun testBaseUrlAndWebSocketUrl() {
        sessionManager.setBaseUrl("http://localhost:5000")
        assertEquals("http://localhost:5000", sessionManager.getBaseUrl())
        assertEquals("ws://localhost:5000/ws", sessionManager.getWebSocketUrl())

        sessionManager.setBaseUrl("https://api.mychat.com")
        assertEquals("https://api.mychat.com", sessionManager.getBaseUrl())
        assertEquals("wss://api.mychat.com/ws", sessionManager.getWebSocketUrl())
    }
}
