package com.homedatacenter.app

import android.app.Application
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.util.PrefsManager
import com.homedatacenter.app.util.ThemeManager

class HomeCenterApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        ThemeManager.init(this)
        container = AppContainer(this)
    }
}
