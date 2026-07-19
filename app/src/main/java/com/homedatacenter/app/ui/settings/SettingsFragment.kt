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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
