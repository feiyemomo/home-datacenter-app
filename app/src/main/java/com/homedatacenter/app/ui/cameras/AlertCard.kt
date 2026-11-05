package com.homedatacenter.app.ui.cameras

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.Alert
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.LiquidButton
import com.homedatacenter.app.ui.glass.LiquidGlassCard
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compact alert row component rendered inside LazyColumn.
 * Features:
 * - Real Haze backdrop blur via [hazeState] and dynamic [hazeSampling]
 * - Colors and styles fully driven by [GlassTheme.tokens] (no hardcoded color constants)
 * - Anti-overflow middle column with weighted layout (1f)
 * - Glass capsule button for "查看录像" with translucent background and specular sheen
 * - Coil downsampling to exact hardware pixels with memory caching
 */
@Composable
fun AlertCard(
    alert: Alert,
    baseUrl: String?,
    token: String?,
    onSnapshotClick: (Alert) -> Unit,
    onJumpCamera: (Alert) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    performanceMode: HazePerformanceMode = HazePerformanceMode.Adaptive,
) {
    val tokens = GlassTheme.tokens
    val context = LocalContext.current
    val density = LocalDensity.current

    val thumbWidthPx = remember(density) { with(density) { 96.dp.roundToPx() } }
    val thumbHeightPx = remember(density) { with(density) { 60.dp.roundToPx() } }

    val imageModel = remember(alert.id, alert.thumbnail, baseUrl) {
        AlertThumbnailResolver.resolveModel(
            thumbnailBase64 = alert.thumbnail,
            alertId = alert.id,
            baseUrl = baseUrl
        )
    }

    val imageRequest = remember(imageModel, token, thumbWidthPx, thumbHeightPx) {
        if (imageModel == null) null
        else {
            ImageRequest.Builder(context)
                .data(imageModel)
                .apply {
                    if (imageModel is String && imageModel.startsWith("http") && !token.isNullOrEmpty()) {
                        addHeader("Authorization", "Bearer $token")
                    }
                }
                .size(width = thumbWidthPx, height = thumbHeightPx)
                .crossfade(180)
                .placeholder(R.drawable.bg_thumbnail_placeholder)
                .error(R.drawable.bg_thumbnail_placeholder)
                .memoryCacheKey("alert_thumb_${alert.id}")
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .build()
        }
    }

    val formattedTime = remember(alert.startTime) {
        val date = Date((alert.startTime * 1000).toLong())
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(date)
    }

    val labelDisplay = remember(alert.label) {
        when (alert.label.lowercase(Locale.getDefault())) {
            "person" -> "人形检测"
            "car" -> "车辆识别"
            "truck" -> "卡车识别"
            "bus" -> "巴士识别"
            "bicycle" -> "自行车"
            "motorcycle" -> "摩托车"
            "dog" -> "宠物(犬)"
            "cat" -> "宠物(猫)"
            "bird" -> "鸟类识别"
            "fall" -> "摔倒警报"
            else -> alert.label
        }
    }

    val confidencePercent = remember(alert.confidence) {
        "${(alert.confidence * 100).toInt()}%"
    }

    LiquidGlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        hazeState = hazeState,
        tokens = tokens,
        performanceMode = performanceMode,
        onClick = { onSnapshotClick(alert) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: 96x60dp Thumbnail with robust frosted glass placeholder (never pure black)
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        width = 1.dp,
                        color = tokens.borderColors.firstOrNull()?.copy(alpha = 0.35f) ?: Color(0x33FFFFFF),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .background(if (tokens.isLight) Color(0x1F000000) else Color(0xFF2C2735))
                    .clickable { onSnapshotClick(alert) },
            ) {
                if (imageRequest != null) {
                    SubcomposeAsyncImage(
                        model = imageRequest,
                        contentDescription = "${alert.label} alert thumbnail",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        loading = {
                            AlertThumbnailPlaceholder(
                                label = "加载快照…",
                                iconRes = R.drawable.ic_camera,
                                tokens = tokens,
                                isError = false
                            )
                        },
                        error = {
                            AlertThumbnailPlaceholder(
                                label = "快照获取失败",
                                iconRes = R.drawable.ic_alert_circle,
                                tokens = tokens,
                                isError = true
                            )
                        },
                        success = {
                            SubcomposeAsyncImageContent()
                        }
                    )
                } else {
                    AlertThumbnailPlaceholder(
                        label = "无快照",
                        iconRes = R.drawable.ic_camera,
                        tokens = tokens,
                        isError = false
                    )
                }

                if (alert.hasClip) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(28.dp),
                        shape = CircleShape,
                        color = Color(0x99000000),
                        shadowElevation = 3.dp
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_play_circle),
                            contentDescription = "Play recording",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.size(10.dp))

            // Middle: Label, confidence, time, camera name (weighted 1f to prevent clipping)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    com.homedatacenter.app.ui.components.GlassText(
                        text = labelDisplay,
                        color = tokens.statusDanger,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        tokens = tokens,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // Unified GlassTag for detection confidence
                    com.homedatacenter.app.ui.components.GlassTag(
                        text = confidencePercent,
                        type = com.homedatacenter.app.ui.components.GlassTagType.Info,
                        tokens = tokens,
                    )
                }

                Spacer(modifier = Modifier.size(2.dp))

                com.homedatacenter.app.ui.components.GlassText(
                    text = formattedTime,
                    type = com.homedatacenter.app.ui.components.GlassTextType.Body,
                    tokens = tokens,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.size(2.dp))

                val camDisplayName = alert.cameraName.ifEmpty { alert.cameraSlug.ifEmpty { "未知摄像头" } }
                com.homedatacenter.app.ui.components.GlassText(
                    text = camDisplayName,
                    type = com.homedatacenter.app.ui.components.GlassTextType.Secondary,
                    tokens = tokens,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Right: Standardized GlassActionButton for clip playback
            if (alert.hasClip) {
                Spacer(modifier = Modifier.size(6.dp))
                com.homedatacenter.app.ui.components.GlassActionButton(
                    text = "查看录像",
                    onClick = { onJumpCamera(alert) },
                    hazeState = hazeState,
                    tokens = tokens,
                    performanceMode = performanceMode,
                    cornerRadius = 14.dp,
                )
            }
        }
    }
}

/**
 * High-visibility frosted placeholder for alert snapshots.
 * Guarantees that loading, offline, and error states display clear visual indicators
 * instead of dead black voids.
 */
@Composable
private fun AlertThumbnailPlaceholder(
    label: String,
    @DrawableRes iconRes: Int,
    tokens: com.homedatacenter.app.ui.glass.GlassTokens,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = if (isError) {
                        if (tokens.isLight) listOf(Color(0x33EF4444), Color(0x1FEF4444))
                        else listOf(Color(0xFF3D272A), Color(0xFF2B1F23))
                    } else {
                        if (tokens.isLight) listOf(Color(0x1A000000), Color(0x0D000000))
                        else listOf(Color(0xFF383244), Color(0xFF282332))
                    }
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (isError) {
                    if (tokens.isLight) Color(0xFFEF4444) else Color(0xFFFF8A80)
                } else {
                    if (tokens.isLight) Color(0xFF64748B) else Color(0xFFD4CDDB)
                },
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = label,
                color = if (isError) {
                    if (tokens.isLight) Color(0xFFDC2626) else Color(0xFFFF8A80).copy(alpha = 0.95f)
                } else {
                    if (tokens.isLight) Color(0xFF475569) else Color(0xFFE2DCED)
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
