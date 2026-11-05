package com.homedatacenter.app.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * AppThemePreset: Extensible architectural theme preset enum.
 *
 * Encapsulates the 4 standardized Liquid Glassmorphism design presets:
 * 1. 晨曦浅色 (WarmLight)
 * 2. 曜夜深色 (AmethystDark)
 * 3. 北欧苍翠 (NordicForest)
 * 4. 日落琥珀 (SunsetAmber - 黑金质感)
 *
 * Strict constraint: In dark modes, carbon black base occupies >= 85% visual area,
 * and radial fluid glow opacities are strictly bounded between 8% and 14%.
 */
private val WarmLightBlobColors = listOf(
    Color(0x1AFDE68A), // ~10% 浅香槟金晨曦微光 (高透光轻薄，告别泥巴棕发脏)
    Color(0x14BAE6FD), // ~8% 晨曦天光微蓝
    Color(0x17FED7AA), // ~9% 柔粉橙极淡微澜
    Color(0x10FFFFFF)  // ~6% 纯白透光高光提亮
)

private val AmethystDarkBlobColors = listOf(
    Color(0x1F4F46E5), // rgba(79, 70, 229, 0.12) 深靛蓝微光
    Color(0x1A7C3AED), // rgba(124, 58, 237, 0.10) 极光紫微光
    Color(0x1738BDF8), // ~9% 冷冽微光
    Color(0x144F46E5)  // ~8% 幽夜微光
)

private val NordicForestBlobColors = listOf(
    Color(0x1F0D9488), // rgba(13, 148, 136, 0.12) 冷松青微光
    Color(0x1A059669), // rgba(5, 150, 105, 0.10) 祖母绿微光
    Color(0x142DD4BF), // ~8% 薄荷青微光
    Color(0x120D9488)  // ~7% 极夜微光
)

private val SunsetAmberBlobColors = listOf(
    Color(0x1AD97706), // rgba(217, 119, 6, 0.10) 极弱暗金光晕
    Color(0x14E11D48), // rgba(225, 29, 72, 0.08) 极弱暮色暗红光晕
    Color(0x12F59E0B), // ~7% 琥珀金流光
    Color(0x10BE123C)  // ~6% 暮色微澜
)

/**
 * AppThemePreset: Extensible architectural theme preset enum.
 */
enum class AppThemePreset(
    val presetId: String,
    val displayName: String,
    val subtitle: String,
    val isDark: Boolean,
    val tokensProvider: () -> GlassTokens,
    val fluidBaseColor: Color,
    val fluidBlobColors: List<Color>,
) {
    /**
     * Default warm frosted cream liquid glass.
     */
    WarmLight(
        presetId = "warm_light",
        displayName = "晨曦浅色",
        subtitle = "暖云白微晶 · 香槟金流体 (浅色)",
        isDark = false,
        tokensProvider = { LightGlassTokens },
        fluidBaseColor = Color(0xFFF8F7F4),
        fluidBlobColors = WarmLightBlobColors,
    ),

    /**
     * Deep night amethyst and obsidian liquid glass.
     */
    AmethystDark(
        presetId = "amethyst_dark",
        displayName = "曜夜深色",
        subtitle = "曜石黑微晶 · 极光紫流体 (深色)",
        isDark = true,
        tokensProvider = { DarkGlassTokens },
        fluidBaseColor = Color(0xFF090B10),
        fluidBlobColors = AmethystDarkBlobColors,
    ),

    /**
     * Midnight Emerald: Nordic forest deep green glass palette.
     */
    NordicForest(
        presetId = "nordic_forest",
        displayName = "北欧苍翠",
        subtitle = "极夜松黑微晶 · 冷松青流体 (深色)",
        isDark = true,
        tokensProvider = { NordicForestGlassTokens },
        fluidBaseColor = Color(0xFF070D0C),
        fluidBlobColors = NordicForestBlobColors,
    ),

    /**
     * Sunset Amber: Warm golden hour and twilight rose glass palette.
     * Redesigned to pure black-gold texture, eliminating muddy brown tints.
     */
    SunsetAmber(
        presetId = "sunset_amber",
        displayName = "日落琥珀",
        subtitle = "暖炭黑微晶 · 流光黑金 (深色)",
        isDark = true,
        tokensProvider = { SunsetAmberGlassTokens },
        fluidBaseColor = Color(0xFF0C0A09),
        fluidBlobColors = SunsetAmberBlobColors,
    );

    val tokens: GlassTokens get() = tokensProvider()

    companion object {
        fun fromIsDark(isDark: Boolean): AppThemePreset =
            if (isDark) AmethystDark else WarmLight

        fun fromPresetId(id: String): AppThemePreset =
            entries.firstOrNull { it.presetId == id } ?: WarmLight
    }
}

