package com.homedatacenter.app.ui.ops

import android.view.ViewTreeObserver
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.BanEntry
import com.homedatacenter.app.data.model.DefenseOverview
import com.homedatacenter.app.data.model.SecurityEventItem
import com.homedatacenter.app.data.model.SecurityWhitelistEntry
import com.homedatacenter.app.data.model.SystemLog
import com.homedatacenter.app.data.model.SystemStatus
import com.homedatacenter.app.ui.components.GlassActionButton
import com.homedatacenter.app.ui.components.GlassProgressIndicator
import com.homedatacenter.app.ui.components.GlassTag
import com.homedatacenter.app.ui.components.GlassTagType
import com.homedatacenter.app.ui.glass.AmbientFluidBackground
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.GlassTokens
import com.homedatacenter.app.ui.glass.LiquidButton
import com.homedatacenter.app.ui.glass.LiquidGlassCard
import com.homedatacenter.app.ui.glass.ProvideGlassTheme
import com.homedatacenter.app.util.ThemeManager
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OpsCenterScreen: 100% Jetpack Compose Smart Operations & Copilot Center.
 *
 * Implements Work Order 5.3:
 * - Liquid Glass Design Language (GlassTheme.tokens)
 * - Tab 1: 【系统大盘】(System Dashboard)
 *   - Circular gauges for CPU, RAM, Disk pool usage
 *   - Core container cards with live status LEDs (green/running, red/stopped) & restart action
 *   - 1Panel Enterprise WAF blocked count card and S3 offsite backup status
 *   - Prominent top action button: "进入 1Panel 完整控制台"
 * - Tab 2: 【待批提案】(Pending Proposals)
 *   - Agent-generated pending upgrade/repair proposals
 *   - Risk badges (LOW, MEDIUM, HIGH / 高危)
 *   - Estimated time & rollback command summary
 *   - Action buttons: 【立即执行】 and 【设定凌晨 03:00 自动执行】
 * - Tab 3: 【Copilot 对话】(Copilot Chat)
 *   - Native chat bubble UI and frosted input bar
 *   - Preset query chips for instant diagnostics
 *   - Backend Ops Chat API integration with structured responses
 */
