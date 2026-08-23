package com.homedatacenter.app.util

import com.homedatacenter.app.data.api.NetworkFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * v1.8.15: OkHttp interceptor that silently refreshes the JWT token
 * when the server rejects it due to token version mismatch (admin
 * rotation) or expiry.
 *
 * Flow:
 *   1. A request fails with 401 and the body's root `code` is a known
 *      token-invalid / version-mismatch code, or (fallback) the
 *      body.message = "token version mismatch" / "invalid token".
 *   2. The interceptor calls POST /api/v1/auth/bind with the stored
 *      access_key to obtain a fresh JWT.
 *   3. If successful, the new token is persisted and the original
 *      request is retried with the updated Authorization header.
 *   4. If re-binding fails (e.g. access_key was also revoked), the
 *      original 401 is returned as-is and the user is redirected to
 *      the login screen.
 *
 * This interceptor must be added to the OkHttpClient AFTER the
 * User-Agent interceptor, but BEFORE the logging interceptor (to
 * avoid logging the retry as a separate request).
 */
class TokenRefreshInterceptor(
    private val prefsManager: PrefsManager,
    private val baseUrlProvider: () -> String,
    // Business codes that indicate the current token is invalid or its
    // version mismatches (admin rotation). Matched against the response
    // body's root `code` field BEFORE the message fallback.
    // TODO: replace with the authoritative codes once the backend confirms
    // the values behind "token version mismatch" / "invalid token".
    private val tokenInvalidCodes: Set<Int> = emptySet(),
) : Interceptor {

    private val json = Json { ignoreUnknownKeys = true }

    // A dedicated client for the re-bind request — we MUST NOT use
    // the main OkHttpClient (which includes this interceptor) or
    // we'd create an infinite loop.
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

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)

        // Only intercept 401 responses.
        if (response.code != 401) return response

        // Read the response body to check the error message.
        val bodyString = response.body?.string() ?: return response
        val shouldRetry = isTokenRefreshNeeded(bodyString)

        if (!shouldRetry) {
            // Close the body and return a new response with the body
            // re-attached so the caller can still read it.
            return response.newBuilder()
                .body(okhttp3.ResponseBody.create(
                    response.body?.contentType(),
                    bodyString
                ))
                .build()
        }

        // Close the 401 response — we're going to retry.
        response.close()

        // Attempt to re-bind with the stored access_key.
        val accessKey = prefsManager.accessKey ?: return response
        val userId = prefsManager.userId
        if (userId <= 0L) return response

        val newToken = try {
            refreshToken(userId, accessKey)
        } catch (_: Exception) {
            null
        }

        if (newToken == null) return response

        // Persist the new token and update the refresh timestamp.
        prefsManager.token = newToken
        prefsManager.lastTokenRefreshTime = System.currentTimeMillis()

        // Retry the original request with the fresh token.
        val retryRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()

        return chain.proceed(retryRequest)
    }

    /**
     * Calls POST /auth/bind to exchange (user_id, access_key) for a
     * new JWT. Returns the token string, or throws on failure.
     */
    private fun refreshToken(userId: Long, accessKey: String): String {
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

    /**
     * Decides whether a 401 response indicates a token version mismatch /
     * invalid token (and thus should trigger a re-bind), matching in order:
     *   1. If the body's root `code` field is present and is one of
     *      [tokenInvalidCodes], trigger a refresh.
     *   2. Otherwise fall back to the legacy `message` text matching
     *      ("token version mismatch" / "invalid token") so an unconfirmed
     *      code value or a changed backend message still works.
     *
     * TODO: Once the backend confirms the business codes behind
     * "token version mismatch" / "invalid token", encode them into
     * [tokenInvalidCodes] and the message fallback below can be removed.
     */
    private fun isTokenRefreshNeeded(body: String): Boolean {
        val root = parseRoot(body) ?: return false

        // 1) Prefer the business `code` field.
        val code = root["code"]?.jsonPrimitive?.content?.toIntOrNull()
        if (code != null && code in tokenInvalidCodes) return true

        // 2) Fall back to the legacy message-text matching (backward compat).
        val message = root["message"]?.jsonPrimitive?.content
        return message == "token version mismatch" || message == "invalid token"
    }

    private fun parseRoot(body: String): JsonObject? {
        return try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            null
        }
    }
}