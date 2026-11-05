package com.homedatacenter.app.ui.alerts

import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog as ComposeDialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.DialogFragment
import androidx.compose.runtime.collectAsState
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.Alert
import com.homedatacenter.app.ui.components.GlassTag
import com.homedatacenter.app.ui.components.GlassTagType
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.ProvideGlassTheme
import com.homedatacenter.app.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure Jetpack Compose Alert Snapshot modal with Liquid Glass styling.
 * Eliminates legacy dialog_alert_snapshot.xml.
 */
class AlertSnapshotDialogFragment : DialogFragment() {

    private var alert: Alert? = null
    private var baseUrl: String? = null
    private var token: String? = null
    private var okHttpClient: OkHttpClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.SnapshotDialogTheme)
        isCancelable = true

        arguments?.let { args ->
            val json = args.getString("alert_json")
            if (json != null && alert == null) {
                alert = runCatching {
                    com.homedatacenter.app.data.api.NetworkFactory.json.decodeFromString(Alert.serializer(), json)
                }.getOrNull()
            }
            if (baseUrl == null) baseUrl = args.getString("baseUrl")
            if (token == null) token = args.getString("token")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val a = alert ?: return View(requireContext())
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                val preset by ThemeManager.themePresetFlow.collectAsState()
                ProvideGlassTheme(preset = preset) {
                    AlertSnapshotCard(
                        alert = a,
                        baseUrl = baseUrl,
                        token = token,
                        okHttpClient = okHttpClient,
                        onDismiss = { dismiss() }
                    )
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    companion object {
        const val TAG = "AlertSnapshotDialog"

        fun newInstance(
            alert: Alert,
            baseUrl: String?,
            token: String?,
            client: OkHttpClient? = null
        ): AlertSnapshotDialogFragment {
            return AlertSnapshotDialogFragment().apply {
                this.alert = alert
                this.baseUrl = baseUrl
                this.token = token
                this.okHttpClient = client
                arguments = Bundle().apply {
                    val json = runCatching {
                        com.homedatacenter.app.data.api.NetworkFactory.json.encodeToString(Alert.serializer(), alert)
                    }.getOrNull()
                    putString("alert_json", json)
                    putString("baseUrl", baseUrl)
                    putString("token", token)
                }
            }
        }
    }
}

/**
 * Standalone Dialog version of snapshot preview, safe to display from any Context or Dialog.
 */
class AlertSnapshotDialog(
    context: Context,
    private val alert: Alert,
    private val baseUrl: String?,
    private val token: String?,
    private val okHttpClient: OkHttpClient?
) : androidx.appcompat.app.AppCompatDialog(context, R.style.SnapshotDialogTheme) {

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                AlertSnapshotCard(
                    alert = alert,
                    baseUrl = baseUrl,
                    token = token,
                    okHttpClient = okHttpClient,
                    onDismiss = { dismiss() }
                )
            }
        }
        setContentView(composeView)
    }
}

