package com.demo.chat.data.model

import com.google.gson.annotations.SerializedName

data class Chat(
    val id: Int,
    @SerializedName("user1_id", alternate = ["user1Id"])
    val user1Id: Int? = null,
    @SerializedName("user2_id", alternate = ["user2Id"])
    val user2Id: Int? = null,
    val partner: User? = null,
    val recipient: User? = null,
    val user: User? = null,
    @SerializedName("last_message", alternate = ["lastMessage"])
    val lastMessage: ChatMessage? = null,
    @SerializedName("unread_count", alternate = ["unreadCount"])
    val unreadCount: Int = 0,
    @SerializedName("created_at", alternate = ["createdAt"])
    val createdAt: String? = null,
    @SerializedName("updated_at", alternate = ["updatedAt"])
    val updatedAt: String? = null
) {
    fun getDisplayPartner(currentUserId: Int): User? {
        if (partner != null && partner.id != currentUserId) return partner
        if (recipient != null && recipient.id != currentUserId) return recipient
        if (user != null && user.id != currentUserId) return user
        if (lastMessage?.sender != null && lastMessage.sender.id != currentUserId) return lastMessage.sender
        return partner ?: recipient ?: user
    }
}
