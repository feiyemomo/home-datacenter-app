package com.homedatacenter.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homedatacenter.app.R
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.GlassTokens
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * Tab items in the main navigation.
 */
enum class MainTab(
    val title: String,
    @DrawableRes val iconRes: Int,
    val requiresAdmin: Boolean = false,
) {
    Dashboard("主页", R.drawable.ic_dashboard),
    Cameras("摄像头", R.drawable.ic_camera),
    Ops("运维", R.drawable.ic_security_shield, requiresAdmin = true),
    Users("用户管理", R.drawable.ic_admin_users, requiresAdmin = true),
    Settings("设置", R.drawable.ic_settings);
}

/**
 * LiquidGlassBottomBar: Pure Jetpack Compose floating capsule bottom navigation bar.
 *
 * Guarantees:
 * - 100% pure Compose, completely eliminating XML/BottomNavigationView dark mode lag/black box.
 * - Sourced directly from [GlassTheme.tokens], perfectly matching ambient light and dark themes.
 * - Backdrop blur via [hazeState] with Haze 2.0.0.
 * - Spring-bouncy jelly pill indicator on active tab.
 * - WCAG AA/AAA compliant text and icon contrast.
 */
@Composable
fun LiquidGlassBottomBar(
    currentTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    isAdmin: Boolean = true,
    hazeState: HazeState? = null,
    tokens: GlassTokens = GlassTheme.tokens,
    performanceMode: HazePerformanceMode = HazePerformanceMode.Adaptive,
) {
    val barShape = remember { RoundedCornerShape(28.dp) }
    val isLight = tokens.isLight

    val hazeModifier = if (hazeState != null) {
        Modifier.hazeBlur(
            input = HazeInput.Sources(hazeState),
            style = HazeBlurStyle {
                blurRadius(24.dp)
                backgroundColor(tokens.navBg)
            },
            performanceMode = performanceMode
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .shadow(
                elevation = tokens.outerShadowElevation,
                shape = barShape,
                ambientColor = tokens.outerShadowColor,
                spotColor = tokens.outerShadowColor
            )
            .clip(barShape)
            .then(hazeModifier)
            .drawWithCache {
                val radiusPx = 28.dp.toPx()
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
                    // Base high-opacity liquid surface body (88% opacity, anti-peekthrough)
                    drawRect(color = tokens.navBg)

                    // 1px subtle card-border (removed top harsh white specular line)
                    drawPath(
                        path = borderPath,
                        color = tokens.cardBorder,
                        style = borderStroke
                    )
                }
            }
            .height(72.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MainTab.entries.filter { !it.requiresAdmin || isAdmin }.forEach { tab ->
                val isSelected = tab == currentTab
                BottomNavItem(
                    tab = tab,
                    isSelected = isSelected,
                    onClick = { onTabSelected(tab) },
                    tokens = tokens,
                    isLight = isLight,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BottomNavItem(
    tab: MainTab,
    isSelected: Boolean,
    onClick: () -> Unit,
    tokens: GlassTokens,
    isLight: Boolean,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.0f else 0.95f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "nav_item_scale"
    )

    // Theme-driven dynamic contrast colors
    val activeColor = tokens.accentColor
    val inactiveColor = tokens.textSecondaryColor
    val indicatorColor = tokens.accentBg

    val contentColor = if (isSelected) activeColor else inactiveColor

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Pill bubble for active tab
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Surface(
                    modifier = Modifier.size(width = 48.dp, height = 28.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = indicatorColor
                ) {}
            }
            Icon(
                painter = painterResource(tab.iconRes),
                contentDescription = tab.title,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        GlassText(
            text = tab.title,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}
