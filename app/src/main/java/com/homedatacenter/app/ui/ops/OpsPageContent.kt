package com.homedatacenter.app.ui.ops

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.BanEntry
import com.homedatacenter.app.data.model.DefenseOverview
import com.homedatacenter.app.data.model.NetworkStatus
import com.homedatacenter.app.data.model.SecurityEventItem
import com.homedatacenter.app.data.model.SecurityWhitelistEntry
import com.homedatacenter.app.data.model.SystemLog
import com.homedatacenter.app.data.model.SystemStatus
import com.homedatacenter.app.ui.components.GlassActionButton
import com.homedatacenter.app.ui.components.GlassTag
import com.homedatacenter.app.ui.components.GlassTagType
import com.homedatacenter.app.ui.components.GlassText
import com.homedatacenter.app.ui.components.GlassTextType
import com.homedatacenter.app.ui.glass.AmbientFluidBackground
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.LiquidButton
import com.homedatacenter.app.ui.glass.LiquidGlassCard
import com.homedatacenter.app.ui.glass.ProvideGlassTheme
import com.homedatacenter.app.util.ThemeManager
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import java.util.Locale

/**
 * Data model representing microservices health status.
 */
data class ServiceHealthInfo(
    val name: String,
    val port: String,
    val description: String,
    val isOnline: Boolean,
    val statusText: String? = null,
    val latencyMs: Long? = null
)

fun formatUptime(seconds: Long): String {
    if (seconds < 60) return "刚刚启动"
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        days > 0 -> "${days}天 ${hours}小时"
        hours > 0 -> "${hours}小时 ${minutes}分钟"
        else -> "${minutes}分钟"
    }
}

