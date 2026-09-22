package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CpuMetrics(
    @SerialName("percent") val percent: Double = 0.0,
    @SerialName("cores") val cores: Int = 0
)

@Serializable
data class MemoryMetrics(
    @SerialName("total_bytes") val totalBytes: Long = 0,
    @SerialName("available_bytes") val availableBytes: Long = 0,
    @SerialName("used_bytes") val usedBytes: Long = 0,
    @SerialName("used_percent") val usedPercent: Double = 0.0
)

@Serializable
data class DiskMetrics(
    @SerialName("path") val path: String = "",
    @SerialName("total_bytes") val totalBytes: Long = 0,
    @SerialName("free_bytes") val freeBytes: Long = 0,
    @SerialName("used_bytes") val usedBytes: Long = 0,
    @SerialName("used_percent") val usedPercent: Double = 0.0
)

@Serializable
data class RecordingsMetrics(
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("quota_bytes") val quotaBytes: Long = 0,
    @SerialName("quota_percent") val quotaPercent: Double = 0.0,
    @SerialName("quota_active") val quotaActive: Boolean = false
)

@Serializable
data class TranscodeCacheMetrics(
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("file_count") val fileCount: Int = 0
)

@Serializable
data class SystemStatusMetrics(
    @SerialName("cpu") val cpu: CpuMetrics? = null,
    @SerialName("memory") val memory: MemoryMetrics? = null,
    @SerialName("disk") val disk: DiskMetrics? = null,
    @SerialName("recordings") val recordings: RecordingsMetrics? = null,
    @SerialName("transcode_cache") val transcodeCache: TranscodeCacheMetrics? = null,

    // Flat compatibility fields
    @SerialName("cpu_percent") val cpuPercent: Double? = null,
    @SerialName("memory_used_mb") val memoryUsedMb: Long? = null,
    @SerialName("memory_total_mb") val memoryTotalMb: Long? = null,
    @SerialName("memory_percent") val memoryPercent: Double? = null,
    @SerialName("recordings_used_gb") val recordingsUsedGb: Double? = null,
    @SerialName("recordings_limit_gb") val recordingsLimitGb: Double? = null,
    @SerialName("recordings_percent") val recordingsPercent: Double? = null,
    @SerialName("transcode_cache_mb") val transcodeCacheMb: Long? = null
) {
    val effectiveCpuPercent: Double
        get() = cpu?.percent ?: cpuPercent ?: 0.0

    val effectiveMemoryUsedMb: Long
        get() = memory?.let { it.usedBytes / (1024 * 1024) } ?: memoryUsedMb ?: 0L

    val effectiveMemoryTotalMb: Long
        get() = memory?.let { it.totalBytes / (1024 * 1024) } ?: memoryTotalMb ?: 0L

    val effectiveMemoryPercent: Double
        get() = memory?.usedPercent ?: memoryPercent ?: 0.0

    val effectiveRecordingsUsedGb: Double
        get() = recordings?.let { it.sizeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0) } ?: recordingsUsedGb ?: 0.0

    val effectiveRecordingsLimitGb: Double
        get() = recordings?.let {
            if (it.quotaBytes > 0) it.quotaBytes.toDouble() / (1024.0 * 1024.0 * 1024.0) else 400.0
        } ?: recordingsLimitGb ?: 400.0

    val effectiveRecordingsPercent: Double
        get() = recordings?.quotaPercent ?: recordingsPercent ?: 0.0

    val effectiveTranscodeCacheMb: Long
        get() = transcodeCache?.let { it.sizeBytes / (1024 * 1024) } ?: transcodeCacheMb ?: 0L
}

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
