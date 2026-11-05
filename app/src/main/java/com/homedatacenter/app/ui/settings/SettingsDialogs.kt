package com.homedatacenter.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.UpdateInfo
import com.homedatacenter.app.ui.components.GlassActionButton
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.ProvideGlassTheme
import com.homedatacenter.app.util.ThemeManager
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.border
import java.util.Locale

@Composable
fun UpdateAvailableDialog(
    info: UpdateInfo,
    currentVersion: String,
    hasApk: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val preset by ThemeManager.themePresetFlow.collectAsState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ProvideGlassTheme(preset = preset) {
            val tokens = GlassTheme.tokens
            val textPrimary = tokens.textColor
            val textSecondary = tokens.textSecondaryColor
            val controlBg = tokens.controlBg
            val accent = tokens.accentColor

            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(24.dp)),
                color = tokens.dialogBg,
                border = BorderStroke(1.dp, tokens.cardBorder),
                shadowElevation = 24.dp
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "发现新版本",
                        color = textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(controlBg)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "关闭",
                            tint = textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "当前版本: $currentVersion",
                        color = textSecondary,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "最新版本: ${info.version_name}",
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                val sizeText = String.format(Locale.US, "%.1f MB", info.size_bytes / (1024.0 * 1024.0))
                Text(
                    text = "安装包大小: $sizeText",
                    color = textSecondary,
                    fontSize = 13.sp
                )

                if (info.release_notes.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "更新日志:",
                        color = textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(controlBg)
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = info.release_notes,
                            color = textPrimary.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassActionButton(
                        text = "稍后更新",
                        onClick = onDismiss
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    GlassActionButton(
                        text = if (hasApk) "立即安装" else "下载并安装",
                        onClick = {
                            onDismiss()
                            onConfirm()
                        }
                    )
                }
            }
        }
    }
}
}

@Composable
fun StorageQuotaDialog(
    quotaGb: Int,
    archiveHour: Int,
    minAgeDays: Int,
    usageText: String,
    usagePercent: Float,
    onEditQuotaClick: () -> Unit,
    onPickHourClick: () -> Unit,
    onEditMinAgeClick: () -> Unit,
    onTriggerArchive: () -> Unit,
    onCleanCache: () -> Unit,
    isArchiving: Boolean,
    isCleaning: Boolean,
    onDismiss: () -> Unit
) {
    val preset by ThemeManager.themePresetFlow.collectAsState()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ProvideGlassTheme(preset = preset) {
            val tokens = GlassTheme.tokens
            val textPrimary = tokens.textColor
            val textSecondary = tokens.textSecondaryColor
            val controlBg = tokens.controlBg
            val accent = tokens.accentColor

            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(24.dp)),
                color = tokens.dialogBg,
                border = BorderStroke(1.dp, tokens.cardBorder),
                shadowElevation = 24.dp
            ) {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "录像配额与云归档",
                        color = textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(controlBg)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "关闭",
                            tint = textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Storage Progress
                Text(
                    text = "本地录像空间占用",
                    color = textSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = usageText,
                        color = textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (usagePercent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = accent,
                    trackColor = controlBg
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Settings Cards
                StorageSettingItem(
                    label = "本地容量上限",
                    value = "${quotaGb} GB",
                    onClick = onEditQuotaClick
                )

                Spacer(modifier = Modifier.height(8.dp))

                StorageSettingItem(
                    label = "每日云端归档时间",
                    value = "%02d:00".format(archiveHour),
                    onClick = onPickHourClick
                )

                Spacer(modifier = Modifier.height(8.dp))

                StorageSettingItem(
                    label = "本地保留门限",
                    value = "超过 ${minAgeDays} 天",
                    onClick = onEditMinAgeClick
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassActionButton(
                        text = if (isArchiving) "正在归档…" else "立即归档",
                        modifier = Modifier.weight(1f),
                        enabled = !isArchiving,
                        onClick = onTriggerArchive
                    )
                    GlassActionButton(
                        text = if (isCleaning) "清理中…" else "清理切片缓存",
                        modifier = Modifier.weight(1f),
                        enabled = !isCleaning,
                        onClick = onCleanCache
                    )
                }
            }
        }
    }
}
}

