package com.homedatacenter.app.ui.ops

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Patterns
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.model.BanEntry
import com.homedatacenter.app.data.model.DefenseOverview
import com.homedatacenter.app.data.model.DeviceList
import com.homedatacenter.app.data.model.SecurityEventItem
import com.homedatacenter.app.data.model.SecurityWhitelistEntry
import com.homedatacenter.app.data.model.SystemLog
import com.homedatacenter.app.data.model.SystemLogListData
import com.homedatacenter.app.data.model.SystemLogLevel
import com.homedatacenter.app.data.model.SystemStatus
import com.homedatacenter.app.data.model.SystemStatusMetrics
import com.homedatacenter.app.data.model.WsMessage
import com.homedatacenter.app.data.model.WsMessageType
import com.homedatacenter.app.data.ws.HomeCenterWebSocket
import com.homedatacenter.app.data.ws.WsEventListener
import com.homedatacenter.app.databinding.DialogAddRecipientBinding
import com.homedatacenter.app.databinding.DialogAddWhitelistBinding
import com.homedatacenter.app.databinding.DialogManualBanBinding
import com.homedatacenter.app.databinding.DialogSecurityConfigBinding
import com.homedatacenter.app.databinding.FragmentOpsBinding
import com.homedatacenter.app.databinding.ItemStatCardBinding
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.ui.admin.BannedIpAdapter
import com.homedatacenter.app.ui.admin.SecurityEventAdapter
import com.homedatacenter.app.ui.admin.WhitelistAdapter
import com.homedatacenter.app.ui.logs.LogListItem
import com.homedatacenter.app.ui.logs.ServiceLogAdapter
import com.homedatacenter.app.ui.main.MainActivity
import com.homedatacenter.app.util.CacheManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * "运维" (Ops & Security) unified tab:
 * Merges Host Resource Monitoring & Infrastructure, Security Defense Center, and Service Logs.
 *
 * Subtabs:
 * 0: 系统监控 (System & Host Metrics)
 * 1: 安全防御 (Security Defense Center)
 * 2: 服务日志 (Service Logs with Working Search & Filters)
 */
class OpsFragment : Fragment() {

    private var _binding: FragmentOpsBinding? = null
    private val binding get() = _binding!!

    private lateinit var container: AppContainer

    // Subtab indices
    companion object {
        const val TAB_SYSTEM = 0
        const val TAB_SECURITY = 1
        const val TAB_LOGS = 2

        @Volatile
        var targetSubTab: Int = TAB_SYSTEM
    }

    private var currentMainTab = TAB_SYSTEM

    // --- Security Defense State ---
    private lateinit var bannedAdapter: BannedIpAdapter
    private lateinit var whitelistAdapter: WhitelistAdapter
    private lateinit var securityEventAdapter: SecurityEventAdapter
    private var currentSecuritySubTab = 0 // 0: bans, 1: whitelist, 2: events, 3: host-logs
    private var cachedOverview: DefenseOverview? = null

    // --- Service Logs State ---
    private lateinit var logsAdapter: ServiceLogAdapter
    private val pageLimit = 50
    private var currentOffset = 0
    private var totalKnown = Long.MAX_VALUE
    private var isLoadingLogs = false
    private var hasMoreLogs = true
    private val criticalLogs = mutableListOf<SystemLog>()
    private val otherLogs = mutableListOf<SystemLog>()
    private var otherCollapsed = false
    private var selectedLevel: String? = null
    private var searchKeyword: String? = null
    private var logsWebSocket: HomeCenterWebSocket? = null
    private var searchDebounceJob: Job? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        container = (requireActivity().application as HomeCenterApp).container
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOpsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTopTabs()
        setupSwipeRefresh()
        setupSystemOpsTab()
        setupSecurityOpsTab()
        setupLogsOpsTab()

        // Switch to target subtab if specified externally
        if (targetSubTab != currentMainTab && targetSubTab in 0..2) {
            binding.tabLayoutOps.getTabAt(targetSubTab)?.select()
            currentMainTab = targetSubTab
            switchMainTab(currentMainTab)
        } else {
            switchMainTab(currentMainTab)
        }

