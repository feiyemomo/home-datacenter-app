package com.homedatacenter.app.data.api

import com.homedatacenter.app.BuildConfig
import com.homedatacenter.app.util.BaseUrlResolver
import com.homedatacenter.app.util.RetryInterceptor
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object NetworkFactory {

    // Unified User-Agent for every HTTP path (Retrofit, WebRTC signaling,
    // ExoPlayer, preview-frame fetches). Reading VERSION_NAME keeps the
    // UA in sync with the version shown to users without manual edits.
    const val USER_AGENT = "HomeDatacenter/${BuildConfig.VERSION_NAME} (Android)"

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * Dedicated OkHttpClient for BaseUrlResolver probes.
     * Clean, lightweight, no retry or rewrite interceptors to ensure
     * raw, unbiased latency and reachability measurement to target URLs.
     */
    fun createProbeClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .header("X-Probe-Request", "true")
                        .build()
                )
            }
            .build()
    }

    fun okHttpClient(
        enableLogging: Boolean = false,
        baseUrlProvider: (() -> String)? = null,
        baseUrlResolver: BaseUrlResolver? = null,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 10, TimeUnit.MINUTES))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            // Unified User-Agent
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .build()
                )
            }
            // RetryInterceptor: automatically retries GET requests on transient failures.
            // Placed BEFORE DynamicBaseUrlInterceptor so each retry attempt
            // can be dynamically rewritten to the failover target URL.
            .addInterceptor(RetryInterceptor(baseUrlResolver = baseUrlResolver))

        // DynamicBaseUrlInterceptor: dynamically rewrites requests to current active base URL
        if (baseUrlProvider != null) {
            builder.addInterceptor(DynamicBaseUrlInterceptor(baseUrlProvider))
        }

        // 仅 DEBUG 构建挂载日志中间件
        if (enableLogging && BuildConfig.DEBUG) {
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