/**
 * Nordic Forest specialized dark glass tokens.
 */
val NordicForestGlassTokens = GlassTokens(
    blurRadius = 28.dp,
    surfaceAlpha = 0.72f,
    surfaceColor = Color(0xFF0F1917),
    textColor = Color(0xFFF0FDF4),
    textSecondaryColor = Color(0xFF94A3B8),
    borderColors = listOf(
        Color(0x662DD4BF),
        Color(0x402DD4BF),
        Color(0x662DD4BF)
    ),
    borderWidth = 1.8.dp,
    innerGlowColor = Color(0xFFFFFFFF),
    innerGlowAlpha = 0.15f,
    outerShadowColor = Color(0x4D000000),
    outerShadowElevation = 10.dp,
    cornerRadius = 24.dp,
    scrimAlpha = 0.15f,
    springMotionSpec = DarkGlassTokens.springMotionSpec,
    sheenColor = Color(0xFFFFFFFF),
    sheenAlpha = 0.15f,
    isLight = false,
    bgBase = Color(0xFF070D0C),
    bgGlow1 = Color(0x1F0D9488),
    bgGlow2 = Color(0x1A059669),
    cardBg = Color(0xB80F1917),
    cardBorder = Color(0x662DD4BF),
    controlBg = Color(0x0FFFFFFF),
    accentColor = Color(0xFF2DD4BF),
    accentBg = Color(0x262DD4BF),
    navBg = Color(0xE00A1210),
    dialogBg = Color(0xF80F1917),
    dialogScrim = Color.Black.copy(alpha = 0.70f),
)

/**
 * Sunset Amber specialized dark glass tokens.
 * Black-gold metallic and warm charcoal finish.
 */
val SunsetAmberGlassTokens = GlassTokens(
    blurRadius = 28.dp,
    surfaceAlpha = 0.75f,
    surfaceColor = Color(0xFF181514),
    textColor = Color(0xFFFAFAF9),
    textSecondaryColor = Color(0xFFA8A29E),
    borderColors = listOf(
        Color(0x66F59E0B),
        Color(0x40F59E0B),
        Color(0x66F59E0B)
    ),
    borderWidth = 1.8.dp,
    innerGlowColor = Color(0xFFFFFFFF),
    innerGlowAlpha = 0.15f,
    outerShadowColor = Color(0x4D000000),
    outerShadowElevation = 10.dp,
    cornerRadius = 24.dp,
    scrimAlpha = 0.15f,
    springMotionSpec = DarkGlassTokens.springMotionSpec,
    sheenColor = Color(0xFFFFFFFF),
    sheenAlpha = 0.15f,
    isLight = false,
    bgBase = Color(0xFF0C0A09),
    bgGlow1 = Color(0x1AD97706),
    bgGlow2 = Color(0x14E11D48),
    cardBg = Color(0xBF181514),
    cardBorder = Color(0x66F59E0B),
    controlBg = Color(0x0FFFFFFF),
    accentColor = Color(0xFFF59E0B),
    accentBg = Color(0x26F59E0B),
    navBg = Color(0xE012100F),
    dialogBg = Color(0xF8181514),
    dialogScrim = Color.Black.copy(alpha = 0.70f),
)
