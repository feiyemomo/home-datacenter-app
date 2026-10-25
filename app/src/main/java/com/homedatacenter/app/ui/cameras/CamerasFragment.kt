package com.homedatacenter.app.ui.cameras

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.homedatacenter.app.R
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.model.Alert
import com.homedatacenter.app.data.model.AlertListData
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.databinding.DialogFilterOptionsBinding
import com.homedatacenter.app.databinding.FragmentCamerasBinding
import com.homedatacenter.app.databinding.ItemDialogFilterOptionBinding
import com.homedatacenter.app.ui.alerts.AlertListAdapter
import com.homedatacenter.app.ui.alerts.AlertSnapshotDialogFragment
import com.homedatacenter.app.ui.main.MainActivity
import com.homedatacenter.app.util.AnimationHelper
import com.homedatacenter.app.util.CacheManager
import com.homedatacenter.app.util.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CamerasFragment : Fragment() {

    private var _binding: FragmentCamerasBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: CameraAdapter
    private lateinit var allAlertsAdapter: AlertListAdapter

    // v1.10.0 (P2-2): camera list state lives in the ViewModel.
    private val viewModel: CamerasViewModel by viewModels {
        val app = requireActivity().application as android.app.Application
        val repo = (requireActivity() as MainActivity).container.getRepository()
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                CamerasViewModel(app, repo, (requireActivity() as MainActivity).container.getApi()) as T
        }
    }

    // "全部报警" pagination state lives in the ViewModel (alerts,
    // alertsLoading, alertsHasMore). The fragment only remembers
    // whether the first page already faded in.
    private var alertsFadedIn = false
    // Set by DashboardFragment's "全部" button before switching to the
    // cameras tab. When this fragment becomes visible it scrolls to the
    // "全部报警" section and resets the flag.
    companion object {
        @Volatile
        var pendingScrollToAlerts = false
    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCamerasBinding.inflate(inflater, container, false)
        return binding.root
    }

    private var camerasFadedIn = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        camerasFadedIn = false

        val mainActivity = activity as? MainActivity ?: return
        val baseUrl = mainActivity.container.getApiBaseUrl()
        val token = mainActivity.container.prefsManager.token
        val okHttpClient = mainActivity.container.okHttpClient

        // v1.5.2: tap a camera card opens CameraDetailActivity,
        // which now hosts the video player, recordings list, alerts
        // list, and PTZ controls. The list itself stays cheap to
        // scroll (no ExoPlayer per row).
        adapter = CameraAdapter(
            onClick = { camera -> openCameraDetail(camera) },
            baseUrl = baseUrl,
            token = token,
            okHttpClient = okHttpClient,
        )
        // v1.6.8: pick layout manager based on screen width. Phones
        // (1 column) use LinearLayoutManager — full-width cards with
        // 192x108dp thumbnails. Tablets / large landscape (2 columns)
        // use GridLayoutManager so the extra horizontal real estate
        // isn't wasted on a single stretched card. Column count is
        // read from @integer/camera_list_column_count so the sw600dp
        // / sw936dp resource qualifiers drive the layout choice —
        // no runtime screen-width probing needed.
        val columnCount = resources.getInteger(R.integer.camera_list_column_count)
        binding.recyclerView.layoutManager =
            if (columnCount <= 1) LinearLayoutManager(context)
            else GridLayoutManager(context, columnCount)
        binding.recyclerView.adapter = adapter

        // "全部报警" section: reuse AlertListAdapter. Tap thumbnail →
        // snapshot modal; tap "查看录像" chip → jump to the camera's
        // recording page at the alert's timestamp.
        allAlertsAdapter = AlertListAdapter(
            baseUrl = baseUrl,
            token = token,
            okHttpClient = okHttpClient,
            onSnapshotClick = { alert -> showSnapshotDialog(alert) },
            onJumpCamera = { alert -> jumpToCameraWithAlert(alert) },
            onRowClick = null,
        )
        binding.rvAllAlerts.layoutManager = LinearLayoutManager(context)
        binding.rvAllAlerts.adapter = allAlertsAdapter
        // Load more alerts when the user scrolls to the bottom of the
        // "全部报警" list.
        binding.rvAllAlerts.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val total = lm.itemCount
                val lastVisible = lm.findLastVisibleItemPosition()
                if (viewModel.alertsHasMore.value && !viewModel.alertsLoading.value && lastVisible >= total - 3) {
                    loadMoreAlerts()
                }
            }
        })

        // v1.14.9: an explicit swipe-to-refresh should also drop the
        // cached preview frames, not just re-hit the metadata API —
        // otherwise a stale frame (or a go2rtc error placeholder left
        // over from an outage) stays on screen even though the user
        // just asked for fresh data.
        binding.swipeRefresh.setOnRefreshListener {
            adapter.invalidateThumbnails()
            loadCamerasFromNetwork()
        }

        // Admin-only: show FAB for registering a new camera. The
        // server still enforces admin-gating on POST /api/v1/cameras,
        // so a non-admin who somehow sees the FAB will receive 403.
        val isAdmin = mainActivity.container.prefsManager.isAdmin
        binding.fabRegisterCamera?.visibility = if (isAdmin) View.VISIBLE else View.GONE
        binding.fabRegisterCamera?.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            RegisterCameraDialog(ctx, mainActivity.container) { loadCamerasFromNetwork() }.show()
        }

        binding.btnMultiCamera?.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            startActivity(Intent(ctx, MultiCameraActivity::class.java))
        }

        setupAlertFilters()

        // v1.5.7: pre-fetch ICE config so the first WebRTC stream
        // doesn't wait for an extra round-trip. Idempotent — the
        // AppContainer skips if already cached. Mirrors
        // DashboardFragment.refreshAll().
        // v1.10.0 (P2-2): observe the ViewModel instead of owning the
        // load logic. The fade-in now also covers the cache paint -
        // visually negligible, and the network arrival still fades.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.cameras.collect { cameras ->
                        if (cameras != null) {
                            adapter.submitList(cameras)
                            showEmpty(cameras.isEmpty())
                            if (!camerasFadedIn && cameras.isNotEmpty()) {
                                camerasFadedIn = true
                                AnimationHelper.fadeIn(binding.recyclerView, 300)
                            }
                            preheatOnlineCameras(cameras)
                        }
                    }
                }
                launch {
                    viewModel.refreshing.collect { refreshing ->
                        binding.swipeRefresh.isRefreshing = refreshing
                    }
                }
                launch {
                    viewModel.alerts.collect { alerts ->
                        if (alerts != null) {
                            allAlertsAdapter.submitList(alerts)
                            binding.tvAllAlertsEmpty.visibility =
                                if (alerts.isEmpty()) View.VISIBLE else View.GONE
                            if (!alertsFadedIn) {
                                alertsFadedIn = true
                                AnimationHelper.fadeIn(binding.rvAllAlerts, 300)
                            }
                        }
                    }
                }
            }
        }

        mainActivity.container.prefetchIceConfig()
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
        // Fire-and-forget preheat: warm up the camera's RTSP/go2rtc
        // connection on the backend so the detail page's first stream
        // request doesn't pay the cold-start cost. Do NOT await — the
        // UI jumps to CameraDetailActivity immediately while preheat
        // runs in the background.
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
            loadCamerasFromCache()
            loadAllAlerts()
            maybeScrollToAlerts()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded) {
            loadCamerasFromCache()
            loadAllAlerts()
            maybeScrollToAlerts()
        } else if (hidden) {
            adapter.releaseAllPlayers()
        }
    }

    override fun onPause() {
        super.onPause()
        if (this::adapter.isInitialized) {
            adapter.releaseAllPlayers()
        }
    }

    private fun loadCamerasFromCache() {
        val mainActivity = activity as? MainActivity ?: return
        viewModel.loadCameras(mainActivity.container.prefsManager.token)
    }

    private fun loadCamerasFromNetwork() {
        val mainActivity = activity as? MainActivity ?: return
        // v1.10.0 (P2-2): network load moved to the ViewModel.
        viewModel.refreshCameras(mainActivity.container.prefsManager.token)
    }

    private fun showEmpty(show: Boolean) {
        val mainActivity = activity as? MainActivity
        val isAdmin = mainActivity?.container?.prefsManager?.isAdmin == true
        if (show) {
            binding.layoutEmptyCameras.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            binding.tvAllAlertsTitle.visibility = View.GONE
            binding.rvAllAlerts.visibility = View.GONE
            binding.tvAllAlertsEmpty.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false

            if (isAdmin) {
                binding.tvEmptyTitle.text = "暂未添加摄像头"
                binding.tvEmptySubtitle.text = "当前系统尚未接入任何监控摄像头，您可以点击右上角「+」注册新摄像头。"
                binding.btnEmptyAction.text = "注册摄像头"
                binding.btnEmptyAction.setIconResource(R.drawable.ic_add)
                binding.btnEmptyAction.setOnClickListener {
                    val ctx = context ?: return@setOnClickListener
                    RegisterCameraDialog(ctx, mainActivity!!.container) { loadCamerasFromNetwork() }.show()
                }
            } else {
                binding.tvEmptyTitle.text = "暂无可用摄像头"
                binding.tvEmptySubtitle.text = "您当前是普通成员账号，管理员尚未为您分配或分享任何摄像头。\n请联系系统管理员为您分配访问权限。"
                binding.btnEmptyAction.text = "刷新重试"
                binding.btnEmptyAction.setIconResource(R.drawable.ic_refresh)
                binding.btnEmptyAction.setOnClickListener {
                    loadCamerasFromNetwork()
                }
            }
        } else {
            binding.layoutEmptyCameras.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            binding.tvAllAlertsTitle.visibility = View.VISIBLE
            binding.rvAllAlerts.visibility = View.VISIBLE
        }
        binding.tvEmpty.visibility = View.GONE
    }

    data class AlertFilterItem(val id: Any?, val title: String, val iconRes: Int)

    private fun showCustomFilterDialog(
        title: String,
        items: List<AlertFilterItem>,
        selectedId: Any?,
        onSelect: (AlertFilterItem) -> Unit
    ) {
        val dialogBinding = DialogFilterOptionsBinding.inflate(layoutInflater)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.tvFilterDialogTitle.text = title
        dialogBinding.btnFilterDialogClose.setOnClickListener { dialog.dismiss() }

        items.forEach { item ->
            val optBinding = ItemDialogFilterOptionBinding.inflate(layoutInflater, dialogBinding.containerOptions, false)
            optBinding.tvOptionTitle.text = item.title
            optBinding.ivOptionIcon.setImageResource(item.iconRes)
            val isSelected = item.id == selectedId
            optBinding.ivOptionCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
            if (isSelected) {
                optBinding.root.setBackgroundResource(R.drawable.bg_filter_option_selected)
                optBinding.tvOptionTitle.setTextColor(resources.getColor(R.color.primary, null))
                optBinding.tvOptionTitle.paint.isFakeBoldText = true
                optBinding.ivOptionIcon.imageTintList = android.content.res.ColorStateList.valueOf(resources.getColor(R.color.primary, null))
            } else {
                optBinding.root.setBackgroundResource(R.drawable.bg_filter_option_item)
                optBinding.tvOptionTitle.setTextColor(resources.getColor(R.color.text_primary, null))
                optBinding.tvOptionTitle.paint.isFakeBoldText = false
                optBinding.ivOptionIcon.imageTintList = android.content.res.ColorStateList.valueOf(resources.getColor(R.color.text_secondary, null))
            }
            optBinding.root.setOnClickListener {
                dialog.dismiss()
                onSelect(item)
            }
            dialogBinding.containerOptions.addView(optBinding.root)
        }

        dialog.show()
    }

    private fun setupAlertFilters() {
        binding.chipFilterCamera.setOnClickListener {
            val cameras = viewModel.cameras.value ?: emptyList()
            val items = mutableListOf<AlertFilterItem>()
            items.add(AlertFilterItem(null, "全部摄像头", R.drawable.ic_camera))
            cameras.forEach { cam ->
                items.add(AlertFilterItem(cam.id, cam.name, R.drawable.ic_camera))
            }

            showCustomFilterDialog(
                title = "选择摄像头来源",
                items = items,
                selectedId = viewModel.filterCameraId
            ) { selected ->
                val chosenId = selected.id as? Long
                val chosenName = if (chosenId == null) "全部" else selected.title
                binding.chipFilterCamera.text = "来源: $chosenName ▾"
                viewModel.updateFilters(chosenId, viewModel.filterLabel, viewModel.filterDays)
                updateResetFilterButton()
                loadAllAlerts()
            }
        }

        binding.chipFilterDate.setOnClickListener {
            val items = listOf(
                AlertFilterItem(0, "全部时间", R.drawable.ic_history),
                AlertFilterItem(1, "今天", R.drawable.ic_history),
                AlertFilterItem(3, "近 3 天", R.drawable.ic_history),
                AlertFilterItem(7, "近 7 天", R.drawable.ic_history)
            )

            showCustomFilterDialog(
                title = "选择报警时间范围",
                items = items,
                selectedId = viewModel.filterDays
            ) { selected ->
                val days = selected.id as Int
                binding.chipFilterDate.text = "日期: ${selected.title} ▾"
                viewModel.updateFilters(viewModel.filterCameraId, viewModel.filterLabel, days)
                updateResetFilterButton()
                loadAllAlerts()
            }
        }

        binding.chipFilterLabel.setOnClickListener {
            val items = listOf(
                AlertFilterItem(null, "全部目标", R.drawable.ic_alerts),
                AlertFilterItem("person", "人形检测 (Person)", R.drawable.ic_alerts),
                AlertFilterItem("car", "车辆识别 (Car)", R.drawable.ic_alerts),
                AlertFilterItem("dog,cat", "宠物识别 (Pet)", R.drawable.ic_alerts),
                AlertFilterItem("fall", "摔倒警报 (Fall)", R.drawable.ic_alert_circle)
            )

            showCustomFilterDialog(
                title = "选择目标类型",
                items = items,
                selectedId = viewModel.filterLabel
            ) { selected ->
                val chosenLabel = selected.id as? String
                val labelTitle = if (chosenLabel == null) "全部" else selected.title.substringBefore(" ")
                binding.chipFilterLabel.text = "目标: $labelTitle ▾"
                viewModel.updateFilters(viewModel.filterCameraId, chosenLabel, viewModel.filterDays)
                updateResetFilterButton()
                loadAllAlerts()
            }
        }

        binding.btnResetAlertFilter.setOnClickListener {
            binding.chipFilterCamera.text = "来源: 全部 ▾"
            binding.chipFilterDate.text = "日期: 全部 ▾"
            binding.chipFilterLabel.text = "目标: 全部 ▾"
            viewModel.updateFilters(null, null, 0)
            updateResetFilterButton()
            loadAllAlerts()
        }
    }

    private fun updateResetFilterButton() {
        val hasActiveFilter = viewModel.filterCameraId != null || viewModel.filterLabel != null || viewModel.filterDays != 0
        binding.btnResetAlertFilter.visibility = if (hasActiveFilter) View.VISIBLE else View.GONE
    }

    // --- "全部报警" section ---

    /** Load the first page of all alerts (limit = 20). */
    private fun loadAllAlerts() {
        viewModel.loadAllAlerts(
            (activity as? MainActivity)?.container?.prefsManager?.token
        )
    }

    /** Fetch the next page of alerts, growing the limit since the API
     *  has no offset parameter. */
    private fun loadMoreAlerts() {
        viewModel.loadMoreAlerts(
            (activity as? MainActivity)?.container?.prefsManager?.token
        )
    }

    /** Scroll to the "全部报警" section if DashboardFragment requested it. */
    private fun maybeScrollToAlerts() {
        if (!pendingScrollToAlerts) return
        pendingScrollToAlerts = false
        binding.nestedScroll.post {
            // Guard against the view being destroyed before the posted
            // runnable executes (e.g. rapid tab switching).
            if (_binding != null) {
                binding.nestedScroll.smoothScrollTo(
                    0,
                    binding.tvAllAlertsTitle.top
                )
            }
        }
    }

    private fun showSnapshotDialog(alert: Alert) {
        val mainActivity = activity as? MainActivity ?: return
        val baseUrl = mainActivity.container.getApiBaseUrl()
        val token = mainActivity.container.prefsManager.token
        val client = mainActivity.container.okHttpClient
        val dialog = AlertSnapshotDialogFragment.newInstance(alert, baseUrl, token, client)
        dialog.show(parentFragmentManager, AlertSnapshotDialogFragment.TAG)
    }

    /** Jump from an alert row to the camera's recording page at the
     *  alert's exact timestamp. Mirrors DashboardFragment's logic but
     *  simplified: find the camera, then launch CameraDetailActivity. */
    private fun jumpToCameraWithAlert(alert: Alert) {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token
        if (token.isNullOrEmpty()) {
            android.widget.Toast.makeText(requireContext(),
                R.string.not_logged_in,
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val startTs = alert.startTime.toLong()

        lifecycleScope.launch {
            try {
                val cameras = mainActivity.container.getRepository()
                    .listCameras(token, useCache = true)
                val cam = cameras.firstOrNull { it.id == alert.cameraId }
                    ?: cameras.firstOrNull { alert.cameraName.isNotEmpty() && it.name == alert.cameraName }
                    ?: cameras.firstOrNull { alert.cameraSlug.isNotEmpty() && it.stream?.streamName == alert.cameraSlug }
                if (cam == null) {
                    android.widget.Toast.makeText(requireContext(),
                        R.string.alert_jump_camera_not_found,
                        android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val cameraJson = NetworkFactory.json.encodeToString(
                    com.homedatacenter.app.data.model.Camera.serializer(), cam)
                val intent = Intent(requireContext(), CameraDetailActivity::class.java).apply {
                    putExtra(CameraDetailActivity.EXTRA_CAMERA_JSON, cameraJson)
                    putExtra(CameraDetailActivity.EXTRA_INITIAL_TIMESTAMP, startTs)
                }
                startActivity(intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("CamerasFragment",
                    "jumpToCameraWithAlert failed: ${e.message}", e)
                android.widget.Toast.makeText(requireContext(),
                    R.string.alert_jump_failed,
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (this::adapter.isInitialized) {
            adapter.releaseAllPlayers()
        }
        _binding = null
    }
}
