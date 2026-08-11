package com.homedatacenter.app.ui.cameras

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.homedatacenter.app.R
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.model.Alert
import com.homedatacenter.app.data.model.AlertListData
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.databinding.FragmentCamerasBinding
import com.homedatacenter.app.ui.alerts.AlertListAdapter
import com.homedatacenter.app.ui.alerts.AlertSnapshotDialogFragment
import com.homedatacenter.app.ui.main.MainActivity
import com.homedatacenter.app.util.AnimationHelper
import com.homedatacenter.app.util.CacheManager
import com.homedatacenter.app.util.NetworkMonitor
import com.homedatacenter.app.util.PrefetchManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CamerasFragment : Fragment() {

    private var _binding: FragmentCamerasBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: CameraAdapter
    private lateinit var allAlertsAdapter: AlertListAdapter

    // Set by DashboardFragment's "全部" button before switching to the
    // cameras tab. When this fragment becomes visible it scrolls to the
    // "全部报警" section and resets the flag.
    companion object {
        @Volatile
        var pendingScrollToAlerts = false
    }

    // Pagination state for the "全部报警" section. listAlerts only
    // supports a limit (no offset), so we page by growing the limit
    // (20, 40, 60...) and stop when a page returns fewer than the
    // requested count.
    private var alertsLimit = 20
    private var alertsLoading = false
    private var alertsHasMore = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCamerasBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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
                if (alertsHasMore && !alertsLoading && lastVisible >= total - 3) {
                    loadMoreAlerts()
                }
            }
        })

        binding.swipeRefresh.setOnRefreshListener { loadCamerasFromNetwork() }

        // Admin-only: show FAB for registering a new camera. The
        // server still enforces admin-gating on POST /api/v1/cameras,
        // so a non-admin who somehow sees the FAB will receive 403.
        val isAdmin = mainActivity.container.prefsManager.isAdmin
        binding.fabRegisterCamera?.visibility = if (isAdmin) View.VISIBLE else View.GONE
        binding.fabRegisterCamera?.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            RegisterCameraDialog(ctx, mainActivity.container) { loadCamerasFromNetwork() }.show()
        }

        // v1.5.7: pre-fetch ICE config so the first WebRTC stream
        // doesn't wait for an extra round-trip. Idempotent — the
        // AppContainer skips if already cached. Mirrors
        // DashboardFragment.refreshAll().
        mainActivity.container.prefetchIceConfig()
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
        val token = mainActivity.container.prefsManager.token ?: return

        // Immediately populate from cache
        val cached = CacheManager.getInstance(requireContext()).get<List<Camera>>("cameras.list", 30_000L)
        if (!cached.isNullOrEmpty()) {
            adapter.submitList(cached)
            showEmpty(cached.isEmpty())
        }

        // Silent background refresh
        loadCamerasFromNetwork()
    }

    private fun loadCamerasFromNetwork() {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return

        lifecycleScope.launch {
            try {
                // If offline, skip network call and just show cached data
                if (!NetworkMonitor.getInstance(requireContext()).isOnlineNow()) {
                    val cached = CacheManager.getInstance(requireContext()).get<List<Camera>>("cameras.list", 30_000L)
                    if (!cached.isNullOrEmpty()) {
                        adapter.submitList(cached)
                        showEmpty(cached.isEmpty())
                    }
                    return@launch
                }

                val cameras = mainActivity.container.getRepository().listCameras(
                    token, useCache = false, refreshCache = true
                )
                adapter.submitList(cameras)
                showEmpty(cameras.isEmpty())
                // v1.7.18: gentle fade-in once the network list lands.
                AnimationHelper.fadeIn(binding.recyclerView, 300)

                // Cache the result for offline access
                CacheManager.getInstance(requireContext()).set("cameras.list", cameras)

                // Prefetch ICE config for faster camera detail loading
                PrefetchManager.getInstance(requireContext()).prefetchOnIdle("cameras.ice", {
                    mainActivity.container.getRepository().getIceConfig(token)
                }, 2000L)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Network failure: keep cached data
            } finally {
                if (view != null) {
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun showEmpty(show: Boolean) {
        binding.tvEmpty.visibility = if (show) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (show) View.GONE else View.VISIBLE
    }

    // --- "全部报警" section ---

    /** Load the first page of all alerts (limit = 20). */
    private fun loadAllAlerts() {
        if (alertsLoading) return
        alertsLimit = 20
        alertsHasMore = true
        loadMoreAlerts()
    }

    /** Fetch the next page of alerts, growing the limit since the API
     *  has no offset parameter. */
    private fun loadMoreAlerts() {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return
        if (alertsLoading || !alertsHasMore) return
        alertsLoading = true

        lifecycleScope.launch {
            try {
                val resp = mainActivity.container.getApi()
                    .listAlerts("Bearer $token", limit = alertsLimit)
                val alerts = if (resp.isSuccess) {
                    resp.decodeData<AlertListData>()?.alerts ?: emptyList()
                } else {
                    emptyList()
                }
                allAlertsAdapter.submitList(alerts)
                binding.tvAllAlertsEmpty.visibility =
                    if (alerts.isEmpty()) View.VISIBLE else View.GONE
                // v1.7.18: fade the "全部报警" list in when its first
                // page lands. Skip pagination — re-fading on every page
                // would flash the list while the user scrolls.
                if (alertsLimit == 20) {
                    AnimationHelper.fadeIn(binding.rvAllAlerts, 300)
                }
                // If we got fewer than requested, there are no more pages.
                alertsHasMore = alerts.size >= alertsLimit
                alertsLimit += 20
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Network failure: keep whatever we already have.
            } finally {
                alertsLoading = false
            }
        }
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