@Composable
fun OpsCenterScreen(
    viewModel: OpsViewModel,
    modifier: Modifier = Modifier,
    onOpen1Panel: (() -> Unit)? = null
) {
    val preset by ThemeManager.themePresetFlow.collectAsState()

    ProvideGlassTheme(preset = preset) {
        val hazeState = rememberHazeState()
        val tokens = GlassTheme.tokens
        val context = LocalContext.current

        val selectedTab by viewModel.selectedTab.collectAsState()
        val systemStatus by viewModel.systemStatus.collectAsState()
        val containers by viewModel.containers.collectAsState()
        val wafSummary by viewModel.wafSecuritySummary.collectAsState()
        val proposals by viewModel.pendingProposals.collectAsState()
        val chatMessages by viewModel.chatMessages.collectAsState()
        val defenseOverview by viewModel.defenseOverview.collectAsState()
        val bannedIps by viewModel.bannedIps.collectAsState()
        val whitelist by viewModel.whitelist.collectAsState()
        val securityEvents by viewModel.securityEvents.collectAsState()
        val systemLogs by viewModel.systemLogs.collectAsState()
        val alertFilters by viewModel.alertFilters.collectAsState()
        val chatDraft by viewModel.chatDraft.collectAsState()
        val isRefreshing by viewModel.isRefreshing.collectAsState()
        val isChatLoading by viewModel.isChatLoading.collectAsState()
        val restartingMap by viewModel.restartingContainers.collectAsState()
        val approvingMap by viewModel.approvingProposals.collectAsState()

        val view = LocalView.current
        var isKeyboardOpen by remember { mutableStateOf(false) }
        DisposableEffect(view) {
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                val insets = ViewCompat.getRootWindowInsets(view)
                val imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
                val imeHeight = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                val rect = android.graphics.Rect()
                view.getWindowVisibleDisplayFrame(rect)
                val screenHeight = view.rootView.height
                val keypadHeight = screenHeight - rect.bottom
                isKeyboardOpen = imeVisible || imeHeight > 100 || keypadHeight > screenHeight * 0.15
            }
            view.viewTreeObserver.addOnGlobalLayoutListener(listener)
            onDispose {
                if (view.viewTreeObserver.isAlive) {
                    view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                }
            }
        }

        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // When keyboard is open, stick closely above keyboard top (6.dp); when closed, avoid bottom nav bar
        val chatBottomPadding = if (isKeyboardOpen) 6.dp else (navBottom + 84.dp)
        val tabContentBottomPadding = navBottom + 96.dp

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(tokens.bgBase)
        ) {
            // Ambient Fluid Background with specular mesh blur
            AmbientFluidBackground(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState),
                preset = preset
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 4.dp)
            ) {
                // Top Action Header (compact in Copilot Chat mode to eliminate wasted top space)
                OpsHeader(
                    isRefreshing = isRefreshing,
                    isCompact = selectedTab == OpsTab.COPILOT_CHAT,
                    onRefresh = { viewModel.refreshAll(silent = false) }
                )

                // 4-Tab Segmented Selector
                OpsTabSelector(
                    selectedTab = selectedTab,
                    pendingProposalsCount = proposals.count { it.isAwaitingApproval },
                    onTabSelected = { viewModel.selectTab(it) }
                )

                // Tab Content Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when (selectedTab) {
                        OpsTab.SYSTEM_DASHBOARD -> {
                            SystemDashboardTabContent(
                                systemStatus = systemStatus,
                                containers = containers,
                                wafSummary = wafSummary,
                                restartingMap = restartingMap,
                                hazeState = hazeState,
                                bottomPadding = tabContentBottomPadding,
                                onOpen1Panel = {
                                    if (onOpen1Panel != null) {
                                        onOpen1Panel()
                                    } else {
                                        viewModel.launch1PanelConsole(context)
                                    }
                                },
                                onRestartContainer = { name ->
                                    viewModel.restartContainer(name)
                                }
                            )
                        }
                        OpsTab.SECURITY_AND_LOGS -> {
                            SecurityAndLogsTabContent(
                                defenseOverview = defenseOverview,
                                bannedIps = bannedIps,
                                whitelist = whitelist,
                                securityEvents = securityEvents,
                                systemLogs = systemLogs,
                                alertFilters = alertFilters,
                                hazeState = hazeState,
                                bottomPadding = tabContentBottomPadding,
                                onManualBan = { ip, duration, reason ->
                                    viewModel.manualBanIp(ip, duration, reason)
                                },
                                onUnbanIp = { ip ->
                                    viewModel.unbanIp(ip)
                                },
                                onAddWhitelist = { entry, note ->
                                    viewModel.addWhitelist(entry, note)
                                },
                                onRemoveWhitelist = { entry ->
                                    viewModel.deleteWhitelist(entry)
                                },
                                onAddAlertRecipient = { email ->
                                    viewModel.addAlertRecipient(email)
                                },
                                onDeleteAlertRecipient = { email ->
                                    viewModel.deleteAlertRecipient(email)
                                },
                                onUpdateAlertFilters = { config ->
                                    viewModel.updateAlertFilters(config)
                                }
                            )
                        }
                        OpsTab.PENDING_PROPOSALS -> {
                            PendingProposalsTabContent(
                                proposals = proposals,
                                approvingMap = approvingMap,
                                hazeState = hazeState,
                                bottomPadding = tabContentBottomPadding,
                                onApproveNow = { id ->
                                    viewModel.approveProposal(id, scheduleAt = null)
                                },
                                onScheduleNight = { id ->
                                    viewModel.approveProposal(id, scheduleAt = "03:00")
                                },
                                onDiscussWithCopilot = { proposal ->
                                    val prompt = "关于待审提案「${proposal.title}」（目标: ${proposal.targetComponent}, 风险: ${proposal.riskLevel}）：\n描述: ${proposal.description}\n回滚方案: ${proposal.rollbackPlan}\n请协助评估或调整此方案..."
                                    viewModel.openCopilotDiscussion(prompt)
                                }
                            )
                        }
                        OpsTab.COPILOT_CHAT -> {
                            CopilotChatTabContent(
                                messages = chatMessages,
                                isLoading = isChatLoading,
                                hazeState = hazeState,
                                bottomPadding = chatBottomPadding,
                                isKeyboardOpen = isKeyboardOpen,
                                chatDraft = chatDraft,
                                onConsumeDraft = { viewModel.consumeChatDraft() },
                                onSendMessage = { text ->
                                    viewModel.sendChatMessage(text)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Top App Bar with Title, Subtitle, and Refresh button.
 */
@Composable
private fun OpsHeader(
    isRefreshing: Boolean,
    isCompact: Boolean = false,
    onRefresh: () -> Unit
) {
    val tokens = GlassTheme.tokens
    val infiniteTransition = rememberInfiniteTransition(label = "refresh_spin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin_angle"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (isCompact) 2.dp else 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "运维管理中心",
                style = TextStyle(
                    fontSize = if (isCompact) 16.sp else 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.textColor,
                    letterSpacing = 0.3.sp
                )
            )
            if (!isCompact) {
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = "主动协作 Agent & 1Panel 智能监控控制台",
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = tokens.textSecondaryColor
                    )
                )
            }
        }

        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(tokens.controlBg)
                .border(1.dp, tokens.cardBorder, CircleShape)
                .clickable { if (!isRefreshing) onRefresh() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_refresh),
                contentDescription = "刷新",
                tint = tokens.textColor,
                modifier = Modifier
                    .size(16.dp)
                    .then(if (isRefreshing) Modifier.rotate(spinAngle) else Modifier)
            )
        }
    }
}

/**
 * Liquid Glass Segmented Tab Selector.
 */
