package com.homedatacenter.app.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.Alert
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.data.model.SecurityGuard
import com.homedatacenter.app.data.model.SystemLog
import com.homedatacenter.app.data.model.SystemStatus
import com.homedatacenter.app.data.model.WeatherResponse
import com.homedatacenter.app.ui.cameras.AlertThumbnailResolver
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DashboardPageContent — Pure Compose Liquid Glass Dashboard Screen.
 *
 * Implements Stage B requirements:
 * 1. OverviewSummaryCard: System health, uptime, core metrics (CPU/RAM load, container count).
 * 2. QuickActionsRow / QuickActionButton: Ops center, user permissions, security arming/disarming.
 * 3. CameraFeedCard: Realtime camera preview, camera status, AI recognition alert badge.
 * 4. RecentEventsCard: Recent system snapshots and automation/security event stream.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardPageContent(
    systemStatus: SystemStatus?,
    securityGuard: SecurityGuard?,
    cameras: List<Camera>,
    alerts: List<Alert>,
    recentLogs: List<SystemLog>,
    weather: WeatherResponse?,
    backendPathText: String,
    backendPathIsFast: Boolean,
    isRefreshing: Boolean,
    isAdmin: Boolean,
    baseUrl: String?,
    token: String?,
    userName: String = "管理员",
    onRefresh: () -> Unit,
    onNavigateOps: () -> Unit,
    onNavigateUsers: () -> Unit,
    onToggleSecurity: () -> Unit,
    onNavigateAutomations: () -> Unit,
    onNavigateNetworkDetail: () -> Unit,
    onNavigateCameras: () -> Unit,
    onCameraClick: (Camera) -> Unit = {},
    onMultiCameraClick: () -> Unit = {},
    onViewAllAlerts: () -> Unit,
    onViewAllLogs: () -> Unit,
    onAlertClick: (Alert) -> Unit,
    onCleanCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preset by ThemeManager.themePresetFlow.collectAsState()
    val isDark = preset.isDark

    // Material 3 ColorScheme matching ambient fluid glass theme
    val colorTween = tween<Color>(durationMillis = 400)
    val animPrimary by animateColorAsState(if (isDark) Color(0xFFFFC38B) else Color(0xFFD7A064), colorTween, label = "m3_primary")
    val animSurface by animateColorAsState(if (isDark) Color(0x332C2836) else Color(0x99FFFFFF), colorTween, label = "m3_surface")
    val animOnSurface by animateColorAsState(if (isDark) Color(0xFFF8FAFC) else Color(0xFF1E1B24), colorTween, label = "m3_on_surface")
    val animBackground by animateColorAsState(preset.fluidBaseColor, colorTween, label = "m3_bg")
    val animOnBackground by animateColorAsState(if (isDark) Color(0xFFEAE2D8) else Color(0xFF3D3530), colorTween, label = "m3_on_bg")

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
                // Layer 0: Fluid mesh background
                AmbientFluidBackground(
                    hazeState = hazeState,
                    preset = preset,
                    modifier = Modifier.fillMaxSize()
                )

                // Layer 1: Pull to refresh & scrollable content
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
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 100.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 1. Top Header Banner (Greeting + Network Path Chip)
                        item(key = "dashboard_header") {
                            DashboardHeaderSection(
                                userName = userName,
                                backendPathText = backendPathText,
                                backendPathIsFast = backendPathIsFast,
                                weather = weather,
                                onPathClick = onNavigateNetworkDetail
                            )
                        }

                        // 2. OverviewSummaryCard (System Health, Uptime, Core Metrics) - Admin Only
                        if (isAdmin) {
                            item(key = "overview_summary") {
                                OverviewSummaryCard(
                                    systemStatus = systemStatus,
                                    onCleanCache = onCleanCache,
                                    hazeState = hazeState,
                                    performanceMode = performanceMode,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                )
                            }
                        }

                        // 3. QuickActionsRow (Ops, Users, Security, Automations)
                        item(key = "quick_actions") {
                            QuickActionsRow(
                                securityGuard = securityGuard,
                                isAdmin = isAdmin,
                                onNavigateOps = onNavigateOps,
                                onNavigateUsers = onNavigateUsers,
                                onToggleSecurity = onToggleSecurity,
                                onNavigateAutomations = onNavigateAutomations,
                                hazeState = hazeState,
                                performanceMode = performanceMode,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            )
                        }

                        // 4. CameraFeedCard (Realtime Camera Preview & AI Alert Badge)
                        item(key = "camera_feed_preview") {
                            CameraFeedCard(
                                cameras = cameras,
                                alerts = alerts,
                                baseUrl = baseUrl,
                                token = token,
                                onCardClick = onNavigateCameras,
                                onCameraClick = onCameraClick,
                                onMultiCameraClick = onMultiCameraClick,
                                hazeState = hazeState,
                                performanceMode = performanceMode,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            )
                        }

                        // 5. RecentEventsCard (Snapshots & Events Stream)
                        item(key = "recent_events") {
                            RecentEventsCard(
                                alerts = alerts,
                                recentLogs = recentLogs,
                                baseUrl = baseUrl,
                                token = token,
                                onAlertClick = onAlertClick,
                                onViewAllAlerts = onViewAllAlerts,
                                onViewAllLogs = onViewAllLogs,
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

/**
 * Top Header Banner displaying user greeting, weather, and network latency pill.
 */
@Composable
private fun DashboardHeaderSection(
    userName: String,
    backendPathText: String,
    backendPathIsFast: Boolean,
    weather: WeatherResponse?,
    onPathClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = GlassTheme.tokens
    val condition = weather?.currentCondition?.firstOrNull()
    val temp = condition?.tempC.orEmpty()
    val rawDesc = condition?.weatherDesc?.firstOrNull()?.value.orEmpty()
    val desc = when (rawDesc.trim().lowercase()) {
        "clear", "sunny" -> "晴"
        "partly cloudy" -> "多云"
        "cloudy", "overcast" -> "阴"
        "light rain", "patchy light rain", "patchy rain possible" -> "小雨"
        "moderate rain", "heavy rain" -> "雨"
        "thundery outbreaks possible" -> "雷阵雨"
        "fog", "mist" -> "雾"
        "light snow", "patchy snow possible" -> "小雪"
        else -> rawDesc
    }
    val city = "陈仓区"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Row 1: Greeting on Left, Network Path Chip on Right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassText(
                text = "你好，",
                type = GlassTextType.Secondary,
                fontSize = 13.sp
            )

            // Modern, single-line Glass Network Path Chip
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (backendPathIsFast) Color(0x3310B981) else Color(0x33F59E0B),
                border = BorderStroke(
                    1.dp,
                    if (tokens.isLight) {
                        if (backendPathIsFast) Color(0x6610B981) else Color(0x66F59E0B)
                    } else {
                        if (backendPathIsFast) Color(0x4010B981) else Color(0x40F59E0B)
                    }
                ),
                modifier = Modifier.clickable(onClick = onPathClick)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (backendPathIsFast) Color(0xFF10B981) else Color(0xFFF59E0B))
                    )
                    Text(
                        text = backendPathText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textColor,
                        maxLines = 1
                    )
                }
            }
        }

        // Row 2: User Name on Left, Weather badge on Right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassText(
                text = userName,
                type = GlassTextType.Header,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (temp.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = tokens.surfaceColor.copy(alpha = 0.45f),
                    border = BorderStroke(
                        1.dp,
                        if (tokens.isLight) Color(0x33000000) else Color(0x26FFFFFF)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (city.isNotEmpty()) "$city $desc ${temp}°C" else "$desc ${temp}°C",
                            fontSize = 11.sp,
                            color = tokens.textColor,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * 1. OverviewSummaryCard
 * Displays system healthiness, uptime, and core progress metrics (CPU, RAM, Storage, Services).
 */
@Composable
fun OverviewSummaryCard(
    systemStatus: SystemStatus?,
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
    val diskPercent = metrics?.effectiveRecordingsPercent ?: 0.0
    val diskUsedGb = metrics?.effectiveRecordingsUsedGb ?: 0.0
    val diskLimitGb = metrics?.effectiveRecordingsLimitGb ?: 400.0
    val uptimeSeconds = systemStatus?.uptimeSeconds ?: 0L

    val uptimeText = remember(uptimeSeconds) {
        if (uptimeSeconds <= 0) "正在启动..."
        else {
            val days = uptimeSeconds / 86400
            val hours = (uptimeSeconds % 86400) / 3600
            val mins = (uptimeSeconds % 3600) / 60
            if (days > 0) "${days}天 ${hours}小时稳定运行"
            else "${hours}小时 ${mins}分稳定运行"
        }
    }

    val isHealthy = cpuPercent < 85.0 && memPercent < 90.0

    LiquidGlassCard(
        modifier = modifier,
        hazeState = hazeState,
        performanceMode = performanceMode
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Health Title + Uptime Tag
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isHealthy) Color(0xFF10B981) else Color(0xFFF43F5E))
                    )
                    GlassText(
                        text = if (isHealthy) "系统运行正常" else "系统负载过高",
                        type = GlassTextType.Title,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                GlassTag(
                    text = uptimeText,
                    type = GlassTagType.Info
                )
            }

            // Core Metrics: CPU & RAM Gauges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // CPU Metric Column
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(tokens.surfaceColor.copy(alpha = if (tokens.isLight) 0.45f else 0.35f))
                        .border(
                            width = 1.dp,
                            color = if (tokens.isLight) Color(0x33000000) else Color(0x1AFFFFFF),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassText(
                            text = "CPU 负载",
                            type = GlassTextType.Secondary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f, fill = false).padding(end = 4.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = String.format(Locale.getDefault(), "%.1f%%", cpuPercent),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.textColor,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (cpuPercent / 100f).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (cpuPercent > 80.0) Color(0xFFF43F5E) else Color(0xFF10B981),
                        trackColor = tokens.textColor.copy(alpha = 0.12f)
                    )
                }

                // RAM Metric Column
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(tokens.surfaceColor.copy(alpha = if (tokens.isLight) 0.45f else 0.35f))
                        .border(
                            width = 1.dp,
                            color = if (tokens.isLight) Color(0x33000000) else Color(0x1AFFFFFF),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassText(
                            text = "内存负载",
                            type = GlassTextType.Secondary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f, fill = false).padding(end = 4.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = String.format(Locale.getDefault(), "%.1f%%", memPercent),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.textColor,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (memPercent / 100f).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (memPercent > 85.0) Color(0xFFF59E0B) else Color(0xFF3B82F6),
                        trackColor = tokens.textColor.copy(alpha = 0.12f)
                    )
                }
            }

            // Storage Quota Metric Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.surfaceColor.copy(alpha = if (tokens.isLight) 0.45f else 0.35f))
                    .border(
                        width = 1.dp,
                        color = if (tokens.isLight) Color(0x33000000) else Color(0x1AFFFFFF),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassText(
                        text = "录像存储空间配额",
                        type = GlassTextType.Secondary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = String.format(
                            Locale.getDefault(),
                            "%.1f / %.0f GB (%.1f%%)",
                            diskUsedGb,
                            diskLimitGb,
                            diskPercent
                        ),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.textColor,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (diskPercent / 100f).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (diskPercent > 90.0) Color(0xFFF43F5E) else Color(0xFF10B981),
                    trackColor = tokens.textColor.copy(alpha = 0.12f)
                )
            }

            // Connectivity status footer: MQTT status & connected devices
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassTag(
                    text = if (systemStatus?.mqttConnected == true) "MQTT 在线" else "MQTT 离线",
                    type = if (systemStatus?.mqttConnected == true) GlassTagType.Success else GlassTagType.Error
                )

                GlassTag(
                    text = "${systemStatus?.onlineDeviceCount ?: 0} 台设备在线",
                    type = GlassTagType.Info
                )
            }
        }
    }
}

