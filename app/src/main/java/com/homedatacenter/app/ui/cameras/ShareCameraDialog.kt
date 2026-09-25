package com.homedatacenter.app.ui.cameras

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.CameraShare
import com.homedatacenter.app.data.model.User
import com.homedatacenter.app.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Dialog that manages camera shares for a single camera. Lists the
 * users the camera is currently shared with, lets the caller revoke
 * a share, and exposes an "add share" action that opens a user picker
 * (or falls back to a numeric user-id input when the user list API
 * is unavailable, e.g. for non-admin camera owners).
 *
 * Visibility: the host [CameraDetailActivity] only opens this dialog
 * for admins or the camera's owner — the dialog itself does not
 * re-check the role, but the backend still enforces authorization
 * on every share/unshare/list call.
 *
 * Follows the same Dialog (not DialogFragment) pattern used by
 * [RegisterCameraDialog], [RecordingsDialog] and [AlertsDialog] in
 * this package: constructor-injected [AppContainer], own
 * [CoroutineScope] (Main dispatcher + SupervisorJob), and a custom
 * layout inflated via [R.layout.dialog_share_camera].
 *
 * States handled:
 *  - Loading: [R.id.progressBar] visible, list hidden.
 *  - Empty:   [R.id.tvEmpty] visible when the shares list is empty.
 *  - Error:   [R.id.tvError] visible with the failure message.
 *  - Loaded:  [R.id.rvShares] visible with the [ShareListAdapter].
 */
