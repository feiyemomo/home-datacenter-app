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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * Comprehensive unit test suite for Ops Center (Work Order 5.3):
 * - OpsTab enumeration and mapping
 * - Data models: NasContainerInfo, WafSecuritySummary, PendingProposal, OpsChatMessage
 * - OpsViewModel lifecycle, seeded data, tab switching, and 1Panel URL resolution
 * - Container safe restart action and optimistic state updates
 * - Pending proposal approvals (Immediate execution vs. 03:00 AM scheduled execution)
 * - Ops Copilot interactive chat and domain diagnostic responses
 * - Compatibility verification with formatUptime and ServiceHealthInfo
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpsCenterStateTest {

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
    fun testOpsTabEnum() {
        assertEquals(OpsTab.SYSTEM_DASHBOARD, OpsTab.fromIndex(0))
        assertEquals(OpsTab.SECURITY_AND_LOGS, OpsTab.fromIndex(1))
        assertEquals(OpsTab.PENDING_PROPOSALS, OpsTab.fromIndex(2))
        assertEquals(OpsTab.COPILOT_CHAT, OpsTab.fromIndex(3))
        assertEquals(OpsTab.SYSTEM_DASHBOARD, OpsTab.fromIndex(999)) // fallback default

        assertEquals("系统大盘", OpsTab.SYSTEM_DASHBOARD.title)
        assertEquals("安全与日志", OpsTab.SECURITY_AND_LOGS.title)
        assertEquals("待审提案", OpsTab.PENDING_PROPOSALS.title)
        assertEquals("Copilot 对话", OpsTab.COPILOT_CHAT.title)
    }

    @Test
    fun testNasContainerInfoModel() {
        val runningContainer = NasContainerInfo(
            name = "home-api",
            image = "feiyemomo/home-api:v1.2.0",
            status = "running",
            cpuPercent = 1.5,
            memoryMb = 85,
            restartCount = 0,
            uptime = "7天"
        )
        assertTrue(runningContainer.isRunning)
        assertFalse(runningContainer.isRestarting)

        val restartingContainer = NasContainerInfo(
            name = "vision",
            image = "feiyemomo/vision:v1.1.0",
            status = "restarting"
        )
        assertFalse(restartingContainer.isRunning)
        assertTrue(restartingContainer.isRestarting)

        val stoppedContainer = NasContainerInfo(
            name = "backup-task",
            image = "alpine:latest",
            status = "exited"
        )
        assertFalse(stoppedContainer.isRunning)
        assertFalse(stoppedContainer.isRestarting)
    }

    @Test
    fun testWafSecuritySummaryModel() {
        val summary = WafSecuritySummary(
            todayBlockedCount = 142,
            totalRules = 48,
            lastBlockedIp = "194.26.29.112",
            lastBlockedTime = "10分钟前",
            s3BackupLastTime = "今日 04:00",
            s3BackupStatus = "HEALTHY",
            s3BackupSizeBytes = 107374182400L // 100 GB
        )

        assertEquals(142, summary.todayBlockedCount)
        assertEquals(48, summary.totalRules)
        assertTrue(summary.isS3Healthy)

        val unhealthySummary = summary.copy(s3BackupStatus = "FAILED")
        assertFalse(unhealthySummary.isS3Healthy)
    }

    @Test
    fun testPendingProposalModel() {
        val highRiskProp = PendingProposal(
            id = "prop-101",
            title = "【高危】Nginx 基础镜像升级",
            description = "修复 CVE-2024-24989 漏洞",
            targetComponent = "home-web",
            riskLevel = "HIGH",
            estimatedSeconds = 45,
            downtimeSeconds = 3,
            rollbackPlan = "docker tag home-web:prev home-web:latest",
            status = "AWAITING_APPROVAL"
        )

        assertTrue(highRiskProp.isAwaitingApproval)
        assertFalse(highRiskProp.isScheduled)
        assertFalse(highRiskProp.isApproved)
        assertTrue(highRiskProp.isHighRisk)

        val scheduledProp = highRiskProp.copy(status = "SCHEDULED", scheduledAt = "03:00")
        assertFalse(scheduledProp.isAwaitingApproval)
        assertTrue(scheduledProp.isScheduled)
        assertFalse(scheduledProp.isApproved)

        val approvedProp = highRiskProp.copy(status = "APPROVED")
        assertTrue(approvedProp.isApproved)
    }

    @Test
    fun testOpsChatMessageModel() {
        val userMsg = OpsChatMessage(role = "user", content = "分析过去 24 小时 SSH 攻击")
        assertTrue(userMsg.isUser)
        assertFalse(userMsg.isAssistant)
        assertEquals("分析过去 24 小时 SSH 攻击", userMsg.content)

        val assistantMsg = OpsChatMessage(role = "assistant", content = "已拦截 142 次攻击", model = "agent-deep")
        assertFalse(assistantMsg.isUser)
        assertTrue(assistantMsg.isAssistant)
        assertEquals("agent-deep", assistantMsg.model)
    }

    @Test
    fun testViewModelInitializationAndSeededData() = runTest {
        val viewModel = OpsViewModel(
            application = mockApp,
            baseUrlProvider = { "http://192.168.31.234:8088/" },
            tokenProvider = { "fake-jwt-token" },
            ioDispatcher = testDispatcher
        )

        assertEquals(OpsTab.SYSTEM_DASHBOARD, viewModel.selectedTab.value)
        assertEquals(7, viewModel.containers.value.size)
        assertTrue(viewModel.containers.value.any { it.name == "home-api" })
        assertTrue(viewModel.containers.value.any { it.name == "home-web" })
        assertTrue(viewModel.containers.value.any { it.name == "vision" })
        assertTrue(viewModel.containers.value.any { it.name == "frigate-nvr" })
        assertTrue(viewModel.containers.value.any { it.name == "mosquitto" })
        assertTrue(viewModel.containers.value.any { it.name == "1panel-waf" })
        assertTrue(viewModel.containers.value.any { it.name == "new-api" })

        assertNotNull(viewModel.wafSecuritySummary.value)
        assertEquals(142, viewModel.wafSecuritySummary.value?.todayBlockedCount)

        assertEquals(2, viewModel.pendingProposals.value.size)
        assertTrue(viewModel.pendingProposals.value.all { it.isAwaitingApproval })

        assertEquals(1, viewModel.chatMessages.value.size)
        assertTrue(viewModel.chatMessages.value.first().isAssistant)
    }

    @Test
    fun testViewModelTabSwitching() {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)

        viewModel.selectTab(OpsTab.SECURITY_AND_LOGS)
        assertEquals(OpsTab.SECURITY_AND_LOGS, viewModel.selectedTab.value)

        viewModel.selectTab(OpsTab.PENDING_PROPOSALS)
        assertEquals(OpsTab.PENDING_PROPOSALS, viewModel.selectedTab.value)

        viewModel.selectTab(OpsTab.COPILOT_CHAT)
        assertEquals(OpsTab.COPILOT_CHAT, viewModel.selectedTab.value)

        viewModel.selectTab(0)
        assertEquals(OpsTab.SYSTEM_DASHBOARD, viewModel.selectedTab.value)

        viewModel.selectTab(1)
        assertEquals(OpsTab.SECURITY_AND_LOGS, viewModel.selectedTab.value)

        viewModel.selectTab(2)
        assertEquals(OpsTab.PENDING_PROPOSALS, viewModel.selectedTab.value)

        viewModel.selectTab(3)
        assertEquals(OpsTab.COPILOT_CHAT, viewModel.selectedTab.value)
    }

    @Test
    fun testResolve1PanelUrl() {
        val viewModelLan = OpsViewModel(
            mockApp,
            baseUrlProvider = { "http://192.168.31.234:8088/" },
            ioDispatcher = testDispatcher
        )
        assertEquals("http://192.168.31.234:8088/btpanel", viewModelLan.resolve1PanelUrl())

        val viewModelRemote = OpsViewModel(
            mockApp,
            baseUrlProvider = { "https://api.feiyemomo.top/" },
            ioDispatcher = testDispatcher
        )
        assertEquals("https://api.feiyemomo.top/btpanel", viewModelRemote.resolve1PanelUrl())
    }

    @Test
    fun testContainerRestartAction() = runTest {
        val viewModel = OpsViewModel(
            application = mockApp,
            ioDispatcher = testDispatcher
        )

        var callbackCalled = false
        var callbackSuccess = false

        viewModel.restartContainer("home-api") { success, _ ->
            callbackCalled = true
            callbackSuccess = success
        }

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(callbackCalled)
        assertTrue(callbackSuccess)

        val container = viewModel.containers.value.first { it.name == "home-api" }
        assertEquals("running", container.status)
        assertEquals("刚刚重启", container.uptime)
        assertEquals(1, container.restartCount)

        assertNotNull(viewModel.toastMessage.value)
        assertTrue(viewModel.toastMessage.value!!.contains("home-api"))
        viewModel.consumeToast()
        assertNull(viewModel.toastMessage.value)
    }

    @Test
    fun testProposalApprovalImmediate() = runTest {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)

        var callbackCalled = false
        viewModel.approveProposal("prop-101", scheduleAt = null) { success, _ ->
            callbackCalled = true
        }

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(callbackCalled)
        val prop = viewModel.pendingProposals.value.first { it.id == "prop-101" }
        assertEquals("APPROVED", prop.status)
        assertTrue(prop.isApproved)
        assertFalse(prop.isAwaitingApproval)

        assertNotNull(viewModel.toastMessage.value)
        assertTrue(viewModel.toastMessage.value!!.contains("立即批准执行"))
    }

    @Test
    fun testProposalApprovalScheduledAt0300() = runTest {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)

        var callbackCalled = false
        viewModel.approveProposal("prop-102", scheduleAt = "03:00") { success, _ ->
            callbackCalled = true
        }

        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(callbackCalled)
        val prop = viewModel.pendingProposals.value.first { it.id == "prop-102" }
        assertEquals("SCHEDULED", prop.status)
        assertEquals("03:00", prop.scheduledAt)
        assertTrue(prop.isScheduled)
        assertFalse(prop.isAwaitingApproval)

        assertNotNull(viewModel.toastMessage.value)
        assertTrue(viewModel.toastMessage.value!!.contains("03:00"))
    }

    @Test
    fun testCopilotChatDiagnosticSSHAnalysis() = runTest {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)

        viewModel.sendChatMessage("分析过去 24 小时 SSH 攻击")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(3, viewModel.chatMessages.value.size) // Welcome (index 0) + User (index 1) + Assistant (index 2)
        val userMsg = viewModel.chatMessages.value[1]
        val assistantMsg = viewModel.chatMessages.value[2]

        assertEquals("user", userMsg.role)
        assertEquals("assistant", assistantMsg.role)
        assertTrue(assistantMsg.content.contains("SSH 安全威胁分析报告"))
        assertTrue(assistantMsg.content.contains("142 次"))
        assertTrue(assistantMsg.content.contains("1Panel WAF"))
        assertFalse(viewModel.isChatLoading.value)
    }

    @Test
    fun testCopilotChatDiagnosticProposals() = runTest {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)

        viewModel.sendChatMessage("当前有哪些高危待批提案？")
        testDispatcher.scheduler.advanceUntilIdle()

        val assistantMsg = viewModel.chatMessages.value.last()
        assertTrue(assistantMsg.content.contains("待审批系统提案"))
        assertTrue(assistantMsg.content.contains("prop-101") || assistantMsg.content.contains("Nginx"))
    }

    @Test
    fun testCopilotChatDiagnosticS3Backup() = runTest {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)

        viewModel.sendChatMessage("检查 S3 异地备份状态")
        testDispatcher.scheduler.advanceUntilIdle()

        val assistantMsg = viewModel.chatMessages.value.last()
        assertTrue(assistantMsg.content.contains("S3 异地增量备份"))
        assertTrue(assistantMsg.content.contains("HEALTHY") || assistantMsg.content.contains("正常"))
    }

    @Test
    fun testCopilotChatDiagnosticVisionRestart() = runTest {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)

        viewModel.sendChatMessage("为什么昨天凌晨 vision 重启了？")
        testDispatcher.scheduler.advanceUntilIdle()

        val assistantMsg = viewModel.chatMessages.value.last()
        assertTrue(assistantMsg.content.contains("Vision AI 服务运行诊断"))
        assertTrue(assistantMsg.content.contains("CPU 门控硬件保护熔断"))
    }

    @Test
    fun testCopilotChatDiagnosticContainersRanking() = runTest {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)

        viewModel.sendChatMessage("查询容器资源消耗排行")
        testDispatcher.scheduler.advanceUntilIdle()

        val assistantMsg = viewModel.chatMessages.value.last()
        assertTrue(assistantMsg.content.contains("容器资源消耗排行"))
        assertTrue(assistantMsg.content.contains("home-api"))
    }

    @Test
    fun testUptimeAndServiceHealthCompatibility() {
        assertEquals("刚刚启动", formatUptime(10))
        assertEquals("5分钟", formatUptime(300))
        assertEquals("2小时 10分钟", formatUptime(7800))
        assertEquals("3天 4小时", formatUptime(3 * 86400 + 4 * 3600))

        val service = ServiceHealthInfo(
            name = "1Panel WAF",
            port = "10000",
            description = "企业版应用防火墙",
            isOnline = true
        )
        assertTrue(service.isOnline)
        assertEquals("1Panel WAF", service.name)
    }

    @Test
    fun testOpenCopilotDiscussionPrefill() {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)

        val prompt = "关于待审提案「【高危】Nginx 基础镜像安全升级至 v1.25.4-alpine」"
        viewModel.openCopilotDiscussion(prompt)

        assertEquals(OpsTab.COPILOT_CHAT, viewModel.selectedTab.value)
        assertEquals(prompt, viewModel.chatDraft.value)

        viewModel.consumeChatDraft()
        assertNull(viewModel.chatDraft.value)
    }

    @Test
    fun testSecurityDefenseAndLogsState() {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)

        assertNotNull(viewModel.defenseOverview.value)
        assertEquals(142, viewModel.defenseOverview.value?.totalBannedCount)
        assertTrue(viewModel.defenseOverview.value?.smtpEnabled == true)
        assertEquals(2, viewModel.defenseOverview.value?.allRecipients?.size)

        assertEquals(2, viewModel.bannedIps.value.size)
        assertTrue(viewModel.bannedIps.value.any { it.ip == "194.26.29.112" })

        assertEquals(2, viewModel.whitelist.value.size)
        assertTrue(viewModel.whitelist.value.any { it.entry == "192.168.31.0/24" })

        assertEquals(4, viewModel.systemLogs.value.size)
        assertTrue(viewModel.systemLogs.value.any { it.level == "critical" })

        assertNotNull(viewModel.alertFilters.value)
        assertTrue(viewModel.alertFilters.value.criticalVuln)
        assertTrue(viewModel.alertFilters.value.autoRollback)
        assertEquals("CRITICAL", viewModel.alertFilters.value.minSeverity)
    }

    @Test
    fun testManualBanAndUnban() = runTest {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)

        viewModel.manualBanIp("1.2.3.4", 60, "测试手动阻断")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.bannedIps.value.any { it.ip == "1.2.3.4" })
        assertEquals("已成功阻断 IP: 1.2.3.4 (60 分钟)", viewModel.toastMessage.value)
        viewModel.consumeToast()

        viewModel.unbanIp("1.2.3.4")
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.bannedIps.value.any { it.ip == "1.2.3.4" })
        assertEquals("已解除 IP 阻断: 1.2.3.4", viewModel.toastMessage.value)
    }

    @Test
    fun testAddAndDeleteWhitelist() = runTest {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)

        viewModel.addWhitelist("10.0.0.1", "内网网关")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.whitelist.value.any { it.entry == "10.0.0.1" })
        assertEquals("已添加到信任白名单: 10.0.0.1", viewModel.toastMessage.value)
        viewModel.consumeToast()

        viewModel.deleteWhitelist("10.0.0.1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.whitelist.value.any { it.entry == "10.0.0.1" })
        assertEquals("已从白名单移除: 10.0.0.1", viewModel.toastMessage.value)
    }

    @Test
    fun testAlertRecipientsManagement() = runTest {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)

        viewModel.addAlertRecipient("security-chief@homedatacenter.local")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.defenseOverview.value?.recipients?.contains("security-chief@homedatacenter.local") == true)
        assertEquals("已成功添加告警接收邮箱: security-chief@homedatacenter.local", viewModel.toastMessage.value)
        viewModel.consumeToast()

        viewModel.deleteAlertRecipient("security-chief@homedatacenter.local")
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.defenseOverview.value?.recipients?.contains("security-chief@homedatacenter.local") == true)
        assertEquals("已移除告警接收邮箱: security-chief@homedatacenter.local", viewModel.toastMessage.value)
    }

    @Test
    fun testAlertFiltersStateAndToggle() = runTest {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)

        val updatedConfig = AlertFilterConfig(
            criticalVuln = true,
            autoRollback = false,
            bruteForceBan = true,
            containerCrash = false,
            minSeverity = "WARNING"
        )
        viewModel.updateAlertFilters(updatedConfig)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(updatedConfig, viewModel.alertFilters.value)
        assertFalse(viewModel.alertFilters.value.autoRollback)
        assertFalse(viewModel.alertFilters.value.containerCrash)
        assertEquals("WARNING", viewModel.alertFilters.value.minSeverity)
        assertEquals("告警推送过滤配置已保存", viewModel.toastMessage.value)
    }
}
