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
import com.homedatacenter.app.R
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.databinding.FragmentCamerasBinding
import com.homedatacenter.app.ui.main.MainActivity
import com.homedatacenter.app.util.CacheManager
import com.homedatacenter.app.util.NetworkMonitor
import com.homedatacenter.app.util.PrefetchManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CamerasFragment : Fragment() {

    private var _binding: FragmentCamerasBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: CameraAdapter

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
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded) {
            loadCamerasFromCache()
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

                // Cache the result for offline access
                CacheManager.getInstance(requireContext()).set("cameras.list", cameras)

                // Prefetch ICE config for faster camera detail loading
                PrefetchManager.getInstance(requireContext()).prefetchOnIdle("cameras.ice", {
                    mainActivity.container.getRepository().getIceConfig(token)
                }, 2000L)
            } catch (_: Exception) {
                // Network failure: keep cached data
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun showEmpty(show: Boolean) {
        binding.tvEmpty.visibility = if (show) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (show) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (this::adapter.isInitialized) {
            adapter.releaseAllPlayers()
        }
        _binding = null
    }
}
