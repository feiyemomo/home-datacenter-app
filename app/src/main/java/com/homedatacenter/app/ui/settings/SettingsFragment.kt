package com.homedatacenter.app.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.homedatacenter.app.R
import com.homedatacenter.app.ui.admin.UsersActivity
import com.homedatacenter.app.ui.automations.AutomationsActivity
import com.homedatacenter.app.ui.glass.AmbientFluidBackground
import com.homedatacenter.app.ui.glass.AppThemePreset
import com.homedatacenter.app.ui.glass.ProvideGlassTheme
import com.homedatacenter.app.ui.main.MainActivity
import com.homedatacenter.app.ui.ops.OpsFragment
import com.homedatacenter.app.ui.vision.FamilyFacesActivity
import com.homedatacenter.app.util.ApkInstaller
import com.homedatacenter.app.util.BaseUrlResolver
import com.homedatacenter.app.util.JwtUtil
import com.homedatacenter.app.util.PrefsManager
import com.homedatacenter.app.util.ThemeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SettingsFragment : Fragment() {

    private var activeUpdateInfo by mutableStateOf<com.homedatacenter.app.data.model.UpdateInfo?>(null)
    private var storageDialogState by mutableStateOf<StorageDialogUiState?>(null)
    private var showEditQuotaDialog by mutableStateOf<Int?>(null)

    private var uiState by mutableStateOf(SettingsUiState())

    private val updatePollHandler = Handler(Looper.getMainLooper())
    private val updatePollRunnable = object : Runnable {
        override fun run() {
            val container = (activity as? MainActivity)?.container ?: return
            renderCachedUpdateStatus()
            if (container.isDownloadingApk()) {
                updatePollHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                val preset by ThemeManager.themePresetFlow.collectAsState()
                val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val actions = androidx.compose.runtime.remember { buildActions() }

                ProvideGlassTheme(preset = preset) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AmbientFluidBackground(
                            preset = preset,
                            modifier = Modifier.fillMaxSize()
                        )

                        SettingsPageContent(
                            state = uiState.copy(currentPreset = preset),
                            actions = actions,
                            bottomPadding = navBarBottom + 80.dp
                        )

                        activeUpdateInfo?.let { info ->
                            val apkFile = (activity as? MainActivity)?.container?.getCachedDownloadedApk()
                            val hasApk = apkFile != null && apkFile.exists()
                            UpdateAvailableDialog(
                                info = info,
                                currentVersion = getAppVersionString(),
                                hasApk = hasApk,
                                onDismiss = { activeUpdateInfo = null },
                                onConfirm = {
                                    val mainActivity = activity as? MainActivity ?: return@UpdateAvailableDialog
                                    if (hasApk) {
                                        ApkInstaller.launchInstaller(requireActivity(), apkFile!!)
                                    } else {
                                        mainActivity.container.startDownloadFor(info)
                                        Toast.makeText(requireContext(), "正在后台下载新版本", Toast.LENGTH_SHORT).show()
                                        renderCachedUpdateStatus()
                                        startUpdatePollingIfNeeded()
                                    }
                                }
                            )
                        }

                        storageDialogState?.let { state ->
                            StorageQuotaDialog(
                                quotaGb = state.quotaGb,
                                archiveHour = state.archiveHour,
                                minAgeDays = state.minAgeDays,
                                usageText = state.usageText,
                                usagePercent = state.usagePercent,
                                isArchiving = state.isArchiving,
                                isCleaning = state.isCleaning,
                                onEditQuotaClick = { showEditQuotaDialog = state.quotaGb },
                                onPickHourClick = { pickArchiveHour() },
                                onEditMinAgeClick = { pickMinAge() },
                                onTriggerArchive = { triggerArchive() },
                                onCleanCache = { cleanSystemCache() },
                                onDismiss = { storageDialogState = null }
                            )
                        }

                        showEditQuotaDialog?.let { currentQuota ->
                            EditQuotaDialog(
                                initialQuota = currentQuota,
                                onDismiss = { showEditQuotaDialog = null },
                                onConfirm = { newQuota ->
                                    updateQuota(newQuota)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize state from prefs and environment
        val mainActivity = activity as? MainActivity
        val prefs = mainActivity?.container?.prefsManager
        if (prefs != null) {
            val resolver = mainActivity.container.baseUrlResolver
            val effectiveLan = resolver.getCustomLanUrl() ?: BaseUrlResolver.LAN_URL

            uiState = uiState.copy(
                userName = prefs.userName?.ifEmpty { "User" } ?: "User",
                isAdmin = prefs.isAdmin,
                currentPreset = ThemeManager.currentPreset,
                followSystemDark = prefs.themeMode == PrefsManager.THEME_FOLLOW_SYSTEM,
                notificationsEnabled = prefs.notificationsEnabled,
                notifyPerson = prefs.notifyPerson,
                notifyMotion = prefs.notifyMotion,
                notifySystem = prefs.notifySystem,
                notifyIncludeSnapshot = prefs.notifyIncludeSnapshot,
                notifyDndEnabled = prefs.notifyDndEnabled,
                dndSummaryText = formatDndSummary(prefs),
                keepAliveEnabled = prefs.keepAliveEnabled,
                lanHost = effectiveLan,
                appVersion = getAppVersionString(),
                updateTrack = prefs.getEffectiveUpdateTrack()
            )
            setupJwtInfo(prefs)
            updateBatteryOptimizationState()
        }

        loadUserInfo()
        loadAISourceStatus()
        renderCachedUpdateStatus()
        startUpdatePollingIfNeeded()
    }

    private fun buildActions(): SettingsActions {
        val mainActivity = activity as? MainActivity
        val prefs = mainActivity?.container?.prefsManager

        return SettingsActions(
            onPresetChange = { preset ->
                val ctx = requireContext()
                ThemeManager.setPreset(ctx, preset)
                uiState = uiState.copy(
                    currentPreset = preset,
                    followSystemDark = false
                )
                Toast.makeText(ctx, "已切换风格: ${preset.displayName}", Toast.LENGTH_SHORT).show()
            },
            onFollowSystemDarkChange = { follow ->
                val ctx = requireContext()
                if (prefs != null) {
                    val mode = if (follow) PrefsManager.THEME_FOLLOW_SYSTEM else {
                        if (ThemeManager.currentPreset.isDark) PrefsManager.THEME_DARK else PrefsManager.THEME_LIGHT
                    }
                    ThemeManager.setAppTheme(ctx, mode, recreateActivity = false)
                }
                uiState = uiState.copy(followSystemDark = follow)
            },
            onToggleSessionDetails = {
                uiState = uiState.copy(isSessionDetailsExpanded = !uiState.isSessionDetailsExpanded)
            },
            onRefreshToken = {
                if (prefs != null) refreshJwtToken(prefs)
            },
            onLogoutClick = {
                showLogoutDialog()
            },
            onNotificationsMasterChange = { enabled ->
                prefs?.notificationsEnabled = enabled
                uiState = uiState.copy(notificationsEnabled = enabled)
            },
            onNotifyPersonChange = { enabled ->
                prefs?.notifyPerson = enabled
                uiState = uiState.copy(notifyPerson = enabled)
            },
            onNotifyMotionChange = { enabled ->
                prefs?.notifyMotion = enabled
                uiState = uiState.copy(notifyMotion = enabled)
            },
            onNotifySystemChange = { enabled ->
                prefs?.notifySystem = enabled
                uiState = uiState.copy(notifySystem = enabled)
            },
            onNotifySnapshotChange = { enabled ->
                prefs?.notifyIncludeSnapshot = enabled
                uiState = uiState.copy(notifyIncludeSnapshot = enabled)
            },
            onNotifyDndChange = { enabled ->
                prefs?.notifyDndEnabled = enabled
                uiState = uiState.copy(notifyDndEnabled = enabled)
            },
            onDndTimeClick = {
                if (prefs != null) showDndTimePickerDialog(prefs)
            },
            onKeepAliveChange = { enabled ->
                prefs?.keepAliveEnabled = enabled
                uiState = uiState.copy(keepAliveEnabled = enabled)
                if (enabled) {
                    com.homedatacenter.app.service.AlertKeepAliveService.start(requireContext())
                } else {
                    com.homedatacenter.app.service.AlertKeepAliveService.stop(requireContext())
                }
            },
            onBatteryOptClick = {
                requestIgnoreBatteryOptimization()
            },
            onLanHostSave = { input ->
                saveLanHost(input)
            },
            onLanHostReset = {
                resetLanHost()
            },
            onNavAutomations = {
                startActivity(Intent(requireContext(), AutomationsActivity::class.java))
            },
            onNavSecurityDefense = {
                (activity as? MainActivity)?.let {
                    OpsFragment.targetSubTab = OpsFragment.TAB_SECURITY
                    it.selectTab(com.homedatacenter.app.ui.components.MainTab.Ops)
                }
            },
            onNavUsers = {
                startActivity(Intent(requireContext(), UsersActivity::class.java))
            },
            onNavFamilyFaces = {
                startActivity(Intent(requireContext(), FamilyFacesActivity::class.java))
            },
            onStorageConfigClick = {
                showStorageConfigDialog()
            },
            onAiSourceConfigClick = {
                showAISourceDialog()
            },
            onMotionFallbackChange = { isChecked ->
                val mAct = activity as? MainActivity ?: return@SettingsActions
                val token = mAct.container.prefsManager.token ?: return@SettingsActions
                val repo = mAct.container.getRepository()
                lifecycleScope.launch {
                    try {
                        repo.setMotionFallback(token, isChecked)
                        uiState = uiState.copy(motionFallback = isChecked)
                        val ctx = context ?: return@launch
                        Toast.makeText(
                            ctx,
                            if (isChecked) "已开启移动兼容侦测" else "已关闭移动兼容侦测",
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        val ctx = context ?: return@launch
                        Toast.makeText(ctx, "设置失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onUpdateTrackChange = { newTrack ->
                val mAct = activity as? MainActivity ?: return@SettingsActions
                val p = mAct.container.prefsManager
                if (!p.isAdmin) return@SettingsActions
                p.updateTrack = newTrack
                uiState = uiState.copy(
                    updateTrack = newTrack,
                    updateStatusText = "正在探测${if (newTrack == PrefsManager.TRACK_DEBUG) "开发测试" else "正式稳定"}通道更新..."
                )
                mAct.container.clearCachedUpdateInfo()
                val ctx = context ?: return@SettingsActions
                Toast.makeText(
                    ctx,
                    if (newTrack == PrefsManager.TRACK_DEBUG) "已切换至开发测试通道 (Debug)" else "已切换至正式发布通道 (Release)",
                    Toast.LENGTH_SHORT
                ).show()

                lifecycleScope.launch {
                    try {
                        val newInfo = mAct.container.forceCheckUpdate()
                        val currentCtx = context ?: return@launch
                        if (newInfo == null) {
                            uiState = uiState.copy(updateStatusText = "当前通道已是最新版本")
                            Toast.makeText(currentCtx, "当前通道已是最新版本", Toast.LENGTH_SHORT).show()
                        } else {
                            showUpdateAvailableDialog(newInfo)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        uiState = uiState.copy(updateStatusText = "检查更新失败")
                    } finally {
                        renderCachedUpdateStatus()
                        startUpdatePollingIfNeeded()
                    }
                }
            },
            onCheckUpdateClick = {
                handleCheckUpdateClick()
            }
        )
    }

    private fun formatDndSummary(prefs: PrefsManager): String {
        val startStr = String.format(Locale.US, "%02d:%02d", prefs.notifyDndStartHour, prefs.notifyDndStartMinute)
        val endStr = String.format(Locale.US, "%02d:%02d", prefs.notifyDndEndHour, prefs.notifyDndEndMinute)
        return "时段: $startStr ~ $endStr (点击修改)"
    }

    private fun showDndTimePickerDialog(prefs: PrefsManager) {
        val startPicker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(prefs.notifyDndStartHour)
            .setMinute(prefs.notifyDndStartMinute)
            .setTitleText("选择免打扰起始时间")
            .build()

        startPicker.addOnPositiveButtonClickListener {
            prefs.notifyDndStartHour = startPicker.hour
            prefs.notifyDndStartMinute = startPicker.minute

            val endPicker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(prefs.notifyDndEndHour)
                .setMinute(prefs.notifyDndEndMinute)
                .setTitleText("选择免打扰结束时间")
                .build()

            endPicker.addOnPositiveButtonClickListener {
                prefs.notifyDndEndHour = endPicker.hour
                prefs.notifyDndEndMinute = endPicker.minute
                uiState = uiState.copy(dndSummaryText = formatDndSummary(prefs))
                Toast.makeText(requireContext(), "免打扰时段已更新", Toast.LENGTH_SHORT).show()
            }
            endPicker.show(parentFragmentManager, "dnd_end_picker")
        }
        startPicker.show(parentFragmentManager, "dnd_start_picker")
    }

    private fun updateBatteryOptimizationState() {
        val ctx = context ?: return
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        val isIgnored = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        uiState = uiState.copy(isBatteryOptimized = isIgnored)
    }

    private fun requestIgnoreBatteryOptimization() {
        val ctx = context ?: return
        try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:${ctx.packageName}")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(ctx, "无法打开系统电池优化设置: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupJwtInfo(prefs: PrefsManager) {
        val token = prefs.token
        val userId = JwtUtil.userId(token)?.toString() ?: "-"
        val deviceId = JwtUtil.deviceId(token)?.toString() ?: "-"
        val issuedAt = JwtUtil.issuedAt(token)
        val expiresAt = JwtUtil.expiresAt(token)
        val remaining = JwtUtil.secondsUntilExpiry(token)

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val tokenIssued = if (issuedAt != null) fmt.format(Date(issuedAt * 1000L)) else "-"
        val tokenExpires = if (expiresAt != null) fmt.format(Date(expiresAt * 1000L)) else "-"

        val days = remaining?.let { if (it > 0) TimeUnit.SECONDS.toDays(it) else 0L }
        val isExpired = remaining != null && remaining <= 0

        uiState = uiState.copy(
            userId = userId,
            deviceId = deviceId,
            tokenIssued = tokenIssued,
            tokenExpires = tokenExpires,
            tokenRemainingDays = days,
            isTokenExpired = isExpired
        )
    }

    private fun refreshJwtToken(prefs: PrefsManager) {
        val mainActivity = activity as? MainActivity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            context?.let { ctx ->
                Toast.makeText(ctx, "正在刷新令牌...", Toast.LENGTH_SHORT).show()
            }
            val refreshed = withContext(Dispatchers.IO) {
                mainActivity.container.tokenManager.refreshViaTokenAndPersist(prefs.token ?: "", force = true)
                    ?: if (!prefs.accessKey.isNullOrEmpty() && prefs.userId > 0L) {
                        mainActivity.container.tokenManager.refreshAndPersist(prefs.userId, prefs.accessKey!!, force = true)
                    } else null
            }
            val ctx = context ?: return@launch
            if (refreshed != null) {
                setupJwtInfo(prefs)
                Toast.makeText(ctx, "令牌已更新", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "令牌刷新失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadUserInfo() {
        val mainActivity = activity as? MainActivity ?: return
        val prefs = mainActivity.container.prefsManager
        val token = prefs.token ?: return

        lifecycleScope.launch {
            try {
                val user = mainActivity.container.getRepository().getMe(token)
                prefs.saveUserInfo(user.name, user.isAdmin)
                prefs.userId = user.id
                mainActivity.container.roleManager.updateAdmin(user.isAdmin)
                uiState = uiState.copy(
                    userName = user.name,
                    isAdmin = user.isAdmin
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun loadAISourceStatus() {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return
        val repo = mainActivity.container.getRepository()
        lifecycleScope.launch {
            try {
                val status = repo.getAISource(token)
                val label = when (status.currentSource) {
                    "frigate" -> "Frigate NVR 软件检测"
                    "both" -> "双路冗余互备 (智能去重)"
                    else -> "摄像头自带AI (默认)"
                }
                uiState = uiState.copy(
                    aiSourceMode = label,
                    motionFallback = status.motionFallback
                )
            } catch (_: Exception) {}
        }
    }

    private fun saveLanHost(input: String) {
        val mainActivity = activity as? MainActivity ?: return
        val resolver = mainActivity.container.baseUrlResolver

        val trimmed = input.trim()
        if (trimmed.isBlank() || (!trimmed.startsWith("http://") && !trimmed.startsWith("https://"))) {
            Toast.makeText(requireContext(), R.string.lan_config_invalid, Toast.LENGTH_SHORT).show()
            return
        }

        resolver.setCustomLanUrl(trimmed)
        uiState = uiState.copy(lanHost = trimmed)
        Toast.makeText(requireContext(), R.string.lan_config_saved, Toast.LENGTH_SHORT).show()
    }

    private fun resetLanHost() {
        val mainActivity = activity as? MainActivity ?: return
        val resolver = mainActivity.container.baseUrlResolver

        resolver.setCustomLanUrl(null)
        uiState = uiState.copy(lanHost = BaseUrlResolver.LAN_URL)
        Toast.makeText(requireContext(), R.string.lan_config_reset, Toast.LENGTH_SHORT).show()
    }

    private fun getAppVersionString(): String {
        return try {
            val ctx = context ?: return "v1.14.1"
            val pInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "v${pInfo.versionName} (${pInfo.versionCode})"
        } catch (_: Exception) {
            "v1.14.1"
        }
    }

    private fun renderCachedUpdateStatus() {
        val mainActivity = activity as? MainActivity ?: return
        val container = mainActivity.container
        val info = container.getCachedUpdateInfo()
        val apkFile = container.getCachedDownloadedApk()

        val isDownloading = container.isDownloadingApk()
        val downloadProg = container.getDownloadProgress() / 100f

        val statusText = when {
            apkFile != null && info != null -> "新版本已就绪，点击安装"
            isDownloading -> "正在下载更新中..."
            container.isDownloadFailed() -> "下载失败，点击重试"
            info != null -> "发现新版本: v${info.version_name}"
            else -> "已是最新版本"
        }

        uiState = uiState.copy(
            updateStatusText = statusText,
            isDownloading = isDownloading,
            downloadProgress = downloadProg
        )
    }

    private fun startUpdatePollingIfNeeded() {
        val container = (activity as? MainActivity)?.container ?: return
        updatePollHandler.removeCallbacks(updatePollRunnable)
        if (container.isDownloadingApk()) {
            updatePollHandler.postDelayed(updatePollRunnable, POLL_INTERVAL_MS)
        }
    }

    private fun handleCheckUpdateClick() {
        val mainActivity = activity as? MainActivity ?: return
        val container = mainActivity.container
        val info = container.getCachedUpdateInfo()
        val apkFile = container.getCachedDownloadedApk()

        when {
            apkFile != null && apkFile.exists() && info != null -> {
                ApkInstaller.launchInstaller(requireActivity(), apkFile)
            }
            container.isDownloadFailed() -> {
                container.retryDownload()
                renderCachedUpdateStatus()
                startUpdatePollingIfNeeded()
            }
            else -> {
                uiState = uiState.copy(updateStatusText = "正在检查更新...")
                lifecycleScope.launch {
                    try {
                        val newInfo = container.forceCheckUpdate()
                        val ctx = context ?: return@launch
                        if (newInfo == null) {
                            uiState = uiState.copy(updateStatusText = "已是最新版本")
                            Toast.makeText(ctx, "当前已是最新版本", Toast.LENGTH_SHORT).show()
                        } else {
                            showUpdateAvailableDialog(newInfo)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        uiState = uiState.copy(updateStatusText = "检查更新失败")
                    } finally {
                        renderCachedUpdateStatus()
                        startUpdatePollingIfNeeded()
                    }
                }
            }
        }
    }

    private fun showUpdateAvailableDialog(info: com.homedatacenter.app.data.model.UpdateInfo) {
        activeUpdateInfo = info
    }

    private fun showAISourceDialog() {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return
        val repo = mainActivity.container.getRepository()

        lifecycleScope.launch {
            var currentSource = "camera"
            var sourceList: List<com.homedatacenter.app.data.model.AISourceInfo> = emptyList()
            try {
                val status = repo.getAISource(token)
                currentSource = status.currentSource
                sourceList = status.availableSources
            } catch (e: Exception) {
                context?.let { ctx ->
                    Toast.makeText(ctx, "获取模式失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val keys = if (sourceList.isNotEmpty()) {
                sourceList.map { it.key }.toTypedArray()
            } else {
                arrayOf("camera", "frigate", "both")
            }
            val displayNames = if (sourceList.isNotEmpty()) {
                sourceList.map { "${it.name}\n${it.description}" }.toTypedArray()
            } else {
                arrayOf(
                    "摄像头自带硬件AI (默认)\n只推摄像头人形，0% NAS CPU开销，无重复",
                    "Frigate NVR 软件检测\n由NAS主机运行模型，支持精细防区与跌倒检测",
                    "双路冗余互备 (智能去重)\n两边都接收，6秒内同相机自动合并去重"
                )
            }

            val selectedIdx = keys.indexOf(currentSource).let { if (it >= 0) it else 0 }

            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.ai_alert_source_dialog_title))
                .setSingleChoiceItems(displayNames, selectedIdx) { dialog, which ->
                    val chosenKey = keys[which]
                    dialog.dismiss()
                    val targetLabel = when (chosenKey) {
                        "frigate" -> "Frigate NVR 软件检测"
                        "both" -> "双路冗余互备 (智能去重)"
                        else -> "摄像头自带硬件AI"
                    }
                    uiState = uiState.copy(aiSourceMode = targetLabel)
                    if (chosenKey == currentSource) return@setSingleChoiceItems

                    lifecycleScope.launch {
                        try {
                            val updated = repo.setAISource(token, chosenKey)
                            val label = when (updated.currentSource) {
                                "frigate" -> "Frigate NVR 软件检测"
                                "both" -> "双路冗余互备 (智能去重)"
                                else -> "摄像头自带硬件AI"
                            }
                            uiState = uiState.copy(aiSourceMode = label)
                            val ctx = context ?: return@launch
                            Toast.makeText(
                                ctx,
                                getString(R.string.ai_alert_source_success, label),
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Exception) {
                            val ctx = context ?: return@launch
                            Toast.makeText(
                                ctx,
                                getString(R.string.ai_alert_source_failed, e.message),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
    }

    data class StorageDialogUiState(
        val quotaGb: Int,
        val archiveHour: Int,
        val minAgeDays: Int,
        val usageText: String,
        val usagePercent: Float,
        val isArchiving: Boolean = false,
        val isCleaning: Boolean = false,
        val metrics: com.homedatacenter.app.data.model.SystemStatusMetrics? = null
    )

    private fun showStorageConfigDialog() {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return
        val repo = mainActivity.container.getRepository()

        lifecycleScope.launch {
            var quotaGb = 400
            var scheduleHour = 3
            var minAgeDays = 7
            try {
                val json = repo.getStorageConfig(token)
                if (json != null) {
                    quotaGb = json["quota_gb"]?.toString()?.toDoubleOrNull()?.toInt() ?: 400
                    scheduleHour = json["archive_schedule_hour"]?.toString()?.toDoubleOrNull()?.toInt() ?: 3
                    minAgeDays = json["archive_min_age_days"]?.toString()?.toDoubleOrNull()?.toInt() ?: 7
                }
            } catch (e: Exception) {
                android.util.Log.w("SettingsFragment", "getStorageConfig: ${e.message}")
            }

            var usageText = "-- / ${quotaGb} GB"
            var usagePercent = 0f
            var metrics: com.homedatacenter.app.data.model.SystemStatusMetrics? = null
            try {
                val status = repo.getSystemStatus(token, useCache = true)
                val m = status.metrics
                metrics = m
                if (m != null) {
                    val pct = m.effectiveRecordingsPercent.toFloat()
                    usageText = String.format(
                        Locale.getDefault(),
                        "%.1f GB / %.0f GB (%.1f%%)",
                        m.effectiveRecordingsUsedGb,
                        m.effectiveRecordingsLimitGb,
                        pct
                    )
                    usagePercent = pct.coerceIn(0f, 100f)
                }
            } catch (_: Exception) {}

            storageDialogState = StorageDialogUiState(
                quotaGb = quotaGb,
                archiveHour = scheduleHour,
                minAgeDays = minAgeDays,
                usageText = usageText,
                usagePercent = usagePercent,
                metrics = metrics
            )
        }
    }

    private fun updateQuota(newQuota: Int) {
        val current = storageDialogState ?: return
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return
        val repo = mainActivity.container.getRepository()

        val m = current.metrics
        val (usageText, usagePercent) = if (m != null) {
            val pct = (m.effectiveRecordingsUsedGb / newQuota * 100.0).coerceIn(0.0, 100.0).toFloat()
            String.format(
                Locale.getDefault(),
                "%.1f GB / %d GB (%.1f%%)",
                m.effectiveRecordingsUsedGb,
                newQuota,
                pct
            ) to pct
        } else {
            "-- / ${newQuota} GB" to 0f
        }

        storageDialogState = current.copy(
            quotaGb = newQuota,
            usageText = usageText,
            usagePercent = usagePercent
        )

        lifecycleScope.launch {
            try {
                repo.updateStorageConfig(token, newQuota, current.archiveHour, current.minAgeDays)
                context?.let { ctx ->
                    Toast.makeText(ctx, "录像配额已调整为 ${newQuota} GB", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                context?.let { ctx ->
                    Toast.makeText(ctx, "设置失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun pickArchiveHour() {
        val current = storageDialogState ?: return
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return
        val repo = mainActivity.container.getRepository()

        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(current.archiveHour)
            .setMinute(0)
            .setTitleText("选择每日云端归档时间")
            .build()

        picker.addOnPositiveButtonClickListener {
            val selectedHour = picker.hour
            storageDialogState = current.copy(archiveHour = selectedHour)
            lifecycleScope.launch {
                try {
                    repo.updateStorageConfig(token, current.quotaGb, selectedHour, current.minAgeDays)
                    context?.let { ctx ->
                        Toast.makeText(ctx, "每日归档时间已更新为 %02d:00".format(selectedHour), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    context?.let { ctx ->
                        Toast.makeText(ctx, "更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        picker.show(parentFragmentManager, "archive_time_picker")
    }

    private fun pickMinAge() {
        val current = storageDialogState ?: return
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return
        val repo = mainActivity.container.getRepository()

        val options = arrayOf("保留 3 天后归档", "保留 5 天后归档", "保留 7 天后归档 (推荐)", "保留 14 天后归档", "保留 30 天后归档")
        val values = intArrayOf(3, 5, 7, 14, 30)
        val selectedIdx = values.indexOf(current.minAgeDays).let { if (it >= 0) it else 2 }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择本地保留归档门限")
            .setSingleChoiceItems(options, selectedIdx) { mDialog, idx ->
                val newMinAge = values[idx]
                storageDialogState = current.copy(minAgeDays = newMinAge)
                mDialog.dismiss()
                lifecycleScope.launch {
                    try {
                        repo.updateStorageConfig(token, current.quotaGb, current.archiveHour, newMinAge)
                        context?.let { ctx ->
                            Toast.makeText(ctx, "冷归档门限已设置为 ${newMinAge} 天", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        context?.let { ctx ->
                            Toast.makeText(ctx, "设置失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun triggerArchive() {
        val current = storageDialogState ?: return
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return
        val repo = mainActivity.container.getRepository()

        storageDialogState = current.copy(isArchiving = true)
        lifecycleScope.launch {
            try {
                repo.triggerArchiveSync(token)
                context?.let { ctx ->
                    Toast.makeText(ctx, "已触发蓝奏云录像归档任务！", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                context?.let { ctx ->
                    Toast.makeText(ctx, "触发归档失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                storageDialogState = storageDialogState?.copy(isArchiving = false)
            }
        }
    }

    private fun cleanSystemCache() {
        val current = storageDialogState ?: return
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return
        val repo = mainActivity.container.getRepository()

        storageDialogState = current.copy(isCleaning = true)
        lifecycleScope.launch {
            try {
                repo.cleanSystemCache(token)
                val activeApk = if (mainActivity.container.isDownloadingApk()) {
                    mainActivity.container.getCachedDownloadedApk()
                } else null
                val freed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context?.let { ctx -> ApkInstaller.pruneStaleDownloads(ctx, activeApk) } ?: 0L
                }
                context?.let { ctx ->
                    val msg = if (freed > 0) {
                        val freedStr = if (freed >= 1024 * 1024) {
                            String.format(java.util.Locale.getDefault(), "%.1f MB", freed / (1024.0 * 1024.0))
                        } else {
                            "${(freed + 1023) / 1024} KB"
                        }
                        "转码与安装包缓存已清理 (释放 $freedStr)"
                    } else {
                        "转码切片缓存已清理！"
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                context?.let { ctx ->
                    Toast.makeText(ctx, "清理缓存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                storageDialogState = storageDialogState?.copy(isCleaning = false)
            }
        }
    }

    private fun showLogoutDialog() {
        val context = context ?: return
        AlertDialog.Builder(context)
            .setTitle(R.string.confirm_logout_title)
            .setMessage(R.string.confirm_logout_message)
            .setPositiveButton(R.string.btn_confirm) { _, _ -> logout() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun logout() {
        val mainActivity = activity as? MainActivity ?: return
        mainActivity.container.prefsManager.clearAuth()
        mainActivity.navigateToLogin()
    }

    override fun onResume() {
        super.onResume()
        if (isAdded) {
            updateBatteryOptimizationState()
            renderCachedUpdateStatus()
            startUpdatePollingIfNeeded()
            val mainActivity = activity as? MainActivity
            if (mainActivity != null) {
                val prefs = mainActivity.container.prefsManager
                setupJwtInfo(prefs)
                loadAISourceStatus()
                val resolver = mainActivity.container.baseUrlResolver
                val effective = resolver.getCustomLanUrl() ?: BaseUrlResolver.LAN_URL
                uiState = uiState.copy(
                    lanHost = effective,
                    updateTrack = prefs.getEffectiveUpdateTrack()
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        updatePollHandler.removeCallbacks(updatePollRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        updatePollHandler.removeCallbacks(updatePollRunnable)
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1000L
    }
}
