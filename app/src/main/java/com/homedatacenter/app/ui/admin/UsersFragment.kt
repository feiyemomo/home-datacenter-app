package com.homedatacenter.app.ui.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.User
import com.homedatacenter.app.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Admin-only user management fragment, shown as a tab in [com.homedatacenter.app.ui.main.MainActivity].
 * 100% Jetpack Compose declarative architecture.
 */
class UsersFragment : Fragment() {

    private lateinit var container: AppContainer

    private val _users = MutableStateFlow<List<User>>(emptyList())
    private val _isRefreshing = MutableStateFlow(false)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        this.container = (requireContext().applicationContext as HomeCenterApp).container

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                val users by _users.collectAsState()
                val isRefreshing by _isRefreshing.collectAsState()

                var showCreateDialog by remember { mutableStateOf(false) }
                var userToEdit by remember { mutableStateOf<User?>(null) }
                var isDialogSubmitting by remember { mutableStateOf(false) }

                Box(modifier = Modifier.fillMaxSize()) {
                    UsersPageContent(
                        users = users,
                        isRefreshing = isRefreshing,
                        onRefresh = { loadUsers() },
                        onAddUserClick = { showCreateDialog = true },
                        onEditUserClick = { user -> userToEdit = user },
                        onDeleteUserClick = { user -> confirmDeleteUser(user) },
                        onToggleAdminClick = { user, newAdminState ->
                            updateUserAdmin(user, newAdminState)
                        }
                    )

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
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadUsers()
    }

    override fun onResume() {
        super.onResume()
        loadUsers()
    }

    private fun loadUsers() {
        val token = container.prefsManager.token ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            _isRefreshing.value = true
            try {
                val users = withContext(Dispatchers.IO) {
                    container.getRepository().listUsers(token)
                }
                _users.value = users
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("加载失败: ${e.message}")
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
        viewLifecycleOwner.lifecycleScope.launch {
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
                val ctx = context ?: return@launch
                android.app.AlertDialog.Builder(ctx)
                    .setTitle("用户创建成功")
                    .setMessage("用户名: $name\n用户 ID: $idText\n\n该成员登录时「用户 ID」栏需填写数字 $idText。")
                    .setPositiveButton("复制用户 ID") { _, _ ->
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("User ID", idText))
                        toast("已复制用户 ID $idText")
                    }
                    .setNegativeButton("好的", null)
                    .show()
            } catch (e: Exception) {
                toast("创建失败: ${e.message}")
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
        viewLifecycleOwner.lifecycleScope.launch {
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
                toast("更新成功")
                loadUsers()
            } catch (e: Exception) {
                toast("更新失败: ${e.message}")
            } finally {
                onSubmittingChange(false)
            }
        }
    }

    private fun updateUserAdmin(user: User, isAdmin: Boolean) {
        val token = container.prefsManager.token ?: return
        val currentUserId = container.prefsManager.userId
        if (user.id == currentUserId && !isAdmin) {
            toast("无法取消自己的管理员权限")
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    container.getRepository().updateUser(
                        token,
                        userId = user.id,
                        name = null,
                        isAdmin = isAdmin
                    )
                }
                toast("已更新权限")
                loadUsers()
            } catch (e: Exception) {
                toast("更新失败: ${e.message}")
            }
        }
    }

    private fun confirmDeleteUser(user: User) {
        val ctx = context ?: return
        val token = container.prefsManager.token ?: return
        val currentUserId = container.prefsManager.userId
        if (user.id == currentUserId) {
            toast("无法删除当前登录的管理员账户")
            return
        }

        android.app.AlertDialog.Builder(ctx)
            .setTitle("确认删除用户")
            .setMessage("确定要删除用户「${user.name}」(ID: ${user.id}) 吗？此操作无法撤销。")
            .setPositiveButton("删除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            container.getRepository().deleteUser(token, user.id)
                        }
                        toast("已删除用户")
                        loadUsers()
                    } catch (e: Exception) {
                        toast("删除失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) {
        context?.let { ctx ->
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
