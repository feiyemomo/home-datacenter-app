package com.homedatacenter.app.data.api

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object NetworkFactory {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun okHttpClient(enableLogging: Boolean = false): OkHttpClient {
        // Connection resilience for mobile networks:
        //   - Long connect timeout: mobile carriers' middleboxes and
        //     Cloudflare Tunnel can take ~20-30s to establish TLS on a
        //     cold connection. 15s sometimes gives up too early.
        //   - retryOnConnectionFailure: re-attempts TLS/TCP failures
        //     caused by transient carrier issues ("connection closed"
        //     errors that don't reflect a real server problem).
        //   - Protocols: prefer HTTP/1.1 over HTTP/2. Cloudflare Tunnel
        //     occasionally closes HTTP/2 streams abruptly on mobile
        //     networks, surfacing as "connection closed" in OkHttp.
        //     Forcing HTTP/1.1 trades multiplexing for stability on
        //     the slow, lossy mobile paths this app uses.
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))

        if (enableLogging) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
        }
        return builder.build()
    }

    fun createApi(baseUrl: String, client: OkHttpClient): HomeCenterApi {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(HomeCenterApi::class.java)
    }
}
