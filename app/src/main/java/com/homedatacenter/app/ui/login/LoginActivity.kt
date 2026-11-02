package com.homedatacenter.app.ui.login

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.ui.glass.AmbientFluidBackground
import com.homedatacenter.app.ui.glass.ProvideGlassTheme
import com.homedatacenter.app.ui.main.MainActivity
import com.homedatacenter.app.util.ThemeManager
import kotlinx.coroutines.launch

class LoginActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    private var userIdText by mutableStateOf("")
    private var accessKeyText by mutableStateOf("")
    private var isLoading by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as HomeCenterApp).container

        if (container.prefsManager.isLoggedIn()) {
            navigateToMain()
            return
        }

        // v1.14.2: arrived here because the server invalidated the login.
        intent?.getStringExtra(EXTRA_AUTH_INVALID_MESSAGE)?.let { errorMessage = it }

        setContent {
            val preset by ThemeManager.themePresetFlow.collectAsState()
            ProvideGlassTheme(preset = preset) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AmbientFluidBackground(
                        preset = preset,
                        modifier = Modifier.fillMaxSize()
                    )

                    LoginPageContent(
                        userId = userIdText,
                        onUserIdChange = {
                            userIdText = it
                            errorMessage = null
                        },
                        accessKey = accessKeyText,
                        onAccessKeyChange = {
                            accessKeyText = it
                            errorMessage = null
                        },
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        onLoginClick = { attemptLogin() }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_AUTH_INVALID_MESSAGE = "extra_auth_invalid_message"
    }

    private fun attemptLogin() {
        val userIdStr = userIdText.trim()
        val accessKey = accessKeyText.trim()

        if (userIdStr.isEmpty() || accessKey.isEmpty()) {
            errorMessage = getString(com.homedatacenter.app.R.string.login_invalid_input)
            return
        }

        val userId = userIdStr.toLongOrNull()
        if (userId == null || userId <= 0) {
            errorMessage = getString(com.homedatacenter.app.R.string.login_invalid_input)
            return
        }

        isLoading = true
        errorMessage = null

        lifecycleScope.launch {
            try {
                val token = container.getRepository().bind(userId, accessKey)
                container.prefsManager.token = token
                container.prefsManager.userId = userId
                container.prefsManager.accessKey = accessKey
                container.prefsManager.lastTokenRefreshTime = System.currentTimeMillis()
                container.authInvalidHandler.reset()

                try {
                    val user = container.getRepository().getMe(token)
                    container.prefsManager.saveUserInfo(user.name, user.isAdmin)
                    container.roleManager.updateAdmin(user.isAdmin)
                } catch (_: Exception) {}

                container.warmWebRtc()
                container.prefetchIceConfig()
                navigateToMain()
            } catch (e: Exception) {
                val errorMsg = if (e is retrofit2.HttpException) {
                    val errorBody = e.response()?.errorBody()?.string()
                    val parsedMsg = try {
                        if (!errorBody.isNullOrBlank()) {
                            val json = org.json.JSONObject(errorBody)
                            json.optString("message").ifBlank { null }
                        } else null
                    } catch (_: Exception) {
                        null
                    }
                    parsedMsg ?: when (e.code()) {
                        401 -> "凭据无效，请检查用户 ID 与密钥"
                        403 -> "访问被安全防御系统拦截，请稍后再试或联系管理员"
                        502, 504 -> "无法连接至家庭服务器网关 (网关超时/错误)"
                        else -> "HTTP ${e.code()}: ${e.message()}"
                    }
                } else {
                    e.message ?: "网络连接异常"
                }
                errorMessage = getString(com.homedatacenter.app.R.string.login_error) + ": $errorMsg"
            } finally {
                isLoading = false
            }
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}