/**
 * OpsPageContent — Pure Compose Liquid Glass Operations & Security Center.
 *
 * Implements Stage B requirements:
 * 1. OpsMetricsCard: System comprehensive metrics (CPU/RAM/Storage progress bars, net throughput & TCP status).
 * 2. OpsServicesCard: Microservice daemon status card (API, Frigate, MQTT, Vision AI status & ports).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpsPageContent(
    systemStatus: SystemStatus?,
    networkStatus: NetworkStatus?,
    defenseOverview: DefenseOverview?,
    bannedIps: List<BanEntry>,
    whitelist: List<SecurityWhitelistEntry>,
    securityEvents: List<SecurityEventItem>,
    systemLogs: List<SystemLog>,
    isRefreshing: Boolean,
    selectedSubTab: Int = 0,
    onSubTabChanged: (Int) -> Unit = {},
    onRefresh: () -> Unit,
    onCleanCache: () -> Unit,
    onManualBanClick: () -> Unit = {},
    onAddWhitelistClick: () -> Unit = {},
    onUnbanIp: (String) -> Unit = {},
    onRemoveWhitelist: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val preset by ThemeManager.themePresetFlow.collectAsState()
    val isDark = preset.isDark

    val colorTween = tween<Color>(durationMillis = 400)
    val animPrimary by animateColorAsState(if (isDark) Color(0xFFFFC38B) else Color(0xFFD7A064), colorTween, label = "ops_primary")
    val animSurface by animateColorAsState(if (isDark) Color(0x332C2836) else Color(0x99FFFFFF), colorTween, label = "ops_surface")
    val animOnSurface by animateColorAsState(if (isDark) Color(0xFFF8FAFC) else Color(0xFF1E1B24), colorTween, label = "ops_on_surface")
    val animBackground by animateColorAsState(preset.fluidBaseColor, colorTween, label = "ops_bg")
    val animOnBackground by animateColorAsState(if (isDark) Color(0xFFEAE2D8) else Color(0xFF3D3530), colorTween, label = "ops_on_bg")

    val m3ColorScheme = remember(animSurface, animOnSurface, animBackground, animOnBackground, isDark) {
        if (isDark) {
            darkColorScheme(
                primary = animPrimary,
                onPrimary = Color.White,
                surface = animSurface,
                onSurface = animOnSurface,
                background = animBackground,
                onBackground = animOnBackground
            )
        } else {
            lightColorScheme(
                primary = animPrimary,
                onPrimary = Color.White,
                surface = animSurface,
                onSurface = animOnSurface,
                background = animBackground,
                onBackground = animOnBackground
            )
        }
    }

    MaterialTheme(colorScheme = m3ColorScheme) {
        ProvideGlassTheme(preset = preset) {
            val tokens = GlassTheme.tokens
            val hazeState = rememberHazeState()
            val lazyListState = rememberLazyListState()
            val pullRefreshState = rememberPullToRefreshState()

            val fixedMode = remember { HazePerformanceMode.Fixed(0.4f) }
            val performanceMode = if (lazyListState.isScrollInProgress) {
                fixedMode
            } else {
                HazePerformanceMode.Adaptive
            }

            Box(modifier = modifier.fillMaxSize()) {
                // Layer 0: Ambient Fluid Mesh Background
                AmbientFluidBackground(
                    hazeState = hazeState,
                    preset = preset,
                    modifier = Modifier.fillMaxSize()
                )

                // Layer 1: Content with Material 3 PullToRefreshBox
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    state = pullRefreshState,
                    modifier = Modifier.fillMaxSize(),
                    indicator = {
                        PullToRefreshDefaults.Indicator(
                            state = pullRefreshState,
                            isRefreshing = isRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter),
                            containerColor = tokens.surfaceColor,
                            color = tokens.innerGlowColor.copy(alpha = 0.9f)
                        )
                    }
                ) {
                    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = statusBarTop + 12.dp, bottom = navBarBottom + 150.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Section Header & Subtab selector
                        item(key = "ops_header") {
                            OpsHeaderSection(
                                selectedSubTab = selectedSubTab,
                                onSubTabChanged = onSubTabChanged,
                                isServerOnline = systemStatus != null
                            )
                        }

                        when (selectedSubTab) {
                            0 -> {
                                // 1. OpsMetricsCard: System Metrics, Gauges, Throughput & TCP
                                item(key = "ops_metrics_card") {
                                    OpsMetricsCard(
                                        systemStatus = systemStatus,
                                        networkStatus = networkStatus,
                                        onCleanCache = onCleanCache,
                                        hazeState = hazeState,
                                        performanceMode = performanceMode,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp)
                                    )
                                }

                                // 2. OpsServicesCard: Microservices (API, Frigate, MQTT, Vision AI)
                                item(key = "ops_services_card") {
                                    val isServerOnline = systemStatus != null
                                    val servicesList = remember(systemStatus) {
                                        val mqttOk = systemStatus?.mqttConnected == true
                                        listOf(
                                            ServiceHealthInfo("Backend API", "8080", "Go Gin REST 业务主服务", isServerOnline, if (isServerOnline) "响应正常" else "离线"),
                                            ServiceHealthInfo("Frigate NVR", "1984 / 5000", "go2rtc WebRTC 视频推流网关", isServerOnline, if (isServerOnline) "伴生就绪" else "未连接"),
                                            ServiceHealthInfo("Mosquitto MQTT", "1883", "物联网与自动化消息总线", isServerOnline && mqttOk, if (!isServerOnline) "未连接" else if (mqttOk) "已连接" else "未连接"),
                                            ServiceHealthInfo("Vision AI", "8090", "OpenCV DNN 边缘人工智能识别", isServerOnline, if (isServerOnline) "守护运行中" else "未连接")
                                        )
                                    }

                                    OpsServicesCard(
                                        services = servicesList,
                                        isServerOnline = isServerOnline,
                                        hazeState = hazeState,
                                        performanceMode = performanceMode,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp)
                                    )
                                }
                            }
                            1 -> {
                                // Security Defense Subtab
                                item(key = "security_defense_content") {
                                    SecurityDefenseSection(
                                        defenseOverview = defenseOverview,
                                        bannedIps = bannedIps,
                                        whitelist = whitelist,
                                        securityEvents = securityEvents,
                                        onManualBanClick = onManualBanClick,
                                        onAddWhitelistClick = onAddWhitelistClick,
                                        onUnbanIp = onUnbanIp,
                                        onRemoveWhitelist = onRemoveWhitelist,
                                        hazeState = hazeState,
                                        performanceMode = performanceMode,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp)
                                    )
                                }
                            }
                            2 -> {
                                // Service Logs Subtab
                                item(key = "service_logs_content") {
                                    ServiceLogsSection(
                                        logs = systemLogs,
                                        hazeState = hazeState,
                                        performanceMode = performanceMode,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Top Header & Subtab segmented glass bar.
 */
