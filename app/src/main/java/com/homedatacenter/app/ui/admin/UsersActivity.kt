package com.homedatacenter.app.ui.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.data.model.User
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.ui.glass.AmbientFluidBackground
import com.homedatacenter.app.ui.glass.AppThemePreset
import com.homedatacenter.app.ui.glass.ProvideGlassTheme
import com.homedatacenter.app.util.ThemeManager
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Admin-only user management screen (Standalone Activity).
 * 100% Jetpack Compose declarative architecture.
 */
class UsersActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    private val _users = MutableStateFlow<List<User>>(emptyList())
    private val _isRefreshing = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as HomeCenterApp).container

        setContent {
            val preset by ThemeManager.themePresetFlow.collectAsState()
            ProvideGlassTheme(preset = preset) {
                val hazeState = rememberHazeState()
                val users by _users.collectAsState()
                val isRefreshing by _isRefreshing.collectAsState()

                var showCreateDialog by remember { mutableStateOf(false) }
                var userToEdit by remember { mutableStateOf<User?>(null) }
                var isDialogSubmitting by remember { mutableStateOf(false) }

                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    AmbientFluidBackground(
                        hazeState = hazeState,
                        preset = preset
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(WindowInsets.statusBars.asPaddingValues())
                    ) {
                        // Top Bar with back arrow
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val tokens = com.homedatacenter.app.ui.glass.GlassTheme.tokens
                            IconButton(onClick = { finish() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回",
                                    tint = tokens.textColor
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "用户与权限管理",
                                color = tokens.textColor,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Content
                        UsersPageContent(
                            users = users,
                            isRefreshing = isRefreshing,
                            onRefresh = { loadUsers() },
                            onAddUserClick = { showCreateDialog = true },
                            onEditUserClick = { userToEdit = it },
                            onDeleteUserClick = { user -> confirmDeleteUser(user) },
                            onToggleAdminClick = { user, newAdminState ->
                                updateUserAdmin(user, newAdminState)
                            }
                        )
                    }

                    // Create User Dialog in Compose
                    if (showCreateDialog) {
                        CreateUserDialog(
                            isSubmitting = isDialogSubmitting,
                            onDismiss = { showCreateDialog = false },
                            onConfirm = { name, password, isAdmin ->
                                createUser(
                                    name = name,
                                    pass = password,
                                    isAdmin = isAdmin,
                                    onSubmittingChange = { isDialogSubmitting = it },
                                    onSuccess = { showCreateDialog = false }
                                )
                            }
                        )
                    }

                    // Edit User Dialog in Compose
                    userToEdit?.let { user ->
                        EditUserDialog(
                            user = user,
                            isSubmitting = isDialogSubmitting,
                            onDismiss = { userToEdit = null },
                            onConfirm = { newName, newIsAdmin ->
                                updateUser(
                                    user = user,
                                    newName = newName,
                                    newIsAdmin = newIsAdmin,
                                    onSubmittingChange = { isDialogSubmitting = it },
                                    onSuccess = { userToEdit = null }
                                )
                            }
                        )
                    }
                }
            }
        }

        loadUsers()
    }

    private fun loadUsers() {
        val token = container.prefsManager.token ?: return
        lifecycleScope.launch {
            _isRefreshing.value = true
            try {
                val users = withContext(Dispatchers.IO) {
                    container.getRepository().listUsers(token)
                }
                _users.value = users
            } catch (e: Exception) {
                Toast.makeText(this@UsersActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private fun createUser(
        name: String,
        pass: String,
        isAdmin: Boolean,
        onSubmittingChange: (Boolean) -> Unit,
        onSuccess: () -> Unit
    ) {
        val token = container.prefsManager.token ?: return
        onSubmittingChange(true)
        lifecycleScope.launch {
            try {
                val created = withContext(Dispatchers.IO) {
                    container.getRepository().createUserDetailed(
                        token,
                        name = name,
                        isAdmin = isAdmin,
                        password = pass
                    )
                }
                onSuccess()
                loadUsers()
                val idText = created?.id?.toString() ?: "未知"
                android.app.AlertDialog.Builder(this@UsersActivity)
                    .setTitle("用户创建成功")
                    .setMessage("用户名: $name\n用户 ID: $idText\n\n该成员登录时「用户 ID」栏需填写数字 $idText。")
                    .setPositiveButton("复制用户 ID") { _, _ ->
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("User ID", idText))
                        Toast.makeText(this@UsersActivity, "已复制用户 ID $idText", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("好的", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@UsersActivity, "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                onSubmittingChange(false)
            }
        }
    }

    private fun updateUser(
        user: User,
        newName: String,
        newIsAdmin: Boolean,
        onSubmittingChange: (Boolean) -> Unit,
        onSuccess: () -> Unit
    ) {
        val token = container.prefsManager.token ?: return
        onSubmittingChange(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    container.getRepository().updateUser(
                        token = token,
                        userId = user.id,
                        name = if (newName != user.name) newName else null,
                        isAdmin = if (newIsAdmin != user.isAdmin) newIsAdmin else null
                    )
                }
                onSuccess()
                Toast.makeText(this@UsersActivity, "更新成功", Toast.LENGTH_SHORT).show()
                loadUsers()
            } catch (e: Exception) {
                Toast.makeText(this@UsersActivity, "更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                onSubmittingChange(false)
            }
        }
    }

    private fun updateUserAdmin(user: User, isAdmin: Boolean) {
        val token = container.prefsManager.token ?: return
        val currentUserId = container.prefsManager.userId
        if (user.id == currentUserId && !isAdmin) {
            Toast.makeText(this, "无法取消自己的管理员权限", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    container.getRepository().updateUser(
                        token,
                        userId = user.id,
                        name = null,
                        isAdmin = isAdmin
                    )
                }
                Toast.makeText(this@UsersActivity, "已更新权限", Toast.LENGTH_SHORT).show()
                loadUsers()
            } catch (e: Exception) {
                Toast.makeText(this@UsersActivity, "更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDeleteUser(user: User) {
        val token = container.prefsManager.token ?: return
        val currentUserId = container.prefsManager.userId
        if (user.id == currentUserId) {
            Toast.makeText(this, "无法删除当前登录的管理员账户", Toast.LENGTH_SHORT).show()
            return
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("确认删除用户")
            .setMessage("确定要删除用户「${user.name}」(ID: ${user.id}) 吗？此操作无法撤销。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            container.getRepository().deleteUser(token, user.id)
                        }
                        Toast.makeText(this@UsersActivity, "已删除用户", Toast.LENGTH_SHORT).show()
                        loadUsers()
                    } catch (e: Exception) {
                        Toast.makeText(this@UsersActivity, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
