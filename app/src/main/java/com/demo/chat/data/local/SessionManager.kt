package com.demo.chat.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.demo.chat.data.model.User
import com.demo.chat.utils.ChatLogger
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionManager(
    context: Context,
    customPrefs: SharedPreferences? = null
) {

    private val prefs: SharedPreferences = customPrefs ?: try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Throwable) {
        ChatLogger.w(TAG, "EncryptedSharedPreferences failed, falling back to standard SharedPreferences", e)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val gson = Gson()

    private val _authTokenFlow = MutableStateFlow<String?>(prefs.getString(KEY_AUTH_TOKEN, null))
    val authTokenFlow: StateFlow<String?> = _authTokenFlow.asStateFlow()

    fun saveAuthToken(token: String) {
        prefs.edit().putString(KEY_AUTH_TOKEN, token).apply()
        _authTokenFlow.value = token
        ChatLogger.d(TAG, "AuthToken successfully persisted in SessionManager")
    }

    fun getAuthToken(): String? {
        return prefs.getString(KEY_AUTH_TOKEN, null)
    }

    fun hasValidToken(): Boolean {
        val token = getAuthToken()
        return !token.isNullOrBlank()
    }

    fun saveUser(user: User) {
        val json = gson.toJson(user)
        prefs.edit()
            .putString(KEY_USER_DATA, json)
            .putInt(KEY_USER_ID, user.id)
            .putString(KEY_USER_NAME, user.name)
            .putString(KEY_USER_EMAIL, user.email)
            .apply()
        ChatLogger.d(TAG, "User data persisted: ${user.name} (id=${user.id})")
    }

    fun getUser(): User? {
        val json = prefs.getString(KEY_USER_DATA, null) ?: return null
        return try {
            gson.fromJson(json, User::class.java)
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Error deserializing stored user", e)
            null
        }
    }

    fun getUserId(): Int {
        val user = getUser()
        return user?.id ?: prefs.getInt(KEY_USER_ID, -1)
    }

    fun setBaseUrl(url: String) {
        val sanitized = if (url.endsWith("/")) url.dropLast(1) else url
        prefs.edit()
            .putString(KEY_BASE_URL, sanitized)
            .remove(KEY_WS_URL) // Reset cached WS URL so it re-derives from new base URL
            .apply()
    }

    fun getBaseUrl(): String {
        return prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    }

    fun setWebSocketUrl(url: String) {
        prefs.edit().putString(KEY_WS_URL, url).apply()
    }

    fun getWebSocketUrl(): String {
        val storedWs = prefs.getString(KEY_WS_URL, null)
        if (!storedWs.isNullOrBlank() && (!storedWs.contains("192.168.") || storedWs.contains("socket.io"))) return storedWs

        val httpBase = getBaseUrl()
        val wsScheme = if (httpBase.startsWith("https://")) "wss://" else "ws://"
        val cleanHost = httpBase.removePrefix("https://").removePrefix("http://").trimEnd('/')

        return if (cleanHost.contains("192.168.") || cleanHost.contains("socket.io")) {
            "$wsScheme$cleanHost/socket.io/?EIO=4&transport=websocket"
        } else {
            "$wsScheme$cleanHost/ws"
        }
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_USER_DATA)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_NAME)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_WS_URL)
            .apply()
        _authTokenFlow.value = null
        ChatLogger.d(TAG, "Session cleared")
    }

    companion object {
        private const val TAG = "SessionManager"
        private const val PREFS_NAME = "chat_session_prefs"
        private const val KEY_AUTH_TOKEN = "jwt_auth_token"
        private const val KEY_USER_DATA = "user_json_data"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_BASE_URL = "api_base_url"
        private const val KEY_WS_URL = "ws_endpoint_url"

        const val DEFAULT_BASE_URL = "http://192.168.0.14:5000"
    }
}
