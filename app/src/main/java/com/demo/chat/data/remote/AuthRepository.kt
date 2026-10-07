package com.demo.chat.data.remote

import com.demo.chat.data.local.SessionManager
import com.demo.chat.data.model.AuthData
import com.demo.chat.data.model.LoginRequest
import com.demo.chat.data.model.RegisterRequest
import com.demo.chat.data.model.User
import com.demo.chat.utils.ChatLogger
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthRepository(
    private val apiService: ChatApiService,
    private val sessionManager: SessionManager,
    private val gson: Gson = Gson()
) {

    suspend fun register(
        name: String,
        email: String,
        password: String
    ): Result<AuthData> = withContext(Dispatchers.IO) {
        try {
            if (name.isBlank() || email.isBlank() || password.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Name, email and password cannot be blank"))
            }

            val request = RegisterRequest(name = name.trim(), email = email.trim(), password = password)
            val response = apiService.register(request)

            if (response.isSuccessful) {
                val body = response.body()
                val authData = parseAuthData(body?.data)

                if (authData?.token != null) {
                    sessionManager.saveAuthToken(authData.token)
                    authData.user?.let { sessionManager.saveUser(it) }
                    ChatLogger.d(TAG, "Registration successful for: $email, token saved")
                    Result.success(authData)
                } else {
                    Result.failure(IllegalStateException(body?.message ?: "Missing token in register response"))
                }
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string())
                Result.failure(Exception("Registration failed (${response.code()}): $errorMsg"))
            }
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Exception during register", e)
            Result.failure(e)
        }
    }

    suspend fun login(
        email: String,
        password: String
    ): Result<AuthData> = withContext(Dispatchers.IO) {
        try {
            if (email.isBlank() || password.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Email and password cannot be blank"))
            }

            val request = LoginRequest(email = email.trim(), password = password)
            val response = apiService.login(request)

            if (response.isSuccessful) {
                val body = response.body()
                val authData = parseAuthData(body?.data)

                if (authData?.token != null) {
                    sessionManager.saveAuthToken(authData.token)
                    authData.user?.let { sessionManager.saveUser(it) }
                    ChatLogger.d(TAG, "Login successful for: $email, token saved")
                    Result.success(authData)
                } else {
                    Result.failure(IllegalStateException(body?.message ?: "Missing token in login response"))
                }
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string())
                Result.failure(Exception("Login failed (${response.code()}): $errorMsg"))
            }
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Exception during login", e)
            Result.failure(e)
        }
    }

    suspend fun getCurrentUser(): Result<User> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getCurrentUser()
            if (response.isSuccessful) {
                val body = response.body()
                val user = parseUser(body?.data)
                if (user != null) {
                    sessionManager.saveUser(user)
                    Result.success(user)
                } else {
                    Result.failure(IllegalStateException("Failed to parse user profile"))
                }
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string())
                Result.failure(Exception("Get profile failed (${response.code()}): $errorMsg"))
            }
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Exception during getCurrentUser", e)
            Result.failure(e)
        }
    }

    fun isLoggedIn(): Boolean = sessionManager.hasValidToken()

    fun getAuthToken(): String? = sessionManager.getAuthToken()

    fun getCachedUser(): User? = sessionManager.getUser()

    fun logout() {
        sessionManager.clearSession()
        ChatLogger.d(TAG, "User logged out, session cleared")
    }

    private fun parseAuthData(jsonElement: Any?): AuthData? {
        if (jsonElement == null) return null
        return try {
            val jsonString = if (jsonElement is String) jsonElement else gson.toJson(jsonElement)
            val jsonObject = gson.fromJson(jsonString, JsonObject::class.java)

            val token = when {
                jsonObject.has("token") -> jsonObject.get("token").asString
                jsonObject.has("authToken") -> jsonObject.get("authToken").asString
                jsonObject.has("jwt") -> jsonObject.get("jwt").asString
                else -> null
            }

            val user = when {
                jsonObject.has("user") -> gson.fromJson(jsonObject.get("user"), User::class.java)
                else -> null
            }

            AuthData(token = token, user = user)
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Error parsing auth data", e)
            null
        }
    }

    private fun parseUser(jsonElement: Any?): User? {
        if (jsonElement == null) return null
        return try {
            val jsonString = if (jsonElement is String) jsonElement else gson.toJson(jsonElement)
            gson.fromJson(jsonString, User::class.java)
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Error parsing user", e)
            null
        }
    }

    private fun parseErrorMessage(errorBody: String?): String {
        if (errorBody.isNullOrBlank()) return "Unknown server error"
        return try {
            val json = gson.fromJson(errorBody, JsonObject::class.java)
            json.get("message")?.asString
                ?: json.get("error")?.asString
                ?: errorBody
        } catch (e: Exception) {
            errorBody
        }
    }

    companion object {
        private const val TAG = "AuthRepository"
    }
}
