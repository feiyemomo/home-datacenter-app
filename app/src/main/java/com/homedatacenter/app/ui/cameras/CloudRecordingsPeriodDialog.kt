package com.homedatacenter.app.ui.cameras

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.data.model.Recording
import com.homedatacenter.app.ui.components.GlassActionButton
import com.homedatacenter.app.ui.components.GlassTag
import com.homedatacenter.app.ui.components.GlassTagType
import com.homedatacenter.app.ui.glass.AmbientFluidBackground
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.GlassTokens
import com.homedatacenter.app.ui.glass.ProvideGlassTheme
import com.homedatacenter.app.util.ThemeManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Represents a continuous period of cloud recordings (grouped from contiguous 60s clips).
 */
data class RecordingPeriod(
    val id: Long,
    val startUnix: Long,
    val endUnix: Long,
    val startTimeStr: String,
    val endTimeStr: String,
    val durationSeconds: Int,
    val durationFormatted: String,
    val totalSizeBytes: Long,
    val sizeHuman: String,
    val hourOfDay: Int,
    val clips: List<Recording>
)

/**
 * CloudRecordingsPeriodDialog: Liquid Glass period selector for cloud cameras (Dagu 4G / Lanzou).
 * Displays a 24-hour visual distribution timeline and continuous recording period cards so
 * users can clearly see available time periods and start playback with a single tap.
 */
class CloudRecordingsPeriodDialog(
    context: Context,
    private val camera: Camera,
    private val dateCalendar: Calendar,
    private val recordings: List<Recording>,
    private val onPlayClips: (clips: List<Recording>, startIndex: Int) -> Unit
) : AppCompatDialog(context, R.style.FullScreenDialog) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                val preset by ThemeManager.themePresetFlow.collectAsState()
                ProvideGlassTheme(preset = preset) {
                    CloudRecordingsPeriodContent(
                        camera = camera,
                        dateCalendar = dateCalendar,
                        recordings = recordings,
                        onDismiss = { dismiss() },
                        onPlayClips = { clips, startIndex ->
                            dismiss()
                            onPlayClips(clips, startIndex)
                        }
                    )
                }
            }
        }
        setContentView(composeView)
    }
}