@Composable
fun AlertSnapshotCard(
    alert: Alert,
    baseUrl: String?,
    token: String?,
    okHttpClient: OkHttpClient?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    var showRegisterFaceDialog by remember { mutableStateOf(false) }
    var registerFaceName by remember { mutableStateOf("") }
    var isRegisteringFace by remember { mutableStateOf(false) }
    var registerStatusText by remember { mutableStateOf<String?>(null) }

    val preset by ThemeManager.themePresetFlow.collectAsState()

    ProvideGlassTheme(preset = preset) {
        val tokens = GlassTheme.tokens
        val isLight = tokens.isLight

        val cyberCyan = tokens.accentColor
        val dialogBg = tokens.dialogBg
        val borderCol = tokens.cardBorder
        val textPrimary = tokens.textColor
        val textSecondary = tokens.textSecondaryColor
        val warningOrange = tokens.statusWarning

        val app = context.applicationContext as? HomeCenterApp
        val isAdmin = app?.container?.let { it.roleManager.isAdmin() || it.prefsManager.isAdmin } == true

        LaunchedEffect(alert.id) {
        isLoading = true
        errorMessage = null
        withContext(Dispatchers.IO) {
            val client = okHttpClient ?: (context.applicationContext as? HomeCenterApp)?.container?.okHttpClient ?: OkHttpClient()
            val url = if (!baseUrl.isNullOrBlank()) {
                val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                "${base}api/v1/cameras/alerts/${alert.id}/snapshot?quality=100"
            } else ""

            var loaded: Bitmap? = null
            if (url.isNotEmpty()) {
                try {
                    val req = Request.Builder().url(url).apply {
                        if (!token.isNullOrEmpty()) addHeader("Authorization", "Bearer $token")
                    }.build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            resp.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                        } else null
                    }?.also { loaded = it }
                } catch (_: Exception) {}
            }

            if (loaded == null && alert.thumbnail.isNotEmpty()) {
                try {
                    val bytes = android.util.Base64.decode(alert.thumbnail, android.util.Base64.DEFAULT)
                    loaded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (_: Exception) {}
            }

            if (loaded == null && !baseUrl.isNullOrBlank()) {
                try {
                    val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                    val thumbUrl = "${base}api/v1/cameras/alerts/${alert.id}/thumbnail"
                    val thumbReq = Request.Builder().url(thumbUrl).apply {
                        if (!token.isNullOrEmpty()) addHeader("Authorization", "Bearer $token")
                    }.build()
                    client.newCall(thumbReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            resp.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                        } else null
                    }?.also { loaded = it }
                } catch (_: Exception) {}
            }

            withContext(Dispatchers.Main) {
                bitmap = loaded
                isLoading = false
                if (loaded == null) {
                    errorMessage = "暂无抓拍大图"
                }
            }
        }
    }

    fun saveToGallery() {
        val bmp = bitmap ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val filename = "Snapshot_${alert.cameraSlug}_${System.currentTimeMillis()}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/HomeSecurity")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        context.contentResolver.update(uri, values, null, null)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "抓拍大图已保存至系统相册！", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(tokens.dialogScrim)
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = dialogBg,
                border = BorderStroke(1.2.dp, borderCol),
                shadowElevation = if (isLight) 10.dp else 24.dp,
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .clickable(enabled = false) {}
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = warningOrange.copy(alpha = 0.18f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_alerts),
                                    contentDescription = null,
                                    tint = warningOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = alert.label,
                                    color = textPrimary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                GlassTag(
                                    text = "${(alert.confidence * 100).toInt()}%",
                                    type = GlassTagType.Info,
                                    tokens = tokens
                                )
                            }
                            val camera = alert.cameraName.ifEmpty { alert.cameraSlug.ifEmpty { "监控摄像头" } }
                            val zones = if (alert.zones.isNotEmpty()) alert.zones.joinToString(", ") else "全域"
                            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                .format(Date((alert.startTime * 1000).toLong()))
                            Text(
                                text = "$camera · $zones · $timeStr",
                                color = textSecondary,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        // Save button
                        IconButton(onClick = { saveToGallery() }, enabled = bitmap != null) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_download),
                                contentDescription = "保存至相册",
                                tint = if (bitmap != null) textPrimary else textSecondary.copy(alpha = 0.4f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Close button
                        IconButton(onClick = onDismiss) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_close),
                                contentDescription = "关闭",
                                tint = textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Image Container
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isLight) Color(0x14000000) else Color(0xFF0F172A))
                            .border(
                                1.dp,
                                tokens.borderColors.firstOrNull()?.copy(alpha = 0.4f) ?: Color(0x22888888),
                                RoundedCornerShape(16.dp)
                            )
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 4f)
                                    if (scale > 1f) {
                                        offsetX += pan.x
                                        offsetY += pan.y
                                    } else {
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(color = cyberCyan)
                        } else if (bitmap != null) {
                            Image(
                                bitmap = bitmap!!.asImageBitmap(),
                                contentDescription = "抓拍大图",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        translationX = offsetX,
                                        translationY = offsetY
                                    )
                            )
                            if (scale > 1.05f) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xCC000000),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp)
                                        .clickable {
                                            scale = 1f
                                            offsetX = 0f
                                            offsetY = 0f
                                        }
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "%.1fx 点击复位", scale),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        } else {
                            Text(errorMessage ?: "无法加载抓拍大图", color = textSecondary, fontSize = 13.sp)
                        }
                    }

                    // Family Face Registration action for person alerts
                    if (isAdmin && alert.label.equals("person", ignoreCase = true) && bitmap != null) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { showRegisterFaceDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = cyberCyan,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Text(
                                registerStatusText ?: "录入为家庭成员人脸档案",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }

        if (showRegisterFaceDialog && bitmap != null) {
            val bmp = bitmap!!
            ComposeDialog(
                onDismissRequest = { if (!isRegisteringFace) showRegisterFaceDialog = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = dialogBg,
                    border = BorderStroke(1.2.dp, borderCol),
                    shadowElevation = 16.dp,
                    modifier = Modifier.padding(24.dp).fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("录入家庭人脸档案", color = textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "将此抓拍帧人脸提取特征并录入至家庭人脸库：",
                            color = textSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
                        )

                        val textFieldColors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = textPrimary,
                            unfocusedTextColor = textPrimary,
                            focusedBorderColor = cyberCyan,
                            unfocusedBorderColor = cyberCyan.copy(alpha = 0.35f),
                            cursorColor = cyberCyan,
                        )

                        OutlinedTextField(
                            value = registerFaceName,
                            onValueChange = { registerFaceName = it },
                            label = { Text("成员称呼或姓名", color = textSecondary) },
                            placeholder = { Text("例如：爸爸、妈妈、宝贝", color = textSecondary.copy(alpha = 0.5f)) },
                            singleLine = true,
                            colors = textFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { showRegisterFaceDialog = false },
                                enabled = !isRegisteringFace
                            ) {
                                Text("取消", color = textSecondary)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val name = registerFaceName.trim()
                                    if (name.isEmpty()) return@Button
                                    val tok = token ?: app?.container?.prefsManager?.token ?: return@Button
                                    isRegisteringFace = true
                                    scope.launch {
                                        try {
                                            val base64 = withContext(Dispatchers.IO) {
                                                val stream = ByteArrayOutputStream()
                                                bmp.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                                                android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
                                            }
                                            withContext(Dispatchers.IO) {
                                                app?.container?.getRepository()?.registerVisionPerson(tok, name, base64)
                                            }
                                            Toast.makeText(context, "已成功录入家庭成员「$name」的人脸档案！", Toast.LENGTH_SHORT).show()
                                            registerStatusText = "已录入为 $name"
                                            showRegisterFaceDialog = false
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "录入失败: ${e.message}", Toast.LENGTH_LONG).show()
                                        } finally {
                                            isRegisteringFace = false
                                        }
                                    }
                                },
                                enabled = !isRegisteringFace,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = cyberCyan,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (isRegisteringFace) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("确认录入", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
