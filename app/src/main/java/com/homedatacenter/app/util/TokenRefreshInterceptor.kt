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
    // v1.14.2: invoked when a 401 means the login itself is gone
    // (device deleted/revoked). Never invoked for network failures.
    private val onFatalAuth: ((String) -> Unit)? = null,
) : Interceptor {

    private val json = Json { ignoreUnknownKeys = true }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)

        // Only intercept 401 responses.
        if (response.code != 401) return response

        // Read the response body to check the error message.
        val bodyString = response.body?.string() ?: return response

        // v1.14.2: device/user deleted or revoked -> re-login required.
        // Only for authenticated requests (the login bind call itself
        // carries no Authorization header and must just show its error).
        val hadAuth = !originalRequest.header("Authorization").isNullOrBlank()
        if (hadAuth && isFatalAuth(bodyString)) {
            val reason = parseRoot(bodyString)?.get("message")?.jsonPrimitive?.content ?: "fatal 401"
            try { onFatalAuth?.invoke(reason) } catch (_: Exception) {}
            return respondWithReattachedBody(response, bodyString)
        }

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
        // Legacy hook: root `code` in caller-supplied set.
        val code = root["code"]?.jsonPrimitive?.content?.toIntOrNull()
        if (code != null && code in tokenInvalidCodes) return true
        return classify(body) == AuthErrorKind.RECOVERABLE
    }

    private fun isFatalAuth(body: String): Boolean = classify(body) == AuthErrorKind.FATAL

    private fun parseRoot(body: String): JsonObject? = parseRootStatic(body)

    /** v1.14.3: how a 401 body should be handled by the client. */
    enum class AuthErrorKind { RECOVERABLE, FATAL, TRANSIENT, UNKNOWN }

    companion object {
        // Stable backend error codes (services/api/internal/utils/errcodes.go).
        const val ERR_AUTH_MISSING = 40101
        const val ERR_AUTH_TOKEN_INVALID = 40102
        const val ERR_AUTH_DEVICE_NOT_FOUND = 40103
        const val ERR_AUTH_DEVICE_REVOKED = 40104
        const val ERR_AUTH_TOKEN_VERSION_MISMATCH = 40105
        const val ERR_AUTH_DEVICE_LOOKUP_FAILED = 40106
        const val ERR_AUTH_INVALID_CREDENTIALS = 40107
        const val ERR_AUTH_USER_NOT_FOUND = 40108

        private val RECOVERABLE_CODES = setOf(ERR_AUTH_TOKEN_INVALID, ERR_AUTH_TOKEN_VERSION_MISMATCH)
        private val FATAL_CODES = setOf(
            ERR_AUTH_MISSING, ERR_AUTH_DEVICE_NOT_FOUND, ERR_AUTH_DEVICE_REVOKED,
            ERR_AUTH_INVALID_CREDENTIALS, ERR_AUTH_USER_NOT_FOUND,
        )
        private val TRANSIENT_CODES = setOf(ERR_AUTH_DEVICE_LOOKUP_FAILED)

        private val staticJson = Json { ignoreUnknownKeys = true }

        internal fun parseRootStatic(body: String): JsonObject? = try {
            staticJson.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            null
        }

        /**
         * Classifies a 401 body. Prefers the stable `error_code`; falls
         * back to legacy `message` text for older backends.
         * Pure function - unit tested.
         */
        fun classify(body: String): AuthErrorKind {
            val root = parseRootStatic(body) ?: return AuthErrorKind.UNKNOWN
            val errorCode = try {
                root["error_code"]?.jsonPrimitive?.content?.toIntOrNull()
            } catch (_: Exception) { null }
            if (errorCode != null) {
                return when (errorCode) {
                    in RECOVERABLE_CODES -> AuthErrorKind.RECOVERABLE
                    in FATAL_CODES -> AuthErrorKind.FATAL
                    in TRANSIENT_CODES -> AuthErrorKind.TRANSIENT
                    else -> AuthErrorKind.UNKNOWN
                }
            }
            val message = try {
                root["message"]?.jsonPrimitive?.content
            } catch (_: Exception) { null } ?: return AuthErrorKind.UNKNOWN
            return when {
                message == "token version mismatch" || message == "invalid token" -> AuthErrorKind.RECOVERABLE
                message in AuthInvalidHandler.FATAL_401_MESSAGES -> AuthErrorKind.FATAL
                message == "device lookup failed" -> AuthErrorKind.TRANSIENT
                else -> AuthErrorKind.UNKNOWN
            }
        }
    }
}