@Composable
private fun OpsTabSelector(
    selectedTab: OpsTab,
    pendingProposalsCount: Int,
    onTabSelected: (OpsTab) -> Unit
) {
    val tokens = GlassTheme.tokens

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(tokens.controlBg)
            .border(1.dp, tokens.cardBorder, RoundedCornerShape(14.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        OpsTab.entries.forEach { tab ->
            val isSelected = tab == selectedTab
            val animBgColor by animateColorAsState(
                targetValue = if (isSelected) tokens.accentBg else Color.Transparent,
                animationSpec = tween(250),
                label = "tab_bg"
            )
            val animBorderColor by animateColorAsState(
                targetValue = if (isSelected) tokens.accentColor.copy(alpha = 0.40f) else Color.Transparent,
                animationSpec = tween(250),
                label = "tab_border"
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) tokens.textColor else tokens.textSecondaryColor,
                animationSpec = tween(250),
                label = "tab_text"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(animBgColor)
                    .border(1.dp, animBorderColor, RoundedCornerShape(10.dp))
                    .clickable { onTabSelected(tab) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = tab.title,
                        style = TextStyle(
                            fontSize = 12.5.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = textColor
                        )
                    )

                    // Pending count indicator badge
                    if (tab == OpsTab.PENDING_PROPOSALS && pendingProposalsCount > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(tokens.statusDanger),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = pendingProposalsCount.toString(),
                                style = TextStyle(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================================================================
// TAB 1: 【系统大盘】(System Dashboard)
// ==========================================================================================

@Composable
private fun SystemDashboardTabContent(
    systemStatus: SystemStatus?,
    containers: List<NasContainerInfo>,
    wafSummary: WafSecuritySummary?,
    restartingMap: Map<String, Boolean>,
    hazeState: HazeState,
    bottomPadding: Dp = 96.dp,
    onOpen1Panel: () -> Unit,
    onRestartContainer: (String) -> Unit
) {
    val tokens = GlassTheme.tokens
    var pendingRestartTarget by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Top Action Button: "进入 1Panel 完整控制台"
        LiquidButton(
            onClick = onOpen1Panel,
            modifier = Modifier.fillMaxWidth(),
            hazeState = hazeState,
            cornerRadius = 16.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp, horizontal = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(tokens.accentBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_dashboard),
                            contentDescription = null,
                            tint = tokens.accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "进入 1Panel 完整控制台",
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.textColor
                            )
                        )
                        Text(
                            text = "管理 Docker 编排、WAF 企业防护与持久化存储",
                            style = TextStyle(
                                fontSize = 11.5.sp,
                                color = tokens.textSecondaryColor
                            )
                        )
                    }
                }

                GlassTag(text = "打开", type = GlassTagType.Info)
            }
        }

        // 2. System Resource Usage: Circular & Linear indicators
        SystemResourceMetricsCard(systemStatus = systemStatus, hazeState = hazeState)

        // 3. 1Panel Enterprise WAF & S3 Backup Snapshot
        WafAndBackupSnapshotCard(wafSummary = wafSummary, hazeState = hazeState)

        // 4. Core Docker Containers with live status LED & restart action
        CoreContainersSection(
            containers = containers,
            restartingMap = restartingMap,
            hazeState = hazeState,
            onRequestRestart = { name -> pendingRestartTarget = name }
        )

        Spacer(modifier = Modifier.height(bottomPadding))
    }

    // Container Restart Confirmation Dialog
    pendingRestartTarget?.let { containerName ->
        AlertDialog(
            onDismissRequest = { pendingRestartTarget = null },
            title = {
                Text(
                    text = "确认重启容器 $containerName？",
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = tokens.textColor)
                )
            },
            text = {
                Text(
                    text = "主动协作 Agent 将通过 1Panel OpenAPI 接口发出安全重启指令。重启期间相关业务可能有数秒瞬断。",
                    style = TextStyle(fontSize = 14.sp, color = tokens.textSecondaryColor)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = containerName
                        pendingRestartTarget = null
                        onRestartContainer(name)
                    }
                ) {
                    Text(text = "立即重启", color = tokens.statusDanger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestartTarget = null }) {
                    Text(text = "取消", color = tokens.textColor)
                }
            },
            containerColor = tokens.dialogBg
        )
    }
}

/**
 * System Resource Metrics Card with 3 circular gauges for CPU, RAM, and Disk Pool.
 */
@Composable
private fun SystemResourceMetricsCard(
    systemStatus: SystemStatus?,
    hazeState: HazeState
) {
    val tokens = GlassTheme.tokens
    val metrics = systemStatus?.metrics

    val cpuPercent = (metrics?.effectiveCpuPercent ?: 24.5).toFloat()
    val cores = metrics?.cpu?.cores ?: 8

    val memoryUsedMb = metrics?.effectiveMemoryUsedMb ?: 3840L
    val memoryTotalMb = (metrics?.effectiveMemoryTotalMb ?: 8192L).coerceAtLeast(1L)
    val memoryPercent = ((memoryUsedMb.toDouble() / memoryTotalMb) * 100).toFloat()

    val diskUsedGb = metrics?.effectiveRecordingsUsedGb ?: 1200.0
    val diskLimitGb = (metrics?.effectiveRecordingsLimitGb ?: 2048.0).coerceAtLeast(1.0)
    val diskPercent = ((diskUsedGb / diskLimitGb) * 100).toFloat()

    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        hazeState = hazeState
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "系统资源水位",
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = tokens.textColor)
                )
                GlassTag(
                    text = if (systemStatus != null) "服务运行中" else "监控连通中",
                    type = GlassTagType.Success
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Triple Circular Gauges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassCircularGauge(
                    percent = (cpuPercent / 100f).coerceIn(0f, 1f),
                    label = "CPU 负载",
                    valueText = String.format(Locale.US, "%.1f%%", cpuPercent),
                    subText = "${cores}核",
                    color = getStatusColor(cpuPercent, tokens)
                )

                GlassCircularGauge(
                    percent = (memoryPercent / 100f).coerceIn(0f, 1f),
                    label = "内存占用",
                    valueText = String.format(Locale.US, "%.0f%%", memoryPercent),
                    subText = "${memoryUsedMb / 1024}G / ${memoryTotalMb / 1024}G",
                    color = getStatusColor(memoryPercent, tokens)
                )

                GlassCircularGauge(
                    percent = (diskPercent / 100f).coerceIn(0f, 1f),
                    label = "存储池水位",
                    valueText = String.format(Locale.US, "%.0f%%", diskPercent),
                    subText = String.format(Locale.US, "%.1fG / %.1fG", diskUsedGb, diskLimitGb),
                    color = getStatusColor(diskPercent, tokens)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Linear breakdown for recordings quota & cache
            Text(
                text = String.format(Locale.US, "录像存储配额: %.1f GB / %.1f GB (%.1f%%)", diskUsedGb, diskLimitGb, diskPercent),
                style = TextStyle(fontSize = 12.sp, color = tokens.textSecondaryColor)
            )
            Spacer(modifier = Modifier.height(6.dp))
            GlassProgressIndicator(
                progress = (diskPercent / 100f).coerceIn(0f, 1f),
                color = getStatusColor(diskPercent, tokens),
                height = 7.dp
            )
        }
    }
}

