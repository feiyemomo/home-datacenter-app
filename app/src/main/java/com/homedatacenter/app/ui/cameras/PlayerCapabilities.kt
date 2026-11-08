package com.homedatacenter.app.ui.cameras

/**
 * Stream transport protocol for live and VOD playback.
 */
enum class StreamTransport {
    WEBRTC,
    MP4,
    HLS,
    NONE
}

/**
 * Operational mode of the player (Live feed vs VOD recording playback).
 */
enum class PlaybackMode {
    LIVE,
    VOD
}

/**
 * Unified capabilities interface exposing player state, capabilities, and controls.
 * Live streams hide seeking and speed adjustment, while VOD streams enable them.
 */
interface PlayerCapabilities {
    val isLive: Boolean
    val supportsSeeking: Boolean
    val supportsSpeedAdjustment: Boolean
    val currentTransport: StreamTransport get() = StreamTransport.NONE
    val playbackMode: PlaybackMode get() = if (isLive) PlaybackMode.LIVE else PlaybackMode.VOD
    val isPlaying: Boolean get() = false
    val currentPositionMs: Long get() = 0L
    val durationMs: Long get() = 0L

    fun play() {}
    fun pause() {}
    fun seekTo(positionMs: Long) {}
    fun setPlaybackSpeed(speed: Float) {}
    fun setAudioEnabled(enabled: Boolean) {}
    fun resetZoom(animate: Boolean = false)
}
