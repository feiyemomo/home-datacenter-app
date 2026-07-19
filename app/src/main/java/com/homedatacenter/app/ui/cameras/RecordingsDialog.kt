package com.homedatacenter.app.ui.cameras

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.data.model.Recording
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.databinding.DialogRecordingsBinding
import com.homedatacenter.app.databinding.ItemRecordingBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class RecordingsDialog(
    context: Context,
    private val camera: Camera,
    private val container: AppContainer
) : Dialog(context, R.style.FullScreenDialog) {

    private lateinit var binding: DialogRecordingsBinding
    private lateinit var adapter: RecordingAdapter
    private val baseUrl = container.getApiBaseUrl()
    private val token = container.prefsManager.token
    private var player: ExoPlayer? = null

    init {
        binding = DialogRecordingsBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)
        setupRecyclerView()
        loadRecordings()
        binding.toolbar.setNavigationOnClickListener { dismiss() }
        binding.toolbar.title = "${camera.name} - 录像"
    }

    private fun setupRecyclerView() {
        adapter = RecordingAdapter(
            baseUrl = baseUrl,
            token = token,
            onPlayRecording = { recording -> playRecording(recording) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
    }

    private fun loadRecordings() {
        binding.progressBar.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
        binding.tvEmpty.visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val authHeader = if (!token.isNullOrEmpty()) "Bearer $token" else ""
                android.util.Log.d("RecordingsDialog",
                    "Fetching recordings for camera ${camera.id} (name='${camera.name}')")
                val resp = container.getApi().listRecordings(authHeader, camera.id)
                android.util.Log.d("RecordingsDialog",
                    "API response: code=${resp.code}, message='${resp.message}', data=${resp.data}")

                val recordings = if (resp.isSuccess) {
                    val decoded = resp.decodeData<List<Recording>>()
                    android.util.Log.d("RecordingsDialog",
                        "Decoded ${decoded?.size ?: 0} recordings")
                    decoded ?: emptyList()
                } else {
                    android.util.Log.w("RecordingsDialog",
                        "API returned error: code=${resp.code}, message='${resp.message}'")
                    emptyList()
                }

                withContext(Dispatchers.Main) {
                    adapter.submitList(recordings)
                    binding.progressBar.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    binding.tvEmpty.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
                    if (recordings.isEmpty()) {
                        binding.tvEmpty.text = if (resp.isSuccess) "暂无录像" else "加载失败: ${resp.message}"
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("RecordingsDialog", "Exception loading recordings: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.tvEmpty.text = "加载失败: ${e.message}"
                }
            }
        }
    }

    private fun playRecording(recording: Recording) {
        val url = buildRecordingUrl(recording.id)
        if (url.isEmpty()) return

        android.util.Log.d("RecordingsDialog", "Playing recording ${recording.id} from $url")

        binding.videoContainer.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE

        player?.release()
        player = ExoPlayer.Builder(context).build().apply {
            val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
                setConnectTimeoutMs(15000)
                setReadTimeoutMs(60000)
                if (!token.isNullOrEmpty()) {
                    setDefaultRequestProperties(mutableMapOf(
                        "Authorization" to "Bearer $token",
                        "Cookie" to "home_token=$token"
                    ))
                }
            }
            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(url))
            setMediaSource(mediaSource)
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    binding.progressPlayer.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }
                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.e("RecordingsDialog", "Player error: ${error.message}")
                }
            })
            prepare()
        }
        binding.playerView.player = player
        binding.btnBack.setOnClickListener {
            player?.release()
            player = null
            binding.videoContainer.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
    }

    private fun buildRecordingUrl(recId: Long): String {
        if (baseUrl.isNullOrBlank()) return ""
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return "${base}api/v1/cameras/${camera.id}/recordings/$recId/file"
    }

    override fun dismiss() {
        player?.release()
        player = null
        super.dismiss()
    }

    class RecordingAdapter(
        private val baseUrl: String?,
        private val token: String?,
        private val onPlayRecording: (Recording) -> Unit
    ) : ListAdapter<Recording, RecordingAdapter.RecordingViewHolder>(DiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordingViewHolder {
            val b = ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return RecordingViewHolder(b)
        }

        override fun onBindViewHolder(holder: RecordingViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class RecordingViewHolder(private val binding: ItemRecordingBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(recording: Recording) {
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                try {
                    val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.parse(recording.startAt)
                    if (date != null) {
                        binding.tvTime.text = fmt.format(date)
                    } else {
                        binding.tvTime.text = recording.startAt
                    }
                } catch (_: Exception) {
                    binding.tvTime.text = recording.startAt
                }

                val minutes = recording.durationSeconds / 60
                val seconds = recording.durationSeconds % 60
                binding.tvDuration.text = String.format("%02d:%02d", minutes, seconds)
                binding.tvSize.text = recording.sizeHuman

                binding.btnPlay.setOnClickListener { onPlayRecording(recording) }
            }
        }

        class DiffCallback : DiffUtil.ItemCallback<Recording>() {
            override fun areItemsTheSame(oldItem: Recording, newItem: Recording) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Recording, newItem: Recording) = oldItem == newItem
        }
    }
}
