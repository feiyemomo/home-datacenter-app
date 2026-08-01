package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for POST /api/v1/cameras/:id/shares.
 *
 * @param userId the user to share the camera with.
 */
@Serializable
data class ShareCameraRequest(
    @SerialName("user_id") val userId: Long,
)

/**
 * A camera share record returned by GET /api/v1/cameras/:id/shares.
 *
 * The backend persists one row per (camera_id, user_id) pair; the
 * [id] is the share row's primary key, not the user id. Use
 * [userId] to identify the granted user when calling DELETE.
 */
@Serializable
data class CameraShare(
    val id: Long,
    @SerialName("camera_id") val cameraId: Long,
    @SerialName("user_id") val userId: Long,
    @SerialName("created_at") val createdAt: String = "",
)
