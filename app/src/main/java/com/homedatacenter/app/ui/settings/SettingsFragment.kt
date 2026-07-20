package com.homedatacenter.app.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.homedatacenter.app.R
import com.homedatacenter.app.databinding.FragmentSettingsBinding
import com.homedatacenter.app.ui.admin.UsersActivity
import com.homedatacenter.app.ui.main.MainActivity
import com.homedatacenter.app.util.ApkInstaller
import com.homedatacenter.app.util.JwtUtil
import com.homedatacenter.app.util.PrefsManager
import com.homedatacenter.app.util.ThemeManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

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
        setupAdminSection(prefs)
        setupJwtInfo(prefs)
        setupUpdateSection()

        binding.btnLogout.setOnClickListener { showLogoutDialog() }
        binding.btnOpenUsers.setOnClickListener {
            startActivity(Intent(requireContext(), UsersActivity::class.java))
        }

        loadUserInfo()
        setupVersion()
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
            // setDefaultNightMode triggers Activity recreate automatically.
            // Post the recreate to the next frame to avoid conflicts with the
            // RadioGroup state restoration during the current frame.
            binding.root.post { activity?.recreate() }
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

    private fun setupAdminSection(prefs: PrefsManager) {
        val isAdmin = prefs.isAdmin
        binding.tvAdminSectionLabel.visibility = if (isAdmin) View.VISIBLE else View.GONE
        binding.cardAdmin.visibility = if (isAdmin) View.VISIBLE else View.GONE
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
                // Refresh admin section visibility based on fresh role.
                setupAdminSection(prefs)
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
     * v1.6.11: in-app self-update section. On view creation we
     * immediately render the cached UpdateInfo (from the silent
     * background check in HomeCenterApp.onCreate). The button
     * triggers a fresh forceCheckUpdate and shows the result.
     */
    private fun setupUpdateSection() {
        val mainActivity = activity as? MainActivity ?: return
        val container = mainActivity.container

        // Render cached state immediately (e.g. "new version 1.6.11
        // available" if the background startup check found one).
        renderCachedUpdateStatus()

        binding.btnCheckUpdate.setOnClickListener {
            // Disable button + show "checking..." while the network
            // call is in flight. Re-enable on completion.
            binding.btnCheckUpdate.isEnabled = false
            binding.tvUpdateStatus.text = getString(R.string.update_checking)

            lifecycleScope.launch {
                try {
                    val info = container.forceCheckUpdate()
                    if (info != null) {
                        // New version available — show confirmation
                        // dialog with download/install button.
                        showUpdateAvailableDialog(info)
                    } else {
                        binding.tvUpdateStatus.text = getString(R.string.update_latest)
                    }
                } catch (_: Exception) {
                    binding.tvUpdateStatus.text = getString(R.string.update_check_failed)
                } finally {
                    binding.btnCheckUpdate.isEnabled = true
                }
            }
        }
    }

    /** Render the cached UpdateInfo (if any) as a "new version
     *  available" hint. Called from setupUpdateSection on view
     *  creation so the user sees the startup-check result without
     *  having to tap "Check for updates" themselves. */
    private fun renderCachedUpdateStatus() {
        val mainActivity = activity as? MainActivity ?: return
        val info = mainActivity.container.getCachedUpdateInfo()
        if (info != null) {
            binding.tvUpdateStatus.text = getString(
                R.string.setting_check_update_new_format, info.version_name
            )
            // Tint the status text to the primary (warm peach) color
            // so the "new version available" hint stands out from
            // the default text_hint color.
            binding.tvUpdateStatus.setTextColor(
                requireContext().getColor(R.color.primary)
            )
        } else {
            binding.tvUpdateStatus.text = getString(R.string.setting_check_update_summary)
            binding.tvUpdateStatus.setTextColor(
                requireContext().getColor(R.color.text_hint)
            )
        }
    }

    /**
     * Show the "new version available" dialog with current version,
     * new version, APK size, and a Download+Install button. On
     * confirm, kicks off ApkInstaller.downloadAndInstall which
     * streams the APK to private storage and launches the system
     * PackageInstaller.
     */
    private fun showUpdateAvailableDialog(info: com.homedatacenter.app.data.model.UpdateInfo) {
        val context = context ?: return
        val mainActivity = activity as? MainActivity ?: return

        val currentVersion = ApkInstaller.installedVersionName(context)
        val sizeStr = formatSize(info.size_bytes)

        AlertDialog.Builder(context)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(
                R.string.update_available_message_format,
                currentVersion, info.version_name, sizeStr
            ))
            .setPositiveButton(R.string.btn_download_install) { _, _ ->
                startApkDownload(info)
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                // Re-render the cached status (still shows "new
                // version available" so the user can come back).
                renderCachedUpdateStatus()
            }
            .show()
    }

    /**
     * Stream the APK to disk and launch the installer. Updates
     * tvUpdateStatus with download progress (0..100%). On failure
     * shows an error message — the user can retry by tapping
     * "Check for updates" again.
     */
    private fun startApkDownload(info: com.homedatacenter.app.data.model.UpdateInfo) {
        val mainActivity = activity as? MainActivity ?: return
        val container = mainActivity.container
        val token = container.prefsManager.token ?: return
        val activity = activity ?: return

        binding.btnCheckUpdate.isEnabled = false
        binding.tvUpdateStatus.text = getString(R.string.update_download_in_progress)

        lifecycleScope.launch {
            val success = ApkInstaller.downloadAndInstall(
                activity = activity,
                repo = container.getRepository(),
                token = token,
                info = info,
                onProgress = { percent ->
                    // onProgress fires from the IO dispatcher — hop
                    // back to main to update the TextView safely.
                    if (isAdded) {
                        binding.tvUpdateStatus.text = getString(
                            R.string.update_download_progress_format, percent
                        )
                    }
                }
            )

            if (isAdded) {
                binding.btnCheckUpdate.isEnabled = true
                if (!success) {
                    binding.tvUpdateStatus.text = getString(R.string.update_download_failed)
                    binding.tvUpdateStatus.setTextColor(
                        requireContext().getColor(R.color.error)
                    )
                }
                // If success, the system PackageInstaller is now
                // showing the install confirmation screen. When the
                // user finishes the install, the app process is
                // killed and restarted — no need to update UI here.
            }
        }
    }

    /** Format a byte count as "12.3 MB" or "1.23 GB". */
    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) {
            getString(R.string.update_size_gb_format, mb / 1024)
        } else {
            getString(R.string.update_size_mb_format, mb)
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
        // After the user installs an update the app restarts, but
        // if they cancel the install and come back to settings, we
        // should re-render the cached update hint (in case the
        // startup check found one).
        if (isAdded && _binding != null) {
            renderCachedUpdateStatus()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