/**
 * Reusable Circular Arc Gauge Indicator.
 */
@Composable
private fun GlassCircularGauge(
    percent: Float,
    label: String,
    valueText: String,
    subText: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 92.dp,
    strokeWidth: Dp = 7.dp
) {
    val tokens = GlassTheme.tokens
    val animPercent by animateFloatAsState(
        targetValue = percent,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "gauge_arc"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val pad = strokeWidth.toPx() / 2f
                val arcSize = Size(this.size.width - pad * 2, this.size.height - pad * 2)

                // Background track arc (270 degrees from 135 to 405)
                drawArc(
                    color = tokens.controlBg,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(pad, pad),
                    size = arcSize,
                    style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
                )

                // Foreground active progress arc
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(color.copy(alpha = 0.85f), color)
                    ),
                    startAngle = 135f,
                    sweepAngle = 270f * animPercent,
                    useCenter = false,
                    topLeft = Offset(pad, pad),
                    size = arcSize,
                    style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = valueText,
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.textColor
                    )
                )
                Text(
                    text = subText,
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = tokens.textSecondaryColor
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = TextStyle(
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = tokens.textColor
            )
        )
    }
}

/**
 * 1Panel Enterprise WAF and S3 Offsite Backup Status Card.
 */
@Composable
private fun WafAndBackupSnapshotCard(
    wafSummary: WafSecuritySummary?,
    hazeState: HazeState
) {
    val tokens = GlassTheme.tokens
    val summary = wafSummary ?: WafSecuritySummary()

    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        hazeState = hazeState
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "企业安全哨兵与异地容灾",
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = tokens.textColor)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // WAF Card
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(tokens.controlBg)
                        .border(1.dp, tokens.cardBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "1Panel WAF",
                                style = TextStyle(fontSize = 12.sp, color = tokens.textSecondaryColor)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(tokens.statusWarning)
                                )
                                Text(
                                    text = "生效中",
                                    style = TextStyle(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = tokens.statusWarning
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${summary.todayBlockedCount}",
                            style = TextStyle(
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.statusDanger
                            )
                        )
                        Text(
                            text = "今日恶意攻击拦截",
                            style = TextStyle(fontSize = 11.sp, color = tokens.textColor)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "最近IP: ${summary.lastBlockedIp.ifEmpty { "194.26.29.112" }}",
                            style = TextStyle(fontSize = 10.sp, color = tokens.textSecondaryColor)
                        )
                    }
                }

                // S3 Backup Snapshot Card
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(tokens.controlBg)
                        .border(1.dp, tokens.cardBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "S3 增量异地冷备",
                                style = TextStyle(fontSize = 12.sp, color = tokens.textSecondaryColor)
                            )
                            GlassTag(
                                text = if (summary.isS3Healthy) "健康" else "检查中",
                                type = GlassTagType.Success
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        val sizeGb = String.format(Locale.US, "%.1f GB", summary.s3BackupSizeBytes / (1024.0 * 1024.0 * 1024.0))
                        Text(
                            text = sizeGb,
                            style = TextStyle(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.accentColor
                            )
                        )
                        Text(
                            text = "快照同步时间",
                            style = TextStyle(fontSize = 11.sp, color = tokens.textColor)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = summary.s3BackupLastTime.ifEmpty { "今日 04:00" },
                            style = TextStyle(fontSize = 10.sp, color = tokens.textSecondaryColor)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Core Docker Containers Section with Live Status LED and Safe Restart Button.
 */
@Composable
private fun CoreContainersSection(
    containers: List<NasContainerInfo>,
    restartingMap: Map<String, Boolean>,
    hazeState: HazeState,
    onRequestRestart: (String) -> Unit
) {
    val tokens = GlassTheme.tokens

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "核心容器运行状态",
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = tokens.textColor)
            )
            val runningCount = containers.count { it.isRunning }
            GlassTag(text = "$runningCount 运行中 / ${containers.size} 总计", type = GlassTagType.Success)
        }

        containers.forEach { container ->
            val isRestarting = restartingMap[container.name] == true || container.isRestarting

            LiquidGlassCard(
                modifier = Modifier.fillMaxWidth(),
                hazeState = hazeState
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Pulsing Live Status LED
                        LiveStatusLed(
                            isRunning = container.isRunning && !isRestarting,
                            isRestarting = isRestarting
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = container.name,
                                    style = TextStyle(
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = tokens.textColor
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                GlassTag(
                                    text = if (isRestarting) "重启中" else if (container.isRunning) "运行中" else "已停止",
                                    type = if (isRestarting) GlassTagType.Warning else if (container.isRunning) GlassTagType.Success else GlassTagType.Error
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${container.image} • ${container.uptime}",
                                style = TextStyle(fontSize = 11.sp, color = tokens.textSecondaryColor),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "CPU: ${container.cpuPercent}%  |  内存: ${container.memoryMb} MB",
                                style = TextStyle(fontSize = 11.sp, color = tokens.accentColor)
                            )
                        }
                    }

                    // Restart Action Button
                    GlassActionButton(
                        text = if (isRestarting) "重启中" else "重启",
                        enabled = !isRestarting,
                        onClick = { onRequestRestart(container.name) }
                    )
                }
            }
        }
    }
}

/**
 * Pulsing Status LED Indicator.
 */
@Composable
private fun LiveStatusLed(
    isRunning: Boolean,
    isRestarting: Boolean
) {
    val tokens = GlassTheme.tokens
    val ledColor = when {
        isRestarting -> tokens.statusWarning
        isRunning -> tokens.statusOnline
        else -> tokens.statusDanger
    }

    val infiniteTransition = rememberInfiniteTransition(label = "led_pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "led_alpha"
    )

    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(ledColor.copy(alpha = if (isRunning) alphaAnim * 0.25f else 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(ledColor)
        )
    }
}

