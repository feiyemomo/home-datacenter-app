package com.homedatacenter.app.ui.cameras

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
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
 *
 * v1.13.3:
 * - Double-tap any slot to zoom in/focus to 1-grid; double-tap in 1-grid to restore previous grid mode.
 * - Toggle aspect ratio between Fit (original 16:9) and Fill (center-crop).
 */
class MultiCameraActivity : AppCompatActivity() {

    enum class GridMode(val slots: Int, val label: String, val width: Int, val quality: Int, val intervalMs: Long) {
        ONE(1, "1格", 720, 35, 100L),
        FOUR(4, "4格", 360, 25, 200L),
        NINE(9, "9格", 240, 18, 400L)
    }

    private lateinit var binding: ActivityMultiCameraBinding
    private lateinit var container: AppContainer
    private var allCameras: List<Camera> = emptyList()
    private val slotMapping = IntArray(9) { it }
    private var currentGridMode: GridMode = GridMode.FOUR
    private var previousGridMode: GridMode = GridMode.FOUR
    private var focusedCameraIndex: Int = 0
    private var isFillCrop: Boolean = false
    private var pollJob: Job? = null

    /** Last height applied to the grid rows, to avoid layout churn. */
    private var lastRowHeightPx: Int = -1

    /** True while the rows are stretched to fill (single-camera mode). */
    private var rowsFill: Boolean = true

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
        binding.btnAspectRatio.setOnClickListener { toggleAspectRatio() }
        binding.btnFloatingAspectRatio.setOnClickListener { toggleAspectRatio() }

        binding.btnPrevCamera.setOnClickListener { switchToPrevCamera() }
        binding.btnNextCamera.setOnClickListener { switchToNextCamera() }
        binding.btnCameraSwitchPill.setOnClickListener { showSelectCameraDialogForOneGrid() }
        binding.btnFloatingSwitchCamera.setOnClickListener { showSelectCameraDialogForOneGrid() }

        updateLayoutForOrientation(resources.configuration.orientation)
        applyGridModeLayout()
        applyAspectRatio()

