package com.homedatacenter.app.ui.ops

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * Empirical Stress & Edge Case Test Suite for OpsCenter ViewModel & UI State (Challenge 3).
 *
 * Verifies:
 * - Proposal scheduling (03:00 low-peak vs. immediate execution)
 * - Risk level tagging and badge semantics
 * - Asynchronous state updates, map lifecycles, and toast notifications
 * - Rapid concurrent operations and edge inputs
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpsCenterStressTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
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

    @Test
    fun testRiskLevelBadgeSemantics() {
        val highRisk1 = PendingProposal(
            id = "p-1",
            title = "Nginx upgrade",
            description = "desc",
            targetComponent = "home-web",
            riskLevel = "HIGH"
        )
        assertTrue(highRisk1.isHighRisk)

        val highRisk2 = PendingProposal(
            id = "p-2",
            title = "Dangerous modprobe",
            description = "desc",
            targetComponent = "kernel",
            riskLevel = "高危"
        )
        assertTrue(highRisk2.isHighRisk)

        val mediumRisk = PendingProposal(
            id = "p-3",
            title = "Index optimization",
            description = "desc",
            targetComponent = "database",
            riskLevel = "MEDIUM"
        )
        assertFalse(mediumRisk.isHighRisk)

        val lowRisk = PendingProposal(
            id = "p-4",
            title = "Cache purge",
            description = "desc",
            targetComponent = "tmp",
            riskLevel = "LOW"
        )
        assertFalse(lowRisk.isHighRisk)
    }

    @Test
    fun testConcurrentProposalApprovals() = runTest {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)

        // Approve prop-101 immediately and prop-102 scheduled at 03:00 concurrently
        var prop101Done = false
        var prop102Done = false

        viewModel.approveProposal("prop-101", scheduleAt = null) { success, _ ->
            prop101Done = success
        }

        viewModel.approveProposal("prop-102", scheduleAt = "03:00") { success, _ ->
            prop102Done = success
        }

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(prop101Done)
        assertTrue(prop102Done)

        val prop101 = viewModel.pendingProposals.value.first { it.id == "prop-101" }
        val prop102 = viewModel.pendingProposals.value.first { it.id == "prop-102" }

        assertEquals("APPROVED", prop101.status)
        assertTrue(prop101.isApproved)
        assertFalse(prop101.isScheduled)

        assertEquals("SCHEDULED", prop102.status)
        assertEquals("03:00", prop102.scheduledAt)
        assertTrue(prop102.isScheduled)
        assertFalse(prop102.isAwaitingApproval)

        // Maps should be empty after completion
        assertTrue(viewModel.approvingProposals.value.isEmpty())
    }

    @Test
    fun testEmptyOrWhitespaceChatMessageHandling() = runTest {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)
        val initialCount = viewModel.chatMessages.value.size

        // Send whitespace only
        viewModel.sendChatMessage("   ")
        viewModel.sendChatMessage("")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Empty prompts must be discarded", initialCount, viewModel.chatMessages.value.size)
        assertFalse(viewModel.isChatLoading.value)
    }

    @Test
    fun testContainerRestartStateLifecycle() = runTest {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)

        var restartFinished = false
        viewModel.restartContainer("vision") { success, _ ->
            restartFinished = success
        }

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(restartFinished)
        val vision = viewModel.containers.value.first { it.name == "vision" }
        assertEquals("running", vision.status)
        assertEquals(1, vision.restartCount)
        assertEquals("刚刚重启", vision.uptime)

        // Restarting map must be cleared
        assertFalse(viewModel.restartingContainers.value.containsKey("vision"))
    }

    @Test
    fun testToastMessageLifecycle() = runTest {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)

        viewModel.showToast("Test Notification")
        assertEquals("Test Notification", viewModel.toastMessage.value)

        viewModel.consumeToast()
        assertEquals(null, viewModel.toastMessage.value)
    }
}
