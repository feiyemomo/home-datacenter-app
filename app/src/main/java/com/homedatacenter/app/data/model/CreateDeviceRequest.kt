package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for POST /api/v1/device (admin creates a new auth
 * device for the current user).
 *
 * @param deviceName 1..64 chars; the human-friendly label shown in
 *   the device list (e.g. "Pixel 7", "客厅平板").
 */
@Serializable
data class CreateDeviceRequest(
    @SerialName("device_name") val deviceName: String,
)

/**
 * Response data for POST /api/v1/device.
 *
 * Backend returns `{ "device": {...}, "access_key": "..." }`. The
 * plaintext [accessKey] is shown ONCE at creation time — the server
 * stores only the SHA-256 hash, so it cannot be retrieved later.
 *
 * @param device the newly created Device record (may be null if the
 *   server omits it).
 * @param accessKey 64-char hex plaintext access key — empty if the
 *   server did not return one.
 */
@Serializable
data class CreateDeviceResponse(
    val device: Device? = null,
    @SerialName("access_key") val accessKey: String = "",
)
