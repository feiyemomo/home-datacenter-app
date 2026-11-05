package com.homedatacenter.app.ui.glass

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ambient Fluid Background with organic drifting mesh gradient.
 *
 * Implements 4 fluid blobs drifting with mismatched prime-number durations
 * (13s, 17s, 23s, 29s) with [FastOutSlowInEasing] for continuous organic movement.
 *
 * Automatic Performance Degradation:
 * - API < 31 (absence of hardware RenderEffect backend)
 * - PowerManager isPowerSaveMode == true
 * - System animator/transition scale == 0 (Reduced Motion accessibility)
 *
 * When degraded, switches to a static, zero-ticker multi-stop mesh gradient,
 * entirely bypassing continuous frame rendering passes.
 */
@Composable
fun AmbientFluidBackground(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    preset: AppThemePreset = AppThemePreset.WarmLight,
    forceDegraded: Boolean = false,
    content: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val isDegraded = remember(forceDegraded) {
        forceDegraded || checkPerformanceDegradation(context)
    }

    // Smooth in-place color animation over 400ms (zero Activity recreation)
    val colorTween = tween<Color>(durationMillis = 400)
    val animatedBaseBgColor by animateColorAsState(
        targetValue = preset.fluidBaseColor,
        animationSpec = colorTween,
        label = "fluid_base_bg"
    )

    val targetBlobs = preset.fluidBlobColors
    val animBlob0 by animateColorAsState(targetBlobs.getOrElse(0) { Color.Transparent }, colorTween, label = "blob_0")
    val animBlob1 by animateColorAsState(targetBlobs.getOrElse(1) { Color.Transparent }, colorTween, label = "blob_1")
    val animBlob2 by animateColorAsState(targetBlobs.getOrElse(2) { Color.Transparent }, colorTween, label = "blob_2")
    val animBlob3 by animateColorAsState(targetBlobs.getOrElse(3) { Color.Transparent }, colorTween, label = "blob_3")

    val blobColors = listOf(animBlob0, animBlob1, animBlob2, animBlob3)

    val hazeModifier = if (hazeState != null) {
        Modifier.hazeSource(state = hazeState)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(hazeModifier)
    ) {
        if (isDegraded) {
            // Degraded static mesh: zero GPU ticker overhead
            StaticDegradedBackground(
                baseBgColor = animatedBaseBgColor,
                blobColors = blobColors
            )
        } else {
            // Prime-number period fluid animation loop
            DynamicFluidMeshBackground(
                baseBgColor = animatedBaseBgColor,
                blobColors = blobColors
            )
        }

        // Child overlay content
        content()
    }
}

/**
 * Backward compatibility overload accepting Boolean isDark.
 */
@Composable
fun AmbientFluidBackground(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    isDark: Boolean = false,
    forceDegraded: Boolean = false,
    content: @Composable () -> Unit = {}
) {
    AmbientFluidBackground(
        modifier = modifier,
        hazeState = hazeState,
        preset = AppThemePreset.fromIsDark(isDark),
        forceDegraded = forceDegraded,
        content = content
    )
}

/**
 * Checks system conditions to trigger graceful performance degradation.
 */
private fun checkPerformanceDegradation(context: Context): Boolean {
    // 1. Devices below Android 12 (API 31) lack RenderEffect GPU acceleration
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return true
    }

    // 2. Power Saver Mode active
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    if (powerManager?.isPowerSaveMode == true) {
        return true
    }

    // 3. User requested reduced motion via developer / accessibility settings
    val animScale = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        )
    }.getOrDefault(1.0f)
    val transScale = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            1.0f
        )
    }.getOrDefault(1.0f)
    return animScale == 0f || transScale == 0f
}

/**
 * Static gradient background for degraded/battery-saver execution.
 * Built with drawWithCache to avoid allocations during drawing.
 */
@Composable
private fun StaticDegradedBackground(
    baseBgColor: Color,
    blobColors: List<Color>
) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .drawWithCache {
                // Pass 0: 100% solid base background fill — prevents transparent punch-through to underlying OS window
                val c0 = blobColors.getOrElse(0) { Color.Transparent }
                val c1 = blobColors.getOrElse(1) { Color.Transparent }

                // Use same hue with zero alpha instead of Color.Transparent (0x00000000)
                // to eliminate dirty brown/gray sRGB interpolation muddy spots
                val topGlow = Brush.radialGradient(
                    colors = listOf(c0, c0.copy(alpha = 0f)),
                    center = Offset(size.width * 0.80f, size.height * 0.20f),
                    radius = size.minDimension * 0.85f
                )

                val bottomGlow = Brush.radialGradient(
                    colors = listOf(c1, c1.copy(alpha = 0f)),
                    center = Offset(size.width * 0.20f, size.height * 0.80f),
                    radius = size.minDimension * 0.90f
                )

                onDrawBehind {
                    // 100% solid non-transparent base background fill
                    drawRect(color = baseBgColor)

                    // Pure luminous ambient glows without cross-hue muddy mixing
                    drawRect(brush = topGlow)
                    drawRect(brush = bottomGlow)
                }
            }
    ) {}
}

