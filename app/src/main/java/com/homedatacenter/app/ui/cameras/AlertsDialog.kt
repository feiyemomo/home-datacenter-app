package com.homedatacenter.app.ui.cameras

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import com.homedatacenter.app.ui.glass.AmbientFluidBackground
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.ProvideGlassTheme
import com.homedatacenter.app.util.ThemeManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.Alert
import com.homedatacenter.app.data.model.AlertListData
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.ui.alerts.AlertSnapshotDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pure Jetpack Compose Alerts Dialog with Liquid Glass styling.
 * Eliminates legacy dialog_alerts.xml, item_alert.xml, and AlertListAdapter.
 */
class AlertsDialog(
    context: Context,
    private val camera: Camera,
    private val container: AppContainer
) : AppCompatDialog(context, R.style.FullScreenDialog) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawableResource(R.color.bg_primary)
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                val preset by ThemeManager.themePresetFlow.collectAsState()
                ProvideGlassTheme(preset = preset) {
                    AlertsDialogContent(
                        camera = camera,
                        container = container,
                        onDismiss = { dismiss() },
                        preset = preset,
                        onOpenRecording = { ts ->
                            val act = context as? CameraDetailActivity
                            if (act != null) {
                                dismiss()
                                act.showRecordings(ts)
                            } else {
                                dismiss()
                                RecordingsDialog(
                                    context = context,
                                    camera = camera,
                                    container = container,
                                    initialTimestamp = ts
                                ).show()
                            }
                        }
                    )
                }
            }
        }
        setContentView(composeView)
    }

    fun onBackPressedCustom(): Boolean {
        return false
    }
}

@Composable
fun AlertsDialogContent(
    camera: Camera,
    container: AppContainer,
    onDismiss: () -> Unit,
    onOpenRecording: (Long) -> Unit,
    preset: com.homedatacenter.app.ui.glass.AppThemePreset = com.homedatacenter.app.ui.glass.AppThemePreset.WarmLight
) {
    val context = LocalContext.current
    val baseUrl = remember { container.getApiBaseUrl() }
    val token = remember { container.prefsManager.token }
    val okHttpClient = remember { container.okHttpClient }

    val tokens = GlassTheme.tokens
    val textPrimary = tokens.textColor
    val textSecondary = tokens.textSecondaryColor
    val accent = tokens.accentColor

    var allAlerts by remember { mutableStateOf<List<Alert>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedCategory by remember { mutableStateOf("全部") }

    val scope = rememberCoroutineScope()

    fun loadAlerts() {
        isLoading = true
        errorMessage = null
        scope.launch {
            try {
                val authHeader = if (!token.isNullOrEmpty()) "Bearer $token" else ""
                val resp = withContext(Dispatchers.IO) {
                    container.getApi().listAlerts(authHeader, limit = 50, cameraId = camera.id)
                }
                val rawAlerts = if (resp.isSuccess) {
                    resp.decodeData<AlertListData>()?.alerts ?: emptyList()
                } else {
                    emptyList()
                }

                // Strict Camera Matching
                val expectedName = camera.name.trim()
                val matched = rawAlerts.filter { alert ->
                    if (!alert.cameraName.isNullOrBlank() && !alert.cameraName.equals(expectedName, ignoreCase = true)) {
                        return@filter false
                    }
                    if (alert.cameraId != null && alert.cameraId > 0 && alert.cameraId != camera.id) {
                        return@filter false
                    }
                    if (alert.cameraId != null && alert.cameraId == camera.id) {
                        return@filter true
                    }
                    if (!alert.cameraName.isNullOrBlank() && alert.cameraName.equals(expectedName, ignoreCase = true)) {
                        return@filter true
                    }
                    true
                }
                allAlerts = matched
                isLoading = false
            } catch (e: Exception) {
                errorMessage = "加载失败: ${e.message}"
                isLoading = false
            }
        }
    }

    LaunchedEffect(camera.id) {
        loadAlerts()
    }

    val filteredAlerts = remember(allAlerts, selectedCategory) {
        when (selectedCategory) {
            "人形" -> allAlerts.filter { it.label.equals("person", ignoreCase = true) }
            "车辆" -> allAlerts.filter {
                it.label.equals("car", ignoreCase = true) ||
                    it.label.equals("truck", ignoreCase = true) ||
                    it.label.equals("bus", ignoreCase = true) ||
                    it.label.equals("motorcycle", ignoreCase = true) ||
                    it.label.equals("bicycle", ignoreCase = true)
            }
            "其他" -> allAlerts.filter {
                !it.label.equals("person", ignoreCase = true) &&
                    !it.label.equals("car", ignoreCase = true) &&
                    !it.label.equals("truck", ignoreCase = true) &&
                    !it.label.equals("bus", ignoreCase = true) &&
                    !it.label.equals("motorcycle", ignoreCase = true) &&
                    !it.label.equals("bicycle", ignoreCase = true)
            }
            else -> allAlerts
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        AmbientFluidBackground(
            modifier = Modifier.fillMaxSize(),
            preset = preset
        )

        Column(modifier = Modifier.fillMaxSize()) {
            // Toolbar
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
                    Text(
                        text = "${camera.name} - 报警历史",
                        color = textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Category Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("全部", "人形", "车辆", "其他").forEach { category ->
                    val isSelected = selectedCategory == category
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = category },
                        label = {
                            Text(
                                text = category,
                                color = if (isSelected) tokens.accentColor else textSecondary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = tokens.accentBg,
                            containerColor = tokens.controlBg
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = if (isSelected) tokens.accentColor else tokens.cardBorder
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            // Content
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = accent)
                }
            } else if (errorMessage != null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(errorMessage ?: "", color = tokens.statusDanger, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(onClick = { loadAlerts() }) {
                            Text("点击重试", color = accent)
                        }
                    }
                }
            } else if (filteredAlerts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("暂无此类报警记录", color = textSecondary, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredAlerts, key = { it.id }) { alert ->
                        AlertCard(
                            alert = alert,
                            baseUrl = baseUrl,
                            token = token,
                            onSnapshotClick = { a ->
                                AlertSnapshotDialog(context, a, baseUrl, token, okHttpClient).show()
                            },
                            onJumpCamera = { a ->
                                val ts = a.startTime.toLong()
                                if (ts > 0) {
                                    onOpenRecording(ts)
                                }
                            }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}
