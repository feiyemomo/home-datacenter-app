package com.homedatacenter.app.ui.ops

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.BanEntry
import com.homedatacenter.app.data.model.DefenseOverview
import com.homedatacenter.app.data.model.NetworkStatus
import com.homedatacenter.app.data.model.SecurityEventItem
import com.homedatacenter.app.data.model.SecurityWhitelistEntry
import com.homedatacenter.app.data.model.SystemLog
import com.homedatacenter.app.data.model.SystemStatus
import com.homedatacenter.app.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OpsFragment — Stage B Host Fragment.
 *
 * Hosts [OpsPageContent] via [ComposeView], managing data fetching and lifecycle
 * for Host Metrics, Microservices status, Docker containers, Defense Center and Service Logs.
 */
class OpsFragment : Fragment() {

    private lateinit var container: AppContainer

    companion object {
        const val TAB_SYSTEM = 0
        const val TAB_SECURITY = 1
        const val TAB_LOGS = 2

        @Volatile
        var targetSubTab: Int = TAB_SYSTEM

        private const val CACHE_TTL_MS = 300_000L // 5 minutes cache lifetime
        private const val THROTTLE_MS = 30_000L // 30s minimum interval for silent auto-refresh
    }

    private val _systemStatus = MutableStateFlow<SystemStatus?>(null)
    private val _networkStatus = MutableStateFlow<NetworkStatus?>(null)
    private val _defenseOverview = MutableStateFlow<DefenseOverview?>(null)
    private val _bannedIps = MutableStateFlow<List<BanEntry>>(emptyList())
    private val _whitelist = MutableStateFlow<List<SecurityWhitelistEntry>>(emptyList())
    private val _securityEvents = MutableStateFlow<List<SecurityEventItem>>(emptyList())
    private val _systemLogs = MutableStateFlow<List<SystemLog>>(emptyList())
    private val _isRefreshing = MutableStateFlow(false)

    private var currentSubTab by mutableIntStateOf(TAB_SYSTEM)
    private var lastRefreshTimestamp: Long = 0L

