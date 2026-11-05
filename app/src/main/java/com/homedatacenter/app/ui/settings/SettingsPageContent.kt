package com.homedatacenter.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homedatacenter.app.R
import com.homedatacenter.app.ui.components.GlassActionButton
import com.homedatacenter.app.ui.components.GlassProgressIndicator
import com.homedatacenter.app.ui.components.GlassSwitch
import com.homedatacenter.app.ui.components.GlassTag
import com.homedatacenter.app.ui.components.GlassTagType
import com.homedatacenter.app.ui.components.GlassThemeSelector
import com.homedatacenter.app.ui.glass.AppThemePreset
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.LiquidGlassCard
import dev.chrisbanes.haze.HazeState

/**
 * SettingsMenuSection — Functional category keys for clean accordion collapsing.
 */
enum class SettingsMenuSection {
    Appearance,      // 色彩风格与流体拟态
    Notifications,   // 消息与安防通知
    Connectivity,    // 局域网接入
    AdminServices,   // 高级运维中枢
    AboutUpdates,    // 版本与升级
}

/**
 * SettingsUiState — Comprehensive reactive state for the Settings tab.
 */
data class SettingsUiState(
    val userName: String = "User",
    val isAdmin: Boolean = false,
    val userId: String = "-",
    val deviceId: String = "-",
    val tokenIssued: String = "-",
    val tokenExpires: String = "-",
    val tokenRemainingDays: Long? = null,
    val isTokenExpired: Boolean = false,
    val isSessionDetailsExpanded: Boolean = false,

    val currentPreset: AppThemePreset = AppThemePreset.WarmLight,
    val followSystemDark: Boolean = true,

    val notificationsEnabled: Boolean = true,
    val notifyPerson: Boolean = true,
    val notifyMotion: Boolean = false,
    val notifySystem: Boolean = false,
    val notifyIncludeSnapshot: Boolean = true,
    val notifyDndEnabled: Boolean = false,
    val dndSummaryText: String = "时段: 23:00 ~ 07:00 (点击修改)",
    val keepAliveEnabled: Boolean = false,
    val isBatteryOptimized: Boolean = false,

    val lanHost: String = "",

    val aiSourceMode: String = "摄像头自带AI (默认)",
    val motionFallback: Boolean = false,

    val appVersion: String = "v1.17.1",
    val updateTrack: String = "release",
    val updateStatusText: String = "已是最新版本",
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
)

/**
 * SettingsActions — Event callbacks emitted to the host fragment.
 */
data class SettingsActions(
    val onPresetChange: (AppThemePreset) -> Unit = {},
    val onFollowSystemDarkChange: (Boolean) -> Unit = {},
    val onToggleSessionDetails: () -> Unit = {},
    val onRefreshToken: () -> Unit = {},
    val onLogoutClick: () -> Unit = {},

    val onNotificationsMasterChange: (Boolean) -> Unit = {},
    val onNotifyPersonChange: (Boolean) -> Unit = {},
    val onNotifyMotionChange: (Boolean) -> Unit = {},
    val onNotifySystemChange: (Boolean) -> Unit = {},
    val onNotifySnapshotChange: (Boolean) -> Unit = {},
    val onNotifyDndChange: (Boolean) -> Unit = {},
    val onDndTimeClick: () -> Unit = {},
    val onKeepAliveChange: (Boolean) -> Unit = {},
    val onBatteryOptClick: () -> Unit = {},

    val onLanHostSave: (String) -> Unit = {},
    val onLanHostReset: () -> Unit = {},

    val onNavAutomations: () -> Unit = {},
    val onNavSecurityDefense: () -> Unit = {},
    val onNavUsers: () -> Unit = {},
    val onNavFamilyFaces: () -> Unit = {},
    val onStorageConfigClick: () -> Unit = {},
    val onAiSourceConfigClick: () -> Unit = {},
    val onMotionFallbackChange: (Boolean) -> Unit = {},

    val onUpdateTrackChange: (String) -> Unit = {},
    val onCheckUpdateClick: () -> Unit = {},
)

/**
 * SettingsPageContent — Liquid glassmorphism Settings hub with category accordion architecture.
 */
