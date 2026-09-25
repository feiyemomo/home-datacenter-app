package com.homedatacenter.app.ui.cameras

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.databinding.ActivityMultiCameraBinding
import com.homedatacenter.app.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * MultiCameraActivity — Dynamic Multi-Camera Monitor Hall (v1.13.0).
 * Supports 1-grid / 4-grid / 9-grid modes with adaptive substream resolution
 * (720p/360p/240p) and polling frequency to optimize bandwidth and rendering.
 */
class MultiCameraActivity : AppCompatActivity() {

    enum class GridMode(val slots: Int, val label: String, val width: Int, val quality: Int, val intervalMs: Long) {
        ONE(1, "1格", 720, 35, 300L),
        FOUR(4, "4格", 360, 25, 400L),
        NINE(9, "9格", 240, 18, 700L)
    }

    private lateinit var binding: ActivityMultiCameraBinding
    private lateinit var container: AppContainer
    private var allCameras: List<Camera> = emptyList()
    private var currentGridMode: GridMode = GridMode.FOUR
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMultiCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        container = (application as HomeCenterApp).container

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnToggleLandscape.setOnClickListener { toggleOrientation() }
        binding.btnFloatingExitLandscape.setOnClickListener { toggleOrientation() }

        binding.btnGridMode.setOnClickListener {
            cycleGridMode()
        }

        updateLayoutForOrientation(resources.configuration.orientation)
        applyGridModeLayout()

        binding.swipeRefresh.setOnRefreshListener { loadCameras() }

