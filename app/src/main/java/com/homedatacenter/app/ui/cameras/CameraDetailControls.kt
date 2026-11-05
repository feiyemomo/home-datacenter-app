package com.homedatacenter.app.ui.cameras

import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.data.model.CameraPreset
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.LiquidGlassCard

/**
 * Pure Jetpack Compose TopBar for CameraDetailActivity.
 */
@Composable
fun CameraDetailTopBar(
    camera: Camera?,
    isOnline: Boolean,
    onBackClick: () -> Unit,
    onSnapshotClick: () -> Unit,
    onPipClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = GlassTheme.tokens
    val textPrimary = tokens.textColor
    val textSecondary = tokens.textSecondaryColor

    Surface(
        color = tokens.navBg,
        border = BorderStroke(1.dp, tokens.cardBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_arrow_back_24),
                    contentDescription = "返回",
                    tint = textPrimary
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            ) {
                Text(
                    text = camera?.name ?: "摄像头",
                    color = textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                color = if (isOnline) tokens.statusOnline else textSecondary,
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isOnline) "在线" else "离线",
                        color = textPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    val brand = (camera?.vendor ?: "").ifBlank { "generic" }
                    val host = camera?.host ?: ""
                    val extra = listOf(brand, host).filter { it.isNotBlank() }.joinToString(" · ")
                    if (extra.isNotBlank()) {
                        Text(
                            text = " · $extra",
                            color = textSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            IconButton(onClick = onSnapshotClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_camera),
                    contentDescription = "截图抓拍",
                    tint = textPrimary
                )
            }

            IconButton(onClick = onPipClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_pip),
                    contentDescription = "画中画",
                    tint = textPrimary
                )
            }
        }
    }
}

/**
 * Pure Jetpack Compose Action Row for CameraDetailActivity.
 * Unified neutral glass pills with theme-accent highlights (no rainbow candy colors).
 */
