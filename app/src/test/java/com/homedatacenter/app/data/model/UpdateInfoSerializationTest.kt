package com.homedatacenter.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UpdateInfo is what the in-app updater parses from
 * GET /api/v1/release/latest. Verify snake_case field mapping from the
 * backend JSON and that missing optional fields fall back to defaults.
 */
class UpdateInfoSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesAllFields_fromBackendJson() {
        val raw = """
            {
              "version_name": "1.8.44",
              "version_code": 10844,
              "download_url": "/api/v1/release/latest/apk",
              "file_name": "app-release-v1.8.44.apk",
              "size_bytes": 79571774,
              "release_notes": "test notes"
            }
        """.trimIndent()

        val info = json.decodeFromString<UpdateInfo>(raw)
        assertEquals("1.8.44", info.version_name)
        assertEquals(10844, info.version_code)
        assertEquals("/api/v1/release/latest/apk", info.download_url)
        assertEquals("app-release-v1.8.44.apk", info.file_name)
        assertEquals(79_571_774L, info.size_bytes)
        assertEquals("test notes", info.release_notes)
    }

    @Test
    fun missingOptionalFields_useDefaults() {
        val raw = """{"version_name":"1.0.0","version_code":100}"""
        val info = json.decodeFromString<UpdateInfo>(raw)
        assertEquals("1.0.0", info.version_name)
        assertEquals(100, info.version_code)
        assertEquals("", info.download_url)
        assertEquals("", info.file_name)
        assertEquals(0L, info.size_bytes)
        assertEquals("", info.release_notes)
    }

    @Test
    fun sizeBytes_roundTripsThroughEncode() {
        val info = UpdateInfo(
            version_name = "2.0.0",
            version_code = 20000,
            download_url = "/x",
            file_name = "app-release-v2.0.0.apk",
            size_bytes = 123_456_789L,
            release_notes = "big update",
        )
        val restored = json.decodeFromString<UpdateInfo>(json.encodeToString(UpdateInfo.serializer(), info))
        assertEquals(123_456_789L, restored.size_bytes)
        assertEquals("2.0.0", restored.version_name)
    }

    @Test
    fun flavor_detectsReleaseAndDebug() {
        val releaseInfo = UpdateInfo(file_name = "app-release-v1.10.4.apk", flavor = "release")
        val debugInfo = UpdateInfo(file_name = "app-debug-v1.10.4.apk", flavor = "debug")
        val legacyRelease = UpdateInfo(file_name = "app-release-v1.10.4.apk")
        val legacyDebug = UpdateInfo(file_name = "app-debug-v1.10.4.apk")

        assertEquals(true, releaseInfo.isRelease)
        assertEquals(false, releaseInfo.isDebug)

        assertEquals(false, debugInfo.isRelease)
        assertEquals(true, debugInfo.isDebug)

        assertEquals(true, legacyRelease.isRelease)
        assertEquals(false, legacyRelease.isDebug)

        assertEquals(false, legacyDebug.isRelease)
        assertEquals(true, legacyDebug.isDebug)
    }
}