@Composable
private fun OpsHeaderSection(
    selectedSubTab: Int,
    onSubTabChanged: (Int) -> Unit,
    isServerOnline: Boolean = true,
    modifier: Modifier = Modifier
) {
    val tokens = GlassTheme.tokens
    val tabs = listOf("系统监控", "安全防御", "服务日志")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isServerOnline) Color(0x3310B981) else Color(0x33F43F5E)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_security_shield),
                        contentDescription = "Ops",
                        tint = if (isServerOnline) Color(0xFF10B981) else Color(0xFFF43F5E),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column {
                    GlassText(
                        text = "系统运维中心",
                        type = GlassTextType.Header,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    GlassText(
                        text = "微服务集群与基础设施监控",
                        type = GlassTextType.Secondary,
                        fontSize = 11.sp
                    )
                }
            }

            GlassTag(
                text = if (isServerOnline) "系统正常" else "服务离线",
                type = if (isServerOnline) GlassTagType.Success else GlassTagType.Error
            )
        }

        // Segmented Tab Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(tokens.surfaceColor.copy(alpha = 0.35f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabs.forEachIndexed { index, title ->
                val isSelected = selectedSubTab == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected) tokens.surfaceColor.copy(alpha = 0.85f)
                            else Color.Transparent
                        )
                        .clickable { onSubTabChanged(index) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) tokens.textColor else tokens.textSecondaryColor
                    )
                }
            }
        }
    }
}

/**
 * 1. OpsMetricsCard
 * System comprehensive metrics (CPU/RAM/Storage progress bars, net throughput & TCP status).
 */
