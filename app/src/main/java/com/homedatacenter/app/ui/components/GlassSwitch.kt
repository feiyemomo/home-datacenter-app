package com.homedatacenter.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.homedatacenter.app.ui.glass.GlassTheme

/**
 * GlassSwitch — Tactile liquid glass toggle switch.
 *
 * Features:
 * - Liquid glass pill track with specular rim
 * - Physics-driven bouncy spring thumb translation
 * - Thumb squish expansion on press (volume-conserving tactile feedback)
 * - Luminous accent glow in checked state
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    activeColor: Color = Color(0xFF10B981),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val tokens = GlassTheme.tokens
    val isPressed by interactionSource.collectIsPressedAsState()

    val trackWidth = 50.dp
    val trackHeight = 28.dp
    val thumbSize = 22.dp
    val thumbPadding = 3.dp

    // Spring bouncy thumb offset
    val maxOffset = trackWidth - thumbSize - (thumbPadding * 2)
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) maxOffset else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "switch_thumb_offset"
    )

    // Thumb width squish on press (22dp -> 26dp)
    val thumbWidth by animateDpAsState(
        targetValue = if (isPressed && enabled) thumbSize + 4.dp else thumbSize,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "switch_thumb_squish"
    )

    // Animated track background color
    val trackBgColor by animateColorAsState(
        targetValue = if (checked) {
            activeColor.copy(alpha = if (tokens.isLight) 0.85f else 0.45f)
        } else {
            tokens.surfaceColor.copy(alpha = if (tokens.isLight) 0.40f else 0.25f)
        },
        animationSpec = tween(250),
        label = "switch_track_color"
    )

    val trackBorderColor by animateColorAsState(
        targetValue = if (checked) {
            activeColor.copy(alpha = 0.80f)
        } else {
            tokens.borderColors.firstOrNull()?.copy(alpha = 0.40f) ?: Color.White.copy(alpha = 0.20f)
        },
        animationSpec = tween(250),
        label = "switch_track_border"
    )

    val thumbColor by animateColorAsState(
        targetValue = if (checked) {
            if (tokens.isLight) Color.White else Color(0xFFFFFFFF)
        } else {
            if (tokens.isLight) Color(0xFFFAFAFA) else Color(0xFFE2E8F0)
        },
        animationSpec = tween(200),
        label = "switch_thumb_color"
    )

    Box(
        modifier = modifier
            .width(trackWidth)
            .height(trackHeight)
            .clip(RoundedCornerShape(trackHeight / 2))
            .background(trackBgColor)
            .border(
                width = 1.dp,
                color = trackBorderColor,
                shape = RoundedCornerShape(trackHeight / 2)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Switch
            ) {
                onCheckedChange(!checked)
            }
            .padding(horizontal = thumbPadding),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .width(thumbWidth)
                .height(thumbSize)
                .shadow(elevation = 3.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            thumbColor,
                            thumbColor.copy(alpha = 0.92f)
                        )
                    )
                )
                .border(
                    width = 0.8.dp,
                    color = Color.White.copy(alpha = 0.60f),
                    shape = CircleShape
                )
        )
    }
}