/**
 * 2. QuickActionsRow
 * Row of quick action glass buttons: Ops Center, Users, Security Guard, Automations.
 */
@Composable
fun QuickActionsRow(
    securityGuard: SecurityGuard?,
    isAdmin: Boolean,
    onNavigateOps: () -> Unit,
    onNavigateUsers: () -> Unit,
    onToggleSecurity: () -> Unit,
    onNavigateAutomations: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    performanceMode: HazePerformanceMode = HazePerformanceMode.Adaptive,
) {
    val tokens = GlassTheme.tokens
    val isArmed = securityGuard?.isDisarmed != true
    val guardModeText = when (securityGuard?.mode) {
        "armed_away", "away" -> "离家布防"
        "armed_home", "home" -> "在家守护"
        else -> if (isArmed) "已布防" else "已撤防"
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isAdmin) {
            // Admin Actions: Ops Center, Users, Security Guard
            QuickActionButton(
                iconRes = R.drawable.ic_security_shield,
                title = "运维中心",
                subtitle = "系统与服务",
                onClick = onNavigateOps,
                hazeState = hazeState,
                performanceMode = performanceMode,
                modifier = Modifier.weight(1f)
            )

            QuickActionButton(
                iconRes = R.drawable.ic_admin_users,
                title = "用户权限",
                subtitle = "成员管理",
                onClick = onNavigateUsers,
                hazeState = hazeState,
                performanceMode = performanceMode,
                modifier = Modifier.weight(1f)
            )

            QuickActionButton(
                iconRes = R.drawable.ic_security_shield,
                title = "安防布防",
                subtitle = guardModeText,
                active = isArmed,
                onClick = onToggleSecurity,
                hazeState = hazeState,
                performanceMode = performanceMode,
                modifier = Modifier.weight(1f)
            )
        } else {
            // Regular User: Read-only Security Guard monitor (no permission to arm/disarm)
            LiquidGlassCard(
                modifier = Modifier.fillMaxWidth(),
                hazeState = hazeState,
                performanceMode = performanceMode,
                onClick = onToggleSecurity
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isArmed) Color(0x2610B981) else Color(0x1F64748B)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_security_shield),
                                contentDescription = null,
                                tint = if (isArmed) Color(0xFF10B981) else tokens.textSecondaryColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                GlassText(
                                    text = "家庭安防布控",
                                    type = GlassTextType.Body,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                GlassTag(
                                    text = "只读监控",
                                    type = GlassTagType.Neutral
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            GlassText(
                                text = "当前状态: $guardModeText (管理员权限)",
                                type = GlassTextType.Caption,
                                color = if (isArmed) Color(0xFF10B981) else tokens.textSecondaryColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Read-only status pill (compact, single line, no vertical stretch)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isArmed) Color(0x1A10B981) else Color(0x1A64748B),
                        border = BorderStroke(
                            1.dp,
                            if (isArmed) {
                                if (tokens.isLight) Color(0x6610B981) else Color(0x4D10B981)
                            } else {
                                if (tokens.isLight) Color(0x4D64748B) else Color(0x3364748B)
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isArmed) Color(0xFF10B981) else Color(0xFF94A3B8))
                            )
                            Text(
                                text = guardModeText,
                                color = if (isArmed) Color(0xFF10B981) else tokens.textColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single tactile quick action button with liquid glass squish feedback.
 */
@Composable
fun QuickActionButton(
    iconRes: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    hazeState: HazeState? = null,
    performanceMode: HazePerformanceMode = HazePerformanceMode.Adaptive,
) {
    val tokens = GlassTheme.tokens

    LiquidGlassCard(
        modifier = modifier.height(98.dp),
        hazeState = hazeState,
        performanceMode = performanceMode,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (active) Color(0x3310B981)
                        else tokens.surfaceColor.copy(alpha = if (tokens.isLight) 0.60f else 0.5f)
                    )
                    .border(
                        width = 1.dp,
                        color = if (active) {
                            if (tokens.isLight) Color(0x6610B981) else Color(0x4D10B981)
                        } else {
                            if (tokens.isLight) Color(0x33000000) else Color(0x1AFFFFFF)
                        },
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = title,
                    tint = if (active) Color(0xFF10B981) else tokens.textColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = tokens.textSecondaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 3. CameraFeedCard
 * Realtime swipeable camera preview card showcasing camera online counts,
 * horizontal pager to smoothly swipe across all live cameras, and quick jump to live broadcast.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CameraFeedCard(
    cameras: List<Camera>,
    alerts: List<Alert>,
    baseUrl: String?,
    token: String?,
    onCardClick: () -> Unit,
    onCameraClick: (Camera) -> Unit = {},
    onMultiCameraClick: () -> Unit = onCardClick,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    performanceMode: HazePerformanceMode = HazePerformanceMode.Adaptive,
) {
    val tokens = GlassTheme.tokens
    val preset by ThemeManager.themePresetFlow.collectAsState()
    val isDark = preset.isDark
    val onlineCount = cameras.count { it.isOnline }
    val recentAlertCount = alerts.size

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
            // Header: Section title + online badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(end = 6.dp)
                        .clickable(onClick = onCardClick),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_camera),
                        contentDescription = "Cameras",
                        tint = tokens.textColor,
                        modifier = Modifier.size(20.dp)
                    )
                    GlassText(
                        text = "实时监控中心",
                        type = GlassTextType.Title,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GlassTag(
                        text = "${onlineCount}/${cameras.size} 在线",
                        type = if (onlineCount > 0) GlassTagType.Success else GlassTagType.Error
                    )

                    if (recentAlertCount > 0) {
                        GlassTag(
                            text = "${recentAlertCount} 告警",
                            type = GlassTagType.Warning
                        )
                    }
                }
            }

            if (cameras.isEmpty()) {
                // Empty state card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isDark) Color(0xFF0F172A) else Color(0x1A000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_camera),
                            contentDescription = null,
                            tint = tokens.textSecondaryColor.copy(alpha = 0.5f),
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "暂无接入摄像头",
                            color = tokens.textSecondaryColor,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                // Infinite Loop HorizontalPager for swipeable camera cards
                val actualCount = cameras.size
                val isLoop = actualCount > 1
                val virtualPageCount = if (isLoop) actualCount * 10000 else actualCount
                val initialPage = if (isLoop) (virtualPageCount / 2) - ((virtualPageCount / 2) % actualCount) else 0
                val pagerState = key(actualCount) {
                    rememberPagerState(initialPage = initialPage, pageCount = { virtualPageCount })
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(175.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0F172A))
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        val actualIndex = if (isLoop) ((page % actualCount) + actualCount) % actualCount else page
                        val currentCamera = cameras.getOrNull(actualIndex) ?: return@HorizontalPager
                        val context = LocalContext.current
                        val density = LocalDensity.current
                        val thumbWidthPx = remember(density) { with(density) { 360.dp.roundToPx() } }
                        val thumbHeightPx = remember(density) { with(density) { 175.dp.roundToPx() } }

                        val previewUrl = remember(currentCamera.id, baseUrl) {
                            if (!baseUrl.isNullOrBlank()) {
                                "${baseUrl.trimEnd('/')}/api/v1/cameras/${currentCamera.id}/frame?quality=35&width=640"
                            } else null
                        }

                        val imageRequest = remember(previewUrl, token, thumbWidthPx, thumbHeightPx) {
                            if (previewUrl == null) null
                            else {
                                ImageRequest.Builder(context)
                                    .data(previewUrl)
                                    .apply {
                                        if (!token.isNullOrEmpty()) {
                                            addHeader("Authorization", "Bearer $token")
                                        }
                                    }
                                    .size(thumbWidthPx, thumbHeightPx)
                                    .crossfade(200)
                                    .memoryCachePolicy(CachePolicy.ENABLED)
                                    .build()
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { onCameraClick(currentCamera) }
                        ) {
                            if (imageRequest != null) {
                                SubcomposeAsyncImage(
                                    model = imageRequest,
                                    contentDescription = currentCamera.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                    loading = {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(
                                                color = Color(0xFFFFC38B),
                                                modifier = Modifier.size(28.dp),
                                                strokeWidth = 2.dp
                                            )
                                        }
                                    },
                                    error = {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    painter = painterResource(id = R.drawable.ic_camera),
                                                    contentDescription = null,
                                                    tint = Color.White.copy(alpha = 0.5f),
                                                    modifier = Modifier.size(36.dp)
                                                )
                                                Text(
                                                    text = currentCamera.name,
                                                    color = Color.White.copy(alpha = 0.8f),
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = if (currentCamera.isOnline) "轻触进入实时直播" else "摄像头离线",
                                                    color = Color.White.copy(alpha = 0.6f),
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                    }
                                )
                            }

                            // Top overlay: Index indicator if multi cameras
                            if (actualCount > 1) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0x80000000),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = "${actualIndex + 1}/$actualCount",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            // Bottom overlay gradient with Camera name & Fullscreen inspection pill
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color.Transparent, Color(0xE6000000))
                                        )
                                    )
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(if (currentCamera.isOnline) Color(0xFF10B981) else Color(0xFFEF4444))
                                            )
                                            Text(
                                                text = currentCamera.name,
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            text = "左右滑动切换 · 点击全屏巡检",
                                            color = Color.White.copy(alpha = 0.7f),
                                            fontSize = 11.sp
                                        )
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0x4DFFFFFF),
                                        border = BorderStroke(1.dp, Color(0x66FFFFFF)),
                                        modifier = Modifier.clickable { onCameraClick(currentCamera) }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_fullscreen),
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(
                                                text = "全屏巡检",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Pager dots indicator
                if (actualCount > 1) {
                    val currentActualIndex = if (isLoop) ((pagerState.currentPage % actualCount) + actualCount) % actualCount else pagerState.currentPage
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        cameras.indices.forEach { index ->
                            val isSelected = currentActualIndex == index
                            val dotWidth by animateDpAsState(
                                targetValue = if (isSelected) 16.dp else 4.dp,
                                animationSpec = tween(durationMillis = 250),
                                label = "pagerDotWidth"
                            )
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .height(4.dp)
                                    .width(dotWidth)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) (if (isDark) Color(0xFFFFC38B) else Color(0xFFD7A064))
                                        else tokens.textColor.copy(alpha = 0.25f)
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 4. RecentEventsCard
 * Displays recent system snapshots, AI alerts, and automation triggers.
 */
@Composable
fun RecentEventsCard(
    alerts: List<Alert>,
    recentLogs: List<SystemLog>,
    baseUrl: String?,
    token: String?,
    onAlertClick: (Alert) -> Unit,
    onViewAllAlerts: () -> Unit,
    onViewAllLogs: () -> Unit,
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
            // Header Row: Title & "View All" action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_history),
                        contentDescription = "Recent Events",
                        tint = tokens.textColor,
                        modifier = Modifier.size(18.dp)
                    )
                    GlassText(
                        text = "近期动态与告警抓拍",
                        type = GlassTextType.Title,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val viewAllColor = if (tokens.isLight) Color(0xFF0284C7) else Color(0xFF38BDF8)
                val viewAllBg = if (tokens.isLight) Color(0x140284C7) else Color(0x2638BDF8)
                val viewAllBorder = if (tokens.isLight) Color(0x590284C7) else Color(0x4038BDF8)

                Surface(
                    onClick = onViewAllAlerts,
                    shape = RoundedCornerShape(8.dp),
                    color = viewAllBg,
                    border = BorderStroke(1.dp, viewAllBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "查看全部",
                            color = viewAllColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            painter = painterResource(id = R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = viewAllColor,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            if (alerts.isEmpty() && recentLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    GlassText(
                        text = "暂无近期抓拍与自动化事件",
                        type = GlassTextType.Secondary,
                        fontSize = 13.sp
                    )
                }
            } else {
                // Stream list of recent alerts
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    alerts.take(4).forEach { alert ->
                        RecentAlertItemRow(
                            alert = alert,
                            baseUrl = baseUrl,
                            token = token,
                            onClick = { onAlertClick(alert) }
                        )
                    }

                    // Display system logs if available
                    recentLogs.take(2).forEach { log ->
                        RecentLogItemRow(
                            log = log,
                            onClick = onViewAllLogs
                        )
                    }
                }
            }
        }
    }
}

/**
 * Row representing a single alert event with thumbnail and detection label.
 */
@Composable
private fun RecentAlertItemRow(
    alert: Alert,
    baseUrl: String?,
    token: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = GlassTheme.tokens
    val context = LocalContext.current
    val density = LocalDensity.current

    val thumbWidthPx = remember(density) { with(density) { 72.dp.roundToPx() } }
    val thumbHeightPx = remember(density) { with(density) { 46.dp.roundToPx() } }

    val imageModel = remember(alert.id, alert.thumbnail, baseUrl) {
        AlertThumbnailResolver.resolveModel(
            thumbnailBase64 = alert.thumbnail,
            alertId = alert.id,
            baseUrl = baseUrl
        )
    }

    val imageRequest = remember(imageModel, token, thumbWidthPx, thumbHeightPx) {
        if (imageModel == null) null
        else {
            ImageRequest.Builder(context)
                .data(imageModel)
                .apply {
                    if (imageModel is String && imageModel.startsWith("http") && !token.isNullOrEmpty()) {
                        addHeader("Authorization", "Bearer $token")
                    }
                }
                .size(thumbWidthPx, thumbHeightPx)
                .crossfade(180)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .build()
        }
    }

    val tagText = when (alert.label) {
        "person" -> "人形检测"
        "car" -> "车辆抓拍"
        "dog", "cat" -> "宠物识别"
        "fall" -> "摔倒告警"
        else -> alert.label.ifEmpty { "AI抓拍" }
    }

    val tagType = when (alert.label) {
        "person" -> GlassTagType.Info
        "car" -> GlassTagType.Warning
        "fall" -> GlassTagType.Error
        else -> GlassTagType.Neutral
    }

    val timeFormatted = remember(alert.startTime) {
        try {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            sdf.format(Date((alert.startTime * 1000).toLong()))
        } catch (_: Exception) {
            ""
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.surfaceColor.copy(alpha = if (tokens.isLight) 0.45f else 0.25f))
            .border(
                width = 1.dp,
                color = if (tokens.isLight) Color(0x33000000) else Color(0x1AFFFFFF),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail Snapshot
        Box(
            modifier = Modifier
                .size(width = 72.dp, height = 46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E293B)),
            contentAlignment = Alignment.Center
        ) {
            if (imageRequest != null) {
                SubcomposeAsyncImage(
                    model = imageRequest,
                    contentDescription = "Alert Snapshot",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                color = Color(0xFFFFC38B),
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 1.5.dp
                            )
                        }
                    },
                    error = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_camera),
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.ic_camera),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Details
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                GlassTag(text = tagText, type = tagType)
                Text(
                    text = alert.cameraName.ifEmpty { "监控镜头" },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = tokens.textColor,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = timeFormatted,
                fontSize = 11.sp,
                color = tokens.textSecondaryColor
            )
        }

        // Arrow indicator
        Icon(
            painter = painterResource(id = R.drawable.ic_arrow_down),
            contentDescription = "Details",
            tint = tokens.textSecondaryColor,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * Row representing a system log / automation log entry.
 */
@Composable
private fun RecentLogItemRow(
    log: SystemLog,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = GlassTheme.tokens
    val isWarnOrError = log.level.equals("critical", ignoreCase = true) || log.level.equals("warning", ignoreCase = true)

    val timeFormatted = remember(log.ts) {
        try {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            sdf.format(Date(log.ts * 1000))
        } catch (_: Exception) {
            ""
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.surfaceColor.copy(alpha = if (tokens.isLight) 0.40f else 0.20f))
            .border(
                width = 1.dp,
                color = if (tokens.isLight) Color(0x33000000) else Color(0x1AFFFFFF),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (isWarnOrError) Color(0xFFF59E0B) else Color(0xFF10B981))
            )
            Text(
                text = "[${log.source}] ${log.message}",
                fontSize = 12.sp,
                color = tokens.textColor,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = timeFormatted,
            fontSize = 11.sp,
            color = tokens.textSecondaryColor,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
