package com.homedatacenter.app.ui.cameras

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.runtime.collectAsState
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.ProvideGlassTheme
import com.homedatacenter.app.util.ThemeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homedatacenter.app.data.model.RegisterCameraRequest
import com.homedatacenter.app.di.AppContainer
import kotlinx.coroutines.launch

/**
 * Admin-only dialog that wraps POST /api/v1/cameras.
 * Pure Jetpack Compose implementation with Liquid Glass styling.
 * Eliminates legacy dialog_register_camera.xml.
 */
class RegisterCameraDialog(
    context: Context,
    private val container: AppContainer,
    private val onSuccess: () -> Unit,
) : androidx.appcompat.app.AppCompatDialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                val preset by ThemeManager.themePresetFlow.collectAsState()
                ProvideGlassTheme(preset = preset) {
                    RegisterCameraDialogCard(
                        container = container,
                        onDismiss = { dismiss() },
                        onSuccess = {
                            onSuccess()
                            dismiss()
                        }
                    )
                }
            }
        }
        setContentView(composeView)
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.94).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
    }
}

@Composable
fun RegisterCameraDialogCard(
    container: AppContainer,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var vendor by remember { mutableStateOf("hikvision") }
    var channelId by remember { mutableStateOf("101") }
    var onvifPort by remember { mutableStateOf("80") }
    var rtspPort by remember { mutableStateOf("554") }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }

    var ptz by remember { mutableStateOf(true) }
    var audio by remember { mutableStateOf(true) }
    var twoWayAudio by remember { mutableStateOf(false) }
    var motion by remember { mutableStateOf(true) }

    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val tokens = GlassTheme.tokens
    val cyberCyan = tokens.accentColor
    val cardBg = tokens.dialogBg
    val borderCol = tokens.cardBorder
    val textPrimary = tokens.textColor
    val textSecondary = tokens.textSecondaryColor

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = textPrimary,
        unfocusedTextColor = textPrimary,
        focusedBorderColor = cyberCyan,
        unfocusedBorderColor = borderCol,
        focusedLabelColor = cyberCyan,
        unfocusedLabelColor = textSecondary,
        cursorColor = cyberCyan,
    )

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = cardBg,
        border = BorderStroke(1.dp, borderCol),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "注册新摄像头",
                color = textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "配置 ONVIF 与 RTSP 连接参数并交由 go2rtc 托管",
                color = textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                placeholder = { Text("例如：客厅摄像机") },
                singleLine = true,
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            )

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("主机 IP") },
                placeholder = { Text("例如：192.168.31.100") },
                singleLine = true,
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = vendor,
                    onValueChange = { vendor = it },
                    label = { Text("厂商") },
                    singleLine = true,
                    colors = textFieldColors,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = channelId,
                    onValueChange = { channelId = it },
                    label = { Text("通道") },
                    singleLine = true,
                    colors = textFieldColors,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = onvifPort,
                    onValueChange = { onvifPort = it },
                    label = { Text("ONVIF 端口") },
                    singleLine = true,
                    colors = textFieldColors,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = rtspPort,
                    onValueChange = { rtspPort = it },
                    label = { Text("RTSP 端口") },
                    singleLine = true,
                    colors = textFieldColors,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    colors = textFieldColors,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = textFieldColors,
                    modifier = Modifier.weight(1f)
                )
            }

            // Capability Checkboxes
            Text(
                text = "能力支持",
                color = textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
            )

            val checkboxColors = CheckboxDefaults.colors(
                checkedColor = cyberCyan,
                uncheckedColor = textSecondary,
                checkmarkColor = Color(0xFF0F172A)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = ptz, onCheckedChange = { ptz = it }, colors = checkboxColors)
                    Text("云台(PTZ)", color = textPrimary, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = audio, onCheckedChange = { audio = it }, colors = checkboxColors)
                    Text("音频输入", color = textPrimary, fontSize = 13.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = twoWayAudio, onCheckedChange = { twoWayAudio = it }, colors = checkboxColors)
                    Text("双向对讲", color = textPrimary, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = motion, onCheckedChange = { motion = it }, colors = checkboxColors)
                    Text("动态侦测", color = textPrimary, fontSize = 13.sp)
                }
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage ?: "",
                    color = Color(0xFFEF4444),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSubmitting
                ) {
                    Text("取消", color = textSecondary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val n = name.trim()
                        val h = host.trim()
                        val p = password
                        if (n.isEmpty() || h.isEmpty() || p.isEmpty()) {
                            errorMessage = "请填写名称、主机 IP 和密码"
                            return@Button
                        }
                        val token = container.prefsManager.token
                        if (token.isNullOrEmpty()) {
                            errorMessage = "未登录，无法执行操作"
                            return@Button
                        }
                        isSubmitting = true
                        errorMessage = null
                        scope.launch {
                            try {
                                val req = RegisterCameraRequest(
                                    name = n,
                                    vendor = vendor.trim().ifBlank { "hikvision" },
                                    host = h,
                                    onvifPort = onvifPort.trim().toIntOrNull() ?: 80,
                                    rtspPort = rtspPort.trim().toIntOrNull() ?: 554,
                                    channelId = channelId.trim().toIntOrNull() ?: 101,
                                    username = username.trim().ifBlank { "admin" },
                                    password = p,
                                    ptz = ptz,
                                    audio = audio,
                                    twoWayAudio = twoWayAudio,
                                    motion = motion,
                                )
                                container.getRepository().registerCamera(token, req)
                                onSuccess()
                            } catch (e: Exception) {
                                errorMessage = "注册失败: ${e.message}"
                                isSubmitting = false
                            }
                        }
                    },
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cyberCyan,
                        contentColor = Color(0xFF0F172A)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            color = Color(0xFF0F172A),
                            modifier = Modifier.padding(horizontal = 12.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("开始注册", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
