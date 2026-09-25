package com.homedatacenter.app.ui.admin

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.User
import com.homedatacenter.app.databinding.ActivityUsersBinding
import com.homedatacenter.app.databinding.DialogCreateUserBinding
import com.homedatacenter.app.databinding.DialogEditUserBinding
import com.homedatacenter.app.di.AppContainer
import kotlinx.coroutines.launch

/**
 * Admin-only user management screen.
 *
 * Shows the full user list (GET /api/v1/user), lets the admin create
 * new users (POST /api/v1/user), rename / toggle admin (PUT), and
 * delete (DELETE).
 *
 * Self-guard: the backend rejects self-delete and self-demote, so we
 * surface the error message verbatim. The last-admin guard is also
 * enforced by the server.
 */
class UsersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUsersBinding
    private lateinit var container: AppContainer
    private lateinit var adapter: UserListAdapter

    companion object {
        private const val MENU_ADD_USER = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUsersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        container = (application as HomeCenterApp).container

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.menu.add(Menu.NONE, MENU_ADD_USER, Menu.NONE, R.string.users_create)
            .setIcon(R.drawable.ic_add)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_ADD_USER -> {
                    showCreateUserDialog()
                    true
                }
                else -> false
            }
        }
        binding.swipeRefresh.setOnRefreshListener { loadUsers() }

        adapter = UserListAdapter { user -> showEditUserDialog(user) }
        binding.rvUsers.layoutManager = LinearLayoutManager(this)
        binding.rvUsers.adapter = adapter

        loadUsers()
    }

    private fun loadUsers() {
        val token = container.prefsManager.token ?: return
        lifecycleScope.launch {
            try {
                val users = container.getRepository().listUsers(token)
                adapter.submitList(users)
                binding.tvEmpty.visibility = if (users.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                toast("加载失败: ${e.message}")
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun showCreateUserDialog() {
        val token = container.prefsManager.token ?: return
        val dialogBinding = DialogCreateUserBinding.inflate(layoutInflater)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnConfirm.setOnClickListener {
            val name = dialogBinding.etUserName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                toast("请填写用户名")
                return@setOnClickListener
            }
            val password = dialogBinding.etPassword.text?.toString().orEmpty()
            if (password.isEmpty()) {
                toast("请设置密码")
                return@setOnClickListener
            }
            if (!Regex("^[A-Za-z0-9]+$").matches(password)) {
                toast("密码仅限字母和数字")
                return@setOnClickListener
            }

            dialog.dismiss()
            lifecycleScope.launch {
                try {
                    container.getRepository().createUser(
                        token,
                        name = name,
                        isAdmin = dialogBinding.switchIsAdmin.isChecked,
                        password = password,
                    )
                    loadUsers()
                    toast("用户已创建（登录凭据为所设密码）")
                } catch (e: Exception) {
                    toast("创建失败: ${e.message}")
                }
            }
        }

        dialog.show()
    }

    private fun showEditUserDialog(user: User) {
        val token = container.prefsManager.token ?: return
        val currentUserId = container.prefsManager.userId
        val dialogBinding = DialogEditUserBinding.inflate(layoutInflater)

        dialogBinding.etUserName.setText(user.name)
        dialogBinding.switchIsAdmin.isChecked = user.isAdmin

        val isSelf = user.id == currentUserId
        if (isSelf) {
            dialogBinding.switchIsAdmin.isEnabled = false
            dialogBinding.tvAdminTitle.text = "${getString(R.string.user_admin_label)} (当前用户)"
            dialogBinding.tvAdminDesc.text = "不可降级当前登录的管理员账号"
            dialogBinding.btnDelete.isEnabled = false
            dialogBinding.btnDelete.alpha = 0.4f
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnDelete.setOnClickListener {
            dialog.dismiss()
            confirmDeleteUser(user)
        }

        dialogBinding.btnSave.setOnClickListener {
            val newName = dialogBinding.etUserName.text?.toString()?.trim().orEmpty()
            if (newName.isEmpty()) {
                toast("用户名不能为空")
                return@setOnClickListener
            }
            val isAdminChanged = dialogBinding.switchIsAdmin.isChecked != user.isAdmin

            dialog.dismiss()
            lifecycleScope.launch {
                try {
                    container.getRepository().updateUser(
                        token,
                        userId = user.id,
                        name = if (newName != user.name) newName else null,
                        isAdmin = if (isAdminChanged) dialogBinding.switchIsAdmin.isChecked else null,
                    )
                    toast("已更新")
                    loadUsers()
                } catch (e: Exception) {
                    toast("更新失败: ${e.message}")
                }
            }
        }

        dialog.show()
    }

    private fun confirmDeleteUser(user: User) {
        val token = container.prefsManager.token ?: return
        val currentUserId = container.prefsManager.userId

        if (user.id == currentUserId) {
            toast(getString(R.string.user_self_delete_guard))
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.user_action_delete)
            .setMessage(getString(R.string.user_delete_confirm) + "\n\n" + user.name)
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                lifecycleScope.launch {
                    try {
                        val deleted = container.getRepository().deleteUser(token, user.id)
                        toast("已删除 (吊销 $deleted 个设备)")
                        loadUsers()
                    } catch (e: Exception) {
                        val msg = e.message.orEmpty()
                        if (msg.contains("last", ignoreCase = true)) {
                            toast(getString(R.string.user_last_admin_guard))
                        } else {
                            toast("删除失败: $msg")
                        }
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
