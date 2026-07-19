package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Recording(
    val id: Long,
    @SerialName("camera_id") val cameraId: Long,
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String,
    @SerialName("duration_seconds") val durationSeconds: Int,
    @SerialName("segment_count") val segmentCount: Int = 0,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("size_human") val sizeHuman: String = "--",
    @SerialName("file_path") val filePath: String = ""
)
