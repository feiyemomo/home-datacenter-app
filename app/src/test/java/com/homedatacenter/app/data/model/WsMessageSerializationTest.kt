package com.homedatacenter.app.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WsMessage is the envelope for all WebSocket traffic (heartbeat,
 * subscribe, broadcast events). Verify encode/decode round-trips and
 * that a JSON payload survives the trip intact.
 */
class WsMessageSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun roundTrip_preservesFields() {
        val msg = WsMessage(
            type = WsMessageType.EVENT,
            topic = "cameras",
            ts = 123L,
        )
        val restored = json.decodeFromString<WsMessage>(json.encodeToString(WsMessage.serializer(), msg))
        assertEquals(WsMessageType.EVENT, restored.type)
        assertEquals("cameras", restored.topic)
        assertEquals(123L, restored.ts)
        assertNull(restored.payload)
    }

    @Test
    fun roundTrip_preservesNestedPayload() {
        val nested = buildJsonObject {
            put("camera_id", 5)
            put("online", true)
        }
        val msg = WsMessage(type = WsMessageType.EVENT, topic = "cameras", payload = nested, ts = 7)
        val restored = json.decodeFromString<WsMessage>(json.encodeToString(WsMessage.serializer(), msg))
        assertEquals(WsMessageType.EVENT, restored.type)
        assertTrue(restored.payload != null)
        val parsed = restored.payload
        assertEquals(5, (parsed?.get("camera_id") as JsonPrimitive).int)
        assertEquals(true, (parsed!!.get("online") as JsonPrimitive).boolean)
    }

    @Test
    fun defaults_appliedWhenAbsent() {
        val msg = json.decodeFromString<WsMessage>("""{"type":"heartbeat"}""")
        assertEquals(WsMessageType.HEARTBEAT, msg.type)
        assertNull(msg.topic)
        assertNull(msg.payload)
        assertEquals(0L, msg.ts)
    }

    @Test
    fun unknownFields_ignored() {
        val msg = json.decodeFromString<WsMessage>(
            """{"type":"event","topic":"x","extra":"field","another":123}"""
        )
        assertEquals(WsMessageType.EVENT, msg.type)
        assertEquals("x", msg.topic)
    }
}