class ShareCameraDialog(
    context: Context,
    private val container: AppContainer,
    private val cameraId: Long,
) : Dialog(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var rvShares: RecyclerView
    private lateinit var progressBar: View
    private lateinit var tvEmpty: View
    private lateinit var tvError: TextView
    private lateinit var btnAddShare: View
    private lateinit var btnClose: View

    private val shareAdapter = ShareListAdapter(
        onUnshare = { share -> confirmUnshare(share) },
        onTogglePtz = { share, canPtz -> updateSharePtz(share, canPtz) },
        userNameFor = { userId -> userNameFor(userId) },
    )

    // Best-effort user-id → name map. Populated from listUsers when
    // the caller is an admin; stays empty for non-admin owners, in
    // which case the adapter falls back to "用户 #ID".
    private var users: List<User> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_share_camera)
        setTitle(R.string.camera_share_title)

        rvShares = findViewById(R.id.rvShares)
        progressBar = findViewById(R.id.progressBar)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvError = findViewById(R.id.tvError)
        btnAddShare = findViewById(R.id.btnAddShare)
        btnClose = findViewById(R.id.btnClose)

        rvShares.layoutManager = LinearLayoutManager(context)
        rvShares.adapter = shareAdapter

        btnClose.setOnClickListener { dismiss() }
        btnAddShare.setOnClickListener { showAddSharePicker() }

        // Make the dialog wider so the row layout breathes.
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
        )

        loadShares()
    }

    // android.app.Dialog has no onDismiss/onCancel override — onStop() is
    // the lifecycle callback fired when the dialog is dismissed. Cancel the
    // Scope here so no coroutine outlives the dialog (leak / UI-after-destroy).
    override fun onStop() {
        super.onStop()
        scope.cancel()
    }

    // --- Share list loading ---

    private fun loadShares() {
        val token = container.prefsManager.token ?: return
        showLoading(true)
        scope.launch {
            try {
                val shares = container.getRepository().listShares(token, cameraId)
                // Best-effort: fetch the user list in parallel so we
                // can resolve user_id → name in the adapter. Failure
                // is non-fatal — non-admin owners simply see "用户 #ID".
                if (users.isEmpty()) {
                    users = try {
                        container.getRepository().listUsers(token)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                showShares(shares)
            } catch (e: Exception) {
                showError("加载失败: ${e.message}")
            }
        }
    }

    private fun showShares(shares: List<CameraShare>) {
        showLoading(false)
        tvError.visibility = View.GONE
        shareAdapter.submit(shares)
        tvEmpty.visibility = if (shares.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) {
            rvShares.visibility = View.GONE
            tvEmpty.visibility = View.GONE
            tvError.visibility = View.GONE
        } else {
            rvShares.visibility = View.VISIBLE
        }
    }

    private fun showError(message: String) {
        showLoading(false)
        shareAdapter.submit(emptyList())
        tvEmpty.visibility = View.GONE
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    private fun userNameFor(userId: Long): String? =
        users.firstOrNull { it.id == userId }?.name

    // --- Add share (user picker) ---

    /**
     * Open a picker for selecting a user to share with. Prefers the
     * user list (admin-only) — falls back to a numeric EditText when
     * [users] is empty (e.g. non-admin camera owner, or listUsers
     * returned 403). The owner and already-shared users are excluded
     * from the picker.
     */
    private fun showAddSharePicker() {
        val currentUserId = container.prefsManager.userId
        val alreadySharedIds = shareAdapter.current().map { it.userId }.toSet()

        if (users.isEmpty()) {
            // Couldn't load the user list (non-admin owner). Fall
            // back to a manual user-id input.
            showManualUserIdInput()
            return
        }

        val candidates = users.filter {
            it.id != currentUserId && it.id !in alreadySharedIds
        }
        if (candidates.isEmpty()) {
            toast("没有可共享的其他用户")
            return
        }

        val labels = candidates.map { user ->
            buildString {
                append(user.name)
                if (user.isAdmin) append("  (管理员)")
            }
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(R.string.camera_share_add)
            .setItems(labels) { _, which ->
                val picked = candidates[which]
                showConfirmAddShareDialog(picked)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showConfirmAddShareDialog(user: User) {
        val cbPtz = android.widget.CheckBox(context).apply {
            text = "同时允许控制云台 (PTZ)"
            isChecked = false
            setPadding(8, 8, 8, 8)
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 10)
            addView(TextView(context).apply {
                text = "确定将摄像头分享给「${user.name}」？"
                textSize = 15f
                setTextColor(context.getColor(R.color.text_primary))
            })
            addView(cbPtz)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.camera_share_add)
            .setView(layout)
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                addShare(user.id, cbPtz.isChecked)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /**
     * Fallback picker for non-admin camera owners: prompts for a
     * numeric user id and shares with that user.
     */
    private fun showManualUserIdInput() {
        val input = EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "用户 ID"
        }
        val cbPtz = android.widget.CheckBox(context).apply {
            text = "同时允许控制云台 (PTZ)"
            isChecked = false
        }
        val container2 = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
            addView(input)
            addView(cbPtz)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.camera_share_add)
            .setView(container2)
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                val uid = input.text.toString().trim().toLongOrNull()
                if (uid == null || uid <= 0L) {
                    toast("请输入有效的用户 ID")
                    return@setPositiveButton
                }
                addShare(uid, cbPtz.isChecked)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun addShare(userId: Long, canPtz: Boolean = false) {
        val token = container.prefsManager.token ?: return
        scope.launch {
            try {
                container.getRepository().shareCamera(token, cameraId, userId, canPtz)
                toast("已共享")
                loadShares()
            } catch (e: Exception) {
                toast("共享失败: ${e.message}")
            }
        }
    }

    private fun updateSharePtz(share: CameraShare, canPtz: Boolean) {
        val token = container.prefsManager.token ?: return
        scope.launch {
            try {
                container.getRepository().shareCamera(token, cameraId, share.userId, canPtz)
                toast(if (canPtz) "已开启云台控制权限" else "已关闭云台控制权限")
                loadShares()
            } catch (e: Exception) {
                toast("更新权限失败: ${e.message}")
                loadShares()
            }
        }
    }

    // --- Unshare ---

    private fun confirmUnshare(share: CameraShare) {
        val name = userNameFor(share.userId) ?: "用户 #${share.userId}"
        AlertDialog.Builder(context)
            .setTitle(R.string.camera_share_revoke)
            .setMessage("取消与 \"$name\" 的共享？")
            .setPositiveButton(R.string.btn_confirm) { _, _ -> unshare(share) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun unshare(share: CameraShare) {
        val token = container.prefsManager.token ?: return
        scope.launch {
            try {
                container.getRepository().unshareCamera(token, cameraId, share.userId)
                toast("已取消共享")
                loadShares()
            } catch (e: Exception) {
                toast("取消共享失败: ${e.message}")
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    // --- RecyclerView adapter ---

    private class ShareListAdapter(
        private val onUnshare: (CameraShare) -> Unit,
        private val onTogglePtz: (CameraShare, Boolean) -> Unit,
        private val userNameFor: (Long) -> String?,
    ) : RecyclerView.Adapter<ShareListAdapter.ShareVH>() {

        private val items = mutableListOf<CameraShare>()

        fun submit(list: List<CameraShare>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun current(): List<CameraShare> = items.toList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShareVH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_share_camera, parent, false)
            return ShareVH(view)
        }

        override fun onBindViewHolder(holder: ShareVH, position: Int) {
            val item = items[position]
            holder.bind(
                share = item,
                userNameFor = userNameFor,
                onTogglePtz = { canPtz -> onTogglePtz(item, canPtz) },
                onUnshare = { onUnshare(item) },
            )
        }

        override fun getItemCount(): Int = items.size

        private class ShareVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName: TextView = itemView.findViewById(R.id.tvUserName)
            private val tvMeta: TextView = itemView.findViewById(R.id.tvUserMeta)
            private val switchPtz: com.google.android.material.switchmaterial.SwitchMaterial = itemView.findViewById(R.id.switchPtz)
            private val btnUnshare: View = itemView.findViewById(R.id.btnUnshare)

            fun bind(
                share: CameraShare,
                userNameFor: (Long) -> String?,
                onTogglePtz: (Boolean) -> Unit,
                onUnshare: () -> Unit,
            ) {
                val name = userNameFor(share.userId) ?: "用户 #${share.userId}"
                tvName.text = name
                tvMeta.text = "ID: ${share.userId} • 云台: ${if (share.canPtz) "已开启" else "未授权"}"
                switchPtz.setOnCheckedChangeListener(null)
                switchPtz.isChecked = share.canPtz
                switchPtz.setOnCheckedChangeListener { _, isChecked ->
                    onTogglePtz(isChecked)
                }
                btnUnshare.setOnClickListener { onUnshare() }
            }
        }
    }
}
