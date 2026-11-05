package com.homedatacenter.app.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.homedatacenter.app.ui.glass.AppThemePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ThemeManager {

    private val _isDarkFlow = MutableStateFlow(false)
    val isDarkFlow: StateFlow<Boolean> = _isDarkFlow.asStateFlow()

    private val _themePresetFlow = MutableStateFlow(AppThemePreset.WarmLight)
    val themePresetFlow: StateFlow<AppThemePreset> = _themePresetFlow.asStateFlow()

    val currentPreset: AppThemePreset
        get() = _themePresetFlow.value

    fun applyTheme(mode: Int) {
        when (mode) {
            PrefsManager.THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            PrefsManager.THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            PrefsManager.THEME_FOLLOW_SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    /**
     * Theme switching: saves mode, updates reactive [isDarkFlow] and [themePresetFlow] StateFlows,
     * and synchronizes AppCompatDelegate so both Compose and legacy XML surfaces
     * immediately load the correct color qualifier.
     */
    fun setAppTheme(context: Context, mode: Int, recreateActivity: Boolean = true) {
        val prefs = PrefsManager(context)
        prefs.themeMode = mode
        val newDark = isDarkMode(context)
        _isDarkFlow.value = newDark

        // If current preset matches the target dark/light mode, keep it; otherwise fall back to default for mode
        val currentPreset = _themePresetFlow.value
        val updatedPreset = if (currentPreset.isDark == newDark) {
            currentPreset
        } else {
            AppThemePreset.fromIsDark(newDark)
        }
        _themePresetFlow.value = updatedPreset
        prefs.themePreset = updatedPreset.presetId

        applyTheme(mode)
    }

    /**
     * Sets a specific [AppThemePreset] directly, syncing mode and persisting preset ID.
     */
    fun setPreset(context: Context, preset: AppThemePreset) {
        val prefs = PrefsManager(context)
        prefs.themePreset = preset.presetId
        _themePresetFlow.value = preset
        _isDarkFlow.value = preset.isDark

        val mode = if (preset.isDark) PrefsManager.THEME_DARK else PrefsManager.THEME_LIGHT
        prefs.themeMode = mode
        applyTheme(mode)
    }

    fun init(context: Context) {
        val prefs = PrefsManager(context)
        val initialDark = isDarkMode(context)
        _isDarkFlow.value = initialDark

        val savedPresetId = prefs.themePreset
        val restoredPreset = if (!savedPresetId.isNullOrEmpty()) {
            val p = AppThemePreset.fromPresetId(savedPresetId)
            // If saved preset's dark status aligns with initialDark, respect it; otherwise fall back
            if (p.isDark == initialDark) p else AppThemePreset.fromIsDark(initialDark)
        } else {
            AppThemePreset.fromIsDark(initialDark)
        }
        _themePresetFlow.value = restoredPreset
        applyTheme(prefs.themeMode)
    }

    fun isDarkMode(context: Context): Boolean {
        return when (PrefsManager(context).themeMode) {
            PrefsManager.THEME_LIGHT -> false
            PrefsManager.THEME_DARK -> true
            else -> {
                val nightMode = context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }
}
