package com.homedatacenter.app.ui.ops

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.ui.glass.GlassTheme
import com.homedatacenter.app.ui.glass.ProvideGlassTheme
import com.homedatacenter.app.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * OnePanelWebActivity: Secure In-App 1Panel Web Console with Automated Session Injection.
 *
 * Fetches authenticated session cookies (psession, pcsrftoken) from /api/v1/ops/onepanel/session
 * using the app's JWT credentials, injects them into CookieManager, and loads the 1Panel SPA.
 */
class OnePanelWebActivity : ComponentActivity() {

    private val tag = "OnePanelWebActivity"
    private var webView: WebView? = null

    companion object {
        const val EXTRA_URL = "extra_1panel_url"
        const val EXTRA_TOKEN = "extra_token"
        const val EXTRA_API_BASE_URL = "extra_api_base_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = applicationContext as? HomeCenterApp
        val targetUrl = intent.getStringExtra(EXTRA_URL) ?: resolveDefaultUrl()
        val token = intent.getStringExtra(EXTRA_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: app?.container?.prefsManager?.token.orEmpty()
        val apiBaseUrl = intent.getStringExtra(EXTRA_API_BASE_URL)
            ?.takeIf { it.isNotBlank() }
            ?: app?.container?.baseUrlResolver?.current()
            ?: "http://192.168.31.234:8080"

        setContent {
            val preset by ThemeManager.themePresetFlow.collectAsState()

            ProvideGlassTheme(preset = preset) {
                OnePanelWebScreen(
                    targetUrl = targetUrl,
                    token = token,
                    apiBaseUrl = apiBaseUrl,
                    onBack = { finish() },
                    onAttachWebView = { wv -> webView = wv }
                )
            }
        }
    }

    private fun resolveDefaultUrl(): String {
        return try {
            val base = (applicationContext as? HomeCenterApp)?.container?.baseUrlResolver?.current() ?: "http://192.168.31.234:8088"
            "${base.trimEnd('/')}/btpanel"
        } catch (_: Exception) {
            "http://192.168.31.234:8088/btpanel"
        }
    }

    override fun onDestroy() {
        webView?.let { wv ->
            try {
                wv.stopLoading()
                wv.clearHistory()
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            } catch (e: Exception) {
                Log.w(tag, "WebView cleanup error: ${e.message}")
            }
        }
        webView = null
        super.onDestroy()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OnePanelWebScreen(
    targetUrl: String,
    token: String,
    apiBaseUrl: String,
    onBack: () -> Unit,
    onAttachWebView: (WebView) -> Unit
) {
    val tokens = GlassTheme.tokens
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    var webProgress by remember { mutableFloatStateOf(0f) }
    var isSessionInjecting by remember { mutableStateOf(true) }
    var retryTrigger by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf("1Panel 运维控制台") }
    var currentWebView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = canGoBack) {
        currentWebView?.goBack()
    }

    // Coroutine to fetch session and inject cookies
    LaunchedEffect(targetUrl, apiBaseUrl, retryTrigger) {
        withContext(Dispatchers.IO) {
            try {
                val base = apiBaseUrl.trimEnd('/')

                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .build()

                val reqBuilder = Request.Builder()
                    .url("$base/api/v1/ops/onepanel/session")
                    .get()

                if (token.isNotBlank()) {
                    val authHeader = if (token.startsWith("Bearer ")) token else "Bearer $token"
                    reqBuilder.header("Authorization", authHeader)
                }

                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bodyStr = resp.body?.string().orEmpty()
                        val json = JSONObject(bodyStr)
                        val dataObj = json.optJSONObject("data")
                        if (dataObj != null) {
                            val psession = dataObj.optString("psession")
                            val pcsrftoken = dataObj.optString("pcsrftoken")
                            val entryUrl = dataObj.optString("entry_url")

                            withContext(Dispatchers.Main) {
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                if (psession.isNotEmpty()) {
                                    cookieManager.setCookie(targetUrl, "psession=$psession; Path=/; SameSite=Lax")
                                }
                                if (pcsrftoken.isNotEmpty()) {
                                    cookieManager.setCookie(targetUrl, "pcsrftoken=$pcsrftoken; Path=/; SameSite=Lax")
                                }
                                if (entryUrl.isNotEmpty() && entryUrl != targetUrl) {
                                    if (psession.isNotEmpty()) {
                                        cookieManager.setCookie(entryUrl, "psession=$psession; Path=/; SameSite=Lax")
                                    }
                                    if (pcsrftoken.isNotEmpty()) {
                                        cookieManager.setCookie(entryUrl, "pcsrftoken=$pcsrftoken; Path=/; SameSite=Lax")
                                    }
                                }
                                cookieManager.flush()
                                Log.i("OnePanelWebActivity", "Session cookies successfully injected into CookieManager")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("OnePanelWebActivity", "Failed to auto-fetch session: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    isSessionInjecting = false
                    if (currentWebView?.url == null) {
                        currentWebView?.loadUrl(targetUrl)
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.bgBase)
    ) {
        // Top Glass Navigation Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.surfaceColor)
                .padding(top = topInset, bottom = 8.dp, start = 8.dp, end = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_baseline_arrow_back_24),
                        contentDescription = "返回",
                        tint = tokens.textColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pageTitle,
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.textColor
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = targetUrl,
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = tokens.textSecondaryColor
                        ),
                        maxLines = 1
                    )
                }

                // Refresh button
                IconButton(
                    onClick = {
                        currentWebView?.reload()
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_refresh),
                        contentDescription = "刷新",
                        tint = tokens.textColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Web Loading Progress Indicator
        if (webProgress in 0.01f..0.99f || isSessionInjecting) {
            LinearProgressIndicator(
                progress = { if (isSessionInjecting) 0.3f else webProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = tokens.accentColor,
                trackColor = tokens.controlBg
            )
        }

        // WebView Container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            cacheMode = WebSettings.LOAD_DEFAULT
                            userAgentString = "$userAgentString HomeDataCenterAndroid/1.18.9"
                        }
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                webProgress = newProgress / 100f
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                if (!title.isNullOrBlank() && !title.startsWith("http")) {
                                    pageTitle = title
                                }
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                pageError = null
                                canGoBack = view?.canGoBack() == true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                webProgress = 1.0f
                                canGoBack = view?.canGoBack() == true
                            }

                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                if (request?.isForMainFrame == true) {
                                    val errDesc = error?.description?.toString() ?: "连接失败"
                                    Log.w("OnePanelWebActivity", "Load error: $errDesc")
                                    pageError = errDesc
                                }
                            }

                            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 200) >= 400) {
                                    val code = errorResponse?.statusCode ?: 500
                                    val phrase = errorResponse?.reasonPhrase ?: "服务暂不可用"
                                    Log.w("OnePanelWebActivity", "HTTP error: $code $phrase")
                                    pageError = "HTTP $code ($phrase)"
                                }
                            }
                        }

                        currentWebView = this
                        onAttachWebView(this)
                    }
                },
                update = { wv ->
                    if (!isSessionInjecting && wv.url == null) {
                        wv.loadUrl(targetUrl)
                    }
                }
            )

            // Centered spinner during initial cookie injection
            if (isSessionInjecting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(tokens.bgBase.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = tokens.accentColor,
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "正在安全同步 1Panel 运维会话...",
                            style = TextStyle(
                                fontSize = 13.5.sp,
                                color = tokens.textColor,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }
            }
            // Error overlay with retry & channel fallback
            if (pageError != null && !isSessionInjecting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(tokens.bgBase.copy(alpha = 0.95f))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(tokens.cardBg)
                            .border(1.dp, tokens.cardBorder, RoundedCornerShape(20.dp))
                            .padding(20.dp)
                    ) {
                        Text(
                            text = "1Panel 控制台连接受阻",
                            style = TextStyle(
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = tokens.textColor
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "网络错误: $pageError\n建议使用统一网关反向代理通道（无需开放 10000 端口）。",
                            style = TextStyle(
                                fontSize = 12.5.sp,
                                color = tokens.textSecondaryColor,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        )
                        Spacer(modifier = Modifier.height(18.dp))

                        // Retry via Gateway button
                        Button(
                            onClick = {
                                pageError = null
                                isSessionInjecting = true
                                retryTrigger++
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = tokens.accentColor),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "重新同步会话并重试 (/btpanel)", color = Color.White)
                        }

                        val host = try { URI(apiBaseUrl).host ?: "192.168.31.234" } catch (_: Exception) { "192.168.31.234" }
                        val isLan = host == "192.168.31.234" || host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")
                        if (isLan) {
                            Spacer(modifier = Modifier.height(10.dp))
                            // Fallback direct LAN button (only available when on home LAN)
                            OutlinedButton(
                                onClick = {
                                    pageError = null
                                    val directUrl = "http://$host:10000/btpanel"
                                    currentWebView?.loadUrl(directUrl)
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(text = "局域网直连模式 (:10000/btpanel)", color = tokens.textColor)
                            }
                        }
                    }
                }
            }
        }
    }
}
