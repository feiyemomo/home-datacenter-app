package com.homedatacenter.app.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {

    fun applyTheme(mode: Int) {
        when (mode) {
            PrefsManager.THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            PrefsManager.THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            PrefsManager.THEME_FOLLOW_SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    fun init(context: Context) {
        val prefs = PrefsManager(context)
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
