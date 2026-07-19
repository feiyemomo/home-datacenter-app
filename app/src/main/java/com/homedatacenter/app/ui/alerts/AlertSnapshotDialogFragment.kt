package com.homedatacenter.app.ui.alerts

import android.app.Dialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.Alert
import com.homedatacenter.app.databinding.DialogAlertSnapshotBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-resolution snapshot modal — mirrors the web's snapshot modal.
 *
 * Shows the alert label, confidence, camera name, zones, time, and the
 * full-resolution snapshot image (loaded from
 * `GET /api/v1/cameras/alerts/:id/snapshot`).
 *
 * Tap outside or the close button to dismiss.
 */
class AlertSnapshotDialogFragment : DialogFragment() {

    private var _binding: DialogAlertSnapshotBinding? = null
    private val binding get() = _binding!!

    private lateinit var alert: Alert
    private var baseUrl: String? = null
    private var token: String? = null
    private var okHttpClient: OkHttpClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.SnapshotDialogTheme)
        isCancelable = true
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAlertSnapshotBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvSnapshotLabel.text = formatLabel(alert.label)
        binding.chipSnapshotConfidence.text = "${(alert.confidence * 100).toInt()}%"

        val camera = alert.cameraName.ifEmpty {
            alert.cameraSlug.ifEmpty { getString(R.string.live_detection_camera_unknown) }
        }
        val zones = if (alert.zones.isNotEmpty()) alert.zones.joinToString(", ") else "—"
        val date = Date((alert.startTime * 1000).toLong())
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(date)
        binding.tvSnapshotMeta.text = "$camera · $zones · $timeStr"

        binding.btnCloseSnapshot.setOnClickListener { dismiss() }
        binding.root.setOnClickListener { dismiss() }
        // Prevent clicks inside the inner card from dismissing the dialog
        binding.root.findViewById<View>(R.id.btnCloseSnapshot)?.let { /* already wired */ }

        loadSnapshot()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    private fun loadSnapshot() {
        val url = buildSnapshotUrl(alert.id)
        if (url.isEmpty()) {
            binding.progressSnapshot.visibility = View.GONE
            binding.tvSnapshotError.visibility = View.VISIBLE
            binding.tvSnapshotError.text = getString(R.string.error)
            return
        }

        binding.progressSnapshot.visibility = View.VISIBLE
        binding.tvSnapshotError.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val client = okHttpClient ?: OkHttpClient()
                val req = Request.Builder().url(url).apply {
                    if (!token.isNullOrEmpty()) addHeader("Authorization", "Bearer $token")
                }.build()
                val bitmap = withContext(Dispatchers.IO) {
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        resp.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                    }
                }
                if (bitmap != null) {
                    binding.ivSnapshot.setImageBitmap(bitmap)
                } else {
                    binding.tvSnapshotError.visibility = View.VISIBLE
                    binding.tvSnapshotError.text = getString(R.string.weather_failed)
                }
            } catch (e: Exception) {
                binding.tvSnapshotError.visibility = View.VISIBLE
                binding.tvSnapshotError.text = e.message ?: getString(R.string.error)
            } finally {
                binding.progressSnapshot.visibility = View.GONE
            }
        }
    }

    private fun buildSnapshotUrl(alertId: String): String {
        if (baseUrl.isNullOrBlank()) return ""
        val base = if (baseUrl!!.endsWith("/")) baseUrl!! else "$baseUrl/"
        return "${base}api/v1/cameras/alerts/$alertId/snapshot"
    }

    private fun formatLabel(label: String): String {
        val res = when (label.lowercase(Locale.getDefault())) {
            "person" -> R.string.detection_label_person
            "car" -> R.string.detection_label_car
            "truck" -> R.string.detection_label_truck
            "bus" -> R.string.detection_label_bus
            "bicycle" -> R.string.detection_label_bicycle
            "motorcycle" -> R.string.detection_label_motorcycle
            "dog" -> R.string.detection_label_dog
            "cat" -> R.string.detection_label_cat
            "bird" -> R.string.detection_label_bird
            else -> 0
        }
        return if (res != 0) getString(res) else label
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AlertSnapshotDialog"

        fun newInstance(
            alert: Alert,
            baseUrl: String?,
            token: String?,
            okHttpClient: OkHttpClient?
        ): AlertSnapshotDialogFragment {
            return AlertSnapshotDialogFragment().apply {
                this.alert = alert
                this.baseUrl = baseUrl
                this.token = token
                this.okHttpClient = okHttpClient
            }
        }
    }
}
