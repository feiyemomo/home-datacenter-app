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

        // Explicit close button only — do not dismiss on tapping snapshot image or card
        binding.btnCloseSnapshot.setOnClickListener { dismiss() }
        binding.btnSaveSnapshot.setOnClickListener { saveSnapshotToGallery() }

        // Wire pinch-to-zoom badge
        binding.zoomContainer.onZoomChanged = { scale ->
            if (scale > 1.01f) {
                binding.tvZoomBadge.visibility = View.VISIBLE
                binding.tvZoomBadge.text = String.format(Locale.US, "%.1fx 双击复位", scale)
            } else {
                binding.tvZoomBadge.visibility = View.GONE
            }
        }

        loadSnapshot()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
        }
        return dialog
    }

    private fun saveSnapshotToGallery() {
        val bitmap = loadedBitmap ?: run {
            android.widget.Toast.makeText(context, "图片尚未加载完成", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val ctx = context ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val filename = "Snapshot_${alert.cameraSlug}_${System.currentTimeMillis()}.jpg"
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/HomeSecurity")
                        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                        ctx.contentResolver.update(uri, values, null, null)
                    }
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, "抓拍大图已保存至系统相册！", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, "保存失败，无法创建文件", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(ctx, "保存失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadSnapshot() {
        val b = _binding ?: return
        val url = buildSnapshotUrl(alert.id)
        if (url.isEmpty()) {
            b.progressSnapshot.visibility = View.GONE
            b.tvSnapshotError.visibility = View.VISIBLE
            b.tvSnapshotError.text = getString(R.string.error)
            return
        }

        b.progressSnapshot.visibility = View.VISIBLE
        b.tvSnapshotError.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val client = okHttpClient ?: OkHttpClient()
                val req = Request.Builder().url(url).apply {
                    if (!token.isNullOrEmpty()) addHeader("Authorization", "Bearer $token")
                }.build()
                val bitmap = withContext(Dispatchers.IO) {
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        resp.body?.byteStream()?.use {
                            val opts = BitmapFactory.Options().apply {
                                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                                inScaled = false
                            }
                            BitmapFactory.decodeStream(it, null, opts)
                        }
                    }
                }
                val curBinding = _binding ?: return@launch
                if (bitmap != null) {
                    loadedBitmap = bitmap
                    curBinding.ivSnapshot.setImageBitmap(bitmap)
                    val app = activity?.application as? com.homedatacenter.app.HomeCenterApp
                    val isAdmin = app?.container?.let { it.roleManager.isAdmin() || it.prefsManager.isAdmin } == true
                    if (isAdmin && alert.label.equals("person", ignoreCase = true)) {
                        curBinding.layoutSnapshotActions.visibility = View.VISIBLE
                        curBinding.btnRegisterFace.setOnClickListener {
                            showRegisterFaceDialog(bitmap)
                        }
                    } else {
                        curBinding.layoutSnapshotActions.visibility = View.GONE
                    }
                } else {
                    curBinding.tvSnapshotError.visibility = View.VISIBLE
                    curBinding.tvSnapshotError.text = getString(R.string.weather_failed)
                }
            } catch (e: Exception) {
                _binding?.let { curBinding ->
                    curBinding.tvSnapshotError.visibility = View.VISIBLE
                    curBinding.tvSnapshotError.text = e.message ?: getString(R.string.error)
                }
            } finally {
                _binding?.progressSnapshot?.visibility = View.GONE
            }
        }
    }

    private fun showRegisterFaceDialog(bitmap: android.graphics.Bitmap) {
        val context = context ?: return
        val app = activity?.application as? com.homedatacenter.app.HomeCenterApp
        val isAdmin = app?.container?.let { it.roleManager.isAdmin() || it.prefsManager.isAdmin } == true
        if (!isAdmin) {
            android.widget.Toast.makeText(context, "只有管理员可以录入人脸档案", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

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
        val isAdmin = app.container.let { it.roleManager.isAdmin() || it.prefsManager.isAdmin }
        if (!isAdmin) {
            context?.let { android.widget.Toast.makeText(it, "只有管理员可以录入人脸档案", android.widget.Toast.LENGTH_SHORT).show() }
            return
        }

        val tok = token ?: app.container.prefsManager.token
        if (tok.isNullOrEmpty()) return
        val validToken: String = tok

        _binding?.let { b ->
            b.btnRegisterFace.isEnabled = false
            b.btnRegisterFace.text = getString(R.string.vision_registering)
        }

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

                context?.let { ctx ->
                    android.widget.Toast.makeText(
                        ctx,
                        "已成功录入家庭成员「$name」的人脸档案！",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                _binding?.btnRegisterFace?.text = "已录入为 $name"
            } catch (e: Exception) {
                _binding?.let { b ->
                    b.btnRegisterFace.isEnabled = true
                    b.btnRegisterFace.text = getString(R.string.vision_register_from_alert)
                }
                context?.let { ctx ->
                    android.widget.Toast.makeText(
                        ctx,
                        "录入失败: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun buildSnapshotUrl(alertId: String): String {
        if (baseUrl.isNullOrBlank()) return ""
        val base = if (baseUrl!!.endsWith("/")) baseUrl!! else "$baseUrl/"
        return "${base}api/v1/cameras/alerts/$alertId/snapshot?quality=100"
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
