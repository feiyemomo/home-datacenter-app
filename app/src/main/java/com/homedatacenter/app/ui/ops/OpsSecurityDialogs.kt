package com.homedatacenter.app.ui.ops

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.homedatacenter.app.ui.components.GlassActionButton
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.ProvideGlassTheme
import com.homedatacenter.app.util.ThemeManager
import androidx.compose.runtime.collectAsState

@Composable
fun ManualBanDialog(
    onDismiss: () -> Unit,
    onConfirm: (ip: String, durationMin: Int, reason: String) -> Unit
) {
    var ip by remember { mutableStateOf("") }
    var durationText by remember { mutableStateOf("30") }
    var reason by remember { mutableStateOf("") }
    var ipError by remember { mutableStateOf(false) }

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
            val cardBorder = tokens.cardBorder
            val accent = tokens.accentColor
            val danger = tokens.statusDanger

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
                Text(
                    text = "手动阻断 IP",
                    color = textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = ip,
                    onValueChange = {
                        ip = it
                        ipError = false
                    },
                    label = { Text("目标 IPv4 地址", color = textSecondary) },
                    isError = ipError,
                    supportingText = if (ipError) {
                        { Text("请输入有效的 IPv4 地址", color = danger) }
                    } else null,
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

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = durationText,
                    onValueChange = { durationText = it },
                    label = { Text("封禁时长 (分钟)", color = textSecondary) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("封禁原因 (选填)", color = textSecondary) },
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
                        text = "立即阻断",
                        onClick = {
                            val trimmedIp = ip.trim()
                            if (trimmedIp.isEmpty()) {
                                ipError = true
                                return@GlassActionButton
                            }
                            val duration = durationText.trim().toIntOrNull() ?: 30
                            val finalReason = reason.trim().ifEmpty { "管理员手动阻断" }
                            onConfirm(trimmedIp, duration, finalReason)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}
}

@Composable
fun AddWhitelistDialog(
    onDismiss: () -> Unit,
    onConfirm: (ipOrCidr: String, note: String) -> Unit
) {
    var entry by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var entryError by remember { mutableStateOf(false) }

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
            val cardBorder = tokens.cardBorder
            val accent = tokens.accentColor
            val danger = tokens.statusDanger

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
                Text(
                    text = "添加白名单",
                    color = textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = entry,
                    onValueChange = {
                        entry = it
                        entryError = false
                    },
                    label = { Text("IP 或 CIDR 网段", color = textSecondary) },
                    placeholder = { Text("如 192.168.1.0/24", color = textSecondary.copy(alpha = 0.5f)) },
                    isError = entryError,
                    supportingText = if (entryError) {
                        { Text("请输入有效的 IP 或网段", color = danger) }
                    } else null,
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

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注说明", color = textSecondary) },
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
                        text = "添加信任",
                        onClick = {
                            val trimmedEntry = entry.trim()
                            if (trimmedEntry.isEmpty()) {
                                entryError = true
                                return@GlassActionButton
                            }
                            val finalNote = note.trim().ifEmpty { "管理员添加" }
                            onConfirm(trimmedEntry, finalNote)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}
}

@Composable
fun AddAlertRecipientDialog(
    onDismiss: () -> Unit,
    onConfirm: (email: String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var emailError by remember { mutableStateOf(false) }

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
            val cardBorder = tokens.cardBorder
            val accent = tokens.accentColor
            val danger = tokens.statusDanger

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
                    Text(
                        text = "添加告警接收邮箱",
                        color = textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it
                            emailError = false
                        },
                        label = { Text("管理员邮箱地址", color = textSecondary) },
                        placeholder = { Text("如 admin@homedatacenter.local", color = textSecondary.copy(alpha = 0.5f)) },
                        isError = emailError,
                        supportingText = if (emailError) {
                            { Text("请输入有效的邮箱格式 (如 name@domain.com)", color = danger) }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
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
                            text = "确认添加",
                            onClick = {
                                val trimmed = email.trim()
                                val isValid = trimmed.isNotEmpty() && trimmed.contains("@") && trimmed.contains(".")
                                if (!isValid) {
                                    emailError = true
                                    return@GlassActionButton
                                }
                                onConfirm(trimmed)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}
