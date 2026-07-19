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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.homedatacenter.app.data.model.Camera

private val CardBackground = Color(0xFF171C24)
private val FooterBackground = Color(0xFF202733)
private val OnlineColor = Color(0xFF35C98A)
private val OfflineColor = Color(0xFFE05B65)
private val AccentColor = Color(0xFF55A7FF)

@Composable
fun CameraCard(
    camera: Camera,
    thumbnail: Bitmap?,
    thumbnailLoading: Boolean,
    thumbnailError: Boolean,
    isPlaying: Boolean,
    playerLoading: Boolean,
    player: ExoPlayer?,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onRecordings: () -> Unit,
    onAlerts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
                .clickable(enabled = !isPlaying, onClick = onPlay),
        ) {
            if (isPlaying && player != null) {
                val context = LocalContext.current
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = {
                        StyledPlayerView(context).apply {
                            useController = true
                            this.player = player
                        }
                    },
                    update = { it.player = player },
                )
            } else if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = "${camera.name} camera preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }

            if (!isPlaying && thumbnail == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF11151B)),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        thumbnailLoading -> CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = Color.White,
                            strokeWidth = 3.dp,
                        )

                        thumbnailError -> Text(
                            text = "预览加载失败",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.42f))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = camera.name,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = camera.vendor.ifBlank { "Unknown" },
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusBadge(
                    text = camera.codec.ifBlank { "H264" }.uppercase(),
                    color = AccentColor,
                )
                Spacer(modifier = Modifier.size(7.dp))
                StatusBadge(
                    text = if (camera.isOnline) "在线" else "离线",
                    color = if (camera.isOnline) OnlineColor else OfflineColor,
                )
            }

            if (!isPlaying) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(58.dp)
                        .clickable(onClick = onPlay),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.62f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = "▶", color = Color.White, fontSize = 24.sp)
                    }
                }
            }

            if (isPlaying) {
                Button(
                    onClick = onStop,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black.copy(alpha = 0.68f),
                    ),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Text("关闭", color = Color.White, fontSize = 12.sp)
                }
            }

            if (playerLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(44.dp),
                    color = Color.White,
                    strokeWidth = 4.dp,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(FooterBackground)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onRecordings,
                modifier = Modifier.weight(1f),
            ) {
                Text("录像")
            }
            OutlinedButton(
                onClick = onAlerts,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = OfflineColor),
            ) {
                Text("报警")
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.88f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
