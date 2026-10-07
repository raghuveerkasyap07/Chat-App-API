package com.demo.chat.data.remote

import com.demo.chat.data.local.SessionManager
import com.demo.chat.data.model.*
import com.demo.chat.utils.ChatLogger
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChatRepository(
    private val apiService: ChatApiService,
    private val sessionManager: SessionManager,
    private val gson: Gson = Gson()
) {

    /**
     * GET /api/users: Fetch registered contacts.
     */
    suspend fun getUsers(search: String? = null): Result<List<User>> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getUsers(search)
            if (response.isSuccessful) {
                val body = response.body()
                val users = parseUserList(body?.data)
                Result.success(users)
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string())
                Result.failure(Exception("Failed to fetch users (${response.code()}): $errorMsg"))
            }
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Exception fetching users", e)
            Result.failure(e)
        }
    }

    /**
     * GET /api/users/{id}: Fetch user profile by ID.
     */
    suspend fun getUserById(userId: Int): Result<User> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getUserById(userId)
            if (response.isSuccessful) {
                val body = response.body()
                val user = parseSingleObject(body?.data, User::class.java)
                if (user != null) {
                    Result.success(user)
                } else {
                    Result.failure(IllegalStateException("Failed to parse user details"))
                }
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string())
                Result.failure(Exception("Failed to fetch user by ID (${response.code()}): $errorMsg"))
            }
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Exception fetching user by ID", e)
            Result.failure(e)
        }
    }

    /**
     * POST /api/chats: Create or retrieve 1-on-1 chat room (chatId).
     */
    suspend fun createOrGetChat(recipientId: Int): Result<Chat> = withContext(Dispatchers.IO) {
        try {
            if (recipientId <= 0) {
                return@withContext Result.failure(IllegalArgumentException("Invalid recipientId: $recipientId"))
            }

            val request = CreateChatRequest(recipientId = recipientId)
            val response = apiService.createOrGetChat(request)

            if (response.isSuccessful) {
                val body = response.body()
                val chat = parseChatResponse(body?.data)
                if (chat != null) {
                    ChatLogger.d(TAG, "Chat created or retrieved with ID: ${chat.id}")
                    Result.success(chat)
                } else {
                    Result.failure(IllegalStateException(body?.message ?: "Failed to parse chat response"))
                }
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string())
                Result.failure(Exception("Failed to create/get chat (${response.code()}): $errorMsg"))
            }
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Exception creating/getting chat", e)
            Result.failure(e)
        }
    }

    /**
     * GET /api/chats: Fetch active conversation list for the current user.
     */
    suspend fun getConversations(): Result<List<Chat>> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getConversations()
            if (response.isSuccessful) {
                val body = response.body()
                val chats = parseChatList(body?.data)
                Result.success(chats)
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string())
                Result.failure(Exception("Failed to fetch conversations (${response.code()}): $errorMsg"))
            }
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Exception fetching conversations", e)
            Result.failure(e)
        }
    }

    /**
     * GET /api/chats/{chatId}: Get single conversation details.
     */
    suspend fun getChatDetails(chatId: Int): Result<Chat> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getChatDetails(chatId)
            if (response.isSuccessful) {
                val body = response.body()
                val chat = parseChatResponse(body?.data)
                if (chat != null) {
                    Result.success(chat)
                } else {
                    Result.failure(IllegalStateException("Failed to parse chat details"))
                }
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string())
                Result.failure(Exception("Failed to fetch chat details (${response.code()}): $errorMsg"))
            }
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Exception fetching chat details", e)
            Result.failure(e)
        }
    }

    /**
     * GET /api/chats/{chatId}/messages: Fetch paginated chat history.
     */
    suspend fun getChatMessages(
        chatId: Int,
        page: Int = 1,
        limit: Int = 50
    ): Result<List<ChatMessage>> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getChatMessages(chatId = chatId, page = page, limit = limit)
            if (response.isSuccessful) {
                val body = response.body()
                val messages = parseMessageList(body?.data, fallbackChatId = chatId)
                Result.success(messages)
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string())
                Result.failure(Exception("Failed to fetch chat messages (${response.code()}): $errorMsg"))
            }
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Exception fetching chat messages", e)
            Result.failure(e)
        }
    }

    /**
     * POST /api/chats/{chatId}/messages: Send text message via REST endpoint.
     */
    suspend fun sendTextMessage(
        chatId: Int,
        message: String
    ): Result<ChatMessage> = withContext(Dispatchers.IO) {
        try {
            if (message.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Message cannot be empty"))
            }

            val request = SendMessageRequest(message = message.trim(), type = ChatMessage.TYPE_TEXT)
            val response = apiService.sendMessage(chatId = chatId, request = request)

            if (response.isSuccessful) {
                val body = response.body()
                val chatMessage = parseSingleObject(body?.data, ChatMessage::class.java)
                    ?: ChatMessage(
                        chatId = chatId,
                        senderId = sessionManager.getUserId(),
                        message = message,
                        type = ChatMessage.TYPE_TEXT
                    )
                Result.success(chatMessage)
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string())
                Result.failure(Exception("Failed to send message (${response.code()}): $errorMsg"))
            }
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Exception sending message via REST", e)
            Result.failure(e)
        }
    }

    // --- Parsing Helpers ---

    private fun parseUserList(jsonElement: JsonElement?): List<User> {
        if (jsonElement == null) return emptyList()
        return try {
            if (jsonElement.isJsonArray) {
                val type = object : TypeToken<List<User>>() {}.type
                gson.fromJson(jsonElement, type)
            } else if (jsonElement.isJsonObject) {
                val obj = jsonElement.asJsonObject
                val array = when {
                    obj.has("users") && obj.get("users").isJsonArray -> obj.getAsJsonArray("users")
                    obj.has("items") && obj.get("items").isJsonArray -> obj.getAsJsonArray("items")
                    else -> null
                }
                if (array != null) {
                    val type = object : TypeToken<List<User>>() {}.type
                    gson.fromJson(array, type)
                } else emptyList()
            } else emptyList()
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Error parsing user list", e)
            emptyList()
        }
    }

    private fun parseChatResponse(jsonElement: JsonElement?): Chat? {
        if (jsonElement == null) return null
        return try {
            if (jsonElement.isJsonObject) {
                val obj = jsonElement.asJsonObject
                if (obj.has("chat") && obj.get("chat").isJsonObject) {
                    gson.fromJson(obj.get("chat"), Chat::class.java)
                } else if (obj.has("id")) {
                    gson.fromJson(obj, Chat::class.java)
                } else null
            } else null
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Error parsing chat response", e)
            null
        }
    }

    private fun parseChatList(jsonElement: JsonElement?): List<Chat> {
        if (jsonElement == null) return emptyList()
        return try {
            if (jsonElement.isJsonArray) {
                val type = object : TypeToken<List<Chat>>() {}.type
                gson.fromJson(jsonElement, type)
            } else if (jsonElement.isJsonObject) {
                val obj = jsonElement.asJsonObject
                val array = when {
                    obj.has("chats") && obj.get("chats").isJsonArray -> obj.getAsJsonArray("chats")
                    obj.has("conversations") && obj.get("conversations").isJsonArray -> obj.getAsJsonArray("conversations")
                    obj.has("items") && obj.get("items").isJsonArray -> obj.getAsJsonArray("items")
                    else -> null
                }
                if (array != null) {
                    val type = object : TypeToken<List<Chat>>() {}.type
                    gson.fromJson(array, type)
                } else emptyList()
            } else emptyList()
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Error parsing chat list", e)
            emptyList()
        }
    }

    private fun parseMessageList(jsonElement: JsonElement?, fallbackChatId: Int): List<ChatMessage> {
        if (jsonElement == null) return emptyList()
        return try {
            val jsonArray: JsonArray? = when {
                jsonElement.isJsonArray -> jsonElement.asJsonArray
                jsonElement.isJsonObject -> {
                    val obj = jsonElement.asJsonObject
                    when {
                        obj.has("messages") && obj.get("messages").isJsonArray -> obj.getAsJsonArray("messages")
                        obj.has("items") && obj.get("items").isJsonArray -> obj.getAsJsonArray("items")
                        obj.has("data") && obj.get("data").isJsonArray -> obj.getAsJsonArray("data")
                        else -> null
                    }
                }
                else -> null
            }

            if (jsonArray != null) {
                val list = mutableListOf<ChatMessage>()
                for (item in jsonArray) {
                    try {
                        val msg = gson.fromJson(item, ChatMessage::class.java)
                        if (msg != null) {
                            val sanitizedMsg = if (msg.chatId == 0) msg.copy(chatId = fallbackChatId) else msg
                            list.add(sanitizedMsg)
                        }
                    } catch (e: Exception) {
                        ChatLogger.e(TAG, "Error parsing message element", e)
                    }
                }
                list
            } else emptyList()
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Error parsing message list", e)
            emptyList()
        }
    }

    private fun <T> parseSingleObject(jsonElement: JsonElement?, clazz: Class<T>): T? {
        if (jsonElement == null || !jsonElement.isJsonObject) return null
        return try {
            gson.fromJson(jsonElement, clazz)
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Error parsing single object of ${clazz.simpleName}", e)
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
        private const val TAG = "ChatRepository"
    }
}
