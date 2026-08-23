package com.homedatacenter.app.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the derived capability/meta getters on [Camera].
 * These drive UI visibility (PTZ pad, audio toggle, motion badge) and
 * the Frigate continuous-recording switch, so deserializing a real
 * backend payload must produce the right booleans.
 */
class CameraTest {

    private fun camera(
        capabilities: Map<String, Boolean> = emptyMap(),
        meta: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    ) = Camera(id = 1, name = "c", capabilities = capabilities, meta = meta)

    @Test
    fun hasAudio_reflectsCapability() {
        assertTrue(camera(capabilities = mapOf("audio" to true)).hasAudio)
        assertFalse(camera(capabilities = mapOf("audio" to false)).hasAudio)
        assertFalse(camera().hasAudio)
    }

    @Test
    fun hasPtz_reflectsCapability() {
        assertTrue(camera(capabilities = mapOf("ptz" to true)).hasPtz)
        assertFalse(camera().hasPtz)
    }

    @Test
    fun hasMotion_reflectsCapability() {
        assertTrue(camera(capabilities = mapOf("motion" to true)).hasMotion)
        assertFalse(camera().hasMotion)
    }

    @Test
    fun isRecordingEnabled_trueWhenMetaRecordingObjectEnabled() {
        val meta = mapOf(
            "recording" to buildJsonObject {
                put("enabled", true)
                put("retention_days", 7)
                put("segment_seconds", 60)
            }
        )
        assertTrue(camera(meta = meta).isRecordingEnabled)
    }

    @Test
    fun isRecordingEnabled_falseWhenDisabled_legacyBareBoolean() {
        assertFalse(
            camera(meta = mapOf("recording" to buildJsonObject { put("enabled", false) }))
                .isRecordingEnabled
        )
        // Legacy bare-boolean form.
        assertTrue(
            camera(meta = mapOf("recording" to kotlinx.serialization.json.JsonPrimitive(true)))
                .isRecordingEnabled
        )
    }

    @Test
    fun isRecordingEnabled_falseWhenMissingOrMalformed() {
        assertFalse(camera().isRecordingEnabled)
        assertFalse(camera(meta = mapOf("recording" to kotlinx.serialization.json.JsonPrimitive("garbage"))).isRecordingEnabled)
        assertFalse(camera(meta = mapOf("recording" to kotlinx.serialization.json.JsonPrimitive(123))).isRecordingEnabled)
    }

    @Test
    fun streamConfig_deserializesWithHlsHevc() {
        val camera = Json.decodeFromString<Camera>(
            """{"id":1,"name":"c","stream":{"stream_name":"main","webrtc_url":"wss://x","hls_url":"http://a","hls_hevc_url":"http://b"}}"""
        )
        assertTrue(camera.stream?.hlsHevcUrl == "http://b")
    }
}
