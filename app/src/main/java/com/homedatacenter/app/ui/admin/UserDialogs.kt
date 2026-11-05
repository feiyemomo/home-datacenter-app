package com.homedatacenter.app.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.ProvideGlassTheme
import com.homedatacenter.app.util.ThemeManager
import androidx.compose.runtime.collectAsState
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.User

@Composable
fun CreateUserDialog(
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, password: String, isAdmin: Boolean) -> Unit
) {
    val preset by ThemeManager.themePresetFlow.collectAsState()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isAdmin by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ProvideGlassTheme(preset = preset) {
            val tokens = GlassTheme.tokens
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(24.dp)),
                color = tokens.dialogBg,
                border = androidx.compose.foundation.BorderStroke(1.dp, tokens.cardBorder),
                shadowElevation = 8.dp
            ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(tokens.accentBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_user_plus),
                            contentDescription = null,
                            tint = tokens.accentColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "添加新用户",
                            color = tokens.textColor,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "添加家庭或系统成员账号与初始凭据",
                            color = tokens.textSecondaryColor,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Username Field
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        errorText = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("用户名") },
                    singleLine = true,
                    enabled = !isSubmitting,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = tokens.textColor,
                        unfocusedTextColor = tokens.textColor,
                        focusedBorderColor = tokens.accentColor,
                        unfocusedBorderColor = tokens.cardBorder,
                        focusedLabelColor = tokens.accentColor,
                        unfocusedLabelColor = tokens.textSecondaryColor,
                        focusedContainerColor = tokens.controlBg,
                        unfocusedContainerColor = tokens.controlBg
                    ),
                    shape = RoundedCornerShape(14.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Password Field
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorText = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("登录密码 (至少 6 位)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !isSubmitting,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = tokens.textColor,
                        unfocusedTextColor = tokens.textColor,
                        focusedBorderColor = tokens.accentColor,
                        unfocusedBorderColor = tokens.cardBorder,
                        focusedLabelColor = tokens.accentColor,
                        unfocusedLabelColor = tokens.textSecondaryColor,
                        focusedContainerColor = tokens.controlBg,
                        unfocusedContainerColor = tokens.controlBg
                    ),
                    shape = RoundedCornerShape(14.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Admin switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(tokens.controlBg)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "管理员权限",
                            color = tokens.textColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "开启后可管理摄像头配置、AI 档案与系统设置",
                            color = tokens.textSecondaryColor,
                            fontSize = 11.sp
                        )
                    }

                    Switch(
                        checked = isAdmin,
                        onCheckedChange = { isAdmin = it },
                        enabled = !isSubmitting,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = tokens.accentColor,
                            uncheckedThumbColor = tokens.textSecondaryColor,
                            uncheckedTrackColor = tokens.cardBorder
                        )
                    )
                }

                errorText?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = it, color = tokens.statusDanger, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isSubmitting
                    ) {
                        Text("取消", color = tokens.textSecondaryColor)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        onClick = {
                            val u = username.trim()
                            if (u.isEmpty()) {
                                errorText = "请填写用户名"
                                return@Surface
                            }
                            if (password.length < 6) {
                                errorText = "密码长度至少为 6 位"
                                return@Surface
                            }
                            onConfirm(u, password, isAdmin)
                        },
                        enabled = !isSubmitting,
                        shape = RoundedCornerShape(12.dp),
                        color = tokens.accentColor,
                        border = androidx.compose.foundation.BorderStroke(1.dp, tokens.cardBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = tokens.onAccentColor
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = "确认创建",
                                color = tokens.onAccentColor,
                                fontSize = 14.sp,
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

@Composable
fun EditUserDialog(
    user: User,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (newName: String, newIsAdmin: Boolean) -> Unit
) {
    val preset by ThemeManager.themePresetFlow.collectAsState()
    var username by remember { mutableStateOf(user.name) }
    var isAdmin by remember { mutableStateOf(user.isAdmin) }
    var errorText by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ProvideGlassTheme(preset = preset) {
            val tokens = GlassTheme.tokens
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(24.dp)),
                color = tokens.dialogBg,
                border = androidx.compose.foundation.BorderStroke(1.dp, tokens.cardBorder),
                shadowElevation = 8.dp
            ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(tokens.accentBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = tokens.accentColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "编辑用户档案",
                            color = tokens.textColor,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "ID: ${user.id} • 修改名称或角色",
                            color = tokens.textSecondaryColor,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Username Field
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        errorText = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("用户名称") },
                    singleLine = true,
                    enabled = !isSubmitting,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = tokens.textColor,
                        unfocusedTextColor = tokens.textColor,
                        focusedBorderColor = tokens.accentColor,
                        unfocusedBorderColor = tokens.cardBorder,
                        focusedLabelColor = tokens.accentColor,
                        unfocusedLabelColor = tokens.textSecondaryColor,
                        focusedContainerColor = tokens.controlBg,
                        unfocusedContainerColor = tokens.controlBg
                    ),
                    shape = RoundedCornerShape(14.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Admin switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(tokens.controlBg)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "管理员权限",
                            color = tokens.textColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "是否具备系统与摄像头高级管理权限",
                            color = tokens.textSecondaryColor,
                            fontSize = 11.sp
                        )
                    }

                    Switch(
                        checked = isAdmin,
                        onCheckedChange = { isAdmin = it },
                        enabled = !isSubmitting,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = tokens.accentColor,
                            uncheckedThumbColor = tokens.textSecondaryColor,
                            uncheckedTrackColor = tokens.cardBorder
                        )
                    )
                }

                errorText?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = it, color = tokens.statusDanger, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isSubmitting
                    ) {
                        Text("取消", color = tokens.textSecondaryColor)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        onClick = {
                            val u = username.trim()
                            if (u.isEmpty()) {
                                errorText = "请填写用户名"
                                return@Surface
                            }
                            onConfirm(u, isAdmin)
                        },
                        enabled = !isSubmitting,
                        shape = RoundedCornerShape(12.dp),
                        color = tokens.accentColor,
                        border = androidx.compose.foundation.BorderStroke(1.dp, tokens.cardBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = tokens.onAccentColor
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = "保存修改",
                                color = tokens.onAccentColor,
                                fontSize = 14.sp,
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
