package com.homedatacenter.app.ui.admin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.User
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

/**
 * UsersPageContent — Pure Compose Liquid Glass User Management Screen.
 *
 * Implements Stage B requirements:
 * 1. UserOverviewCard: User statistics and prominent "添加用户" action button.
 * 2. UserCard: Frosted glass initial avatar, Admin/Viewer role badge, status, edit & toggle callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsersPageContent(
    users: List<User>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onAddUserClick: () -> Unit,
    onEditUserClick: (User) -> Unit,
    onDeleteUserClick: (User) -> Unit,
    onToggleAdminClick: ((User, Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val preset by ThemeManager.themePresetFlow.collectAsState()
    val isDark = preset.isDark
    val tokens = preset.tokens

    val colorTween = tween<Color>(durationMillis = 400)
    val animPrimary by animateColorAsState(tokens.accentColor, colorTween, label = "users_primary")
    val animSurface by animateColorAsState(tokens.cardBg, colorTween, label = "users_surface")
    val animOnSurface by animateColorAsState(tokens.textColor, colorTween, label = "users_on_surface")
    val animBackground by animateColorAsState(preset.fluidBaseColor, colorTween, label = "users_bg")
    val animOnBackground by animateColorAsState(tokens.textSecondaryColor, colorTween, label = "users_on_bg")

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
                // Layer 0: Ambient Fluid Background
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
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Header Section
                        item(key = "users_header") {
                            UsersHeaderSection(
                                userCount = users.size
                            )
                        }

                        // 1. UserOverviewCard (Statistics & Add User Action)
                        item(key = "user_overview_card") {
                            UserOverviewCard(
                                totalUsers = users.size,
                                adminCount = users.count { it.isAdmin },
                                onAddUserClick = onAddUserClick,
                                hazeState = hazeState,
                                performanceMode = performanceMode,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            )
                        }

                        // 2. User Cards Section
                        if (users.isEmpty()) {
                            item(key = "users_empty") {
                                EmptyUsersCard(
                                    hazeState = hazeState,
                                    performanceMode = performanceMode,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                )
                            }
                        } else {
                            items(
                                items = users,
                                key = { it.id }
                            ) { user ->
                                UserCard(
                                    user = user,
                                    onEditUser = { onEditUserClick(user) },
                                    onDeleteUser = { onDeleteUserClick(user) },
                                    onToggleAdmin = { newAdminState ->
                                        onToggleAdminClick?.invoke(user, newAdminState)
                                    },
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

/**
 * Header Section for Users screen.
 */
@Composable
private fun UsersHeaderSection(
    userCount: Int,
    modifier: Modifier = Modifier
) {
    val tokens = GlassTheme.tokens

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val tokens = GlassTheme.tokens
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tokens.accentBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_admin_users),
                    contentDescription = "Users",
                    tint = tokens.accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column {
                GlassText(
                    text = "用户管理中心",
                    type = GlassTextType.Header,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                GlassText(
                    text = "系统成员与权限安全配置",
                    type = GlassTextType.Secondary,
                    fontSize = 11.sp
                )
            }
        }

        GlassTag(
            text = "共 $userCount 位成员",
            type = GlassTagType.Info
        )
    }
}

/**
 * 1. UserOverviewCard
 * Displays user counts (total, admins, viewers) and prominent "添加用户" action button.
 */
@Composable
fun UserOverviewCard(
    totalUsers: Int,
    adminCount: Int,
    onAddUserClick: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    performanceMode: HazePerformanceMode = HazePerformanceMode.Adaptive,
) {
    val tokens = GlassTheme.tokens
    val viewersCount = (totalUsers - adminCount).coerceAtLeast(0)

    LiquidGlassCard(
        modifier = modifier,
        hazeState = hazeState,
        performanceMode = performanceMode
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassText(
                    text = "成员与权限总览",
                    type = GlassTextType.Title,
                    fontWeight = FontWeight.Bold
                )

                Surface(
                    onClick = onAddUserClick,
                    shape = RoundedCornerShape(12.dp),
                    color = tokens.controlBg,
                    border = BorderStroke(1.dp, tokens.cardBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "添加用户",
                            tint = tokens.accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "添加用户",
                            color = tokens.accentColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Summary Stats Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Total Users
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(tokens.controlBg)
                        .padding(12.dp)
                ) {
                    GlassText(text = "系统总成员", type = GlassTextType.Secondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$totalUsers",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.textColor
                    )
                }

                // Admins
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(tokens.controlBg)
                        .padding(12.dp)
                ) {
                    GlassText(text = "超级管理员", type = GlassTextType.Secondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$adminCount",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.statusOnline
                    )
                }

                // Standard Viewers
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(tokens.controlBg)
                        .padding(12.dp)
                ) {
                    GlassText(text = "普通访客", type = GlassTextType.Secondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$viewersCount",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.textColor
                    )
                }
            }
        }
    }
}

/**
 * 2. UserCard
 * Individual user card with frosted glass avatar, role badges, metadata, and edit/toggle actions.
 */
@Composable
fun UserCard(
    user: User,
    onEditUser: () -> Unit,
    onDeleteUser: () -> Unit,
    onToggleAdmin: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    performanceMode: HazePerformanceMode = HazePerformanceMode.Adaptive,
) {
    val tokens = GlassTheme.tokens
    val initial = remember(user.name) {
        if (user.name.isNotEmpty()) user.name.take(1).uppercase() else "U"
    }

    LiquidGlassCard(
        modifier = modifier,
        hazeState = hazeState,
        performanceMode = performanceMode,
        onClick = onEditUser
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Frosted Glass Initial Avatar (首字母玻璃质感头像)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (user.isAdmin) Color(0x3310B981)
                        else tokens.surfaceColor.copy(alpha = 0.5f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (user.isAdmin) Color(0xFF10B981) else tokens.textColor
                )
            }

            // User Info (Name, Role Badge, ID / Registration Date)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = user.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    GlassTag(
                        text = if (user.isAdmin) "管理员" else "访客",
                        type = if (user.isAdmin) GlassTagType.Info else GlassTagType.Neutral,
                        modifier = if (onToggleAdmin != null) {
                            Modifier.clickable { onToggleAdmin(!user.isAdmin) }
                        } else Modifier
                    )
                }

                val regDate = remember(user.createdAt) {
                    if (user.createdAt.isNullOrEmpty()) ""
                    else user.createdAt.substringBefore(" ").substringBefore("T").take(10)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (regDate.isNotEmpty()) "ID: ${user.id} · 注册: $regDate" else "ID: ${user.id}",
                        fontSize = 11.sp,
                        color = tokens.textSecondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Action Buttons: Edit / Delete
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = tokens.controlBg,
                    border = BorderStroke(1.dp, tokens.cardBorder),
                    modifier = Modifier.clickable(onClick = onEditUser)
                ) {
                    Text(
                        text = "编辑",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.textColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = tokens.statusDanger.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, tokens.statusDanger.copy(alpha = 0.35f)),
                    modifier = Modifier.clickable(onClick = onDeleteUser)
                ) {
                    Text(
                        text = "删除",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.statusDanger,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

/**
 * Placeholder card displayed when user list is empty.
 */
@Composable
private fun EmptyUsersCard(
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_admin_users),
                    contentDescription = null,
                    tint = tokens.textColor.copy(alpha = 0.4f),
                    modifier = Modifier.size(36.dp)
                )
                GlassText(
                    text = "暂无其他用户成员",
                    type = GlassTextType.Secondary,
                    fontSize = 13.sp
                )
            }
        }
    }
}
