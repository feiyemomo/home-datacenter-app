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
import com.homedatacenter.app.ui.cameras.CamerasFragment
import com.homedatacenter.app.ui.dashboard.DashboardFragment
import com.homedatacenter.app.ui.logs.ServiceLogsFragment
import com.homedatacenter.app.ui.login.LoginActivity
import com.homedatacenter.app.ui.admin.UsersFragment
import com.homedatacenter.app.ui.settings.SettingsFragment
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var _binding: ActivityMainBinding
    val binding get() = _binding
    lateinit var container: AppContainer

    private lateinit var dashboardFragment: Fragment
    private lateinit var camerasFragment: Fragment
    private lateinit var logsFragment: Fragment
    private lateinit var settingsFragment: Fragment
    private lateinit var usersFragment: Fragment
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
            R.id.nav_logs -> logsFragment
            R.id.nav_users -> usersFragment
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
        logsFragment = fm.findFragmentByTag("logs") ?: ServiceLogsFragment()
        usersFragment = fm.findFragmentByTag("users") ?: UsersFragment()
        settingsFragment = fm.findFragmentByTag("settings") ?: SettingsFragment()

        if (savedInstanceState == null) {
            // First creation: add all fragments, show dashboard.
            fm.commit {
                add(R.id.nav_host_fragment, settingsFragment, "settings").hide(settingsFragment)
                add(R.id.nav_host_fragment, usersFragment, "users").hide(usersFragment)
                add(R.id.nav_host_fragment, logsFragment, "logs").hide(logsFragment)
                add(R.id.nav_host_fragment, camerasFragment, "cameras").hide(camerasFragment)
                add(R.id.nav_host_fragment, dashboardFragment, "dashboard")
            }
            activeFragment = dashboardFragment
        } else {
            // After recreation (e.g. theme switch), super.onCreate has
            // already restored fragment state — fragments are added and
            // their show/hide state is preserved. Do NOT call add() again
            // or it throws IllegalStateException: Fragment already added.
            // Determine the currently-visible fragment from restored state.
            activeFragment = listOf(
                dashboardFragment, camerasFragment, logsFragment,
                usersFragment, settingsFragment
            ).firstOrNull { it.isAdded && !it.isHidden } ?: dashboardFragment
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
                R.id.nav_dashboard -> { showFragment(dashboardFragment); true }
                R.id.nav_cameras -> { showFragment(camerasFragment); true }
                R.id.nav_logs -> { showFragment(logsFragment); true }
                R.id.nav_users -> { showFragment(usersFragment); true }
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
