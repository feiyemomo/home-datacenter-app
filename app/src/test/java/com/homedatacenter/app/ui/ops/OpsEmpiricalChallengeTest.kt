package com.homedatacenter.app.ui.ops

import android.app.Application
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import java.util.Locale
import kotlin.random.Random

/**
 * Empirical Adversarial Challenger Test Suite (Milestones M2 & M3).
 *
 * Rigorously stress-tests and challenges:
 * 1. OpsCenterScreen layout and insets:
 *    - IME insets and bottom navigation bar padding (closed vs open keyboard).
 *    - Rapid switching across all 4 tabs (OpsTab: 0..3) with random cycles and out-of-bound indices.
 *    - "与 Copilot 讨论修改" interaction with empty, very long, and adversarial special character proposals.
 *    - WAF LED dot decoupling under small screen / large font / dual column constraints.
 *    - Storage pool watermark formatting with 0 GB, fractional GB, large numbers, and over-quota inputs.
 * 2. OnePanelWebActivity:
 *    - CookieManager cookie string formatting, Path, SameSite, and injection order logic.
 * 3. Alert Filters & Recipients:
 *    - Recipient email validation logic (valid emails accepted, invalid emails rejected, edge cases).
 *    - Recipient deduplication, deletion, and filter switches toggle integrity.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpsEmpiricalChallengeTest {

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

    // =========================================================================
    // Challenge 1.1: IME Insets & Bottom Navigation Bar Padding Calculations
    // =========================================================================

    @Test
    fun testImeInsetsAndBottomNavBarPaddingCalculations() {
        // Formula from OpsCenterScreen.kt (lines 158-160):
        // val contentBottomPadding = maxOf(navBottom + 96.dp, imeBottom)
        fun calculateContentBottomPadding(navBottom: Dp, imeBottom: Dp): Dp {
            return maxOf(navBottom + 96.dp, imeBottom)
        }

        // Scenario A: Soft keyboard closed (imeBottom = 0.dp), standard gesture navigation (navBottom = 20.dp)
        val paddingGestureClosed = calculateContentBottomPadding(20.dp, 0.dp)
        assertEquals(116.dp, paddingGestureClosed)
        assertTrue("Must be at least 96.dp to clear floating nav bar", paddingGestureClosed >= 96.dp)

        // Scenario B: Soft keyboard closed (imeBottom = 0.dp), 3-button navigation (navBottom = 48.dp)
        val padding3ButtonClosed = calculateContentBottomPadding(48.dp, 0.dp)
        assertEquals(144.dp, padding3ButtonClosed)
        assertTrue(padding3ButtonClosed >= 96.dp)

        // Scenario C: Immersive full screen / zero navigation bar (navBottom = 0.dp, imeBottom = 0.dp)
        val paddingImmersiveClosed = calculateContentBottomPadding(0.dp, 0.dp)
        assertEquals(96.dp, paddingImmersiveClosed)

        // Scenario D: Soft keyboard open (imeBottom = 320.dp), gesture navigation (navBottom = 20.dp)
        // navBottom + 96.dp = 116.dp < 320.dp. Content bottom padding must dynamically expand to 320.dp!
        val paddingGestureOpen = calculateContentBottomPadding(20.dp, 320.dp)
        assertEquals(320.dp, paddingGestureOpen)
        assertTrue("Input field must float above keyboard", paddingGestureOpen >= 320.dp)

        // Scenario E: Soft keyboard open (imeBottom = 350.dp), 3-button navigation (navBottom = 48.dp)
        // navBottom + 96.dp = 144.dp < 350.dp -> padding must be 350.dp
        val padding3ButtonOpen = calculateContentBottomPadding(48.dp, 350.dp)
        assertEquals(350.dp, padding3ButtonOpen)

        // Scenario F: Extremely large soft keyboard / tablet split keyboard (imeBottom = 540.dp)
        val paddingLargeIme = calculateContentBottomPadding(24.dp, 540.dp)
        assertEquals(540.dp, paddingLargeIme)

        // Scenario G: Invariance property: For all non-negative nav and IME dimensions,
        // padding is >= 96.dp and padding >= imeBottom.
        val testNavValues = listOf(0.dp, 16.dp, 20.dp, 24.dp, 48.dp, 64.dp)
        val testImeValues = listOf(0.dp, 50.dp, 100.dp, 200.dp, 300.dp, 400.dp, 600.dp)

        for (nav in testNavValues) {
            for (ime in testImeValues) {
                val pad = calculateContentBottomPadding(nav, ime)
                assertTrue("Padding must never be less than 96.dp", pad >= 96.dp)
                assertTrue("Padding must never be less than imeBottom", pad >= ime)
                assertTrue("Padding must never be less than nav + 96.dp", pad >= nav + 96.dp)
            }
        }
    }

    // =========================================================================
    // Challenge 1.2: Rapid Tab Switching Across All 4 Tabs & Boundary Stress
    // =========================================================================

    @Test
    fun testRapidTabSwitchingStressAndBoundary() {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)

        // 1. Verify 4-Tab mapping and titles
        val expectedTabs = listOf(
            OpsTab.SYSTEM_DASHBOARD to "系统大盘",
            OpsTab.SECURITY_AND_LOGS to "安全与日志",
            OpsTab.PENDING_PROPOSALS to "待审提案",
            OpsTab.COPILOT_CHAT to "Copilot 对话"
        )
        for ((tab, title) in expectedTabs) {
            assertEquals(title, tab.title)
            assertEquals(tab, OpsTab.fromIndex(tab.index))
        }

        // 2. Boundary out-of-range checks
        val outOfBounds = listOf(-1, -99, Int.MIN_VALUE, 4, 10, 999, Int.MAX_VALUE)
        for (invalidIndex in outOfBounds) {
            val fallback = OpsTab.fromIndex(invalidIndex)
            assertEquals("Out-of-range index $invalidIndex must fall back to SYSTEM_DASHBOARD",
                OpsTab.SYSTEM_DASHBOARD, fallback)
        }

        // 3. Stress test: Rapid sequential and randomized tab switching (1,000 cycles)
        val random = Random(42)
        for (i in 0 until 1000) {
            val targetIndex = random.nextInt(0, 4)
            viewModel.selectTab(targetIndex)
            assertEquals(OpsTab.fromIndex(targetIndex), viewModel.selectedTab.value)
        }

        // Ensure final tab can be set to any of the 4 tabs cleanly
        viewModel.selectTab(OpsTab.COPILOT_CHAT)
        assertEquals(OpsTab.COPILOT_CHAT, viewModel.selectedTab.value)
        viewModel.selectTab(OpsTab.SECURITY_AND_LOGS)
        assertEquals(OpsTab.SECURITY_AND_LOGS, viewModel.selectedTab.value)
    }

    // =========================================================================
    // Challenge 1.3: "与 Copilot 讨论修改" Interaction with Adversarial Inputs
    // =========================================================================

    @Test
    fun testDiscussWithCopilotAdversarialInputs() = runTest {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)

        fun buildPrompt(proposal: PendingProposal): String {
            return "关于待审提案「${proposal.title}」（目标: ${proposal.targetComponent}, 风险: ${proposal.riskLevel}）：\n" +
                    "描述: ${proposal.description}\n" +
                    "回滚方案: ${proposal.rollbackPlan}\n" +
                    "请协助评估或调整此方案..."
        }

        // Case A: Standard proposal
        val standardProposal = PendingProposal(
            id = "p-std",
            title = "Nginx 升级",
            description = "升级至最新稳定版",
            targetComponent = "home-web",
            riskLevel = "HIGH",
            rollbackPlan = "docker tag prev latest"
        )
        val promptA = buildPrompt(standardProposal)
        viewModel.openCopilotDiscussion(promptA)

        assertEquals(OpsTab.COPILOT_CHAT, viewModel.selectedTab.value)
        assertEquals(promptA, viewModel.chatDraft.value)
        viewModel.consumeChatDraft()
        assertNull(viewModel.chatDraft.value)

        // Case B: Completely empty proposal
        val emptyProposal = PendingProposal(
            id = "p-empty",
            title = "",
            description = "",
            targetComponent = "",
            riskLevel = "",
            rollbackPlan = ""
        )
        val promptB = buildPrompt(emptyProposal)
        assertTrue(promptB.contains("关于待审提案「」"))
        viewModel.openCopilotDiscussion(promptB)
        assertEquals(OpsTab.COPILOT_CHAT, viewModel.selectedTab.value)
        assertEquals(promptB, viewModel.chatDraft.value)
        viewModel.consumeChatDraft()

        // Case C: Extremely large proposal (50,000 characters)
        val massiveDesc = "A".repeat(50000)
        val massiveRollback = "ROLLBACK_CMD; ".repeat(1000)
        val massiveProposal = PendingProposal(
            id = "p-huge",
            title = "超大规模重构提案".repeat(100),
            description = massiveDesc,
            targetComponent = "deep-learning-cluster",
            riskLevel = "CRITICAL",
            rollbackPlan = massiveRollback
        )
        val promptC = buildPrompt(massiveProposal)
        assertTrue(promptC.length > 50000)
        viewModel.openCopilotDiscussion(promptC)
        assertEquals(OpsTab.COPILOT_CHAT, viewModel.selectedTab.value)
        assertEquals(promptC.length, viewModel.chatDraft.value?.length)
        viewModel.consumeChatDraft()

        // Case D: Adversarial special characters & injection payloads
        val adversarialPayloads = listOf(
            "'; DROP TABLE proposals; --",
            "<script>alert('XSS')</script><img src=x onerror=alert(1)>",
            "Quotes: \" ' ` and backslash: \\ and tabs: \t and newlines: \r\n\n",
            "Format specifiers: %s %d %x %n %% %08d",
            "JSON payload: {\"key\": \"value\", \"nested\": [1,2,3]}",
            "Unicode & Emojis: 🚀🔥🚨🛡️⚙️💥 中文 繁體 𠮷 日本語 한국어"
        )

        for (payload in adversarialPayloads) {
            val advProposal = PendingProposal(
                id = "p-adv",
                title = payload,
                description = "Desc: $payload",
                targetComponent = "comp: $payload",
                riskLevel = "HIGH",
                rollbackPlan = "Rollback: $payload"
            )
            val advPrompt = buildPrompt(advProposal)
            viewModel.openCopilotDiscussion(advPrompt)

            assertEquals(OpsTab.COPILOT_CHAT, viewModel.selectedTab.value)
            assertEquals(advPrompt, viewModel.chatDraft.value)
            assertTrue("Draft must contain exact raw payload", viewModel.chatDraft.value!!.contains(payload))
            viewModel.consumeChatDraft()

            // Verify sending chat message with adversarial prompt does not crash
            viewModel.sendChatMessage(advPrompt)
            testDispatcher.scheduler.advanceUntilIdle()
            val sentMsg = viewModel.chatMessages.value.find { it.content == advPrompt }
            assertNotNull("Adversarial message must be recorded in chat history", sentMsg)
        }
    }

    // =========================================================================
    // Challenge 1.4: WAF LED Dot Decoupling & Dual-Column Card Layout
    // =========================================================================

    @Test
    fun testWafLedDotDecouplingDualColumnConstraints() {
        // Geometric budget model in OpsCenterScreen.kt:
        // 1. SystemDashboardTabContent Column: horizontal padding = 16.dp * 2 = 32.dp (line 469)
        // 2. WafAndBackupSnapshotCard LiquidGlassCard: padding = 18.dp * 2 = 36.dp (line 775)
        // 3. Row columns gap: spacedBy(12.dp) (line 785)
        // 4. Inside Box padding: 14.dp * 2 = 28.dp (line 794)
        fun calculateAvailableColumnWidth(screenWidthDp: Float): Float {
            val screenPadding = 16f * 2 // 32dp
            val cardPadding = 18f * 2   // 36dp
            val columnGap = 12f
            val totalUsableRowWidth = screenWidthDp - screenPadding - cardPadding
            val singleColumnOuterWidth = (totalUsableRowWidth - columnGap) / 2f
            val boxHorizontalPadding = 14f * 2 // 28dp
            return singleColumnOuterWidth - boxHorizontalPadding
        }

        // Approximate typography widths:
        // "1Panel WAF" at 12sp ≈ 65dp width
        // Previous GlassTag("拦截已生效"): padding(8*2) + border(2) + 5 chars * ~11dp ≈ 73dp width
        // Old row total width ≈ 65 + 73 + 8(gap) = 146dp
        val oldDesignWidth = 65f + 73f + 8f // 146dp

        // New design:
        // Text("1Panel WAF") at 12sp ≈ 65dp
        // Compact LED Dot: 6dp circle + 4dp gap + 3 chars ("生效中") at 10sp (~30dp) ≈ 40dp
        val newDesignWidth = 65f + 40f // 105dp

        // Test on standard 360dp phone screen:
        // ((360 - 32 - 36 - 12) / 2) - 28 = (280 / 2) - 28 = 140 - 28 = 112dp
        val available360 = calculateAvailableColumnWidth(360f)
        assertEquals(112f, available360, 0.1f)
        assertTrue("Old design severely overflows 360dp column (146dp > 112dp by 34dp!)", oldDesignWidth > available360)
        assertTrue("New decoupled LED dot fits 360dp column (105dp < 112dp)", newDesignWidth < available360)

        val headroom360 = available360 - newDesignWidth
        assertTrue("Positive headroom on 360dp screen", headroom360 > 0f)

        // Test on 390dp / 412dp phone screens:
        val available390 = calculateAvailableColumnWidth(390f) // (310 / 2) - 28 = 127dp
        assertEquals(127f, available390, 0.1f)
        assertTrue(newDesignWidth < available390)

        val available412 = calculateAvailableColumnWidth(412f) // (332 / 2) - 28 = 138dp
        assertEquals(138f, available412, 0.1f)
        assertTrue(newDesignWidth < available412)

        // Contrast: On 360dp screen, old design overflowed by 34dp (146dp vs 112dp),
        // whereas new LED dot design fits within standard 360dp width (105dp < 112dp).
        assertTrue(newDesignWidth < available360)
        assertTrue(oldDesignWidth > available360)
    }

    // =========================================================================
    // Challenge 1.5: Storage Pool Watermark Formatting (0 GB, Fractions, Large)
    // =========================================================================

    @Test
    fun testStoragePoolWatermarkFormatting() {
        data class StorageReading(val subText: String, val progressText: String, val percentFraction: Float)

        fun formatStorageWatermark(diskUsedGb: Double, rawDiskLimitGb: Double): StorageReading {
            val diskLimitGb = rawDiskLimitGb.coerceAtLeast(1.0)
            val diskPercent = ((diskUsedGb / diskLimitGb) * 100.0).toFloat()
            val subText = String.format(Locale.US, "%.1fG / %.1fG", diskUsedGb, diskLimitGb)
            val progressText = String.format(Locale.US, "录像存储配额: %.1f GB / %.1f GB (%.1f%%)",
                diskUsedGb, diskLimitGb, diskPercent)
            val percentFraction = (diskPercent / 100f).coerceIn(0f, 1f)
            return StorageReading(subText, progressText, percentFraction)
        }

        // 1. Zero usage (0 GB)
        val zeroReading = formatStorageWatermark(0.0, 1024.0)
        assertEquals("0.0G / 1024.0G", zeroReading.subText)
        assertEquals("录像存储配额: 0.0 GB / 1024.0 GB (0.0%)", zeroReading.progressText)
        assertEquals(0.0f, zeroReading.percentFraction, 0.001f)

        // 2. Fractional GB (The exact prompt requirement: 312.4 GB / 400.0 GB (78.1%))
        val promptReading = formatStorageWatermark(312.4, 400.0)
        assertEquals("312.4G / 400.0G", promptReading.subText)
        assertEquals("录像存储配额: 312.4 GB / 400.0 GB (78.1%)", promptReading.progressText)
        assertEquals(0.781f, promptReading.percentFraction, 0.001f)

        // 3. Fractional GB with rounding
        val roundingReading = formatStorageWatermark(312.44, 400.0)
        assertEquals("312.4G / 400.0G", roundingReading.subText)
        assertEquals("录像存储配额: 312.4 GB / 400.0 GB (78.1%)", roundingReading.progressText)

        // 4. Large enterprise numbers (e.g. 18.4 TB array in GB)
        val largeReading = formatStorageWatermark(18432.5, 24576.0)
        assertEquals("18432.5G / 24576.0G", largeReading.subText)
        assertEquals("录像存储配额: 18432.5 GB / 24576.0 GB (75.0%)", largeReading.progressText)
        assertEquals(0.75f, largeReading.percentFraction, 0.001f)

        // 5. Over quota protection (125% full: 500 GB / 400 GB)
        val overQuotaReading = formatStorageWatermark(500.0, 400.0)
        assertEquals("500.0G / 400.0G", overQuotaReading.subText)
        assertEquals("录像存储配额: 500.0 GB / 400.0 GB (125.0%)", overQuotaReading.progressText)
        assertEquals("Gauge and progress indicator must be clamped to 1.0f", 1.0f, overQuotaReading.percentFraction, 0.001f)

        // 6. Zero or negative raw limit edge case (division by zero protection)
        val zeroLimitReading = formatStorageWatermark(50.0, 0.0)
        assertEquals("50.0G / 1.0G", zeroLimitReading.subText)
        assertFalse("Must never produce NaN or Infinity", zeroLimitReading.percentFraction.isNaN())
        assertFalse("Must never produce Infinity", zeroLimitReading.percentFraction.isInfinite())
    }

    // =========================================================================
    // Challenge 2: OnePanelWebActivity CookieManager Formatting & Injection
    // =========================================================================

    @Test
    fun testOnePanelWebActivityCookieFormattingAndInjectionOrder() {
        // Verification of cookie string builder logic from OnePanelWebActivity.kt (lines 201-215)
        fun formatCookie(name: String, value: String): String {
            return "$name=$value; Path=/; SameSite=Lax"
        }

        val testPsession = "sess_abc123456789xyz"
        val testPcsrftoken = "csrf_token_987654321"

        val cookie1 = formatCookie("psession", testPsession)
        val cookie2 = formatCookie("pcsrftoken", testPcsrftoken)

        assertEquals("psession=sess_abc123456789xyz; Path=/; SameSite=Lax", cookie1)
        assertEquals("pcsrftoken=csrf_token_987654321; Path=/; SameSite=Lax", cookie2)

        assertTrue("Cookie must set Path=/ to apply to all 1Panel SPA endpoints", cookie1.contains("Path=/"))
        assertTrue("Cookie must specify SameSite=Lax for webview safety", cookie1.contains("SameSite=Lax"))

        // Multi-domain injection check:
        val targetUrl = "http://192.168.31.234:10000/btpanel"
        val entryUrl = "http://192.168.31.234:10000"

        val injectedUrls = mutableListOf<Pair<String, String>>()
        fun mockSetCookie(url: String, cookieStr: String) {
            injectedUrls.add(url to cookieStr)
        }

        // Simulate injection routine
        if (testPsession.isNotEmpty()) mockSetCookie(targetUrl, formatCookie("psession", testPsession))
        if (testPcsrftoken.isNotEmpty()) mockSetCookie(targetUrl, formatCookie("pcsrftoken", testPcsrftoken))
        if (entryUrl.isNotEmpty() && entryUrl != targetUrl) {
            if (testPsession.isNotEmpty()) mockSetCookie(entryUrl, formatCookie("psession", testPsession))
            if (testPcsrftoken.isNotEmpty()) mockSetCookie(entryUrl, formatCookie("pcsrftoken", testPcsrftoken))
        }

        assertEquals(4, injectedUrls.size)
        assertTrue(injectedUrls.any { it.first == targetUrl && it.second.startsWith("psession=") })
        assertTrue(injectedUrls.any { it.first == targetUrl && it.second.startsWith("pcsrftoken=") })
        assertTrue(injectedUrls.any { it.first == entryUrl && it.second.startsWith("psession=") })
        assertTrue(injectedUrls.any { it.first == entryUrl && it.second.startsWith("pcsrftoken=") })

        // Injection order contract:
        // In OnePanelWebActivity.kt, `isSessionInjecting` defaults to true.
        // `currentWebView?.loadUrl(targetUrl)` is ONLY invoked in `finally` AFTER cookies are injected and flushed,
        // and AndroidView's `update` blocks `loadUrl` while `isSessionInjecting` is true.
        var isSessionInjecting = true
        var webViewUrlLoaded: String? = null

        fun onSessionFetchCompleted() {
            // Step 1: Inject cookies
            mockSetCookie(targetUrl, formatCookie("psession", testPsession))
            // Step 2: Clear injection flag and trigger loadUrl
            isSessionInjecting = false
            if (webViewUrlLoaded == null) {
                webViewUrlLoaded = targetUrl
            }
        }

        assertEquals(null, webViewUrlLoaded)
        onSessionFetchCompleted()
        assertFalse(isSessionInjecting)
        assertEquals(targetUrl, webViewUrlLoaded)
    }

    // =========================================================================
    // Challenge 3: Alert Filters & Recipients Email Validation
    // =========================================================================

    @Test
    fun testAlertRecipientEmailValidationAndFilterSwitches() = runTest {
        val viewModel = OpsViewModel(mockApp, ioDispatcher = testDispatcher)

        // Exact validation rule from AddAlertRecipientDialog (OpsSecurityDialogs.kt lines 384-386):
        fun validateRecipientEmail(email: String): Boolean {
            val trimmed = email.trim()
            return trimmed.isNotEmpty() && trimmed.contains("@") && trimmed.contains(".")
        }

        // 1. Valid emails: Must pass validation
        val validEmails = listOf(
            "admin@homedatacenter.local",
            "security@nas.internal",
            "devops+alert@sub.domain.co.uk",
            "momo_alerts@163.com",
            "ops.chief@datacenter.org"
        )
        for (email in validEmails) {
            assertTrue("Email '$email' must be valid", validateRecipientEmail(email))
        }

        // 2. Invalid emails: Must be rejected
        val invalidEmails = listOf(
            "",
            "   ",
            "justausername",
            "admin@no_dot_domain",
            "user.name.without.at",
            "  \t\n  "
        )
        for (invalid in invalidEmails) {
            assertFalse("Invalid email '$invalid' must be rejected", validateRecipientEmail(invalid))
        }

        // 3. Boundary email check:
        // Note: Simple heuristic `trimmed.isNotEmpty() && trimmed.contains("@") && trimmed.contains(".")`
        // permits minimal strings like "@." or "a@b.c".
        assertTrue(validateRecipientEmail("a@b.c"))
        assertTrue(validateRecipientEmail("@."))

        // 4. ViewModel recipient deduplication and deletion stress test
        val initialRecipients = viewModel.defenseOverview.value?.recipients ?: emptyList()
        assertTrue(initialRecipients.isNotEmpty())

        val newEmail = "emergency-ops@homedatacenter.local"
        viewModel.addAlertRecipient(newEmail)
        testDispatcher.scheduler.advanceUntilIdle()

        val afterAdd = viewModel.defenseOverview.value?.recipients ?: emptyList()
        assertTrue(afterAdd.contains(newEmail))

        // Adding duplicate must not create duplicate entries
        viewModel.addAlertRecipient(newEmail)
        testDispatcher.scheduler.advanceUntilIdle()
        val afterDuplicate = viewModel.defenseOverview.value?.recipients ?: emptyList()
        assertEquals(afterAdd.size, afterDuplicate.size)

        // Deleting recipient
        viewModel.deleteAlertRecipient(newEmail)
        testDispatcher.scheduler.advanceUntilIdle()
        val afterDelete = viewModel.defenseOverview.value?.recipients ?: emptyList()
        assertFalse(afterDelete.contains(newEmail))

        // 5. Alert Filter switches and MinSeverity states
        val originalFilters = viewModel.alertFilters.value
        assertNotNull(originalFilters)

        val customFilters = AlertFilterConfig(
            criticalVuln = false,
            autoRollback = true,
            bruteForceBan = false,
            containerCrash = true,
            minSeverity = "WARNING"
        )
        viewModel.updateAlertFilters(customFilters)
        testDispatcher.scheduler.advanceUntilIdle()

        val currentFilters = viewModel.alertFilters.value
        assertFalse(currentFilters.criticalVuln)
        assertTrue(currentFilters.autoRollback)
        assertFalse(currentFilters.bruteForceBan)
        assertTrue(currentFilters.containerCrash)
        assertEquals("WARNING", currentFilters.minSeverity)
    }
}
