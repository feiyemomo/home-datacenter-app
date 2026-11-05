package com.homedatacenter.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.GlassTokens
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * GlassActionButton: Standardized tactile action pill button (e.g., "查看录像", "保存").
 *
 * Design Guidelines:
 * - Default state: var(--control-bg) background, 1px solid var(--card-border), var(--text-primary) font.
 * - Pressed state: var(--accent-bg) background, var(--accent-color) border and font.
 * - Non-uniform volume-conserving squish physics (scaleX ~0.96, scaleY ~1.03).
 * - Eliminates oversaturated orange/amber gradient tints.
 */
@Composable
fun GlassActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    tokens: GlassTokens = GlassTheme.tokens,
    performanceMode: HazePerformanceMode = HazePerformanceMode.Adaptive,
    enabled: Boolean = true,
    cornerRadius: Dp = 14.dp,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val isPressed by interactionSource.collectIsPressedAsState()

    val squishScaleX by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.96f else 1.0f,
        animationSpec = tokens.springMotionSpec,
        label = "btn_squish_x"
    )
    val squishScaleY by animateFloatAsState(
        targetValue = if (isPressed && enabled) 1.03f else 1.0f,
        animationSpec = tokens.springMotionSpec,
        label = "btn_squish_y"
    )

    val animatedRadius by animateDpAsState(
        targetValue = if (isPressed && enabled) cornerRadius * 0.90f else cornerRadius,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "btn_corner_spring"
    )

    val buttonShape = remember(animatedRadius) {
        RoundedCornerShape(animatedRadius)
    }

    val hazeModifier = if (hazeState != null) {
        Modifier.hazeBlur(
            input = HazeInput.Sources(hazeState),
            style = HazeBlurStyle {
                blurRadius(16.dp)
                backgroundColor(tokens.controlBg)
            },
            performanceMode = performanceMode
        )
    } else {
        Modifier
    }

    val resolvedTextColor = if (isPressed) tokens.accentColor else tokens.textColor
    val resolvedBgColor = if (isPressed) tokens.accentBg else tokens.controlBg
    val resolvedBorderColor = if (isPressed) tokens.accentColor else tokens.cardBorder

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = squishScaleX
                scaleY = squishScaleY
            }
            .shadow(
                elevation = if (isPressed) 1.dp else tokens.outerShadowElevation * 0.3f,
                shape = buttonShape,
                ambientColor = tokens.outerShadowColor,
                spotColor = tokens.outerShadowColor
            )
            .clip(buttonShape)
            .then(hazeModifier)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .drawWithCache {
                val radiusPx = animatedRadius.toPx()
                val strokeWidthPx = 1.dp.toPx()

                val borderPath = Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = Rect(Offset.Zero, size),
                            cornerRadius = CornerRadius(radiusPx, radiusPx)
                        )
                    )
                }

                val borderStroke = Stroke(width = strokeWidthPx)

                onDrawBehind {
                    // Control surface background
                    drawRect(color = resolvedBgColor)

                    // 1px micro border
                    drawPath(
                        path = borderPath,
                        color = resolvedBorderColor,
                        style = borderStroke
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            Text(
                text = text,
                color = resolvedTextColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
