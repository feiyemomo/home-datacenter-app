package com.homedatacenter.app.ui.admin

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.User
import com.homedatacenter.app.databinding.ActivityUsersBinding
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

        val dialogContainer = LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }
        val etName = EditText(this).apply { hint = getString(R.string.user_name_label) }
        val etPassword = EditText(this).apply {
            hint = "密码（仅限字母和数字）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val cbAdmin = CheckBox(this).apply { text = getString(R.string.user_admin_label) }
        dialogContainer.apply {
            addView(etName)
            addView(etPassword)
            addView(cbAdmin)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.users_create)
            .setView(dialogContainer)
            .setPositiveButton(R.string.action_create) { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isEmpty()) {
                    toast("请填写用户名")
                    return@setPositiveButton
                }
        val password = etPassword.text.toString()
        if (password.isEmpty()) {
            toast("请设置密码")
            return@setPositiveButton
        }
        if (!Regex("^[A-Za-z0-9]+$").matches(password)) {
            toast("密码仅限字母和数字")
            return@setPositiveButton
        }
                lifecycleScope.launch {
                    try {
                        val accessKey = container.getRepository().createUser(
                            token,
                            name = name,
                            isAdmin = cbAdmin.isChecked,
                password = password,
                        )
                        loadUsers()
                toast("用户已创建（登录凭据为所设密码）")
                    } catch (e: Exception) {
                        toast("创建失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

//     private fun showAccessKeyDialog(accessKey: String) {
//         val container = LinearLayout(this).apply {
//             orientation = LinearLayout.VERTICAL
//             setPadding(50, 30, 50, 10)
//         }
//         val tvLabel = TextView(this).apply {
//             text = getString(R.string.device_register_access_key_label)
//             textSize = 14f
//         }
//         val tvKey = TextView(this).apply {
//             text = accessKey
//             textSize = 16f
//             setTextColor(
//                 resources.getColor(android.R.color.holo_red_dark, theme)
//             )
//             setPadding(0, 16, 0, 16)
//             typeface = android.graphics.Typeface.MONOSPACE
//         }
//         container.apply {
//             addView(tvLabel)
//             addView(tvKey)
//         }
// 
//         AlertDialog.Builder(this)
//             .setTitle(R.string.users_create)
//             .setView(container)
//             .setPositiveButton(R.string.device_register_copied) { _, _ ->
//                 copyToClipboard(accessKey)
//                 toast("已复制")
//             }
//             .setNeutralButton(R.string.btn_confirm) { _, _ -> }
//             .show()
//     }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("access_key", text))
    }

    private fun showEditUserDialog(user: User) {
        val token = container.prefsManager.token ?: return
        val currentUserId = container.prefsManager.userId

        val dialogContainer = LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }
        val etName = EditText(this).apply {
            setText(user.name)
            hint = getString(R.string.user_name_label)
        }
        val cbAdmin = CheckBox(this).apply {
            text = getString(R.string.user_admin_label)
            isChecked = user.isAdmin
            // Disable if editing self — backend rejects self-demote.
            if (user.id == currentUserId) {
                isEnabled = false
                text = getString(R.string.user_admin_label) + " (当前用户)"
            }
        }
        dialogContainer.apply {
            addView(etName)
            addView(cbAdmin)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.user_action_edit)
            .setView(dialogContainer)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val newName = etName.text.toString().trim()
                if (newName.isEmpty()) {
                    toast("用户名不能为空")
                    return@setPositiveButton
                }
                val isAdminChanged = cbAdmin.isChecked != user.isAdmin
                lifecycleScope.launch {
                    try {
                        container.getRepository().updateUser(
                            token,
                            userId = user.id,
                            name = if (newName != user.name) newName else null,
                            isAdmin = if (isAdminChanged) cbAdmin.isChecked else null,
                        )
                        toast("已更新")
                        loadUsers()
                    } catch (e: Exception) {
                        toast("更新失败: ${e.message}")
                    }
                }
            }
            .setNeutralButton(R.string.user_action_delete) { _, _ ->
                confirmDeleteUser(user)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
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
