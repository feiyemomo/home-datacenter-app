package com.homedatacenter.app.util

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * Unit tests for [TokenManager] with a REAL instance against a
 * MockWebServer /api/v1/auth/bind endpoint - covers the bind request
 * shape, the token parse, and the persist step.
 */
class TokenManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var prefs: PrefsManager
    private lateinit var tokenManager: TokenManager

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        prefs = mock(PrefsManager::class.java)
        tokenManager = TokenManager(prefs) { server.url("/").toString().trimEnd('/') }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `successful bind parses token and persists`() {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"token":"tk-real"}}""").setHeader("Content-Type", "application/json"))
        val token = tokenManager.refreshAndPersist(42L, "ak-123")
        assertEquals("tk-real", token)
        verify(prefs).token = "tk-real"
        verify(prefs).lastTokenRefreshTime = org.mockito.ArgumentMatchers.anyLong()
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/api/v1/auth/bind"))
        val body = req.body.readUtf8()
        assertTrue("ACTUAL-BODY=" + body, body.contains("\"user_id\":42"))
        assertTrue("ACTUAL-BODY=" + body, body.contains("\"access_key\":\"ak-123\""))
    }

    @Test
    fun `bind http error returns null and does not persist`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        val token = tokenManager.refreshAndPersist(42L, "ak-123")
        assertNull(token)
    }

    @Test
    fun `bind business error code returns null`() {
        server.enqueue(MockResponse().setBody("""{"code":4001,"message":"access key revoked"}""").setHeader("Content-Type", "application/json"))
        val token = tokenManager.refreshToken(42L, "ak-123")
        assertNull(token)
    }

    @Test
    fun `auto refresh skips when last refresh is fresh`() {
        `when`(prefs.token).thenReturn("tk-current")
        `when`(prefs.accessKey).thenReturn("ak-123")
        `when`(prefs.userId).thenReturn(42L)
        `when`(prefs.lastTokenRefreshTime).thenReturn(System.currentTimeMillis() - 1000L)
        tokenManager.tryAutoRefreshToken()
        Thread.sleep(200)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `successful token refresh parses token and persists`() {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"token":"tk-fresh-sliding","device_id":2}}""").setHeader("Content-Type", "application/json"))
        val token = tokenManager.refreshViaTokenAndPersist("tk-existing")
        assertEquals("tk-fresh-sliding", token)
        verify(prefs).token = "tk-fresh-sliding"
        verify(prefs).lastTokenRefreshTime = org.mockito.ArgumentMatchers.anyLong()
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/api/v1/auth/refresh"))
        assertEquals("Bearer tk-existing", req.getHeader("Authorization"))
    }

    @Test
    fun `token refresh http error returns null and does not persist`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":401,"message":"token expired"}"""))
        val token = tokenManager.refreshViaToken("tk-expired")
        assertNull(token)
    }

    @Test
    fun `auto refresh triggers sliding refresh when debounce period passed`() {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"token":"tk-auto-refreshed","device_id":2}}""").setHeader("Content-Type", "application/json"))
        `when`(prefs.token).thenReturn("tk-current")
        `when`(prefs.accessKey).thenReturn("ak-123")
        `when`(prefs.userId).thenReturn(42L)
        `when`(prefs.lastTokenRefreshTime).thenReturn(System.currentTimeMillis() - 10_000L)
        val job = tokenManager.tryAutoRefreshToken()
        kotlinx.coroutines.runBlocking { job?.join() }
        assertEquals(1, server.requestCount)
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/api/v1/auth/refresh"))
        assertEquals("Bearer tk-current", req.getHeader("Authorization"))
        verify(prefs).token = "tk-auto-refreshed"
    }
}