package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SystemStatus(
    @SerialName("mqtt_connected") val mqttConnected: Boolean = false,
    @SerialName("ws_clients") val wsClients: Int = 0,
    @SerialName("online_device_count") val onlineDeviceCount: Int = 0,
    @SerialName("online_device_ids") val onlineDeviceIds: List<Long>? = null,
    @SerialName("uptime_seconds") val uptimeSeconds: Long = 0,
    @SerialName("server_time") val serverTime: String = ""
)
