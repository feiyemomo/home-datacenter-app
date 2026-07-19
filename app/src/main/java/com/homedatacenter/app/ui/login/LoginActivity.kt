package com.homedatacenter.app.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.databinding.ActivityLoginBinding
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.ui.main.MainActivity
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        container = (application as HomeCenterApp).container

        if (container.prefsManager.isLoggedIn()) {
            navigateToMain()
            return
        }

        binding.btnLogin.setOnClickListener { attemptLogin() }
    }

    private fun attemptLogin() {
        val userIdStr = binding.etUserId.text?.toString()?.trim() ?: ""
        val accessKey = binding.etAccessKey.text?.toString()?.trim() ?: ""

        if (userIdStr.isEmpty() || accessKey.isEmpty()) {
            showError(getString(com.homedatacenter.app.R.string.login_invalid_input))
            return
        }

        val userId = userIdStr.toLongOrNull()
        if (userId == null || userId <= 0) {
            showError(getString(com.homedatacenter.app.R.string.login_invalid_input))
            return
        }

        showLoading(true)
        hideError()

        lifecycleScope.launch {
            try {
                val token = container.getRepository().bind(userId, accessKey)
                container.prefsManager.token = token
                container.prefsManager.userId = userId
                try {
                    val user = container.getRepository().getMe(token)
                    container.prefsManager.saveUserInfo(user.name, user.isAdmin)
                } catch (_: Exception) {
                }
                navigateToMain()
            } catch (e: Exception) {
                showError(getString(com.homedatacenter.app.R.string.login_error) + ": ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !show
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.tvError.visibility = View.GONE
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}
