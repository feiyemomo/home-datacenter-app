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

/**
 * v1.6.0: motion ranges for the day-playback SeekBar overlay.
 *
 * Each range is a (startUnix, endUnix) pair in unix seconds. The
 * backend returns them as a JSON array of 2-element arrays:
 * `{"ranges": [[1784537584, 1784537594], [1784537615, 1784537625]], "total": 2}`
 *
 * We don't model this as a @Serializable data class because
 * kotlinx.serialization can't directly decode `List<Pair<Long,Long>>`
 * from bare JSON arrays. The RecordingsDialog decodes the JSON
 * manually using kotlinx.serialization.json.JsonArray.
 */
data class MotionRangesData(
    val ranges: List<Pair<Long, Long>> = emptyList(),
    val total: Int = 0,
)