@Composable
fun SettingsPageContent(
    state: SettingsUiState,
    actions: SettingsActions,
    bottomPadding: Dp = 96.dp,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens

    // Manages multi-section expansion state; default expands Appearance for instant customization
    var expandedSections by remember {
        mutableStateOf(setOf(SettingsMenuSection.Appearance))
    }

    fun toggleSection(section: SettingsMenuSection) {
        expandedSections = if (expandedSections.contains(section)) {
            expandedSections - section
        } else {
            expandedSections + section
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = bottomPadding + 32.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Header Banner
        item(key = "header") {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(
                    text = "系统设置",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.textColor
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "个人中枢 · 模块分类收纳 · 毫秒级流体响应",
                    fontSize = 12.sp,
                    color = tokens.textSecondaryColor
                )
            }
        }

        // 2. User & Session Card (Persistent Primary Hub)
        item(key = "user_profile") {
            UserProfileCard(
                state = state,
                onToggleDetails = actions.onToggleSessionDetails,
                onRefreshToken = actions.onRefreshToken,
                onLogout = actions.onLogoutClick,
                hazeState = hazeState
            )
        }

        // 3. Category 1: Appearance & Theme
        item(key = "category_appearance") {
            SettingsCategoryCard(
                title = "色彩风格与流体拟态",
                subtitle = "当前预设: ${state.currentPreset.displayName} · 4 套高光流体",
                iconRes = R.drawable.ic_star_on,
                badgeText = "${AppThemePreset.entries.size} 套预设",
                badgeType = GlassTagType.Info,
                isExpanded = expandedSections.contains(SettingsMenuSection.Appearance),
                onToggle = { toggleSection(SettingsMenuSection.Appearance) },
                hazeState = hazeState
            ) {
                ThemeAppearanceSection(
                    currentPreset = state.currentPreset,
                    followSystemDark = state.followSystemDark,
                    onPresetSelect = actions.onPresetChange,
                    onFollowSystemDarkChange = actions.onFollowSystemDarkChange
                )
            }
        }

        // 4. Category 2: Notifications & Alerts
        item(key = "category_notifications") {
            SettingsCategoryCard(
                title = "消息与安防通知策略",
                subtitle = if (state.notificationsEnabled) "推送已开启 · 人员/移动/快照" else "推送总开关已关闭",
                iconRes = R.drawable.ic_notifications,
                badgeText = if (state.notificationsEnabled) "已启用" else "已停用",
                badgeType = if (state.notificationsEnabled) GlassTagType.Success else GlassTagType.Neutral,
                isExpanded = expandedSections.contains(SettingsMenuSection.Notifications),
                onToggle = { toggleSection(SettingsMenuSection.Notifications) },
                hazeState = hazeState
            ) {
                NotificationSection(
                    state = state,
                    actions = actions
                )
            }
        }

        // 5. Category 3: LAN Direct Connection
        item(key = "category_connectivity") {
            SettingsCategoryCard(
                title = "局域网高速直连接入",
                subtitle = if (state.lanHost.isNotBlank()) "内网直连: ${state.lanHost}" else "当前采用默认解析",
                iconRes = R.drawable.ic_devices,
                badgeText = if (state.lanHost.isNotBlank()) "直连生效" else "默认",
                badgeType = GlassTagType.Info,
                isExpanded = expandedSections.contains(SettingsMenuSection.Connectivity),
                onToggle = { toggleSection(SettingsMenuSection.Connectivity) },
                hazeState = hazeState
            ) {
                LanConfigSection(
                    currentHost = state.lanHost,
                    onSave = actions.onLanHostSave,
                    onReset = actions.onLanHostReset
                )
            }
        }

        // 6. Category 4: Admin Services (Visible only to Admin)
        if (state.isAdmin) {
            item(key = "category_admin_services") {
                SettingsCategoryCard(
                    title = "高级系统与运维中枢",
                    subtitle = "自动化 · 安防 · 权限 · 人脸 · 存储配额",
                    iconRes = R.drawable.ic_ptz_gear,
                    badgeText = "管理特权",
                    badgeType = GlassTagType.Success,
                    isExpanded = expandedSections.contains(SettingsMenuSection.AdminServices),
                    onToggle = { toggleSection(SettingsMenuSection.AdminServices) },
                    hazeState = hazeState
                ) {
                    AdminServicesSection(
                        state = state,
                        actions = actions
                    )
                }
            }
        }

        // 7. Category 5: App Version & Updates
        item(key = "category_version_updates") {
            val isDebugTrack = state.updateTrack == "debug"
            SettingsCategoryCard(
                title = "应用版本与在线升级",
                subtitle = "${state.appVersion} · ${if (isDebugTrack) "测试开发通道" else "正式稳定通道"}",
                iconRes = R.drawable.ic_download,
                badgeText = if (state.isDownloading) "下载中" else if (isDebugTrack) "测试通道" else "正式通道",
                badgeType = if (state.isDownloading) GlassTagType.Success else if (isDebugTrack) GlassTagType.Info else GlassTagType.Neutral,
                isExpanded = expandedSections.contains(SettingsMenuSection.AboutUpdates),
                onToggle = { toggleSection(SettingsMenuSection.AboutUpdates) },
                hazeState = hazeState
            ) {
                AppVersionSection(
                    appVersion = state.appVersion,
                    updateTrack = state.updateTrack,
                    isAdmin = state.isAdmin,
                    updateStatusText = state.updateStatusText,
                    isDownloading = state.isDownloading,
                    downloadProgress = state.downloadProgress,
                    onUpdateTrackChange = actions.onUpdateTrackChange,
                    onCheckUpdate = actions.onCheckUpdateClick
                )
            }
        }
    }
}