    override fun onAttach(context: Context) {
        super.onAttach(context)
        container = (requireActivity().application as HomeCenterApp).container
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        currentSubTab = targetSubTab
        loadFromCache()

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                val systemStatus by _systemStatus.collectAsState()
                val networkStatus by _networkStatus.collectAsState()
                val defenseOverview by _defenseOverview.collectAsState()
                val bannedIps by _bannedIps.collectAsState()
                val whitelist by _whitelist.collectAsState()
                val securityEvents by _securityEvents.collectAsState()
                val systemLogs by _systemLogs.collectAsState()
                val isRefreshing by _isRefreshing.collectAsState()

                var showManualBanDialog by remember { mutableStateOf(false) }
                var showAddWhitelistDialog by remember { mutableStateOf(false) }

                OpsPageContent(
                    systemStatus = systemStatus,
                    networkStatus = networkStatus,
                    defenseOverview = defenseOverview,
                    bannedIps = bannedIps,
                    whitelist = whitelist,
                    securityEvents = securityEvents,
                    systemLogs = systemLogs,
                    isRefreshing = isRefreshing,
                    selectedSubTab = currentSubTab,
                    onSubTabChanged = { tabIndex ->
                        currentSubTab = tabIndex
                        targetSubTab = tabIndex
                    },
                    onRefresh = { refreshAll(silent = false) },
                    onCleanCache = { cleanSystemCache() },
                    onManualBanClick = { showManualBanDialog = true },
                    onAddWhitelistClick = { showAddWhitelistDialog = true },
                    onUnbanIp = { ip -> unbanIp(ip) },
                    onRemoveWhitelist = { entry -> removeWhitelistEntry(entry) }
                )

                if (showManualBanDialog) {
                    ManualBanDialog(
                        onDismiss = { showManualBanDialog = false },
                        onConfirm = { ip, durationMin, reason ->
                            manualBanIpWithDuration(ip, durationMin, reason)
                        }
                    )
                }

                if (showAddWhitelistDialog) {
                    AddWhitelistDialog(
                        onDismiss = { showAddWhitelistDialog = false },
                        onConfirm = { ip, note ->
                            addWhitelistIp(ip, note)
                        }
                    )
                }
            }
        }
    }

    private fun loadFromCache() {
        val ctx = context ?: return
        val cache = com.homedatacenter.app.util.CacheManager.getInstance(ctx)
        cache.get<SystemStatus>("ops.system_status", CACHE_TTL_MS)?.let { _systemStatus.value = it }
        cache.get<NetworkStatus>("ops.network_status", CACHE_TTL_MS)?.let { _networkStatus.value = it }
        cache.get<DefenseOverview>("ops.defense_overview", CACHE_TTL_MS)?.let { _defenseOverview.value = it }
        cache.get<List<BanEntry>>("ops.banned_ips", CACHE_TTL_MS)?.let { _bannedIps.value = it }
        cache.get<List<SecurityWhitelistEntry>>("ops.whitelist", CACHE_TTL_MS)?.let { _whitelist.value = it }
        cache.get<List<SecurityEventItem>>("ops.security_events", CACHE_TTL_MS)?.let { _securityEvents.value = it }
        cache.get<List<SystemLog>>("ops.system_logs", CACHE_TTL_MS)?.let { _systemLogs.value = it }
    }

    override fun onResume() {
        super.onResume()
        if (isAdded && !isHidden) {
            currentSubTab = targetSubTab
            val now = System.currentTimeMillis()
            if (now - lastRefreshTimestamp > THROTTLE_MS) {
                refreshAll(silent = true)
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded) {
            currentSubTab = targetSubTab
            val now = System.currentTimeMillis()
            if (now - lastRefreshTimestamp > THROTTLE_MS) {
                refreshAll(silent = true)
            }
        }
    }

    private fun showToast(message: String) {
        context?.let { ctx ->
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshAll(silent: Boolean = false) {
        val token = container.prefsManager.token ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            if (!silent) {
                _isRefreshing.value = true
            }
            try {
                withContext(Dispatchers.IO) {
                    val repo = container.getRepository()
                    val ctx = context
                    val cache = if (ctx != null) com.homedatacenter.app.util.CacheManager.getInstance(ctx) else null

                    runCatching { repo.getSystemStatus(token, useCache = false, refreshCache = true) }
                        .onSuccess {
                            _systemStatus.value = it
                            cache?.set("ops.system_status", it)
                        }

                    runCatching { repo.getNetworkStatus(token, refresh = true) }
                        .onSuccess {
                            _networkStatus.value = it
                            cache?.set("ops.network_status", it)
                        }

                    runCatching { repo.getDefenseOverview(token) }
                        .onSuccess {
                            _defenseOverview.value = it
                            cache?.set("ops.defense_overview", it)
                        }

                    runCatching { repo.getBannedIPs(token) }
                        .onSuccess {
                            _bannedIps.value = it
                            cache?.set("ops.banned_ips", it)
                        }

                    runCatching { repo.getSecurityWhitelist(token) }
                        .onSuccess {
                            _whitelist.value = it
                            cache?.set("ops.whitelist", it)
                        }

                    runCatching { repo.getSecurityEvents(token, limit = 50) }
                        .onSuccess {
                            _securityEvents.value = it
                            cache?.set("ops.security_events", it)
                        }

                    runCatching { repo.listSystemLogs(token, limit = 100) }
                        .onSuccess {
                            _systemLogs.value = it.logs
                            cache?.set("ops.system_logs", it.logs)
                        }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep showing existing/cached data on network error, do not wipe to null
                if (!silent) {
                    showToast("数据刷新失败: ${e.message}")
                }
            } finally {
                lastRefreshTimestamp = System.currentTimeMillis()
                _isRefreshing.value = false
            }
        }
    }

    private fun cleanSystemCache() {
        val token = container.prefsManager.token ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    container.getRepository().cleanSystemCache(token)
                }
                showToast("转码切片缓存已清理")
                refreshAll(silent = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("清理缓存失败: ${e.message}")
            }
        }
    }

    private fun manualBanIpWithDuration(ip: String, durationMin: Int, reason: String) {
        val token = container.prefsManager.token ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    container.getRepository().manualBan(token, ip, durationMin, reason)
                }
                showToast("已成功封禁 $ip")
                refreshAll(silent = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("封禁失败: ${e.message}")
            }
        }
    }

    private fun addWhitelistIp(ip: String, note: String) {
        val token = container.prefsManager.token ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    container.getRepository().addSecurityWhitelist(token, ip, note)
                }
                showToast("已添加白名单: $ip")
                refreshAll(silent = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("添加白名单失败: ${e.message}")
            }
        }
    }

    private fun unbanIp(ip: String) {
        val token = container.prefsManager.token ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    container.getRepository().manualUnban(token, ip)
                }
                showToast("已解封 $ip")
                refreshAll(silent = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("解封失败: ${e.message}")
            }
        }
    }

    private fun removeWhitelistEntry(entry: String) {
        val token = container.prefsManager.token ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    container.getRepository().deleteSecurityWhitelist(token, entry)
                }
                showToast("已移除白名单 $entry")
                refreshAll(silent = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("移除白名单失败: ${e.message}")
            }
        }
    }
}
