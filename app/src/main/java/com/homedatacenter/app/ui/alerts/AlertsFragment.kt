package com.homedatacenter.app.ui.alerts

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.homedatacenter.app.R
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.model.AlertListData
import com.homedatacenter.app.databinding.FragmentAlertsBinding
import com.homedatacenter.app.ui.cameras.CameraDetailActivity
import com.homedatacenter.app.ui.main.MainActivity
import kotlinx.coroutines.launch

class AlertsFragment : Fragment() {

    private var _binding: FragmentAlertsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: AlertListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlertsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainActivity = activity as? MainActivity ?: return
        val baseUrl = mainActivity.container.getApiBaseUrl()
        val token = mainActivity.container.prefsManager.token
        val okHttpClient = mainActivity.container.okHttpClient

        adapter = AlertListAdapter(
            baseUrl = baseUrl,
            token = token,
            okHttpClient = okHttpClient,
            onSnapshotClick = { alert ->
                val dialog = AlertSnapshotDialogFragment.newInstance(
                    alert = alert,
                    baseUrl = baseUrl,
                    token = token,
                    okHttpClient = okHttpClient
                )
                dialog.show(parentFragmentManager, AlertSnapshotDialogFragment.TAG)
            },
            // v1.6.0: "查看录像" now jumps directly to the camera's
            // recording page at the alert's exact timestamp. We fetch
            // the camera list, find the matching camera by id (or by
            // name as fallback when the alert's cameraId is null —
            // happens when the backend's LookupByFrigateSlug failed),
            // then launch CameraDetailActivity with the camera JSON +
            // alert's start_time as EXTRA_INITIAL_TIMESTAMP. The
            // activity auto-opens RecordingsDialog with that timestamp
            // so the user lands at the alert's exact moment.
            onJumpCamera = { alert -> jumpToCameraAtTimestamp(alert) },
            onRowClick = null
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadAlerts() }
        loadAlerts()
    }

    /**
     * v1.6.0: launches CameraDetailActivity with the alert's start
     * time as the initial playback position. Fetches the camera list
     * (uses cache to avoid a network round-trip when possible), finds
     * the matching camera by id or name, then passes the camera JSON
     * + alert.startTime to the activity.
     *
     * On failure (no matching camera, network error), shows a toast
     * and falls back to the old behavior of just switching to the
     * cameras tab so the user can pick a camera manually.
     */
    private fun jumpToCameraAtTimestamp(alert: com.homedatacenter.app.data.model.Alert) {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token
        if (token.isNullOrEmpty()) {
            Toast.makeText(requireContext(), R.string.not_logged_in, Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(requireContext(),
                        R.string.alert_jump_camera_not_found,
                        Toast.LENGTH_SHORT).show()
                    // Fallback: switch to cameras tab so the user can pick.
                    mainActivity.binding.bottomNav.selectedItemId = R.id.nav_cameras
                    return@launch
                }
                val cameraJson = NetworkFactory.json.encodeToString(
                    com.homedatacenter.app.data.model.Camera.serializer(), cam)
                val intent = Intent(requireContext(), CameraDetailActivity::class.java).apply {
                    putExtra(CameraDetailActivity.EXTRA_CAMERA_JSON, cameraJson)
                    putExtra(CameraDetailActivity.EXTRA_INITIAL_TIMESTAMP, startTs)
                }
                startActivity(intent)
            } catch (e: Exception) {
                android.util.Log.w("AlertsFragment",
                    "jumpToCameraAtTimestamp failed: ${e.message}", e)
                Toast.makeText(requireContext(),
                    R.string.alert_jump_failed,
                    Toast.LENGTH_SHORT).show()
                mainActivity.binding.bottomNav.selectedItemId = R.id.nav_cameras
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isAdded && this::adapter.isInitialized) {
            loadAlerts()
        }
    }

    private fun loadAlerts() {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return

        lifecycleScope.launch {
            try {
                val auth = "Bearer $token"
                val resp = mainActivity.container.getApi().listAlerts(auth, limit = 50)
                val alerts = if (resp.isSuccess) {
                    resp.decodeData<AlertListData>()?.alerts ?: emptyList()
                } else {
                    emptyList()
                }
                adapter.submitList(alerts)
                showEmpty(alerts.isEmpty())
            } catch (_: Exception) {
                showEmpty(true)
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
        _binding = null
    }
}