// =========================================================================================
// Category Accordion Card Wrapper
// =========================================================================================
@Composable
private fun SettingsCategoryCard(
    title: String,
    subtitle: String,
    iconRes: Int,
    badgeText: String? = null,
    badgeType: GlassTagType = GlassTagType.Info,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    content: @Composable () -> Unit,
) {
    val tokens = GlassTheme.tokens
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "chevron_rotation"
    )

    LiquidGlassCard(
        hazeState = hazeState,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
        ) {
            // Header Bar (Always visible)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Category icon circle
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(tokens.controlBg)
                            .border(1.dp, tokens.cardBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = iconRes),
                            contentDescription = title,
                            tint = tokens.textColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.textColor,
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (badgeText != null) {
                                GlassTag(
                                    text = badgeText,
                                    type = badgeType
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            fontSize = 11.sp,
                            color = tokens.textSecondaryColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Smooth rotating arrow indicator
                Icon(
                    painter = painterResource(id = R.drawable.ic_chevron_down),
                    contentDescription = if (isExpanded) "收起" else "展开",
                    tint = tokens.textSecondaryColor,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(chevronRotation)
                )
            }

            // Expanded Content Body
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    HorizontalDivider(
                        color = tokens.textColor.copy(alpha = 0.08f),
                        thickness = 0.8.dp,
                        modifier = Modifier.padding(bottom = 14.dp)
                    )
                    content()
                }
            }
        }
    }
}

// =========================================================================================
// 1. User Profile & Security Credentials
// =========================================================================================
@Composable
private fun UserProfileCard(
    state: SettingsUiState,
    onToggleDetails: () -> Unit,
    onRefreshToken: () -> Unit,
    onLogout: () -> Unit,
    hazeState: HazeState?,
) {
    val tokens = GlassTheme.tokens
    val avatarLetter = state.userName.firstOrNull()?.uppercaseChar()?.toString() ?: "U"

    LiquidGlassCard(
        hazeState = hazeState,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .animateContentSize()
        ) {
            // Profile Top Row: Avatar + Name + Role Badge + Logout
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(tokens.controlBg)
                        .border(1.dp, tokens.cardBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = avatarLetter,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.textColor
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = state.userName,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.textColor,
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        GlassTag(
                            text = if (state.isAdmin) "系统管理员" else "普通成员",
                            type = if (state.isAdmin) GlassTagType.Success else GlassTagType.Neutral
                        )
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = if (state.isTokenExpired) {
                            "登录凭证已过期"
                        } else if (state.tokenRemainingDays != null) {
                            "剩余有效期: ${state.tokenRemainingDays} 天"
                        } else {
                            "凭证状态正常"
                        },
                        fontSize = 11.sp,
                        color = if (state.isTokenExpired) Color(0xFFEF4444) else tokens.textSecondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFEF4444).copy(alpha = 0.12f))
                        .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .clickable { onLogout() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "退出",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFEF4444)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(
                color = tokens.textColor.copy(alpha = 0.08f),
                thickness = 0.8.dp
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Expand Details Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onToggleDetails() }
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "设备与安全凭据详情",
                        fontSize = 11.sp,
                        color = tokens.textSecondaryColor
                    )
                    Text(
                        text = if (state.isSessionDetailsExpanded) "收起 ▴" else "展开 ▾",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF10B981)
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onRefreshToken() }
                        .padding(vertical = 4.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_refresh),
                        contentDescription = "Refresh",
                        tint = tokens.textColor,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "刷新令牌",
                        fontSize = 11.sp,
                        color = tokens.textColor
                    )
                }
            }

            AnimatedVisibility(
                visible = state.isSessionDetailsExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CredentialDetailRow(label = "用户标识 (User ID)", value = state.userId)
                    CredentialDetailRow(label = "绑定设备 (Device ID)", value = state.deviceId)
                    CredentialDetailRow(label = "凭据签发时间", value = state.tokenIssued)
                    CredentialDetailRow(label = "凭据到期时间", value = state.tokenExpires)
                }
            }
        }
    }
}