        loadCameras()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLayoutForOrientation(newConfig.orientation)
    }

    private fun cycleGridMode() {
        currentGridMode = when (currentGridMode) {
            GridMode.FOUR -> GridMode.NINE
            GridMode.NINE -> GridMode.ONE
            GridMode.ONE -> GridMode.FOUR
        }
        binding.btnGridMode.text = currentGridMode.label
        applyGridModeLayout()
        bindSlots()
        startFramePolling()
    }

    private fun toggleOrientation() {
        val currentOrientation = resources.configuration.orientation
        if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun updateLayoutForOrientation(orientation: Int) {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.toolbar.visibility = if (isLandscape) View.GONE else View.VISIBLE
        binding.btnFloatingExitLandscape.visibility = if (isLandscape) View.VISIBLE else View.GONE

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        val density = resources.displayMetrics.density
        if (isLandscape) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            binding.slotsContainer.setPadding(4, 4, 4, 4)
            updateSlotMargins((2 * density).toInt())
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            val pad = (6 * density).toInt()
            binding.slotsContainer.setPadding(pad, pad, pad, pad)
            updateSlotMargins((3 * density).toInt())
        }
    }

    private fun updateSlotMargins(margin: Int) {
        val allCards = listOf(
            binding.cardSlot0, binding.cardSlot1, binding.cardSlot2,
            binding.cardSlot3, binding.cardSlot4, binding.cardSlot5,
            binding.cardSlot6, binding.cardSlot7, binding.cardSlot8
        )
        for (card in allCards) {
            val lp = card.layoutParams as? LinearLayout.LayoutParams ?: continue
            lp.setMargins(margin, margin, margin, margin)
            card.layoutParams = lp
        }
    }

    private fun applyGridModeLayout() {
        when (currentGridMode) {
            GridMode.ONE -> {
                binding.row0.visibility = View.VISIBLE
                binding.row1.visibility = View.GONE
                binding.row2.visibility = View.GONE

                binding.cardSlot0.visibility = View.VISIBLE
                binding.cardSlot1.visibility = View.GONE
                binding.cardSlot2.visibility = View.GONE
            }
            GridMode.FOUR -> {
                binding.row0.visibility = View.VISIBLE
                binding.row1.visibility = View.VISIBLE
                binding.row2.visibility = View.GONE

                binding.cardSlot0.visibility = View.VISIBLE
                binding.cardSlot1.visibility = View.VISIBLE
                binding.cardSlot2.visibility = View.GONE

                binding.cardSlot3.visibility = View.VISIBLE
                binding.cardSlot4.visibility = View.VISIBLE
                binding.cardSlot5.visibility = View.GONE
            }
            GridMode.NINE -> {
                binding.row0.visibility = View.VISIBLE
                binding.row1.visibility = View.VISIBLE
                binding.row2.visibility = View.VISIBLE

                binding.cardSlot0.visibility = View.VISIBLE
                binding.cardSlot1.visibility = View.VISIBLE
                binding.cardSlot2.visibility = View.VISIBLE

                binding.cardSlot3.visibility = View.VISIBLE
                binding.cardSlot4.visibility = View.VISIBLE
                binding.cardSlot5.visibility = View.VISIBLE

                binding.cardSlot6.visibility = View.VISIBLE
                binding.cardSlot7.visibility = View.VISIBLE
                binding.cardSlot8.visibility = View.VISIBLE
            }
        }
    }

    private fun loadCameras() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                val token = container.prefsManager.token ?: ""
                if (token.isEmpty()) return@launch
                val list = withContext(Dispatchers.IO) {
                    container.getRepository().listCameras(token, useCache = false, refreshCache = true)
                }
                allCameras = list
                bindSlots()
                startFramePolling()
            } catch (e: Exception) {
                android.util.Log.e("MultiCameraActivity", "Failed to load cameras: ${e.message}", e)
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun getActiveSlots(): List<SlotViews> {
        val allSlots = listOf(
            SlotViews(0, binding.cardSlot0, binding.ivPreview0, binding.progress0, binding.tvEmpty0, binding.overlay0, binding.tvName0, binding.tvStatus0),
            SlotViews(1, binding.cardSlot1, binding.ivPreview1, binding.progress1, binding.tvEmpty1, binding.overlay1, binding.tvName1, binding.tvStatus1),
            SlotViews(2, binding.cardSlot2, binding.ivPreview2, binding.progress2, binding.tvEmpty2, binding.overlay2, binding.tvName2, binding.tvStatus2),
            SlotViews(3, binding.cardSlot3, binding.ivPreview3, binding.progress3, binding.tvEmpty3, binding.overlay3, binding.tvName3, binding.tvStatus3),
            SlotViews(4, binding.cardSlot4, binding.ivPreview4, binding.progress4, binding.tvEmpty4, binding.overlay4, binding.tvName4, binding.tvStatus4),
            SlotViews(5, binding.cardSlot5, binding.ivPreview5, binding.progress5, binding.tvEmpty5, binding.overlay5, binding.tvName5, binding.tvStatus5),
            SlotViews(6, binding.cardSlot6, binding.ivPreview6, binding.progress6, binding.tvEmpty6, binding.overlay6, binding.tvName6, binding.tvStatus6),
            SlotViews(7, binding.cardSlot7, binding.ivPreview7, binding.progress7, binding.tvEmpty7, binding.overlay7, binding.tvName7, binding.tvStatus7),
            SlotViews(8, binding.cardSlot8, binding.ivPreview8, binding.progress8, binding.tvEmpty8, binding.overlay8, binding.tvName8, binding.tvStatus8),
        )

        return when (currentGridMode) {
            GridMode.ONE -> listOf(allSlots[0])
            GridMode.FOUR -> listOf(allSlots[0], allSlots[1], allSlots[3], allSlots[4])
            GridMode.NINE -> allSlots
        }
    }

    private fun bindSlots() {
        val activeSlots = getActiveSlots()
        val hasNoCameras = allCameras.isEmpty()
        for (i in activeSlots.indices) {
            val s = activeSlots[i]
            val cam = allCameras.getOrNull(i)
            if (cam != null) {
                s.empty.visibility = View.GONE
                s.overlay.visibility = View.VISIBLE
                s.name.text = cam.name
                val isOnline = cam.status == "online"
                s.status.text = if (isOnline) "实时" else "离线"
                s.status.setTextColor(if (isOnline) 0xFF5CB880.toInt() else 0xFFE07070.toInt())

                s.card.setOnClickListener {
                    openCameraDetail(cam)
                }
            } else {
                s.empty.visibility = View.VISIBLE
                s.empty.text = if (hasNoCameras && i == 0) "暂无分配的摄像头\n(请联系管理员分享权限)" else "未配置"
                s.progress.visibility = View.GONE
                s.overlay.visibility = View.GONE
                s.preview.setImageDrawable(null)
                s.card.setOnClickListener(null)
            }
        }
    }

    private fun openCameraDetail(camera: Camera) {
        val json = NetworkFactory.json.encodeToString(Camera.serializer(), camera)
        val intent = Intent(this, CameraDetailActivity::class.java).apply {
            putExtra(CameraDetailActivity.EXTRA_CAMERA_JSON, json)
        }
        startActivity(intent)
    }

    private fun startFramePolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch(Dispatchers.IO) {
            val baseUrl = container.getApiBaseUrl().trimEnd('/')
            val token = container.prefsManager.token
            val client = container.okHttpClient

            val activeSlots = getActiveSlots()
            val mode = currentGridMode

            for (i in activeSlots.indices) {
                val cam = allCameras.getOrNull(i) ?: continue
                val slot = activeSlots[i]
                val iv = slot.preview
                val pb = slot.progress

                launch(Dispatchers.IO) {
                    delay(i * 80L)
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    }
                    while (isActive) {
                        try {
                            val ts = System.currentTimeMillis()
                            val frameUrl = "$baseUrl/api/v1/cameras/${cam.id}/frame?width=${mode.width}&quality=${mode.quality}&nocache=1&t=$ts"
                            val req = Request.Builder()
                                .url(frameUrl)
                                .apply {
                                    if (!token.isNullOrEmpty()) {
                                        header("Authorization", "Bearer $token")
                                        header("Cookie", "home_token=$token")
                                    }
                                }
                                .build()
                            client.newCall(req).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    resp.body?.byteStream()?.use { stream ->
                                        val bitmap = BitmapFactory.decodeStream(stream, null, opts)
                                        if (bitmap != null && isActive) {
                                            withContext(Dispatchers.Main) {
                                                iv.setImageBitmap(bitmap)
                                                pb.visibility = View.GONE
                                            }
                                        }
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        pb.visibility = View.GONE
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            withContext(Dispatchers.Main) {
                                pb.visibility = View.GONE
                            }
                        }
                        delay(mode.intervalMs)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (allCameras.isNotEmpty()) {
            startFramePolling()
        }
    }

    override fun onStop() {
        super.onStop()
        pollJob?.cancel()
        pollJob = null
    }

    private data class SlotViews(
        val index: Int,
        val card: MaterialCardView,
        val preview: ImageView,
        val progress: ProgressBar,
        val empty: TextView,
        val overlay: View,
        val name: TextView,
        val status: TextView,
    )
}
