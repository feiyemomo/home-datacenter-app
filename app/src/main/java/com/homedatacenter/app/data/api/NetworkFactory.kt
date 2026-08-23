package com.homedatacenter.app.data.api

import com.homedatacenter.app.BuildConfig
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
        // v1.6.28: explicit ConnectionPool config so keep-alive
        // connections are reused across API calls. Default OkHttp pool
        // is (5, 5min) — same numbers, but setting it explicitly makes
        // the intent visible and pins the behavior across OkHttp
        // versions. This is the critical optimization for cellular
        // IPv6 (~250ms RTT): the second request to the same origin
        // reuses the warmed TCP connection and skips the handshake,
        // cutting ~250ms off the request. Without an explicit pool,
        // OkHttp still keeps connections alive by default, but
        // documenting it here makes the warmup strategy in
        // BaseUrlResolver.warmupConnection() coherent.
        //
        // v1.6.29: keep-alive extended from 5 min to 10 min so it
        // EXCEEDS the BaseUrlResolver probe TTL (5 min). Previously,
        // keep-alive (5 min) == TTL (5 min), which meant by the time
        // the next probe ran, the warmup connection had just expired
        // and the probe paid the full TCP handshake again. With 10 min
        // keep-alive, the connection from the previous warmup is still
        // alive when the next probe fires, so the probe reuses it and
        // the displayed RTT stays at ~250ms instead of jumping back to
        // ~500ms every 5 minutes.
        val builder = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 10, TimeUnit.MINUTES))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            // Unified User-Agent: .header() (not .addHeader()) overwrites
            // OkHttp's default UA so every Retrofit request and any
            // direct newCall() sharing this client advertises the same
            // app identity to the backend / Cloudflare Tunnel.
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .build()
                )
            }
            // RetryInterceptor: automatically retries GET requests on
            // transient failures (5xx, timeout, DNS failure, reset).
            // Added AFTER the User-Agent interceptor but BEFORE the
            // logging interceptor so retries are logged.
            .addInterceptor(RetryInterceptor())

        // 仅 DEBUG 构建挂载日志中间件：即使调用方传 enableLogging=true，
        // release 也绝不打印日志（BuildConfig.DEBUG 在 release 恒为 false）。
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
