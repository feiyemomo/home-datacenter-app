package com.homedatacenter.app.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.data.model.WsMessage
import com.homedatacenter.app.databinding.ActivityMainBinding
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.ui.admin.UsersFragment
import com.homedatacenter.app.ui.cameras.CameraDetailActivity
import com.homedatacenter.app.ui.cameras.CamerasFragment
import com.homedatacenter.app.ui.components.LiquidGlassBottomBar
import com.homedatacenter.app.ui.components.MainTab
import com.homedatacenter.app.ui.dashboard.DashboardFragment
import com.homedatacenter.app.ui.glass.AmbientFluidBackground
import com.homedatacenter.app.ui.glass.ProvideGlassTheme
import com.homedatacenter.app.ui.login.LoginActivity
import com.homedatacenter.app.ui.ops.OpsFragment
import com.homedatacenter.app.ui.settings.SettingsFragment
import com.homedatacenter.app.util.NotificationHelper
import com.homedatacenter.app.util.ThemeManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var _binding: ActivityMainBinding
    val binding get() = _binding
    lateinit var container: AppContainer

    private var activeFragment: Fragment? = null
    private var currentTab by mutableStateOf(MainTab.Dashboard)
    var isAdminState by mutableStateOf(false)
    private val tabHistory = java.util.ArrayDeque<MainTab>()
    private var lastBackPressTime = 0L
    private var keyboardLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        container = (application as HomeCenterApp).container

        if (!container.prefsManager.isLoggedIn()) {
            navigateToLogin()
            return
        }

        isAdminState = container.prefsManager.isAdmin

        // Restore or initialize selected tab before rendering to prevent first-frame tab jump
        val initialTab = savedInstanceState?.getString(KEY_SELECTED_TAB)?.let {
            runCatching { MainTab.valueOf(it) }.getOrNull()
        } ?: MainTab.Dashboard
        currentTab = if (initialTab.requiresAdmin && !isAdminState) MainTab.Dashboard else initialTab

        // Ambient fluid mesh background
        binding.globalFluidBackground.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.globalFluidBackground.setContent {
            val preset by ThemeManager.themePresetFlow.collectAsState()
            AmbientFluidBackground(preset = preset)
        }

        // Pure Compose LiquidGlassBottomBar
        binding.composeBottomNav.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeBottomNav.setContent {
            val preset by ThemeManager.themePresetFlow.collectAsState()
            val isAdmin by container.roleManager.isAdminFlow.collectAsState()
            ProvideGlassTheme(preset = preset) {
                LiquidGlassBottomBar(
                    currentTab = currentTab,
                    onTabSelected = { tab ->
                        selectTab(tab)
                    },
                    isAdmin = isAdmin,
                )
            }
        }
        setupKeyboardListener()

        setupFragments(savedInstanceState)
        container.baseUrlResolver.forceProbe()
        refreshRole()
        container.tryAutoRefreshToken()

        NotificationHelper.createChannels(this)
        checkNotificationPermission()

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (tabHistory.isNotEmpty()) {
                    val prevTab = tabHistory.pop()
                    selectTab(prevTab, addToHistory = false)
                    return
                }
                if (currentTab != MainTab.Dashboard) {
                    selectTab(MainTab.Dashboard, addToHistory = false)
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000L) {
                    finish()
                } else {
                    lastBackPressTime = now
                    android.widget.Toast.makeText(this@MainActivity, "再按一次退出应用", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        })
        if (savedInstanceState == null) {
            handleIntentNavigation(intent)
        }
        ensureWebSocketConnected()

        binding.offlineBanner.setOnClickListener {
            container.baseUrlResolver.forceProbe()
        }
        container.baseUrlResolver.addReachabilityListener(reachabilityListener)
        updateOfflineBanner(container.baseUrlResolver.serverReachable)
    }

    private val reachabilityListener: (Boolean) -> Unit = { reachable ->
        runOnUiThread { updateOfflineBanner(reachable) }
    }

    private fun updateOfflineBanner(reachable: Boolean) {
        if (isFinishing || isDestroyed) return
        binding.offlineBanner.visibility =
            if (reachable) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun setupKeyboardListener() {
        val rootView = binding.root
        var isKeyboardOpen = false
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val rect = Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val screenHeight = rootView.rootView.height
            val keypadHeight = screenHeight - rect.bottom
            val keyboardNowOpen = keypadHeight > screenHeight * 0.15

            if (keyboardNowOpen != isKeyboardOpen) {
                isKeyboardOpen = keyboardNowOpen
                if (isKeyboardOpen) {
                    // Keyboard popped up: smoothly animate translation down and set GONE so it doesn't cover input box
                    binding.composeBottomNav.animate()
                        .translationY(binding.composeBottomNav.height.toFloat().coerceAtLeast(200f))
                        .alpha(0f)
                        .setDuration(180)
                        .withEndAction {
                            binding.composeBottomNav.visibility = View.GONE
                        }
                        .start()
                } else {
                    // Keyboard closed: restore VISIBLE and smoothly animate back up
                    binding.composeBottomNav.visibility = View.VISIBLE
                    binding.composeBottomNav.translationY = binding.composeBottomNav.height.toFloat().coerceAtLeast(200f)
                    binding.composeBottomNav.alpha = 0f
                    binding.composeBottomNav.animate()
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(220)
                        .start()
                }
            }
        }
        rootView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        keyboardLayoutListener = layoutListener
    }

    override fun onDestroy() {
        keyboardLayoutListener?.let { listener ->
            binding.root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
        if (::container.isInitialized) {
            container.baseUrlResolver.removeReachabilityListener(reachabilityListener)
        }
        super.onDestroy()
    }

    fun registerWsListener(listener: (WsMessage) -> Unit) {
        com.homedatacenter.app.service.AlertKeepAliveService.registerUiListener(listener)
    }

    fun unregisterWsListener(listener: (WsMessage) -> Unit) {
        com.homedatacenter.app.service.AlertKeepAliveService.unregisterUiListener(listener)
    }

    fun ensureWebSocketConnected() {
        if (container.prefsManager.keepAliveEnabled) {
            com.homedatacenter.app.service.AlertKeepAliveService.start(this)
        }
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
        if (tabId != 0) {
            intent.removeExtra(NotificationHelper.EXTRA_NAVIGATE_TAB)
            setIntent(intent)
            val targetTab = when (tabId) {
                R.id.nav_cameras -> MainTab.Cameras
                R.id.nav_logs -> MainTab.Ops
                R.id.nav_users -> MainTab.Users
                R.id.nav_settings -> MainTab.Settings
                else -> MainTab.Dashboard
            }
            selectTab(targetTab)
        }
        val cameraId = intent.getLongExtra(NotificationHelper.EXTRA_ALERT_CAMERA_ID, 0L)
        val startTs = intent.getLongExtra(NotificationHelper.EXTRA_ALERT_START_TS, 0L)
        if (cameraId > 0L) {
            intent.removeExtra(NotificationHelper.EXTRA_ALERT_CAMERA_ID)
            intent.removeExtra(NotificationHelper.EXTRA_ALERT_START_TS)
            setIntent(intent)
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
        container.tryAutoRefreshToken()
        container.baseUrlResolver.forceProbe()
        refreshRole()
        ensureWebSocketConnected()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SELECTED_TAB, currentTab.name)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val tabName = savedInstanceState.getString(KEY_SELECTED_TAB)
        val restoredTab = tabName?.let { runCatching { MainTab.valueOf(it) }.getOrNull() } ?: MainTab.Dashboard
        selectTab(restoredTab)
    }

    private fun refreshRole() {
        val token = container.prefsManager.token ?: return
        lifecycleScope.launch {
            try {
                container.roleManager.refresh(token)
            } catch (_: Exception) {
            }
            runOnUiThread {
                isAdminState = container.prefsManager.isAdmin
                // Demotion fallback: if active tab requires admin and user is no longer admin, fall back to Dashboard
                if (!isAdminState && currentTab.requiresAdmin) {
                    selectTab(MainTab.Dashboard)
                }
            }
        }
    }

    private fun setupFragments(savedInstanceState: Bundle?) {
        val fm = supportFragmentManager
        if (savedInstanceState == null) {
            val dashboard = DashboardFragment()
            fm.commit {
                add(R.id.nav_host_fragment, dashboard, TAG_DASHBOARD)
            }
            activeFragment = dashboard
            currentTab = MainTab.Dashboard
        } else {
            val tags = listOf(TAG_DASHBOARD, TAG_CAMERAS, TAG_LOGS, TAG_USERS, TAG_SETTINGS)
            activeFragment = tags.mapNotNull { fm.findFragmentByTag(it) }
                .firstOrNull { it.isAdded && !it.isHidden }
                ?: fm.findFragmentByTag(TAG_DASHBOARD)
        }
    }

    fun selectTab(tab: MainTab, addToHistory: Boolean = true) {
        // Guard admin-only tabs
        val target = if (tab.requiresAdmin && !isAdminState) MainTab.Dashboard else tab
        if (target != currentTab && addToHistory) {
            tabHistory.remove(target)
            tabHistory.push(currentTab)
        }
        currentTab = target
        when (target) {
            MainTab.Dashboard -> showFragmentByTag(TAG_DASHBOARD) { DashboardFragment() }
            MainTab.Cameras -> showFragmentByTag(TAG_CAMERAS) { CamerasFragment() }
            MainTab.Ops -> showFragmentByTag(TAG_LOGS) { OpsFragment() }
            MainTab.Users -> showFragmentByTag(TAG_USERS) { UsersFragment() }
            MainTab.Settings -> showFragmentByTag(TAG_SETTINGS) { SettingsFragment() }
        }
    }

    private fun showFragmentByTag(tag: String, factory: () -> Fragment) {
        val fm = supportFragmentManager
        val target = fm.findFragmentByTag(tag)
        if (target != null && target === activeFragment) return

        fm.commit {
            setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
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
        private const val KEY_SELECTED_TAB = "selected_tab"
    }
}
