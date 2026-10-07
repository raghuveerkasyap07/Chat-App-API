package com.demo.chat.utils

object ChatLogger {
    fun d(tag: String, msg: String) {
        try {
            android.util.Log.d(tag, msg)
        } catch (e: Throwable) {
            println("DEBUG: [$tag] $msg")
        }
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        try {
            android.util.Log.w(tag, msg, tr)
        } catch (e: Throwable) {
            println("WARN: [$tag] $msg ${tr?.message ?: ""}")
        }
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        try {
            android.util.Log.e(tag, msg, tr)
        } catch (e: Throwable) {
            System.err.println("ERROR: [$tag] $msg ${tr?.message ?: ""}")
        }
    }
}
