package com.homedatacenter.app.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

@Serializable
private data class SamplePayload(val name: String, val count: Int = 0)

/**
 * Pure-JVM tests for [ApiResponse.decodeData] / [decodeDataOrThrow], the
 * envelope decoder every repository call goes through. Verifies the
 * code!=0 throw path, the data:null path, and the happy path.
 */
class ApiResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun envelope(code: Int, message: String, data: String): String =
        """{"code":$code,"message":"$message","data":$data}"""

    @Test
    fun decodeData_successDecodesPayload() {
        val response = json.decodeFromString<ApiResponse>(
            envelope(0, "success", """{"name":"cam1","count":3}""")
        )
        val payload = response.decodeData<SamplePayload>()
        assertEquals("cam1", payload?.name)
        assertEquals(3, payload?.count)
    }

    @Test
    fun decodeDataNull_whenDataMissingReturnsNull() {
        val response = json.decodeFromString<ApiResponse>(
            """{"code":0,"message":"success","data":null}"""
        )
        assertNull(response.decodeData<SamplePayload>())
    }

    @Test(expected = ApiException::class)
    fun decodeData_throws_whenBusinessError() {
        val response = json.decodeFromString<ApiResponse>(
            envelope(1001, "some business error", "{}")
        )
        response.decodeData<SamplePayload>()
    }

    @Test
    fun decodeDataOrThrow_throws_whenDataNull() {
        val response = json.decodeFromString<ApiResponse>(
            """{"code":0,"message":"success","data":null}"""
        )
        try {
            response.decodeDataOrThrow<SamplePayload>()
            fail("expected ApiException when data is null")
        } catch (e: ApiException) {
            assertEquals(0, e.code)
        }
    }

    @Test
    fun decodeDataOrThrow_succeeds_whenPresent() {
        val response = json.decodeFromString<ApiResponse>(
            envelope(0, "success", """{"name":"cam2"}""")
        )
        assertEquals("cam2", response.decodeDataOrThrow<SamplePayload>().name)
    }

    @Test
    fun customJsonRoundTrip_viaNetworkFactoryJson() {
        // decodeData uses NetworkFactory.json under the hood; ensure a
        // realistic envelope decodes cleanly (ignoreUnknownKeys).
        val response = json.decodeFromString<ApiResponse>(
            envelope(0, "success", """{"name":"x","count":1,"extra":"ignored"}""")
        )
        assertEquals(1, response.decodeData<SamplePayload>()?.count)
    }
}
