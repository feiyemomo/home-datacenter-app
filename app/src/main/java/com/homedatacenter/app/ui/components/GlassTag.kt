package com.homedatacenter.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.GlassTokens

/**
 * Semantic purpose of the badge capsule.
 */
enum class GlassTagType {
    Info,       // Codec (H264/HEVC), theme-accent metadata
    Audio,      // Microphone active - emerald online tint
    Success,    // Online status, active connected
    Warning,    // Pending
    Error,      // Offline, intrusion alert
    Neutral     // Muted audio, disabled
}

/**
 * GlassTag: Specialized capsule badge component for status indicators and codecs.
 *
 * Design Guidelines:
 * - Metadata badges (Info/Neutral e.g. H264, 音频, 96%): var(--control-bg) background,
 *   var(--text-secondary) font, borderless.
 * - Online status badge: rgba(0, 0, 0, 0.6) background on dark surface + 6px green micro-dot + pure white text.
 * - Eliminates oversaturated glowing borders and multi-color visual noise.
 */
@Composable
fun GlassTag(
    text: String,
    modifier: Modifier = Modifier,
    type: GlassTagType = GlassTagType.Info,
    tokens: GlassTokens = GlassTheme.tokens,
    onDarkSurface: Boolean = false,
) {
    val style = when (type) {
        GlassTagType.Success -> {
            if (onDarkSurface) {
                // Video / thumbnail overlay online indicator: rgba(0, 0, 0, 0.6) + 6px green dot + pure white text
                TagStyle(Color.White, Color(0x99000000), dotColor = tokens.statusOnline)
            } else {
                TagStyle(tokens.textColor, tokens.controlBg, dotColor = tokens.statusOnline)
            }
        }
        GlassTagType.Error -> {
            if (onDarkSurface) {
                TagStyle(Color.White, Color(0x99000000), dotColor = tokens.statusDanger)
            } else {
                TagStyle(tokens.textColor, tokens.controlBg, dotColor = tokens.statusDanger)
            }
        }
        GlassTagType.Warning -> {
            // Subtle warning capsule
            TagStyle(tokens.statusWarning, Color(0x1FF59E0B))
        }
        GlassTagType.Info -> {
            // Codec tag: Theme-accent capsule with theme accent text and border
            TagStyle(tokens.accentColor, tokens.accentBg, borderColor = tokens.accentColor.copy(alpha = 0.25f))
        }
        GlassTagType.Audio -> {
            // Active microphone tag: deep emerald in light mode (WCAG AA >= 4.5:1), radiant emerald in dark mode
            val audioTextColor = if (tokens.isLight) Color(0xFF047857) else tokens.statusOnline
            TagStyle(audioTextColor, tokens.statusOnline.copy(alpha = 0.14f), borderColor = tokens.statusOnline.copy(alpha = 0.25f))
        }
        GlassTagType.Neutral -> {
            // Standard metadata: var(--control-bg) background, var(--text-secondary) text, borderless
            if (onDarkSurface) {
                TagStyle(Color.White.copy(alpha = 0.9f), Color(0x80000000))
            } else {
                TagStyle(tokens.textSecondaryColor, tokens.controlBg)
            }
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = style.backgroundColor,
        border = style.borderColor?.let { androidx.compose.foundation.BorderStroke(1.dp, it) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.5.dp, vertical = 2.5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (style.dotColor != null) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(style.dotColor)
                )
            }
            Text(
                text = text,
                color = style.textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class TagStyle(
    val textColor: Color,
    val backgroundColor: Color,
    val dotColor: Color? = null,
    val borderColor: Color? = null,
)
