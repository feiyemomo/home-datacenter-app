package com.homedatacenter.app.ui.cameras

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.homedatacenter.app.HomeCenterApp
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
 * MultiCameraActivity — 2x2 split-screen live monitor hall (v1.10.9).
 * Displays up to 4 cameras simultaneously with periodic live JPEG frames
 * and smooth transitions. Tapping any camera cell opens CameraDetailActivity.
 */
class MultiCameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMultiCameraBinding
    private lateinit var container: AppContainer
    private var cameras: List<Camera> = emptyList()
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMultiCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        container = (application as HomeCenterApp).container

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.swipeRefresh.setOnRefreshListener { loadCameras() }

        loadCameras()
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
                cameras = list.take(4)
                bindSlots()
                startFramePolling()
            } catch (e: Exception) {
                android.util.Log.e("MultiCameraActivity", "Failed to load cameras: ${e.message}", e)
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun bindSlots() {
        val slots = listOf(
            SlotViews(binding.cardSlot0, binding.ivPreview0, binding.progress0, binding.tvEmpty0, binding.overlay0, binding.tvName0, binding.tvStatus0),
            SlotViews(binding.cardSlot1, binding.ivPreview1, binding.progress1, binding.tvEmpty1, binding.overlay1, binding.tvName1, binding.tvStatus1),
            SlotViews(binding.cardSlot2, binding.ivPreview2, binding.progress2, binding.tvEmpty2, binding.overlay2, binding.tvName2, binding.tvStatus2),
            SlotViews(binding.cardSlot3, binding.ivPreview3, binding.progress3, binding.tvEmpty3, binding.overlay3, binding.tvName3, binding.tvStatus3),
        )

        for (i in slots.indices) {
            val s = slots[i]
            val cam = cameras.getOrNull(i)
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

            val previews = listOf(binding.ivPreview0, binding.ivPreview1, binding.ivPreview2, binding.ivPreview3)

            while (isActive) {
                for (i in cameras.indices) {
                    if (!isActive) break
                    val cam = cameras[i]
                    val iv = previews[i]
                    try {
                        val frameUrl = "$baseUrl/api/v1/cameras/${cam.id}/frame?width=320&quality=20"
                        val reqBuilder = Request.Builder().url(frameUrl)
                        if (!token.isNullOrEmpty()) {
                            reqBuilder.header("Authorization", "Bearer $token")
                            reqBuilder.header("Cookie", "home_token=$token")
                        }
                        val resp = client.newCall(reqBuilder.build()).execute()
                        if (resp.isSuccessful) {
                            resp.body?.byteStream()?.use { stream ->
                                val bitmap = BitmapFactory.decodeStream(stream)
                                if (bitmap != null) {
                                    withContext(Dispatchers.Main) {
                                        iv.setImageBitmap(bitmap)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // Suppress single frame failures
                    }
                }
                delay(2500)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (cameras.isNotEmpty()) {
            startFramePolling()
        }
    }

    override fun onStop() {
        super.onStop()
        pollJob?.cancel()
        pollJob = null
    }

    private data class SlotViews(
        val card: MaterialCardView,
        val preview: ImageView,
        val progress: ProgressBar,
        val empty: TextView,
        val overlay: View,
        val name: TextView,
        val status: TextView,
    )
}
