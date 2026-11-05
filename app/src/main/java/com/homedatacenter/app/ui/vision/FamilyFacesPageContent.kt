package com.homedatacenter.app.ui.vision

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.painterResource
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.VisionPerson
import com.homedatacenter.app.data.model.VisionStatus
import com.homedatacenter.app.ui.glass.AmbientFluidBackground
import com.homedatacenter.app.ui.glass.AppThemePreset
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.LiquidGlassCard
import com.homedatacenter.app.ui.glass.ProvideGlassTheme
import com.homedatacenter.app.util.ThemeManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyFacesPageContent(
    visionStatus: VisionStatus,
    persons: List<VisionPerson>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onBackClick: () -> Unit,
    onAddFaceClick: () -> Unit,
    onDeletePersonClick: (VisionPerson) -> Unit,
    preset: AppThemePreset = ThemeManager.currentPreset,
    modifier: Modifier = Modifier
) {
    val hazeState = rememberHazeState()
    val pullRefreshState = rememberPullToRefreshState()
    val tokens = GlassTheme.tokens

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Fluid Background
        AmbientFluidBackground(
            hazeState = hazeState,
            preset = preset
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.statusBars.asPaddingValues())
        ) {
            // Modern Liquid Glass Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val tokens = GlassTheme.tokens
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = tokens.textColor
                    )
                }

                Text(
                    text = "家庭人脸档案与视觉识别",
                    color = tokens.textColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Surface(
                    onClick = onAddFaceClick,
                    shape = RoundedCornerShape(12.dp),
                    color = tokens.controlBg,
                    border = androidx.compose.foundation.BorderStroke(1.dp, tokens.cardBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "录入人脸",
                            tint = tokens.accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "录入人脸",
                            color = tokens.accentColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Main Content Area with PullToRefresh
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
                        containerColor = tokens.cardBg,
                        color = tokens.accentColor
                    )
                }
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 10.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 32.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. Vision Engine Status Card
                    item {
                        VisionStatusCard(status = visionStatus, hazeState = hazeState)
                    }

                    // 2. Section Header: Registered Faces
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "已登记家庭成员",
                                color = tokens.textColor,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )

                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = tokens.accentBg,
                                border = androidx.compose.foundation.BorderStroke(1.dp, tokens.cardBorder)
                            ) {
                                Text(
                                    text = "已建库 ${persons.size} 位",
                                    color = tokens.accentColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    // 3. Persons list or Empty state
                    if (persons.isEmpty()) {
                        item {
                            EmptyPersonsCard(onAddFaceClick = onAddFaceClick, hazeState = hazeState)
                        }
                    } else {
                        items(persons, key = { it.name }) { person ->
                            PersonGlassCard(
                                person = person,
                                onDeleteClick = { onDeletePersonClick(person) },
                                hazeState = hazeState
                            )
                        }
                    }

                    // 4. Bottom Action Card
                    item {
                        val tokens = GlassTheme.tokens
                        Surface(
                            onClick = onAddFaceClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = tokens.controlBg,
                            border = androidx.compose.foundation.BorderStroke(1.dp, tokens.cardBorder)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 14.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = tokens.accentColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "录入新家庭人脸档案",
                                    color = tokens.textColor,
                                    fontSize = 15.sp,
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
private fun VisionStatusCard(
    status: VisionStatus,
    hazeState: HazeState
) {
    val tokens = GlassTheme.tokens
    val green = tokens.statusOnline
    val red = tokens.statusDanger
    val warning = tokens.statusWarning

    val onlineColor = if (status.online) green else red
    val statusText = when {
        status.online -> "服务在线"
        status.error?.contains("not configured", ignoreCase = true) == true -> "未配置服务"
        else -> "服务离线"
    }

    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        hazeState = hazeState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Title & Online Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Face,
                    contentDescription = null,
                    tint = tokens.accentColor,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "边缘 AI 视觉推理引擎",
                    color = tokens.textColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = onlineColor.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, onlineColor.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(onlineColor)
                        )
                        Text(
                            text = statusText,
                            color = onlineColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2x2 Engine & CPU Metrics Grid
            Row(modifier = Modifier.fillMaxWidth()) {
                // Col 1: Face & Pose Engines
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column {
                        Text(text = "人脸特征比对引擎", color = tokens.textSecondaryColor, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (status.faceEngineReady) "已就绪 (Inference Ready)" else "未就绪",
                            color = if (status.faceEngineReady) green else red,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Column {
                        Text(text = "人体姿态骨骼引擎", color = tokens.textSecondaryColor, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (status.poseEngineReady) "已就绪 (Pose Ready)" else "未就绪",
                            color = if (status.poseEngineReady) green else red,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Col 2: CPU Usage & Gate
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column {
                        Text(text = "边缘计算负载", color = tokens.textSecondaryColor, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        val cpuText = if (status.online) {
                            String.format(Locale.getDefault(), "%.1f%% CPU", status.cpuUsagePercent)
                        } else {
                            "—"
                        }
                        Text(
                            text = cpuText,
                            color = tokens.textColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Column {
                        Text(text = "负载安全门控", color = tokens.textSecondaryColor, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        val (gateText, gateColor) = when (status.cpuGate.lowercase(Locale.getDefault())) {
                            "circuit_break" -> "保护性熔断 (暂停)" to red
                            "degraded" -> "降频保护 (降级)" to warning
                            else -> "正常运行 (全开)" to green
                        }
                        Text(
                            text = if (status.online) gateText else "不可用",
                            color = if (status.online) gateColor else red,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Footer hint
            Text(
                text = "CPU 超过 80% 触发自适应降频，超过 90% 暂停推理以保护系统稳定性",
                color = tokens.textSecondaryColor,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun PersonGlassCard(
    person: VisionPerson,
    onDeleteClick: () -> Unit,
    hazeState: HazeState
) {
    val tokens = GlassTheme.tokens
    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        hazeState = hazeState
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Circular initial avatar
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(tokens.accentColor, tokens.accentColor.copy(alpha = 0.7f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = person.name.firstOrNull()?.uppercaseChar()?.toString() ?: "人",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = person.name,
                        color = tokens.textColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = tokens.accentBg,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, tokens.cardBorder)
                    ) {
                        Text(
                            text = "家庭成员",
                            color = tokens.accentColor,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = "人脸特征向量已就绪 • 支持抓拍秒级比对",
                    color = tokens.textSecondaryColor,
                    fontSize = 12.sp
                )
            }

            // Delete action button
            IconButton(onClick = onDeleteClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = "删除档案",
                    tint = tokens.statusDanger,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyPersonsCard(
    onAddFaceClick: () -> Unit,
    hazeState: HazeState
) {
    val tokens = GlassTheme.tokens
    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        hazeState = hazeState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 36.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Face,
                contentDescription = null,
                tint = tokens.textSecondaryColor,
                modifier = Modifier.size(56.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "暂无已登记人脸档案",
                color = tokens.textColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "录入家庭成员正面人脸照片后，摄像头 AI 报警将能自动识别并标注人物称呼",
                color = tokens.textSecondaryColor,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                lineHeight = 17.sp
            )
        }
    }
}

/**
 * Pure Compose Add Family Face Dialog (100% Liquid Glass).
 * Eliminates legacy dialog_add_family_face.xml completely.
 */
@Composable
fun AddFamilyFaceDialog(
    name: String,
    onNameChange: (String) -> Unit,
    nameError: String?,
    selectedBitmap: Bitmap?,
    isSubmitting: Boolean,
    onPickGallery: () -> Unit,
    onPickCamera: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit
) {
    val preset by ThemeManager.themePresetFlow.collectAsState()

    Dialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
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
            border = androidx.compose.foundation.BorderStroke(1.dp, tokens.cardBorder),
            shadowElevation = 24.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(22.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(tokens.accentBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Face,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "录入家庭人脸档案",
                            color = textPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "提供清晰正面照片，AI 自动提取 512 维特征向量",
                            color = textSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Name Input Box
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("成员称呼或姓名 (如: 爸爸/妈妈)") },
                    singleLine = true,
                    enabled = !isSubmitting,
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(text = it, color = tokens.statusDanger) } },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = textPrimary,
                        unfocusedTextColor = textPrimary,
                        focusedBorderColor = accent,
                        unfocusedBorderColor = tokens.cardBorder,
                        focusedLabelColor = accent,
                        unfocusedLabelColor = textSecondary,
                        focusedContainerColor = tokens.controlBg,
                        unfocusedContainerColor = tokens.controlBg
                    ),
                    shape = RoundedCornerShape(14.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Image Preview Container
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(tokens.controlBg)
                        .border(1.dp, tokens.cardBorder, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (selectedBitmap != null) {
                        Image(
                            bitmap = selectedBitmap.asImageBitmap(),
                            contentDescription = "照片预览",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )

                        // Check badge on top right
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(tokens.statusOnline),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Face,
                                contentDescription = null,
                                tint = textSecondary,
                                modifier = Modifier.size(42.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "请选择相册照片或直接拍照",
                                color = textSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Pick buttons row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        onClick = onPickGallery,
                        enabled = !isSubmitting,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = tokens.controlBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, tokens.cardBorder)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_gallery_black_24dp),
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "相册选图",
                                color = textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Surface(
                        onClick = onPickCamera,
                        enabled = !isSubmitting,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = tokens.controlBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, tokens.cardBorder)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_camera_black_24dp),
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "拍摄人脸",
                                color = textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                if (isSubmitting) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = accent,
                        trackColor = tokens.accentBg
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action buttons: Cancel & Submit
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isSubmitting
                    ) {
                        Text(
                            text = "取消",
                            color = textSecondary,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Surface(
                        onClick = onSubmit,
                        enabled = !isSubmitting,
                        shape = RoundedCornerShape(12.dp),
                        color = accent,
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
                                text = if (isSubmitting) "提取特征中..." else "开始录入",
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

/**
 * Pure Compose Delete Person Confirmation Dialog.
 */
@Composable
fun ConfirmDeletePersonDialog(
    personName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val preset by ThemeManager.themePresetFlow.collectAsState()
    Dialog(onDismissRequest = onDismiss) {
        ProvideGlassTheme(preset = preset) {
            val tokens = GlassTheme.tokens
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .clip(RoundedCornerShape(20.dp)),
                color = tokens.dialogBg,
                border = androidx.compose.foundation.BorderStroke(1.dp, tokens.cardBorder),
                shadowElevation = 16.dp
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = tokens.statusDanger,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "删除人脸档案",
                            color = tokens.textColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "确定要删除家庭成员「$personName」的人脸档案吗？\n删除后，AI 视觉识别将无法在告警中识别该成员。",
                        color = tokens.textSecondaryColor,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(text = "取消", color = tokens.textSecondaryColor)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Surface(
                            onClick = onConfirm,
                            shape = RoundedCornerShape(10.dp),
                            color = tokens.statusDanger
                        ) {
                            Text(
                                text = "确认删除",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
