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

    // Filters
    private var selectedLevel: String? = null
    private var searchKeyword: String? = null
    private var searchDebounceJob: kotlinx.coroutines.Job? = null

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

        setupFilterControls()
        setupWebSocket()
        resetAndLoad()
    }

    private fun setupFilterControls() {
        binding.chipGroupLevel.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedLevel = when (checkedIds.firstOrNull()) {
                R.id.chipLevelCritical -> "critical"
                R.id.chipLevelWarning -> "warning"
                R.id.chipLevelNormal -> "normal"
                R.id.chipLevelInfo -> "info"
                else -> null
            }
            resetAndLoad()
        }

        binding.etSearchKeyword.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val kw = s?.toString()?.trim()
                binding.btnClearSearch.visibility = if (!kw.isNullOrEmpty()) View.VISIBLE else View.GONE
                searchDebounceJob?.cancel()
                searchDebounceJob = lifecycleScope.launch {
                    kotlinx.coroutines.delay(300L)
                    val target = kw?.ifEmpty { null }
                    if (target != searchKeyword) {
                        searchKeyword = target
                        resetAndLoad()
                    }
                }
            }
        })

        binding.etSearchKeyword.setOnEditorActionListener { _, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN)
            ) {
                searchDebounceJob?.cancel()
                val kw = binding.etSearchKeyword.text.toString().trim()
                searchKeyword = kw.ifEmpty { null }
                resetAndLoad()
                true
            } else false
        }

        binding.btnClearSearch.setOnClickListener {
            searchDebounceJob?.cancel()
            binding.etSearchKeyword.setText("")
            searchKeyword = null
            binding.btnClearSearch.visibility = View.GONE
            resetAndLoad()
        }
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

        // Check cache before making API call (only for unfiltered default page)
        val isFiltered = selectedLevel != null || !searchKeyword.isNullOrBlank()
        val cacheKey = "logs.page.${currentOffset / pageLimit}.${selectedLevel ?: "all"}.${searchKeyword ?: ""}"
        if (!isFiltered) {
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
                if (view != null) {
                    binding.progressLoadMore.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                }
                return
            }
        }

        lifecycleScope.launch {
            try {
                val auth = "Bearer $token"
                val reqLimit = if (!searchKeyword.isNullOrBlank()) 100 else pageLimit
                val resp = mainActivity.container.getApi()
                    .listSystemLogs(
                        auth,
                        limit = reqLimit,
                        offset = currentOffset,
                        level = selectedLevel,
                        keyword = searchKeyword
                    )
                val data = if (resp.isSuccess) {
                    resp.decodeData<SystemLogListData>()
                } else null
                val rawLogs = data?.logs ?: emptyList()
                if (data != null) totalKnown = data.total

                val kw = searchKeyword
                val logs = if (!kw.isNullOrBlank()) {
                    rawLogs.filter { log ->
                        log.message.contains(kw, ignoreCase = true) ||
                        log.event_type.contains(kw, ignoreCase = true) ||
                        log.source.contains(kw, ignoreCase = true) ||
                        log.payload.contains(kw, ignoreCase = true)
                    }
                } else rawLogs

                // v1.6.39: route each log to its section by level.
                for (log in logs) {
                    if (log.level == SystemLogLevel.CRITICAL) {
                        criticalLogs.add(log)
                    } else {
                        otherLogs.add(log)
                    }
                }
                rebuildDisplayList()

                // Cache the fetched page if not filtered
                if (!isFiltered) {
                    CacheManager.getInstance(requireContext()).set(cacheKey, logs)
                }

                currentOffset += rawLogs.size
                hasMore = rawLogs.size >= reqLimit && (criticalLogs.size + otherLogs.size) < totalKnown
                val emptyMsg = if (!kw.isNullOrBlank()) "未搜索到与「$kw」相关的系统日志" else getString(R.string.no_logs)
                showEmpty(criticalLogs.isEmpty() && otherLogs.isEmpty(), emptyMsg)
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

    private fun showEmpty(show: Boolean, message: String = getString(R.string.no_logs)) {
        binding.tvEmpty.visibility = if (show) View.VISIBLE else View.GONE
        binding.tvEmpty.text = message
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
            wsUrlProvider = { mainActivity.container.getWsUrl() },
            onConnectionFailed = { failedWsUrl ->
                android.util.Log.w(TAG, "Logs WS connection dropped on $failedWsUrl")
            }
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

        selectedLevel?.let { filterLvl ->
            if (log.level != filterLvl) return
        }
        searchKeyword?.let { kw ->
            val match = log.message.contains(kw, ignoreCase = true) ||
                    log.source.contains(kw, ignoreCase = true) ||
                    log.event_type.contains(kw, ignoreCase = true) ||
                    log.payload.contains(kw, ignoreCase = true)
            if (!match) return
        }

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
        searchDebounceJob?.cancel()
        searchDebounceJob = null
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
