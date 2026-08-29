package com.homedatacenter.app.util

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * Unit tests for [TokenRefreshInterceptor].
 *
 * Uses a MockWebServer for BOTH the intercepted API call and the
 * /api/v1/auth/bind re-bind call (baseUrlProvider points at the mock
 * server), with a path-based dispatcher. PrefsManager and TokenManager
 * are Mockito mocks (mockito 5 inline mockmaker handles Kotlin final
 * classes).
 *
 * Note: OkHttpClient is NOT Closeable in OkHttp 4.12, so client-side
 * .use { } blocks are intentionally avoided; responses (which ARE
 * Closeable) are closed via .use.
 */
class TokenRefreshInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var prefs: PrefsManager
    private lateinit var tokenManager: TokenManager

    // Main API responses served in order; bind responses are separate.
    private val mainResponses = ArrayDeque<MockResponse>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: "/"
                return if (path.startsWith("/api/v1/auth/bind")) {
                    MockResponse().setResponseCode(500).setBody("{}")
                } else {
                    mainResponses.removeFirstOrNull()
                        ?: MockResponse().setResponseCode(500).setBody("{}")
                }
            }
        }
        server.start()
        prefs = mock(PrefsManager::class.java)
        `when`(prefs.accessKey).thenReturn("ak-123")
        `when`(prefs.userId).thenReturn(42L)
        tokenManager = mock(TokenManager::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newClient(tokenInvalidCodes: Set<Int> = emptySet()): OkHttpClient {
        return OkHttpClient.Builder()
            .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(TokenRefreshInterceptor(prefs, tokenManager, tokenInvalidCodes))
            .build()
    }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun call(client: OkHttpClient, path: String = "/api/v1/devices"): okhttp3.Response {
        val req = Request.Builder().url(baseUrl() + path).build()
        return client.newCall(req).execute()
    }

    private fun MockResponse.json(body: String, code: Int = 200): MockResponse {
        setResponseCode(code).setBody(body).setHeader("Content-Type", "application/json")
        return this
    }

    @Test
    fun `non-401 response passes through untouched`() {
        mainResponses.add(MockResponse().json("""{"code":0,"data":{"ok":true}}"""))
        val client = newClient(setOf(4001))
        call(client).use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body!!.string().contains(""""ok""""))
        }
        verify(tokenManager, never()).refreshAndPersist(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun `401 with unrelated message returns readable body and does not refresh`() {
        mainResponses.add(MockResponse().json("""{"code":1,"message":"no permission"}""", 401))
        val client = newClient(setOf(4001))
        call(client).use { resp ->
            assertEquals(401, resp.code)
            // v1.10.0 fix: the body must still be readable.
            assertTrue(resp.body!!.string().contains("no permission"))
        }
        verify(tokenManager, never()).refreshAndPersist(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun `401 with malformed body passes through`() {
        mainResponses.add(MockResponse().json("<html>denied</html>", 401))
        val client = newClient(setOf(4001))
        call(client).use { resp ->
            assertEquals(401, resp.code)
            assertTrue(resp.body!!.string().contains("denied"))
        }
        verify(tokenManager, never()).refreshAndPersist(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun `401 with matching business code rebinds retries and persists`() {
        mainResponses.add(MockResponse().json("""{"code":4001,"message":"ignored"}""", 401))
        mainResponses.add(MockResponse().json("""{"code":0,"data":{"ok":true}}"""))
        `when`(tokenManager.refreshAndPersist(42L, "ak-123")).thenReturn("tk-2")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: "/"
                return if (path.startsWith("/api/v1/auth/bind")) {
                    MockResponse().json("""{"code":0,"data":{"token":"tk-2"}}""")
                } else {
                    mainResponses.removeFirstOrNull() ?: MockResponse().setResponseCode(500)
                }
            }
        }
        val client = newClient(setOf(4001))
        call(client).use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body!!.string().contains(""""ok""""))
        }
        // Persistence happens inside the real TokenManager; here the
        // interceptor must simply delegate the re-bind to it.
        verify(tokenManager).refreshAndPersist(42L, "ak-123")
        // Request order: original 401 call, then the retried call. The bind
        // HTTP exchange itself is covered by TokenManagerTest (TokenManager
        // is a mock here and never touches the network).
        val r0 = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS) ?: throw AssertionError("r0 missing")
        val r1 = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS) ?: throw AssertionError("r1 missing")
        assertTrue(r0.path!!.startsWith("/api/v1/devices"))
        assertTrue(r1.path!!.startsWith("/api/v1/devices"))
        assertEquals("Bearer tk-2", r1.getHeader("Authorization"))
    }

    @Test
    fun `401 with legacy message falls back to rebind`() {
        mainResponses.add(MockResponse().json("""{"code":99,"message":"token version mismatch"}""", 401))
        mainResponses.add(MockResponse().json("""{"code":0,"data":{"ok":true}}"""))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: "/"
                return if (path.startsWith("/api/v1/auth/bind")) {
                    MockResponse().json("""{"code":0,"data":{"token":"tk-3"}}""")
                } else {
                    mainResponses.removeFirstOrNull() ?: MockResponse().setResponseCode(500)
                }
            }
        }
        `when`(tokenManager.refreshAndPersist(42L, "ak-123")).thenReturn("tk-3")
        val client = newClient()
        call(client).use { resp ->
            assertEquals(200, resp.code)
        }
        verify(tokenManager).refreshAndPersist(42L, "ak-123")
    }

    @Test
    fun `failed rebind returns original 401 with readable body`() {
        mainResponses.add(MockResponse().json("""{"code":4001,"message":"expired"}""", 401))
        `when`(tokenManager.refreshAndPersist(42L, "ak-123")).thenReturn(null)
        val client = newClient(setOf(4001))
        call(client).use { resp ->
            assertEquals(401, resp.code)
            // v1.10.0 regression test: the body must be readable even
            // after a failed re-bind (used to be a closed response).
            assertTrue(resp.body!!.string().contains("expired"))
        }
    }

    @Test
    fun `missing access key skips rebind and returns readable 401`() {
        `when`(prefs.accessKey).thenReturn(null)
        mainResponses.add(MockResponse().json("""{"code":4001,"message":"expired"}""", 401))
        val client = newClient(setOf(4001))
        call(client).use { resp ->
            assertEquals(401, resp.code)
            assertTrue(resp.body!!.string().contains("expired"))
        }
        verify(tokenManager, never()).refreshAndPersist(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString())
    }
}