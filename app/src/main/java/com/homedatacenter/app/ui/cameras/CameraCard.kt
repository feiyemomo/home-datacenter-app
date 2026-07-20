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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homedatacenter.app.data.model.Camera

private val CardBackground = Color(0xFF171C24)
private val OnlineColor = Color(0xFF35C98A)
private val OfflineColor = Color(0xFFE05B65)
private val AccentColor = Color(0xFF55A7FF)

/**
 * Compact camera list card (v1.5.2 redesign).
 *
 * Layout:
 *   ┌──────────────────────────────────────────────────────┐
 *   │ ┌──────────┐  Camera Name                       ›    │
 *   │ │ thumb    │  Vendor · codec                       │
 *   │ │ 16:9     │  ● 在线  H264                          │
 *   │ └──────────┘                                        │
 *   └──────────────────────────────────────────────────────┘
 *
 * Tap anywhere on the card → [onClick] (opens CameraDetailActivity,
 * which contains the live video player, recordings dialog, alerts
 * dialog, and PTZ controls).
 *
 * The inline playback / settings gear / recordings+alerts footer
 * that lived in the previous card design have been moved to the
 * detail page so the list stays compact and scannable.
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
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail (16:9, fixed 128x72 area)
            Box(
                modifier = Modifier
                    .size(width = 128.dp, height = 72.dp)
                    .background(Color.Black, RoundedCornerShape(8.dp)),
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
                                .size(22.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    }
                    thumbnailError -> {
                        Text(
                            text = "无预览",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }

                // Online indicator dot on the thumbnail's bottom-left
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = if (camera.isOnline) OnlineColor else OfflineColor,
                ) {
                    Text(
                        text = if (camera.isOnline) "在线" else "离线",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.size(12.dp))

            // Camera name + vendor + codec badges
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = camera.name,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = camera.vendor.ifBlank { "Unknown" },
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.size(6.dp))
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
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.88f),
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
