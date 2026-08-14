package com.homedatacenter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * JwtUtil is a pure-JVM parser (v1.8.44 moved it off android.util.Base64).
 * These tests build real base64url-no-padding JWTs and verify claim
 * extraction, expiry math, and malformed-token handling.
 */
class JwtUtilTest {

    private fun b64url(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    private fun jwt(payloadJson: String): String =
        "${b64url("""{"alg":"HS256"}""")}.${b64url(payloadJson)}.signature"

    private fun payload(
        userId: Long = 42L,
        deviceId: Long = 7L,
        iat: Long = 1_000L,
        exp: Long = 1_000_000L,
    ) = """{"user_id":$userId,"device_id":$deviceId,"iat":$iat,"exp":$exp}"""

    @Test
    fun parsePayload_decodesClaims() {
        val token = jwt(payload())
        val parsed = JwtUtil.parsePayload(token)
        assertTrue(parsed != null)
        assertEquals(42L, parsed!!.optLong("user_id"))
        assertEquals(7L, parsed.optLong("device_id"))
        assertEquals(1_000L, parsed.optLong("iat"))
        assertEquals(1_000_000L, parsed.optLong("exp"))
    }

    @Test
    fun claimAccessors_returnExpectedValues() {
        val token = jwt(payload(userId = 99, deviceId = 3, iat = 111, exp = 222))
        assertEquals(99L, JwtUtil.userId(token))
        assertEquals(3L, JwtUtil.deviceId(token))
        assertEquals(111L, JwtUtil.issuedAt(token))
        assertEquals(222L, JwtUtil.expiresAt(token))
    }

    @Test
    fun secondsUntilExpiry_positiveWhenFuture() {
        // exp far in the future relative to now.
        val token = jwt(payload(exp = System.currentTimeMillis() / 1000 + 500))
        val remaining = JwtUtil.secondsUntilExpiry(token)
        assertTrue(remaining != null)
        assertTrue("expected positive remaining, got $remaining", remaining!! > 490)
    }

    @Test
    fun secondsUntilExpiry_negativeWhenExpired() {
        val token = jwt(payload(exp = System.currentTimeMillis() / 1000 - 100))
        val remaining = JwtUtil.secondsUntilExpiry(token)
        assertTrue(remaining != null)
        assertTrue("expected negative remaining, got $remaining", remaining!! < 0)
    }

    @Test
    fun userId_zeroClaimIsIgnored() {
        val token = jwt(payload(userId = 0))
        assertNull(JwtUtil.userId(token))
    }

    @Test
    fun malformedInputs_returnNull() {
        assertNull(JwtUtil.parsePayload(null))
        assertNull(JwtUtil.parsePayload(""))
        assertNull(JwtUtil.parsePayload("   "))
        // Not three dot-separated segments.
        assertNull(JwtUtil.parsePayload("abc"))
        assertNull(JwtUtil.parsePayload("a.b"))
        assertNull(JwtUtil.parsePayload("a.b.c.d"))
        // Invalid base64url in the payload segment.
        assertNull(JwtUtil.parsePayload("a.!!!not%base64!!.c"))
        // Valid base64url but not JSON.
        assertNull(JwtUtil.parsePayload("a.${b64url("not json")}.c"))
    }

    @Test
    fun expiresAt_zeroClaimReturnsNull() {
        val token = jwt(payload(exp = 0))
        assertNull(JwtUtil.expiresAt(token))
    }
}