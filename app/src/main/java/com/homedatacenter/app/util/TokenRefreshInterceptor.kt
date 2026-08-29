package com.homedatacenter.app.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.Response

/**
 * v1.8.15: OkHttp interceptor that silently refreshes the JWT token
 * when the server rejects it due to token version mismatch (admin
 * rotation) or expiry.
 *
 * v1.10.0: the bind call and token persistence moved into [TokenManager]
 * (shared with AppContainer's monthly auto-refresh). This class now only
 * decides WHEN a 401 means "re-bind", and performs the retried request.
 * Also fixes a latent bug: on failed re-bind the original response was
 * returned with its body already consumed/closed, crashing callers that
 * read the body - it is now rebuilt with a fresh readable body.
 *
 * Flow:
 *   1. A request fails with 401 and the body's root `code` is a known
 *      token-invalid / version-mismatch code, or (fallback) the
 *      body.message = "token version mismatch" / "invalid token".
 *   2. The interceptor asks [TokenManager] to re-bind with the stored
 *      access_key to obtain a fresh JWT.
 *   3. If successful, the new token is persisted and the original
 *      request is retried with the updated Authorization header.
 *   4. If re-binding fails (e.g. access_key was also revoked), the
 *      original 401 is returned with its body re-attached and the user
 *      is redirected to the login screen by the caller.
 *
 * This interceptor must be added to the OkHttpClient AFTER the
 * User-Agent interceptor, but BEFORE the logging interceptor (to
 * avoid logging the retry as a separate request).
 */
class TokenRefreshInterceptor(
    private val prefsManager: PrefsManager,
    private val tokenManager: TokenManager,
    // Business codes that indicate the current token is invalid or its
    // version mismatches (admin rotation). Matched against the response
    // body's root `code` field BEFORE the message fallback.
    // TODO: replace with the authoritative codes once the backend confirms
    // the values behind "token version mismatch" / "invalid token".
    private val tokenInvalidCodes: Set<Int> = emptySet(),
) : Interceptor {

    private val json = Json { ignoreUnknownKeys = true }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)

        // Only intercept 401 responses.
        if (response.code != 401) return response

        // Read the response body to check the error message.
        val bodyString = response.body?.string() ?: return response
        val shouldRetry = isTokenRefreshNeeded(bodyString)

        if (!shouldRetry) {
            return respondWithReattachedBody(response, bodyString)
        }

        // Close the 401 response - we're going to retry.
        response.close()

        // Attempt to re-bind with the stored access_key.
        val accessKey = prefsManager.accessKey
            ?: return respondWithReattachedBody(response, bodyString)
        val userId = prefsManager.userId
        if (userId <= 0L) return respondWithReattachedBody(response, bodyString)

        val newToken = tokenManager.refreshAndPersist(userId, accessKey)
            ?: return respondWithReattachedBody(response, bodyString)

        // Retry the original request with the fresh token.
        val retryRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()

        return chain.proceed(retryRequest)
    }

    /**
     * v1.10.0: rebuild the 401 response with a fresh, readable body.
     * The original body was already consumed by [intercept]; returning
     * the closed response used to crash callers that read the body.
     */
    private fun respondWithReattachedBody(response: Response, bodyString: String): Response {
        return response.newBuilder()
            .body(okhttp3.ResponseBody.create(response.body?.contentType(), bodyString))
            .build()
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