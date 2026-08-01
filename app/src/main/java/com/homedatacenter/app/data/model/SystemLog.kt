package com.homedatacenter.app.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * Severity level for a [SystemLog] entry (v1.6.36).
 *
 * Mirrors the backend `model.SystemLog.Level` field. The backend
 * assigns levels as follows:
 *   - "critical": camera offline, device offline
 *   - "normal":   user login/logout, device/camera online
 *   - "info":     camera status_changed (codec/quality)
 *
 * Empty string (from rows written before v1.6.36, or if the
 * backend omits the field) is treated as "normal" by the UI for
 * backward compatibility.
 */
object SystemLogLevel {
    const val CRITICAL = "critical"
    const val NORMAL = "normal"
    const val INFO = "info"
}

@Serializable
data class SystemLog(
    @SerialName("ID") val id: Long = 0,
    @SerialName("ts") val ts: Long = 0,
    @SerialName("EventType") val event_type: String = "",
    @SerialName("Level") val level: String = SystemLogLevel.NORMAL,
    @SerialName("Source") val source: String = "",
    @SerialName("Message") val message: String = "",
    @SerialName("Payload") val payload: String = ""
)

@Serializable
data class SystemLogListData(
    val logs: List<SystemLog> = emptyList(),
    val total: Long = 0
)
