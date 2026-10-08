package com.demo.chat.data.remote

import com.demo.chat.data.local.SessionManager
import com.demo.chat.data.model.AttachmentUploadResponse
import com.demo.chat.data.model.MediaMetadata
import com.demo.chat.utils.ChatLogger
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class MediaRepository(
    private val apiService: ChatApiService,
    private val sessionManager: SessionManager,
    private val gson: Gson = Gson()
) {

    /**
     * POST /api/chats/{chatId}/attachments
     * Multipart photo upload returning image URL and metadata.
     */
    suspend fun uploadPhoto(
        chatId: Int,
        file: File,
        mimeType: String = "image/jpeg",
        description: String? = null
    ): Result<AttachmentUploadResponse> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || !file.canRead()) {
                return@withContext Result.failure(IllegalArgumentException("File does not exist or cannot be read: ${file.absolutePath}"))
            }

            val mediaType = mimeType.toMediaTypeOrNull() ?: "image/*".toMediaTypeOrNull()
            val requestFile = file.asRequestBody(mediaType)
            val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)

            val descBody = description?.toRequestBody("text/plain".toMediaTypeOrNull())
            val typeBody = "TYPE_IMAGE".toRequestBody("text/plain".toMediaTypeOrNull())

            val response = apiService.uploadAttachment(
                chatId = chatId,
                file = filePart,
                message = descBody,
                description = descBody,
                type = typeBody
            )

            if (response.isSuccessful) {
                val body = response.body()
                val parsed = parseAttachmentResponse(body?.data, fallbackFileName = file.name, fallbackFileSize = file.length(), fallbackMime = mimeType)
                if (parsed != null) {
                    ChatLogger.d(TAG, "Uploaded photo successfully: url=${parsed.url}")
                    Result.success(parsed)
                } else {
                    Result.failure(IllegalStateException(body?.message ?: "Failed to parse upload response"))
                }
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string())
                Result.failure(Exception("Attachment upload failed (${response.code()}): $errorMsg"))
            }
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Exception during uploadPhoto", e)
            Result.failure(e)
        }
    }

    /**
     * Upload photo from raw byte array (useful for compressed camera bitmaps or picker streams).
     */
    suspend fun uploadPhotoBytes(
        chatId: Int,
        bytes: ByteArray,
        fileName: String = "photo_${System.currentTimeMillis()}.jpg",
        mimeType: String = "image/jpeg",
        description: String? = null
    ): Result<AttachmentUploadResponse> = withContext(Dispatchers.IO) {
        try {
            if (bytes.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Byte array is empty"))
            }

            val mediaType = mimeType.toMediaTypeOrNull() ?: "image/*".toMediaTypeOrNull()
            val requestBody = bytes.toRequestBody(mediaType)
            val filePart = MultipartBody.Part.createFormData("file", fileName, requestBody)

            val descBody = description?.toRequestBody("text/plain".toMediaTypeOrNull())
            val typeBody = "TYPE_IMAGE".toRequestBody("text/plain".toMediaTypeOrNull())

            val response = apiService.uploadAttachment(
                chatId = chatId,
                file = filePart,
                description = descBody,
                type = typeBody
            )

            if (response.isSuccessful) {
                val body = response.body()
                val parsed = parseAttachmentResponse(body?.data, fallbackFileName = fileName, fallbackFileSize = bytes.size.toLong(), fallbackMime = mimeType)
                if (parsed != null) {
                    ChatLogger.d(TAG, "Uploaded photo bytes successfully: url=${parsed.url}")
                    Result.success(parsed)
                } else {
                    Result.failure(IllegalStateException(body?.message ?: "Failed to parse upload response"))
                }
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string())
                Result.failure(Exception("Attachment byte upload failed (${response.code()}): $errorMsg"))
            }
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Exception during uploadPhotoBytes", e)
            Result.failure(e)
        }
    }

    /**
     * Multipart video upload returning video URL and metadata.
     */
    suspend fun uploadVideo(
        chatId: Int,
        file: File,
        mimeType: String = "video/mp4",
        description: String? = null
    ): Result<AttachmentUploadResponse> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || !file.canRead()) {
                return@withContext Result.failure(IllegalArgumentException("File does not exist or cannot be read: ${file.absolutePath}"))
            }

            val mediaType = mimeType.toMediaTypeOrNull() ?: "video/*".toMediaTypeOrNull()
            val requestFile = file.asRequestBody(mediaType)
            val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)

            val descBody = description?.toRequestBody("text/plain".toMediaTypeOrNull())
            val typeBody = "TYPE_VIDEO".toRequestBody("text/plain".toMediaTypeOrNull())

            val response = apiService.uploadAttachment(
                chatId = chatId,
                file = filePart,
                message = descBody,
                description = descBody,
                type = typeBody
            )

            if (response.isSuccessful) {
                val body = response.body()
                val parsed = parseAttachmentResponse(body?.data, fallbackFileName = file.name, fallbackFileSize = file.length(), fallbackMime = mimeType)
                if (parsed != null) {
                    ChatLogger.d(TAG, "Uploaded video successfully: url=${parsed.url}")
                    Result.success(parsed)
                } else {
                    Result.failure(IllegalStateException(body?.message ?: "Failed to parse upload response"))
                }
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string())
                Result.failure(Exception("Video upload failed (${response.code()}): $errorMsg"))
            }
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Exception during uploadVideo", e)
            Result.failure(e)
        }
    }

    /**
     * Multipart audio upload returning audio URL and metadata.
     */
    suspend fun uploadAudio(
        chatId: Int,
        file: File,
        mimeType: String = "audio/mpeg",
        description: String? = null
    ): Result<AttachmentUploadResponse> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || !file.canRead()) {
                return@withContext Result.failure(IllegalArgumentException("File does not exist or cannot be read: ${file.absolutePath}"))
            }

            val mediaType = mimeType.toMediaTypeOrNull() ?: "audio/*".toMediaTypeOrNull()
            val requestFile = file.asRequestBody(mediaType)
            val filePart = MultipartBody.Part.createFormData("file", file.name, requestFile)

            val descBody = description?.toRequestBody("text/plain".toMediaTypeOrNull())
            val typeBody = "TYPE_AUDIO".toRequestBody("text/plain".toMediaTypeOrNull())

            val response = apiService.uploadAttachment(
                chatId = chatId,
                file = filePart,
                message = descBody,
                description = descBody,
                type = typeBody
            )

            if (response.isSuccessful) {
                val body = response.body()
                val parsed = parseAttachmentResponse(body?.data, fallbackFileName = file.name, fallbackFileSize = file.length(), fallbackMime = mimeType)
                if (parsed != null) {
                    ChatLogger.d(TAG, "Uploaded audio successfully: url=${parsed.url}")
                    Result.success(parsed)
                } else {
                    Result.failure(IllegalStateException(body?.message ?: "Failed to parse upload response"))
                }
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string())
                Result.failure(Exception("Audio upload failed (${response.code()}): $errorMsg"))
            }
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Exception during uploadAudio", e)
            Result.failure(e)
        }
    }

    private fun parseAttachmentResponse(
        jsonElement: JsonElement?,
        fallbackFileName: String,
        fallbackFileSize: Long,
        fallbackMime: String
    ): AttachmentUploadResponse? {
        if (jsonElement == null) return null
        return try {
            val jsonObject: JsonObject = if (jsonElement.isJsonObject) {
                jsonElement.asJsonObject
            } else {
                return null
            }

            val rawUrl = when {
                jsonObject.has("url") -> jsonObject.get("url").asString
                jsonObject.has("imageUrl") -> jsonObject.get("imageUrl").asString
                jsonObject.has("mediaUrl") -> jsonObject.get("mediaUrl").asString
                jsonObject.has("filePath") -> jsonObject.get("filePath").asString
                jsonObject.has("path") -> jsonObject.get("path").asString
                else -> null
            } ?: return null

            // If relative url, prepend base URL
            val fullUrl = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                val base = sessionManager.getBaseUrl()
                val trimmedBase = if (base.endsWith("/")) base.dropLast(1) else base
                val trimmedPath = if (rawUrl.startsWith("/")) rawUrl else "/$rawUrl"
                trimmedBase + trimmedPath
            } else {
                rawUrl
            }

            val fileName = when {
                jsonObject.has("file_name") -> jsonObject.get("file_name").asString
                jsonObject.has("fileName") -> jsonObject.get("fileName").asString
                jsonObject.has("filename") -> jsonObject.get("filename").asString
                jsonObject.has("name") -> jsonObject.get("name").asString
                else -> fallbackFileName
            }

            val fileSize = jsonObject.get("fileSize")?.asLong
                ?: jsonObject.get("size")?.asLong
                ?: fallbackFileSize

            val mimeType = jsonObject.get("mimeType")?.asString
                ?: jsonObject.get("mimetype")?.asString
                ?: fallbackMime

            val metadata = MediaMetadata(
                fileName = fileName,
                fileSize = fileSize,
                mimeType = mimeType,
                width = jsonObject.get("width")?.asInt,
                height = jsonObject.get("height")?.asInt
            )

            AttachmentUploadResponse(
                url = fullUrl,
                fileName = fileName,
                fileSize = fileSize,
                mimeType = mimeType,
                metadata = metadata
            )
        } catch (e: Exception) {
            ChatLogger.e(TAG, "Error parsing attachment response", e)
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
        private const val TAG = "MediaRepository"
    }
}
