package com.homedatacenter.app.ui.admin

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.User
import com.homedatacenter.app.databinding.ActivityUsersBinding
import com.homedatacenter.app.di.AppContainer
import kotlinx.coroutines.launch

/**
 * Admin-only user management fragment, shown as a tab in [com.homedatacenter.app.ui.main.MainActivity].
 *
 * Shows the full user list (GET /api/v1/user), lets the admin create
 * new users (POST /api/v1/user), rename / toggle admin (PUT), and
 * delete (DELETE).
 *
 * Self-guard: the backend rejects self-delete and self-demote, so we
 * surface the error message verbatim. The last-admin guard is also
 * enforced by the server.
 */
class UsersFragment : Fragment() {

    private var _binding: ActivityUsersBinding? = null
    private val binding get() = _binding!!
    private lateinit var container: AppContainer
    private lateinit var adapter: UserListAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityUsersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        this.container = (requireContext().applicationContext as HomeCenterApp).container

        // No back button since it's a tab
        binding.toolbar.setNavigationIcon(null)
        binding.toolbar.title = getString(R.string.users_title)
        binding.fabAddUser.setOnClickListener { showCreateUserDialog() }
        binding.swipeRefresh.setOnRefreshListener { loadUsers() }

        adapter = UserListAdapter { user -> showEditUserDialog(user) }
        binding.rvUsers.layoutManager = LinearLayoutManager(requireContext())
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

        val dialogContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }
        val etName = EditText(requireContext()).apply { hint = getString(R.string.user_name_label) }
        val cbAdmin = CheckBox(requireContext()).apply { text = getString(R.string.user_admin_label) }
        dialogContainer.apply {
            addView(etName)
            addView(cbAdmin)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.users_create)
            .setView(dialogContainer)
            .setPositiveButton(R.string.action_create) { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isEmpty()) {
                    toast("请填写用户名")
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        container.getRepository().createUser(
                            token,
                            name = name,
                            isAdmin = cbAdmin.isChecked,
                        )
                        toast("用户已创建")
                        loadUsers()
                    } catch (e: Exception) {
                        toast("创建失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showEditUserDialog(user: User) {
        val token = container.prefsManager.token ?: return
        val currentUserId = container.prefsManager.userId

        val dialogContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }
        val etName = EditText(requireContext()).apply {
            setText(user.name)
            hint = getString(R.string.user_name_label)
        }
        val cbAdmin = CheckBox(requireContext()).apply {
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

        AlertDialog.Builder(requireContext())
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

        AlertDialog.Builder(requireContext())
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
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}