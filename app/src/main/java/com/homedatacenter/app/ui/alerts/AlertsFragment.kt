package com.homedatacenter.app.ui.alerts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.AlertListData
import com.homedatacenter.app.databinding.FragmentAlertsBinding
import com.homedatacenter.app.ui.main.MainActivity
import kotlinx.coroutines.launch

class AlertsFragment : Fragment() {

    private var _binding: FragmentAlertsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: AlertListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlertsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainActivity = activity as? MainActivity ?: return
        val baseUrl = mainActivity.container.getApiBaseUrl()
        val token = mainActivity.container.prefsManager.token
        val okHttpClient = mainActivity.container.okHttpClient

        adapter = AlertListAdapter(
            baseUrl = baseUrl,
            token = token,
            okHttpClient = okHttpClient,
            onSnapshotClick = { alert ->
                val dialog = AlertSnapshotDialogFragment.newInstance(
                    alert = alert,
                    baseUrl = baseUrl,
                    token = token,
                    okHttpClient = okHttpClient
                )
                dialog.show(parentFragmentManager, AlertSnapshotDialogFragment.TAG)
            },
            onJumpCamera = { alert ->
                (activity as? com.homedatacenter.app.ui.main.MainActivity)?.let {
                    it.binding.bottomNav.selectedItemId = R.id.nav_cameras
                }
            },
            onRowClick = null
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadAlerts() }
        loadAlerts()
    }

    override fun onResume() {
        super.onResume()
        if (isAdded && this::adapter.isInitialized) {
            loadAlerts()
        }
    }

    private fun loadAlerts() {
        val mainActivity = activity as? MainActivity ?: return
        val token = mainActivity.container.prefsManager.token ?: return

        lifecycleScope.launch {
            try {
                val auth = "Bearer $token"
                val resp = mainActivity.container.getApi().listAlerts(auth, limit = 50)
                val alerts = if (resp.isSuccess) {
                    resp.decodeData<AlertListData>()?.alerts ?: emptyList()
                } else {
                    emptyList()
                }
                adapter.submitList(alerts)
                showEmpty(alerts.isEmpty())
            } catch (_: Exception) {
                showEmpty(true)
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
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
