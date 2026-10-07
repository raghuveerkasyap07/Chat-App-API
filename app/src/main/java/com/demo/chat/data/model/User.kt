package com.demo.chat.data.model

import com.google.gson.annotations.SerializedName

data class User(
    val id: Int,
    val name: String,
    val email: String,
    @SerializedName("avatar_url", alternate = ["avatarUrl"])
    val avatarUrl: String? = null,
    @SerializedName("created_at", alternate = ["createdAt"])
    val createdAt: String? = null,
    @SerializedName("is_online", alternate = ["isOnline"])
    val isOnline: Boolean? = false
)
