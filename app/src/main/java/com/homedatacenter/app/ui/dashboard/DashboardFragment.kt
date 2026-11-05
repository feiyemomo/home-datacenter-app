package com.homedatacenter.app.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.homedatacenter.app.R
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.model.Alert
import com.homedatacenter.app.data.model.Camera
import androidx.compose.ui.platform.ComposeView
import com.homedatacenter.app.ui.alerts.AlertSnapshotDialogFragment
import com.homedatacenter.app.ui.automations.AutomationsActivity
import com.homedatacenter.app.ui.cameras.CameraDetailActivity
import com.homedatacenter.app.ui.cameras.CamerasFragment
import com.homedatacenter.app.ui.cameras.MultiCameraActivity
import com.homedatacenter.app.ui.components.MainTab
import com.homedatacenter.app.ui.main.MainActivity
import com.homedatacenter.app.ui.network.NetworkDetailActivity
import com.homedatacenter.app.ui.ops.OpsFragment
import com.homedatacenter.app.util.NotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * DashboardFragment — Pure Compose Host Fragment.
 *
 * Replaces legacy 50KB XML ViewBinding with [DashboardPageContent] mounted via [ComposeView].
 * Drives all state via [DashboardViewModel] and reactive StateFlows.
 */
class DashboardFragment : Fragment() {

    private var statusPollingJob: Job? = null
    private val _camerasFlow = MutableStateFlow<List<Camera>>(emptyList())

