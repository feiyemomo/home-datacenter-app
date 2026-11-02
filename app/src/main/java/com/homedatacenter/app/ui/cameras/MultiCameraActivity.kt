package com.homedatacenter.app.ui.cameras

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.ui.glass.ProvideGlassTheme
import com.homedatacenter.app.util.ThemeManager

/**
 * MultiCameraActivity — 100% Jetpack Compose Multi-Camera Monitor Hall.
 * Eliminates legacy activity_multi_camera.xml.
 */
class MultiCameraActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as HomeCenterApp).container

        setContent {
            val preset by ThemeManager.themePresetFlow.collectAsState()
            ProvideGlassTheme(preset = preset) {
                MultiCameraPageContent(
                    container = container,
                    onBack = { finish() },
                    onToggleOrientation = { toggleOrientation() },
                    onOpenCameraDetail = { cam -> openCameraDetail(cam) }
                )
            }
        }
    }

    private fun toggleOrientation() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun openCameraDetail(camera: Camera) {
        val json = NetworkFactory.json.encodeToString(Camera.serializer(), camera)
        val intent = Intent(this, CameraDetailActivity::class.java).apply {
            putExtra(CameraDetailActivity.EXTRA_CAMERA_JSON, json)
        }
        startActivity(intent)
    }
}
