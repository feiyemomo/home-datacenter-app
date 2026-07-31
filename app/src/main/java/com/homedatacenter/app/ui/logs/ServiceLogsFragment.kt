package com.homedatacenter.app.ui.logs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.homedatacenter.app.R
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.model.SystemLog
import com.homedatacenter.app.data.model.SystemLogListData
import com.homedatacenter.app.data.model.WsMessage
import com.homedatacenter.app.data.model.WsMessageType
import com.homedatacenter.app.data.ws.HomeCenterWebSocket
import com.homedatacenter.app.data.ws.WsEventListener
import com.homedatacenter.app.databinding.FragmentServiceLogsBinding
import com.homedatacenter.app.ui.main.MainActivity
import kotlinx.coroutines.launch

/**
 * "服务日志" tab — replaces the old "报警" tab in the bottom nav.
 *
 * Displays system event logs (device online/offline, user login /
 * logout, camera status changes) fetched from
 * `GET /api/v1/system/logs` with offset-based pagination, and
 * live-prepends new entries pushed over the WebSocket `system.log`
 * topic.
 *
 * v1.6.37: also fetches the camera list (`GET /api/v1/cameras`) and
 * feeds it to [ServiceLogAdapter.updateCameraMap] so camera-related
 * log rows show the camera's CURRENT status as a subtitle. The
 * camera list is refreshed:
 *   - on initial load
 *   - on pull-to-refresh
 *   - after a WS camera.* event arrives (so the subtitle updates in
 *     real time when a camera recovers)
 *
 * Alert viewing is preserved elsewhere — the Dashboard's "最近报警"
 * card and the CameraDetail "报警记录" dialog still surface alerts.
 */
class ServiceLogsFragment : Fragment() {

    private var _binding: FragmentServiceLogsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: ServiceLogAdapter

    private val pageLimit = 50
    private var currentOffset = 0
    private var totalKnown = Long.MAX_VALUE
    private var isLoading = false
    private var hasMore = true

