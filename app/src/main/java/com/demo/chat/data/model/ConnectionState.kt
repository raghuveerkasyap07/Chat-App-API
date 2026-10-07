package com.demo.chat.data.model

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class Reconnecting(
        val attempt: Int,
        val maxAttempts: Int = 5,
        val delayMillis: Long = 0L
    ) : ConnectionState()
    data class Failed(
        val error: Throwable,
        val message: String = error.localizedMessage ?: "Connection failed"
    ) : ConnectionState()

    val isConnected: Boolean
        get() = this is Connected

    val isReconnecting: Boolean
        get() = this is Reconnecting
}
