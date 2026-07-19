package com.homedatacenter.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Camera(
    val id: Long,
    val type: String = "camera",
    val name: String,
    val vendor: String = "",
    val host: String = "",
    @SerialName("onvif_port") val onvifPort: Int = 80,
    @SerialName("rtsp_port") val rtspPort: Int = 554,
    @SerialName("channel_id") val channelId: Int = 1,
    val status: String = "offline",
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("owner_id") val ownerId: Long = 0,
    val transcode: Boolean = false,
    val codec: String = "h264",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val stream: StreamConfig? = null
) {
    val isOnline: Boolean get() = status == "online"
}

@Serializable
data class StreamConfig(
    @SerialName("stream_name") val streamName: String = "",
    @SerialName("webrtc_url") val webrtcUrl: String = "",
    @SerialName("hls_url") val hlsUrl: String = ""
)
