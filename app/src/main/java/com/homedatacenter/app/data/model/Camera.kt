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
    // Capabilities mirrors the backend model.Camera.Capabilities
    // JSON map. The audio flag determines whether the go2rtc source
    // URL has #audio=aac appended (transcoding PCMA → AAC for
    // browser/ExoPlayer decoding). The ptz flag controls PTZ UI
    // visibility. The motion flag indicates Frigate motion
    // detection is enabled. Defaulted to an empty map so legacy
    // backends that don't return the field still deserialize.
    val capabilities: Map<String, Boolean> = emptyMap(),
    // Meta mirrors the backend model.Camera.Meta JSON map. Used to
    // surface recording plan state ({recording: {enabled, retention_days,
    // segment_seconds}}) and other operator-set metadata. Defaulted
    // to empty map for backward compat.
    val meta: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    val stream: StreamConfig? = null
) {
    val isOnline: Boolean get() = status == "online"

    /**
     * True when the backend has enabled audio transcoding for this
     * camera. The go2rtc source URL will include `#audio=aac` so the
     * live MP4/HLS/WebRTC stream carries an AAC audio track that
     * ExoPlayer and modern browsers can decode natively. The original
     * PCMA from Hikvision cameras is not browser-decodable.
     *
     * The audio capability is a per-camera toggle set by an admin via
     * PUT /api/v1/cameras/:id/audio. The flag is stored as a generic
     * JSON value in the capabilities map; this getter coerces it to
     * a Boolean (defaults to false when missing or wrong type).
     */
    val hasAudio: Boolean
        get() = capabilities["audio"] == true

    /**
     * True when the camera supports ONVIF PTZ commands. Controls
     * whether the PTZ directional pad is shown on the camera card.
     */
    val hasPtz: Boolean
        get() = capabilities["ptz"] == true

    /**
     * True when Frigate motion detection is enabled for this camera.
     * Surfaced as a UI hint (badge) only — motion detection itself
     * is configured at the Frigate level, not per-API-call.
     */
    val hasMotion: Boolean
        get() = capabilities["motion"] == true
}

@Serializable
data class StreamConfig(
    @SerialName("stream_name") val streamName: String = "",
    @SerialName("webrtc_url") val webrtcUrl: String = "",
    @SerialName("hls_url") val hlsUrl: String = ""
)
