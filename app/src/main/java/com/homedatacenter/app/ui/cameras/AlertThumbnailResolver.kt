package com.homedatacenter.app.ui.cameras

/**
 * Resolver for alert snapshot data models to pass into Coil.
 *
 * Guarantees:
 * - If embedded Base64 is present, safely decodes to [ByteArray] for direct in-memory
 *   bitmap rendering (bypassing Coil data URI fetcher failure).
 * - If Base64 is absent but [baseUrl] exists, computes the authenticated thumbnail endpoint URL.
 * - Handles trailing slashes gracefully.
 * - Works identically across JVM unit tests and Android runtime.
 */
object AlertThumbnailResolver {

    fun resolveModel(
        thumbnailBase64: String?,
        alertId: String,
        baseUrl: String?
    ): Any? {
        if (!thumbnailBase64.isNullOrBlank()) {
            val decoded = decodeBase64Safe(thumbnailBase64.trim())
            if (decoded != null && decoded.isNotEmpty()) {
                return decoded
            }
        }

        if (!baseUrl.isNullOrBlank()) {
            val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            return "${base}api/v1/cameras/alerts/$alertId/thumbnail"
        }

        return null
    }

    fun decodeBase64Safe(base64Str: String): ByteArray? {
        // Strip data:image/...;base64, prefix if accidentally present
        val cleanPayload = if (base64Str.startsWith("data:", ignoreCase = true)) {
            val commaIdx = base64Str.indexOf(',')
            if (commaIdx >= 0) base64Str.substring(commaIdx + 1) else base64Str
        } else {
            base64Str
        }

        return try {
            java.util.Base64.getDecoder().decode(cleanPayload)
        } catch (_: Throwable) {
            try {
                android.util.Base64.decode(cleanPayload, android.util.Base64.DEFAULT)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
