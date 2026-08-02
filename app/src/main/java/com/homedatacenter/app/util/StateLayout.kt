package com.homedatacenter.app.util

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.homedatacenter.app.R

/**
 * A custom FrameLayout that manages four display states:
 *   - Loading: centered progress indicator
 *   - Error: error icon + message + retry button
 *   - Empty: empty icon + message
 *   - Content: the actual content (children)
 *
 * Usage:
 *   <com.homedatacenter.app.util.StateLayout
 *       android:id="@+id/stateLayout"
 *       android:layout_width="match_parent"
 *       android:layout_height="match_parent">
 *       <!-- Your actual content here -->
 *   </com.homedatacenter.app.util.StateLayout>
 *
 * Then in code:
 *   stateLayout.showLoading()
 *   stateLayout.showError("无法连接到服务器") { loadData() }
 *   stateLayout.showEmpty("暂无数据")
 *   stateLayout.showContent()
 */
class StateLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var loadingView: FrameLayout? = null
    private var errorView: FrameLayout? = null
    private var emptyView: FrameLayout? = null

    private var onRetryCallback: (() -> Unit)? = null

    init {
        // Do not inflate overlay views here - they are created lazily
        // to avoid unnecessary layout passes on initial render.
    }

    /**
     * Show the loading state: hide content, show centered progress indicator.
     */
    fun showLoading() {
        hideAllOverlays()
        if (loadingView == null) {
            loadingView = LayoutInflater.from(context)
                .inflate(
                    R.layout.layout_state_loading,
                    this,
                    false
                ) as FrameLayout
            addView(loadingView)
        }
        loadingView?.visibility = VISIBLE
        // Hide actual content
        showContentInternal(false)
    }

    /**
     * Show the error state: hide content, show error icon + message + retry button.
     * @param message Error message to display
     * @param onRetry Callback invoked when the retry button is tapped
     */
    fun showError(message: String, onRetry: () -> Unit) {
        hideAllOverlays()
        if (errorView == null) {
            errorView = LayoutInflater.from(context)
                .inflate(
                    R.layout.layout_state_error,
                    this,
                    false
                ) as FrameLayout
            addView(errorView)

            // Wire retry button
            errorView?.findViewById<MaterialButton>(R.id.btnStateRetry)?.setOnClickListener {
                onRetryCallback?.invoke()
            }
        }
        errorView?.visibility = VISIBLE
        errorView?.findViewById<MaterialTextView>(R.id.tvStateError)?.text = message
        onRetryCallback = onRetry
        showContentInternal(false)
    }

    /**
     * Show the empty state: hide content, show empty icon + message.
     * @param message Empty state message to display
     */
    fun showEmpty(message: String) {
        hideAllOverlays()
        if (emptyView == null) {
            emptyView = LayoutInflater.from(context)
                .inflate(
                    R.layout.layout_state_empty,
                    this,
                    false
                ) as FrameLayout
            addView(emptyView)
        }
        emptyView?.visibility = VISIBLE
        emptyView?.findViewById<MaterialTextView>(R.id.tvStateEmpty)?.text = message
        showContentInternal(false)
    }

    /**
     * Show the actual content, hide all state overlays.
     */
    fun showContent() {
        hideAllOverlays()
        showContentInternal(true)
    }

    private fun hideAllOverlays() {
        loadingView?.visibility = GONE
        errorView?.visibility = GONE
        emptyView?.visibility = GONE
    }

    /**
     * Show or hide the actual child views (the content).
     */
    private fun showContentInternal(show: Boolean) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === loadingView || child === errorView || child === emptyView) {
                continue
            }
            child.visibility = if (show) VISIBLE else INVISIBLE
        }
    }
}