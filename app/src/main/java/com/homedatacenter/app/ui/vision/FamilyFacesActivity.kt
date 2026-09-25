package com.homedatacenter.app.ui.vision

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.VisionPerson
import com.homedatacenter.app.data.model.VisionStatus
import com.homedatacenter.app.databinding.ActivityFamilyFacesBinding
import com.homedatacenter.app.databinding.DialogAddFamilyFaceBinding
import com.homedatacenter.app.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * Family Faces Management and Vision AI Status activity.
 *
 * Allows family administrators to:
 * 1. Monitor edge Vision AI service status, CPU usage & safety gate thresholds.
 * 2. View registered family members whose face embeddings are loaded.
 * 3. Register new family face profiles via gallery pick or camera photo.
 * 4. Remove family face profiles.
 */
class FamilyFacesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFamilyFacesBinding
    private lateinit var container: AppContainer
    private lateinit var adapter: FamilyFaceAdapter

    private var selectedBitmap: Bitmap? = null
    private var activeAddDialog: AlertDialog? = null
    private var activeDialogBinding: DialogAddFamilyFaceBinding? = null

    companion object {
        private const val MENU_ADD = 1
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val bmp = decodeSampledBitmap(uri, 1024, 1024)
            onImagePicked(bmp)
        }
    }

    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp: Bitmap? ->
        if (bmp != null) {
            onImagePicked(bmp)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFamilyFacesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        container = (application as HomeCenterApp).container

        setupToolbar()
        setupRecyclerView()
        setupListeners()

        fetchData()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.menu.add(Menu.NONE, MENU_ADD, Menu.NONE, R.string.vision_btn_add)
            .setIcon(R.drawable.ic_add)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_ADD -> {
                    showAddFaceDialog()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = FamilyFaceAdapter { person ->
            confirmDeletePerson(person)
        }
        binding.rvFaces.layoutManager = LinearLayoutManager(this)
        binding.rvFaces.adapter = adapter
    }

    private fun setupListeners() {
        binding.swipeRefresh.setOnRefreshListener {
            fetchData()
        }
        binding.btnAddFace.setOnClickListener {
            showAddFaceDialog()
        }
    }

    private fun fetchData() {
        val token = container.prefsManager.token
        if (token.isNullOrEmpty()) {
            binding.swipeRefresh.isRefreshing = false
            Toast.makeText(this, "登录状态失效", Toast.LENGTH_SHORT).show()
            return
        }
        val validToken: String = token

        binding.swipeRefresh.isRefreshing = true

        lifecycleScope.launch {
            // 1. Fetch Vision AI status
            try {
                val status = withContext(Dispatchers.IO) {
                    container.getRepository().getVisionStatus(validToken)
                }
                updateStatusUi(status)
            } catch (e: Exception) {
                updateStatusUi(VisionStatus(online = false, error = e.message))
            }

            // 2. Fetch Persons
            try {
                val persons = withContext(Dispatchers.IO) {
                    container.getRepository().listVisionPersons(validToken)
                }
                adapter.submitList(persons)
                binding.tvPersonsCount.text = getString(R.string.vision_persons_count, persons.size)
                binding.layoutEmpty.visibility = if (persons.isEmpty()) View.VISIBLE else View.GONE
                binding.rvFaces.visibility = if (persons.isEmpty()) View.GONE else View.VISIBLE
            } catch (e: Exception) {
                Toast.makeText(this@FamilyFacesActivity, "获取人脸档案失败: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.layoutEmpty.visibility = View.VISIBLE
                binding.rvFaces.visibility = View.GONE
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun updateStatusUi(status: VisionStatus) {
        val greenColor = ContextCompat.getColor(this, R.color.online)
        val redColor = ContextCompat.getColor(this, R.color.error)
        val warningColor = ContextCompat.getColor(this, R.color.warning)

        if (status.online) {
            binding.tvVisionOnlineStatus.text = getString(R.string.vision_status_online)
            binding.tvVisionOnlineStatus.setBackgroundResource(R.drawable.bg_badge_admin)

            binding.tvFaceEngineStatus.text = if (status.faceEngineReady) {
                getString(R.string.vision_engine_ready)
            } else {
                getString(R.string.vision_engine_not_ready)
            }
            binding.tvFaceEngineStatus.setTextColor(if (status.faceEngineReady) greenColor else redColor)

            binding.tvPoseEngineStatus.text = if (status.poseEngineReady) {
                getString(R.string.vision_engine_ready)
            } else {
                getString(R.string.vision_engine_not_ready)
            }
            binding.tvPoseEngineStatus.setTextColor(if (status.poseEngineReady) greenColor else redColor)

            binding.tvCpuUsage.text = String.format(Locale.getDefault(), "%.1f%% CPU", status.cpuUsagePercent)

            when (status.cpuGate.lowercase(Locale.getDefault())) {
                "circuit_break" -> {
                    binding.tvCpuGate.text = getString(R.string.vision_cpu_gate_circuit_break)
                    binding.tvCpuGate.setTextColor(redColor)
                }
                "degraded" -> {
                    binding.tvCpuGate.text = getString(R.string.vision_cpu_gate_degraded)
                    binding.tvCpuGate.setTextColor(warningColor)
                }
                else -> {
                    binding.tvCpuGate.text = getString(R.string.vision_cpu_gate_normal)
                    binding.tvCpuGate.setTextColor(greenColor)
                }
            }
        } else {
            val text = if (status.error?.contains("not configured", ignoreCase = true) == true) {
                getString(R.string.vision_status_unconfigured)
            } else {
                getString(R.string.vision_status_offline)
            }
            binding.tvVisionOnlineStatus.text = text
            binding.tvVisionOnlineStatus.setBackgroundResource(R.drawable.bg_badge_user)

            binding.tvFaceEngineStatus.text = getString(R.string.vision_engine_not_ready)
            binding.tvFaceEngineStatus.setTextColor(redColor)
            binding.tvPoseEngineStatus.text = getString(R.string.vision_engine_not_ready)
            binding.tvPoseEngineStatus.setTextColor(redColor)
            binding.tvCpuUsage.text = "—"
            binding.tvCpuGate.text = "不可用"
            binding.tvCpuGate.setTextColor(redColor)
        }
    }

    private fun showAddFaceDialog() {
        selectedBitmap = null
        val dialogBinding = DialogAddFamilyFaceBinding.inflate(layoutInflater)
        activeDialogBinding = dialogBinding

        dialogBinding.btnPickGallery.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        dialogBinding.btnPickCamera.setOnClickListener {
            takePhotoLauncher.launch(null)
        }

        dialogBinding.btnCancel.setOnClickListener {
            activeAddDialog?.dismiss()
        }

        dialogBinding.btnSubmit.setOnClickListener {
            val name = dialogBinding.etName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                dialogBinding.tilName.error = "请输入姓名或称呼"
                return@setOnClickListener
            }
            dialogBinding.tilName.error = null

            val bitmap = selectedBitmap
            if (bitmap == null) {
                Toast.makeText(this, "请先选择或拍摄人脸照片", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            submitRegisterFace(name, bitmap, dialogBinding)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        activeAddDialog = dialog
        dialog.show()
    }

    private fun onImagePicked(bitmap: Bitmap?) {
        selectedBitmap = bitmap
        activeDialogBinding?.let { b ->
            if (bitmap != null) {
                b.ivFacePreview.setImageBitmap(bitmap)
                b.ivFacePreview.visibility = View.VISIBLE
                b.layoutPickPrompt.visibility = View.GONE
            } else {
                b.ivFacePreview.visibility = View.GONE
                b.layoutPickPrompt.visibility = View.VISIBLE
            }
        }
    }

    private fun submitRegisterFace(name: String, bitmap: Bitmap, dialogBinding: DialogAddFamilyFaceBinding) {
        val token = container.prefsManager.token
        if (token.isNullOrEmpty()) return
        val validToken: String = token

        dialogBinding.btnSubmit.isEnabled = false
        dialogBinding.btnCancel.isEnabled = false
        dialogBinding.btnPickGallery.isEnabled = false
        dialogBinding.btnPickCamera.isEnabled = false
        dialogBinding.progressRegister.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val base64 = withContext(Dispatchers.IO) {
                    bitmapToBase64(bitmap)
                }

                withContext(Dispatchers.IO) {
                    container.getRepository().registerVisionPerson(validToken, name, base64)
                }

                Toast.makeText(this@FamilyFacesActivity, getString(R.string.vision_register_success), Toast.LENGTH_SHORT).show()
                activeAddDialog?.dismiss()
                fetchData()
            } catch (e: Exception) {
                val errMsg = e.message ?: "录入失败"
                Toast.makeText(this@FamilyFacesActivity, errMsg, Toast.LENGTH_LONG).show()
                dialogBinding.btnSubmit.isEnabled = true
                dialogBinding.btnCancel.isEnabled = true
                dialogBinding.btnPickGallery.isEnabled = true
                dialogBinding.btnPickCamera.isEnabled = true
                dialogBinding.progressRegister.visibility = View.GONE
            }
        }
    }

    private fun confirmDeletePerson(person: VisionPerson) {
        AlertDialog.Builder(this)
            .setTitle(R.string.vision_delete_confirm_title)
            .setMessage(getString(R.string.vision_delete_confirm_message, person.name))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                deletePerson(person.name)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun deletePerson(name: String) {
        val token = container.prefsManager.token
        if (token.isNullOrEmpty()) return
        val validToken: String = token

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    container.getRepository().deleteVisionPerson(validToken, name)
                }
                Toast.makeText(this@FamilyFacesActivity, "已删除成员 $name", Toast.LENGTH_SHORT).show()
                fetchData()
            } catch (e: Exception) {
                Toast.makeText(this@FamilyFacesActivity, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun decodeSampledBitmap(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            var stream: InputStream? = contentResolver.openInputStream(uri)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(stream, null, options)
            stream?.close()

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false

            stream = contentResolver.openInputStream(uri)
            val sampled = BitmapFactory.decodeStream(stream, null, options)
            stream?.close()
            sampled
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    override fun onDestroy() {
        super.onDestroy()
        activeAddDialog?.dismiss()
        activeAddDialog = null
        activeDialogBinding = null
        selectedBitmap = null
    }
}
