package com.homedatacenter.app.ui.devices

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.homedatacenter.app.R
import com.homedatacenter.app.ui.main.MainActivity
import kotlinx.coroutines.launch

/**
 * Admin-only dialog that wraps POST /api/v1/device to create a new
 * auth device for the current user.
 *
 * Two states share a single layout (dialog_register_device.xml):
 *  1. Input form — collects the device name (1–64 chars).
 *  2. Result view — shows the plaintext access key ONCE with a copy
 *     button. The server stores only the SHA-256 hash, so the key
 *     cannot be retrieved later.
 *
 * On "完成" the dialog dismisses and asks the host [DevicesFragment]
 * to refresh its list via [DevicesFragment.refreshDevices].
 *
 * Shown via `childFragmentManager` from DevicesFragment so that
 * `parentFragment` resolves to the host fragment.
 */
class RegisterDeviceDialog : DialogFragment() {

    private lateinit var inputForm: View
    private lateinit var resultView: View
    private lateinit var etDeviceName: TextInputEditText
    private lateinit var btnCreate: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var tvAccessKey: TextView
    private lateinit var btnCopy: MaterialButton
    private lateinit var btnDone: MaterialButton
    private lateinit var progressBar: ProgressBar

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_register_device, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        inputForm = view.findViewById(R.id.inputForm)
        resultView = view.findViewById(R.id.resultView)
        etDeviceName = view.findViewById(R.id.etDeviceName)
        btnCreate = view.findViewById(R.id.btnCreate)
        btnCancel = view.findViewById(R.id.btnCancel)
        tvAccessKey = view.findViewById(R.id.tvAccessKey)
        btnCopy = view.findViewById(R.id.btnCopy)
        btnDone = view.findViewById(R.id.btnDone)
        progressBar = view.findViewById(R.id.progressBar)

        btnCancel.setOnClickListener { dismiss() }
        btnCreate.setOnClickListener { submit() }
        btnCopy.setOnClickListener { copyAccessKey() }
        btnDone.setOnClickListener {
            (parentFragment as? DevicesFragment)?.refreshDevices()
            dismiss()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    private fun submit() {
        val name = etDeviceName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty() || name.length > 64) {
            toast(getString(R.string.device_register_invalid))
            return
        }
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return

        setLoading(true)
        lifecycleScope.launch {
            try {
                val result = mainActivity.container.getRepository().createDevice(token, name)
                tvAccessKey.text = result.accessKey.ifEmpty { getString(R.string.device_register_access_key_label) }
                inputForm.visibility = View.GONE
                resultView.visibility = View.VISIBLE
            } catch (e: Exception) {
                toast(getString(R.string.device_register_failed, e.message ?: ""))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun copyAccessKey() {
        val key = tvAccessKey.text?.toString().orEmpty()
        if (key.isEmpty()) return
        val ctx = context ?: return
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("access_key", key))
        toast(getString(R.string.device_register_copied))
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnCreate.isEnabled = !loading
        btnCancel.isEnabled = !loading
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
