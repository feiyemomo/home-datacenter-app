package com.homedatacenter.app.ui.cameras

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.Alert
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.ui.glass.AmbientFluidBackground
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.LiquidGlassCard
import com.homedatacenter.app.ui.glass.ProvideGlassTheme
import com.homedatacenter.app.util.ThemeManager
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * CamerasPageContent — Complete Stage A Refactored Compose Screen.
 *
 * Architecture & Visual Hierarchy:
 * 1. Root container [Box] hosts bottom layer [AmbientFluidBackground] with [hazeSource].
 * 2. Top layer hosts [PullToRefreshBox] and single high-performance [LazyColumn].
 * 3. All [LiquidGlassCard] instances consume [rememberHazeState] with dynamic [HazeSampling] (Path B).
 * 4. Content bottom padding is set to 120.dp to completely clear floating navigation bar.
 * 5. All colors and styles are strictly driven by [GlassTheme.tokens] and [ProvideGlassTheme].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CamerasPageContent(
    cameras: List<Camera>,
    alerts: List<Alert>,
    alertsLoading: Boolean,
    alertsHasMore: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    filterState: AlertFilterState,
    onUpdateFilters: (cameraId: Long?, label: String?, days: Int) -> Unit,
    scrollToAlertsFlow: Flow<Unit>,
    isAdmin: Boolean,
    baseUrl: String?,
    token: String?,
    columnCount: Int,
    onCameraClick: (Camera) -> Unit,
    onMultiCameraClick: () -> Unit,
    onRegisterCameraClick: () -> Unit,
    onFilterCameraClick: () -> Unit,
    onFilterDateClick: () -> Unit,
    onFilterLabelClick: () -> Unit,
    onLoadMoreAlerts: () -> Unit,
    onSnapshotClick: (Alert) -> Unit,
    onJumpCamera: (Alert) -> Unit,
    onRefreshClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preset by ThemeManager.themePresetFlow.collectAsState()
    val isDark = preset.isDark

    // Smooth in-place Material 3 ColorScheme animation (tween 400ms, zero tree recreation)
    val colorTween = tween<Color>(durationMillis = 400)
    val animPrimary by animateColorAsState(Color(0xFFD7A064), colorTween, label = "m3_primary")
    val animSurface by animateColorAsState(if (isDark) Color(0xFF1E1B24) else Color(0xFFFAF7F2), colorTween, label = "m3_surface")
    val animOnSurface by animateColorAsState(if (isDark) Color(0xFFEAE2D8) else Color(0xFF3D3530), colorTween, label = "m3_on_surface")
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

    // Single unified theme provider at screen root
    MaterialTheme(colorScheme = m3ColorScheme) {
        ProvideGlassTheme(preset = preset) {
            val tokens = GlassTheme.tokens
            val hazeState = rememberHazeState()
            val lazyListState = rememberLazyListState()
            val pullRefreshState = rememberPullToRefreshState()

            // Path B: Scrolling degradation strategy
            // Fast scrolling uses downsampled quality fraction (0.4f) to preserve framerate;
            // Full Adaptive mode restores when scroll settles.
            val performanceMode = if (lazyListState.isScrollInProgress) {
                remember { HazePerformanceMode.Fixed(0.4f) }
            } else {
                HazePerformanceMode.Adaptive
            }

            // Reactive load-more pagination
            val shouldLoadMore = remember {
                derivedStateOf {
                    val total = lazyListState.layoutInfo.totalItemsCount
                    val lastVisible = lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    total > 0 && lastVisible >= total - 3
                }
            }

            LaunchedEffect(shouldLoadMore.value) {
                if (shouldLoadMore.value && alertsHasMore && !alertsLoading) {
                    onLoadMoreAlerts()
                }
            }

            // Reactive scroll-to-alerts event from Dashboard via Channel/Flow
            LaunchedEffect(scrollToAlertsFlow, cameras.size) {
                scrollToAlertsFlow.collectLatest {
                    val cameraRowCount = if (cameras.isEmpty()) 1 else {
                        if (columnCount > 1) (cameras.size + columnCount - 1) / columnCount else cameras.size
                    }
                    val alertsHeaderIndex = 1 + cameraRowCount
                    lazyListState.animateScrollToItem(alertsHeaderIndex)
                }
            }

            // Reactive filter titles
            val filterCameraTitle = remember(filterState.cameraId, cameras) {
                if (filterState.cameraId == null) "全部"
                else cameras.firstOrNull { it.id == filterState.cameraId }?.name ?: "已选"
            }

            val filterDateTitle = remember(filterState.days) {
                when (filterState.days) {
                    1 -> "今天"
                    3 -> "近 3 天"
                    7 -> "近 7 天"
                    else -> "全部"
                }
            }

            val filterLabelTitle = remember(filterState.label) {
                when (filterState.label) {
                    "person" -> "人形"
                    "car" -> "车辆"
                    "dog,cat" -> "宠物"
                    "fall" -> "摔倒"
                    null -> "全部"
                    else -> filterState.label
                }
            }

            // Layered Root Container: Fluid Background underneath, LazyColumn on top
            Box(modifier = modifier.fillMaxSize()) {
                // Layer 0: Ambient Fluid Mesh Background with hazeSource
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
                        // Dynamic bottom padding guaranteeing full clearance above floating navigation bar
                        contentPadding = PaddingValues(top = statusBarTop + 12.dp, bottom = navBarBottom + 150.dp),
                    ) {
                        // Section 1: Header
                        item(key = "header_top", contentType = "header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "摄像头监控",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = tokens.textColor,
                                )

                                Spacer(modifier = Modifier.weight(1f))

                                IconButton(
                                    onClick = onMultiCameraClick,
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_grid_view),
                                        contentDescription = "多路预览",
                                        tint = tokens.textColor.copy(alpha = 0.9f),
                                        modifier = Modifier.size(24.dp),
                                    )
                                }

                                if (isAdmin) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        onClick = onRegisterCameraClick,
                                        shape = CircleShape,
                                        color = Color(0x3338BDF8),
                                        border = androidx.compose.foundation.BorderStroke(1.2.dp, Color(0x8038BDF8)),
                                        modifier = Modifier.size(40.dp),
                                        shadowElevation = 0.dp,
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_add),
                                                contentDescription = "注册摄像头",
                                                tint = Color(0xFF38BDF8),
                                                modifier = Modifier.size(22.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Section 2: Camera List / Empty Card
                        if (cameras.isEmpty()) {
                            item(key = "cameras_empty", contentType = "empty_camera") {
                                CameraEmptyCard(
                                    isAdmin = isAdmin,
                                    onRegister = onRegisterCameraClick,
                                    onRefresh = onRefreshClick,
                                    hazeState = hazeState,
                                    performanceMode = performanceMode,
                                )
                            }
                        } else if (columnCount > 1) {
                            val chunked = cameras.chunked(columnCount)
                            items(
                                items = chunked,
                                key = { chunk -> chunk.joinToString("_") { it.id.toString() } },
                                contentType = { "camera_row" },
                            ) { rowCameras ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    for (cam in rowCameras) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            CameraCard(
                                                camera = cam,
                                                baseUrl = baseUrl,
                                                token = token,
                                                hazeState = hazeState,
                                                performanceMode = performanceMode,
                                                onClick = { onCameraClick(cam) },
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            items(
                                items = cameras,
                                key = { "cam_${it.id}" },
                                contentType = { "camera_card" },
                            ) { camera ->
                                CameraCard(
                                    camera = camera,
                                    baseUrl = baseUrl,
                                    token = token,
                                    hazeState = hazeState,
                                    performanceMode = performanceMode,
                                    onClick = { onCameraClick(camera) },
                                )
                            }
                        }

                        // Section 3: Alerts Title & Filters
                        item(key = "alerts_header", contentType = "alerts_header") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp, bottom = 6.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "全部报警",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = tokens.textColor,
                                    )

                                    Spacer(modifier = Modifier.weight(1f))

                                    AnimatedVisibility(
                                        visible = filterState.hasActiveFilter,
                                        enter = fadeIn(),
                                        exit = fadeOut()
                                    ) {
                                        Text(
                                            text = "重置筛选",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF38BDF8),
                                            modifier = Modifier
                                                .clickable { onUpdateFilters(null, null, 0) }
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    AlertFilterPill(
                                        label = "来源: $filterCameraTitle ▾",
                                        isSelected = filterState.cameraId != null,
                                        onClick = onFilterCameraClick,
                                    )

                                    AlertFilterPill(
                                        label = "日期: $filterDateTitle ▾",
                                        isSelected = filterState.days != 0,
                                        onClick = onFilterDateClick,
                                    )

                                    AlertFilterPill(
                                        label = "目标: $filterLabelTitle ▾",
                                        isSelected = filterState.label != null,
                                        onClick = onFilterLabelClick,
                                    )
                                }
                            }
                        }

                        // Section 4: Alert Items
                        if (alerts.isEmpty()) {
                            item(key = "alerts_empty", contentType = "alerts_empty") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 36.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "暂无报警事件",
                                        color = tokens.textSecondaryColor,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        } else {
                            items(
                                items = alerts,
                                key = { "alert_${it.id}" },
                                contentType = { "alert_card" },
                            ) { alert ->
                                AlertCard(
                                    alert = alert,
                                    baseUrl = baseUrl,
                                    token = token,
                                    hazeState = hazeState,
                                    performanceMode = performanceMode,
                                    onSnapshotClick = onSnapshotClick,
                                    onJumpCamera = onJumpCamera,
                                )
                            }
                        }

                        // Section 5: Pagination Loading Footer
                        if (alertsLoading) {
                            item(key = "alerts_loading_more", contentType = "loading_indicator") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = tokens.innerGlowColor,
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        }

                        // Extra bottom clearance footer ensuring absolute clearance above floating navigation bar
                        item(key = "bottom_clearance_spacer") {
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertFilterPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens

    val bgColor = if (isSelected) tokens.accentBg else tokens.controlBg
    val contentColor = if (isSelected) tokens.accentColor else tokens.textColor
    val borderColor = if (isSelected) tokens.accentColor else tokens.cardBorder

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = bgColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = modifier
    ) {
        Text(
            text = label,
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun CameraEmptyCard(
    isAdmin: Boolean,
    onRegister: () -> Unit,
    onRefresh: () -> Unit,
    hazeState: HazeState?,
    performanceMode: HazePerformanceMode,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val title = if (isAdmin) "暂未添加摄像头" else "暂无可用摄像头"
    val subtitle = if (isAdmin) {
        "当前系统尚未接入任何监控摄像头，您可以点击上方「+」或下方按钮注册新摄像头。"
    } else {
        "您当前是普通成员账号，管理员尚未为您分配或分享任何摄像头。\n请联系系统管理员为您分配访问权限。"
    }
    val actionText = if (isAdmin) "注册摄像头" else "刷新重试"

    LiquidGlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        hazeState = hazeState,
        tokens = tokens,
        performanceMode = performanceMode,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_camera),
                contentDescription = null,
                tint = tokens.innerGlowColor.copy(alpha = 0.9f),
                modifier = Modifier.size(48.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = tokens.surfaceColor.copy(alpha = 0.95f),
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = tokens.surfaceColor.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                onClick = if (isAdmin) onRegister else onRefresh,
                shape = RoundedCornerShape(18.dp),
                color = Color(0x3338BDF8),
                border = androidx.compose.foundation.BorderStroke(1.2.dp, Color(0x8038BDF8)),
                shadowElevation = 0.dp,
            ) {
                Text(
                    text = actionText,
                    color = Color(0xFFE0F2FE),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}
