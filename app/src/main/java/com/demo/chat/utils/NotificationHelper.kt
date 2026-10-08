package com.demo.chat.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import com.demo.chat.R
import com.demo.chat.ui.MainActivity
import java.net.HttpURLConnection
import java.net.URL

object NotificationHelper {

    fun showChatNotification(
        context: Context,
        title: String,
        body: String,
        chatId: Int,
        imageUrl: String? = null
    ) {
        val channelId = "chat_messages_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Chat Messages & Media Attachments",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming real-time chat messages and photos"
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Launch MainActivity (Chat Screen) with chatId extra
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("chatId", chatId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            chatId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)

        // Handle image notification preview (BigPictureStyle) if imageUrl is provided
        if (!imageUrl.isNullOrBlank()) {
            try {
                val bitmap = downloadBitmap(imageUrl)
                if (bitmap != null) {
                    builder.setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .bigLargeIcon(null as Bitmap?)
                    )
                    builder.setLargeIcon(bitmap)
                }
            } catch (e: Exception) {
                // Fallback to text notification if image download fails
            }
        }

        notificationManager.notify(chatId, builder.build())
    }

    private fun downloadBitmap(urlString: String): Bitmap? {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.doInput = true
            connection.connect()
            val input = connection.inputStream
            BitmapFactory.decodeStream(input)
        } catch (e: Exception) {
            null
        }
    }
}