        // Silent periodic background refresh for active data
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(15_000L)
                    if (_binding == null) break
                    if (binding.swipeRefreshOps.isRefreshing) continue
                    when (currentMainTab) {
                        TAB_SYSTEM -> loadSystemMetrics(silent = true)
                        TAB_SECURITY -> loadSecurityData(silent = true)
                        TAB_LOGS -> { /* logs are live-pushed via WebSocket */ }
                    }
                }
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded && _binding != null) {
            if (targetSubTab != currentMainTab && targetSubTab in 0..2) {
                binding.tabLayoutOps.getTabAt(targetSubTab)?.select()
                currentMainTab = targetSubTab
                switchMainTab(currentMainTab)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isAdded && _binding != null && targetSubTab != currentMainTab && targetSubTab in 0..2) {
            binding.tabLayoutOps.getTabAt(targetSubTab)?.select()
            currentMainTab = targetSubTab
            switchMainTab(currentMainTab)
        }
    }

    private fun setupTopTabs() {
        binding.tabLayoutOps.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentMainTab = tab?.position ?: 0
                targetSubTab = currentMainTab
                switchMainTab(currentMainTab)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun switchMainTab(position: Int) {
        binding.layoutSystemOps.visibility = if (position == TAB_SYSTEM) View.VISIBLE else View.GONE
        binding.layoutSecurityOps.visibility = if (position == TAB_SECURITY) View.VISIBLE else View.GONE
        binding.layoutLogsOps.visibility = if (position == TAB_LOGS) View.VISIBLE else View.GONE

        when (position) {
            TAB_SYSTEM -> loadSystemMetrics()
            TAB_SECURITY -> loadSecurityData()
            TAB_LOGS -> {
                logsWebSocket?.connect()
                if (criticalLogs.isEmpty() && otherLogs.isEmpty()) {
                    resetAndLoadLogs()
                }
            }
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshOps.setOnRefreshListener {
            when (currentMainTab) {
                TAB_SYSTEM -> loadSystemMetrics(silent = false)
                TAB_SECURITY -> loadSecurityData(silent = false)
                TAB_LOGS -> resetAndLoadLogs()
            }
        }
    }

    // =========================================================================
    // 1. 系统监控 (System & Host Metrics)
    // =========================================================================

    private fun setupSystemOpsTab() {
        binding.btnCleanCache.setOnClickListener { cleanTranscodeCache() }
    }

    private fun loadSystemMetrics(silent: Boolean = false) {
        val token = container.prefsManager.token ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = container.getApi().getSystemStatus("Bearer $token")
                if (resp.isSuccess) {
                    val status = resp.decodeData<SystemStatus>()
                    if (status != null && _binding != null) {
                        updateSystemMetricsUI(status)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!silent && isAdded) {
                    Toast.makeText(requireContext(), "获取系统状态失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (_binding != null) {
                    binding.swipeRefreshOps.isRefreshing = false
                }
            }
        }
    }

    private fun updateSystemMetricsUI(status: SystemStatus) {
        val metrics = status.metrics ?: SystemStatusMetrics()

        // 1. CPU Metric
        val cpuVal = metrics.effectiveCpuPercent
        binding.tvCpuPercent.text = String.format(Locale.getDefault(), "%.1f%%", cpuVal)
        binding.pbCpuPercent.progress = cpuVal.toInt().coerceIn(0, 100)
        binding.tvCpuSub.text = when {
            cpuVal > 85.0 -> "计算负载极高，请关注"
            cpuVal > 60.0 -> "计算负载较高"
            else -> "计算负载平稳正常"
        }

        // 2. Memory Metric
        val memPercent = metrics.effectiveMemoryPercent
        binding.tvMemoryPercent.text = String.format(Locale.getDefault(), "%.1f%%", memPercent)
        binding.pbMemoryPercent.progress = memPercent.toInt().coerceIn(0, 100)
        binding.tvMemoryDetail.text = String.format(
            Locale.getDefault(),
            "%d MB / %d MB",
            metrics.effectiveMemoryUsedMb,
            metrics.effectiveMemoryTotalMb
        )

        // 3. Storage Quota
        val quotaPercent = metrics.effectiveRecordingsPercent
        binding.tvStorageQuotaText.text = String.format(
            Locale.getDefault(),
            "%.1f GB / %.0f GB (%.1f%%)",
            metrics.effectiveRecordingsUsedGb,
            metrics.effectiveRecordingsLimitGb,
            quotaPercent
        )
        binding.pbStorageQuota.progress = quotaPercent.toInt().coerceIn(0, 100)
        binding.tvTranscodeCache.text = String.format(
            Locale.getDefault(),
            "转码临时缓存占用: %d MB",
            metrics.effectiveTranscodeCacheMb
        )

        // 4. Infrastructure Cards (Devices, MQTT, WS, Uptime)
        // Devices Card
        val devicesCard = ItemStatCardBinding.bind(binding.cardDevices.root)
        devicesCard.tvLabel.text = getString(R.string.stat_devices_online)
        val totalDevices = try {
            val raw = container.prefsManager.cachedDevices
            if (raw != null) {
                NetworkFactory.json.decodeFromString<DeviceList>(raw).devices.size
            } else 0
        } catch (_: Exception) { 0 }
        devicesCard.tvValue.text = if (totalDevices > 0) {
            "${status.onlineDeviceCount} / $totalDevices"
        } else {
            status.onlineDeviceCount.toString()
        }
        devicesCard.ivIcon.setImageResource(R.drawable.ic_devices)
        devicesCard.ivIcon.imageTintList = ColorStateList.valueOf(resources.getColor(R.color.primary_dark, null))
        applyStatCardDot(devicesCard.statusDot, status.onlineDeviceCount > 0)

        // MQTT Card
        val mqttCard = ItemStatCardBinding.bind(binding.cardMqtt.root)
        mqttCard.tvLabel.text = getString(R.string.stat_mqtt)
        mqttCard.tvValue.text = if (status.mqttConnected) getString(R.string.status_online) else getString(R.string.status_offline)
        mqttCard.ivIcon.setImageResource(R.drawable.ic_mqtt)
        mqttCard.ivIcon.imageTintList = ColorStateList.valueOf(resources.getColor(R.color.primary_dark, null))
        applyStatCardDot(mqttCard.statusDot, status.mqttConnected)

        // WS Card
        val wsCard = ItemStatCardBinding.bind(binding.cardWs.root)
        wsCard.tvLabel.text = getString(R.string.stat_ws_clients)
        wsCard.tvValue.text = status.wsClients.toString()
        wsCard.ivIcon.setImageResource(R.drawable.ic_ws)
        wsCard.ivIcon.imageTintList = ColorStateList.valueOf(resources.getColor(R.color.primary_dark, null))
        applyStatCardDot(wsCard.statusDot, status.wsClients > 0)

        // Uptime Card
        val uptimeCard = ItemStatCardBinding.bind(binding.cardUptime.root)
        uptimeCard.tvLabel.text = getString(R.string.stat_uptime)
        uptimeCard.tvValue.text = formatUptime(status.uptimeSeconds)
        uptimeCard.ivIcon.setImageResource(R.drawable.ic_uptime)
        uptimeCard.ivIcon.imageTintList = ColorStateList.valueOf(resources.getColor(R.color.primary_dark, null))
        applyStatCardDot(uptimeCard.statusDot, on = status.uptimeSeconds >= 3_600, warning = status.uptimeSeconds in 1 until 3_600)
    }

    private fun applyStatCardDot(dot: View, on: Boolean, warning: Boolean = false) {
        dot.visibility = View.VISIBLE
        dot.setBackgroundResource(
            when {
                on -> R.drawable.circle_online
                warning -> R.drawable.circle_warning
                else -> R.drawable.circle_offline
            }
        )
    }

    private fun formatUptime(seconds: Long): String {
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val mins = (seconds % 3600) / 60
        return when {
            days > 0 -> "${days}天 ${hours}小时"
            hours > 0 -> "${hours}小时 ${mins}分"
            else -> "${mins}分钟"
        }
    }

    private fun cleanTranscodeCache() {
        val token = container.prefsManager.token ?: return
        binding.btnCleanCache.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                container.getRepository().cleanSystemCache(token)
                if (isAdded) {
                    Toast.makeText(requireContext(), "转码缓存已清理完成", Toast.LENGTH_SHORT).show()
                    loadSystemMetrics(silent = true)
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "清理失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (_binding != null) {
                    binding.btnCleanCache.isEnabled = true
                }
            }
        }
    }

    // =========================================================================
    // 2. 安全防御中心 (Security Defense Center)
    // =========================================================================

    private fun setupSecurityOpsTab() {
        bannedAdapter = BannedIpAdapter(
            onUnbanClick = { entry -> confirmUnban(entry) },
            onEditClick = { entry -> showEditBanDialog(entry) }
        )
        binding.rvBannedList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvBannedList.adapter = bannedAdapter

        whitelistAdapter = WhitelistAdapter(
            onDeleteClick = { entry -> confirmDeleteWhitelist(entry) }
        )
        binding.rvWhitelist.layoutManager = LinearLayoutManager(requireContext())
        binding.rvWhitelist.adapter = whitelistAdapter

        securityEventAdapter = SecurityEventAdapter()
        binding.rvSecurityEvents.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSecurityEvents.adapter = securityEventAdapter

        binding.tabLayoutSecurity.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentSecuritySubTab = tab?.position ?: 0
                switchSecuritySubTab(currentSecuritySubTab)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Email config buttons
        binding.btnAddRecipient.setOnClickListener { showAddRecipientDialog() }
        binding.btnTestEmail.setOnClickListener { testAlertEmail() }

        // Action buttons
        binding.btnManualBan.setOnClickListener { showManualBanDialog() }
        binding.btnConfigDefense.setOnClickListener { showSecurityConfigDialog() }
        binding.btnAddWhitelist.setOnClickListener { showAddWhitelistDialog() }
        binding.btnPruneEvents.setOnClickListener { confirmPruneEvents() }
    }

    private fun switchSecuritySubTab(index: Int) {
        binding.rvBannedList.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.rvWhitelist.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.rvSecurityEvents.visibility = if (index == 2) View.VISIBLE else View.GONE
        binding.layoutHostLogs.visibility = if (index == 3) View.VISIBLE else View.GONE

        binding.btnAddWhitelist.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.btnPruneEvents.visibility = if (index == 2) View.VISIBLE else View.GONE

        binding.tvTabSectionTitle.text = when (index) {
            0 -> "实时拦截黑名单 (${bannedAdapter.itemCount})"
            1 -> "系统豁免白名单 (${whitelistAdapter.itemCount})"
            2 -> "安全审计事件 (${securityEventAdapter.itemCount})"
            3 -> "宿主机认证日志 (fail2ban 同步)"
            else -> ""
        }
        loadSecurityData(silent = true)
    }

    private fun loadSecurityData(silent: Boolean = false) {
        val token = container.prefsManager.token ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val overview = container.getRepository().getDefenseOverview(token)
                cachedOverview = overview
                if (_binding != null) {
                    updateSecurityOverviewUI(overview)
                }

                when (currentSecuritySubTab) {
                    0 -> {
                        bannedAdapter.submitList(overview.activeBans)
                        binding.tvTabSectionTitle.text = "实时拦截黑名单 (${overview.activeBans.size})"
                        showSecurityEmpty(overview.activeBans.isEmpty(), "当前暂无被拦截封禁的 IP", "系统正在持续监控外部流量与异常试探")
                    }
                    1 -> {
                        val list = container.getRepository().getSecurityWhitelist(token).toMutableList()
                        val existing = list.map { it.entry }.toSet()
                        val sysWhitelist = overview.config?.whitelist ?: emptyList()
                        sysWhitelist.forEach { sysIP ->
                            val trimmed = sysIP.trim()
                            if (trimmed.isNotEmpty() && !existing.contains(trimmed)) {
                                list.add(SecurityWhitelistEntry(entry = trimmed, note = "系统配置内置豁免"))
                            }
                        }
                        whitelistAdapter.submitList(list)
                        binding.tvTabSectionTitle.text = "系统豁免白名单 (${list.size})"
                        showSecurityEmpty(list.isEmpty(), "暂无白名单 IP", "可通过右上角「+ 添加白名单」豁免指定 IP 或网段")
                    }
                    2 -> {
                        val events = container.getRepository().getSecurityEvents(token, limit = 50)
                        securityEventAdapter.submitList(events)
                        binding.tvTabSectionTitle.text = "安全审计事件 (${events.size})"
                        showSecurityEmpty(events.isEmpty(), "暂无安全防御事件", "近期的暴力破解、限速阻断或管理员操作均展示在此")
                    }
                    3 -> {
                        val hostLogs = container.getRepository().getHostLogs(token)
                        binding.tvHostLogHeader.text = if (hostLogs.enabled) "宿主机 SSH 保护已启用" else "宿主机日志监听未启用"
                        binding.tvHostLogDetails.text = "源日志: ${hostLogs.sourcePath.ifBlank { "未指定" }}\n防火墙规则导出: ${hostLogs.exportPath.ifBlank { "未配置" }}"
                        val formattedEntries = if (hostLogs.entries.isNotEmpty()) {
                            hostLogs.entries.joinToString("\n\n") { e ->
                                "[${e.status}] ${e.ip}:${e.port} -> ${e.user}\n${e.rawMessage}"
                            }
                        } else {
                            "暂无宿主机异常认证记录"
                        }
                        binding.tvHostLogEntries.text = formattedEntries
                        binding.layoutEmptySecurity.visibility = View.GONE
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!silent && isAdded) {
                    Toast.makeText(requireContext(), "加载防御数据失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (_binding != null) {
                    binding.swipeRefreshOps.isRefreshing = false
                }
            }
        }
    }

    private fun updateSecurityOverviewUI(overview: DefenseOverview) {
        binding.tvStatBannedCount.text = overview.totalBannedCount.toString()
        binding.tvStatBlockedHits.text = overview.totalBlockedHits.toString()

        val sender = overview.senderEmail.ifBlank { "3318647421@qq.com" }
        val host = overview.smtpHost.ifBlank { "smtp.qq.com:465" }
        binding.tvSmtpSenderInfo.text = "发信服务器: $host (SSL 端口)\n发信账号: $sender"

        if (overview.smtpEnabled) {
            binding.tvStatEmailStatus.text = "正常"
            binding.tvStatEmailStatus.setTextColor(resources.getColor(R.color.online, null))
            binding.tvSmtpStatusBadge.text = "QQ SMTP 已连接"
            binding.tvSmtpStatusBadge.setTextColor(resources.getColor(R.color.online, null))
        } else {
            binding.tvStatEmailStatus.text = "未配置"
            binding.tvStatEmailStatus.setTextColor(resources.getColor(R.color.warning, null))
            binding.tvSmtpStatusBadge.text = "SMTP 未配置"
            binding.tvSmtpStatusBadge.setTextColor(resources.getColor(R.color.warning, null))
        }

        val allRecipients = overview.allRecipients
        renderRecipientChips(allRecipients)
    }

    private fun renderRecipientChips(recipients: List<String>) {
        binding.chipGroupRecipients.removeAllViews()
        if (recipients.isEmpty()) {
            binding.tvEmptyRecipients.visibility = View.VISIBLE
            return
        }
        binding.tvEmptyRecipients.visibility = View.GONE

        recipients.forEach { email ->
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                text = email
                isCloseIconVisible = true
                chipIcon = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_inbox)
                chipIconSize = 36f
                setChipIconTintResource(R.color.primary)
                setCloseIconResource(R.drawable.ic_close)
                setCloseIconTintResource(R.color.text_hint)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                setEnsureMinTouchTargetSize(false)
                setOnCloseIconClickListener {
                    confirmDeleteRecipient(email)
                }
            }
            binding.chipGroupRecipients.addView(chip)
        }
    }

    private fun showSecurityEmpty(show: Boolean, title: String, subtitle: String) {
        if (_binding == null) return
        binding.layoutEmptySecurity.visibility = if (show) View.VISIBLE else View.GONE
        binding.tvEmptySecurityTitle.text = title
        binding.tvEmptySecuritySubtitle.text = subtitle
    }

    private fun showAddRecipientDialog() {
        val dialogBinding = DialogAddRecipientBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.btnCancelRecipient.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnConfirmRecipient.setOnClickListener {
            val email = dialogBinding.etRecipientEmail.text?.toString()?.trim() ?: ""
            if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                dialogBinding.tilRecipientEmail.error = "请输入有效的邮箱地址"
                return@setOnClickListener
            }
            val token = container.prefsManager.token ?: return@setOnClickListener
            dialogBinding.btnConfirmRecipient.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    container.getRepository().addAlertRecipient(token, email)
                    container.prefsManager.customAlertRecipients = emptySet()
                    Toast.makeText(requireContext(), "已成功添加接收邮箱: $email", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    loadSecurityData(silent = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    dialogBinding.btnConfirmRecipient.isEnabled = true
                    Toast.makeText(requireContext(), "添加接收邮箱失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun confirmDeleteRecipient(email: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("移除告警接收邮箱")
            .setMessage("确定移除接收邮箱 $email 吗？移除后将不再向该邮箱投递告警。")
            .setPositiveButton("确认移除") { _, _ ->
                val token = container.prefsManager.token ?: return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        container.getRepository().deleteAlertRecipient(token, email)
                        container.prefsManager.customAlertRecipients = emptySet()
                        Toast.makeText(requireContext(), "已移除接收邮箱: $email", Toast.LENGTH_SHORT).show()
                        loadSecurityData(silent = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "移除接收邮箱失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun testAlertEmail() {
        val token = container.prefsManager.token ?: return
        binding.btnTestEmail.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                container.getRepository().testAlertEmail(token)
                Toast.makeText(requireContext(), "告警测试邮件已成功发送至管理员邮箱，请查收！", Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "发送测试邮件失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                if (_binding != null) {
                    binding.btnTestEmail.isEnabled = true
                }
            }
        }
    }

    private fun showManualBanDialog() {
        val dialogBinding = DialogManualBanBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val presets = mapOf(
            dialogBinding.chip30m to 30,
            dialogBinding.chip6h to 360,
            dialogBinding.chip24h to 1440,
            dialogBinding.chipPermanent to 0,
        )
        dialogBinding.chipGroupDuration.setOnCheckedStateChangeListener { _, checkedIds ->
            val chip = checkedIds.firstOrNull()?.let { dialogBinding.chipGroupDuration.findViewById<com.google.android.material.chip.Chip>(it) }
            val minutes = chip?.let { presets[it] }
            if (minutes != null) {
                dialogBinding.etDuration.setText(minutes.toString())
            }
        }

        dialogBinding.btnCancelBan.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnConfirmBan.setOnClickListener {
            val ip = dialogBinding.etIp.text?.toString()?.trim() ?: ""
            val durationStr = dialogBinding.etDuration.text?.toString()?.trim() ?: "30"
            val reason = dialogBinding.etReason.text?.toString()?.trim()?.ifBlank { "管理员手动阻断" } ?: "管理员手动阻断"

            if (ip.isEmpty()) {
                dialogBinding.tilIp.error = "IP 不能为空"
                return@setOnClickListener
            }
            val minutes = durationStr.toIntOrNull() ?: 30
            val token = container.prefsManager.token ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    container.getRepository().manualBan(token, ip, minutes, reason)
                    Toast.makeText(requireContext(), "IP $ip 已列入阻断黑名单", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    loadSecurityData(silent = true)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "封禁失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun showSecurityConfigDialog() {
        val overview = cachedOverview
        val config = overview?.config
        val dialogBinding = DialogSecurityConfigBinding.inflate(layoutInflater)
        if (config != null) {
            dialogBinding.etMaxFailures.setText(config.maxAuthFailures.toString())
            dialogBinding.etWindowMins.setText(config.failureWindowMins.toString())
            dialogBinding.etBanDurationMins.setText(config.banDurationMins.toString())
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.btnCancelConfig.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnSaveConfig.setOnClickListener {
            val maxFailures = dialogBinding.etMaxFailures.text?.toString()?.toIntOrNull() ?: 5
            val windowMins = dialogBinding.etWindowMins.text?.toString()?.toIntOrNull() ?: 5
            val banDurationMins = dialogBinding.etBanDurationMins.text?.toString()?.toIntOrNull() ?: 30

            val token = container.prefsManager.token ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    container.getRepository().updateSecurityConfig(token, maxFailures, windowMins, banDurationMins)
                    Toast.makeText(requireContext(), "防御配置更新成功", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    loadSecurityData(silent = true)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "配置保存失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun showAddWhitelistDialog() {
        val dialogBinding = DialogAddWhitelistBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.btnCancelWhitelist.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnConfirmWhitelist.setOnClickListener {
            val entry = dialogBinding.etEntry.text?.toString()?.trim() ?: ""
            val note = dialogBinding.etNote.text?.toString()?.trim() ?: ""
            if (entry.isBlank()) {
                dialogBinding.tilEntry.error = "请输入 IP 或 CIDR 网段"
                return@setOnClickListener
            }
            val token = container.prefsManager.token ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    container.getRepository().addSecurityWhitelist(token, entry, note)
                    Toast.makeText(requireContext(), "已将 $entry 加入白名单", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    loadSecurityData(silent = true)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "添加白名单失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun confirmUnban(entry: BanEntry) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("解除 IP 拦截")
            .setMessage("确定解除对 IP ${entry.ip} 的阻断限制吗？")
            .setPositiveButton("立即解封") { _, _ ->
                val token = container.prefsManager.token ?: return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        container.getRepository().manualUnban(token, entry.ip)
                        Toast.makeText(requireContext(), "IP ${entry.ip} 已解封", Toast.LENGTH_SHORT).show()
                        loadSecurityData(silent = true)
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "解封失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditBanDialog(entry: BanEntry) {
        val durations = arrayOf("解除封禁", "封禁 1 小时", "封禁 6 小时", "封禁 24 小时", "永久封禁")
        val minutes = arrayOf(-1, 60, 360, 1440, 0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("调整封禁策略: ${entry.ip}")
            .setItems(durations) { _, which ->
                val m = minutes[which]
                val token = container.prefsManager.token ?: return@setItems
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        if (m == -1) {
                            container.getRepository().manualUnban(token, entry.ip)
                            Toast.makeText(requireContext(), "已解封 ${entry.ip}", Toast.LENGTH_SHORT).show()
                        } else {
                            container.getRepository().updateBan(token, entry.ip, m, m == 0)
                            Toast.makeText(requireContext(), "已更新 ${entry.ip} 封禁策略", Toast.LENGTH_SHORT).show()
                        }
                        loadSecurityData(silent = true)
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun confirmDeleteWhitelist(entry: SecurityWhitelistEntry) {
        if (entry.note.contains("内置") || entry.note.contains("配置") || entry.note.contains("系统")) {
            Toast.makeText(requireContext(), "系统内置白名单由服务配置文件维护，不可从客户端直接移除", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("移除白名单")
            .setMessage("确定从白名单移除 ${entry.entry} 吗？移除后其请求将恢复正常安全检测。")
            .setPositiveButton("移除") { _, _ ->
                val token = container.prefsManager.token ?: return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        container.getRepository().deleteSecurityWhitelist(token, entry.entry)
                        Toast.makeText(requireContext(), "已移除 ${entry.entry}", Toast.LENGTH_SHORT).show()
                        loadSecurityData(silent = true)
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "移除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmPruneEvents() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("清理历史安全日志")
            .setMessage("确定清理 30 天前的历史安全事件日志吗？")
            .setPositiveButton("清理") { _, _ ->
                val token = container.prefsManager.token ?: return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val (deleted, _) = container.getRepository().pruneSecurityEvents(token, 30)
                        Toast.makeText(requireContext(), "清理完成，共删除 $deleted 条历史日志", Toast.LENGTH_SHORT).show()
                        loadSecurityData(silent = true)
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "清理失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // =========================================================================
    // 3. 服务日志 (Service Logs with Working Search & Filters)
    // =========================================================================

    private fun setupLogsOpsTab() {
        logsAdapter = ServiceLogAdapter(
            onHeaderClick = { section ->
                if (section == LogListItem.Section.OTHER) {
                    otherCollapsed = !otherCollapsed
                    rebuildLogsDisplayList()
                }
            },
            onVerify = { log -> verifyLog(log) }
        )
        val layoutManager = LinearLayoutManager(requireContext())
        binding.rvLogs.layoutManager = layoutManager
        binding.rvLogs.adapter = logsAdapter

        binding.rvLogs.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoadingLogs || !hasMoreLogs) return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val total = logsAdapter.itemCount
                if (lastVisible >= total - 5) loadNextLogsPage()
            }
        })

        // Level chips
        binding.chipGroupLevel.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedLevel = when (checkedIds.firstOrNull()) {
                R.id.chipLevelCritical -> "critical"
                R.id.chipLevelWarning -> "warning"
                R.id.chipLevelNormal -> "normal"
                R.id.chipLevelInfo -> "info"
                else -> null
            }
            resetAndLoadLogs()
        }

        // Search text watcher with 300ms debounce
        binding.etSearchKeyword.doAfterTextChanged { text ->
            val kw = text?.toString()?.trim()
            binding.btnClearSearch.visibility = if (!kw.isNullOrEmpty()) View.VISIBLE else View.GONE

            searchDebounceJob?.cancel()
            searchDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(300L)
                val target = kw?.ifEmpty { null }
                if (target != searchKeyword) {
                    searchKeyword = target
                    resetAndLoadLogs()
                }
            }
        }

        // Soft keyboard search / enter key
        binding.etSearchKeyword.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                searchDebounceJob?.cancel()
                val kw = binding.etSearchKeyword.text.toString().trim()
                searchKeyword = kw.ifEmpty { null }
                resetAndLoadLogs()
                true
            } else false
        }

        binding.btnClearSearch.setOnClickListener {
            searchDebounceJob?.cancel()
            binding.etSearchKeyword.setText("")
            searchKeyword = null
            binding.btnClearSearch.visibility = View.GONE
            resetAndLoadLogs()
        }

        binding.ivSearchIcon.setOnClickListener {
            searchDebounceJob?.cancel()
            val kw = binding.etSearchKeyword.text.toString().trim()
            searchKeyword = kw.ifEmpty { null }
            resetAndLoadLogs()
        }

        setupLogsWebSocket()
    }

    private fun resetAndLoadLogs() {
        currentOffset = 0
        totalKnown = Long.MAX_VALUE
        hasMoreLogs = true
        criticalLogs.clear()
        otherLogs.clear()
        rebuildLogsDisplayList()
        loadNextLogsPage()
        loadCameraSnapshot()
    }

    private fun loadNextLogsPage() {
        if (isLoadingLogs) return
        val token = container.prefsManager.token ?: return

        isLoadingLogs = true
        if (currentOffset > 0) binding.progressLogsMore.visibility = View.VISIBLE

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
                rebuildLogsDisplayList()
                currentOffset += cached.size
                hasMoreLogs = cached.size >= pageLimit
                isLoadingLogs = false
                if (_binding != null) {
                    binding.progressLogsMore.visibility = View.GONE
                    binding.swipeRefreshOps.isRefreshing = false
                }
                return
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val reqLimit = if (!searchKeyword.isNullOrBlank()) 100 else pageLimit
                val resp = container.getApi().listSystemLogs(
                    "Bearer $token",
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

                // Client-side filtering guarantee across all fields
                val kw = searchKeyword
                val logs = if (!kw.isNullOrBlank()) {
                    rawLogs.filter { log ->
                        log.message.contains(kw, ignoreCase = true) ||
                        log.event_type.contains(kw, ignoreCase = true) ||
                        log.source.contains(kw, ignoreCase = true) ||
                        log.payload.contains(kw, ignoreCase = true)
                    }
                } else rawLogs

                for (log in logs) {
                    if (log.level == SystemLogLevel.CRITICAL) {
                        criticalLogs.add(log)
                    } else {
                        otherLogs.add(log)
                    }
                }
                rebuildLogsDisplayList()

                if (!isFiltered) {
                    CacheManager.getInstance(requireContext()).set(cacheKey, logs)
                }

                currentOffset += rawLogs.size
                hasMoreLogs = rawLogs.size >= reqLimit && (criticalLogs.size + otherLogs.size) < totalKnown
                val emptyMsg = if (!kw.isNullOrBlank()) "未搜索到与「$kw」相关的系统日志" else getString(R.string.no_logs)
                showLogsEmpty(criticalLogs.isEmpty() && otherLogs.isEmpty(), emptyMsg)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (currentOffset == 0 && _binding != null) showLogsEmpty(true)
            } finally {
                isLoadingLogs = false
                if (_binding != null) {
                    binding.progressLogsMore.visibility = View.GONE
                    binding.swipeRefreshOps.isRefreshing = false
                }
            }
        }
    }

    private fun rebuildLogsDisplayList() {
        val items = mutableListOf<LogListItem>()

        // 1. Pending section (always visible)
        if (criticalLogs.isNotEmpty()) {
            items.add(LogListItem.Header(
                section = LogListItem.Section.IMPORTANT,
                title = "待处理日志",
                count = criticalLogs.size,
                collapsed = false
            ))
            criticalLogs.forEach { items.add(LogListItem.LogEntry(it)) }
        }

        // 2. All logs section
        val allLogs = (criticalLogs + otherLogs).sortedByDescending { it.ts }
        if (allLogs.isNotEmpty()) {
            items.add(LogListItem.Header(
                section = LogListItem.Section.OTHER,
                title = if (!searchKeyword.isNullOrBlank()) "搜索结果" else "所有日志",
                count = allLogs.size,
                collapsed = otherCollapsed
            ))
            if (!otherCollapsed) {
                allLogs.forEach { items.add(LogListItem.LogEntry(it)) }
            }
        }

        logsAdapter.submitList(items)
    }

    private fun loadCameraSnapshot() {
        val token = container.prefsManager.token ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val cameras = container.getRepository().listCameras(token, useCache = false, refreshCache = true)
                val map = cameras.associateBy { it.id }
                logsAdapter.updateCameraMap(map)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    private fun showLogsEmpty(show: Boolean, message: String = getString(R.string.no_logs)) {
        if (_binding == null) return
        binding.tvLogsEmpty.visibility = if (show) View.VISIBLE else View.GONE
        binding.tvLogsEmpty.text = message
        binding.rvLogs.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun verifyLog(log: SystemLog) {
        val token = container.prefsManager.token ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            var success = false
            try {
                val resp = container.getApi().verifySystemLog("Bearer $token", log.id)
                success = resp.isSuccess
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}

            if (_binding == null) return@launch
            if (success) {
                CacheManager.getInstance(requireContext()).clear("logs.page.")
                criticalLogs.removeAll { it.id == log.id }
                val verified = log.copy(level = SystemLogLevel.NORMAL)
                otherLogs.add(0, verified)
                rebuildLogsDisplayList()
                showLogsEmpty(criticalLogs.isEmpty() && otherLogs.isEmpty())
                Toast.makeText(requireContext(), R.string.log_verified_deleted, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.log_verify_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupLogsWebSocket() {
        if (logsWebSocket != null) return
        val token = container.prefsManager.token ?: return
        logsWebSocket = HomeCenterWebSocket(
            client = container.okHttpClient,
            wsUrl = container.getWsUrl(),
            token = token,
            listener = object : WsEventListener {
                override fun onConnected() {
                    logsWebSocket?.subscribe("system.log")
                }

                override fun onMessage(message: WsMessage) {
                    activity?.runOnUiThread {
                        if (_binding != null) handleLogsWsMessage(message)
                    }
                }

                override fun onDisconnected(code: Int, reason: String?) = Unit
                override fun onError(throwable: Throwable, reconnectAttempt: Int) = Unit
            },
            wsUrlProvider = { container.getWsUrl() }
        )
        logsWebSocket?.connect()
    }

    private fun handleLogsWsMessage(message: WsMessage) {
        if (message.type != WsMessageType.EVENT || message.topic != "system.log") return
        val payload = message.payload ?: return
        val log = try {
            NetworkFactory.json.decodeFromJsonElement(SystemLog.serializer(), payload)
        } catch (_: Exception) { return }

        // Dedupe
        if (criticalLogs.any { it.id == log.id && log.id != 0L }) return
        if (otherLogs.any { it.id == log.id && log.id != 0L }) return

        // Respect active filters
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

        if (log.level == SystemLogLevel.CRITICAL) {
            criticalLogs.add(0, log)
        } else {
            otherLogs.add(0, log)
        }
        rebuildLogsDisplayList()
        showLogsEmpty(false)

        val wasAtTop = (binding.rvLogs.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() == 0
        if (wasAtTop) {
            binding.rvLogs.post {
                if (_binding != null) binding.rvLogs.scrollToPosition(0)
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
        binding.rvLogs.adapter = null
        binding.rvBannedList.adapter = null
        binding.rvWhitelist.adapter = null
        binding.rvSecurityEvents.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