// ==========================================================================================
// TAB 1: 【安全与日志】(Security & Logs - R5, R6)
// ==========================================================================================

enum class SecuritySubTab(val title: String) {
    DEFENSE("🛡️ 入侵防护"),
    ALERT_CONFIG("🔔 告警配置"),
    SYSTEM_LOGS("📜 服务日志")
}

@Composable
private fun SecurityAndLogsTabContent(
    defenseOverview: DefenseOverview?,
    bannedIps: List<BanEntry>,
    whitelist: List<SecurityWhitelistEntry>,
    securityEvents: List<SecurityEventItem>,
    systemLogs: List<SystemLog>,
    alertFilters: AlertFilterConfig,
    hazeState: HazeState,
    bottomPadding: Dp = 96.dp,
    onManualBan: (String, Int, String) -> Unit,
    onUnbanIp: (String) -> Unit,
    onAddWhitelist: (String, String) -> Unit,
    onRemoveWhitelist: (String) -> Unit,
    onAddAlertRecipient: (String) -> Unit,
    onDeleteAlertRecipient: (String) -> Unit,
    onUpdateAlertFilters: (AlertFilterConfig) -> Unit
) {
    val tokens = GlassTheme.tokens
    var activeSubTab by remember { mutableStateOf(SecuritySubTab.DEFENSE) }
    var showManualBanDialog by remember { mutableStateOf(false) }
    var showAddWhitelistDialog by remember { mutableStateOf(false) }
    var showAddRecipientDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Sub-Tab Switcher Pills (Reduces clutter: Defense | Alerts | Logs)
        item(key = "sub_tab_switcher") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.controlBg)
                    .border(1.dp, tokens.cardBorder, RoundedCornerShape(12.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SecuritySubTab.entries.forEach { subTab ->
                    val isSelected = subTab == activeSubTab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (isSelected) tokens.accentBg else Color.Transparent)
                            .border(1.dp, if (isSelected) tokens.accentColor.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(9.dp))
                            .clickable { activeSubTab = subTab }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = subTab.title,
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) tokens.textColor else tokens.textSecondaryColor
                            )
                        )
                    }
                }
            }
        }

        // SubTab 1: IP Blacklist & Whitelist Management
        if (activeSubTab == SecuritySubTab.DEFENSE) {
            item(key = "security_defense_section") {
                SecurityDefenseSection(
                    defenseOverview = defenseOverview,
                    bannedIps = bannedIps,
                    whitelist = whitelist,
                    securityEvents = securityEvents,
                    onManualBanClick = { showManualBanDialog = true },
                    onAddWhitelistClick = { showAddWhitelistDialog = true },
                    onUnbanIp = onUnbanIp,
                    onRemoveWhitelist = onRemoveWhitelist,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    hazeState = hazeState
                )
            }
        }

        // SubTab 2: Admin Alert Email Recipients & Push Filtering (R6)
        if (activeSubTab == SecuritySubTab.ALERT_CONFIG) {
            item(key = "alert_recipients_card") {
                AlertRecipientsCard(
                    defenseOverview = defenseOverview,
                    onAddRecipientClick = { showAddRecipientDialog = true },
                    onDeleteRecipient = onDeleteAlertRecipient,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    hazeState = hazeState
                )
            }

            item(key = "alert_filters_card") {
                AlertFiltersCard(
                    filters = alertFilters,
                    onUpdateFilters = onUpdateAlertFilters,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    hazeState = hazeState
                )
            }
        }

        // SubTab 3: System Service Logs
        if (activeSubTab == SecuritySubTab.SYSTEM_LOGS) {
            item(key = "service_logs_section") {
                ServiceLogsSection(
                    logs = systemLogs,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    hazeState = hazeState
                )
            }
        }

        // Avoid bottom navigation bar occlusion
        item(key = "bottom_spacer") {
            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }

    if (showManualBanDialog) {
        ManualBanDialog(
            onDismiss = { showManualBanDialog = false },
            onConfirm = { ip, duration, reason ->
                onManualBan(ip, duration, reason)
            }
        )
    }

    if (showAddWhitelistDialog) {
        AddWhitelistDialog(
            onDismiss = { showAddWhitelistDialog = false },
            onConfirm = { entry, note ->
                onAddWhitelist(entry, note)
            }
        )
    }

    if (showAddRecipientDialog) {
        AddAlertRecipientDialog(
            onDismiss = { showAddRecipientDialog = false },
            onConfirm = { email ->
                onAddAlertRecipient(email)
            }
        )
    }
}

/**
 * Admin Alert Email Recipients Management Card (R6).
 */