@Composable
private fun CredentialDetailRow(label: String, value: String) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = tokens.textSecondaryColor,
            modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = tokens.textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// =========================================================================================
// 2. Theme Section (Embedded in Accordion)
// =========================================================================================
@Composable
private fun ThemeAppearanceSection(
    currentPreset: AppThemePreset,
    followSystemDark: Boolean,
    onPresetSelect: (AppThemePreset) -> Unit,
    onFollowSystemDarkChange: (Boolean) -> Unit,
) {
    val tokens = GlassTheme.tokens

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "精选微晶流体配色库（点击即时切换，全 App 丝滑渐变）：",
            fontSize = 11.sp,
            color = tokens.textSecondaryColor
        )
        Spacer(modifier = Modifier.height(10.dp))

        // Atomic Theme Preset Selector
        GlassThemeSelector(
            currentPreset = currentPreset,
            onPresetSelect = onPresetSelect
        )

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(
            color = tokens.textColor.copy(alpha = 0.08f),
            thickness = 0.8.dp
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "跟随系统深浅外观",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = tokens.textColor
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "开启时按系统昼夜自动在晨曦浅色与深色预设间切换",
                    fontSize = 11.sp,
                    color = tokens.textSecondaryColor
                )
            }

            GlassSwitch(
                checked = followSystemDark,
                onCheckedChange = onFollowSystemDarkChange
            )
        }
    }
}

// =========================================================================================
// 3. Notification Section (Embedded in Accordion)
// =========================================================================================
@Composable
private fun NotificationSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val tokens = GlassTheme.tokens

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Master switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "系统与安防推送总开关",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textColor
                )
                Text(
                    text = "开启后接收摄像头异常活动与系统通知",
                    fontSize = 11.sp,
                    color = tokens.textSecondaryColor
                )
            }

            GlassSwitch(
                checked = state.notificationsEnabled,
                onCheckedChange = actions.onNotificationsMasterChange
            )
        }

        AnimatedVisibility(
            visible = state.notificationsEnabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HorizontalDivider(
                    color = tokens.textColor.copy(alpha = 0.08f),
                    thickness = 0.8.dp
                )

                SettingToggleRow(
                    title = "人员与访客侦测 (Person)",
                    subtitle = "AI 识别到人形越界或停留时触发优先报警",
                    checked = state.notifyPerson,
                    onCheckedChange = actions.onNotifyPersonChange
                )

                SettingToggleRow(
                    title = "画面移动与动态事件 (Motion)",
                    subtitle = "接收环境通用移动侦测事件推送",
                    checked = state.notifyMotion,
                    onCheckedChange = actions.onNotifyMotionChange
                )

                if (state.isAdmin) {
                    SettingToggleRow(
                        title = "系统维护与服务状态 (System)",
                        subtitle = "接收容器重启、服务宕机或离线预警",
                        checked = state.notifySystem,
                        onCheckedChange = actions.onNotifySystemChange
                    )
                }

                SettingToggleRow(
                    title = "通知附带抓拍快照",
                    subtitle = "在系统通知栏直接预览 AI 报警快照",
                    checked = state.notifyIncludeSnapshot,
                    onCheckedChange = actions.onNotifySnapshotChange
                )

                SettingToggleRow(
                    title = "免打扰时段过滤",
                    subtitle = state.dndSummaryText,
                    checked = state.notifyDndEnabled,
                    onCheckedChange = actions.onNotifyDndChange,
                    onSubtitleClick = actions.onDndTimeClick
                )

                HorizontalDivider(
                    color = tokens.textColor.copy(alpha = 0.08f),
                    thickness = 0.8.dp
                )

                SettingToggleRow(
                    title = "前台常驻保活服务",
                    subtitle = "在通知栏保持前台服务，提高低内存保活概率",
                    checked = state.keepAliveEnabled,
                    onCheckedChange = actions.onKeepAliveChange
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (state.isBatteryOptimized) Color(0xFF10B981).copy(alpha = 0.10f)
                            else Color(0xFFF59E0B).copy(alpha = 0.12f)
                        )
                        .clickable { actions.onBatteryOptClick() }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_security_shield),
                        contentDescription = "Shield",
                        tint = if (state.isBatteryOptimized) Color(0xFF10B981) else Color(0xFFF59E0B),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (state.isBatteryOptimized) "已加入电池优化白名单 (后台稳定推送)"
                        else "未加入电池白名单，点击配置以防后台被杀",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (state.isBatteryOptimized) Color(0xFF10B981) else Color(0xFFF59E0B)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onSubtitleClick: (() -> Unit)? = null,
) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = tokens.textColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = if (onSubtitleClick != null) Color(0xFF10B981) else tokens.textSecondaryColor,
                modifier = if (onSubtitleClick != null) Modifier.clickable { onSubtitleClick() } else Modifier
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        GlassSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

