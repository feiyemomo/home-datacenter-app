package com.homedatacenter.app.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.homedatacenter.app.R
import com.homedatacenter.app.databinding.FragmentSettingsBinding
import com.homedatacenter.app.ui.admin.SecurityDefenseActivity
import com.homedatacenter.app.ui.admin.UsersActivity
import com.homedatacenter.app.ui.automations.AutomationsActivity
import com.homedatacenter.app.ui.vision.FamilyFacesActivity
import com.homedatacenter.app.ui.main.MainActivity
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.homedatacenter.app.util.ApkInstaller
import com.homedatacenter.app.util.BaseUrlResolver
import com.homedatacenter.app.util.JwtUtil
import com.homedatacenter.app.util.PrefsManager
import com.homedatacenter.app.util.ThemeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    // v1.6.28: polls AppContainer's background-download state while a
    // download is in flight so the progress text stays live. The
    // runnable self-perpetuates (postDelayed) until the download
    // completes or fails, then stops. Restarted from onResume and
    // after a manual force-check.
    private val updatePollHandler = Handler(Looper.getMainLooper())
    private val updatePollRunnable = object : Runnable {
        override fun run() {
            val container = (activity as? MainActivity)?.container ?: return
            if (_binding == null) return
            renderCachedUpdateStatus()
            if (container.isDownloadingApk()) {
                updatePollHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainActivity = activity as? MainActivity ?: return
        val prefs = mainActivity.container.prefsManager

        setupThemeSelector(prefs)
        setupNotificationSettings(prefs)
        setupProfileCard(prefs)
        setupJwtInfo(prefs)
        setupLanConfig()
        setupAdminSection(prefs)
        setupUpdateSection()

        binding.tvAccountAction.setOnClickListener { showLogoutDialog() }

        loadUserInfo()
        setupVersion()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            val mainActivity = activity as? MainActivity ?: return
            setupJwtInfo(mainActivity.container.prefsManager)
            updateBatteryOptimizationState()
        }
    }


    private fun updateBatteryOptimizationState() {
        val ctx = context ?: return
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        val isIgnored = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        if (isIgnored) {
            binding.tvBatteryOptimizationHint.text = "已加入电池优化白名单 (后台运行保护开启)"
            binding.tvBatteryOptimizationHint.setTextColor(resources.getColor(R.color.online, ctx.theme))
        } else {
            binding.tvBatteryOptimizationHint.text = "未加入电池白名单，点击设置以避免后台被系统杀掉"
            binding.tvBatteryOptimizationHint.setTextColor(resources.getColor(R.color.primary, ctx.theme))
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        val ctx = context ?: return
        try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:${ctx.packageName}")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(ctx, "无法打开系统电池优化设置: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupThemeSelector(prefs: PrefsManager) {
        when (prefs.themeMode) {
            PrefsManager.THEME_LIGHT -> binding.rbThemeLight.isChecked = true
            PrefsManager.THEME_DARK -> binding.rbThemeDark.isChecked = true
            else -> binding.rbThemeSystem.isChecked = true
        }

        binding.radioGroupTheme.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            val mode = when (checkedId) {
                R.id.rb_theme_light -> PrefsManager.THEME_LIGHT
                R.id.rb_theme_dark -> PrefsManager.THEME_DARK
                else -> PrefsManager.THEME_FOLLOW_SYSTEM
            }
            prefs.themeMode = mode
            ThemeManager.applyTheme(mode)
            // setDefaultNightMode triggers Activity recreation automatically.
            // Do NOT call activity?.recreate() here — it would cause a
            // double-recreation race condition, crashing the app with a
            // Fragment state conflict (the recreate triggered by
            // setDefaultNightMode and the explicit recreate overlap,
            // leaving the fragment in an inconsistent state).
        }
    }

    private fun setupNotificationSettings(prefs: PrefsManager) {
        binding.switchNotificationsMaster.isChecked = prefs.notificationsEnabled
        binding.layoutNotificationDetails.visibility = if (prefs.notificationsEnabled) View.VISIBLE else View.GONE

        binding.switchNotificationsMaster.setOnCheckedChangeListener { _, isChecked ->
            prefs.notificationsEnabled = isChecked
            binding.layoutNotificationDetails.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.switchNotifyPerson.isChecked = prefs.notifyPerson
        binding.switchNotifyPerson.setOnCheckedChangeListener { _, isChecked ->
            prefs.notifyPerson = isChecked
        }

        binding.switchNotifyMotion.isChecked = prefs.notifyMotion
        binding.switchNotifyMotion.setOnCheckedChangeListener { _, isChecked ->
            prefs.notifyMotion = isChecked
        }

        binding.layoutNotifySystem.visibility = if (prefs.isAdmin) View.VISIBLE else View.GONE
        binding.switchNotifySystem.isChecked = prefs.notifySystem
        binding.switchNotifySystem.setOnCheckedChangeListener { _, isChecked ->
            prefs.notifySystem = isChecked
        }

        binding.switchNotifySnapshot.isChecked = prefs.notifyIncludeSnapshot
        binding.switchNotifySnapshot.setOnCheckedChangeListener { _, isChecked ->
            prefs.notifyIncludeSnapshot = isChecked
        }

        binding.switchNotifyDnd.isChecked = prefs.notifyDndEnabled
        binding.switchNotifyDnd.setOnCheckedChangeListener { _, isChecked ->
            prefs.notifyDndEnabled = isChecked
            updateDndSummaryText(prefs)
        }

        updateDndSummaryText(prefs)
        binding.tvDndTimeSummary.setOnClickListener {
            showDndTimePickerDialog(prefs)
        }

        binding.switchKeepAlive.isChecked = prefs.keepAliveEnabled
        binding.switchKeepAlive.setOnCheckedChangeListener { _, isChecked ->
            prefs.keepAliveEnabled = isChecked
            if (isChecked) {
                com.homedatacenter.app.service.AlertKeepAliveService.start(requireContext())
            } else {
                com.homedatacenter.app.service.AlertKeepAliveService.stop(requireContext())
            }
        }
        updateBatteryOptimizationState()
        binding.tvBatteryOptimizationHint.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }
    }

    private fun updateDndSummaryText(prefs: PrefsManager) {
        val startStr = String.format(java.util.Locale.US, "%02d:%02d", prefs.notifyDndStartHour, prefs.notifyDndStartMinute)
        val endStr = String.format(java.util.Locale.US, "%02d:%02d", prefs.notifyDndEndHour, prefs.notifyDndEndMinute)
        binding.tvDndTimeSummary.text = "时段: $startStr ~ $endStr (点击修改)"
    }

    private fun showDndTimePickerDialog(prefs: PrefsManager) {
        val startPicker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(prefs.notifyDndStartHour)
            .setMinute(prefs.notifyDndStartMinute)
            .setTitleText("选择免打扰起始时间")
            .build()

        startPicker.addOnPositiveButtonClickListener {
            prefs.notifyDndStartHour = startPicker.hour
            prefs.notifyDndStartMinute = startPicker.minute

            val endPicker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(prefs.notifyDndEndHour)
                .setMinute(prefs.notifyDndEndMinute)
                .setTitleText("选择免打扰结束时间")
                .build()

            endPicker.addOnPositiveButtonClickListener {
                prefs.notifyDndEndHour = endPicker.hour
                prefs.notifyDndEndMinute = endPicker.minute
                updateDndSummaryText(prefs)
                Toast.makeText(requireContext(), "免打扰时段已更新", Toast.LENGTH_SHORT).show()
            }
            endPicker.show(parentFragmentManager, "dnd_end_picker")
        }
        startPicker.show(parentFragmentManager, "dnd_start_picker")
    }

    private fun setupProfileCard(prefs: PrefsManager) {
        // Initial render from cached prefs so the card is populated
        // before the /me call resolves.
        val name = prefs.userName?.ifEmpty { "User" } ?: "User"
        binding.tvUserName.text = name
        binding.tvUserAvatar.text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "U"

        if (prefs.isAdmin) {
            binding.tvUserRoleBadge.text = getString(R.string.setting_admin_label)
            binding.tvUserRoleBadge.setBackgroundResource(R.drawable.bg_badge_admin)
        } else {
            binding.tvUserRoleBadge.text = "普通成员"
            binding.tvUserRoleBadge.setBackgroundResource(R.drawable.bg_badge_user)
        }

        binding.btnToggleSessionDetails.setOnClickListener {
            val isGone = binding.layoutSessionDetails.visibility == View.GONE
            binding.layoutSessionDetails.visibility = if (isGone) View.VISIBLE else View.GONE
            binding.tvSessionToggleArrow.text = if (isGone) "收起 ▴" else "展开 ▾"
        }

        binding.btnRefreshToken.setOnClickListener {
            refreshJwtToken(prefs)
        }
    }

    private fun setupJwtInfo(prefs: PrefsManager) {
        val token = prefs.token
        val userId = JwtUtil.userId(token)
        val deviceId = JwtUtil.deviceId(token)
        val issuedAt = JwtUtil.issuedAt(token)
        val expiresAt = JwtUtil.expiresAt(token)
        val remaining = JwtUtil.secondsUntilExpiry(token)

        binding.tvUserId.text = if (userId != null) {
            "${getString(R.string.profile_user_id)}: $userId"
        } else {
            "${getString(R.string.profile_user_id)}: -"
        }
        binding.tvDeviceId.text = if (deviceId != null) {
            "${getString(R.string.profile_device_id)}: $deviceId"
        } else {
            "${getString(R.string.profile_device_id)}: -"
        }

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        binding.tvTokenIssued.text = if (issuedAt != null) {
            "${getString(R.string.profile_token_issued)}: ${fmt.format(Date(issuedAt * 1000L))}"
        } else {
            "${getString(R.string.profile_token_issued)}: -"
        }
        binding.tvTokenExpires.text = if (expiresAt != null) {
            "${getString(R.string.profile_token_expires)}: ${fmt.format(Date(expiresAt * 1000L))}"
        } else {
            "${getString(R.string.profile_token_expires)}: -"
        }
        binding.tvTokenRemaining.text = if (remaining != null) {
            if (remaining <= 0) {
                binding.tvTokenRemaining.setTextColor(requireContext().getColor(R.color.error))
                getString(R.string.profile_token_expired)
            } else {
                val days = TimeUnit.SECONDS.toDays(remaining)
                "${getString(R.string.profile_token_remaining)}: $days 天"
            }
        } else {
            "${getString(R.string.profile_token_remaining)}: -"
        }

        binding.tvTokenIssued.setOnClickListener {
            refreshJwtToken(prefs)
        }
    }

    private fun refreshJwtToken(prefs: PrefsManager) {
        val mainActivity = activity as? MainActivity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            Toast.makeText(requireContext(), "正在刷新令牌...", Toast.LENGTH_SHORT).show()
            val refreshed = withContext(Dispatchers.IO) {
                mainActivity.container.tokenManager.refreshViaTokenAndPersist(prefs.token ?: "", force = true)
                    ?: if (!prefs.accessKey.isNullOrEmpty() && prefs.userId > 0L) {
                        mainActivity.container.tokenManager.refreshAndPersist(prefs.userId, prefs.accessKey!!, force = true)
                    } else null
            }
            if (refreshed != null) {
                setupJwtInfo(prefs)
                Toast.makeText(requireContext(), "令牌已更新", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "令牌刷新失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadUserInfo() {
        val mainActivity = activity as? MainActivity ?: return
        val prefs = mainActivity.container.prefsManager
        val token = prefs.token ?: return

        lifecycleScope.launch {
            try {
                val user = mainActivity.container.getRepository().getMe(token)
                prefs.saveUserInfo(user.name, user.isAdmin)
                prefs.userId = user.id
                binding.tvUserName.text = user.name
                binding.tvUserAvatar.text = user.name.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
                if (user.isAdmin) {
                    binding.tvUserRoleBadge.text = getString(R.string.setting_admin_label)
                    binding.tvUserRoleBadge.setBackgroundResource(R.drawable.bg_badge_admin)
                } else {
                    binding.tvUserRoleBadge.text = "普通成员"
                    binding.tvUserRoleBadge.setBackgroundResource(R.drawable.bg_badge_user)
                }
                binding.tvAdminSectionHeader.visibility = if (user.isAdmin) View.VISIBLE else View.GONE
                binding.cardAdminSection.visibility = if (user.isAdmin) View.VISIBLE else View.GONE
                binding.layoutNotifySystem.visibility = if (user.isAdmin) View.VISIBLE else View.GONE
                binding.layoutUpdateTrack.visibility = if (user.isAdmin) View.VISIBLE else View.GONE
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun setupAdminSection(prefs: PrefsManager) {
        val isAdmin = prefs.isAdmin
        binding.tvAdminSectionHeader.visibility = if (isAdmin) View.VISIBLE else View.GONE
        binding.cardAdminSection.visibility = if (isAdmin) View.VISIBLE else View.GONE

        binding.btnSettingsAutomations.setOnClickListener {
            startActivity(Intent(requireContext(), AutomationsActivity::class.java))
        }
        binding.btnSettingsVisionFaces.setOnClickListener {
            startActivity(Intent(requireContext(), FamilyFacesActivity::class.java))
        }
        binding.btnSettingsUsers.setOnClickListener {
            startActivity(Intent(requireContext(), UsersActivity::class.java))
        }
        binding.btnSettingsStorage.setOnClickListener {
            showStorageConfigDialog()
        }
        binding.btnSettingsSecurity.setOnClickListener {
            startActivity(Intent(requireContext(), SecurityDefenseActivity::class.java))
        }
    }

    private fun showStorageConfigDialog() {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return
        val repo = mainActivity.container.getRepository()

        lifecycleScope.launch {
            var quotaGb = 400
            var scheduleHour = 3
            var minAgeDays = 7
            try {
                val json = repo.getStorageConfig(token)
                if (json != null) {
                    quotaGb = json["quota_gb"]?.toString()?.toDoubleOrNull()?.toInt() ?: 400
                    scheduleHour = json["archive_schedule_hour"]?.toString()?.toDoubleOrNull()?.toInt() ?: 3
                    minAgeDays = json["archive_min_age_days"]?.toString()?.toDoubleOrNull()?.toInt() ?: 7
                }
            } catch (e: Exception) {
                android.util.Log.w("SettingsFragment", "getStorageConfig: ${e.message}")
            }

            val dialogBinding = com.homedatacenter.app.databinding.DialogStorageQuotaBinding.inflate(layoutInflater)
            var currentQuota = quotaGb
            var currentHour = scheduleHour
            var currentMinAge = minAgeDays

            dialogBinding.tvQuotaValue.text = "${currentQuota} GB"
            dialogBinding.tvArchiveTimeValue.text = "%02d:00".format(currentHour)
            dialogBinding.tvMinAgeValue.text = "超过 ${currentMinAge} 天"

            try {
                val status = repo.getSystemStatus(token, useCache = true)
                val m = status.metrics
                if (m != null) {
                    dialogBinding.tvDialogStorageUsage.text = String.format(
                        java.util.Locale.getDefault(),
                        "%.1f GB / %.0f GB (%.1f%%)",
                        m.effectiveRecordingsUsedGb,
                        m.effectiveRecordingsLimitGb,
                        m.effectiveRecordingsPercent
                    )
                    dialogBinding.pbDialogStorageQuota.progress = m.effectiveRecordingsPercent.toInt().coerceIn(0, 100)
                } else {
                    dialogBinding.tvDialogStorageUsage.text = "-- / ${currentQuota} GB"
                }
            } catch (_: Exception) {
                dialogBinding.tvDialogStorageUsage.text = "-- / ${currentQuota} GB"
            }

            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setView(dialogBinding.root)
                .create()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            dialogBinding.cardEditQuota.setOnClickListener {
                val quotas = arrayOf("200 GB", "300 GB", "400 GB (推荐)", "600 GB", "800 GB", "1000 GB")
                val values = intArrayOf(200, 300, 400, 600, 800, 1000)
                val selectedIdx = values.indexOf(currentQuota).let { if (it >= 0) it else 2 }

                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("调整监控录像存储配额")
                    .setSingleChoiceItems(quotas, selectedIdx) { qDialog, idx ->
                        val newQuota = values[idx]
                        currentQuota = newQuota
                        dialogBinding.tvQuotaValue.text = "${newQuota} GB"
                        qDialog.dismiss()
                        lifecycleScope.launch {
                            try {
                                repo.updateStorageConfig(token, currentQuota, currentHour, currentMinAge)
                                Toast.makeText(requireContext(), "录像配额已调整为 ${newQuota} GB", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(requireContext(), "设置失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }

            dialogBinding.cardPickArchiveTime.setOnClickListener {
                val picker = MaterialTimePicker.Builder()
                    .setTimeFormat(TimeFormat.CLOCK_24H)
                    .setHour(currentHour)
                    .setMinute(0)
                    .setTitleText("选择每日云端归档时间")
                    .build()

                picker.addOnPositiveButtonClickListener {
                    val selectedHour = picker.hour
                    currentHour = selectedHour
                    dialogBinding.tvArchiveTimeValue.text = "%02d:00".format(selectedHour)
                    lifecycleScope.launch {
                        try {
                            repo.updateStorageConfig(token, currentQuota, currentHour, currentMinAge)
                            Toast.makeText(requireContext(), "每日归档时间已更新为 %02d:00".format(selectedHour), Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                picker.show(parentFragmentManager, "archive_time_picker")
            }

            dialogBinding.cardEditMinAge.setOnClickListener {
                val options = arrayOf("保留 3 天后归档", "保留 5 天后归档", "保留 7 天后归档 (推荐)", "保留 14 天后归档", "保留 30 天后归档")
                val values = intArrayOf(3, 5, 7, 14, 30)
                val selectedIdx = values.indexOf(currentMinAge).let { if (it >= 0) it else 2 }

                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("选择本地保留归档门限")
                    .setSingleChoiceItems(options, selectedIdx) { mDialog, idx ->
                        val newMinAge = values[idx]
                        currentMinAge = newMinAge
                        dialogBinding.tvMinAgeValue.text = "超过 ${newMinAge} 天"
                        mDialog.dismiss()
                        lifecycleScope.launch {
                            try {
                                repo.updateStorageConfig(token, currentQuota, currentHour, currentMinAge)
                                Toast.makeText(requireContext(), "冷归档门限已设置为 ${newMinAge} 天", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(requireContext(), "设置失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }

            dialogBinding.btnTriggerArchive.setOnClickListener {
                dialogBinding.btnTriggerArchive.isEnabled = false
                dialogBinding.btnTriggerArchive.text = "正在归档…"
                lifecycleScope.launch {
                    try {
                        repo.triggerArchiveSync(token)
                        Toast.makeText(requireContext(), "已触发蓝奏云录像归档任务！", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "触发归档失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        dialogBinding.btnTriggerArchive.isEnabled = true
                        dialogBinding.btnTriggerArchive.text = "立即归档"
                    }
                }
            }

            dialogBinding.btnCleanCache.setOnClickListener {
                dialogBinding.btnCleanCache.isEnabled = false
                dialogBinding.btnCleanCache.text = "清理中…"
                lifecycleScope.launch {
                    try {
                        repo.cleanSystemCache(token)
                        Toast.makeText(requireContext(), "转码切片缓存已清理！", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "清理缓存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        dialogBinding.btnCleanCache.isEnabled = true
                        dialogBinding.btnCleanCache.text = "清理缓存"
                    }
                }
            }

            dialogBinding.btnClose.setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()
        }
    }

    private fun setupVersion() {
        try {
            val context = context ?: return
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val version = pInfo.versionName
            binding.tvVersion.text = getString(R.string.setting_version) + " " + version
        } catch (_: PackageManager.NameNotFoundException) {
            binding.tvVersion.visibility = View.GONE
        }
    }

    /**
     * v1.8.24: LAN URL configuration. Lets the user set a custom NAS
     * LAN address (e.g. http://192.168.31.235:8088/) so the app
     * adapts to IP changes without recompiling. The custom URL is
     * persisted in SharedPreferences by BaseUrlResolver and overrides
     * the hardcoded LAN_URL on the next probe.
     *
     * UI elements:
     *  - tvLanCurrentUrl: shows the currently effective LAN URL
     *  - etLanUrl: editable text field for inputting a custom URL
     *  - btnLanSave: saves the custom URL and triggers a re-probe
     *  - btnLanReset: clears the custom URL, reverts to hardcoded default
     */
    private fun setupLanConfig() {
        val mainActivity = activity as? MainActivity ?: return
        val resolver = mainActivity.container.baseUrlResolver

        // Show the currently effective LAN URL.
        val customUrl = resolver.getCustomLanUrl()
        val effectiveUrl = customUrl ?: BaseUrlResolver.LAN_URL
        binding.tvLanCurrentUrl.text = getString(
            R.string.setting_lan_config_current, effectiveUrl
        )

        // Pre-fill the EditText with the custom URL if set, otherwise
        // leave it empty (the hint shows the default).
        if (!customUrl.isNullOrBlank()) {
            binding.etLanUrl.setText(customUrl)
        }

        binding.btnLanSave.setOnClickListener {
            val input = binding.etLanUrl.text?.toString()?.trim().orEmpty()
            if (input.isBlank()) {
                Toast.makeText(
                    requireContext(),
                    R.string.lan_config_invalid,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // Basic URL validation: must start with http:// or https://
            if (!input.startsWith("http://") && !input.startsWith("https://")) {
                Toast.makeText(
                    requireContext(),
                    R.string.lan_config_invalid,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            resolver.setCustomLanUrl(input)
            binding.tvLanCurrentUrl.text = getString(
                R.string.setting_lan_config_current, input
            )
            Toast.makeText(
                requireContext(),
                R.string.lan_config_saved,
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnLanReset.setOnClickListener {
            resolver.setCustomLanUrl(null)
            binding.etLanUrl.setText("")
            binding.tvLanCurrentUrl.text = getString(
                R.string.setting_lan_config_current, BaseUrlResolver.LAN_URL
            )
            Toast.makeText(
                requireContext(),
                R.string.lan_config_reset,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * v1.6.28: the update section now reflects the background auto-
     * download state managed by AppContainer. There is no update
     * dialog anymore — the single "Check for updates" button serves
     * three roles depending on the tag set by [renderCachedUpdateStatus]:
     *   - default: trigger forceCheckUpdate (which also starts the
     *     background download when a new version is found)
     *   - ACTION_INSTALL: the APK is ready on disk, launch the system
     *     PackageInstaller with one tap
     *   - ACTION_RETRY: the last background download failed, retry it
     *
     * The click listener is attached exactly once here (in onViewCreated
     * via setupUpdateSection) — never re-assigned in onResume — so the
     * first tap always lands on the current listener. This is the root
     * cause fix for the old "点三下" (triple-click) bug: re-assigning
     * the listener in onResume left the first tap firing a stale
     * listener that consumed the event without acting.
     */
    private fun setupUpdateSection() {
        val mainActivity = activity as? MainActivity ?: return
        val container = mainActivity.container
        val prefs = container.prefsManager

        // Setup admin-selectable update track (Release vs Debug)
        binding.layoutUpdateTrack.visibility = if (prefs.isAdmin) View.VISIBLE else View.GONE
        if (prefs.getEffectiveUpdateTrack() == PrefsManager.TRACK_DEBUG) {
            binding.rbTrackDebug.isChecked = true
        } else {
            binding.rbTrackRelease.isChecked = true
        }
        binding.radioGroupUpdateTrack.setOnCheckedChangeListener { _, checkedId ->
            val newTrack = if (checkedId == R.id.rbTrackDebug) PrefsManager.TRACK_DEBUG else PrefsManager.TRACK_RELEASE
            if (prefs.updateTrack != newTrack) {
                prefs.updateTrack = newTrack
                container.clearCachedUpdateInfo()
                renderCachedUpdateStatus()
                Toast.makeText(
                    requireContext(),
                    if (newTrack == PrefsManager.TRACK_DEBUG) "已切换至测试版通道 (Debug)" else "已切换至正式版通道 (Release)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Initial render from whatever state the background check /
        // download has reached so far.
        renderCachedUpdateStatus()
        startUpdatePollingIfNeeded()

        binding.tvToggleReleaseNotes.setOnClickListener {
            val isGone = binding.layoutReleaseNotes.visibility == View.GONE
            binding.layoutReleaseNotes.visibility = if (isGone) View.VISIBLE else View.GONE
            binding.tvToggleReleaseNotes.text = if (isGone) "收起版本说明 ▴" else "查看版本更新说明 ▾"
        }

        binding.btnCheckUpdate.setOnClickListener {
            val action = binding.btnCheckUpdate.tag as? String
            when (action) {
                ACTION_INSTALL -> {
                    val apkFile = container.getCachedDownloadedApk()
                    if (apkFile != null && apkFile.exists()) {
                        ApkInstaller.launchInstaller(requireActivity(), apkFile)
                    } else {
                        // State changed between render and tap — refresh.
                        renderCachedUpdateStatus()
                    }
                }
                ACTION_RETRY -> {
                    container.retryDownload()
                    renderCachedUpdateStatus()
                    startUpdatePollingIfNeeded()
                }
                else -> {
                    // Default: check for updates. forceCheckUpdate
                    // triggers startBackgroundDownload internally when
                    // a new version is found, so the UI will flip to
                    // "downloading…" on the next poll/render.
                    binding.btnCheckUpdate.isEnabled = false
                    binding.tvUpdateStatus.text = getString(R.string.update_checking)

                    lifecycleScope.launch {
                        try {
                            val info = container.forceCheckUpdate()
                            if (info == null) {
                                binding.tvUpdateStatus.text = getString(R.string.update_latest)
                                Toast.makeText(requireContext(), "当前已是最新版本", Toast.LENGTH_SHORT).show()
                            } else {
                                showUpdateAvailableDialog(info)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            if (view != null) {
                                binding.tvUpdateStatus.text = getString(R.string.update_check_failed)
                            }
                        } finally {
                            if (view != null) {
                                binding.btnCheckUpdate.isEnabled = true
                                renderCachedUpdateStatus()
                                startUpdatePollingIfNeeded()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showUpdateAvailableDialog(info: com.homedatacenter.app.data.model.UpdateInfo) {
        val context = context ?: return
        val mainActivity = activity as? MainActivity ?: return
        val apkFile = mainActivity.container.getCachedDownloadedApk()

        val dialogBinding = com.homedatacenter.app.databinding.DialogUpdateAvailableBinding.inflate(layoutInflater)
        val currentVersion = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName
        } catch (_: Exception) {
            "当前版本"
        }

        dialogBinding.tvCurrentVersion.text = currentVersion
        dialogBinding.tvNewVersion.text = info.version_name
        dialogBinding.tvUpdateSize.text = String.format(java.util.Locale.US, "%.1f MB", info.size_bytes / (1024.0 * 1024.0))

        if (info.release_notes.isNotBlank()) {
            dialogBinding.tvReleaseNotesLabel.visibility = View.VISIBLE
            dialogBinding.scrollReleaseNotes.visibility = View.VISIBLE
            dialogBinding.tvReleaseNotes.text = info.release_notes
        }

        val hasApk = apkFile != null && apkFile.exists()
        dialogBinding.btnDownloadInstall.text = if (hasApk) "立即安装" else "下载并安装"

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setView(dialogBinding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnDownloadInstall.setOnClickListener {
            dialog.dismiss()
            if (hasApk) {
                ApkInstaller.launchInstaller(requireActivity(), apkFile!!)
            } else {
                mainActivity.container.startDownloadFor(info)
                Toast.makeText(requireContext(), "正在后台下载新版本，完成后可在设置页安装", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    /**
     * v1.6.28: render the update card based on AppContainer's
     * background-download state. Sets the button's tag to drive the
     * single click listener defined in [setupUpdateSection].
     *
     * States (checked in priority order):
     *   1. APK ready on disk  → "v<x> 已就绪，点击安装", button = "安装"
     *   2. Downloading        → "正在下载 v<x>… <p>%"
     *   3. Download failed    → "下载失败，点此重试"
     *   4. Update available   → "新版本 <x> 可用" (fallback; download
     *      should already be auto-started, this covers the race)
     *   5. No update info     → default summary
     */
    private fun renderCachedUpdateStatus() {
        val mainActivity = activity as? MainActivity ?: return
        val container = mainActivity.container
        val info = container.getCachedUpdateInfo()
        val latestInfo = container.getLatestKnownReleaseInfo()
        val apkFile = container.getCachedDownloadedApk()

        val flavorSuffix = if (container.prefsManager.isAdmin && info?.isDebug == true) " (Debug)" else ""
        val displayVersion = (info?.version_name ?: "") + flavorSuffix

        // Render release notes section
        if (info != null && info.release_notes.isNotBlank()) {
            // New version update detected: expand and show release notes
            binding.layoutReleaseNotes.visibility = View.VISIBLE
            binding.tvReleaseNotesTitle.text = "新版本特性 (v${info.version_name})"
            binding.tvReleaseNotesBadge.text = "待更新"
            binding.tvReleaseNotesBadge.setTextColor(requireContext().getColor(R.color.primary))
            binding.tvReleaseNotes.text = info.release_notes
            binding.tvToggleReleaseNotes.visibility = View.GONE
        } else if (latestInfo != null && latestInfo.release_notes.isNotBlank()) {
            // Already latest version: show toggle button to view current release notes
            binding.tvToggleReleaseNotes.visibility = View.VISIBLE
            binding.tvReleaseNotesTitle.text = "当前版本特性 (v${latestInfo.version_name})"
            binding.tvReleaseNotesBadge.text = "已安装"
            binding.tvReleaseNotesBadge.setTextColor(requireContext().getColor(R.color.online))
            binding.tvReleaseNotes.text = latestInfo.release_notes
        } else {
            binding.tvToggleReleaseNotes.visibility = View.GONE
            binding.layoutReleaseNotes.visibility = View.GONE
        }

        when {
            // APK fully downloaded — ready to install.
            apkFile != null && info != null -> {
                binding.tvUpdateStatus.text = getString(
                    R.string.update_ready_to_install_format, displayVersion
                )
                binding.tvUpdateStatus.setTextColor(
                    requireContext().getColor(R.color.online)
                )
                binding.btnCheckUpdate.text = getString(R.string.btn_install)
                binding.btnCheckUpdate.tag = ACTION_INSTALL
            }
            // Download in flight — show progress.
            container.isDownloadingApk() && info != null -> {
                binding.tvUpdateStatus.text = getString(
                    R.string.update_downloading_format,
                    displayVersion,
                    container.getDownloadProgress()
                )
                binding.tvUpdateStatus.setTextColor(
                    requireContext().getColor(R.color.text_hint)
                )
                binding.btnCheckUpdate.text = getString(R.string.setting_check_update)
                binding.btnCheckUpdate.tag = null
            }
            // Download failed — offer retry.
            container.isDownloadFailed() -> {
                binding.tvUpdateStatus.text = getString(R.string.update_download_failed_retry)
                binding.tvUpdateStatus.setTextColor(
                    requireContext().getColor(R.color.error)
                )
                binding.btnCheckUpdate.text = getString(R.string.setting_check_update)
                binding.btnCheckUpdate.tag = ACTION_RETRY
            }
            // Update available but not yet downloading — fallback.
            info != null -> {
                binding.tvUpdateStatus.text = getString(
                    R.string.setting_check_update_new_format, displayVersion
                )
                binding.tvUpdateStatus.setTextColor(
                    requireContext().getColor(R.color.primary)
                )
                binding.btnCheckUpdate.text = getString(R.string.setting_check_update)
                binding.btnCheckUpdate.tag = null
            }
            else -> {
                binding.tvUpdateStatus.text = getString(R.string.setting_check_update_summary)
                binding.tvUpdateStatus.setTextColor(
                    requireContext().getColor(R.color.text_hint)
                )
                binding.btnCheckUpdate.text = getString(R.string.setting_check_update)
                binding.btnCheckUpdate.tag = null
            }
        }
    }

    /**
     * v1.6.28: start polling AppContainer's download state every
     * [POLL_INTERVAL_MS] while a download is in flight, so the
     * progress text stays live. Idempotent — removes any existing
     * callback before posting, so it's safe to call from onResume
     * and after a manual check without stacking runnables.
     */
    private fun startUpdatePollingIfNeeded() {
        val container = (activity as? MainActivity)?.container ?: return
        updatePollHandler.removeCallbacks(updatePollRunnable)
        if (container.isDownloadingApk()) {
            updatePollHandler.postDelayed(updatePollRunnable, POLL_INTERVAL_MS)
        }
    }

    private fun showLogoutDialog() {
        val context = context ?: return
        AlertDialog.Builder(context)
            .setTitle(R.string.confirm_logout_title)
            .setMessage(R.string.confirm_logout_message)
            .setPositiveButton(R.string.btn_confirm) { _, _ -> logout() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun logout() {
        val mainActivity = activity as? MainActivity ?: return
        mainActivity.container.prefsManager.clearAuth()
        mainActivity.navigateToLogin()
    }

    override fun onResume() {
        super.onResume()
        // Re-render on resume: the background download may have
        // completed (or failed) while the fragment was paused, and
        // if the user canceled the system installer and came back,
        // the cached APK is still ready to install.
        if (isAdded && _binding != null) {
            updateBatteryOptimizationState()
            renderCachedUpdateStatus()
            startUpdatePollingIfNeeded()
            // v1.8.24: refresh LAN URL display in case it was changed
            // elsewhere (e.g. cleared from another settings entry point).
            val mainActivity = activity as? MainActivity
            if (mainActivity != null) {
                setupJwtInfo(mainActivity.container.prefsManager)
                val resolver = mainActivity.container.baseUrlResolver
                val custom = resolver.getCustomLanUrl()
                val effective = custom ?: BaseUrlResolver.LAN_URL
                binding.tvLanCurrentUrl.text = getString(
                    R.string.setting_lan_config_current, effective
                )
                if (custom.isNullOrBlank()) {
                    binding.etLanUrl.setText("")
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        updatePollHandler.removeCallbacks(updatePollRunnable)
        _binding = null
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1000L

        // Tag values stored on btnCheckUpdate.tag to drive the single
        // click listener in setupUpdateSection without re-assigning it.
        private const val ACTION_INSTALL = "install"
        private const val ACTION_RETRY = "retry"
    }
}