@Composable
private fun AlertRecipientsCard(
    defenseOverview: DefenseOverview?,
    onAddRecipientClick: () -> Unit,
    onDeleteRecipient: (String) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    val tokens = GlassTheme.tokens
    val recipients = defenseOverview?.allRecipients ?: emptyList()
    val isSmtpOk = defenseOverview?.smtpEnabled == true

    LiquidGlassCard(
        modifier = modifier,
        hazeState = hazeState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_alerts),
                        contentDescription = null,
                        tint = tokens.textColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "管理员告警通知邮箱",
                        style = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.textColor
                        )
                    )
                }

                GlassTag(
                    text = if (isSmtpOk) "SMTP 正常" else "SMTP 未就绪",
                    type = if (isSmtpOk) GlassTagType.Success else GlassTagType.Warning
                )
            }

            Text(
                text = "系统在触发高危安全事件、回滚或容器崩溃时，将自动向以下邮箱分发紧急警报。",
                style = TextStyle(fontSize = 11.5.sp, color = tokens.textSecondaryColor)
            )

            if (recipients.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(tokens.controlBg)
                        .padding(12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "暂未配置接收人邮箱，默认仅记录于系统审计日志",
                        style = TextStyle(fontSize = 12.sp, color = tokens.textSecondaryColor)
                    )
                }
            } else {
                recipients.forEach { email ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(tokens.surfaceColor.copy(alpha = 0.25f))
                            .border(1.dp, tokens.cardBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = email,
                            style = TextStyle(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = tokens.textColor
                            )
                        )

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0x33F43F5E),
                            border = BorderStroke(1.dp, Color(0x66F43F5E)),
                            modifier = Modifier.clickable { onDeleteRecipient(email) }
                        ) {
                            Text(
                                text = "移除",
                                style = TextStyle(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFF43F5E)
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            LiquidButton(
                onClick = onAddRecipientClick,
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 10.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+ 添加管理员邮箱",
                        style = TextStyle(
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.textColor
                        )
                    )
                }
            }
        }
    }
}

/**
 * Alert Push Content Filtering Card (R6).
 */
@Composable
private fun AlertFiltersCard(
    filters: AlertFilterConfig,
    onUpdateFilters: (AlertFilterConfig) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    val tokens = GlassTheme.tokens

    LiquidGlassCard(
        modifier = modifier,
        hazeState = hazeState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_settings),
                        contentDescription = null,
                        tint = tokens.textColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "告警推送内容过滤",
                        style = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.textColor
                        )
                    )
                }
                GlassTag(text = "实时生效", type = GlassTagType.Info)
            }

            Text(
                text = "按需开启敏感安全事件推送并设定最低严重级别阈值，避免非关键告警风暴。",
                style = TextStyle(fontSize = 11.5.sp, color = tokens.textSecondaryColor)
            )

            // Switch Item 1: 高危漏洞预警 (critical_vuln)
            AlertFilterSwitchRow(
                title = "高危漏洞预警",
                description = "Google OSV 匹配 CVSS ≥ 7.0 严重漏洞时推送",
                checked = filters.criticalVuln,
                onCheckedChange = { checked ->
                    onUpdateFilters(filters.copy(criticalVuln = checked))
                }
            )

            // Switch Item 2: 自动化故障回滚通知 (auto_rollback)
            AlertFilterSwitchRow(
                title = "自动化故障回滚通知",
                description = "更新后探针校验异常触发 SQLite 快照物理回滚时推送",
                checked = filters.autoRollback,
                onCheckedChange = { checked ->
                    onUpdateFilters(filters.copy(autoRollback = checked))
                }
            )

            // Switch Item 3: 暴力破解与封禁触发 (brute_force_ban)
            AlertFilterSwitchRow(
                title = "暴力破解与封禁触发",
                description = "外部 IP 触发阈值并被安全哨兵自动阻断时推送",
                checked = filters.bruteForceBan,
                onCheckedChange = { checked ->
                    onUpdateFilters(filters.copy(bruteForceBan = checked))
                }
            )

            // Switch Item 4: 核心容器崩溃退出 (container_crash)
            AlertFilterSwitchRow(
                title = "核心容器崩溃退出",
                description = "API / Frigate / Vision AI 容器非预期退出或频繁重启时推送",
                checked = filters.containerCrash,
                onCheckedChange = { checked ->
                    onUpdateFilters(filters.copy(containerCrash = checked))
                }
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Minimum Severity Selector
            Text(
                text = "最低通知严重级别 (Min Severity)",
                style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = tokens.textColor)
            )

            val severityLevels = listOf(
                "INFO" to "INFO",
                "WARNING" to "WARNING",
                "CRITICAL" to "CRITICAL"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                severityLevels.forEach { (levelKey, levelLabel) ->
                    val isSelected = filters.minSeverity.equals(levelKey, ignoreCase = true)
                    val activeBg = when (levelKey) {
                        "CRITICAL" -> Color(0x33F43F5E)
                        "WARNING" -> Color(0x33F59E0B)
                        else -> tokens.accentBg
                    }
                    val activeText = when (levelKey) {
                        "CRITICAL" -> Color(0xFFF43F5E)
                        "WARNING" -> Color(0xFFF59E0B)
                        else -> tokens.accentColor
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) activeBg else tokens.controlBg)
                            .border(
                                1.dp,
                                if (isSelected) activeText.copy(alpha = 0.6f) else tokens.cardBorder,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                onUpdateFilters(filters.copy(minSeverity = levelKey))
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = levelLabel,
                            style = TextStyle(
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) activeText else tokens.textSecondaryColor
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertFilterSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val tokens = GlassTheme.tokens

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tokens.surfaceColor.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = tokens.textColor
                )
            )
            Text(
                text = description,
                style = TextStyle(
                    fontSize = 10.5.sp,
                    color = tokens.textSecondaryColor
                )
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = tokens.accentColor,
                uncheckedThumbColor = tokens.textSecondaryColor,
                uncheckedTrackColor = tokens.cardBorder
            )
        )
    }
}

// ==========================================================================================
// TAB 2: 【待批提案】(Pending Proposals)
// ==========================================================================================

