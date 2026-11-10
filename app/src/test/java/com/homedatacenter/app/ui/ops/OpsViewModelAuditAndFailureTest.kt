package com.homedatacenter.app.ui.ops

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.IOException

/**
 * Verifies Fail-Closed behavior of OpsViewModel mutating operations (no fake-success on 403/409/500/IOException)
 * and end-to-end AgentAuditEngine API integration (/api/v1/ops/audit/runs, detail, verify).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpsViewModelAuditAndFailureTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApp: Application

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockApp = Mockito.mock(Application::class.java)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildClient(handler: (okhttp3.Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()
    }

    private fun jsonResponse(request: okhttp3.Request, code: Int, body: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    @Test
    fun restartContainer_httpErrorsAndIOException_failClosedWithoutMutatingState() = runTest {
        val codes = listOf(403, 409, 500)
        for (code in codes) {
            val client = buildClient { req ->
                jsonResponse(req, code, """{"error":"denied_$code"}""")
            }
            val vm = OpsViewModel(
                application = mockApp,
                baseUrlProvider = { "http://localhost:8080" },
                tokenProvider = { "jwt-admin-token" },
                httpClientOverride = client,
                ioDispatcher = testDispatcher
            )
            val beforeRestartCount = vm.containers.value.first { it.name == "home-api" }.restartCount
            var cbCalled = false
            var cbSuccess = true
            var cbMsg: String? = null

            vm.restartContainer("home-api") { ok, msg ->
                cbCalled = true
                cbSuccess = ok
                cbMsg = msg
            }
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue("HTTP $code must invoke completion callback", cbCalled)
            assertFalse("HTTP $code must NOT report fake success", cbSuccess)
            assertTrue("HTTP $code must include backend error", cbMsg?.contains("denied_$code") == true)
            assertEquals(
                "HTTP $code must NOT increment local restartCount",
                beforeRestartCount,
                vm.containers.value.first { it.name == "home-api" }.restartCount
            )
            assertFalse(vm.restartingContainers.value.containsKey("home-api"))
        }

        // Network IOException must also fail closed
        val failingClient = buildClient {
            throw IOException("connection refused")
        }
        val vmNet = OpsViewModel(
            application = mockApp,
            baseUrlProvider = { "http://localhost:8080" },
            tokenProvider = { "jwt-admin-token" },
            httpClientOverride = failingClient,
            ioDispatcher = testDispatcher
        )
        val beforeCount = vmNet.containers.value.first { it.name == "home-api" }.restartCount
        var netSuccess = true
        var netMsg: String? = null
        vmNet.restartContainer("home-api") { ok, msg ->
            netSuccess = ok
            netMsg = msg
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("IOException must NOT report fake success", netSuccess)
        assertTrue(netMsg?.contains("connection refused") == true)
        assertEquals(beforeCount, vmNet.containers.value.first { it.name == "home-api" }.restartCount)
    }

    @Test
    fun approveProposal_httpErrorsAndIOException_failClosedWithoutMutatingState() = runTest {
        val codes = listOf(403, 409, 500)
        for (code in codes) {
            val client = buildClient { req ->
                jsonResponse(req, code, """{"message":"conflict_or_forbidden_$code"}""")
            }
            val vm = OpsViewModel(
                application = mockApp,
                baseUrlProvider = { "http://localhost:8080" },
                tokenProvider = { "jwt-admin-token" },
                httpClientOverride = client,
                ioDispatcher = testDispatcher
            )
            val beforeStatus = vm.pendingProposals.value.first { it.id == "prop-101" }.status
            var cbCalled = false
            var cbSuccess = true
            var cbMsg: String? = null

            vm.approveProposal("prop-101", scheduleAt = null) { ok, msg ->
                cbCalled = true
                cbSuccess = ok
                cbMsg = msg
            }
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(cbCalled)
            assertFalse("HTTP $code approveProposal must NOT report fake success", cbSuccess)
            assertTrue(cbMsg?.contains("conflict_or_forbidden_$code") == true)
            assertEquals(
                "HTTP $code approveProposal must NOT change local proposal status",
                beforeStatus,
                vm.pendingProposals.value.first { it.id == "prop-101" }.status
            )
            assertFalse(vm.approvingProposals.value.containsKey("prop-101"))
        }

        // Network failure must also fail closed
        val failingClient = buildClient {
            throw IOException("socket timeout")
        }
        val vmNet = OpsViewModel(
            application = mockApp,
            baseUrlProvider = { "http://localhost:8080" },
            tokenProvider = { "jwt-admin-token" },
            httpClientOverride = failingClient,
            ioDispatcher = testDispatcher
        )
        var netSuccess = true
        var netMsg: String? = null
        vmNet.approveProposal("prop-101", scheduleAt = "03:00") { ok, msg ->
            netSuccess = ok
            netMsg = msg
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("IOException approveProposal must fail closed", netSuccess)
        assertTrue(netMsg?.contains("socket timeout") == true)
        assertEquals("AWAITING_APPROVAL", vmNet.pendingProposals.value.first { it.id == "prop-101" }.status)
    }

    @Test
    fun auditRunsDetailAndVerifyChain_parseBackendPayloadsCorrectly() = runTest {
        val client = buildClient { req ->
            val path = req.url.encodedPath
            when {
                path == "/api/v1/ops/audit/runs" -> jsonResponse(
                    req,
                    200,
                    """
                    {
                      "code": 200,
                      "data": {
                        "items": [
                          {
                            "run_id": "run-abc-123",
                            "correlation_id": "corr-1",
                            "trigger_source": "ops_agent_fsm",
                            "actor_type": "agent",
                            "actor_id": "ops-fsm",
                            "action": "restart_container",
                            "target": "frigate-nvr",
                            "component": "frigate",
                            "decision": "ALLOW",
                            "risk_level": "MEDIUM",
                            "status": "COMPLETED",
                            "is_offline": false,
                            "event_count": 3,
                            "first_seq": 10,
                            "last_seq": 12,
                            "started_at_ms": 1762800000000
                          }
                        ],
                        "total": 1
                      }
                    }
                    """.trimIndent()
                )
                path == "/api/v1/ops/audit/runs/run-abc-123" -> jsonResponse(
                    req,
                    200,
                    """
                    {
                      "code": 200,
                      "data": {
                        "run": {
                          "run_id": "run-abc-123",
                          "action": "restart_container",
                          "target": "frigate-nvr",
                          "component": "frigate",
                          "status": "COMPLETED",
                          "event_count": 2,
                          "first_seq": 10,
                          "last_seq": 11
                        },
                        "events": [
                          {
                            "audit_id": "aud-10",
                            "seq": 10,
                            "stream_id": "core",
                            "run_id": "run-abc-123",
                            "event_type": "EXECUTION_STARTED",
                            "actor_type": "agent",
                            "actor_id": "ops-fsm",
                            "decision": "ALLOW",
                            "result": "STARTED",
                            "entry_hash": "deadbeef1234567890",
                            "created_at_ms": 1762800000100
                          },
                          {
                            "audit_id": "aud-11",
                            "seq": 11,
                            "stream_id": "core",
                            "run_id": "run-abc-123",
                            "event_type": "EXECUTION_COMPLETED",
                            "actor_type": "agent",
                            "actor_id": "ops-fsm",
                            "decision": "ALLOW",
                            "result": "SUCCESS",
                            "entry_hash": "cafebabe9876543210",
                            "created_at_ms": 1762800001200
                          }
                        ],
                        "integrity": {
                          "valid": true,
                          "integrity_status": "VERIFIED_AGAINST_CHECKPOINT",
                          "stream_id": "core",
                          "checked_events": 11,
                          "checked_checkpoints": 1,
                          "triggers_intact": true,
                          "limitation_note": "Local NAS caveat"
                        }
                      }
                    }
                    """.trimIndent()
                )
                path == "/api/v1/ops/audit/verify" -> jsonResponse(
                    req,
                    200,
                    """
                    {
                      "code": 200,
                      "data": {
                        "valid": false,
                        "integrity_status": "HASH_MISMATCH",
                        "stream_id": "core",
                        "checked_events": 12,
                        "checked_checkpoints": 1,
                        "triggers_intact": true,
                        "broken_at_seq": 9,
                        "failure_reason": "entry_hash mismatch at seq 9",
                        "limitation_note": "Local NAS caveat"
                      }
                    }
                    """.trimIndent()
                )
                else -> jsonResponse(req, 404, """{"error":"not found"}""")
            }
        }

        val vm = OpsViewModel(
            application = mockApp,
            baseUrlProvider = { "http://localhost:8080" },
            tokenProvider = { "jwt-admin-token" },
            httpClientOverride = client,
            ioDispatcher = testDispatcher
        )

        vm.fetchAuditRuns(status = "COMPLETED", limit = 20)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.auditError.value)
        assertEquals(1, vm.auditRuns.value.size)
        assertEquals("run-abc-123", vm.auditRuns.value.first().runId)
        assertEquals("frigate", vm.auditRuns.value.first().component)

        vm.fetchAuditRunDetail("run-abc-123")
        testDispatcher.scheduler.advanceUntilIdle()

        val detail = vm.selectedAuditDetail.value
        assertNotNull(detail)
        assertEquals(2, detail!!.events.size)
        assertEquals("EXECUTION_STARTED", detail.events[0].eventType)
        assertEquals("VERIFIED_AGAINST_CHECKPOINT", detail.integrity?.integrityStatus)

        vm.verifyAuditChain()
        testDispatcher.scheduler.advanceUntilIdle()

        val verify = vm.auditVerifyResult.value
        assertNotNull(verify)
        assertFalse(verify!!.valid)
        assertEquals("HASH_MISMATCH", verify.integrityStatus)
        assertEquals(9L, verify.brokenAtSeq)

        vm.clearSelectedAuditDetail()
        assertNull(vm.selectedAuditDetail.value)
    }
}
