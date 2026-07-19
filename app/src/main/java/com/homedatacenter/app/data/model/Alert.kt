package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Alert(
    val id: String,
    @SerialName("camera_slug") val cameraSlug: String = "",
    @SerialName("camera_id") val cameraId: Long? = null,
    @SerialName("camera_name") val cameraName: String = "",
    val label: String,
    val confidence: Double,
    @SerialName("start_time") val startTime: Double,
    @SerialName("end_time") val endTime: Double,
    val zones: List<String> = emptyList(),
    @SerialName("has_clip") val hasClip: Boolean = false,
    @SerialName("has_snapshot") val hasSnapshot: Boolean = false,
    val thumbnail: String = ""
)

@Serializable
data class AlertListData(
    val alerts: List<Alert> = emptyList(),
    val total: Int = 0
)
