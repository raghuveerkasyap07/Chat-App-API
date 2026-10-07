package com.demo.chat.data.remote

import com.demo.chat.data.local.SessionManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val sessionManager: SessionManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val token = sessionManager.getAuthToken()

        val requestBuilder = originalRequest.newBuilder()
        if (!token.isNullOrBlank() && originalRequest.header("Authorization") == null) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        // Dynamically adjust host/port/scheme if base URL in SessionManager was updated
        val customBase = sessionManager.getBaseUrl().toHttpUrlOrNull()
        if (customBase != null && (originalRequest.url.host != customBase.host || originalRequest.url.port != customBase.port)) {
            val newUrl = originalRequest.url.newBuilder()
                .scheme(customBase.scheme)
                .host(customBase.host)
                .port(customBase.port)
                .build()
            requestBuilder.url(newUrl)
        }

        return chain.proceed(requestBuilder.build())
    }
}
