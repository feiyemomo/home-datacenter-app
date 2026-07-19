package com.homedatacenter.app.ui.cameras

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.homedatacenter.app.R
import com.homedatacenter.app.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Admin-only dialog that wraps POST /api/v1/cameras. The form collects
 * the minimum required fields (name, host, password) and lets the
 * admin override defaults for vendor, ports, credentials, and
 * capability flags.
 *
 * The dialog is intentionally simple — defaults match the most common
 * Hikvision camera configuration (channel_id=101, onvif_port=80,
 * rtsp_port=554, user=admin). The server's Register handler will
 * push the resulting RTSP URL to go2rtc and return the new camera;
 * we just call the caller-supplied [onSuccess] callback so the
 * CamerasFragment can refresh its list.
 */
class RegisterCameraDialog(
    context: Context,
    private val container: AppContainer,
    private val onSuccess: () -> Unit,
) : Dialog(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var etName: EditText
    private lateinit var etHost: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etVendor: EditText
    private lateinit var etChannelId: EditText
    private lateinit var etOnvifPort: EditText
    private lateinit var etRtspPort: EditText
    private lateinit var cbPtz: CheckBox
    private lateinit var cbAudio: CheckBox
    private lateinit var cbMotion: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.camera_register_title)
        setContentView(buildView())
        // Make the dialog wider so all fields fit comfortably.
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun buildView(): View {
        val inflater = LayoutInflater.from(context)
        val root = inflater.inflate(R.layout.dialog_register_camera, null)

        etName = root.findViewById(R.id.etName)
        etHost = root.findViewById(R.id.etHost)
        etUsername = root.findViewById(R.id.etUsername)
        etPassword = root.findViewById(R.id.etPassword)
        etVendor = root.findViewById(R.id.etVendor)
        etChannelId = root.findViewById(R.id.etChannelId)
        etOnvifPort = root.findViewById(R.id.etOnvifPort)
        etRtspPort = root.findViewById(R.id.etRtspPort)
        cbPtz = root.findViewById(R.id.cbPtz)
        cbAudio = root.findViewById(R.id.cbAudio)
        cbMotion = root.findViewById(R.id.cbMotion)

        // Defaults common to Hikvision cameras
        etVendor.setText("hikvision")
        etUsername.setText("admin")
        etChannelId.setText("101")
        etOnvifPort.setText("80")
        etRtspPort.setText("554")
        cbPtz.isChecked = true
        cbAudio.isChecked = true
        cbMotion.isChecked = true

        val btnSubmit = root.findViewById<android.widget.Button>(R.id.btnSubmit)
        val btnCancel = root.findViewById<android.widget.Button>(R.id.btnCancel)
        btnCancel.setOnClickListener { dismiss() }
        btnSubmit.setOnClickListener { submit() }
        return root
    }

    private fun submit() {
        val name = etName.text.toString().trim()
        val host = etHost.text.toString().trim()
        val password = etPassword.text.toString()
        if (name.isEmpty() || host.isEmpty() || password.isEmpty()) {
            toast("请填写名称、主机、密码")
            return
        }
        val request = com.homedatacenter.app.data.model.RegisterCameraRequest(
            name = name,
            vendor = etVendor.text.toString().trim().ifBlank { "hikvision" },
            host = host,
            onvifPort = etOnvifPort.text.toString().trim().toIntOrNull() ?: 80,
            rtspPort = etRtspPort.text.toString().trim().toIntOrNull() ?: 554,
            channelId = etChannelId.text.toString().trim().toIntOrNull() ?: 101,
            username = etUsername.text.toString().trim().ifBlank { "admin" },
            password = password,
            ptz = cbPtz.isChecked,
            audio = cbAudio.isChecked,
            motion = cbMotion.isChecked,
        )
        val token = container.prefsManager.token ?: return
        scope.launch {
            try {
                container.getRepository().registerCamera(token, request)
                toast("注册成功")
                onSuccess()
                dismiss()
            } catch (e: Exception) {
                toast("注册失败: ${e.message}")
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onStop() {
        super.onStop()
        // Don't cancel ongoing requests on dismiss — let them complete.
    }
}
