package com.demo.chat

import android.app.Application
import com.demo.chat.sdk.core.ChatClient

class ChatApplication : Application() {

    lateinit var chatClient: ChatClient
        private set

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("ChatApplication", "FATAL UNCAUGHT EXCEPTION on thread ${thread.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        chatClient = ChatClient.initialize(this)
    }

    fun getOrCreateChatClient(): ChatClient {
        if (!::chatClient.isInitialized) {
            chatClient = ChatClient.initialize(this)
        }
        return chatClient
    }
}