// =========================================================================================
// 4. LAN Direct Connection Section (Embedded in Accordion)
// =========================================================================================
@Composable
private fun LanConfigSection(
    currentHost: String,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    var inputHost by remember(currentHost) { mutableStateOf(currentHost) }
    val focusManager = LocalFocusManager.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "配置内网 NAS 直连地址，同 Wi-Fi 下直连 NAS 绕过公网流量：",
            fontSize = 11.sp,
            color = tokens.textSecondaryColor
        )
        Spacer(modifier = Modifier.height(10.dp))

        BasicTextField(
            value = inputHost,
            onValueChange = { inputHost = it },
            textStyle = TextStyle(
                color = tokens.textColor,
                fontSize = 13.sp
            ),
            cursorBrush = SolidColor(Color(0xFF10B981)),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                focusManager.clearFocus()
                onSave(inputHost)
            }),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.controlBg)
                .border(1.dp, tokens.cardBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            decorationBox = { innerTextField ->
                if (inputHost.isEmpty()) {
                    Text(
                        text = "例如: http://192.168.31.234:8088/",
                        fontSize = 13.sp,
                        color = tokens.textSecondaryColor.copy(alpha = 0.6f)
                    )
                }
                innerTextField()
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlassActionButton(
                text = "保存并应用",
                onClick = {
                    focusManager.clearFocus()
                    onSave(inputHost)
                },
                modifier = Modifier.weight(1f)
            )

            GlassActionButton(
                text = "重置为默认",
                onClick = {
                    focusManager.clearFocus()
                    inputHost = ""
                    onReset()
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// =========================================================================================
// 5. Admin Advanced Services Section (Embedded in Accordion)
// =========================================================================================
@Composable
private fun AdminServicesSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val tokens = GlassTheme.tokens

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Quick Nav Links Grid (2x2)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AdminQuickTile(
                title = "自动化场景",
                iconRes = R.drawable.ic_bolt,
                onClick = actions.onNavAutomations,
                modifier = Modifier.weight(1f)
            )
            AdminQuickTile(
                title = "安全防御中心",
                iconRes = R.drawable.ic_security_shield,
                onClick = actions.onNavSecurityDefense,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AdminQuickTile(
                title = "用户权限管理",
                iconRes = R.drawable.ic_admin_users,
                onClick = actions.onNavUsers,
                modifier = Modifier.weight(1f)
            )
            AdminQuickTile(
                title = "家庭人脸库",
                iconRes = R.drawable.ic_face_recognition,
                onClick = actions.onNavFamilyFaces,
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider(
            color = tokens.textColor.copy(alpha = 0.08f),
            thickness = 0.8.dp
        )

        // AI Source mode
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { actions.onAiSourceConfigClick() }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = "AI 识别源模式",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = tokens.textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = state.aiSourceMode,
                    fontSize = 11.sp,
                    color = Color(0xFF10B981),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "切换 ❯",
                fontSize = 12.sp,
                color = tokens.textSecondaryColor
            )
        }

        SettingToggleRow(
            title = "移动侦测兼容回退",
            subtitle = if (state.motionFallback) "已开启: 兼容普通移动并报警(防漏报)" else "已关闭: 严格只认人体标签",
            checked = state.motionFallback,
            onCheckedChange = actions.onMotionFallbackChange
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { actions.onStorageConfigClick() }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = "录像存储空间配额",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = tokens.textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "配置自动清理水位线与单路最大保留天数",
                    fontSize = 11.sp,
                    color = tokens.textSecondaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "配置 ❯",
                fontSize = 12.sp,
                color = tokens.textSecondaryColor
            )
        }
    }
}

@Composable
private fun AdminQuickTile(
    title: String,
    iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.controlBg)
            .border(1.dp, tokens.cardBorder, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = title,
            tint = tokens.textColor,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = tokens.textColor
        )
    }
}

