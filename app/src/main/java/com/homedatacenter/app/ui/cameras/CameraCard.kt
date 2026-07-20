package com.homedatacenter.app.ui.cameras

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homedatacenter.app.data.model.Camera

// v1.6.4 rev6: warm liquid glass palette. Replaced the cold dark
// CardBackground (#171C24) with warm cream + warm amber accent so
// the camera list matches the user's "液态玻璃，温馨" design
// constraint. The glass effect comes from the layered translucent
// surface (CardBackground via the Card's containerColor) over the
// warm bg_primary sheet of fragment_cameras.xml — the 95% white
// over warm cream produces the frosted-glass look.
private val CardBackground = Color(0xF2FFFFFF)     // 95% warm white
private val CardBorder    = Color(0x66FFD4B8)      // warm peach edge
private val TextPrimary   = Color(0xFF2D3748)      // deep slate (warm-toned)
private val OnlineColor   = Color(0xFF35C98A)
private val OfflineColor  = Color(0xFFE05B65)
private val AccentColor   = Color(0xFFFF8A65)      // coral — matches the app's primary

/**
 * v1.6.4 rev6: redesigned camera list card.
 *
 * User asked: "卡片放大些，缩略图放大一下，文字占比可以少一些".
 *
 * Layout (horizontal — bigger thumbnail on the left, slim text
 * column on the right):
 *
 *   ┌──────────────────────────────────────────────┐
 *   │ ┌────────────┐                                │
 *   │ │ thumbnail  │  Camera Name                   │
 *   │ │ 192×108    │  [H264] [音频]                  │
 *   │ │ ● 在线     │                                │
 *   │ └────────────┘                                │
 *   └──────────────────────────────────────────────┘
 *
 *   - Card height: 132dp (was 84dp)
 *   - Thumbnail: 192×108dp (was 128×72dp) — 2.25× area
 *   - Text: one row (name) + one row (codec/audio badges) — vendor
 *     line removed entirely
 *   - Liquid glass: warm cream surface + peach border
 *
 * Tap anywhere on the card → [onClick] (opens CameraDetailActivity).
 */
@Composable
fun CameraCard(
    camera: Camera,
    thumbnail: Bitmap?,
    thumbnailLoading: Boolean,
    thumbnailError: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = androidx.compose.foundation.BorderStroke(1.2.dp, CardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        // v1.6.4 rev6: horizontal layout — bigger thumbnail on the
        // left, slim text column on the right. Height fixed at 132dp
        // (108 thumbnail + 12 padding top + 12 padding bottom = 132),
        // so the row is taller than the previous 84dp card but
        // still compact enough to show 4-5 cards on a typical screen.
        // The thumbnail is now 1.78× taller and 1.5× wider than the
        // previous 128×72 — easily big enough to read motion at a
        // glance without opening the detail page.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail (16:9, 192×108dp area — was 128×72).
            Box(
                modifier = Modifier
                    .size(width = 192.dp, height = 108.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black),
            ) {
                when {
                    thumbnail != null -> {
                        Image(
                            bitmap = thumbnail.asImageBitmap(),
                            contentDescription = "${camera.name} preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    thumbnailLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(26.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    }
                    thumbnailError -> {
                        Text(
                            text = "无预览",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }

                // Online indicator pill (bottom-left of thumbnail).
                // Slightly enlarged from 9sp → 10sp + thicker padding
                // since the thumbnail is now bigger and the previous
                // pill looked cramped.
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = if (camera.isOnline) OnlineColor else OfflineColor,
                ) {
                    Text(
                        text = if (camera.isOnline) "在线" else "离线",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.size(14.dp))

            // v1.6.4 rev6: text column — vendor line removed
            // entirely (user said "文字占比可以少一些"), so only
            // the camera name + codec/audio badges remain.
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = camera.name,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusBadge(
                        text = camera.codec.ifBlank { "H264" }.uppercase(),
                        color = AccentColor,
                    )
                    if (camera.hasAudio) {
                        StatusBadge(
                            text = "音频",
                            color = OnlineColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.92f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