@Composable
private fun StorageSettingItem(
    label: String,
    value: String,
    onClick: () -> Unit
) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.controlBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = tokens.textSecondaryColor,
            fontSize = 14.sp
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                color = tokens.accentColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = tokens.textSecondaryColor.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun QuotaStepButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tokens = GlassTheme.tokens
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tokens.controlBg)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = tokens.textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun EditQuotaDialog(
    initialQuota: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var quota by remember { mutableIntStateOf(initialQuota) }
    var textValue by remember { mutableStateOf(initialQuota.toString()) }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun updateValue(newVal: Int) {
        val clamped = newVal.coerceIn(50, 5000)
        quota = clamped
        textValue = clamped.toString()
        errorText = null
    }

    val preset by ThemeManager.themePresetFlow.collectAsState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ProvideGlassTheme(preset = preset) {
            val tokens = GlassTheme.tokens

            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(24.dp)),
                color = tokens.dialogBg,
                border = BorderStroke(1.dp, tokens.cardBorder),
                shadowElevation = 24.dp
            ) {
                val textPrimary = tokens.textColor
                val textSecondary = tokens.textSecondaryColor
                val controlBg = tokens.controlBg
                val cardBorder = tokens.cardBorder
                val accent = tokens.accentColor
                val danger = tokens.statusDanger

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "修改录像配额",
                    color = textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = textValue,
                    onValueChange = {
                        textValue = it
                        val parsed = it.toIntOrNull()
                        if (parsed != null && parsed in 50..5000) {
                            quota = parsed
                            errorText = null
                        }
                    },
                    label = { Text("配额上限 (GB)", color = textSecondary) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = errorText != null,
                    supportingText = errorText?.let { { Text(it, color = danger) } },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = textPrimary,
                        unfocusedTextColor = textPrimary,
                        focusedContainerColor = controlBg,
                        unfocusedContainerColor = controlBg,
                        focusedIndicatorColor = accent,
                        unfocusedIndicatorColor = cardBorder,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Stepper Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuotaStepButton("-10", Modifier.weight(1f)) { updateValue(quota - 10) }
                    QuotaStepButton("-1", Modifier.weight(1f)) { updateValue(quota - 1) }
                    QuotaStepButton("+1", Modifier.weight(1f)) { updateValue(quota + 1) }
                    QuotaStepButton("+10", Modifier.weight(1f)) { updateValue(quota + 10) }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Preset Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuotaStepButton("200G", Modifier.weight(1f)) { updateValue(200) }
                    QuotaStepButton("400G", Modifier.weight(1f)) { updateValue(400) }
                    QuotaStepButton("600G", Modifier.weight(1f)) { updateValue(600) }
                    QuotaStepButton("1000G", Modifier.weight(1f)) { updateValue(1000) }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassActionButton(
                        text = "取消",
                        onClick = onDismiss
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    GlassActionButton(
                        text = "保存生效",
                        onClick = {
                            val parsed = textValue.trim().toIntOrNull()
                            if (parsed == null || parsed < 50 || parsed > 5000) {
                                errorText = "请输入 50 ~ 5000 之间的整数"
                                return@GlassActionButton
                            }
                            onConfirm(parsed)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}
}

data class AiSourceOption(
    val key: String,
    val title: String,
    val description: String,
)

val DefaultAiSourceOptions = listOf(
    AiSourceOption(
        key = "camera",
        title = "摄像头自带硬件AI (默认)",
        description = "只推摄像头人形，0% NAS CPU开销，无重复"
    ),
    AiSourceOption(
        key = "frigate",
        title = "Frigate NVR 软件检测",
        description = "由NAS主机运行模型，支持精细防区与跌倒检测"
    ),
    AiSourceOption(
        key = "both",
        title = "双路冗余互备 (智能去重)",
        description = "两边都接收，6秒内同相机自动合并去重"
    )
)

/**
 * Pure Jetpack Compose Modal Dialog for selecting AI alert source.
 * Replaces legacy MaterialAlertDialogBuilder with consistent liquid glass styling.
 */
@Composable
fun AiSourceModeDialog(
    currentKey: String,
    options: List<AiSourceOption> = DefaultAiSourceOptions,
    onDismiss: () -> Unit,
    onSelect: (key: String, label: String) -> Unit,
) {
    val preset by ThemeManager.themePresetFlow.collectAsState()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ProvideGlassTheme(preset = preset) {
            val tokens = GlassTheme.tokens
            val textPrimary = tokens.textColor
            val textSecondary = tokens.textSecondaryColor
            val accent = tokens.accentColor

            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(24.dp)),
                color = tokens.dialogBg,
                border = BorderStroke(1.dp, tokens.cardBorder),
                shadowElevation = 24.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "选择 AI 告警来源模式",
                            color = textPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(tokens.controlBg)
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = "关闭",
                                tint = textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        options.forEach { opt ->
                            val isSelected = opt.key == currentKey
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) tokens.accentBg else tokens.controlBg,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) accent.copy(alpha = 0.4f) else tokens.cardBorder
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelect(opt.key, opt.title.substringBefore(" ("))
                                        onDismiss()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = opt.title,
                                            color = if (isSelected) accent else textPrimary,
                                            fontSize = 15.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                        Text(
                                            text = opt.description,
                                            color = textSecondary,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(accent),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_check),
                                                contentDescription = "已选择",
                                                tint = tokens.onAccentColor,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .border(1.5.dp, tokens.cardBorder, CircleShape)
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
