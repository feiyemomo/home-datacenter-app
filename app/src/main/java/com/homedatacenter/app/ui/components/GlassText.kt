package com.homedatacenter.app.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.GlassTokens

/**
 * Text hierarchy types for GlassText design system.
 */
enum class GlassTextType {
    Title,       // High emphasis title (e.g. 18sp Bold)
    Body,        // Primary content text (e.g. 14sp Normal)
    Secondary,   // Subdued supporting text (e.g. 12sp Normal, secondary color)
    Caption,     // Small metadata / timestamp (e.g. 11sp)
    Header       // Large section header (e.g. 20sp ExtraBold)
}

/**
 * GlassText: Unified typography component strongly backed by [GlassTheme.tokens].
 * Automatically switches between Dark and Light tokens with WCAG AA compliance.
 */
@Composable
fun GlassText(
    text: String,
    modifier: Modifier = Modifier,
    type: GlassTextType = GlassTextType.Body,
    tokens: GlassTokens = GlassTheme.tokens,
    color: Color? = null,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
) {
    val resolvedColor = color ?: when (type) {
        GlassTextType.Header, GlassTextType.Title, GlassTextType.Body -> tokens.textColor
        GlassTextType.Secondary, GlassTextType.Caption -> tokens.textSecondaryColor
    }

    val resolvedFontSize = if (fontSize != TextUnit.Unspecified) fontSize else when (type) {
        GlassTextType.Header -> 22.sp
        GlassTextType.Title -> 16.sp
        GlassTextType.Body -> 14.sp
        GlassTextType.Secondary -> 12.sp
        GlassTextType.Caption -> 11.sp
    }

    val resolvedFontWeight = fontWeight ?: when (type) {
        GlassTextType.Header -> FontWeight.Bold
        GlassTextType.Title -> FontWeight.Bold
        GlassTextType.Body -> FontWeight.Normal
        GlassTextType.Secondary -> FontWeight.Normal
        GlassTextType.Caption -> FontWeight.Medium
    }

    Text(
        text = text,
        modifier = modifier,
        color = resolvedColor,
        fontSize = resolvedFontSize,
        fontStyle = fontStyle,
        fontWeight = resolvedFontWeight,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
    )
}
