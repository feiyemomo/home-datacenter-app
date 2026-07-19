package com.homedatacenter.app.util

import android.util.Base64
import org.json.JSONObject

/**
 * Lightweight JWT parser — decodes the payload segment without verifying
 * the signature (signature verification is the server's job; we only use
 * the parsed claims for UI display like token countdown and user_id).
 *
 * The backend JWT is HS256-signed with a 365-day expiry. Claims include
 * `user_id`, `device_id`, `iat`, `exp` — but NOT `is_admin` (the server
 * does a DB lookup on every request so an admin can be demoted and the
 * change takes effect immediately).
 */
object JwtUtil {

    /**
     * Parse the payload segment of a JWT into a [JSONObject].
     * Returns null if the token is malformed.
     */
    fun parsePayload(token: String?): JSONObject? {
        if (token.isNullOrEmpty()) return null
        val parts = token.split(".")
        if (parts.size != 3) return null
        return try {
            // JWT uses base64url (no padding). Base64.URL_SAFE|NO_PADDING|NO_WRAP.
            val payload = String(
                Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            )
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
