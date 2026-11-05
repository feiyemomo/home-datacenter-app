package com.homedatacenter.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homedatacenter.app.R
import com.homedatacenter.app.ui.glass.AppThemePreset
import com.homedatacenter.app.ui.glass.GlassTheme

/**
 * GlassThemeSelector — Interactive visual preset theme switcher.
 *
 * Displays visual swatch cards for all registered [AppThemePreset] themes.
 * Smoothly selects and previews fluid gradient blobs and specular border styles.
 */
@Composable
fun GlassThemeSelector(
    currentPreset: AppThemePreset,
    onPresetSelect: (AppThemePreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AppThemePreset.entries.forEach { preset ->
            val isSelected = preset == currentPreset

            val borderColor by animateColorAsState(
                targetValue = if (isSelected) tokens.accentColor else tokens.cardBorder,
                label = "preset_border_${preset.presetId}"
            )

            val bgColor by animateColorAsState(
                targetValue = if (isSelected) {
                    tokens.accentBg
                } else {
                    tokens.controlBg
                },
                label = "preset_bg_${preset.presetId}"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(bgColor)
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = borderColor,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable { onPresetSelect(preset) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Fluid Color Swatch (Mini circular mesh preview)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(preset.fluidBaseColor)
                            .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier.padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            preset.fluidBlobColors.take(3).forEach { blobColor ->
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(blobColor.copy(alpha = 0.9f))
                                )
                            }
                        }
                    }

                    Column {
                        Text(
                            text = preset.displayName,
                            fontSize = 15.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = tokens.textColor
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = preset.subtitle,
                            fontSize = 11.sp,
                            color = tokens.textSecondaryColor
                        )
                    }
                }

                // Check indicator
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) tokens.accentColor
                            else tokens.controlBg
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) tokens.accentColor else tokens.cardBorder,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_check),
                            contentDescription = "Selected",
                            tint = Color.White,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}
