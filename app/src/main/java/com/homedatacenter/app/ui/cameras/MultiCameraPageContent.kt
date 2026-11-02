package com.homedatacenter.app.ui.cameras

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog as ComposeDialog
import androidx.compose.ui.window.DialogProperties
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

enum class MultiCameraGridMode(val slots: Int, val label: String, val width: Int, val quality: Int, val intervalMs: Long) {
    ONE(1, "1格", 720, 35, 100L),
    FOUR(4, "4格", 360, 25, 200L),
    NINE(9, "9格", 240, 18, 400L)
}

@Composable
fun MultiCameraPageContent(
    container: AppContainer,
    onBack: () -> Unit,
    onToggleOrientation: () -> Unit,
    onOpenCameraDetail: (Camera) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val cyberCyan = Color(0xFF38BDF8)
    val cardBg = Color(0xF0131927)
    val borderCol = Color(0x3338BDF8)
    val textPrimary = Color.White
    val textSecondary = Color(0xFF94A3B8)
    val onlineGreen = Color(0xFF10B981)
    val offlineRed = Color(0xFFEF4444)

    val scope = rememberCoroutineScope()
    var allCameras by remember { mutableStateOf<List<Camera>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    var gridMode by remember { mutableStateOf(MultiCameraGridMode.FOUR) }
    var prevGridMode by remember { mutableStateOf(MultiCameraGridMode.FOUR) }
    var focusedCamIndex by remember { mutableStateOf(0) }
    var isFillCrop by remember { mutableStateOf(false) }

    // Slot index -> Camera index in allCameras (-1 for empty)
    val slotMapping = remember { mutableStateMapOf<Int, Int>() }
    // Slot index -> current Bitmap frame
    val slotFrames = remember { mutableStateMapOf<Int, Bitmap?>() }
    // Slot index -> frame tick timestamp (ensures Compose detects every frame update)
    val slotFrameTicks = remember { mutableStateMapOf<Int, Long>() }

    var pickerSlotIndex by remember { mutableStateOf<Int?>(null) }

    fun refreshCameras() {
        val token = container.prefsManager.token ?: return
        isLoading = true
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    container.getRepository().listCameras(token, useCache = false, refreshCache = true)
                }
                allCameras = list
                for (i in 0 until 9) {
                    slotMapping[i] = if (i < list.size) i else -1
                }
                isLoading = false
            } catch (e: Exception) {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshCameras()
    }

    DisposableEffect(Unit) {
        onDispose {
            slotFrames.clear()
            slotFrameTicks.clear()
        }
    }

    // Frame Polling Loop
    LaunchedEffect(gridMode, focusedCamIndex, allCameras.size, slotMapping.values.toList()) {
        if (allCameras.isEmpty()) return@LaunchedEffect

        val activeSlotIndices = when (gridMode) {
            MultiCameraGridMode.ONE -> listOf(0)
            MultiCameraGridMode.FOUR -> listOf(0, 1, 2, 3)
            MultiCameraGridMode.NINE -> (0 until 9).toList()
        }

        val baseUrl = container.getApiBaseUrl().trimEnd('/')
        val token = container.prefsManager.token
        val client = container.okHttpClient

        activeSlotIndices.forEachIndexed { i, slotIdx ->
            launch(Dispatchers.IO) {
                delay(i * 60L)
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }

                while (isActive) {
                    val camIdx = if (gridMode == MultiCameraGridMode.ONE) focusedCamIndex else slotMapping[slotIdx] ?: -1
                    val cam = if (camIdx >= 0) allCameras.getOrNull(camIdx) else null
                    if (cam != null) {
                        try {
                            val activeBase = container.getApiBaseUrl().trimEnd('/')
                            val ts = System.currentTimeMillis()
                            val frameUrl = "$activeBase/api/v1/cameras/${cam.id}/frame?width=${gridMode.width}&quality=${gridMode.quality}&nocache=1&t=$ts"
                            val req = Request.Builder().url(frameUrl).apply {
                                if (!token.isNullOrEmpty()) {
                                    header("Authorization", "Bearer $token")
                                    header("Cookie", "home_token=$token")
                                }
                            }.build()

                            client.newCall(req).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    resp.body?.byteStream()?.use { stream ->
                                        val bmp = BitmapFactory.decodeStream(stream, null, opts)
                                        if (bmp != null && isActive) {
                                            slotFrames[slotIdx] = bmp
                                            slotFrameTicks[slotIdx] = ts
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                        }
                    }
                    delay(gridMode.intervalMs)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0F172A),
                        Color(0xFF131A2E),
                        Color(0xFF0A0F1D)
                    )
                )
            )
    ) {
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.height(statusBarTop))
            // Header Toolbar
            Surface(
                color = Color(0x990F172A),
                border = BorderStroke(1.dp, Color(0x2238BDF8)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_baseline_arrow_back_24),
                            contentDescription = "返回",
                            tint = textPrimary
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "多路监控大厅",
                            color = textPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${gridMode.label} · ${if (isFillCrop) "铺满" else "适应"}",
                            color = textSecondary,
                            fontSize = 11.sp
                        )
                    }

                    // Mode Toggle Pill
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0x331E293B),
                        border = BorderStroke(1.dp, Color(0x3338BDF8)),
                        modifier = Modifier
                            .clickable {
                                gridMode = when (gridMode) {
                                    MultiCameraGridMode.ONE -> MultiCameraGridMode.FOUR
                                    MultiCameraGridMode.FOUR -> MultiCameraGridMode.NINE
                                    MultiCameraGridMode.NINE -> MultiCameraGridMode.ONE
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_grid_view),
                                contentDescription = null,
                                tint = cyberCyan,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(gridMode.label, color = cyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Aspect Ratio Pill
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0x331E293B),
                        border = BorderStroke(1.dp, Color(0x3338BDF8)),
                        modifier = Modifier
                            .clickable { isFillCrop = !isFillCrop }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_aspect_ratio),
                                contentDescription = null,
                                tint = textPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isFillCrop) "铺满" else "适应", color = textPrimary, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Landscape Toggle Pill
                    IconButton(onClick = onToggleOrientation) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_fullscreen),
                            contentDescription = "横屏",
                            tint = textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Grid Content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = cyberCyan)
                } else if (allCameras.isEmpty()) {
                    Text("暂无可用摄像头", color = textSecondary, fontSize = 14.sp)
                } else {
                    when (gridMode) {
                        MultiCameraGridMode.ONE -> {
                            val cam = allCameras.getOrNull(focusedCamIndex)
                            SlotTile(
                                camera = cam,
                                bitmap = slotFrames[0],
                                frameTick = slotFrameTicks[0] ?: 0L,
                                isFillCrop = isFillCrop,
                                modifier = Modifier.fillMaxSize(),
                                onSingleTap = { cam?.let { onOpenCameraDetail(it) } },
                                onDoubleTap = {
                                    gridMode = prevGridMode
                                },
                                onLongPress = { pickerSlotIndex = 0 }
                            )
                        }
                        MultiCameraGridMode.FOUR -> {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (row in 0..1) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        for (col in 0..1) {
                                            val slotIdx = row * 2 + col
                                            val camIdx = slotMapping[slotIdx] ?: -1
                                            val cam = if (camIdx >= 0) allCameras.getOrNull(camIdx) else null
                                            SlotTile(
                                                camera = cam,
                                                bitmap = slotFrames[slotIdx],
                                                frameTick = slotFrameTicks[slotIdx] ?: 0L,
                                                isFillCrop = isFillCrop,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxSize(),
                                                onSingleTap = { cam?.let { onOpenCameraDetail(it) } },
                                                onDoubleTap = {
                                                    if (camIdx >= 0) {
                                                        prevGridMode = gridMode
                                                        focusedCamIndex = camIdx
                                                        slotFrames[slotIdx]?.let { slotFrames[0] = it }
                                                        gridMode = MultiCameraGridMode.ONE
                                                    }
                                                },
                                                onLongPress = { pickerSlotIndex = slotIdx }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        MultiCameraGridMode.NINE -> {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                for (row in 0..2) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        for (col in 0..2) {
                                            val slotIdx = row * 3 + col
                                            val camIdx = slotMapping[slotIdx] ?: -1
                                            val cam = if (camIdx >= 0) allCameras.getOrNull(camIdx) else null
                                            SlotTile(
                                                camera = cam,
                                                bitmap = slotFrames[slotIdx],
                                                frameTick = slotFrameTicks[slotIdx] ?: 0L,
                                                isFillCrop = isFillCrop,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxSize(),
                                                onSingleTap = { cam?.let { onOpenCameraDetail(it) } },
                                                onDoubleTap = {
                                                    if (camIdx >= 0) {
                                                        prevGridMode = gridMode
                                                        focusedCamIndex = camIdx
                                                        slotFrames[slotIdx]?.let { slotFrames[0] = it }
                                                        gridMode = MultiCameraGridMode.ONE
                                                    }
                                                },
                                                onLongPress = { pickerSlotIndex = slotIdx }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 1-Grid bottom switcher pill
            if (gridMode == MultiCameraGridMode.ONE && allCameras.size > 1) {
                Surface(
                    color = Color(0x990F172A),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                focusedCamIndex = (focusedCamIndex - 1 + allCameras.size) % allCameras.size
                            }
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_baseline_arrow_back_24),
                                contentDescription = "上一路",
                                tint = cyberCyan
                            )
                        }

                        val curCam = allCameras.getOrNull(focusedCamIndex)
                        Text(
                            text = "${curCam?.name ?: "摄像头"} (${focusedCamIndex + 1}/${allCameras.size})",
                            color = textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { pickerSlotIndex = 0 }
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )

                        IconButton(
                            onClick = {
                                focusedCamIndex = (focusedCamIndex + 1) % allCameras.size
                            }
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_chevron_right),
                                contentDescription = "下一路",
                                tint = cyberCyan
                            )
                        }
                    }
                }
            }
        }
    }

    // Camera Picker Dialog
    if (pickerSlotIndex != null) {
        val slot = pickerSlotIndex!!
        ComposeDialog(
            onDismissRequest = { pickerSlotIndex = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cardBg,
                border = BorderStroke(1.dp, borderCol),
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("选择摄像头", color = textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(
                        modifier = Modifier.height(240.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(allCameras.indices.toList()) { idx ->
                            val c = allCameras[idx]
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0x221E293B),
                                border = BorderStroke(1.dp, Color(0x2238BDF8)),
                                onClick = {
                                    if (gridMode == MultiCameraGridMode.ONE) {
                                        focusedCamIndex = idx
                                    } else {
                                        slotMapping[slot] = idx
                                    }
                                    pickerSlotIndex = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(c.name, color = textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                    Text(
                                        if (c.status == "online") "在线" else "离线",
                                        color = if (c.status == "online") onlineGreen else offlineRed,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { pickerSlotIndex = null }) {
                            Text("关闭", color = textSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SlotTile(
    camera: Camera?,
    bitmap: Bitmap?,
    isFillCrop: Boolean,
    modifier: Modifier = Modifier,
    frameTick: Long = 0L,
    onSingleTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val cyberCyan = Color(0xFF38BDF8)
    val cardBg = Color(0xFF0F172A)
    val borderCol = Color(0x3338BDF8)
    val onlineGreen = Color(0xFF10B981)
    val offlineRed = Color(0xFFEF4444)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cardBg,
        border = BorderStroke(1.dp, borderCol),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(camera?.id) {
                detectTapGestures(
                    onTap = {
                        if (camera != null) onSingleTap() else onLongPress()
                    },
                    onDoubleTap = { onDoubleTap() },
                    onLongPress = { onLongPress() }
                )
            }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (camera != null) {
                if (bitmap != null && !bitmap.isRecycled) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = camera.name,
                        contentScale = if (isFillCrop) ContentScale.Crop else ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = cyberCyan, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }

                // Overlay Tag
                Surface(
                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                    color = Color(0xB3000000),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(if (camera.status == "online") onlineGreen else offlineRed, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = camera.name,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+ 点击配置摄像头",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
