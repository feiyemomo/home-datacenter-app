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
    private var loadedBitmap: android.graphics.Bitmap? = null

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
                    loadedBitmap = bitmap
                    binding.ivSnapshot.setImageBitmap(bitmap)
                    if (alert.label.equals("person", ignoreCase = true)) {
                        binding.layoutSnapshotActions.visibility = View.VISIBLE
                        binding.btnRegisterFace.setOnClickListener {
                            showRegisterFaceDialog(bitmap)
                        }
                    }
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

    private fun showRegisterFaceDialog(bitmap: android.graphics.Bitmap) {
        val context = context ?: return
        val input = android.widget.EditText(context).apply {
            hint = getString(R.string.vision_hint_name)
            setSingleLine()
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        android.app.AlertDialog.Builder(context)
            .setTitle(R.string.vision_add_dialog_title)
            .setMessage("将当前告警检测到的人脸录入为家庭成员：")
            .setView(input)
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    registerFace(name, bitmap)
                } else {
                    android.widget.Toast.makeText(context, "姓名不能为空", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun registerFace(name: String, bitmap: android.graphics.Bitmap) {
        val app = activity?.application as? com.homedatacenter.app.HomeCenterApp ?: return
        val tok = token ?: app.container.prefsManager.token
        if (tok.isNullOrEmpty()) return
        val validToken: String = tok

        binding.btnRegisterFace.isEnabled = false
        binding.btnRegisterFace.text = getString(R.string.vision_registering)

        lifecycleScope.launch {
            try {
                val base64 = withContext(Dispatchers.IO) {
                    val stream = java.io.ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
                    android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
                }

                withContext(Dispatchers.IO) {
                    app.container.getRepository().registerVisionPerson(validToken, name, base64)
                }

                android.widget.Toast.makeText(
                    context,
                    "已成功录入家庭成员「$name」的人脸档案！",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                binding.btnRegisterFace.text = "已录入为 $name"
            } catch (e: Exception) {
                binding.btnRegisterFace.isEnabled = true
                binding.btnRegisterFace.text = getString(R.string.vision_register_from_alert)
                android.widget.Toast.makeText(
                    context,
                    "录入失败: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
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