    private val viewModel: DashboardViewModel by viewModels {
        val ma = requireActivity() as MainActivity
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                DashboardViewModel(
                    requireActivity().application as android.app.Application,
                    ma.container.getRepository(),
                    ma.container.baseUrlResolver,
                    ma.container.okHttpClient,
                ) as T
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val mainActivity = activity as? MainActivity
        val baseUrl = mainActivity?.container?.getApiBaseUrl() ?: ""
        val token = mainActivity?.container?.prefsManager?.token
        val userName = mainActivity?.container?.prefsManager?.userName ?: "管理员"

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                val isAdmin by (mainActivity?.container?.roleManager?.isAdminFlow ?: MutableStateFlow(false)).collectAsState()
                val systemStatus by viewModel.systemStatus.collectAsState()
                val securityGuard by viewModel.securityGuard.collectAsState()
                val alerts by viewModel.alerts.collectAsState()
                val recentLogs by viewModel.recentLogs.collectAsState()
                val weather by viewModel.weather.collectAsState()
                val backendPathText by viewModel.backendPathText.collectAsState()
                val backendPathIsFast by viewModel.backendPathIsFast.collectAsState()
                val isRefreshing by viewModel.isRefreshing.collectAsState()
                val cameras by _camerasFlow.collectAsState()

                DashboardPageContent(
                    systemStatus = systemStatus,
                    securityGuard = securityGuard,
                    cameras = cameras,
                    alerts = alerts,
                    recentLogs = recentLogs,
                    weather = weather,
                    backendPathText = backendPathText,
                    backendPathIsFast = backendPathIsFast,
                    isRefreshing = isRefreshing,
                    isAdmin = isAdmin,
                    baseUrl = baseUrl,
                    token = token,
                    userName = userName,
                    onRefresh = { refreshAll(isUserTriggered = true) },
                    onNavigateOps = {
                        (activity as? MainActivity)?.selectTab(MainTab.Ops)
                    },
                    onNavigateUsers = {
                        (activity as? MainActivity)?.selectTab(MainTab.Users)
                    },
                    onToggleSecurity = {
                        if (!isAdmin) {
                            toast("普通成员仅支持查看安防状态，更改布防请联系管理员")
                        } else {
                            val currentMode = securityGuard?.mode
                            val targetMode = if (securityGuard?.isDisarmed != true) "disarmed" else "armed_away"
                            viewModel.setSecurityGuard(
                                token = token,
                                mode = targetMode,
                                onSuccess = {
                                    toast(if (targetMode == "disarmed") "已撤防" else "已布防")
                                },
                                onError = { err ->
                                    toast(err)
                                }
                            )
                        }
                    },
                    onNavigateAutomations = {
                        context?.let { ctx -> startActivity(Intent(ctx, AutomationsActivity::class.java)) }
                    },
                    onNavigateNetworkDetail = {
                        context?.let { ctx -> startActivity(Intent(ctx, NetworkDetailActivity::class.java)) }
                    },
                    onNavigateCameras = {
                        (activity as? MainActivity)?.selectTab(MainTab.Cameras)
                    },
                    onCameraClick = { cam ->
                        openCameraDetail(cam)
                    },
                    onMultiCameraClick = {
                        context?.let { ctx -> startActivity(Intent(ctx, MultiCameraActivity::class.java)) }
                    },
                    onViewAllAlerts = {
                        CamerasFragment.pendingScrollToAlerts = true
                        (activity as? MainActivity)?.selectTab(MainTab.Cameras)
                    },
                    onViewAllLogs = {
                        OpsFragment.targetSubTab = OpsFragment.TAB_LOGS
                        (activity as? MainActivity)?.selectTab(MainTab.Ops)
                    },
                    onAlertClick = { alert ->
                        showSnapshotDialog(alert)
                    },
                    onCleanCache = {
                        viewModel.cleanSystemCache(
                            token = token,
                            onSuccess = {
                                toast("转码缓存已清理")
                            },
                            onError = { msg ->
                                toast(msg)
                            }
                        )
                    }
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadCameras()
    }

    private fun loadCameras() {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val cams = withContext(Dispatchers.IO) {
                    mainActivity.container.getRepository().listCameras(token, useCache = true)
                }
                _camerasFlow.value = cams
            } catch (_: Exception) {
            }
        }
    }

    private fun toast(msg: String) {
        context?.let { ctx ->
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val THROTTLE_MS = 30_000L
    }

    private var lastRefreshTimestamp: Long = 0L

    override fun onResume() {
        super.onResume()
        if (isAdded && !isHidden) {
            val now = System.currentTimeMillis()
            if (now - lastRefreshTimestamp > THROTTLE_MS) {
                lastRefreshTimestamp = now
                refreshAll(isUserTriggered = false)
            }
            startStatusPolling()
        }
    }

    override fun onPause() {
        stopStatusPolling()
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded) {
            val now = System.currentTimeMillis()
            if (now - lastRefreshTimestamp > THROTTLE_MS) {
                lastRefreshTimestamp = now
                refreshAll(isUserTriggered = false)
            }
            startStatusPolling()
        } else if (hidden) {
            stopStatusPolling()
        }
    }

    private fun refreshAll(isUserTriggered: Boolean = false) {
        lastRefreshTimestamp = System.currentTimeMillis()
        val mainActivity = activity as? MainActivity ?: return
        val baseUrl = mainActivity.container.getApiBaseUrl()
        val token = mainActivity.container.prefsManager.token
        val isAdmin = mainActivity.container.prefsManager.isAdmin

        viewModel.refreshAll(token, baseUrl, isAdmin, isUserTriggered = isUserTriggered)
        loadCameras()
        mainActivity.container.prefetchIceConfig()
    }

    private fun startStatusPolling() {
        stopStatusPolling()
        statusPollingJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(30_000L)
                val ma = activity as? MainActivity ?: break
                val token = ma.container.prefsManager.token
                val baseUrl = ma.container.getApiBaseUrl()
                val isAdmin = ma.container.prefsManager.isAdmin
                viewModel.refreshSystemStatus(token)
                viewModel.loadWeather(token, baseUrl)
                viewModel.loadRecentAlerts(token)
                viewModel.loadRecentLogs(token, isAdmin)
                viewModel.refreshSecurityGuard(token)
            }
        }
    }

    private fun stopStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = null
    }

    private fun showSnapshotDialog(alert: Alert) {
        val mainActivity = activity as? MainActivity ?: return
        val baseUrl = mainActivity.container.getApiBaseUrl()
        val token = mainActivity.container.prefsManager.token
        val client = mainActivity.container.okHttpClient
        val dialog = AlertSnapshotDialogFragment.newInstance(alert, baseUrl, token, client)
        dialog.show(parentFragmentManager, AlertSnapshotDialogFragment.TAG)
    }

    private fun openCameraDetail(camera: Camera) {
        val ctx = context ?: return
        val cameraJson = NetworkFactory.json.encodeToString(Camera.serializer(), camera)
        val intent = Intent(ctx, CameraDetailActivity::class.java).apply {
            putExtra(CameraDetailActivity.EXTRA_CAMERA_JSON, cameraJson)
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopStatusPolling()
    }
}