@Composable
private fun PendingProposalsTabContent(
    proposals: List<PendingProposal>,
    approvingMap: Map<String, Boolean>,
    hazeState: HazeState,
    bottomPadding: Dp = 96.dp,
    onApproveNow: (String) -> Unit,
    onScheduleNight: (String) -> Unit,
    onDiscussWithCopilot: (PendingProposal) -> Unit
) {
    val tokens = GlassTheme.tokens
    var confirmApproveTarget by remember { mutableStateOf<PendingProposal?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "待审提案",
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = tokens.textColor)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "由主动协作 Agent 在沙箱推演的高危配置、基础镜像与数据库升级方案",
                style = TextStyle(fontSize = 12.sp, color = tokens.textSecondaryColor)
            )
        }

        if (proposals.isEmpty()) {
            LiquidGlassCard(
                modifier = Modifier.fillMaxWidth(),
                hazeState = hazeState
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "暂无待审批提案",
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = tokens.textColor)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "所有核心服务与镜像均处于基线状态，运行平稳",
                        style = TextStyle(fontSize = 12.sp, color = tokens.textSecondaryColor)
                    )
                }
            }
        } else {
            proposals.forEach { proposal ->
                val isBusy = approvingMap[proposal.id] == true

                ProposalCard(
                    proposal = proposal,
                    isBusy = isBusy,
                    hazeState = hazeState,
                    onRequestApproveNow = { confirmApproveTarget = proposal },
                    onRequestScheduleNight = { onScheduleNight(proposal.id) },
                    onDiscussWithCopilot = { onDiscussWithCopilot(proposal) }
                )
            }
        }

        Spacer(modifier = Modifier.height(bottomPadding))
    }

    // Confirmation dialog for immediate execution
    confirmApproveTarget?.let { prop ->
        AlertDialog(
            onDismissRequest = { confirmApproveTarget = null },
            title = {
                Text(
                    text = "立即执行高危提案？",
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = tokens.textColor)
                )
            },
            text = {
                Text(
                    text = "提案「${prop.title}」预计耗时约 ${prop.estimatedSeconds} 秒，可能产生约 ${prop.downtimeSeconds} 秒瞬断。\n\n建议在业务低峰期执行，或选择【设定凌晨 03:00 自动执行】。",
                    style = TextStyle(fontSize = 14.sp, color = tokens.textSecondaryColor)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = prop.id
                        confirmApproveTarget = null
                        onApproveNow(id)
                    }
                ) {
                    Text(text = "立即执行", color = tokens.statusDanger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmApproveTarget = null }) {
                    Text(text = "取消", color = tokens.textColor)
                }
            },
            containerColor = tokens.dialogBg
        )
    }
}

/**
 * Individual Proposal Card with Risk Badges, Execution Estimates, and Action Buttons.
 */
@Composable
private fun ProposalCard(
    proposal: PendingProposal,
    isBusy: Boolean,
    hazeState: HazeState,
    onRequestApproveNow: () -> Unit,
    onRequestScheduleNight: () -> Unit,
    onDiscussWithCopilot: () -> Unit
) {
    val tokens = GlassTheme.tokens

    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        hazeState = hazeState
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Title & Risk badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = proposal.title,
                        style = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.textColor
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "目标组件: ${proposal.targetComponent} • 创建时间: ${proposal.createdAt}",
                        style = TextStyle(fontSize = 11.5.sp, color = tokens.textSecondaryColor)
                    )
                }

                // Risk Badge
                when {
                    proposal.isHighRisk -> GlassTag(text = "高危变更", type = GlassTagType.Error)
                    proposal.riskLevel.equals("MEDIUM", ignoreCase = true) -> GlassTag(text = "中风险", type = GlassTagType.Warning)
                    else -> GlassTag(text = "低风险", type = GlassTagType.Success)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Time estimate badge
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.controlBg)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "预计耗时: ${proposal.estimatedSeconds}秒  |  服务瞬断: 约 ${proposal.downtimeSeconds}秒",
                    style = TextStyle(fontSize = 11.5.sp, color = tokens.accentColor)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Description
            Text(
                text = proposal.description,
                style = TextStyle(fontSize = 13.sp, color = tokens.textColor, lineHeight = 18.sp)
            )

            // Rollback command summary
            if (proposal.rollbackPlan.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.controlBg)
                        .padding(10.dp)
                ) {
                    Text(
                        text = "推演回滚方案 (Rollback Plan):",
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tokens.textSecondaryColor)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = proposal.rollbackPlan,
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = tokens.textColor
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons
            if (proposal.isAwaitingApproval) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Button 1: 【立即执行】 (symmetric weight 1f)
                    LiquidButton(
                        onClick = onRequestApproveNow,
                        modifier = Modifier.weight(1f),
                        enabled = !isBusy,
                        hazeState = hazeState,
                        cornerRadius = 12.dp
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isBusy) "审批中..." else "立即执行",
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = tokens.statusDanger
                                )
                            )
                        }
                    }

                    // Button 2: 【预约 03:00 执行】 (symmetric weight 1f)
                    LiquidButton(
                        onClick = onRequestScheduleNight,
                        modifier = Modifier.weight(1f),
                        enabled = !isBusy,
                        hazeState = hazeState,
                        cornerRadius = 12.dp
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "预约 03:00 执行",
                                style = TextStyle(
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = tokens.accentColor
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Button 3: 【与 Copilot 讨论修改】
                LiquidButton(
                    onClick = onDiscussWithCopilot,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBusy,
                    hazeState = hazeState,
                    cornerRadius = 12.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_bolt),
                            contentDescription = null,
                            tint = tokens.accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "与 Copilot 讨论修改 (agent-deep 漏洞模型)",
                            style = TextStyle(
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = tokens.accentColor
                            )
                        )
                    }
                }
            } else if (proposal.isScheduled) {
                GlassTag(text = "已设定凌晨 03:00 自动执行", type = GlassTagType.Warning)
            } else {
                GlassTag(text = "已批准执行", type = GlassTagType.Success)
            }
        }
    }
}

// ==========================================================================================
// TAB 3: 【Copilot 对话】(Copilot Chat)
// ==========================================================================================

