package com.homedatacenter.app.ui.vision

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.VisionPerson
import com.homedatacenter.app.data.model.VisionStatus
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.ui.glass.ProvideGlassTheme
import com.homedatacenter.app.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Family Faces Management and Vision AI Status activity.
 * Full Jetpack Compose declarative architecture.
 */
class FamilyFacesActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    private val _visionStatus = MutableStateFlow(VisionStatus(online = false))
    private val _persons = MutableStateFlow<List<VisionPerson>>(emptyList())
    private val _isRefreshing = MutableStateFlow(false)

    // Dialog state
    private var showAddDialog by mutableStateOf(false)
    private var addName by mutableStateOf("")
    private var addNameError by mutableStateOf<String?>(null)
    private var selectedBitmap by mutableStateOf<Bitmap?>(null)
    private var isSubmitting by mutableStateOf(false)

    private var personToDelete by mutableStateOf<VisionPerson?>(null)
    private var currentPhotoUri: Uri? = null

    companion object {
        private const val KEY_PHOTO_URI = "key_current_photo_uri"
        private const val KEY_SHOW_ADD_DIALOG = "key_show_add_dialog"
        private const val KEY_ADD_NAME = "key_add_name"
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val bmp = decodeSampledBitmap(uri, 1024, 1024)
                withContext(Dispatchers.Main) {
                    selectedBitmap = bmp
                }
            }
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) {
            launchCameraInternal()
        } else {
            Toast.makeText(this, "需要相机权限以拍摄人脸照片", Toast.LENGTH_SHORT).show()
        }
    }

    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
        if (success) {
            val uri = currentPhotoUri
            if (uri != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val bmp = decodeSampledBitmap(uri, 1024, 1024)
                    withContext(Dispatchers.Main) {
                        if (bmp != null) {
                            selectedBitmap = bmp
                        } else {
                            Toast.makeText(this@FamilyFacesActivity, "解析拍摄照片失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            @Suppress("DEPRECATION")
            currentPhotoUri = savedInstanceState.getParcelable(KEY_PHOTO_URI)
            showAddDialog = savedInstanceState.getBoolean(KEY_SHOW_ADD_DIALOG, false)
            addName = savedInstanceState.getString(KEY_ADD_NAME, "")
        }

        container = (application as HomeCenterApp).container

        setContent {
            val preset by ThemeManager.themePresetFlow.collectAsState()
            ProvideGlassTheme(preset = preset) {
                val status by _visionStatus.collectAsState()
                val personsList by _persons.collectAsState()
                val refreshing by _isRefreshing.collectAsState()

                FamilyFacesPageContent(
                    visionStatus = status,
                    persons = personsList,
                    isRefreshing = refreshing,
                    onRefresh = { fetchData() },
                    onBackClick = { finish() },
                    onAddFaceClick = {
                        addName = ""
                        addNameError = null
                        selectedBitmap = null
                        isSubmitting = false
                        showAddDialog = true
                    },
                    onDeletePersonClick = { person ->
                        personToDelete = person
                    },
                    preset = preset
                )

                // Add Face Dialog in Compose
                if (showAddDialog) {
                    AddFamilyFaceDialog(
                        name = addName,
                        onNameChange = {
                            addName = it
                            if (addNameError != null) addNameError = null
                        },
                        nameError = addNameError,
                        selectedBitmap = selectedBitmap,
                        isSubmitting = isSubmitting,
                        onPickGallery = { pickImageLauncher.launch("image/*") },
                        onPickCamera = { launchCamera() },
                        onDismiss = { showAddDialog = false },
                        onSubmit = { submitRegisterFace() }
                    )
                }

                // Delete Confirmation Dialog in Compose
                personToDelete?.let { person ->
                    ConfirmDeletePersonDialog(
                        personName = person.name,
                        onDismiss = { personToDelete = null },
                        onConfirm = {
                            val target = person
                            personToDelete = null
                            deletePerson(target.name)
                        }
                    )
                }
            }
        }

        fetchData()
    }

    private fun fetchData() {
        val token = container.prefsManager.token
        if (token.isNullOrEmpty()) {
            _isRefreshing.value = false
            Toast.makeText(this, "登录状态失效", Toast.LENGTH_SHORT).show()
            return
        }
        val validToken: String = token

        _isRefreshing.value = true

        lifecycleScope.launch {
            // 1. Fetch Vision AI status
            try {
                val status = withContext(Dispatchers.IO) {
                    container.getRepository().getVisionStatus(validToken)
                }
                _visionStatus.value = status
            } catch (e: Exception) {
                _visionStatus.value = VisionStatus(online = false, error = e.message)
            }

            // 2. Fetch Persons
            try {
                val persons = withContext(Dispatchers.IO) {
                    container.getRepository().listVisionPersons(validToken)
                }
                _persons.value = persons
            } catch (e: Exception) {
                Toast.makeText(this@FamilyFacesActivity, "获取人脸档案失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private fun submitRegisterFace() {
        val name = addName.trim()
        if (name.isEmpty()) {
            addNameError = "请输入姓名或称呼"
            return
        }
        val bitmap = selectedBitmap
        if (bitmap == null) {
            Toast.makeText(this, "请先选择或拍摄人脸照片", Toast.LENGTH_SHORT).show()
            return
        }

        val token = container.prefsManager.token ?: return
        isSubmitting = true

        lifecycleScope.launch {
            try {
                val base64 = withContext(Dispatchers.IO) {
                    bitmapToBase64(bitmap)
                }
                withContext(Dispatchers.IO) {
                    container.getRepository().registerVisionPerson(token, name, base64)
                }
                Toast.makeText(this@FamilyFacesActivity, getString(R.string.vision_register_success), Toast.LENGTH_SHORT).show()
                showAddDialog = false
                fetchData()
            } catch (e: Exception) {
                val errMsg = e.message ?: "录入失败"
                Toast.makeText(this@FamilyFacesActivity, errMsg, Toast.LENGTH_LONG).show()
            } finally {
                isSubmitting = false
            }
        }
    }

    private fun deletePerson(name: String) {
        val token = container.prefsManager.token ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    container.getRepository().deleteVisionPerson(token, name)
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
            val rotationDegrees = try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = android.media.ExifInterface(stream)
                    when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (_: Exception) {
                0f
            }

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false

            val sampled = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            if (sampled != null && rotationDegrees != 0f) {
                val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees) }
                val rotated = Bitmap.createBitmap(sampled, 0, 0, sampled.width, sampled.height, matrix, true)
                if (rotated != sampled) {
                    sampled.recycle()
                }
                rotated
            } else {
                sampled
            }
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentPhotoUri?.let { outState.putParcelable(KEY_PHOTO_URI, it) }
        outState.putBoolean(KEY_SHOW_ADD_DIALOG, showAddDialog)
        outState.putString(KEY_ADD_NAME, addName)
    }

    private fun launchCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCameraInternal()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCameraInternal() {
        try {
            val cameraDir = File(cacheDir, "camera").apply { if (!exists()) mkdirs() }
            cameraDir.listFiles()?.forEach { file ->
                if (file.isFile && System.currentTimeMillis() - file.lastModified() > 24 * 60 * 60 * 1000) {
                    file.delete()
                }
            }
            val photoFile = File(cameraDir, "face_capture_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                photoFile
            )
            currentPhotoUri = uri
            takePhotoLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "启动相机失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
