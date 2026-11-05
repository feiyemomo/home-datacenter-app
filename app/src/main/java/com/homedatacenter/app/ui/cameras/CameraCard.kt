package com.homedatacenter.app.ui.cameras

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.LiquidGlassCard
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState



/**
 * CameraCard — Frosted Liquid Glass Camera Card.
 *
 * Features:
 * - Real Haze 2.0.0 backdrop blur via [hazeState] and [hazeSampling]
 * - Colors and styles fully sourced from [GlassTheme.tokens]
 * - Downsampled Coil AsyncImage preview with exact card dimensions
 * - Online status badge, codec badge, audio indicator badge
 */
@Composable
fun CameraCard(
    camera: Camera,
    baseUrl: String?,
    token: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    performanceMode: HazePerformanceMode = HazePerformanceMode.Adaptive,
) {
    val tokens = GlassTheme.tokens
    val cardHeight = dimensionResource(R.dimen.camera_card_height)
    val thumbWidth = dimensionResource(R.dimen.camera_card_thumb_width)
    val thumbHeight = dimensionResource(R.dimen.camera_card_thumb_height)

    val context = LocalContext.current
    val density = LocalDensity.current
    val thumbWidthPx = remember(thumbWidth, density) { with(density) { thumbWidth.roundToPx() } }
    val thumbHeightPx = remember(thumbHeight, density) { with(density) { thumbHeight.roundToPx() } }

    val snapshotUrl = remember(camera.id, baseUrl) {
        if (baseUrl.isNullOrBlank()) ""
        else "${baseUrl.trimEnd('/')}/api/v1/cameras/${camera.id}/frame?quality=30&width=480"
    }

    val imageRequest = remember(snapshotUrl, token, thumbWidthPx, thumbHeightPx) {
        ImageRequest.Builder(context)
            .data(snapshotUrl)
            .apply {
                if (!token.isNullOrEmpty()) {
                    addHeader("Authorization", "Bearer $token")
                }
            }
            .size(width = thumbWidthPx, height = thumbHeightPx)
            .crossfade(150)
            .placeholder(R.drawable.bg_thumbnail_placeholder)
            .error(R.drawable.bg_thumbnail_placeholder)
            .memoryCacheKey("camera_thumb_${camera.id}")
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    LiquidGlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        hazeState = hazeState,
        tokens = tokens,
        performanceMode = performanceMode,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail (16:9)
            Box(
                modifier = Modifier
                    .size(width = thumbWidth, height = thumbHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (tokens.isLight) Color(0x1F000000) else Color.Black.copy(alpha = 0.5f)),
            ) {
                if (camera.isOnline && snapshotUrl.isNotEmpty()) {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = "${camera.name} preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        com.homedatacenter.app.ui.components.GlassText(
                            text = if (!camera.isOnline) "离线" else "无预览",
                            type = com.homedatacenter.app.ui.components.GlassTextType.Secondary,
                            tokens = tokens,
                            fontSize = 12.sp,
                        )
                    }
                }

                // Online indicator pill (bottom-left of thumbnail overlay).
                com.homedatacenter.app.ui.components.GlassTag(
                    text = if (camera.isOnline) "在线" else "离线",
                    type = if (camera.isOnline) com.homedatacenter.app.ui.components.GlassTagType.Success else com.homedatacenter.app.ui.components.GlassTagType.Error,
                    tokens = tokens,
                    onDarkSurface = true,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(5.dp),
                )
            }

            Spacer(modifier = Modifier.size(10.dp))

            // Text column
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                com.homedatacenter.app.ui.components.GlassText(
                    text = camera.name,
                    type = com.homedatacenter.app.ui.components.GlassTextType.Title,
                    tokens = tokens,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.size(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.homedatacenter.app.ui.components.GlassTag(
                        text = camera.codec.ifBlank { "H264" }.uppercase(),
                        type = com.homedatacenter.app.ui.components.GlassTagType.Info,
                        tokens = tokens,
                    )
                    com.homedatacenter.app.ui.components.GlassTag(
                        text = if (camera.hasAudio) "音频" else "静音",
                        type = if (camera.hasAudio) com.homedatacenter.app.ui.components.GlassTagType.Audio else com.homedatacenter.app.ui.components.GlassTagType.Neutral,
                        tokens = tokens,
                    )
                }
            }
        }
    }
}
