package com.demo.chat.data.model

import com.google.gson.annotations.SerializedName

data class MediaMetadata(
    @SerializedName("file_name", alternate = ["fileName", "filename", "name"])
    val fileName: String? = null,
    @SerializedName("file_size", alternate = ["fileSize", "size"])
    val fileSize: Long? = null,
    @SerializedName("mime_type", alternate = ["mimeType", "mimetype", "contentType"])
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null
)
