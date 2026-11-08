package com.homedatacenter.app.ui.ops

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.homedatacenter.app.HomeCenterApp
import com.homedatacenter.app.di.AppContainer

/**
 * OpsFragment — Host Fragment for OpsCenterScreen.
 *
 * Implements Work Order 5.3:
 * Hosts [OpsCenterScreen] via [ComposeView], integrating 100% Jetpack Compose
 * Liquid Glass Ops Center with System Dashboard, Pending Proposals approval,
 * and Copilot Chat driven by [OpsViewModel].
 */
class OpsFragment : Fragment() {

    private lateinit var container: AppContainer
    private lateinit var viewModel: OpsViewModel

    companion object {
        const val TAB_SYSTEM = 0
        const val TAB_SECURITY = 1
        const val TAB_SECURITY_AND_LOGS = 1
        const val TAB_PROPOSALS = 2
        const val TAB_COPILOT = 3

        // Backward compatibility aliases
        const val TAB_LOGS = 1

        @Volatile
        var targetSubTab: Int = TAB_SYSTEM
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        container = (requireActivity().application as HomeCenterApp).container
        val factory = OpsViewModelFactory(requireActivity().application, container)
        viewModel = ViewModelProvider(this, factory)[OpsViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel.selectTab(targetSubTab)

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                val selectedTab by viewModel.selectedTab.collectAsState()
                val toastMessage by viewModel.toastMessage.collectAsState()

                LaunchedEffect(selectedTab) {
                    targetSubTab = selectedTab.index
                }

                LaunchedEffect(toastMessage) {
                    toastMessage?.let { msg ->
                        showToast(msg)
                        viewModel.consumeToast()
                    }
                }

                OpsCenterScreen(
                    viewModel = viewModel,
                    onOpen1Panel = {
                        viewModel.launch1PanelConsole(requireContext())
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isAdded && !isHidden) {
            viewModel.selectTab(targetSubTab)
            viewModel.refreshAll(silent = true)
            viewModel.startAutoRefresh()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopAutoRefresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded) {
            viewModel.selectTab(targetSubTab)
            viewModel.refreshAll(silent = true)
            viewModel.startAutoRefresh()
        } else {
            viewModel.stopAutoRefresh()
        }
    }

    private fun showToast(message: String) {
        context?.let { ctx ->
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
    }
}
