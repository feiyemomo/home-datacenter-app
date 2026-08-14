package com.homedatacenter.app.util

import org.json.JSONObject
import java.util.Base64

/**
 * Lightweight JWT parser — decodes the payload segment without verifying
 * the signature (signature verification is the server's job; we only use
 * the parsed claims for UI display like token countdown and user_id).
 *
 * The backend JWT is HS256-signed with a 365-day expiry. Claims include
 * `user_id`, `device_id`, `iat`, `exp` — but NOT `is_admin` (the server
 * does a DB lookup on every request so an admin can be demoted and the
 * change takes effect immediately).
 *
 * v1.8.44: switched from android.util.Base64 to java.util.Base64
 * (available since API 26; minSdk is 29) so the class is pure JVM and
 * unit-testable without Robolectric. JWT segments are base64url WITHOUT
 * padding, and java.util.Base64's URL decoder requires padding, so we
 * re-pad to a multiple of 4 before decoding.
 */
object JwtUtil {

    private fun decodeUrlSafeNoPad(input: String): ByteArray {
        var s = input
        val pad = (4 - s.length % 4) % 4
        if (pad > 0) s += "=".repeat(pad)
        return Base64.getUrlDecoder().decode(s)
    }

    /**
     * Parse the payload segment of a JWT into a [JSONObject].
     * Returns null if the token is malformed.
     */
    fun parsePayload(token: String?): JSONObject? {
        if (token.isNullOrEmpty()) return null
        val parts = token.split(".")
        if (parts.size != 3) return null
        return try {
            val payload = String(decodeUrlSafeNoPad(parts[1]))
            JSONObject(payload)
        } catch (_: Exception) {
            null
        }
    }

    fun userId(token: String?): Long? {
        return parsePayload(token)?.optLong("user_id")?.takeIf { it != 0L }
    }

    fun deviceId(token: String?): Long? {
        return parsePayload(token)?.optLong("device_id")?.takeIf { it != 0L }
    }

    /** Unix epoch seconds, or null if missing/malformed. */
    fun issuedAt(token: String?): Long? {
        return parsePayload(token)?.optLong("iat")?.takeIf { it != 0L }
    }

    /** Unix epoch seconds, or null if missing/malformed. */
    fun expiresAt(token: String?): Long? {
        return parsePayload(token)?.optLong("exp")?.takeIf { it != 0L }
    }

    /**
     * Remaining seconds until expiry (negative if already expired).
     * Returns null if the token can't be parsed.
     */
    fun secondsUntilExpiry(token: String?): Long? {
        val exp = expiresAt(token) ?: return null
        return exp - (System.currentTimeMillis() / 1000L)
    }
}