        // v1.14.9: size each visible grid row to the video aspect
        // ratio instead of stretching it to fill the screen height.
        // The rows used to be weight=1 inside a match_parent
        // container, so on a portrait phone a 2x2 grid produced
        // ~540x950 tiles holding a 16:9 stream: the video was
        // letterboxed inside huge black bars. Recompute whenever the
        // container is (re)measured, e.g. on rotation.
        binding.slotsContainer.addOnLayoutChangeListener { _, left, top, right, bottom, oLeft, oTop, oRight, oBottom ->
            if (right - left != oRight - oLeft || bottom - top != oBottom - oTop) {
                applyGridRowHeights()
            }
        }
        binding.slotsContainer.post { applyGridRowHeights() }

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
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.btnFloatingSwitchCamera.visibility =
            if (isLandscape && currentGridMode == GridMode.ONE && allCameras.isNotEmpty()) View.VISIBLE else View.GONE
        applyGridModeLayout()
        applyGridRowHeights()
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
        binding.layoutFloatingControls.visibility = if (isLandscape) View.VISIBLE else View.GONE
        binding.btnFloatingSwitchCamera.visibility =
            if (isLandscape && currentGridMode == GridMode.ONE && allCameras.isNotEmpty()) View.VISIBLE else View.GONE

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        val density = resources.displayMetrics.density
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = if (isLandscape) {
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            window.attributes = lp
        }
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
        // Padding/margins just changed, so the aspect-derived row
        // height is stale — recompute after the next measure pass.
        binding.slotsContainer.post { applyGridRowHeights() }
    }

    /**
     * v1.14.9: give every grid row the height its tiles need for a
     * 16:9 stream (tileWidth * 9 / 16) and centre the grid vertically,
     * instead of letting weight=1 stretch the rows to fill the screen.
     * In landscape this works out to a full-bleed grid; in portrait it
     * yields a compact, correctly proportioned grid with no giant
     * letterbox bars.
     */
    private fun applyGridRowHeights() {
        val container = binding.slotsContainer
        val rows = listOf(binding.row0, binding.row1, binding.row2)

        // The focused single-camera view deliberately fills the whole
        // screen: the 适应/填充 (fit / fill) toggle only has a visible
        // effect when the container is taller than the 16:9 stream.
        // The same is true of 铺满 (centre-crop) in the tile grids, so
        // fill mode keeps the stretched rows there too.
        if (isFillCrop || currentGridMode == GridMode.ONE) {
            if (rowsFill) return
            rowsFill = true
            lastRowHeightPx = -1
            for (row in rows) {
                val lp = row.layoutParams as? LinearLayout.LayoutParams ?: continue
                if (lp.height == 0 && lp.weight == 1f) continue
                lp.height = 0
                lp.weight = 1f
                row.layoutParams = lp
            }
            container.gravity = Gravity.TOP
            return
        }

        val columns = if (currentGridMode == GridMode.FOUR) 2 else 3
        val availableWidth = container.width - container.paddingLeft - container.paddingRight
        if (availableWidth <= 0) return

        val density = resources.displayMetrics.density
        val cardMarginPx = (if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            2 * density
        } else {
            3 * density
        }).toInt()
        // Each card carries a margin on both sides, so the usable
        // tile width shrinks by two margins.
        val tileWidth = availableWidth / columns - 2 * cardMarginPx
        if (tileWidth <= 0) return

        val rowHeight = (tileWidth * 9f / 16f).toInt()
        if (!rowsFill && rowHeight == lastRowHeightPx) return
        rowsFill = false
        lastRowHeightPx = rowHeight

        for (row in rows) {
            val lp = row.layoutParams as? LinearLayout.LayoutParams ?: continue
            if (lp.height == rowHeight && lp.weight == 0f) continue
            lp.height = rowHeight
            lp.weight = 0f
            row.layoutParams = lp
        }
        container.gravity = Gravity.CENTER_VERTICAL
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
                for (idx in 0 until 9) {
                    slotMapping[idx] = if (idx < list.size) idx else -1
                }
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
        val isOneGrid = currentGridMode == GridMode.ONE

        binding.layoutOneGridNav.visibility = if (isOneGrid && allCameras.isNotEmpty()) View.VISIBLE else View.GONE
        if (isOneGrid && allCameras.isNotEmpty()) {
            binding.layoutOneGridNav.bringToFront()
            val cam = allCameras.getOrNull(focusedCameraIndex)
            val name = cam?.name ?: "摄像头"
            binding.tvOneGridCameraName.text = "$name (${focusedCameraIndex + 1}/${allCameras.size})"
            binding.btnPrevCamera.visibility = if (allCameras.size > 1) View.VISIBLE else View.GONE
            binding.btnNextCamera.visibility = if (allCameras.size > 1) View.VISIBLE else View.GONE
        }

        for (i in activeSlots.indices) {
            val s = activeSlots[i]
            val camIndex = if (isOneGrid) focusedCameraIndex else slotMapping.getOrElse(i) { -1 }
            val cam = if (camIndex >= 0) allCameras.getOrNull(camIndex) else null
            if (cam != null) {
                s.empty.visibility = View.GONE
                s.overlay.visibility = View.VISIBLE
                s.name.text = cam.name
                val isOnline = cam.status == "online"
                s.status.text = if (isOnline) "实时" else "离线"
                s.status.setTextColor(if (isOnline) 0xFF5CB880.toInt() else 0xFFE07070.toInt())

                val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        openCameraDetail(cam)
                        return true
                    }
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        toggleFocusSlot(camIndex)
                        return true
                    }
                    override fun onFling(
                        e1: MotionEvent?,
                        e2: MotionEvent,
                        velocityX: Float,
                        velocityY: Float
                    ): Boolean {
                        if (e1 == null) return false
                        val diffX = e2.x - e1.x
                        val diffY = e2.y - e1.y
                        if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > 80 && Math.abs(velocityX) > 80) {
                            if (currentGridMode == GridMode.ONE && allCameras.size > 1) {
                                if (diffX < 0) {
                                    switchToNextCamera()
                                } else {
                                    switchToPrevCamera()
                                }
                                return true
                            }
                        }
                        return false
                    }
                    override fun onLongPress(e: MotionEvent) {
                        if (currentGridMode == GridMode.ONE) {
                            showSelectCameraDialogForOneGrid()
                        } else {
                            showSelectCameraDialogForSlot(i)
                        }
                    }
                })
                s.card.setOnTouchListener { _, event ->
                    gestureDetector.onTouchEvent(event)
                    true
                }
                s.overlay.setOnClickListener {
                    if (currentGridMode == GridMode.ONE) {
                        showSelectCameraDialogForOneGrid()
                    } else {
                        showSelectCameraDialogForSlot(i)
                    }
                }
            } else {
                s.empty.visibility = View.VISIBLE
                s.empty.text = if (hasNoCameras && i == 0) "暂无分配的摄像头\n(请联系管理员分享权限)" else "未配置 · 点击选择"
                s.progress.visibility = View.GONE
                s.overlay.visibility = View.GONE
                s.preview.setImageDrawable(null)
                s.card.setOnTouchListener(null)
                s.card.setOnClickListener {
                    showSelectCameraDialogForSlot(i)
                }
            }
        }
    }

    private fun switchToNextCamera() {
        if (allCameras.isEmpty()) return
        focusedCameraIndex = (focusedCameraIndex + 1) % allCameras.size
        bindSlots()
        startFramePolling()
    }

    private fun switchToPrevCamera() {
        if (allCameras.isEmpty()) return
        focusedCameraIndex = (focusedCameraIndex - 1 + allCameras.size) % allCameras.size
        bindSlots()
        startFramePolling()
    }

    private fun showSelectCameraDialogForOneGrid() {
        if (allCameras.isEmpty()) return
        val names = allCameras.map { it.name }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("切换摄像头")
            .setSingleChoiceItems(names, focusedCameraIndex.coerceIn(0, names.size - 1)) { d, which ->
                focusedCameraIndex = which
                d.dismiss()
                bindSlots()
                startFramePolling()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSelectCameraDialogForSlot(slotIndex: Int) {
        if (allCameras.isEmpty()) return
        val currentCamIdx = slotMapping.getOrElse(slotIndex) { 0 }
        val names = allCameras.map { it.name }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("为窗口 ${slotIndex + 1} 选择摄像头")
            .setSingleChoiceItems(names, currentCamIdx.coerceIn(0, names.size - 1)) { d, which ->
                slotMapping[slotIndex] = which
                d.dismiss()
                bindSlots()
                startFramePolling()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleFocusSlot(camIndex: Int) {
        if (currentGridMode != GridMode.ONE) {
            previousGridMode = currentGridMode
            focusedCameraIndex = camIndex.coerceIn(0, maxOf(0, allCameras.size - 1))
            currentGridMode = GridMode.ONE
        } else {
            currentGridMode = previousGridMode
        }
        binding.btnGridMode.text = currentGridMode.label
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.btnFloatingSwitchCamera.visibility =
            if (isLandscape && currentGridMode == GridMode.ONE && allCameras.isNotEmpty()) View.VISIBLE else View.GONE
        applyGridModeLayout()
        applyGridRowHeights()
        bindSlots()
        startFramePolling()
    }

    private fun toggleAspectRatio() {
        isFillCrop = !isFillCrop
        applyAspectRatio()
        // 铺满 (centre-crop) only has anything to crop when the tile is
        // taller than 16:9, so the rows go back to stretching.
        binding.slotsContainer.post { applyGridRowHeights() }
    }

    private fun applyAspectRatio() {
        val scaleType = if (isFillCrop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        val allPreviews = listOf(
            binding.ivPreview0, binding.ivPreview1, binding.ivPreview2,
            binding.ivPreview3, binding.ivPreview4, binding.ivPreview5,
            binding.ivPreview6, binding.ivPreview7, binding.ivPreview8
        )
        allPreviews.forEach { it.scaleType = scaleType }
        val label = if (isFillCrop) "铺满" else "适应"
        binding.btnAspectRatio.text = label
        binding.btnFloatingAspectRatio.text = label
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
                val camIndex = if (mode == GridMode.ONE) focusedCameraIndex else slotMapping.getOrElse(i) { -1 }
                if (camIndex < 0) continue
                val cam = allCameras.getOrNull(camIndex) ?: continue
                val slot = activeSlots[i]
                val iv = slot.preview
                val pb = slot.progress

                launch(Dispatchers.IO) {
                    delay(i * 80L)
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                        inMutable = true
                    }
                    var reusedBitmap: android.graphics.Bitmap? = null
                    while (isActive) {
                        try {
                            val activeBaseUrl = container.getApiBaseUrl().trimEnd('/')
                            val ts = System.currentTimeMillis()
                            val frameUrl = "$activeBaseUrl/api/v1/cameras/${cam.id}/frame?width=${mode.width}&quality=${mode.quality}&nocache=1&t=$ts"
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
                                        val reusable = reusedBitmap
                                        opts.inBitmap = if (reusable != null && !reusable.isRecycled) reusable else null
                                        val bitmap = try {
                                            BitmapFactory.decodeStream(stream, null, opts)
                                        } catch (_: IllegalArgumentException) {
                                            opts.inBitmap = null
                                            BitmapFactory.decodeStream(stream, null, opts)
                                        }
                                        if (bitmap != null && isActive) {
                                            reusedBitmap = bitmap
                                            withContext(Dispatchers.Main) {
                                                iv.setImageBitmap(bitmap)
                                                pb.visibility = View.GONE
                                                slot.status.text = getString(R.string.camera_online)
                                                slot.status.setTextColor(getColor(R.color.online))
                                            }
                                        }
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        pb.visibility = View.GONE
                                        slot.status.text = getString(R.string.camera_offline)
                                        slot.status.setTextColor(getColor(R.color.offline))
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            withContext(Dispatchers.Main) {
                                pb.visibility = View.GONE
                                slot.status.text = getString(R.string.camera_offline)
                                slot.status.setTextColor(getColor(R.color.offline))
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