@Composable
private fun CopilotChatTabContent(
    messages: List<OpsChatMessage>,
    isLoading: Boolean,
    hazeState: HazeState,
    bottomPadding: Dp = 96.dp,
    isKeyboardOpen: Boolean = false,
    chatDraft: String? = null,
    onConsumeDraft: (() -> Unit)? = null,
    onSendMessage: (String) -> Unit
) {
    val tokens = GlassTheme.tokens
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Sync draft from pending proposal discussion (R7)
    LaunchedEffect(chatDraft) {
        if (!chatDraft.isNullOrBlank()) {
            inputText = chatDraft
            onConsumeDraft?.invoke()
        }
    }

    // Preset query chips
    val presetChips = remember {
        listOf(
            "分析过去 24 小时 SSH 攻击",
            "当前有哪些高危待批提案？",
            "检查 S3 异地备份状态",
            "为什么昨天凌晨 vision 重启了？",
            "查询容器资源消耗排行"
        )
    }

    LaunchedEffect(messages.size, isLoading) {
        val totalCount = messages.size + if (isLoading) 1 else 0
        if (totalCount > 1) {
            listState.animateScrollToItem(totalCount - 1)
        }
    }

    // Viewport adjustment on keyboard open/close
    LaunchedEffect(isKeyboardOpen) {
        if (isKeyboardOpen && messages.isNotEmpty()) {
            val target = messages.size - 1 + if (isLoading) 1 else 0
            listState.animateScrollToItem(target)
        } else if (!isKeyboardOpen && messages.size <= 3) {
            listState.animateScrollToItem(0)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Active Model Identifier Badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.controlBg)
                .border(1.dp, tokens.cardBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_bolt),
                contentDescription = null,
                tint = tokens.accentColor,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "推理引擎: agent-deep (CVE 漏洞加固与架构推演) • DeepSeek-R1",
                style = TextStyle(
                    fontSize = 11.sp,
                    color = tokens.textSecondaryColor,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Message History LazyColumn
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 6.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                ChatBubble(message = msg, hazeState = hazeState)
            }

            if (isLoading) {
                item {
                    ThinkingIndicator(hazeState = hazeState)
                }
            }
        }

        // Preset Query Chips Bar (hidden while soft keyboard is open to preserve vertical chat viewport)
        if (!isKeyboardOpen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presetChips.forEach { chipText ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(tokens.controlBg)
                            .border(1.dp, tokens.cardBorder, RoundedCornerShape(14.dp))
                            .clickable { onSendMessage(chipText) }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = chipText,
                            style = TextStyle(
                                fontSize = 11.5.sp,
                                color = tokens.textColor
                            )
                        )
                    }
                }
            }
        }

        // Frosted Input Bar at bottom: strictly sticks right above keyboard when open, or avoids bottom navigation bar when closed
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = bottomPadding)
                .clip(RoundedCornerShape(20.dp))
                .background(tokens.cardBg)
                .border(1.dp, tokens.cardBorder, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 6.dp),
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        color = tokens.textColor
                    ),
                    cursorBrush = SolidColor(tokens.accentColor),
                    decorationBox = { innerTextField ->
                        if (inputText.isEmpty()) {
                            Text(
                                text = "询问 Ops Copilot 运维大模型...",
                                style = TextStyle(fontSize = 13.5.sp, color = tokens.textSecondaryColor)
                            )
                        }
                        innerTextField()
                    }
                )

                Spacer(modifier = Modifier.width(8.dp))

                val canSend = inputText.isNotBlank() && !isLoading
                LiquidButton(
                    onClick = {
                        val text = inputText
                        inputText = ""
                        onSendMessage(text)
                    },
                    enabled = canSend,
                    cornerRadius = 14.dp
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "发送",
                            style = TextStyle(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (canSend) tokens.accentColor else tokens.textSecondaryColor
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual Chat Bubble (User aligned right, Assistant aligned left).
 */
@Composable
private fun ChatBubble(
    message: OpsChatMessage,
    hazeState: HazeState
) {
    val tokens = GlassTheme.tokens
    val isUser = message.isUser

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            // Assistant avatar / badge
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(tokens.accentBg)
                    .border(1.dp, tokens.accentColor.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "AI",
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = tokens.accentColor)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .background(if (isUser) tokens.accentBg else tokens.cardBg)
                .border(
                    1.dp,
                    if (isUser) tokens.accentColor.copy(alpha = 0.3f) else tokens.cardBorder,
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .padding(14.dp)
        ) {
            Column {
                if (!isUser) {
                    val modelName = message.model ?: "agent-deep"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ops Copilot • $modelName (CVE漏洞模型)",
                            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tokens.accentColor)
                        )
                        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
                        Text(
                            text = timeStr,
                            style = TextStyle(fontSize = 10.sp, color = tokens.textSecondaryColor)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Text(
                    text = message.content,
                    style = TextStyle(
                        fontSize = 13.5.sp,
                        color = tokens.textColor,
                        lineHeight = 19.sp
                    )
                )
            }
        }
    }
}

/**
 * Thinking indicator when Ops Copilot is generating response.
 */
@Composable
private fun ThinkingIndicator(hazeState: HazeState) {
    val tokens = GlassTheme.tokens

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(tokens.accentBg),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = tokens.accentColor
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.cardBg)
                .border(1.dp, tokens.cardBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = "Ops Copilot 思考诊断中...",
                style = TextStyle(fontSize = 12.sp, color = tokens.textSecondaryColor)
            )
        }
    }
}

private fun getStatusColor(percent: Float, tokens: GlassTokens): Color {
    return when {
        percent <= 60f -> tokens.statusOnline
        percent <= 80f -> tokens.statusWarning
        else -> tokens.statusDanger
    }
}
