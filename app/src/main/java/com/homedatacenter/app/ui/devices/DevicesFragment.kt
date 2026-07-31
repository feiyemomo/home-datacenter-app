package com.homedatacenter.app.ui.devices

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.Device
import com.homedatacenter.app.databinding.FragmentDevicesBinding
import com.homedatacenter.app.ui.main.MainActivity
import kotlinx.coroutines.launch

class DevicesFragment : Fragment() {

    private var _binding: FragmentDevicesBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: DeviceAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDevicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = DeviceAdapter { device -> showRevokeDialog(device) }
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadDevices() }

        setupScopeToggle()
        setupFab()

        loadDevices()
    }

    private fun setupScopeToggle() {
        val mainActivity = activity as? MainActivity ?: return
        val prefsManager = mainActivity.container.prefsManager
        if (!mainActivity.container.roleManager.isAdmin()) {
            // Non-admins keep the toggle hidden (default gone).
            return
        }
        binding.toggleDeviceScope.visibility = View.VISIBLE
        val scope = prefsManager.getDeviceScope()
        val initialBtn = if (scope == "all") R.id.btnScopeAll else R.id.btnScopeMine
        // Check before registering the listener so the initial
        // programmatic selection does not trigger a re-fetch.
        binding.toggleDeviceScope.check(initialBtn)
        binding.toggleDeviceScope.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newScope = if (checkedId == R.id.btnScopeAll) "all" else "mine"
            if (newScope == prefsManager.getDeviceScope()) return@addOnButtonCheckedListener
            prefsManager.setDeviceScope(newScope)
            // Invalidate the device cache so the next load fetches with
            // the new scope instead of returning the previous scope's data.
            prefsManager.lastDevicesFetchTime = 0L
            loadDevices()
        }
    }

    private fun setupFab() {
        binding.fabAddDevice.setOnClickListener {
            RegisterDeviceDialog().show(childFragmentManager, "register_device")
        }
    }

    /**
     * Public entry point for [RegisterDeviceDialog] to request a list
     * refresh after a device is created. Delegates to [loadDevices]
     * so the new device appears without a manual pull-to-refresh.
     */
    fun refreshDevices() {
        if (isAdded) loadDevices()
    }

    override fun onResume() {
        super.onResume()
        if (isAdded) {
            // Refresh the cached online device ids before rendering the
            // list. loadDevices() uses the freshest snapshot available
            // in prefsManager.cachedSystemStatus, which DashboardFragment
            // keeps warm via 5s polling. We kick off a refresh of the
            // system status in parallel so that the next render picks
            // up an authoritative onlineDeviceIds list.
            refreshSystemStatus()
            loadDevices()
        }
    }

    // v1.6.12: Fragment.hide/show (used by MainActivity's bottom-nav
    // tab switching) does NOT re-trigger onResume. Without this
    // override the devices list would only load once on first add
    // and never refresh when the user switches back to the devices
    // tab. Now returning to the tab re-fetches the latest device
    // list + online status snapshot.
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded) {
            refreshSystemStatus()
            loadDevices()
        }
    }

    private fun refreshSystemStatus() {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return
        lifecycleScope.launch {
            try {
                mainActivity.container.getRepository().getSystemStatus(
                    token,
                    useCache = false,
                    refreshCache = true,
                )
                activity?.runOnUiThread { applyOnlineSnapshot() }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Push the latest online device ids from the cached SystemStatus
     * into the adapter so the status dots reflect reality.
     */
    private fun applyOnlineSnapshot() {
        val mainActivity = activity as? MainActivity ?: return
        val raw = mainActivity.container.prefsManager.cachedSystemStatus ?: return
        val ids: List<Long> = try {
            mainActivity.container.getRepository().decodeSystemStatus(raw).onlineDeviceIds
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        adapter.onlineDeviceIds = ids.toSet()
    }

    private fun loadDevices() {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return

        val hasCache = !mainActivity.container.prefsManager.cachedDevices.isNullOrEmpty()
        if (!hasCache) {
            showLoading(true)
        }

        lifecycleScope.launch {
            try {
                val devices = mainActivity.container.getRepository().listDevices(
                    token,
                    scope = mainActivity.container.prefsManager.getDeviceScope(),
                    useCache = true
                )
                adapter.submitList(devices)
                applyOnlineSnapshot()
                showEmpty(devices.isEmpty())
            } catch (e: Exception) {
                showEmpty(true)
            } finally {
                showLoading(false)
                binding.swipeRefresh.isRefreshing = false
            }

            refreshInBackground(token)
        }
    }

    private fun refreshInBackground(token: String) {
        lifecycleScope.launch {
            val mainActivity = activity as? MainActivity ?: return@launch
            try {
                val devices = mainActivity.container.getRepository().listDevices(
                    token,
                    scope = mainActivity.container.prefsManager.getDeviceScope(),
                    useCache = false,
                    refreshCache = true
                )
                activity?.runOnUiThread {
                    adapter.submitList(devices)
                    applyOnlineSnapshot()
                    showEmpty(devices.isEmpty())
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun showRevokeDialog(device: Device) {
        val context = context ?: return
        AlertDialog.Builder(context)
            .setTitle(R.string.confirm_revoke_title)
            .setMessage(R.string.confirm_revoke_message)
            .setPositiveButton(R.string.btn_confirm) { _, _ -> revokeDevice(device) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun revokeDevice(device: Device) {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return

        lifecycleScope.launch {
            try {
                mainActivity.container.getRepository().revokeDevice(token, device.id)
                loadDevices()
                refreshSystemStatus()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show && adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    private fun showEmpty(show: Boolean) {
        binding.tvEmpty.visibility = if (show) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (show) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
