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
    private lateinit var adapter: BannedIpAdapter

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
        loadDefenseData()
    }

    private fun setupViews() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.swipeRefresh.setOnRefreshListener {
            loadDefenseData()
        }

        adapter = BannedIpAdapter(
            onUnbanClick = { entry -> confirmUnban(entry) }
        )
        binding.rvBannedList.layoutManager = LinearLayoutManager(this)
        binding.rvBannedList.adapter = adapter

        binding.btnTestEmail.setOnClickListener {
            testAlertEmail()
        }

        binding.btnManualBan.setOnClickListener {
            showManualBanDialog()
        }

        // v1.15.0: silent auto-refresh while the screen is visible, so
        // the ban list/counters don't go stale during an attack.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    kotlinx.coroutines.delay(15_000L)
                    if (binding.swipeRefresh.isRefreshing) continue
                    loadDefenseData(silent = true)
                }
            }
        }
    }

    private fun updateRefreshHint() {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        binding.tvRefreshNotice.text = "每 15 秒自动同步 · 上次更新 $stamp"
    }

    private fun loadDefenseData(silent: Boolean = false) {
        val token = container.prefsManager.token
        if (token.isNullOrBlank()) {
            Toast.makeText(this, "登录状态失效，请重新登录", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (!silent) binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                val overview = container.getRepository().getDefenseOverview(token)
                updateUI(overview)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!silent) {
                    Toast.makeText(this@SecurityDefenseActivity, "加载防御中心数据失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun updateUI(overview: com.homedatacenter.app.data.model.DefenseOverview) {
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

        val bans = overview.activeBans
        adapter.submitList(bans)
        updateRefreshHint()

        if (bans.isEmpty()) {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.rvBannedList.visibility = View.GONE
        } else {
            binding.layoutEmpty.visibility = View.GONE
            binding.rvBannedList.visibility = View.VISIBLE
        }
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
                        loadDefenseData()
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
                        loadDefenseData()
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
}
