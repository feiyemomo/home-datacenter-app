package com.homedatacenter.app.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * Severity level for a [SystemLog] entry (v1.6.36).
 *
 * Mirrors the backend `model.SystemLog.Level` field. The backend
 * assigns levels as follows:
 *   - "critical": camera offline, device offline
 *   - "warning":  recordings quota / disk / backup threshold crossed
 *                 (v1.8.36), rendered amber
 *   - "normal":   user login/logout, device/camera online
 *   - "info":     camera status_changed (codec/quality)
 *
 * Empty string (from rows written before v1.6.36, or if the
 * backend omits the field) is treated as "normal" by the UI for
 * backward compatibility.
 */
object SystemLogLevel {
    const val CRITICAL = "critical"
    const val WARNING = "warning"
    const val NORMAL = "normal"
    const val INFO = "info"
}

@Serializable
data class SystemLog(
    @SerialName("id") val id: Long = 0,
    @SerialName("ts") val ts: Long = 0,
    @SerialName("event_type") val event_type: String = "",
    @SerialName("level") val level: String = SystemLogLevel.NORMAL,
    @SerialName("source") val source: String = "",
    @SerialName("message") val message: String = "",
    @SerialName("payload") val payload: String = ""
)

@Serializable
data class SystemLogListData(
    val logs: List<SystemLog> = emptyList(),
    val total: Long = 0
)
