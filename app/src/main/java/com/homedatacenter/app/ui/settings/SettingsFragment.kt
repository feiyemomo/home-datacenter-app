package com.homedatacenter.app.ui.settings

import android.app.AlertDialog
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
import com.homedatacenter.app.ui.main.MainActivity
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
        setupProfileCard(prefs)
        setupJwtInfo(prefs)
        setupLanConfig()
        setupUpdateSection()

        binding.btnAccountManagement.setOnClickListener {
            if (binding.tvAccountAction.visibility == View.VISIBLE) {
                binding.tvAccountAction.visibility = View.GONE
            } else {
                binding.tvAccountAction.visibility = View.VISIBLE
            }
        }
        binding.tvAccountAction.setOnClickListener { showLogoutDialog() }

        loadUserInfo()
        setupVersion()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            val mainActivity = activity as? MainActivity ?: return
            setupJwtInfo(mainActivity.container.prefsManager)
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

    private fun setupProfileCard(prefs: PrefsManager) {
        // Initial render from cached prefs so the card is populated
        // before the /me call resolves.
        if (!prefs.userName.isNullOrEmpty()) {
            val adminLabel = if (prefs.isAdmin) {
                " (${getString(R.string.setting_admin_label)})"
            } else ""
            binding.tvUserName.text = prefs.userName + adminLabel
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
            val mainActivity = activity as? MainActivity ?: return@setOnClickListener
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
                val adminLabel = if (user.isAdmin) {
                    " (${getString(R.string.setting_admin_label)})"
                } else ""
                binding.tvUserName.text = user.name + adminLabel
                // Admin section was removed — no refresh needed.
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
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

        // Initial render from whatever state the background check /
        // download has reached so far.
        renderCachedUpdateStatus()
        startUpdatePollingIfNeeded()

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
        val apkFile = container.getCachedDownloadedApk()

        val flavorSuffix = if (container.prefsManager.isAdmin && info?.isDebug == true) " (Debug)" else ""
        val displayVersion = (info?.version_name ?: "") + flavorSuffix

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
