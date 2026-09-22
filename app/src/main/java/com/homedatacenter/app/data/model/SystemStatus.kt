package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SystemStatusMetrics(
    @SerialName("cpu_percent") val cpuPercent: Double = 0.0,
    @SerialName("memory_used_mb") val memoryUsedMb: Long = 0,
    @SerialName("memory_total_mb") val memoryTotalMb: Long = 0,
    @SerialName("memory_percent") val memoryPercent: Double = 0.0,
    @SerialName("recordings_used_gb") val recordingsUsedGb: Double = 0.0,
    @SerialName("recordings_limit_gb") val recordingsLimitGb: Double = 400.0,
    @SerialName("recordings_percent") val recordingsPercent: Double = 0.0,
    @SerialName("transcode_cache_mb") val transcodeCacheMb: Long = 0
)

@Serializable
data class SystemStatus(
    @SerialName("mqtt_connected") val mqttConnected: Boolean = false,
    @SerialName("ws_clients") val wsClients: Int = 0,
    @SerialName("online_device_count") val onlineDeviceCount: Int = 0,
    @SerialName("online_device_ids") val onlineDeviceIds: List<Long>? = null,
    @SerialName("uptime_seconds") val uptimeSeconds: Long = 0,
    @SerialName("server_time") val serverTime: String = "",
    @SerialName("metrics") val metrics: SystemStatusMetrics? = null
)