    private var logsWebSocket: HomeCenterWebSocket? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentServiceLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ServiceLogAdapter()
        val layoutManager = LinearLayoutManager(context)
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { resetAndLoad() }
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoading || !hasMore) return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val total = adapter.itemCount
                if (lastVisible >= total - 5) loadNextPage()
            }
        })

        setupWebSocket()
        resetAndLoad()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // Reconnect the WebSocket when the tab becomes visible so
        // live prepend resumes after the user switches tabs. Matches
        // DashboardFragment's onHiddenChanged pattern.
        if (!hidden && isAdded && _binding != null) {
            logsWebSocket?.connect()
        }
    }

    override fun onResume() {
        super.onResume()
        logsWebSocket?.connect()
    }

    // --- Pagination ---

    /** Pull-to-refresh: drop state and reload from offset 0. */
    private fun resetAndLoad() {
        currentOffset = 0
        totalKnown = Long.MAX_VALUE
        hasMore = true
        adapter.submitList(emptyList())
        loadNextPage()
        // v1.6.37: refresh the camera snapshot on each reset so the
        // status subtitles reflect the current fleet state, not a
        // stale copy from the last tab visit.
        loadCameraSnapshot()
    }

    /** Fetch one page (pageLimit rows starting at currentOffset). */
    private fun loadNextPage() {
        if (isLoading) return
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return

        isLoading = true
        if (currentOffset > 0) binding.progressLoadMore.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val auth = "Bearer $token"
                val resp = mainActivity.container.getApi()
                    .listSystemLogs(auth, limit = pageLimit, offset = currentOffset)
                val data = if (resp.isSuccess) {
                    resp.decodeData<SystemLogListData>()
                } else null
                val logs = data?.logs ?: emptyList()
                if (data != null) totalKnown = data.total

                val merged = adapter.currentList + logs
                adapter.submitList(merged)

                currentOffset += logs.size
                hasMore = logs.size >= pageLimit && merged.size < totalKnown
                showEmpty(merged.isEmpty())
                if (!hasMore && merged.isNotEmpty()) {
                    Toast.makeText(requireContext(),
                        R.string.logs_all_loaded, Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                if (currentOffset == 0) showEmpty(true)
            } finally {
                isLoading = false
                binding.progressLoadMore.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    /**
     * v1.6.37: fetch the current camera list and feed it to the
     * adapter so camera-related log rows can show the camera's
     * current status as a subtitle.
     *
     * Best-effort: failures are silently ignored (the subtitle just
     * stays hidden until the next successful fetch). Runs on the
     * lifecycle scope so it's cancelled when the fragment is
     * destroyed.
     *
     * Uses the repository's cached path with refreshCache=true so:
     *   - the first call hits the network (we need fresh status)
     *   - subsequent WS-triggered calls also hit the network (the
     *     whole point is to get the NEW status after an event)
     * The repository's cache is updated as a side effect, so the
     * Cameras tab also benefits from this refresh.
     *
     * Called from:
     *   - [resetAndLoad] (initial load + pull-to-refresh)
     *   - [handleWsMessage] when a camera.* event arrives (so the
     *     subtitle updates in real time)
     */
    private fun loadCameraSnapshot() {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return
        lifecycleScope.launch {
            try {
                val cameras = mainActivity.container.getRepository()
                    .listCameras(token, useCache = false, refreshCache = true)
                val map = cameras.associateBy { it.id }
                adapter.updateCameraMap(map)
            } catch (_: Exception) {
                // Best-effort — the subtitle stays hidden.
            }
        }
    }

    private fun showEmpty(show: Boolean) {
        binding.tvEmpty.visibility = if (show) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (show) View.GONE else View.VISIBLE
    }

    // --- WebSocket live prepend ---

    private fun setupWebSocket() {
        if (logsWebSocket != null) return
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return
        logsWebSocket = HomeCenterWebSocket(
            client = mainActivity.container.okHttpClient,
            wsUrl = mainActivity.container.getWsUrl(),
            token = token,
            listener = object : WsEventListener {
                override fun onConnected() {
                    logsWebSocket?.subscribe("system.log")
                }

                override fun onMessage(message: WsMessage) {
                    activity?.runOnUiThread {
                        if (_binding != null) handleWsMessage(message)
                    }
                }

                override fun onDisconnected(code: Int, reason: String?) = Unit

                override fun onError(throwable: Throwable, reconnectAttempt: Int) {
                    android.util.Log.w(TAG,
                        "WebSocket error, reconnect #$reconnectAttempt: ${throwable.message}")
                }
            },
        )
        logsWebSocket?.connect()
    }

    private fun handleWsMessage(message: WsMessage) {
        if (message.type != WsMessageType.EVENT) return
        if (message.topic != "system.log") return
        val payload = message.payload ?: return
        val log = try {
            NetworkFactory.json.decodeFromJsonElement(SystemLog.serializer(), payload)
        } catch (_: Exception) {
            return
        }

        // Dedupe by id — the backend may replay a log we already
        // fetched via the REST endpoint.
        val existing = adapter.currentList
        if (existing.any { it.id == log.id && log.id != 0L }) return

        val wasAtTop = (binding.recyclerView.layoutManager as? LinearLayoutManager)
            ?.findFirstVisibleItemPosition() == 0

        val merged = listOf(log) + existing
        adapter.submitList(merged)
        showEmpty(false)

        if (wasAtTop) {
            // Auto-scroll to top so the new entry is visible. Post
            // so the submitList diff has flushed to the layout.
            binding.recyclerView.post {
                if (_binding != null) binding.recyclerView.scrollToPosition(0)
            }
        }

        // v1.6.37: when a camera.* event arrives, refresh the camera
        // snapshot so the "当前状态" subtitle on the new row (and any
        // existing camera rows) reflects the latest status. This is
        // what makes the subtitle useful: the moment a camera comes
        // back online, the previously-shown "摄像头 X 离线" row flips
        // its subtitle from "当前状态：离线" to "当前状态：在线".
        if (log.event_type.startsWith("camera.")) {
            loadCameraSnapshot()
        }
    }

    override fun onDestroyView() {
        logsWebSocket?.disconnect()
        logsWebSocket = null
        binding.recyclerView.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "ServiceLogsFragment"
    }
}
