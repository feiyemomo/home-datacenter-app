package com.homedatacenter.app.ui.cameras

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.api.NetworkFactory
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.data.model.CameraPreset
import com.homedatacenter.app.databinding.ActivityCameraDetailBinding
import com.homedatacenter.app.di.AppContainer
import kotlinx.coroutines.launch

/**
 * Camera control screen: PTZ, presets, codec, recording plan, audio
 * toggle, delete camera. Reachable from a [CameraCard] settings gear.
 *
 * Role-based visibility:
 *  - Non-admin users can read the camera info and presets list, and
 *    trigger PTZ movements (PTZ is admin-gated on the server side, so
 *    a non-admin pressing the buttons will receive 403 — we hide the
 *    buttons client-side to avoid confusion, but server enforcement
 *    remains authoritative).
 *  - Admin users get the full control surface: audio/recording/codec
 *    toggles, preset add/delete, delete camera.
 *
 * The activity reads the initial camera from [EXTRA_CAMERA_JSON] (the
 * cached Camera object serialized as JSON). Mutations call the
 * repository and re-fetch the camera to refresh the UI rather than
 * mutating local state blindly — that way the displayed state always
 * matches what the server persisted.
 */
class CameraDetailActivity : AppCompatActivity() {

    private lateinit var _binding: ActivityCameraDetailBinding
    private val binding get() = _binding
    private lateinit var container: AppContainer

    private var camera: Camera? = null
    private var isAdmin: Boolean = false
    private var presets: List<CameraPreset> = emptyList()
    private lateinit var presetAdapter: PresetListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityCameraDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        container = (application as HomeCenterApp).container
        isAdmin = container.prefsManager.isAdmin

        binding.toolbar.setNavigationOnClickListener { finish() }

        // Deserialize the camera passed in via Intent extra.
        val cameraJson = intent.getStringExtra(EXTRA_CAMERA_JSON)
        if (cameraJson.isNullOrEmpty()) {
            finish()
            return
        }
        camera = try {
            NetworkFactory.json.decodeFromString(Camera.serializer(), cameraJson)
        } catch (_: Exception) {
            null
        }
        if (camera == null) {
            finish()
            return
        }

        setupHeader()
        setupPtz()
        setupPresets()
        setupSettings()