/**
 * Fluid mesh background with 4 prime-duration drifting blobs.
 */
@Composable
private fun DynamicFluidMeshBackground(
    baseBgColor: Color,
    blobColors: List<Color>
) {
    val infiniteTransition = rememberInfiniteTransition(label = "fluid_mesh")

    // Mismatched prime-number periods (13s, 17s, 23s, 29s) with initial phases
    // staggered at (0, 0.25, 0.50, 0.75) via keyframesSpec so blobs never start clustered
    val p1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 13000
                0f at 0 with FastOutSlowInEasing
                1f at 6500 with FastOutSlowInEasing
                0f at 13000
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "blob_p13"
    )

    val p2 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 17000
                0.25f at 0 with FastOutSlowInEasing
                1.0f at 6375 with FastOutSlowInEasing
                0.0f at 12750 with FastOutSlowInEasing
                0.25f at 17000
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "blob_p17"
    )

    val p3 by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 23000
                0.5f at 0 with FastOutSlowInEasing
                1.0f at 5750 with FastOutSlowInEasing
                0.0f at 17250 with FastOutSlowInEasing
                0.5f at 23000
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "blob_p23"
    )

    val p4 by infiniteTransition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1.75f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 29000
                0.75f at 0 with FastOutSlowInEasing
                1.0f at 3625 with FastOutSlowInEasing
                0.0f at 18125 with FastOutSlowInEasing
                0.75f at 29000
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "blob_p29"
    )

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val baseRadius = size.minDimension * 0.55f

                val r1 = baseRadius * 1.1f
                val c0 = blobColors.getOrElse(0) { Color.Transparent }
                val brush1 = Brush.radialGradient(
                    colors = listOf(c0, c0.copy(alpha = 0f)),
                    center = Offset.Zero,
                    radius = r1
                )

                val r2 = baseRadius * 1.25f
                val c1 = blobColors.getOrElse(1) { Color.Transparent }
                val brush2 = Brush.radialGradient(
                    colors = listOf(c1, c1.copy(alpha = 0f)),
                    center = Offset.Zero,
                    radius = r2
                )

                val r3 = baseRadius * 0.95f
                val c2 = blobColors.getOrElse(2) { Color.Transparent }
                val brush3 = Brush.radialGradient(
                    colors = listOf(c2, c2.copy(alpha = 0f)),
                    center = Offset.Zero,
                    radius = r3
                )

                val r4 = baseRadius * 1.15f
                val c3 = blobColors.getOrElse(3) { Color.Transparent }
                val brush4 = Brush.radialGradient(
                    colors = listOf(c3, c3.copy(alpha = 0f)),
                    center = Offset.Zero,
                    radius = r4
                )

                val piF = PI.toFloat()

                onDrawBehind {
                    // Base canvas tint
                    drawRect(color = baseBgColor)

                    // Blob 1 (13s period) - Top-left orbital drift
                    val c1X = w * (0.25f + 0.15f * sin(p1 * piF))
                    val c1Y = h * (0.25f + 0.12f * cos(p1 * piF))
                    translate(left = c1X, top = c1Y) {
                        drawCircle(
                            brush = brush1,
                            center = Offset.Zero,
                            radius = r1
                        )
                    }

                    // Blob 2 (17s period) - Bottom-right orbital drift
                    val c2X = w * (0.75f - 0.18f * cos(p2 * piF))
                    val c2Y = h * (0.70f + 0.14f * sin(p2 * piF))
                    translate(left = c2X, top = c2Y) {
                        drawCircle(
                            brush = brush2,
                            center = Offset.Zero,
                            radius = r2
                        )
                    }

                    // Blob 3 (23s period) - Center-top wandering drift
                    val c3X = w * (0.60f + 0.20f * sin(p3 * piF))
                    val c3Y = h * (0.35f - 0.15f * cos(p3 * piF))
                    translate(left = c3X, top = c3Y) {
                        drawCircle(
                            brush = brush3,
                            center = Offset.Zero,
                            radius = r3
                        )
                    }

                    // Blob 4 (29s period) - Bottom-left organic breathing drift
                    val c4X = w * (0.30f - 0.12f * cos(p4 * piF))
                    val c4Y = h * (0.80f - 0.16f * sin(p4 * piF))
                    translate(left = c4X, top = c4Y) {
                        drawCircle(
                            brush = brush4,
                            center = Offset.Zero,
                            radius = r4
                        )
                    }
                }
            }
    ) {}
}
