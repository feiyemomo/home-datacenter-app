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
import com.homedatacenter.app.util.CacheManager
import com.homedatacenter.app.data.model.SystemLog
import com.homedatacenter.app.data.model.SystemLogListData
import com.homedatacenter.app.data.model.SystemLogLevel
import com.homedatacenter.app.data.model.WsMessage
import com.homedatacenter.app.data.model.WsMessageType
import com.homedatacenter.app.data.ws.HomeCenterWebSocket
import com.homedatacenter.app.data.ws.WsEventListener
import com.homedatacenter.app.databinding.FragmentServiceLogsBinding
import com.homedatacenter.app.ui.main.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "服务日志" tab — v1.6.39 redesign with two-section collapsible display.
 *
 * Logs are split by severity:
 *   - "待处理日志" (critical/pending): camera/device offline — always visible
 *   - "所有日志" (all): normal + info + critical — expanded by default,
 *     expandable via header tap. Critical logs are highlighted with
 *     a red icon tint within this section.
 *
 * Data is fetched from `GET /api/v1/system/logs` with offset-based
 * pagination, and live-prepended via the WebSocket `system.log` topic.
 * Incoming logs are routed to the appropriate section based on level.
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

    // v1.6.39: two separate lists for the two sections.
    private val criticalLogs = mutableListOf<SystemLog>()
    private val otherLogs = mutableListOf<SystemLog>()
    private var otherCollapsed = false

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

        adapter = ServiceLogAdapter(
            onHeaderClick = { section ->
                if (section == LogListItem.Section.OTHER) {
                    otherCollapsed = !otherCollapsed
                    rebuildDisplayList()
                }
            },
            onVerify = { log -> verifyLog(log) },
        )
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
        if (!hidden && isAdded && _binding != null) {
            logsWebSocket?.connect()
        }
    }

    override fun onResume() {
        super.onResume()
        logsWebSocket?.connect()
    }

    // --- Pagination ---

    private fun resetAndLoad() {
        currentOffset = 0
        totalKnown = Long.MAX_VALUE
        hasMore = true
        criticalLogs.clear()
        otherLogs.clear()
        rebuildDisplayList()
        loadNextPage()
        loadCameraSnapshot()
    }

    private fun loadNextPage() {
        if (isLoading) return
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return

        isLoading = true
        if (currentOffset > 0) binding.progressLoadMore.visibility = View.VISIBLE

        // Check cache before making API call
        val cacheKey = "logs.page.${currentOffset / pageLimit}"
        val cached = CacheManager.getInstance(requireContext()).get<List<SystemLog>>(cacheKey, 60_000L)
        if (cached != null) {
            for (log in cached) {
                if (log.level == SystemLogLevel.CRITICAL) {
                    criticalLogs.add(log)
                } else {
                    otherLogs.add(log)
                }
            }
            rebuildDisplayList()
            currentOffset += cached.size
            hasMore = cached.size >= pageLimit
            isLoading = false
            // v1.8.21: must stop the swipe-refresh spinner on the
            // cache-hit path too — previously this early return
            // skipped the finally block, leaving the spinner
            // spinning forever after a pull-to-refresh.
            if (view != null) {
                binding.progressLoadMore.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
            return
        }

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

                // v1.6.39: route each log to its section by level.
                for (log in logs) {
                    if (log.level == SystemLogLevel.CRITICAL) {
                        criticalLogs.add(log)
                    } else {
                        otherLogs.add(log)
                    }
                }
                rebuildDisplayList()

                // Cache the fetched page
                CacheManager.getInstance(requireContext()).set(cacheKey, logs)

                currentOffset += logs.size
                hasMore = logs.size >= pageLimit && (criticalLogs.size + otherLogs.size) < totalKnown
                showEmpty(criticalLogs.isEmpty() && otherLogs.isEmpty())
                if (!hasMore && (criticalLogs.isNotEmpty() || otherLogs.isNotEmpty())) {
                    Toast.makeText(requireContext(),
                        R.string.logs_all_loaded, Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (currentOffset == 0 && view != null) showEmpty(true)
            } finally {
                isLoading = false
                if (view != null) {
                    binding.progressLoadMore.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    /**
     * v1.6.39: Build the display list from the two section lists,
     * inserting section headers and respecting collapse state.
     *
     * v1.8.x: renamed sections as requested:
     *   - "重要日志" → "待处理日志" (pending/critical logs)
     *   - "其他日志" → "所有日志" (all logs, including critical ones)
     * Critical logs are shown in BOTH sections: "待处理日志" for quick
     * action items, "所有日志" for full history browsing.
     */
    private fun rebuildDisplayList() {
        val items = mutableListOf<LogListItem>()

        // Pending section (always visible) — shows only critical logs.
        if (criticalLogs.isNotEmpty()) {
            items.add(LogListItem.Header(
                section = LogListItem.Section.IMPORTANT,
                title = "待处理日志",
                count = criticalLogs.size,
                collapsed = false,
            ))
            criticalLogs.forEach { items.add(LogListItem.LogEntry(it)) }
        }

        // All logs section (expanded by default) — shows ALL logs
        // including critical ones, so the user can browse the full
        // history. Critical logs are visually highlighted with the
        // red icon tint (see colorForLevel in LogViewHolder).
        val allLogs = (criticalLogs + otherLogs).sortedByDescending { it.ts }
        if (allLogs.isNotEmpty()) {
            items.add(LogListItem.Header(
                section = LogListItem.Section.OTHER,
                title = "所有日志",
                count = allLogs.size,
                collapsed = otherCollapsed,
            ))
            if (!otherCollapsed) {
                allLogs.forEach { items.add(LogListItem.LogEntry(it)) }
            }
        }

        adapter.submitList(items)
    }

    private fun loadCameraSnapshot() {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return
        lifecycleScope.launch {
            try {
                val cameras = mainActivity.container.getRepository()
                    .listCameras(token, useCache = false, refreshCache = true)
                val map = cameras.associateBy { it.id }
                adapter.updateCameraMap(map)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun showEmpty(show: Boolean) {
        binding.tvEmpty.visibility = if (show) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (show) View.GONE else View.VISIBLE
    }

    /**
     * v1.8.21: "核查" button handler — PATCHes the log via the API
     * to downgrade its level from critical to normal. On success
     * the log is moved from [criticalLogs] to [otherLogs] so it
     * disappears from the "待处理日志" section but stays in the
     * "所有日志" section for full audit history.
     *
     * On failure the log stays in criticalLogs and a toast is
     * shown so the user can retry. The adapter's [pendingVerifyIds]
     * prevents duplicate taps while the request is in-flight.
     */
    private fun verifyLog(log: SystemLog) {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return
        lifecycleScope.launch {
            var success = false
            try {
                val auth = "Bearer $token"
                val resp = mainActivity.container.getApi()
                    .verifySystemLog(auth, log.id)
                success = resp.isSuccess
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Network failure — log stays in criticalLogs.
            }
            if (_binding == null) return@launch
            if (success) {
                // Invalidate cached log pages so the next pull-to-refresh
                // fetches fresh data from the server instead of showing
                // stale entries whose level was just downgraded.
                CacheManager.getInstance(requireContext()).clear("logs.page.")
                // Move the log from critical (pending) to other
                // (all logs). The log is NOT removed — it stays
                // in the audit trail, just no longer highlighted
                // as pending.
                criticalLogs.removeAll { it.id == log.id }
                val verified = log.copy(level = SystemLogLevel.NORMAL)
                otherLogs.add(0, verified)
                rebuildDisplayList()
                showEmpty(criticalLogs.isEmpty() && otherLogs.isEmpty())
                Toast.makeText(requireContext(),
                    R.string.log_verified_deleted, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(),
                    R.string.log_verify_failed, Toast.LENGTH_SHORT).show()
            }
        }
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

        // Dedupe by id.
        if (criticalLogs.any { it.id == log.id && log.id != 0L }) return
        if (otherLogs.any { it.id == log.id && log.id != 0L }) return

        // v1.6.39: route to the appropriate section.
        if (log.level == SystemLogLevel.CRITICAL) {
            criticalLogs.add(0, log)
        } else {
            otherLogs.add(0, log)
        }
        rebuildDisplayList()
        showEmpty(false)

        // Auto-scroll to top if the user was already at the top.
        val wasAtTop = (binding.recyclerView.layoutManager as? LinearLayoutManager)
            ?.findFirstVisibleItemPosition() == 0
        if (wasAtTop) {
            binding.recyclerView.post {
                if (_binding != null) binding.recyclerView.scrollToPosition(0)
            }
        }

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
