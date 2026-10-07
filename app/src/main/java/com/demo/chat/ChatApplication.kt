package com.demo.chat

import android.app.Application
import com.demo.chat.sdk.core.ChatClient

class ChatApplication : Application() {

    lateinit var chatClient: ChatClient
        private set

    override fun onCreate() {
        super.onCreate()
        chatClient = ChatClient.initialize(this)
    }
}
