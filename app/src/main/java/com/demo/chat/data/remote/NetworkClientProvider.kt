package com.demo.chat.data.remote

import com.demo.chat.data.local.SessionManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class NetworkClientProvider(private val sessionManager: SessionManager) {

    val gson: Gson = GsonBuilder()
        .setLenient()
        .create()

    val okHttpClient: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(sessionManager))
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun getRetrofit(baseUrl: String? = null): Retrofit {
        val effectiveUrl = baseUrl ?: sessionManager.getBaseUrl()
        val formattedUrl = if (effectiveUrl.endsWith("/")) effectiveUrl else "$effectiveUrl/"

        return Retrofit.Builder()
            .baseUrl(formattedUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    fun createApiService(baseUrl: String? = null): ChatApiService {
        return getRetrofit(baseUrl).create(ChatApiService::class.java)
    }
}