@Composable
fun CloudRecordingsPeriodContent(
    camera: Camera,
    dateCalendar: Calendar,
    recordings: List<Recording>,
    onDismiss: () -> Unit,
    onPlayClips: (clips: List<Recording>, startIndex: Int) -> Unit
) {
    val tokens = GlassTheme.tokens
    val textPrimary = tokens.textColor
    val textSecondary = tokens.textSecondaryColor

    val tz = remember { TimeZone.getTimeZone("Asia/Shanghai") }
    val parseFmt = remember {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
    val timeFmt = remember {
        SimpleDateFormat("HH:mm:ss", Locale.CHINA).apply {
            timeZone = tz
        }
    }
    val dateStr = remember(dateCalendar) {
        String.format(
            Locale.US,
            "%04d-%02d-%02d",
            dateCalendar.get(Calendar.YEAR),
            dateCalendar.get(Calendar.MONTH) + 1,
            dateCalendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    val isDagu = remember(camera) {
        camera.vendor.contains("dagu", ignoreCase = true) ||
            camera.vendor.contains("daguiot", ignoreCase = true) ||
            camera.name.contains("大谷")
    }
    val brandTitle = if (isDagu) "大谷云端录像" else "云端归档录像"

    // Group sorted recordings into continuous time periods
    val periods = remember(recordings) {
        groupClipsIntoPeriods(recordings, parseFmt, timeFmt, tz)
    }

    val totalDurationSeconds = remember(recordings) { recordings.sumOf { it.durationSeconds } }
    val totalDurationStr = remember(totalDurationSeconds) {
        if (totalDurationSeconds >= 3600) {
            "${totalDurationSeconds / 3600}小时${(totalDurationSeconds % 3600) / 60}分钟"
        } else {
            "${maxOf(1, totalDurationSeconds / 60)}分钟"
        }
    }

    // Available hours filter
    val availableHours = remember(periods) {
        periods.map { it.hourOfDay }.distinct().sorted()
    }
    var selectedHourFilter by remember { mutableStateOf<Int?>(-1) } // -1 means All

    val filteredPeriods = remember(periods, selectedHourFilter) {
        if (selectedHourFilter == null || selectedHourFilter == -1) {
            periods
        } else {
            periods.filter { it.hourOfDay == selectedHourFilter }
        }
    }

    val preset by ThemeManager.themePresetFlow.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        AmbientFluidBackground(
            modifier = Modifier.fillMaxSize(),
            preset = preset
        )

        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Top Bar
            Surface(
                color = tokens.dialogBg,
                border = BorderStroke(1.dp, tokens.cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_baseline_arrow_back_24),
                            contentDescription = "返回",
                            tint = textPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${camera.name} · $brandTitle",
                                color = textPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            GlassTag(
                                text = if (isDagu) "大谷 4G" else "云存储",
                                type = GlassTagType.Info
                            )
                        }
                        Text(
                            text = "$dateStr (共 ${recordings.size} 段 · $totalDurationStr)",
                            color = textSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                }
            }

            // 2. 24-Hour Visual Panorama Timeline Bar Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = tokens.cardBg,
                border = BorderStroke(1.dp, tokens.cardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "全天 24 小时录像分布全景",
                            color = textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "彩色区间为可用录像",
                            color = tokens.accentColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 24h Timeline Bar
                    val dayStartMs = remember(dateCalendar) { dateCalendar.timeInMillis }
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x33000000))
                            .border(BorderStroke(1.dp, tokens.cardBorder), RoundedCornerShape(8.dp))
                    ) {
                        val totalWidth = maxWidth
                        periods.forEach { period ->
                            val sMs = period.startUnix * 1000L
                            val eMs = period.endUnix * 1000L
                            val startFraction = ((sMs - dayStartMs).toFloat() / 86400000f).coerceIn(0f, 1f)
                            val endFraction = ((eMs - dayStartMs).toFloat() / 86400000f).coerceIn(0f, 1f)
                            val widthFraction = maxOf(0.015f, endFraction - startFraction)

                            val leftOffset = totalWidth * startFraction
                            val segWidth = maxOf(4.dp, totalWidth * widthFraction)

                            Box(
                                modifier = Modifier
                                    .padding(start = leftOffset)
                                    .width(segWidth)
                                    .fillMaxHeight()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(tokens.accentColor)
                            )
                        }
                    }

                    // Timeline Rulers
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEach { label ->
                            Text(text = label, color = textSecondary, fontSize = 10.sp)
                        }
                    }
                }
            }

            // 3. Hour Filter Chips
            if (availableHours.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedHourFilter == -1,
                        onClick = { selectedHourFilter = -1 },
                        label = { Text("全部时段 (${recordings.size}段)", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = tokens.accentBg,
                            selectedLabelColor = tokens.accentColor,
                            containerColor = tokens.controlBg,
                            labelColor = textSecondary
                        ),
                        border = BorderStroke(1.dp, tokens.cardBorder)
                    )

                    availableHours.forEach { h ->
                        val count = periods.filter { it.hourOfDay == h }.sumOf { it.clips.size }
                        val labelStr = String.format(Locale.US, "%02d:00 (%d段)", h, count)
                        FilterChip(
                            selected = selectedHourFilter == h,
                            onClick = { selectedHourFilter = h },
                            label = { Text(labelStr, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = tokens.accentBg,
                                selectedLabelColor = tokens.accentColor,
                                containerColor = tokens.controlBg,
                                labelColor = textSecondary
                            ),
                            border = BorderStroke(1.dp, tokens.cardBorder)
                        )
                    }
                }
            }

            // 4. Periods & Clips List
            if (filteredPeriods.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("该时段无可用录像", color = textSecondary, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredPeriods, key = { it.id }) { period ->
                        PeriodCard(
                            period = period,
                            tokens = tokens,
                            textPrimary = textPrimary,
                            textSecondary = textSecondary,
                            onPlayPeriod = { onPlayClips(period.clips, 0) },
                            onPlayClip = { clipIdx -> onPlayClips(period.clips, clipIdx) },
                            timeFmt = timeFmt,
                            parseFmt = parseFmt
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun PeriodCard(
    period: RecordingPeriod,
    tokens: GlassTokens,
    textPrimary: Color,
    textSecondary: Color,
    onPlayPeriod: () -> Unit,
    onPlayClip: (clipIndex: Int) -> Unit,
    timeFmt: SimpleDateFormat,
    parseFmt: SimpleDateFormat
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = tokens.cardBg,
        border = BorderStroke(1.dp, tokens.cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header row: Green dot + Time Range + Duration Tag
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF10B981),
                    modifier = Modifier.size(8.dp)
                ) {}
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${period.startTimeStr} - ${period.endTimeStr}",
                    color = textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                GlassTag(text = period.durationFormatted, type = GlassTagType.Info)
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Subtitle: Clip count and storage size
            Text(
                text = "连续录像 · 共 ${period.clips.size} 个片段 · 大小约 ${period.sizeHuman}",
                color = textSecondary,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Actions row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.padding(0.dp)
                ) {
                    Text(
                        text = if (expanded) "收起片段列表 ▲" else "查看片段明细 (${period.clips.size}) ▼",
                        color = tokens.accentColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                GlassActionButton(
                    text = "▶ 播放此时间段",
                    onClick = onPlayPeriod
                )
            }

            // Expanded Clips List
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0x22000000),
                    border = BorderStroke(1.dp, tokens.cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val nowMs = System.currentTimeMillis()
                        period.clips.forEachIndexed { idx, clip ->
                            val clipStart = try {
                                parseFmt.parse(clip.startAt)?.let { parsed ->
                                    val t = if (parsed.time > nowMs + 3600_000L) parsed.time - 8 * 3600 * 1000L else parsed.time
                                    timeFmt.format(Date(t))
                                } ?: clip.startAt
                            } catch (_: Exception) { clip.startAt }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onPlayClip(idx) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${idx + 1}. $clipStart",
                                        color = textPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${clip.durationSeconds}s",
                                        color = textSecondary,
                                        fontSize = 11.sp
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = clip.sizeHuman,
                                        color = textSecondary,
                                        fontSize = 11.sp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "播放",
                                        color = tokens.accentColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Groups raw recording clips into continuous time periods based on gap threshold (<= 180s).
 */
private fun groupClipsIntoPeriods(
    recordings: List<Recording>,
    parseFmt: SimpleDateFormat,
    timeFmt: SimpleDateFormat,
    tz: TimeZone
): List<RecordingPeriod> {
    if (recordings.isEmpty()) return emptyList()
    val sorted = recordings.sortedBy { it.id }
    val result = mutableListOf<RecordingPeriod>()
    var currentClips = mutableListOf<Recording>()
    var pStartMs = -1L
    var pEndMs = -1L

    val nowMs = System.currentTimeMillis()

    for (rec in sorted) {
        var s = try { parseFmt.parse(rec.startAt)?.time ?: -1L } catch (_: Exception) { -1L }
        if (s <= 0L) continue
        // Defensive normalization: if recording timestamp is in the future (> now + 1h),
        // it carries unadjusted camera local time (+8h / 28800s); normalize by -8 hours.
        if (s > nowMs + 3600_000L) {
            s -= 8 * 3600 * 1000L
        }
        var e = try {
            parseFmt.parse(rec.endAt)?.time ?: (s + rec.durationSeconds * 1000L)
        } catch (_: Exception) { s + rec.durationSeconds * 1000L }
        if (e > nowMs + 3600_000L) {
            e -= 8 * 3600 * 1000L
        }
        if (e < s) e = s + rec.durationSeconds * 1000L

        if (currentClips.isEmpty()) {
            currentClips.add(rec)
            pStartMs = s
            pEndMs = e
        } else {
            // If contiguous or gap <= 3 minutes (180s), merge into same period
            if (s - pEndMs <= 180_000L) {
                currentClips.add(rec)
                pEndMs = maxOf(pEndMs, e)
            } else {
                result.add(createPeriod(currentClips, pStartMs, pEndMs, timeFmt, tz))
                currentClips = mutableListOf(rec)
                pStartMs = s
                pEndMs = e
            }
        }
    }
    if (currentClips.isNotEmpty()) {
        result.add(createPeriod(currentClips, pStartMs, pEndMs, timeFmt, tz))
    }
    return result
}

private fun createPeriod(
    clips: List<Recording>,
    startMs: Long,
    endMs: Long,
    timeFmt: SimpleDateFormat,
    tz: TimeZone
): RecordingPeriod {
    val durSec = ((endMs - startMs) / 1000L).toInt().coerceAtLeast(clips.sumOf { it.durationSeconds })
    val durFormatted = if (durSec >= 3600) {
        "${durSec / 3600}小时${(durSec % 3600) / 60}分"
    } else {
        "${maxOf(1, durSec / 60)}分钟"
    }

    val cal = Calendar.getInstance(tz).apply { timeInMillis = startMs }
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val totalBytes = clips.sumOf { it.sizeBytes }
    val sizeHuman = if (totalBytes >= 1024 * 1024) {
        String.format(Locale.US, "%.1fMB", totalBytes / (1024.0 * 1024.0))
    } else {
        String.format(Locale.US, "%dKB", totalBytes / 1024)
    }

    return RecordingPeriod(
        id = clips.first().id,
        startUnix = startMs / 1000L,
        endUnix = endMs / 1000L,
        startTimeStr = timeFmt.format(Date(startMs)),
        endTimeStr = timeFmt.format(Date(endMs)),
        durationSeconds = durSec,
        durationFormatted = durFormatted,
        totalSizeBytes = totalBytes,
        sizeHuman = sizeHuman,
        hourOfDay = hour,
        clips = clips
    )
}
