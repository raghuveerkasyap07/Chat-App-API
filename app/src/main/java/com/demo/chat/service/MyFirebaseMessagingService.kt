package com.demo.chat.service

import com.demo.chat.utils.NotificationHelper
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: "New Message"
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"] ?: "You have a new chat message"
        val chatId = remoteMessage.data["chatId"]?.toIntOrNull() ?: 0
        val imageUrl = remoteMessage.notification?.imageUrl?.toString()
            ?: remoteMessage.data["imageUrl"]
            ?: remoteMessage.data["mediaUrl"]

        NotificationHelper.showChatNotification(this, title, body, chatId, imageUrl)
    }
}