        loadPresets()
    }

    private fun setupHeader() {
        val cam = camera ?: return
        binding.tvCameraName.text = cam.name
        binding.tvCameraMeta.text = buildString {
            if (cam.vendor.isNotBlank()) append(cam.vendor)
            if (cam.host.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(cam.host)
            }
            if (cam.codec.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append("codec=").append(cam.codec.uppercase())
            }
        }
        binding.tvCameraStatus.text = if (cam.isOnline) {
            getString(R.string.camera_online)
        } else {
            getString(R.string.camera_offline)
        }
        binding.tvCameraStatus.setTextColor(
            getColor(if (cam.isOnline) R.color.online else R.color.offline)
        )
    }

    private fun setupPtz() {
        val cam = camera ?: return
        if (!cam.hasPtz) {
            binding.tvPtzUnsupported.visibility = View.VISIBLE
            binding.ptzGrid.visibility = View.GONE
            binding.seekPtzSpeed.isEnabled = false
            return
        }
        binding.tvPtzUnsupported.visibility = View.GONE
        binding.ptzGrid.visibility = View.VISIBLE

        val buttons = mapOf(
            binding.btnPtzUp to "up",
            binding.btnPtzDown to "down",
            binding.btnPtzLeft to "left",
            binding.btnPtzRight to "right",
            binding.btnPtzStop to "stop",
        )
        buttons.forEach { (btn, command) ->
            // PTZ is admin-gated on the server; hide for non-admin to
            // avoid confusion. Server remains authoritative.
            btn.visibility = if (isAdmin) View.VISIBLE else View.GONE
            btn.setOnClickListener { sendPtz(command) }
        }
        if (!isAdmin) {
            binding.tvPtzUnsupported.visibility = View.VISIBLE
            binding.tvPtzUnsupported.text = getString(R.string.camera_admin_required)
            binding.ptzGrid.visibility = View.GONE
        }

        binding.seekPtzSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = progress / 100.0
                binding.tvPtzSpeed.text = String.format("%.2f", speed)
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })
    }

    private fun setupPresets() {
        presetAdapter = PresetListAdapter(
            isAdmin = isAdmin,
            onGoto = { preset -> gotoPreset(preset) },
            onDelete = { preset -> deletePreset(preset) },
        )
        binding.rvPresets.layoutManager = LinearLayoutManager(this)
        binding.rvPresets.adapter = presetAdapter

        binding.btnAddPreset.visibility = if (isAdmin) View.VISIBLE else View.GONE
        binding.btnAddPreset.setOnClickListener { showAddPresetDialog() }
    }

    private fun setupSettings() {
        val cam = camera ?: return

        // Audio switch — initial state from camera capabilities.
        binding.switchAudio.isChecked = cam.hasAudio
        binding.switchAudio.isEnabled = isAdmin
        binding.switchAudio.setOnCheckedChangeListener { _, isChecked ->
            if (!isAdmin) {
                binding.switchAudio.isChecked = cam.hasAudio
                toast(R.string.camera_admin_required)
                return@setOnCheckedChangeListener
            }
            updateAudio(isChecked)
        }

        // Recording switch — Frigate continuous recording.
        // The camera JSON may not carry the recording plan flag; we
        // assume disabled until backend exposes it in the Camera view.
        binding.switchRecording.isChecked = false
        binding.switchRecording.isEnabled = isAdmin
        binding.switchRecording.setOnCheckedChangeListener { _, isChecked ->
            if (!isAdmin) {
                binding.switchRecording.isChecked = false
                toast(R.string.camera_admin_required)
                return@setOnCheckedChangeListener
            }
            setRecordingPlan(isChecked)
        }

        // Codec button — only H264 is supported via this API.
        binding.btnCodecH264.visibility = if (isAdmin) View.VISIBLE else View.GONE
        binding.btnCodecH264.setOnClickListener { updateCodec() }

        // Delete camera — admin only.
        binding.btnDelete.visibility = if (isAdmin) View.VISIBLE else View.GONE
        binding.btnDelete.setOnClickListener { confirmDeleteCamera() }
    }

    // --- API actions ---

    private fun sendPtz(command: String) {
        val cam = camera ?: return
        val token = container.prefsManager.token ?: return
        val speed = (binding.seekPtzSpeed.progress / 100.0).coerceIn(0.1, 1.0)
        lifecycleScope.launch {
            try {
                container.getRepository().moveCamera(token, cam.id, command, speed)
                toast("PTZ: $command")
            } catch (e: Exception) {
                toast("PTZ 失败: ${e.message}")
            }
        }
    }

    private fun loadPresets() {
        val cam = camera ?: return
        val token = container.prefsManager.token ?: return
        // /presets/discover is JWT-only (read access) — non-admin can
        // list presets but not modify them.
        lifecycleScope.launch {
            try {
                presets = container.getRepository().listCameraPresets(token, cam.id)
                presetAdapter.submit(presets)
                binding.tvPresetsEmpty.visibility =
                    if (presets.isEmpty()) View.VISIBLE else View.GONE
            } catch (_: Exception) {
                binding.tvPresetsEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun gotoPreset(preset: CameraPreset) {
        val cam = camera ?: return
        val token = container.prefsManager.token ?: return
        lifecycleScope.launch {
            try {
                // Use the preset's name as the alias if the user has
                // set one; otherwise fall back to the token (which
                // won't match any alias and will 404). In practice
                // gotoCameraPreset only works on aliases set via
                // PUT /presets/{alias}.
                val alias = preset.name.ifBlank { preset.token }
                container.getRepository().gotoCameraPreset(token, cam.id, alias)
                toast("前往: ${preset.name.ifBlank { preset.token }}")
            } catch (e: Exception) {
                toast("前往预设失败: ${e.message}")
            }
        }
    }

    private fun deletePreset(preset: CameraPreset) {
        val cam = camera ?: return
        val token = container.prefsManager.token ?: return
        val alias = preset.name.ifBlank { return }
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_logout_title)
            .setMessage("删除预设位 \"$alias\"？")
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                lifecycleScope.launch {
                    try {
                        container.getRepository().deleteCameraPreset(token, cam.id, alias)
                        toast("已删除")
                        loadPresets()
                    } catch (e: Exception) {
                        toast("删除失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showAddPresetDialog() {
        val cam = camera ?: return
        val token = container.prefsManager.token ?: return

        val dialogContainer = LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }
        val aliasEdit = EditText(this).apply { hint = getString(R.string.camera_preset_alias) }
        val tokenEdit = EditText(this).apply { hint = getString(R.string.camera_preset_token) }
        dialogContainer.addView(aliasEdit)
        dialogContainer.addView(tokenEdit)

        AlertDialog.Builder(this)
            .setTitle(R.string.camera_preset_add)
            .setView(dialogContainer)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val alias = aliasEdit.text.toString().trim()
                val tokenStr = tokenEdit.text.toString().trim()
                if (alias.isEmpty() || tokenStr.isEmpty()) {
                    toast("别名和 token 都不能为空")
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        this@CameraDetailActivity.container.getRepository()
                            .setCameraPreset(token, cam.id, alias, tokenStr)
                        toast("已添加")
                        loadPresets()
                    } catch (e: Exception) {
                        toast("添加失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun updateAudio(enabled: Boolean) {
        val cam = camera ?: return
        val token = container.prefsManager.token ?: return
        lifecycleScope.launch {
            try {
                container.getRepository().updateCameraAudio(token, cam.id, enabled)
                toast(if (enabled) "音频已启用" else "音频已禁用")
                refreshCamera()
            } catch (e: Exception) {
                toast("音频切换失败: ${e.message}")
                binding.switchAudio.isChecked = !enabled
            }
        }
    }

    private fun setRecordingPlan(enabled: Boolean) {
        val cam = camera ?: return
        val token = container.prefsManager.token ?: return
        lifecycleScope.launch {
            try {
                container.getRepository().setRecordingPlan(token, cam.id, enabled)
                toast(if (enabled) "已开启持续录像" else "已关闭持续录像")
            } catch (e: Exception) {
                toast("录像设置失败: ${e.message}")
                binding.switchRecording.isChecked = !enabled
            }
        }
    }

    private fun updateCodec() {
        val cam = camera ?: return
        val token = container.prefsManager.token ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.camera_codec_section)
            .setMessage(getString(R.string.camera_codec_h264))
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                lifecycleScope.launch {
                    try {
                        container.getRepository().updateCameraCodec(token, cam.id, "h264")
                        toast("已切换至 H264")
                        refreshCamera()
                    } catch (e: Exception) {
                        toast("切换失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun confirmDeleteCamera() {
        val cam = camera ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.camera_delete)
            .setMessage(R.string.camera_delete_confirm)
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                val token = container.prefsManager.token ?: return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        container.getRepository().deleteCamera(token, cam.id)
                        toast("已删除")
                        finish()
                    } catch (e: Exception) {
                        toast("删除失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun refreshCamera() {
        val cam = camera ?: return
        val token = container.prefsManager.token ?: return
        lifecycleScope.launch {
            try {
                val fresh = container.getRepository().getCamera(token, cam.id)
                camera = fresh
                setupHeader()
                binding.switchAudio.isChecked = fresh.hasAudio
            } catch (_: Exception) {
                // Network failure: keep showing the old state.
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    // --- Preset list adapter (inline) ---

    private class PresetListAdapter(
        private val isAdmin: Boolean,
        private val onGoto: (CameraPreset) -> Unit,
        private val onDelete: (CameraPreset) -> Unit,
    ) : RecyclerView.Adapter<PresetListAdapter.VH>() {

        private val items = mutableListOf<CameraPreset>()

        fun submit(list: List<CameraPreset>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                setPadding(32, 32, 32, 32)
                textSize = 14f
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val preset = items[position]
            val tv = (holder.itemView as TextView)
            tv.text = buildString {
                append(preset.name.ifBlank { preset.token })
                if (preset.name.isNotBlank()) {
                    append("  (").append(preset.token).append(")")
                }
            }
            tv.setOnClickListener { onGoto(preset) }
            tv.setOnLongClickListener {
                if (isAdmin) {
                    onDelete(preset)
                    true
                } else false
            }
        }

        override fun getItemCount(): Int = items.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView)
    }

    companion object {
        const val EXTRA_CAMERA_JSON = "camera_json"
    }
}
