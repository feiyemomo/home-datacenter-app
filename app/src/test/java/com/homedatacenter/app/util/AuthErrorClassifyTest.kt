package com.homedatacenter.app.util

import com.homedatacenter.app.util.TokenRefreshInterceptor.AuthErrorKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.14.3: classification of 401 bodies. error_code wins; message text is
 * the fallback for older backends. Network-ish / unknown bodies must never
 * be classified FATAL (that would log the user out).
 */
class AuthErrorClassifyTest {

    private fun body(code: Int?, message: String): String {
        val ec = if (code != null) ",\"error_code\":$code" else ""
        return "{\"code\":401,\"message\":\"$message\",\"data\":null$ec}"
    }

    @Test
    fun errorCode_recoverable() {
        assertEquals(AuthErrorKind.RECOVERABLE, TokenRefreshInterceptor.classify(body(40102, "x")))
        assertEquals(AuthErrorKind.RECOVERABLE, TokenRefreshInterceptor.classify(body(40105, "x")))
    }

    @Test
    fun errorCode_fatal() {
        for (c in listOf(40101, 40103, 40104, 40107, 40108)) {
            assertEquals("code $c", AuthErrorKind.FATAL, TokenRefreshInterceptor.classify(body(c, "x")))
        }
    }

    @Test
    fun errorCode_transient_neverFatal() {
        assertEquals(AuthErrorKind.TRANSIENT, TokenRefreshInterceptor.classify(body(40106, "device not found")))
    }

    @Test
    fun errorCode_overridesMessage() {
        // message says fatal but code says recoverable -> code wins
        assertEquals(AuthErrorKind.RECOVERABLE, TokenRefreshInterceptor.classify(body(40102, "device revoked")))
    }

    @Test
    fun unknownErrorCode_isUnknown() {
        assertEquals(AuthErrorKind.UNKNOWN, TokenRefreshInterceptor.classify(body(49999, "device revoked")))
    }

    @Test
    fun legacyMessage_fallback() {
        assertEquals(AuthErrorKind.RECOVERABLE, TokenRefreshInterceptor.classify(body(null, "invalid token")))
        assertEquals(AuthErrorKind.RECOVERABLE, TokenRefreshInterceptor.classify(body(null, "token version mismatch")))
        assertEquals(AuthErrorKind.FATAL, TokenRefreshInterceptor.classify(body(null, "device not found")))
        assertEquals(AuthErrorKind.FATAL, TokenRefreshInterceptor.classify(body(null, "device revoked")))
        assertEquals(AuthErrorKind.FATAL, TokenRefreshInterceptor.classify(body(null, "missing authorization header")))
        assertEquals(AuthErrorKind.TRANSIENT, TokenRefreshInterceptor.classify(body(null, "device lookup failed")))
    }

    @Test
    fun garbageBodies_areUnknown() {
        assertEquals(AuthErrorKind.UNKNOWN, TokenRefreshInterceptor.classify(""))
        assertEquals(AuthErrorKind.UNKNOWN, TokenRefreshInterceptor.classify("<html>502 Bad Gateway</html>"))
        assertEquals(AuthErrorKind.UNKNOWN, TokenRefreshInterceptor.classify("{}"))
        assertEquals(AuthErrorKind.UNKNOWN, TokenRefreshInterceptor.classify(body(null, "unauthenticated")))
    }
}
