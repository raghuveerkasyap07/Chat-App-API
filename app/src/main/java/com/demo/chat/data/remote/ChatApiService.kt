package com.demo.chat.data.remote

import com.demo.chat.data.model.*
import com.google.gson.JsonElement
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface ChatApiService {

    @GET("api/health")
    suspend fun healthCheck(): Response<JsonElement>

    // --- Auth Endpoints ---
    @POST("api/auth/register")
    suspend fun register(
        @Body request: RegisterRequest
    ): Response<ApiResponse<JsonElement>>

    @POST("api/auth/login")
    suspend fun login(
        @Body request: LoginRequest
    ): Response<ApiResponse<JsonElement>>

    @GET("api/auth/me")
    suspend fun getCurrentUser(): Response<ApiResponse<JsonElement>>

    // --- Users Endpoints ---
    @GET("api/users")
    suspend fun getUsers(
        @Query("search") search: String? = null
    ): Response<ApiResponse<JsonElement>>

    @GET("api/users/{id}")
    suspend fun getUserById(
        @Path("id") id: Int
    ): Response<ApiResponse<JsonElement>>

    // --- Chats Endpoints ---
    @POST("api/chats")
    suspend fun createOrGetChat(
        @Body request: CreateChatRequest
    ): Response<ApiResponse<JsonElement>>

    @GET("api/chats")
    suspend fun getConversations(): Response<ApiResponse<JsonElement>>

    @GET("api/chats/{chatId}")
    suspend fun getChatDetails(
        @Path("chatId") chatId: Int
    ): Response<ApiResponse<JsonElement>>

    // --- Messages Endpoints ---
    @GET("api/chats/{chatId}/messages")
    suspend fun getChatMessages(
        @Path("chatId") chatId: Int,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50
    ): Response<ApiResponse<JsonElement>>

    @POST("api/chats/{chatId}/messages")
    suspend fun sendMessage(
        @Path("chatId") chatId: Int,
        @Body request: SendMessageRequest
    ): Response<ApiResponse<JsonElement>>

    @PUT("api/chats/{chatId}/seen")
    suspend fun markChatSeen(
        @Path("chatId") chatId: Int
    ): Response<ApiResponse<JsonElement>>

    // --- Media Upload (Multipart) ---
    @Multipart
    @POST("api/chats/{chatId}/attachments")
    suspend fun uploadAttachment(
        @Path("chatId") chatId: Int,
        @Part file: MultipartBody.Part,
        @Part("message") message: RequestBody? = null,
        @Part("description") description: RequestBody? = null,
        @Part("type") type: RequestBody? = null
    ): Response<ApiResponse<JsonElement>>
}
