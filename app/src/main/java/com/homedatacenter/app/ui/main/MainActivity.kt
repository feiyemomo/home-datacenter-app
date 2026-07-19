package com.homedatacenter.app.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
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
    }

    private fun setupFragments(savedInstanceState: Bundle?) {
        val fm = supportFragmentManager
        dashboardFragment = fm.findFragmentByTag("dashboard") ?: DashboardFragment()
        camerasFragment = fm.findFragmentByTag("cameras") ?: CamerasFragment()
        alertsFragment = fm.findFragmentByTag("alerts") ?: AlertsFragment()
        devicesFragment = fm.findFragmentByTag("devices") ?: DevicesFragment()
        settingsFragment = fm.findFragmentByTag("settings") ?: SettingsFragment()

        if (savedInstanceState == null) {
            fm.commit {
                add(R.id.nav_host_fragment, settingsFragment, "settings").hide(settingsFragment)
                add(R.id.nav_host_fragment, devicesFragment, "devices").hide(devicesFragment)
                add(R.id.nav_host_fragment, alertsFragment, "alerts").hide(alertsFragment)
                add(R.id.nav_host_fragment, camerasFragment, "cameras").hide(camerasFragment)
                add(R.id.nav_host_fragment, dashboardFragment, "dashboard")
            }
            activeFragment = dashboardFragment
        } else {
            activeFragment = when (binding.bottomNav.selectedItemId) {
                R.id.nav_cameras -> camerasFragment
                R.id.nav_alerts -> alertsFragment
                R.id.nav_devices -> devicesFragment
                R.id.nav_settings -> settingsFragment
                else -> dashboardFragment
            }
            fm.commit {
                hide(dashboardFragment)
                hide(camerasFragment)
                hide(alertsFragment)
                hide(devicesFragment)
                hide(settingsFragment)
                show(activeFragment!!)
            }
        }
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
