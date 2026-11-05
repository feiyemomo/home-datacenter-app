package com.homedatacenter.app.ui.glass

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * LiquidGlassCard: Signature frosted glass container.
 *
 * Features:
 * - Haze 2.0.0 backdrop blur via [Modifier.hazeEffect] with dynamic [HazeSampling] (Path B)
 * - Zero-allocation `drawWithCache` curved inner glow & border sheen
 * - Non-uniform squish physics press reaction (scaleX ~0.97, scaleY ~1.02)
 *   with corner-radius spring compensation
 * - Subtle animated specular sheen sweep
 * - All colors, alphas, radii and shadows strictly driven by [GlassTheme.tokens]
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    tokens: GlassTokens = GlassTheme.tokens,
    performanceMode: HazePerformanceMode = HazePerformanceMode.Adaptive,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable BoxScope.() -> Unit
) {
    val isPressed by interactionSource.collectIsPressedAsState()

    // Squish effect: Non-uniform scale with volume conservation (0.97 * 1.02 ~ 0.99)
    val squishScaleX by animateFloatAsState(
        targetValue = if (isPressed && enabled && onClick != null) 0.97f else 1.0f,
        animationSpec = tokens.springMotionSpec,
        label = "card_squish_x"
    )
    val squishScaleY by animateFloatAsState(
        targetValue = if (isPressed && enabled && onClick != null) 1.02f else 1.0f,
        animationSpec = tokens.springMotionSpec,
        label = "card_squish_y"
    )

    // Corner radius spring compensation
    val animatedCornerRadius by animateDpAsState(
        targetValue = if (isPressed && enabled && onClick != null) {
            tokens.cornerRadius * 0.92f
        } else {
            tokens.cornerRadius
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "card_corner_spring"
    )

    val cardShape = remember(animatedCornerRadius) {
        RoundedCornerShape(animatedCornerRadius)
    }

    // Backdrop blur modifier using Haze 2.0.0 hazeBlur
    val hazeModifier = if (hazeState != null) {
        Modifier.hazeBlur(
            input = HazeInput.Sources(hazeState),
            style = HazeBlurStyle {
                blurRadius(20.dp)
                backgroundColor(Color.Transparent)
            },
            performanceMode = performanceMode
        )
    } else {
        Modifier
    }

    val clickableModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = squishScaleX
                scaleY = squishScaleY
            }
            .shadow(
                elevation = tokens.outerShadowElevation,
                shape = cardShape,
                ambientColor = tokens.outerShadowColor,
                spotColor = tokens.outerShadowColor
            )
            .clip(cardShape)
            .then(hazeModifier)
            .then(clickableModifier)
            .drawWithCache {
                val cornerRadiusPx = animatedCornerRadius.toPx()
                val strokeWidthPx = tokens.borderWidth.toPx()
                val inset = strokeWidthPx / 2f

                // Pre-build Path for unified card-border with inset compensation so it's not clipped
                val roundRect = RoundRect(
                    rect = Rect(inset, inset, size.width - inset, size.height - inset),
                    cornerRadius = CornerRadius((cornerRadiusPx - inset).coerceAtLeast(0f), (cornerRadiusPx - inset).coerceAtLeast(0f))
                )
                val borderPath = Path().apply {
                    addRoundRect(roundRect)
                }

                val borderStroke = Stroke(width = strokeWidthPx)

                onDrawBehind {
                    // 1. Pure card-bg surface body (clean frosted liquid glass without dirty multi-scrim)
                    drawRect(color = tokens.cardBg)

                    // 2. Uniform 1px card-border (no oversaturated rainbow specular rim)
                    drawPath(
                        path = borderPath,
                        color = tokens.cardBorder,
                        style = borderStroke
                    )
                }
            }
    ) {
        content()
    }
}

/**
 * LiquidButton: Tactile glass pill button with responsive squish and sheen.
 */
@Composable
fun LiquidButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    tokens: GlassTokens = GlassTheme.tokens,
    performanceMode: HazePerformanceMode = HazePerformanceMode.Adaptive,
    enabled: Boolean = true,
    cornerRadius: Dp = 16.dp,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit
) {
    val isPressed by interactionSource.collectIsPressedAsState()

    // High-responsiveness squish dynamics for button taps
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

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = squishScaleX
                scaleY = squishScaleY
            }
            .shadow(
                elevation = if (isPressed) 1.dp else tokens.outerShadowElevation * 0.4f,
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
                val strokeWidthPx = tokens.borderWidth.toPx()
                val inset = strokeWidthPx / 2f

                val borderPath = Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = Rect(inset, inset, size.width - inset, size.height - inset),
                            cornerRadius = CornerRadius((radiusPx - inset).coerceAtLeast(0f), (radiusPx - inset).coerceAtLeast(0f))
                        )
                    )
                }

                val borderStroke = Stroke(width = strokeWidthPx)

                onDrawBehind {
                    // Control background fill
                    drawRect(
                        color = if (isPressed) tokens.accentBg else tokens.controlBg
                    )

                    // 1px micro border
                    drawPath(
                        path = borderPath,
                        color = tokens.cardBorder,
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
            content()
        }
    }
}
