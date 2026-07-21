package com.homedatacenter.app.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.databinding.ActivityMainBinding
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.ui.alerts.AlertsFragment
import com.homedatacenter.app.ui.cameras.CamerasFragment
import com.homedatacenter.app.ui.dashboard.DashboardFragment
import com.homedatacenter.app.ui.devices.DevicesFragment
import com.homedatacenter.app.ui.login.LoginActivity
import com.homedatacenter.app.ui.settings.SettingsFragment
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var _binding: ActivityMainBinding
    val binding get() = _binding
    lateinit var container: AppContainer

    private lateinit var dashboardFragment: Fragment
    private lateinit var camerasFragment: Fragment
    private lateinit var alertsFragment: Fragment
    private lateinit var devicesFragment: Fragment
    private lateinit var settingsFragment: Fragment
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

        // v1.6.13: pass null savedInstanceState to setupFragments so the
        // else-branch never reads bottomNav.selectedItemId during onCreate
        // — at this point BottomNavigationView's saved state hasn't been
        // restored yet (restoration happens in onRestoreInstanceState,
        // which runs AFTER onCreate). Reading selectedItemId here returns
        // the menu's default (nav_dashboard), which desyncs the indicator
        // from the actually-shown fragment after a config change / process
        // death recovery. We now ALWAYS start with the dashboard + a
        // hidden set of other fragments, and onRestoreInstanceState
        // re-syncs the active fragment to the user's last tab.
        setupFragments(null)
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
    }

    override fun onResume() {
        super.onResume()
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
        val target = when (binding.bottomNav.selectedItemId) {
            R.id.nav_cameras -> camerasFragment
            R.id.nav_alerts -> alertsFragment
            R.id.nav_devices -> devicesFragment
            R.id.nav_settings -> settingsFragment
            else -> dashboardFragment
        }
        if (target !== activeFragment) {
            supportFragmentManager.commit {
                setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
                hide(activeFragment ?: return@commit)
                show(target)
            }
            activeFragment = target
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
        dashboardFragment = fm.findFragmentByTag("dashboard") ?: DashboardFragment()
        camerasFragment = fm.findFragmentByTag("cameras") ?: CamerasFragment()
        alertsFragment = fm.findFragmentByTag("alerts") ?: AlertsFragment()
        devicesFragment = fm.findFragmentByTag("devices") ?: DevicesFragment()
        settingsFragment = fm.findFragmentByTag("settings") ?: SettingsFragment()

        // v1.6.13: always add the dashboard as the initially-visible
        // fragment. onRestoreInstanceState (if there's saved state)
        // re-syncs the active fragment to the user's last tab once
        // BottomNavigationView's selection has been restored. Reading
        // selectedItemId here is wrong — view state hasn't been
        // restored yet at onCreate time.
        fm.commit {
            add(R.id.nav_host_fragment, settingsFragment, "settings").hide(settingsFragment)
            add(R.id.nav_host_fragment, devicesFragment, "devices").hide(devicesFragment)
            add(R.id.nav_host_fragment, alertsFragment, "alerts").hide(alertsFragment)
            add(R.id.nav_host_fragment, camerasFragment, "cameras").hide(camerasFragment)
            add(R.id.nav_host_fragment, dashboardFragment, "dashboard")
        }
        activeFragment = dashboardFragment
    }

    private fun updateMenuByPermission() {
        // The backend allows every authenticated user to list and manage
        // devices within their own scope. Administrator-only actions are
        // gated inside their respective screens and by the server.
        binding.bottomNav.menu.findItem(R.id.nav_devices)?.isVisible = true
    }

    private fun setupNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> { showFragment(dashboardFragment); true }
                R.id.nav_cameras -> { showFragment(camerasFragment); true }
                R.id.nav_alerts -> { showFragment(alertsFragment); true }
                R.id.nav_devices -> { showFragment(devicesFragment); true }
                R.id.nav_settings -> { showFragment(settingsFragment); true }
                else -> false
            }
        }
    }

    private fun showFragment(fragment: Fragment) {
        if (fragment === activeFragment) return
        supportFragmentManager.commit {
            setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
            hide(activeFragment ?: return@commit)
            show(fragment)
        }
        activeFragment = fragment
    }

    fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        startActivity(intent)
        finish()
    }
}
