package com.shreddro.app.net

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/** Central HTTP client construction (one connection pool for the whole app). */
object Clients {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                // Never log bodies: payloads contain financial data + bearer tokens.
                level = HttpLoggingInterceptor.Level.BASIC
                redactHeader("Authorization")
                redactHeader("X-Shreddro-Secret")
                redactHeader("x-goog-api-key")
            })
            .build()
    }

    val appsScriptApi: AppsScriptApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://script.google.com/")
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AppsScriptApi::class.java)
    }

    val graphApi: GraphApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://graph.microsoft.com/")
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GraphApi::class.java)
    }
}