@Composable
fun OpsMetricsCard(
    systemStatus: SystemStatus?,
    networkStatus: NetworkStatus?,
    onCleanCache: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    performanceMode: HazePerformanceMode = HazePerformanceMode.Adaptive,
) {
    val tokens = GlassTheme.tokens
    val metrics = systemStatus?.metrics

    val cpuPercent = metrics?.effectiveCpuPercent ?: 0.0
    val memPercent = metrics?.effectiveMemoryPercent ?: 0.0
    val memUsedMb = metrics?.effectiveMemoryUsedMb ?: 0L
    val memTotalMb = metrics?.effectiveMemoryTotalMb ?: 0L
    val storagePercent = metrics?.effectiveRecordingsPercent ?: 0.0
    val storageUsedGb = metrics?.effectiveRecordingsUsedGb ?: 0.0
    val storageLimitGb = metrics?.effectiveRecordingsLimitGb ?: 400.0
    val transcodeCacheMb = metrics?.effectiveTranscodeCacheMb ?: 0L

    LiquidGlassCard(
        modifier = modifier,
        hazeState = hazeState,
        performanceMode = performanceMode
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassText(
                    text = "系统综合负载与性能指标",
                    type = GlassTextType.Title,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                    maxLines = 1
                )

                val isOnline = systemStatus != null
                GlassTag(
                    text = if (isOnline) "在线运行中" else "服务离线",
                    type = if (isOnline) GlassTagType.Success else GlassTagType.Error
                )
            }

            // 1. CPU Load Progress
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassText(
                        text = "处理器 (CPU) 负载",
                        type = GlassTextType.Secondary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (systemStatus != null) {
                            String.format(Locale.getDefault(), "%.1f%% (%d核)", cpuPercent, metrics?.cpu?.cores ?: 4)
                        } else {
                            "离线 / 无数据"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (systemStatus != null) tokens.textColor else tokens.textSecondaryColor,
                        maxLines = 1
                    )
                }
                LinearProgressIndicator(
                    progress = { if (systemStatus != null) (cpuPercent / 100f).toFloat().coerceIn(0f, 1f) else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (systemStatus == null) tokens.textColor.copy(alpha = 0.2f) else if (cpuPercent > 80.0) Color(0xFFF43F5E) else Color(0xFF10B981),
                    trackColor = tokens.textColor.copy(alpha = 0.12f)
                )
            }

            // 2. RAM Load Progress
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassText(
                        text = "系统内存 (RAM) 占用",
                        type = GlassTextType.Secondary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (systemStatus != null) {
                            String.format(Locale.getDefault(), "%.1f%% (%d/%d MB)", memPercent, memUsedMb, memTotalMb)
                        } else {
                            "离线 / 无数据"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (systemStatus != null) tokens.textColor else tokens.textSecondaryColor,
                        maxLines = 1
                    )
                }
                LinearProgressIndicator(
                    progress = { if (systemStatus != null) (memPercent / 100f).toFloat().coerceIn(0f, 1f) else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (systemStatus == null) tokens.textColor.copy(alpha = 0.2f) else if (memPercent > 85.0) Color(0xFFF59E0B) else Color(0xFF3B82F6),
                    trackColor = tokens.textColor.copy(alpha = 0.12f)
                )
            }

            // 3. Storage & Transcode Cache
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassText(
                        text = "录像配额与磁盘存储",
                        type = GlassTextType.Secondary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (systemStatus != null) {
                            String.format(Locale.getDefault(), "%.1f / %.0f GB (%.1f%%)", storageUsedGb, storageLimitGb, storagePercent)
                        } else {
                            "离线 / 无数据"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (systemStatus != null) tokens.textColor else tokens.textSecondaryColor,
                        maxLines = 1
                    )
                }
                LinearProgressIndicator(
                    progress = { if (systemStatus != null) (storagePercent / 100f).toFloat().coerceIn(0f, 1f) else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (systemStatus == null) tokens.textColor.copy(alpha = 0.2f) else if (storagePercent > 90.0) Color(0xFFF43F5E) else Color(0xFF8B5CF6),
                    trackColor = tokens.textColor.copy(alpha = 0.12f)
                )
            }

            // Transcode Cache Clean Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.surfaceColor.copy(alpha = 0.25f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        text = "视频转码临时缓存",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = tokens.textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (systemStatus != null) "已占用 $transcodeCacheMb MB" else "未连接",
                        fontSize = 11.sp,
                        color = tokens.textSecondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                LiquidButton(
                    onClick = { if (systemStatus != null) onCleanCache() },
                    cornerRadius = 10.dp,
                    tokens = tokens,
                    enabled = systemStatus != null
                ) {
                    Text(
                        text = "清理缓存",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (systemStatus != null) tokens.textColor else tokens.textSecondaryColor
                    )
                }
            }

            // 4. Network Realtime Throughput & TCP Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // WebSocket Clients
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(tokens.surfaceColor.copy(alpha = 0.25f))
                        .padding(8.dp)
                ) {
                    GlassText(text = "实时长连接", type = GlassTextType.Secondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (systemStatus != null) "${systemStatus.wsClients} 个 WS 活跃" else "未连接 (离线)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (systemStatus != null) tokens.textColor else tokens.textSecondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // TCP & NAT Tunnel Status
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(tokens.surfaceColor.copy(alpha = 0.25f))
                        .padding(8.dp)
                ) {
                    GlassText(text = "网络通道与 NAT", type = GlassTextType.Secondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (networkStatus != null) {
                            "NAT: ${networkStatus.nat.type} (${networkStatus.strategy.uppercase(Locale.getDefault())})"
                        } else {
                            "网络未同步 (离线)"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (networkStatus != null) tokens.textColor else tokens.textSecondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 2. OpsServicesCard
 * Microservice daemon status card (API, Frigate, MQTT, Vision AI status & ports).
 */
@Composable
fun OpsServicesCard(
    services: List<ServiceHealthInfo>,
    isServerOnline: Boolean = true,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    performanceMode: HazePerformanceMode = HazePerformanceMode.Adaptive,
) {
    val tokens = GlassTheme.tokens

    LiquidGlassCard(
        modifier = modifier,
        hazeState = hazeState,
        performanceMode = performanceMode
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_devices),
                        contentDescription = "Services",
                        tint = tokens.textColor,
                        modifier = Modifier.size(18.dp)
                    )
                    GlassText(
                        text = "守护微服务集群状态",
                        type = GlassTextType.Title,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                val allOnline = isServerOnline && services.isNotEmpty() && services.all { it.isOnline }
                GlassTag(
                    text = if (!isServerOnline) "服务器离线" else if (allOnline) "所有微服务正常" else "存在异常服务",
                    type = if (!isServerOnline) GlassTagType.Error else if (allOnline) GlassTagType.Success else GlassTagType.Warning
                )
            }

            // Services list
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                services.forEach { service ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(tokens.surfaceColor.copy(alpha = 0.25f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(if (service.isOnline) Color(0xFF10B981) else Color(0xFFF43F5E))
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = service.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = tokens.textColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val isMultiOrSpecial = service.port.startsWith(":") || service.port.contains(":") || service.port.contains("/")
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = tokens.textColor.copy(alpha = 0.08f),
                                        border = BorderStroke(1.dp, if (tokens.isLight) Color(0x33000000) else Color(0x1AFFFFFF))
                                    ) {
                                        Text(
                                            text = if (isMultiOrSpecial) service.port else ":${service.port}",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = tokens.textSecondaryColor,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = service.description,
                                    fontSize = 11.sp,
                                    color = tokens.textSecondaryColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        GlassTag(
                            text = service.statusText ?: (if (service.isOnline) "运行中" else if (!isServerOnline) "未连接" else "异常"),
                            type = if (service.isOnline) GlassTagType.Success else GlassTagType.Error
                        )
                    }
                }
            }
        }
    }
}

/**
 * Security Defense Section (Bans, Whitelist, Events).
 */
@Composable
internal fun SecurityDefenseSection(
    defenseOverview: DefenseOverview?,
    bannedIps: List<BanEntry>,
    whitelist: List<SecurityWhitelistEntry>,
    securityEvents: List<SecurityEventItem>,
    onManualBanClick: () -> Unit,
    onAddWhitelistClick: () -> Unit,
    onUnbanIp: (String) -> Unit,
    onRemoveWhitelist: (String) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    performanceMode: HazePerformanceMode = HazePerformanceMode.Adaptive,
) {
    val tokens = GlassTheme.tokens
    var isBannedExpanded by remember { mutableStateOf(true) }
    var banSearchQuery by remember { mutableStateOf("") }

    var isWhitelistExpanded by remember { mutableStateOf(true) }
    var whitelistSearchQuery by remember { mutableStateOf("") }

    val filteredBans = remember(bannedIps, banSearchQuery) {
        val q = banSearchQuery.trim()
        if (q.isEmpty()) bannedIps else {
            bannedIps.filter {
                it.ip.contains(q, ignoreCase = true) || it.reason.contains(q, ignoreCase = true)
            }
        }
    }

    val filteredWhitelist = remember(whitelist, whitelistSearchQuery) {
        val q = whitelistSearchQuery.trim()
        if (q.isEmpty()) whitelist else {
            whitelist.filter {
                it.entry.contains(q, ignoreCase = true) || it.note.contains(q, ignoreCase = true)
            }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. Defense Overview Card
        LiquidGlassCard(
            hazeState = hazeState,
            performanceMode = performanceMode,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassText(
                        text = "安全防御态势",
                        type = GlassTextType.Title,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                        maxLines = 1
                    )
                    GlassTag(text = "自动封禁已启用", type = GlassTagType.Success)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(tokens.surfaceColor.copy(alpha = 0.25f))
                            .padding(10.dp)
                    ) {
                        GlassText(text = "已封禁恶意 IP", type = GlassTextType.Secondary, fontSize = 11.sp)
                        Text(
                            text = "${bannedIps.size} 个",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF43F5E)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(tokens.surfaceColor.copy(alpha = 0.25f))
                            .padding(10.dp)
                    ) {
                        GlassText(text = "信任白名单", type = GlassTextType.Secondary, fontSize = 11.sp)
                        Text(
                            text = "${whitelist.size} 条",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiquidButton(onClick = onManualBanClick, modifier = Modifier.weight(1f), tokens = tokens) {
                        Text(text = "+ 手动封禁 IP", fontSize = 12.sp, color = tokens.textColor)
                    }
                    LiquidButton(onClick = onAddWhitelistClick, modifier = Modifier.weight(1f), tokens = tokens) {
                        Text(text = "+ 添加白名单", fontSize = 12.sp, color = tokens.textColor)
                    }
                }
            }
        }

        // 2. Collapsible Banned IP List Card with Search Filter
        LiquidGlassCard(
            hazeState = hazeState,
            performanceMode = performanceMode,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(14.dp)
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header (Click to toggle collapse)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isBannedExpanded = !isBannedExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassText(text = "封禁 IP 列表", type = GlassTextType.Title, fontWeight = FontWeight.Bold)
                        GlassTag(text = "${bannedIps.size}", type = if (bannedIps.isNotEmpty()) GlassTagType.Error else GlassTagType.Info)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (isBannedExpanded) "收起" else "展开",
                            fontSize = 12.sp,
                            color = tokens.textSecondaryColor
                        )
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_down),
                            contentDescription = if (isBannedExpanded) "收起" else "展开",
                            tint = tokens.textSecondaryColor,
                            modifier = Modifier
                                .size(16.dp)
                                .rotate(if (isBannedExpanded) 180f else 0f)
                        )
                    }
                }

                if (isBannedExpanded) {
                    // Search Bar (44dp centered standard)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(tokens.controlBg)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = "Search",
                            tint = tokens.textSecondaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        androidx.compose.foundation.text.BasicTextField(
                            value = banSearchQuery,
                            onValueChange = { banSearchQuery = it },
                            modifier = Modifier.weight(1f),
                            textStyle = TextStyle(
                                color = tokens.textColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            singleLine = true,
                            cursorBrush = SolidColor(tokens.accentColor),
                            decorationBox = { innerTextField ->
                                if (banSearchQuery.isEmpty()) {
                                    Text(
                                        text = "搜索封禁 IP / 触发原因...",
                                        color = tokens.textSecondaryColor.copy(alpha = 0.6f),
                                        fontSize = 13.sp
                                    )
                                }
                                innerTextField()
                            }
                        )
                        if (banSearchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { banSearchQuery = "" },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = "Clear",
                                    tint = tokens.textSecondaryColor,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }

                    if (banSearchQuery.isNotEmpty()) {
                        Text(
                            text = "匹配 ${filteredBans.size} / 共 ${bannedIps.size} 项",
                            fontSize = 11.sp,
                            color = tokens.textSecondaryColor,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    }

                    if (filteredBans.isEmpty()) {
                        Text(
                            text = if (bannedIps.isEmpty()) "当前无被封禁的外部 IP" else "未找到与 \"$banSearchQuery\" 匹配的封禁记录",
                            fontSize = 12.sp,
                            color = tokens.textSecondaryColor,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        filteredBans.forEach { ban ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(tokens.surfaceColor.copy(alpha = 0.2f))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                ) {
                                    Text(
                                        text = ban.ip,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = tokens.textColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val banDetail = listOf(
                                        if (ban.reason.isNotBlank()) "原因: ${ban.reason}" else "",
                                        if (ban.bannedAt.isNotBlank()) "时间: ${ban.bannedAt}" else ""
                                    ).filter { it.isNotBlank() }.joinToString(" · ")
                                    if (banDetail.isNotBlank()) {
                                        Text(
                                            text = banDetail,
                                            fontSize = 11.sp,
                                            color = tokens.textSecondaryColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0x33F43F5E),
                                    border = BorderStroke(1.dp, if (tokens.isLight) Color(0x66F43F5E) else Color(0x4DF43F5E)),
                                    modifier = Modifier.clickable { onUnbanIp(ban.ip) }
                                ) {
                                    Text(
                                        text = "解封",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFF43F5E),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Collapsible Whitelist Card with Search Filter
        LiquidGlassCard(
            hazeState = hazeState,
            performanceMode = performanceMode,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(14.dp)
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header (Click to toggle collapse)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isWhitelistExpanded = !isWhitelistExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassText(text = "信任白名单列表", type = GlassTextType.Title, fontWeight = FontWeight.Bold)
                        GlassTag(text = "${whitelist.size}", type = GlassTagType.Success)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (isWhitelistExpanded) "收起" else "展开",
                            fontSize = 12.sp,
                            color = tokens.textSecondaryColor
                        )
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_down),
                            contentDescription = if (isWhitelistExpanded) "收起" else "展开",
                            tint = tokens.textSecondaryColor,
                            modifier = Modifier
                                .size(16.dp)
                                .rotate(if (isWhitelistExpanded) 180f else 0f)
                        )
                    }
                }

                if (isWhitelistExpanded) {
                    // Search Bar (44dp centered standard)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(tokens.controlBg)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = "Search",
                            tint = tokens.textSecondaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        androidx.compose.foundation.text.BasicTextField(
                            value = whitelistSearchQuery,
                            onValueChange = { whitelistSearchQuery = it },
                            modifier = Modifier.weight(1f),
                            textStyle = TextStyle(
                                color = tokens.textColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            singleLine = true,
                            cursorBrush = SolidColor(tokens.accentColor),
                            decorationBox = { innerTextField ->
                                if (whitelistSearchQuery.isEmpty()) {
                                    Text(
                                        text = "搜索白名单 IP / 备注说明...",
                                        color = tokens.textSecondaryColor.copy(alpha = 0.6f),
                                        fontSize = 13.sp
                                    )
                                }
                                innerTextField()
                            }
                        )
                        if (whitelistSearchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { whitelistSearchQuery = "" },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = "Clear",
                                    tint = tokens.textSecondaryColor,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }

                    if (whitelistSearchQuery.isNotEmpty()) {
                        Text(
                            text = "匹配 ${filteredWhitelist.size} / 共 ${whitelist.size} 项",
                            fontSize = 11.sp,
                            color = tokens.textSecondaryColor,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    }

                    if (filteredWhitelist.isEmpty()) {
                        Text(
                            text = if (whitelist.isEmpty()) "当前无白名单记录" else "未找到与 \"$whitelistSearchQuery\" 匹配的白名单记录",
                            fontSize = 12.sp,
                            color = tokens.textSecondaryColor,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        filteredWhitelist.forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(tokens.surfaceColor.copy(alpha = 0.2f))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                ) {
                                    Text(
                                        text = entry.entry,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = tokens.textColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val noteText = listOf(
                                        if (entry.note.isNotBlank()) "备注: ${entry.note}" else "",
                                        if (entry.createdAt.isNotBlank()) "添加于: ${entry.createdAt}" else ""
                                    ).filter { it.isNotBlank() }.joinToString(" · ")
                                    if (noteText.isNotBlank()) {
                                        Text(
                                            text = noteText,
                                            fontSize = 11.sp,
                                            color = tokens.textSecondaryColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = tokens.controlBg,
                                    border = BorderStroke(1.dp, tokens.cardBorder),
                                    modifier = Modifier.clickable { onRemoveWhitelist(entry.entry) }
                                ) {
                                    Text(
                                        text = "移除",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = tokens.textSecondaryColor,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Service Logs Section with search, level filters, and source filters.
 */
@Composable
internal fun ServiceLogsSection(
    logs: List<SystemLog>,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    performanceMode: HazePerformanceMode = HazePerformanceMode.Adaptive,
) {
    val tokens = GlassTheme.tokens
    var searchKeyword by remember { mutableStateOf("") }
    var selectedLevel by remember { mutableStateOf("all") }
    var selectedSource by remember { mutableStateOf("all") }

    val availableSources = remember(logs) {
        val set = mutableSetOf("all")
        logs.forEach { if (it.source.isNotBlank()) set.add(it.source) }
        set.toList()
    }

    val filteredLogs = remember(logs, searchKeyword, selectedLevel, selectedSource) {
        logs.filter { log ->
            val matchesLevel = when (selectedLevel) {
                "all" -> true
                else -> log.level.equals(selectedLevel, ignoreCase = true)
            }
            val matchesSource = when (selectedSource) {
                "all" -> true
                else -> log.source.equals(selectedSource, ignoreCase = true)
            }
            val matchesKeyword = if (searchKeyword.isBlank()) {
                true
            } else {
                val q = searchKeyword.trim()
                log.message.contains(q, ignoreCase = true) ||
                    log.source.contains(q, ignoreCase = true) ||
                    log.event_type.contains(q, ignoreCase = true) ||
                    log.payload.contains(q, ignoreCase = true)
            }
            matchesLevel && matchesSource && matchesKeyword
        }
    }

    LiquidGlassCard(
        modifier = modifier,
        hazeState = hazeState,
        performanceMode = performanceMode
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Title + Matching Count Tag
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
                        painter = painterResource(id = R.drawable.ic_history),
                        contentDescription = "Logs",
                        tint = tokens.textColor,
                        modifier = Modifier.size(18.dp)
                    )
                    GlassText(
                        text = "微服务运行日志",
                        type = GlassTextType.Title,
                        fontWeight = FontWeight.Bold
                    )
                }

                GlassTag(
                    text = "${filteredLogs.size}/${logs.size} 条",
                    type = if (filteredLogs.isEmpty()) GlassTagType.Neutral else GlassTagType.Info
                )
            }

            // Search Keyword Bar (Standardized 44dp height, vertically centered icon & clear action)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        tokens.surfaceColor.copy(alpha = if (tokens.isLight) 0.50f else 0.18f)
                    )
                    .border(
                        width = 1.dp,
                        color = if (tokens.isLight) Color(0x33000000) else Color(0x26FFFFFF),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_search),
                    contentDescription = "Search",
                    tint = tokens.textSecondaryColor,
                    modifier = Modifier.size(16.dp)
                )

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (searchKeyword.isEmpty()) {
                        Text(
                            text = "搜索日志消息、来源或事件...",
                            fontSize = 12.sp,
                            color = tokens.textSecondaryColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = searchKeyword,
                        onValueChange = { searchKeyword = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = tokens.textColor,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        cursorBrush = SolidColor(if (tokens.isLight) Color(0xFFD7A064) else Color(0xFFFFC38B)),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (searchKeyword.isNotEmpty()) {
                    IconButton(
                        onClick = { searchKeyword = "" },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_close),
                            contentDescription = "Clear",
                            tint = tokens.textSecondaryColor,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            // Level Filter Chips (Horizontal Scrollable)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val levels = listOf(
                    "all" to "全部级别",
                    "critical" to "待处理 (Critical)",
                    "warning" to "警告 (Warning)",
                    "normal" to "常规 (Normal)",
                    "info" to "提示 (Info)"
                )

                levels.forEach { (levelKey, levelLabel) ->
                    val isSelected = selectedLevel == levelKey
                    val chipBg = when {
                        isSelected && levelKey == "critical" -> Color(0x33F43F5E)
                        isSelected && levelKey == "warning" -> Color(0x33F59E0B)
                        isSelected && levelKey == "normal" -> Color(0x3310B981)
                        isSelected && levelKey == "info" -> Color(0x3338BDF8)
                        isSelected -> if (tokens.isLight) Color(0x33D7A064) else Color(0x40FFC38B)
                        else -> tokens.surfaceColor.copy(alpha = if (tokens.isLight) 0.35f else 0.12f)
                    }
                    val chipTextColor = when {
                        isSelected && levelKey == "critical" -> Color(0xFFF43F5E)
                        isSelected && levelKey == "warning" -> Color(0xFFF59E0B)
                        isSelected && levelKey == "normal" -> Color(0xFF10B981)
                        isSelected && levelKey == "info" -> Color(0xFF38BDF8)
                        isSelected -> tokens.textColor
                        else -> tokens.textSecondaryColor
                    }
                    val chipBorderColor = when {
                        isSelected -> if (tokens.isLight) chipTextColor.copy(alpha = 0.8f) else chipTextColor.copy(alpha = 0.6f)
                        tokens.isLight -> Color(0x33000000)
                        else -> Color(0x1AFFFFFF)
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = chipBg,
                        border = BorderStroke(1.dp, chipBorderColor),
                        modifier = Modifier.clickable { selectedLevel = levelKey }
                    ) {
                        Text(
                            text = levelLabel,
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = chipTextColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            // Source Filter Chips (if multiple sources exist)
            if (availableSources.size > 2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    availableSources.forEach { sourceKey ->
                        val isSelected = selectedSource == sourceKey
                        val label = if (sourceKey == "all") "全部来源" else "[${sourceKey}]"
                        val chipBg = if (isSelected) {
                            if (tokens.isLight) Color(0x260284C7) else Color(0x3338BDF8)
                        } else {
                            tokens.surfaceColor.copy(alpha = if (tokens.isLight) 0.35f else 0.08f)
                        }
                        val chipBorder = if (isSelected) {
                            if (tokens.isLight) Color(0xFF0284C7) else Color(0xFF38BDF8)
                        } else {
                            if (tokens.isLight) Color(0x33000000) else Color(0x1AFFFFFF)
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = chipBg,
                            border = BorderStroke(1.dp, chipBorder),
                            modifier = Modifier.clickable { selectedSource = sourceKey }
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) (if (tokens.isLight) Color(0xFF0284C7) else Color(0xFF38BDF8)) else tokens.textSecondaryColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.5.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Logs list
            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (logs.isEmpty()) "暂无系统运行日志" else "未找到符合筛选条件的日志",
                        fontSize = 12.5.sp,
                        color = tokens.textSecondaryColor
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    filteredLogs.take(50).forEach { log ->
                        key(log.id) {
                            val levelColor = when (log.level.lowercase(Locale.getDefault())) {
                            "critical" -> Color(0xFFF43F5E)
                            "warning" -> Color(0xFFF59E0B)
                            "info" -> Color(0xFF38BDF8)
                            else -> Color(0xFF10B981)
                        }
                        var isExpanded by remember(log.id) { mutableStateOf(false) }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(tokens.surfaceColor.copy(alpha = if (tokens.isLight) 0.45f else 0.22f))
                                .border(
                                    width = 1.dp,
                                    color = if (tokens.isLight) Color(0x33000000) else Color(0x1AFFFFFF),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { isExpanded = !isExpanded }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(levelColor)
                                    )
                                    Text(
                                        text = "[${log.source.ifBlank { "system" }}]",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (tokens.isLight) Color(0xFFD97706) else Color(0xFFFFC38B),
                                        maxLines = 1
                                    )
                                }

                                Text(
                                    text = formatLogTime(log.ts),
                                    fontSize = 10.5.sp,
                                    color = tokens.textSecondaryColor
                                )
                            }

                            Text(
                                text = log.message,
                                fontSize = 12.sp,
                                color = tokens.textColor,
                                maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (isExpanded && (log.payload.isNotBlank() || log.event_type.isNotBlank())) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(tokens.textColor.copy(alpha = 0.05f))
                                        .padding(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    if (log.event_type.isNotBlank()) {
                                        Text(
                                            text = "事件类型: ${log.event_type}",
                                            fontSize = 10.5.sp,
                                            color = tokens.textSecondaryColor
                                        )
                                    }
                                    if (log.payload.isNotBlank()) {
                                        Text(
                                            text = "详情载荷: ${log.payload}",
                                            fontSize = 10.5.sp,
                                            color = tokens.textSecondaryColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

private fun formatLogTime(ts: Long): String {
    if (ts <= 0) return "--:--:--"
    val epochMillis = if (ts < 10000000000L) ts * 1000 else ts
    val date = java.util.Date(epochMillis)
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return sdf.format(date)
}
