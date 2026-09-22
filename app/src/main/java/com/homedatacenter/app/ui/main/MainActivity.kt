package com.homedatacenter.app.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.databinding.ActivityMainBinding
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.ui.admin.UsersFragment
import com.homedatacenter.app.ui.cameras.CameraDetailActivity
import com.homedatacenter.app.ui.cameras.CamerasFragment
import com.homedatacenter.app.ui.dashboard.DashboardFragment
import com.homedatacenter.app.ui.login.LoginActivity
import com.homedatacenter.app.ui.logs.ServiceLogsFragment
import com.homedatacenter.app.ui.settings.SettingsFragment
import com.homedatacenter.app.util.NotificationHelper
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var _binding: ActivityMainBinding
    val binding get() = _binding
    lateinit var container: AppContainer

    private var activeFragment: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        container = (application as HomeCenterApp).container

        if (!container.prefsManager.isLoggedIn()) {
            navigateToLogin()
            return
        }

        // Pass the real savedInstanceState so setupFragments can skip
        // re-adding fragments after a config change (e.g. theme switch
        // via recreate()). super.onCreate has already restored fragment
        // state, so the fragments are added and their show/hide state is
        // preserved. Calling add() again would throw
        // IllegalStateException: Fragment already added.
        setupFragments(savedInstanceState)
        setupNavigation()
        updateMenuByPermission()
        // Kick off a LAN probe on UI entry. By the time MainActivity
        // is created the user has waited through the launcher animation
        // and Application.onCreate's initial probe schedule — but on
        // real phones the WiFi stack may still have been unvalidated
        // at that point. By MainActivity.onCreate the WiFi is almost
        // certainly validated (the user can see the WiFi icon in the
        // status bar), so this probe has a high probability of
        // succeeding. It's a no-op if the resolver already picked LAN.
        container.baseUrlResolver.forceProbe()
        // Refresh /me on launch — admin status may have changed since
        // last login (server-side demotion takes effect immediately,
        // but our local cache in PrefsManager may be stale). This
        // updates the role cache so role-based UI (admin-only buttons,
        // FAB on CamerasFragment, Users button in SettingsFragment)
        // shows correctly.
        refreshRole()

        // Refresh token sliding expiry on app open / enter
        container.tryAutoRefreshToken()

        // Initialize system notification channels and request permission on Android 13+
        NotificationHelper.createChannels(this)
        checkNotificationPermission()
        handleIntentNavigation(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentNavigation(intent)
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    private fun handleIntentNavigation(intent: Intent) {
        val tabId = intent.getIntExtra(NotificationHelper.EXTRA_NAVIGATE_TAB, 0)
        if (tabId != 0 && tabId != binding.bottomNav.selectedItemId) {
            binding.bottomNav.selectedItemId = tabId
        }
        val cameraId = intent.getLongExtra(NotificationHelper.EXTRA_ALERT_CAMERA_ID, 0L)
        val startTs = intent.getLongExtra(NotificationHelper.EXTRA_ALERT_START_TS, 0L)
        if (cameraId > 0L) {
            jumpToCameraDetail(cameraId, startTs)
        }
    }

    private fun jumpToCameraDetail(cameraId: Long, startTs: Long) {
        lifecycleScope.launch {
            try {
                val token = container.prefsManager.token ?: return@launch
                val cameras = container.getRepository().listCameras(token, useCache = true)
                val cam = cameras.firstOrNull { it.id == cameraId } ?: return@launch
                val cameraJson = NetworkFactory.json.encodeToString(Camera.serializer(), cam)
                val intent = Intent(this@MainActivity, CameraDetailActivity::class.java).apply {
                    putExtra(CameraDetailActivity.EXTRA_CAMERA_JSON, cameraJson)
                    if (startTs > 0L) {
                        putExtra(CameraDetailActivity.EXTRA_INITIAL_TIMESTAMP, startTs)
                    }
                }
                startActivity(intent)
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "jumpToCameraDetail failed: ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Sliding refresh token if debounce elapsed
        container.tryAutoRefreshToken()
        // Returning to MainActivity from another activity (e.g. after
        // the user changed WiFi settings, switched VPN, came back from
        // background) is a strong signal to re-probe. forceProbe is a
        // no-op if a probe is already in flight, and the TTL prevents
        // over-probing in the common case (user is just switching
        // tabs inside the app — no MainActivity.onResume fires).
        container.baseUrlResolver.forceProbe()
        // Refresh role on resume — covers the case where the admin
        // demoted themselves in another session (e.g. web dashboard).
        refreshRole()
    }

    // v1.6.13: re-sync the active fragment to the BottomNavigationView's
    // restored selected item. onRestoreInstanceState runs AFTER
    // BottomNavigationView's state has been restored (super.onRestoreInstanceState
    // dispatches view-state restoration to children), so reading
    // selectedItemId here returns the user's actual last tab — not the
    // menu default we'd see during onCreate.
    //
    // Without this override, the bottom nav indicator points at (say)
    // nav_cameras after a process-death recovery, but the actually-shown
    // fragment is dashboardFragment (because setupFragments in onCreate
    // always adds dashboard as the visible one). The user clicks the
    // nav_cameras tab again to "fix" it but our showFragment early-returns
    // because fragment === activeFragment is true for cameras — wait,
    // activeFragment is dashboardFragment, not cameras, so the click WOULD
    // work. The real pain is the visual mismatch: indicator on cameras,
    // screen showing dashboard, until the user clicks something.
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        when (binding.bottomNav.selectedItemId) {
            R.id.nav_cameras -> showFragmentByTag(TAG_CAMERAS) { CamerasFragment() }
            R.id.nav_logs -> showFragmentByTag(TAG_LOGS) { ServiceLogsFragment() }
            R.id.nav_users -> showFragmentByTag(TAG_USERS) { UsersFragment() }
            R.id.nav_settings -> showFragmentByTag(TAG_SETTINGS) { SettingsFragment() }
            else -> showFragmentByTag(TAG_DASHBOARD) { DashboardFragment() }
        }
    }

    private fun refreshRole() {
        val token = container.prefsManager.token ?: return
        lifecycleScope.launch {
            try {
                container.roleManager.refresh(token)
            } catch (_: Exception) {
            }
            // Re-evaluate admin-gated UI after role refresh. The
            // individual fragments read prefsManager.isAdmin in their
            // own onViewCreated, so they will pick up the new value
            // when the user next navigates to them. Only the bottom
            // nav needs an explicit refresh here.
            runOnUiThread { updateMenuByPermission() }
        }
    }

    private fun setupFragments(savedInstanceState: Bundle?) {
        val fm = supportFragmentManager
        if (savedInstanceState == null) {
            // First creation: only add the default DashboardFragment lazily.
            // Other tabs will be created and added on first navigation.
            val dashboard = DashboardFragment()
            fm.commit {
                add(R.id.nav_host_fragment, dashboard, TAG_DASHBOARD)
            }
            activeFragment = dashboard
        } else {
            // After recreation (e.g. theme switch), super.onCreate has
            // already restored fragment state — fragments are added and
            // their show/hide state is preserved. Do NOT call add() again
            // or it throws IllegalStateException: Fragment already added.
            // Determine the currently-visible fragment from restored state.
            val tags = listOf(TAG_DASHBOARD, TAG_CAMERAS, TAG_LOGS, TAG_USERS, TAG_SETTINGS)
            activeFragment = tags.mapNotNull { fm.findFragmentByTag(it) }
                .firstOrNull { it.isAdded && !it.isHidden }
                ?: fm.findFragmentByTag(TAG_DASHBOARD)
        }
    }

    private fun updateMenuByPermission() {
        // Service logs tab is admin-only — non-admin users get a
        // simplified navigation bar without the logs entry.
        binding.bottomNav.menu.findItem(R.id.nav_logs)?.isVisible = container.prefsManager.isAdmin
        binding.bottomNav.menu.findItem(R.id.nav_users)?.isVisible = container.prefsManager.isAdmin
    }

    private fun setupNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> { showFragmentByTag(TAG_DASHBOARD) { DashboardFragment() }; true }
                R.id.nav_cameras -> { showFragmentByTag(TAG_CAMERAS) { CamerasFragment() }; true }
                R.id.nav_logs -> { showFragmentByTag(TAG_LOGS) { ServiceLogsFragment() }; true }
                R.id.nav_users -> { showFragmentByTag(TAG_USERS) { UsersFragment() }; true }
                R.id.nav_settings -> { showFragmentByTag(TAG_SETTINGS) { SettingsFragment() }; true }
                else -> false
            }
        }
    }

    private fun showFragmentByTag(tag: String, factory: () -> Fragment) {
        val fm = supportFragmentManager
        val target = fm.findFragmentByTag(tag)
        if (target != null && target === activeFragment) return

        fm.commit {
            setCustomAnimations(R.anim.fragment_slide_in_right, R.anim.fragment_slide_out_left)
            activeFragment?.let { hide(it) }
            if (target == null) {
                val newFragment = factory()
                add(R.id.nav_host_fragment, newFragment, tag)
                activeFragment = newFragment
            } else {
                show(target)
                activeFragment = target
            }
        }
    }

    fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        startActivity(intent)
        finish()
    }

    companion object {
        private const val TAG_DASHBOARD = "dashboard"
        private const val TAG_CAMERAS = "cameras"
        private const val TAG_LOGS = "logs"
        private const val TAG_USERS = "users"
        private const val TAG_SETTINGS = "settings"
    }
}
