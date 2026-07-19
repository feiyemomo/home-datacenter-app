package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Device(
    val id: Long,
    @SerialName("user_id") val userId: Long,
    @SerialName("device_name") val deviceName: String,
    @SerialName("last_login_at") val lastLoginAt: String? = null,
    @SerialName("revoked_at") val revokedAt: String? = null,
    @SerialName("last_ip") val lastIp: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = ""
) {
    val isRevoked: Boolean get() = !revokedAt.isNullOrEmpty()
}

@Serializable
data class DeviceList(
    val devices: List<Device> = emptyList()
)
