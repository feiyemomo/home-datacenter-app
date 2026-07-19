package com.homedatacenter.app.ui.cameras

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.databinding.FragmentCamerasBinding
import com.homedatacenter.app.ui.main.MainActivity
import kotlinx.coroutines.launch

class CamerasFragment : Fragment() {

    private var _binding: FragmentCamerasBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: CameraAdapter
    private var recordingsDialog: RecordingsDialog? = null
    private var alertsDialog: AlertsDialog? = null

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

        adapter = CameraAdapter(
            onRecordingsClick = { camera -> showRecordingsDialog(camera) },
            onAlertsClick = { camera -> showAlertsDialog(camera) },
            baseUrl = baseUrl,
            token = token,
            okHttpClient = okHttpClient
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadCamerasFromNetwork() }
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
        val cached = mainActivity.container.prefsManager.cachedCameras
        if (!cached.isNullOrEmpty()) {
            try {
                val cameras = NetworkFactory.json.decodeFromString<List<Camera>>(cached)
                adapter.submitList(cameras)
                showEmpty(cameras.isEmpty())
            } catch (_: Exception) {
            }
        }

        // Silent background refresh
        loadCamerasFromNetwork()
    }

    private fun loadCamerasFromNetwork() {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return

        lifecycleScope.launch {
            try {
                val cameras = mainActivity.container.getRepository().listCameras(
                    token, useCache = false, refreshCache = true
                )
                adapter.submitList(cameras)
                showEmpty(cameras.isEmpty())
            } catch (_: Exception) {
                // Network failure: keep cached data
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun showRecordingsDialog(camera: Camera) {
        val context = context ?: return
        val mainActivity = activity as? MainActivity ?: return
        recordingsDialog?.dismiss()
        recordingsDialog = RecordingsDialog(context, camera, mainActivity.container).apply { show() }
    }

    private fun showAlertsDialog(camera: Camera) {
        val context = context ?: return
        val mainActivity = activity as? MainActivity ?: return
        alertsDialog?.dismiss()
        alertsDialog = AlertsDialog(context, camera, mainActivity.container).apply { show() }
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
        recordingsDialog?.dismiss()
        alertsDialog?.dismiss()
        recordingsDialog = null
        alertsDialog = null
        _binding = null
    }
}
