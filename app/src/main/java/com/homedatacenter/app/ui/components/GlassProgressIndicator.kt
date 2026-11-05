package com.homedatacenter.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.homedatacenter.app.ui.glass.GlassTheme

/**
 * GlassProgressIndicator — Signature liquid glass progress bar.
 *
 * Features:
 * - Smooth animated progress interpolation
 * - Semi-translucent glass track with inner contrast
 * - Specular highlight shimmer along the progress fill
 * - Dynamic color tinting based on load thresholds
 */
@Composable
fun GlassProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF10B981),
    trackColor: Color = Color.Unspecified,
    height: Dp = 6.dp,
    animated: Boolean = true,
) {
    val tokens = GlassTheme.tokens
    val clampedProgress = progress.coerceIn(0f, 1f)

    val animatedProgress by animateFloatAsState(
        targetValue = clampedProgress,
        animationSpec = tween(durationMillis = if (animated) 400 else 0),
        label = "glass_progress"
    )

    val effectiveTrackColor = if (trackColor != Color.Unspecified) {
        trackColor
    } else {
        tokens.textColor.copy(alpha = 0.12f)
    }

    val shape = RoundedCornerShape(height / 2)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(effectiveTrackColor)
    ) {
        if (animatedProgress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(color)
                    .drawWithCache {
                        val sheenBrush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.40f),
                                Color.Transparent
                            ),
                            start = Offset.Zero,
                            end = Offset(0f, size.height)
                        )
                        onDrawWithContent {
                            drawContent()
                            drawRect(sheenBrush)
                        }
                    }
            )
        }
    }
}
