package com.homedatacenter.app.util

import com.homedatacenter.app.data.api.NetworkFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * v1.10.0: single owner of the JWT re-bind flow. Extracted from the
 * duplicate implementations that previously lived in AppContainer
 * (monthly silent refresh) and TokenRefreshInterceptor (401 recovery).
 *
 * The bind call goes to POST /api/v1/auth/bind with the stored
 * access_key and MUST use a dedicated OkHttpClient - the main client
 * carries TokenRefreshInterceptor itself, which would loop forever.
 */
class TokenManager(
    private val prefsManager: PrefsManager,
    private val baseUrlProvider: () -> String,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val bindClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", NetworkFactory.USER_AGENT)
                    .build()
            )
        }
        .build()

    /**
     * Exchanges (userId, accessKey) for a fresh JWT. Returns the token
     * string, or null on ANY failure (network, HTTP error, bad payload).
     * Never throws - safe to call from OkHttp interceptors.
     */
    fun refreshToken(userId: Long, accessKey: String): String? = try {
        performBind(userId, accessKey)
    } catch (_: Exception) {
        null
    }

    private val refreshLock = Any()

    /**
     * Refresh + persist: on success stores the new token and bumps
     * lastTokenRefreshTime. Returns the new token, or null.
     *
     * Synchronized with double-checked caching: prevents multiple concurrent
     * 401s from spamming /api/v1/auth/bind with redundant re-bind calls.
     */
    fun refreshAndPersist(userId: Long, accessKey: String): String? = synchronized(refreshLock) {
        val now = System.currentTimeMillis()
        val currentToken = prefsManager.token
        if (!currentToken.isNullOrEmpty() && (now - prefsManager.lastTokenRefreshTime) < 5_000L) {
            return currentToken
        }
        val newToken = refreshToken(userId, accessKey) ?: return null
        prefsManager.token = newToken
        prefsManager.lastTokenRefreshTime = System.currentTimeMillis()
        newToken
    }

    // --- Monthly silent refresh (moved from AppContainer, v1.8.15) ---

    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null

    /**
     * Checks and performs the once-per-month silent JWT refresh.
     * Called from HomeCenterApp.onCreate. No-op when credentials are
     * missing or the last refresh is younger than 30 days.
     */
    fun tryAutoRefreshToken() {
        if (refreshJob?.isActive == true) return
        if (prefsManager.token.isNullOrEmpty()) return
        val accessKey = prefsManager.accessKey ?: return
        val userId = prefsManager.userId
        if (userId <= 0L) return

        val lastRefresh = prefsManager.lastTokenRefreshTime
        val now = System.currentTimeMillis()
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000

        // Refreshed within the last 30 days - skip.
        if (lastRefresh > 0 && (now - lastRefresh) < thirtyDaysMs) return

        refreshJob = refreshScope.launch {
            val newToken = refreshAndPersist(userId, accessKey)
            if (newToken != null) {
                android.util.Log.d(TAG, "Token auto-refreshed (monthly)")
            } else {
                android.util.Log.w(TAG, "Token auto-refresh failed")
            }
        }
    }

    private fun performBind(userId: Long, accessKey: String): String {
        val baseUrl = baseUrlProvider().trimEnd('/')
        val bindUrl = "$baseUrl/api/v1/auth/bind"

        val bodyJson = """{"user_id":$userId,"access_key":"$accessKey"}"""
        val requestBody = bodyJson.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(bindUrl)
            .post(requestBody)
            .build()

        val response = bindClient.newCall(request).execute()
        val body = response.body?.string() ?: throw RuntimeException("empty bind response")

        if (!response.isSuccessful) {
            throw RuntimeException("bind failed: code=${response.code} body=$body")
        }

        // Parse the JSON response: {"code":0,"data":{"token":"..."}}
        val root = json.parseToJsonElement(body).jsonObject
        val code = root["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        if (code != 0) {
            throw RuntimeException("bind returned code=$code")
        }

        val data = root["data"]?.jsonObject
            ?: throw RuntimeException("bind response missing data")
        return data["token"]?.jsonPrimitive?.content
            ?: throw RuntimeException("bind response missing token")
    }

    companion object {
        private const val TAG = "TokenManager"
    }
}