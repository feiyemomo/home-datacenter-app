package com.homedatacenter.app.ui.cameras

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.homedatacenter.app.R
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.model.Alert
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.ui.alerts.AlertSnapshotDialogFragment
import com.homedatacenter.app.ui.main.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CamerasFragment — Stage A Host Fragment.
 *
 * Exposes reactive StateFlows to [CamerasPageContent]:
 * - [viewModel.filterState] (AlertFilterState Flow)
 * - [viewModel.scrollToAlertsEvent] (Event channel for cross-tab scroll navigation)
 * - [viewModel.cameras] / [viewModel.alerts] / [viewModel.refreshing]
 */
class CamerasFragment : Fragment() {

    data class AlertFilterItem(
        val id: Any?,
        val title: String,
        @androidx.annotation.DrawableRes val iconRes: Int = 0
    )

    private val viewModel: CamerasViewModel by viewModels {
        val app = requireActivity().application as android.app.Application
        val repo = (requireActivity() as MainActivity).container.getRepository()
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                CamerasViewModel(app, repo, (requireActivity() as MainActivity).container.getApi()) as T
        }
    }

    companion object {
        @Volatile
        var pendingScrollToAlerts = false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val mainActivity = activity as? MainActivity
        val baseUrl = mainActivity?.container?.getApiBaseUrl() ?: ""
        val token = mainActivity?.container?.prefsManager?.token
        val isAdmin = mainActivity?.container?.prefsManager?.isAdmin ?: false
        val columnCount = resources.getInteger(R.integer.camera_list_column_count)

        if (pendingScrollToAlerts) {
            pendingScrollToAlerts = false
            viewModel.triggerScrollToAlerts()
        }

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                val camerasState = viewModel.cameras.collectAsStateWithLifecycle(initialValue = null)
                val alertsState = viewModel.alerts.collectAsStateWithLifecycle(initialValue = null)
                val alertsLoadingState = viewModel.alertsLoading.collectAsStateWithLifecycle(initialValue = false)
                val alertsHasMoreState = viewModel.alertsHasMore.collectAsStateWithLifecycle(initialValue = false)
                val refreshingState = viewModel.refreshing.collectAsStateWithLifecycle(initialValue = false)
                val filterState = viewModel.filterState.collectAsStateWithLifecycle()

                var activeFilterConfig by remember { mutableStateOf<FilterDialogConfig?>(null) }

                CamerasPageContent(
                    cameras = camerasState.value ?: emptyList(),
                    alerts = alertsState.value ?: emptyList(),
                    alertsLoading = alertsLoadingState.value,
                    alertsHasMore = alertsHasMoreState.value,
                    isRefreshing = refreshingState.value,
                    onRefresh = {
                        loadCamerasFromNetwork()
                        loadAllAlerts()
                    },
                    filterState = filterState.value,
                    onUpdateFilters = { camId, lbl, days ->
                        viewModel.updateFilters(camId, lbl, days)
                        loadAllAlerts()
                    },
                    scrollToAlertsFlow = viewModel.scrollToAlertsEvent,
                    isAdmin = isAdmin,
                    baseUrl = baseUrl,
                    token = token,
                    columnCount = columnCount,
                    onCameraClick = { camera -> openCameraDetail(camera) },
                    onMultiCameraClick = {
                        startActivity(Intent(requireContext(), MultiCameraActivity::class.java))
                    },
                    onRegisterCameraClick = {
                        val ctx = context ?: return@CamerasPageContent
                        if (mainActivity != null) {
                            RegisterCameraDialog(ctx, mainActivity.container) { loadCamerasFromNetwork() }.show()
                        }
                    },
                    onFilterCameraClick = {
                        val cameras = viewModel.cameras.value ?: emptyList()
                        val items = mutableListOf<AlertFilterItem>()
                        items.add(AlertFilterItem(null, "全部摄像头", R.drawable.ic_camera))
                        cameras.forEach { cam ->
                            items.add(AlertFilterItem(cam.id, cam.name, R.drawable.ic_camera))
                        }
                        activeFilterConfig = FilterDialogConfig(
                            title = "选择摄像头来源",
                            items = items,
                            selectedId = viewModel.filterCameraId,
                            onSelect = { selected ->
                                val chosenId = selected.id as? Long
                                viewModel.updateFilters(chosenId, viewModel.filterLabel, viewModel.filterDays)
                                loadAllAlerts()
                            }
                        )
                    },
                    onFilterDateClick = {
                        val items = listOf(
                            AlertFilterItem(0, "全部时间", R.drawable.ic_history),
                            AlertFilterItem(1, "今天", R.drawable.ic_history),
                            AlertFilterItem(3, "近 3 天", R.drawable.ic_history),
                            AlertFilterItem(7, "近 7 天", R.drawable.ic_history)
                        )
                        activeFilterConfig = FilterDialogConfig(
                            title = "选择报警时间范围",
                            items = items,
                            selectedId = viewModel.filterDays,
                            onSelect = { selected ->
                                val days = selected.id as Int
                                viewModel.updateFilters(viewModel.filterCameraId, viewModel.filterLabel, days)
                                loadAllAlerts()
                            }
                        )
                    },
                    onFilterLabelClick = {
                        val items = listOf(
                            AlertFilterItem(null, "全部目标", R.drawable.ic_alerts),
                            AlertFilterItem("person", "人形检测 (Person)", R.drawable.ic_alerts),
                            AlertFilterItem("car", "车辆识别 (Car)", R.drawable.ic_alerts),
                            AlertFilterItem("dog,cat", "宠物识别 (Pet)", R.drawable.ic_alerts),
                            AlertFilterItem("fall", "摔倒警报 (Fall)", R.drawable.ic_alert_circle)
                        )
                        activeFilterConfig = FilterDialogConfig(
                            title = "选择目标类型",
                            items = items,
                            selectedId = viewModel.filterLabel,
                            onSelect = { selected ->
                                val chosenLabel = selected.id as? String
                                viewModel.updateFilters(viewModel.filterCameraId, chosenLabel, viewModel.filterDays)
                                loadAllAlerts()
                            }
                        )
                    },
                    onLoadMoreAlerts = { loadMoreAlerts() },
                    onSnapshotClick = { alert -> showSnapshotDialog(alert) },
                    onJumpCamera = { alert -> jumpToCameraWithAlert(alert) },
                    onRefreshClick = { loadCamerasFromNetwork() },
                )

                activeFilterConfig?.let { cfg ->
                    FilterOptionsDialog(
                        config = cfg,
                        onDismiss = { activeFilterConfig = null }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.cameras.collectLatest { cameras ->
                        if (cameras != null) {
                            preheatOnlineCameras(cameras)
                        }
                    }
                }
            }
        }

        (activity as? MainActivity)?.container?.prefetchIceConfig()
    }

    private val preheatedCameras = mutableSetOf<Long>()

    private fun preheatOnlineCameras(cameras: List<Camera>) {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return
        val toPreheat = cameras.filter { it.isOnline && preheatedCameras.add(it.id) }
        if (toPreheat.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            toPreheat.forEach { cam ->
                try {
                    mainActivity.container.getRepository().preheatCamera(token, cam.id)
                } catch (_: Exception) {}
            }
        }
    }

    private fun openCameraDetail(camera: Camera) {
        val ctx = context ?: return
        val mainActivity = activity as? MainActivity
        val token = mainActivity?.container?.prefsManager?.token
        if (mainActivity != null && !token.isNullOrEmpty()) {
            lifecycleScope.launch {
                mainActivity.container.getRepository().preheatCamera(token, camera.id)
            }
        }
        val json = NetworkFactory.json.encodeToString(Camera.serializer(), camera)
        val intent = Intent(ctx, CameraDetailActivity::class.java).apply {
            putExtra(CameraDetailActivity.EXTRA_CAMERA_JSON, json)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        if (isAdded) {
            if (pendingScrollToAlerts) {
                pendingScrollToAlerts = false
                viewModel.triggerScrollToAlerts()
            }
            loadCamerasFromCache()
            loadAllAlerts()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded) {
            loadCamerasFromCache()
            loadAllAlerts()
        }
    }

    private fun loadCamerasFromCache() {
        val mainActivity = activity as? MainActivity ?: return
        viewModel.loadCameras(mainActivity.container.prefsManager.token)
    }

    private fun loadCamerasFromNetwork() {
        val mainActivity = activity as? MainActivity ?: return
        viewModel.refreshCameras(mainActivity.container.prefsManager.token)
    }

    private fun loadAllAlerts() {
        val mainActivity = activity as? MainActivity ?: return
        viewModel.loadAllAlerts(mainActivity.container.prefsManager.token)
    }

    private fun loadMoreAlerts() {
        val mainActivity = activity as? MainActivity ?: return
        viewModel.loadMoreAlerts(mainActivity.container.prefsManager.token)
    }

    private fun showSnapshotDialog(alert: Alert) {
        val mainActivity = activity as? MainActivity ?: return
        val baseUrl = mainActivity.container.getApiBaseUrl()
        val token = mainActivity.container.prefsManager.token
        val client = mainActivity.container.okHttpClient
        val dialog = AlertSnapshotDialogFragment.newInstance(alert, baseUrl, token, client)
        dialog.show(parentFragmentManager, AlertSnapshotDialogFragment.TAG)
    }

    private fun jumpToCameraWithAlert(alert: Alert) {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return
        val camId = alert.cameraId ?: return
        val startTs = alert.startTime

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val cam = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    mainActivity.container.getRepository().getCamera(token, camId)
                }
                val cameraJson = NetworkFactory.json.encodeToString(
                    Camera.serializer(), cam
                )
                val intent = Intent(requireContext(), CameraDetailActivity::class.java).apply {
                    putExtra(CameraDetailActivity.EXTRA_CAMERA_JSON, cameraJson)
                    putExtra(CameraDetailActivity.EXTRA_INITIAL_TIMESTAMP, startTs)
                }
                startActivity(intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("CamerasFragment", "jumpToCameraWithAlert failed: ${e.message}", e)
                android.widget.Toast.makeText(
                    requireContext(),
                    R.string.alert_jump_failed,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