@Composable
fun CameraDetailActionRow(
    onReloadClick: () -> Unit,
    isAudioEnabled: Boolean,
    onAudioToggleClick: () -> Unit,
    isTalkbackSupported: Boolean,
    isTalkbackActive: Boolean,
    onTalkbackTouch: (Boolean) -> Unit,
    onRecordingsClick: () -> Unit,
    onAlertsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = GlassTheme.tokens
    val textPrimary = tokens.textColor

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. 重载
            ActionButtonPill(
                iconRes = R.drawable.ic_refresh,
                label = "重载",
                isActive = false,
                onClick = onReloadClick,
                modifier = Modifier.weight(1f)
            )

            // 2. 拾音开 / 拾音关
            ActionButtonPill(
                iconRes = if (isAudioEnabled) R.drawable.ic_volume_on else R.drawable.ic_volume_off,
                label = if (isAudioEnabled) "拾音开" else "静音",
                isActive = isAudioEnabled,
                onClick = onAudioToggleClick,
                modifier = Modifier.weight(1f)
            )

            // 3. 对讲 (if supported)
            if (isTalkbackSupported) {
                var isPressed by remember { mutableStateOf(false) }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isTalkbackActive) tokens.accentBg else tokens.controlBg,
                    border = BorderStroke(
                        1.dp,
                        if (isTalkbackActive) tokens.accentColor else tokens.cardBorder
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val isDown = event.changes.any { it.pressed }
                                    if (isDown) {
                                        event.changes.forEach { it.consume() }
                                    }
                                    if (isDown != isPressed) {
                                        isPressed = isDown
                                        onTalkbackTouch(isDown)
                                    }
                                }
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_mic),
                            contentDescription = "对讲",
                            tint = if (isTalkbackActive) tokens.accentColor else textPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "对讲",
                            color = if (isTalkbackActive) tokens.accentColor else textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 4. 回放
            ActionButtonPill(
                iconRes = R.drawable.ic_video,
                label = "回放",
                isActive = false,
                onClick = onRecordingsClick,
                modifier = Modifier.weight(1f)
            )

            // 5. 告警
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = tokens.statusWarning.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, tokens.statusWarning.copy(alpha = 0.35f)),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clickable { onAlertsClick() }
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_alerts),
                        contentDescription = "告警",
                        tint = tokens.statusWarning,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "告警",
                        color = tokens.statusWarning,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (isTalkbackSupported) {
            Text(
                text = "按住「对讲」向摄像机喊话",
                color = tokens.textSecondaryColor,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun ActionButtonPill(
    iconRes: Int,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = GlassTheme.tokens
    val textPrimary = tokens.textColor

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) tokens.accentBg else tokens.controlBg,
        border = BorderStroke(
            1.dp,
            if (isActive) tokens.accentColor else tokens.cardBorder
        ),
        modifier = modifier
            .height(44.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = if (isActive) tokens.accentColor else textPrimary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = if (isActive) tokens.accentColor else textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Pure Jetpack Compose PTZ Compass Dial and Speed Control.
 */
@Composable
fun CameraDetailPtzCard(
    ptzSpeed: Float,
    onPtzMove: (direction: String, start: Boolean) -> Unit,
    onPtzStop: () -> Unit,
    onPtzSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = GlassTheme.tokens
    val textPrimary = tokens.textColor
    val textSecondary = tokens.textSecondaryColor

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "云台控制",
            color = textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LiquidGlassCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Circular Dial D-Pad
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(CircleShape)
                        .background(tokens.controlBg)
                ) {
                    // Outer border
                    Surface(
                        shape = CircleShape,
                        color = androidx.compose.ui.graphics.Color.Transparent,
                        border = BorderStroke(1.dp, tokens.cardBorder),
                        modifier = Modifier.fillMaxSize()
                    ) {}

                    // Up
                    PtzDirectionButton(
                        iconRes = R.drawable.ic_ptz_up,
                        contentDescription = "向上",
                        onMove = { start -> onPtzMove("up", start) },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp)
                    )

                    // Down
                    PtzDirectionButton(
                        iconRes = R.drawable.ic_ptz_down,
                        contentDescription = "向下",
                        onMove = { start -> onPtzMove("down", start) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp)
                    )

                    // Left
                    PtzDirectionButton(
                        iconRes = R.drawable.ic_ptz_left,
                        contentDescription = "向左",
                        onMove = { start -> onPtzMove("left", start) },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 10.dp)
                    )

                    // Right
                    PtzDirectionButton(
                        iconRes = R.drawable.ic_ptz_right,
                        contentDescription = "向右",
                        onMove = { start -> onPtzMove("right", start) },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 10.dp)
                    )

                    // Center Stop Button
                    Surface(
                        shape = CircleShape,
                        color = tokens.controlBg,
                        border = BorderStroke(1.dp, tokens.cardBorder),
                        modifier = Modifier
                            .size(52.dp)
                            .align(Alignment.Center)
                            .clickable { onPtzStop() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_ptz_stop),
                                contentDescription = "停止",
                                tint = tokens.textColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                Text(
                    text = "长按方向键持续调整 · 松开即停",
                    color = textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Speed Slider Row
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = tokens.controlBg,
                    border = BorderStroke(1.dp, tokens.cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "转速",
                            color = textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Slider(
                            value = ptzSpeed,
                            onValueChange = onPtzSpeedChange,
                            valueRange = 0.05f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = tokens.accentColor,
                                activeTrackColor = tokens.accentColor,
                                inactiveTrackColor = tokens.cardBorder
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )

                        Text(
                            text = String.format(java.util.Locale.US, "%.2fx", ptzSpeed),
                            color = tokens.accentColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PtzDirectionButton(
    iconRes: Int,
    contentDescription: String,
    onMove: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = GlassTheme.tokens
    var isPressed by remember { mutableStateOf(false) }

    Surface(
        shape = CircleShape,
        color = if (isPressed) tokens.accentBg else androidx.compose.ui.graphics.Color.Transparent,
        modifier = modifier
            .size(50.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val down = event.changes.any { it.pressed }
                        if (down != isPressed) {
                            isPressed = down
                            onMove(down)
                        }
                    }
                }
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = if (isPressed) tokens.accentColor else tokens.textColor,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

/**
 * Pure Jetpack Compose Presets Card.
 */
@Composable
fun CameraDetailPresetsCard(
    presets: List<CameraPreset>,
    isAdmin: Boolean,
    onSelectPreset: (CameraPreset) -> Unit,
    onDeletePreset: (CameraPreset) -> Unit,
    onSaveCurrentPreset: () -> Unit,
    onAddPreset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = GlassTheme.tokens
    val textPrimary = tokens.textColor
    val textSecondary = tokens.textSecondaryColor

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "预置位",
            color = textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LiquidGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                if (presets.isEmpty()) {
                    Text(
                        text = "暂无预置位",
                        color = textSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    presets.forEach { preset ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = tokens.controlBg,
                            border = BorderStroke(1.dp, tokens.cardBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onSelectPreset(preset) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = preset.name,
                                    color = textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isAdmin) {
                                    IconButton(
                                        onClick = { onDeletePreset(preset) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_close),
                                            contentDescription = "删除预置位",
                                            tint = textSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (isAdmin) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 保存当前预置位
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = tokens.accentBg,
                            border = BorderStroke(1.dp, tokens.cardBorder),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clickable { onSaveCurrentPreset() }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_add),
                                    contentDescription = "保存当前",
                                    tint = tokens.accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "保存当前位置",
                                    color = tokens.accentColor,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // 添加预置位
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = tokens.controlBg,
                            border = BorderStroke(1.dp, tokens.cardBorder),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clickable { onAddPreset() }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "添加预置位",
                                    color = textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
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
 * Pure Jetpack Compose Settings Card for CameraDetailActivity.
 * Replaces legacy 3D embossed buttons with clean liquid glass controls and theme-accent switches.
 */
@Composable
fun CameraDetailSettingsCard(
    camera: Camera?,
    isAdmin: Boolean,
    isOwnerOrAdmin: Boolean,
    onShareClick: () -> Unit,
    isAudioPickupEnabled: Boolean,
    onAudioPickupChange: (Boolean) -> Unit,
    isContinuousRecordEnabled: Boolean,
    onContinuousRecordChange: (Boolean) -> Unit,
    onSwitchCodecClick: () -> Unit,
    sampleRateFps: Int,
    onSampleRateClick: () -> Unit,
    onDeleteCameraClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = GlassTheme.tokens
    val textPrimary = tokens.textColor
    val textSecondary = tokens.textSecondaryColor

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "设置",
            color = textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LiquidGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // 1. 共享摄像头 (visible for admin or owner)
                if (isOwnerOrAdmin) {
                    SettingActionButton(
                        iconRes = R.drawable.ic_user_plus,
                        title = "共享摄像头",
                        iconTint = textPrimary,
                        onClick = onShareClick,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                // 2. 摄像头拾音 Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isAudioPickupEnabled) "摄像头拾音 (已开启)" else "摄像头拾音 (已关闭)",
                            color = textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "麦克风已启用，直播与录像包含音频",
                            color = textSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Switch(
                        checked = isAudioPickupEnabled,
                        onCheckedChange = onAudioPickupChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = tokens.accentColor,
                            checkedTrackColor = tokens.accentBg,
                            uncheckedThumbColor = textSecondary,
                            uncheckedTrackColor = tokens.controlBg
                        )
                    )
                }

                // Admin-only controls
                if (isAdmin) {
                    Spacer(modifier = Modifier.height(10.dp))

                    // 3. 开启持续录像 Switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "开启持续录像",
                                color = textPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "由 Frigate 执行 24/7 持续录制",
                                color = textSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        Switch(
                            checked = isContinuousRecordEnabled,
                            onCheckedChange = onContinuousRecordChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = tokens.accentColor,
                                checkedTrackColor = tokens.accentBg,
                                uncheckedThumbColor = textSecondary,
                                uncheckedTrackColor = tokens.controlBg
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 4. 切换为 H264 (推荐) Button
                    SettingActionButton(
                        iconRes = R.drawable.ic_video,
                        title = "切换为 H264 (推荐)",
                        iconTint = textPrimary,
                        onClick = onSwitchCodecClick,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    // 5. AI 抽样检测频率 Button
                    SettingActionButton(
                        iconRes = R.drawable.ic_bolt,
                        title = "AI 抽样检测频率 ($sampleRateFps fps)",
                        iconTint = textPrimary,
                        onClick = onSampleRateClick,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    // 6. 删除摄像头 Button (Danger)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = tokens.statusDanger.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, tokens.statusDanger.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clickable { onDeleteCameraClick() }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = "删除摄像头",
                                tint = tokens.statusDanger,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "删除摄像头",
                                color = tokens.statusDanger,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingActionButton(
    iconRes: Int,
    title: String,
    iconTint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = GlassTheme.tokens
    val textPrimary = tokens.textColor

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = tokens.controlBg,
        border = BorderStroke(1.dp, tokens.cardBorder),
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                color = textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
