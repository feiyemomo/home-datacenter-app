package com.homedatacenter.app.ui.admin

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.BanEntry
import com.homedatacenter.app.databinding.ActivitySecurityDefenseBinding
import com.homedatacenter.app.databinding.DialogManualBanBinding
import com.homedatacenter.app.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Admin-only security defense and intrusion monitoring center.
 *
 * Strictly restricted to administrators (checked via prefsManager.isAdmin).
 * Displays real-time WAF & fail2ban statistics, banned IPs, email alert status,
 * and allows manual IP banning, unbanning, and alert testing.
 */
class SecurityDefenseActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecurityDefenseBinding
    private lateinit var container: AppContainer
    private lateinit var bannedAdapter: BannedIpAdapter
    private lateinit var whitelistAdapter: WhitelistAdapter
    private lateinit var securityEventAdapter: SecurityEventAdapter

    private var currentTab = 0 // 0: bans, 1: whitelist, 2: events, 3: host-logs
    private var cachedOverview: com.homedatacenter.app.data.model.DefenseOverview? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecurityDefenseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        container = (application as HomeCenterApp).container

        // Security Barrier: Strictly enforce Admin role check
        if (!container.prefsManager.isAdmin) {
            Toast.makeText(this, getString(R.string.security_defense_no_permission), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupViews()
        loadTabData()
    }

    private fun setupViews() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.swipeRefresh.setOnRefreshListener {
            loadTabData()
        }

        bannedAdapter = BannedIpAdapter(
            onUnbanClick = { entry -> confirmUnban(entry) },
            onEditClick = { entry -> showEditBanDialog(entry) }
        )
        binding.rvBannedList.layoutManager = LinearLayoutManager(this)
        binding.rvBannedList.adapter = bannedAdapter

        whitelistAdapter = WhitelistAdapter(
            onDeleteClick = { entry -> confirmDeleteWhitelist(entry) }
        )
        binding.rvWhitelist.layoutManager = LinearLayoutManager(this)
        binding.rvWhitelist.adapter = whitelistAdapter

        securityEventAdapter = SecurityEventAdapter()
        binding.rvSecurityEvents.layoutManager = LinearLayoutManager(this)
        binding.rvSecurityEvents.adapter = securityEventAdapter

        binding.tabLayoutSecurity.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                currentTab = tab?.position ?: 0
                switchTab(currentTab)
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        binding.btnSetEmail.setOnClickListener {
            showSetEmailDialog()
        }

        binding.btnTestEmail.setOnClickListener {
            testAlertEmail()
        }

        binding.btnManualBan.setOnClickListener {
            showManualBanDialog()
        }

        binding.btnConfigDefense.setOnClickListener {
            showSecurityConfigDialog()
        }

        binding.btnAddWhitelist.setOnClickListener {
            showAddWhitelistDialog()
        }

        binding.btnPruneEvents.setOnClickListener {
            confirmPruneEvents()
        }

        // v1.15.0: silent auto-refresh while the screen is visible, so
        // the ban list/counters don't go stale during an attack.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    kotlinx.coroutines.delay(15_000L)
                    if (binding.swipeRefresh.isRefreshing) continue
                    loadTabData(silent = true)
                }
            }
        }
    }

    private fun switchTab(tabIndex: Int) {
        binding.rvBannedList.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        binding.rvWhitelist.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        binding.rvSecurityEvents.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
        binding.layoutHostLogs.visibility = if (tabIndex == 3) View.VISIBLE else View.GONE

        binding.btnAddWhitelist.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        binding.btnPruneEvents.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE

        binding.tvTabSectionTitle.text = when (tabIndex) {
            0 -> "实时拦截黑名单"
            1 -> "系统豁免白名单 (IP / CIDR)"
            2 -> "近期入侵与防御安全事件"
            3 -> "宿主机认证日志 (fail2ban 同步)"
            else -> ""
        }

        binding.layoutEmpty.visibility = View.GONE
        loadTabData()
    }

    private fun updateRefreshHint() {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        binding.tvRefreshNotice.text = "每 15 秒自动同步 · 上次更新 $stamp"
    }

    private fun loadTabData(silent: Boolean = false) {
        val token = container.prefsManager.token
        if (token.isNullOrBlank()) {
            Toast.makeText(this, "登录状态失效，请重新登录", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (!silent) binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                // Always refresh overview to keep counters up to date
                val overview = container.getRepository().getDefenseOverview(token)
                cachedOverview = overview
                updateOverviewUI(overview)

                when (currentTab) {
                    0 -> {
                        bannedAdapter.submitList(overview.activeBans)
                        showEmpty(overview.activeBans.isEmpty(), "当前暂无被拦截封禁的 IP", "系统正在持续监控外部流量与异常试探")
                    }
                    1 -> {
                        val list = container.getRepository().getSecurityWhitelist(token).toMutableList()
                        val existing = list.map { it.entry }.toSet()
                        val sysWhitelist = overview.config?.whitelist ?: emptyList()
                        sysWhitelist.forEach { sysIP ->
                            val trimmed = sysIP.trim()
                            if (trimmed.isNotEmpty() && !existing.contains(trimmed)) {
                                list.add(com.homedatacenter.app.data.model.SecurityWhitelistEntry(entry = trimmed, note = "系统配置内置豁免"))
                            }
                        }
                        whitelistAdapter.submitList(list)
                        showEmpty(list.isEmpty(), "暂无白名单条目", "可通过右上角「+ 添加白名单」豁免指定 IP 或网段")
                    }
                    2 -> {
                        val events = container.getRepository().getSecurityEvents(token, limit = 50)
                        securityEventAdapter.submitList(events)
                        showEmpty(events.isEmpty(), "暂无安全防御事件", "近期的暴力破解、限速阻断或管理员操作均会展示在此")
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
                        binding.layoutEmpty.visibility = View.GONE
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!silent) {
                    Toast.makeText(this@SecurityDefenseActivity, "加载数据失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun showEmpty(empty: Boolean, title: String, subtitle: String) {
        if (empty) {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.layoutEmpty.findViewById<android.widget.TextView>(R.id.tvEmptyTitle)?.text = title
            binding.layoutEmpty.findViewById<android.widget.TextView>(R.id.tvEmptySubtitle)?.text = subtitle
        } else {
            binding.layoutEmpty.visibility = View.GONE
        }
    }

    private fun updateOverviewUI(overview: com.homedatacenter.app.data.model.DefenseOverview) {
        binding.tvStatBannedCount.text = overview.totalBannedCount.toString()
        binding.tvStatBlockedHits.text = overview.totalBlockedHits.toString()

        if (overview.smtpEnabled) {
            binding.tvStatEmailStatus.text = "正常"
            binding.tvStatEmailStatus.setTextColor(getColor(R.color.online))
            val rec = if (overview.recipient.isNotBlank()) overview.recipient else "已配置"
            binding.tvAlertRecipient.text = "告警接收邮箱: $rec (QQ SMTP 已连接)"
        } else {
            binding.tvStatEmailStatus.text = "未配置"
            binding.tvStatEmailStatus.setTextColor(getColor(R.color.warning))
            binding.tvAlertRecipient.text = "邮件通知通道未启用，异常仅记录本地日志"
        }
        updateRefreshHint()
    }

    private fun showSetEmailDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "请输入告警接收邮箱 (如 admin@example.com)"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            cachedOverview?.recipient?.takeIf { it.isNotBlank() }?.let { setText(it) }
            setPadding(48, 36, 48, 36)
        }
        val layout = android.widget.FrameLayout(this).apply {
            setPadding(32, 16, 32, 16)
            addView(input)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("设置安全告警邮箱")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val email = input.text.toString().trim()
                if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    Toast.makeText(this, "请输入有效的邮箱地址", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val token = container.prefsManager.token ?: return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        container.getRepository().updateAlertEmail(token, email)
                        Toast.makeText(this@SecurityDefenseActivity, "告警邮箱已更新为: $email", Toast.LENGTH_SHORT).show()
                        loadTabData(silent = true)
                    } catch (e: Exception) {
                        Toast.makeText(this@SecurityDefenseActivity, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun testAlertEmail() {
        val token = container.prefsManager.token ?: return
        binding.btnTestEmail.isEnabled = false
        lifecycleScope.launch {
            try {
                container.getRepository().testAlertEmail(token)
                Toast.makeText(this@SecurityDefenseActivity, "告警测试邮件已成功发送至管理员邮箱，请查收！", Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@SecurityDefenseActivity, "发送测试邮件失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnTestEmail.isEnabled = true
            }
        }
    }

    private fun showManualBanDialog() {
        val dialogBinding = DialogManualBanBinding.inflate(layoutInflater)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        // v1.15.1: make the window transparent so the layout's own
        // bg_dialog_glass rounded surface is what the user sees —
        // otherwise the platform dialog paints an opaque white
        // square-cornered box behind our warm card.
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // v1.15.0: preset duration chips keep the common cases one tap
        // away and make "permanent" discoverable (it used to require
        // knowing that 0 means forever).
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
                val reason = dialogBinding.etReason.text?.toString()?.trim() ?: "管理员手动阻断"

                if (!isValidIp(ip)) {
                    dialogBinding.tilIp.error = "请输入合法的 IPv4 / IPv6 地址"
                    return@setOnClickListener
                }
                if (ip == "127.0.0.1" || ip == "::1" || ip == "192.168.31.1") {
                    dialogBinding.tilIp.error = "不可封禁本地回环或局域网核心网关地址"
                    return@setOnClickListener
                }
                dialogBinding.tilIp.error = null

                val duration = durationStr.toIntOrNull() ?: 30
                if (duration < 0) {
                    dialogBinding.tilDuration.error = "时长不能为负数（0 表示永久）"
                    return@setOnClickListener
                }
                dialogBinding.tilDuration.error = null

                val token = container.prefsManager.token ?: return@setOnClickListener
                lifecycleScope.launch {
                    try {
                        container.getRepository().manualBan(token, ip, duration, reason)
                        Toast.makeText(this@SecurityDefenseActivity, "IP $ip 已成功列入阻断黑名单", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        loadTabData()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toast.makeText(this@SecurityDefenseActivity, "封禁失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }

        dialog.show()
    }

    /** v1.15.1: robust IPv4/IPv6 validation with self/gateway lockout warning */
    private fun isValidIp(raw: String): Boolean {
        if (raw.isBlank() || raw.any { it.isWhitespace() }) return false
        val parts = raw.split('.')
        if (parts.size == 4) {
            return parts.all { p ->
                p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255
            }
        }
        if (raw.contains(':')) {
            val v6Regex = Regex("^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$|^([0-9a-fA-F]{1,4}:)*:[0-9a-fA-F]{1,4}(:[0-9a-fA-F]{1,4})*$|^::$")
            return v6Regex.matches(raw)
        }
        return false
    }

    private fun confirmUnban(entry: BanEntry) {
        val token = container.prefsManager.token ?: return
        AlertDialog.Builder(this)
            .setTitle("确认解除封禁")
            .setMessage("确定要解除对 IP ${entry.ip} 的拦截封禁吗？解除后该 IP 将可以立即恢复访问家庭服务器。")
            .setPositiveButton("立即解封") { _, _ ->
                lifecycleScope.launch {
                    try {
                        container.getRepository().manualUnban(token, entry.ip)
                        Toast.makeText(this@SecurityDefenseActivity, "IP ${entry.ip} 已成功解封", Toast.LENGTH_SHORT).show()
                        loadTabData()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toast.makeText(this@SecurityDefenseActivity, "解封失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditBanDialog(entry: BanEntry) {
        val options = arrayOf("延长封禁 30 分钟", "延长封禁 6 小时", "延长封禁 24 小时", "设为永久封禁")
        val durations = arrayOf(30, 360, 1440, 0)
        val permanents = arrayOf(false, false, false, true)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("调整 IP 封禁时长: ${entry.ip}")
            .setItems(options) { _, which ->
                val duration = durations[which]
                val permanent = permanents[which]
                val token = container.prefsManager.token ?: return@setItems
                lifecycleScope.launch {
                    try {
                        container.getRepository().updateBan(token, entry.ip, duration, permanent)
                        Toast.makeText(this@SecurityDefenseActivity, "已更新 ${entry.ip} 封禁策略", Toast.LENGTH_SHORT).show()
                        loadTabData()
                    } catch (e: Exception) {
                        Toast.makeText(this@SecurityDefenseActivity, "更新失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddWhitelistDialog() {
        val dialogBinding = com.homedatacenter.app.databinding.DialogAddWhitelistBinding.inflate(layoutInflater)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("添加") { _, _ ->
                val entry = dialogBinding.etEntry.text?.toString()?.trim() ?: ""
                val note = dialogBinding.etNote.text?.toString()?.trim() ?: ""
                if (entry.isBlank()) {
                    Toast.makeText(this, "请输入 IP 或 CIDR 网段", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val token = container.prefsManager.token ?: return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        container.getRepository().addSecurityWhitelist(token, entry, note)
                        Toast.makeText(this@SecurityDefenseActivity, "已将 $entry 加入白名单", Toast.LENGTH_SHORT).show()
                        loadTabData()
                    } catch (e: Exception) {
                        Toast.makeText(this@SecurityDefenseActivity, "添加白名单失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteWhitelist(entry: com.homedatacenter.app.data.model.SecurityWhitelistEntry) {
        if (entry.note.contains("内置") || entry.note.contains("配置") || entry.note.contains("系统")) {
            Toast.makeText(this@SecurityDefenseActivity, "系统内置白名单由服务配置文件维护，不可从客户端直接移除", Toast.LENGTH_SHORT).show()
            return
        }
        val token = container.prefsManager.token ?: return
        AlertDialog.Builder(this)
            .setTitle("移除白名单")
            .setMessage("确定要移除白名单规则 ${entry.entry} 吗？移除后该地址将重新受自动限速与封禁规则约束。")
            .setPositiveButton("移除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        container.getRepository().deleteSecurityWhitelist(token, entry.entry)
                        Toast.makeText(this@SecurityDefenseActivity, "已移除 ${entry.entry}", Toast.LENGTH_SHORT).show()
                        loadTabData()
                    } catch (e: Exception) {
                        Toast.makeText(this@SecurityDefenseActivity, "移除失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmPruneEvents() {
        val token = container.prefsManager.token ?: return
        AlertDialog.Builder(this)
            .setTitle("清理历史安全日志")
            .setMessage("确定清理 30 天前的历史安全事件日志吗？")
            .setPositiveButton("立即清理") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val (deleted, _) = container.getRepository().pruneSecurityEvents(token, 30)
                        Toast.makeText(this@SecurityDefenseActivity, "清理完成，共删除 $deleted 条历史日志", Toast.LENGTH_SHORT).show()
                        loadTabData()
                    } catch (e: Exception) {
                        Toast.makeText(this@SecurityDefenseActivity, "清理失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSecurityConfigDialog() {
        val overview = cachedOverview
        val config = overview?.config
        val dialogBinding = com.homedatacenter.app.databinding.DialogSecurityConfigBinding.inflate(layoutInflater)
        if (config != null) {
            dialogBinding.etMaxFailures.setText(config.maxAuthFailures.toString())
            dialogBinding.etWindowMins.setText(config.failureWindowMins.toString())
            dialogBinding.etBanDurationMins.setText(config.banDurationMins.toString())
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("保存生效") { _, _ ->
                val maxFailures = dialogBinding.etMaxFailures.text?.toString()?.toIntOrNull() ?: 5
                val windowMins = dialogBinding.etWindowMins.text?.toString()?.toIntOrNull() ?: 5
                val banDurationMins = dialogBinding.etBanDurationMins.text?.toString()?.toIntOrNull() ?: 30

                val token = container.prefsManager.token ?: return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        container.getRepository().updateSecurityConfig(token, maxFailures, windowMins, banDurationMins)
                        Toast.makeText(this@SecurityDefenseActivity, "防御配置更新成功", Toast.LENGTH_SHORT).show()
                        loadTabData()
                    } catch (e: Exception) {
                        Toast.makeText(this@SecurityDefenseActivity, "配置保存失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
