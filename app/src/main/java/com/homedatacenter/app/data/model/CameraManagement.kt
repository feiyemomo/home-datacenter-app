package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegisterCameraRequest(
    val name: String,
    val vendor: String = "hikvision",
    val host: String,
    @SerialName("onvif_port") val onvifPort: Int = 80,
    @SerialName("rtsp_port") val rtspPort: Int = 554,
    @SerialName("channel_id") val channelId: Int = 101,
    val username: String = "admin",
    val password: String,
    val ptz: Boolean = true,
    val audio: Boolean = true,
    val motion: Boolean = true,
    @SerialName("profile_token") val profileToken: String = "",
    val transcode: Boolean = false,
    val codec: String = "",
)

@Serializable
data class UpdateCodecRequest(
    val codec: String = "h264",
)

@Serializable
data class PtzRequest(
    val command: String,
    val speed: Double = 0.5,
    @SerialName("profile_token") val profileToken: String? = null,
)

@Serializable
data class RecordingPlanRequest(
    val enabled: Boolean,
    @SerialName("segment_seconds") val segmentSeconds: Int? = null,
    @SerialName("retention_days") val retentionDays: Int? = null,
)

@Serializable
data class CameraPreset(
    val token: String,
    val name: String = "",
)

@Serializable
data class SetPresetRequest(
    val token: String,
)

@Serializable
data class GotoPresetRequest(
    val speed: Double = 0.5,
)