// =========================================================================================
// 6. App Version & Updates Section (Embedded in Accordion)
// =========================================================================================
@Composable
private fun AppVersionSection(
    appVersion: String,
    updateTrack: String = "release",
    isAdmin: Boolean = false,
    updateStatusText: String,
    isDownloading: Boolean,
    downloadProgress: Float,
    onUpdateTrackChange: (String) -> Unit = {},
    onCheckUpdate: () -> Unit,
) {
    val tokens = GlassTheme.tokens

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "当前安装版本: $appVersion",
                fontSize = 12.sp,
                color = tokens.textSecondaryColor,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(end = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            GlassTag(
                text = updateStatusText,
                type = if (isDownloading) GlassTagType.Success else GlassTagType.Neutral
            )
        }

        // OTA Update Channel Selector Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.controlBg)
                .border(1.dp, tokens.cardBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = "更新推送通道",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = tokens.textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isAdmin) {
                        if (updateTrack == "debug") "开发测试通道 · 优先接收最新功能与修复" else "正式发布通道 · 仅接收稳定上线版本"
                    } else {
                        "正式发布通道 (非管理员锁定)"
                    },
                    fontSize = 10.5.sp,
                    color = tokens.textSecondaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Channel pills: [正式版] [测试版]
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.textColor.copy(alpha = 0.08f))
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val isReleaseSelected = updateTrack != "debug"
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isReleaseSelected) tokens.surfaceColor.copy(alpha = 0.95f)
                            else Color.Transparent
                        )
                        .clickable(enabled = isAdmin) {
                            if (!isReleaseSelected) onUpdateTrackChange("release")
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "正式版",
                        fontSize = 11.sp,
                        fontWeight = if (isReleaseSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isReleaseSelected) tokens.textColor else tokens.textSecondaryColor
                    )
                }

                val isDebugSelected = updateTrack == "debug"
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isDebugSelected) Color(0xFF3B82F6).copy(alpha = 0.25f)
                            else Color.Transparent
                        )
                        .clickable(enabled = isAdmin) {
                            if (!isDebugSelected) onUpdateTrackChange("debug")
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "测试版",
                        fontSize = 11.sp,
                        fontWeight = if (isDebugSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isDebugSelected) (if (tokens.isLight) Color(0xFF1D4ED8) else Color(0xFF60A5FA)) else tokens.textSecondaryColor
                    )
                }
            }
        }

        if (isDownloading) {
            GlassProgressIndicator(
                progress = downloadProgress,
                height = 8.dp,
                color = Color(0xFF10B981)
            )
            Text(
                text = "正在下载更新包... ${(downloadProgress * 100).toInt()}%",
                fontSize = 11.sp,
                color = tokens.textSecondaryColor
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        val buttonText = when {
            isDownloading -> "更新下载中..."
            updateStatusText.contains("已就绪") -> "立即安装新版本"
            else -> "检查新版本"
        }

        GlassActionButton(
            text = buttonText,
            onClick = onCheckUpdate,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
