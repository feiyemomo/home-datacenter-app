package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * User account record returned by /api/v1/user/me and the admin user endpoints.
 *
 * The /me endpoint only returns {id, name, is_admin} — the optional fields
 * below are populated by the admin-only GET /api/v1/user and GET /api/v1/user/:id
 * endpoints. We model them as nullable so a single data class can carry either
 * response shape; kotlinx.serialization with explicitNulls = false will skip
 * null fields when encoding.
 */
@Serializable
data class User(
    val id: Long,
    val name: String,
    @SerialName("is_admin") val isAdmin: Boolean,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("device_count") val deviceCount: Int? = null,
)

/**
 * Wrapper for GET /api/v1/user response: { users: [...] }
 */
@Serializable
data class UserList(
    val users: List<User> = emptyList(),
)

/**
 * Request body for POST /api/v1/user (create user).
 *
 * @param name 1..32 runes; unicode letters/digits/_/-
 * @param isAdmin grant admin role
 * @param initialDeviceName optional — when set, the server creates a first
 *   auth device for the new user and returns the plaintext AccessKey once.
 */
@Serializable
data class CreateUserRequest(
    val name: String,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("initial_device_name") val initialDeviceName: String? = null,
)

/**
 * Response data for POST /api/v1/user when initialDeviceName is set.
 *
 * @param accessKey 64-char hex plaintext access key — shown only once at
 *   creation time. The server stores only the SHA-256 hash.
 */
@Serializable
data class CreateUserResult(
    val id: Long,
    val name: String,
    @SerialName("is_admin") val isAdmin: Boolean,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val device: CreatedDeviceRef? = null,
    @SerialName("access_key") val accessKey: String? = null,
)

@Serializable
data class CreatedDeviceRef(
    val id: Long,
    @SerialName("device_name") val deviceName: String,
)

/**
 * Request body for PUT /api/v1/user/:id (partial update).
 *
 * Both fields are nullable pointers so the server can distinguish
 * "not provided" from "set to false / empty".
 */
@Serializable
data class UpdateUserRequest(
    val name: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean? = null,
)

/**
 * Response data for DELETE /api/v1/user/:id: { deleted_devices: N }
 */
@Serializable
data class DeleteUserResult(
    @SerialName("deleted_devices") val deletedDevices: Int = 0,
)
