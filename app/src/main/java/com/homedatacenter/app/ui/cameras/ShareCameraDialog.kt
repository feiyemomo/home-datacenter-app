package com.homedatacenter.app.ui.cameras

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog as ComposeDialog
import androidx.compose.ui.window.DialogProperties
import com.homedatacenter.app.data.model.CameraShare
import com.homedatacenter.app.data.model.User
import com.homedatacenter.app.di.AppContainer
import kotlinx.coroutines.launch

/**
 * Dialog that manages camera shares for a single camera.
 * Pure Jetpack Compose implementation with Liquid Glass styling.
 * Eliminates legacy dialog_share_camera.xml and item_share_camera.xml.
 */
class ShareCameraDialog(
    context: Context,
    private val container: AppContainer,
    private val cameraId: Long,
) : androidx.appcompat.app.AppCompatDialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                val preset by ThemeManager.themePresetFlow.collectAsState()
                ProvideGlassTheme(preset = preset) {
                    ShareCameraContent(
                        container = container,
                        cameraId = cameraId,
                        onDismiss = { dismiss() }
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
fun ShareCameraContent(
    container: AppContainer,
    cameraId: Long,
    onDismiss: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    val cyberCyan = tokens.accentColor
    val cardBg = tokens.dialogBg
    val borderCol = tokens.cardBorder
    val textPrimary = tokens.textColor
    val textSecondary = tokens.textSecondaryColor
    val dangerRed = tokens.statusDanger

    val scope = rememberCoroutineScope()
    var shares by remember { mutableStateOf<List<CameraShare>>(emptyList()) }
    var users by remember { mutableStateOf<List<User>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var showAddDialog by remember { mutableStateOf(false) }
    var shareToRevoke by remember { mutableStateOf<CameraShare?>(null) }

    fun refreshShares() {
        val token = container.prefsManager.token ?: return
        isLoading = true
        errorMessage = null
        scope.launch {
            try {
                val list = container.getRepository().listShares(token, cameraId)
                if (users.isEmpty()) {
                    users = try {
                        container.getRepository().listUsers(token)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                shares = list
                isLoading = false
            } catch (e: Exception) {
                errorMessage = "加载失败: ${e.message}"
                isLoading = false
            }
        }
    }

    LaunchedEffect(cameraId) {
        refreshShares()
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = cardBg,
        border = BorderStroke(1.dp, borderCol),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth()
        ) {
            Text(
                text = "共享摄像头",
                color = textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "授权家庭其他成员实时观看或控制此摄像头",
                color = textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = cyberCyan)
                }
            } else if (errorMessage != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(errorMessage ?: "", color = dangerRed, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { refreshShares() }) {
                        Text("重试", color = cyberCyan)
                    }
                }
            } else if (shares.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("当前尚未共享给任何其他用户", color = textSecondary, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(shares, key = { it.userId }) { share ->
                        val userName = users.firstOrNull { it.id == share.userId }?.name ?: "用户 #${share.userId}"
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = tokens.controlBg,
                            border = BorderStroke(1.dp, tokens.cardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = userName,
                                        color = textPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "ID: ${share.userId} · 云台: ${if (share.canPtz) "已开启" else "未授权"}",
                                        color = textSecondary,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                ) {
                                    Text("PTZ", color = textSecondary, fontSize = 10.sp)
                                    Switch(
                                        checked = share.canPtz,
                                        onCheckedChange = { checked ->
                                            val token = container.prefsManager.token ?: return@Switch
                                            scope.launch {
                                                try {
                                                    container.getRepository().shareCamera(token, cameraId, share.userId, checked)
                                                    refreshShares()
                                                } catch (_: Exception) {}
                                            }
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color(0xFF0F172A),
                                            checkedTrackColor = cyberCyan,
                                            uncheckedThumbColor = textSecondary,
                                            uncheckedTrackColor = Color(0x331E293B)
                                        ),
                                        modifier = Modifier.size(width = 38.dp, height = 24.dp)
                                    )
                                }

                                TextButton(
                                    onClick = { shareToRevoke = share }
                                ) {
                                    Text("取消", color = dangerRed, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss
                ) {
                    Text("关闭", color = textSecondary)
                }

                Button(
                    onClick = { showAddDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cyberCyan,
                        contentColor = Color(0xFF0F172A)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("+ 添加共享", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // Revoke Confirmation Dialog
    if (shareToRevoke != null) {
        val share = shareToRevoke!!
        val name = users.firstOrNull { it.id == share.userId }?.name ?: "用户 #${share.userId}"
        ComposeDialog(
            onDismissRequest = { shareToRevoke = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cardBg,
                border = BorderStroke(1.dp, borderCol),
                modifier = Modifier.padding(24.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("取消共享", color = textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "确定取消与「$name」的摄像头共享？",
                        color = textSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { shareToRevoke = null }) {
                            Text("取消", color = textSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val token = container.prefsManager.token
                                val s = share
                                shareToRevoke = null
                                if (token != null) {
                                    scope.launch {
                                        try {
                                            container.getRepository().unshareCamera(token, cameraId, s.userId)
                                            refreshShares()
                                        } catch (_: Exception) {}
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = dangerRed)
                        ) {
                            Text("确定取消", color = Color.White)
                        }
                    }
                }
            }
        }
    }

    // Add Share Dialog
    if (showAddDialog) {
        val currentUserId = container.prefsManager.userId
        val sharedIds = shares.map { it.userId }.toSet()
        val candidates = users.filter { it.id != currentUserId && it.id !in sharedIds }

        var selectedUserId by remember { mutableStateOf<Long?>(candidates.firstOrNull()?.id) }
        var manualUserId by remember { mutableStateOf("") }
        var canPtz by remember { mutableStateOf(false) }

        val textFieldColors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = textPrimary,
            unfocusedTextColor = textPrimary,
            focusedBorderColor = cyberCyan,
            unfocusedBorderColor = Color(0x3338BDF8),
            cursorColor = cyberCyan,
        )

        ComposeDialog(
            onDismissRequest = { showAddDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cardBg,
                border = BorderStroke(1.dp, borderCol),
                modifier = Modifier.padding(24.dp).fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("添加共享用户", color = textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))

                    if (candidates.isNotEmpty()) {
                        Text("选择家庭成员：", color = textSecondary, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 160.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(candidates, key = { it.id }) { u ->
                                val isSelected = selectedUserId == u.id
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) tokens.accentBg else tokens.controlBg,
                                    border = BorderStroke(1.dp, if (isSelected) cyberCyan else tokens.cardBorder),
                                    onClick = { selectedUserId = u.id },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = u.name + if (u.isAdmin) " (管理员)" else "",
                                            color = if (isSelected) cyberCyan else textPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Text("手动输入用户 ID：", color = textSecondary, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = manualUserId,
                            onValueChange = { manualUserId = it },
                            placeholder = { Text("例如：1001") },
                            colors = textFieldColors,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = canPtz,
                            onCheckedChange = { canPtz = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = cyberCyan,
                                uncheckedColor = textSecondary,
                                checkmarkColor = Color(0xFF0F172A)
                            )
                        )
                        Text("同时允许控制云台 (PTZ)", color = textPrimary, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showAddDialog = false }) {
                            Text("取消", color = textSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val targetId = if (candidates.isNotEmpty()) {
                                    selectedUserId
                                } else {
                                    manualUserId.trim().toLongOrNull()
                                }
                                if (targetId == null || targetId <= 0L) {
                                    return@Button
                                }
                                val token = container.prefsManager.token ?: return@Button
                                showAddDialog = false
                                scope.launch {
                                    try {
                                        container.getRepository().shareCamera(token, cameraId, targetId, canPtz)
                                        refreshShares()
                                    } catch (_: Exception) {}
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = cyberCyan,
                                contentColor = Color(0xFF0F172A)
                            )
                        ) {
                            Text("确认添加", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
