package com.homedatacenter.app.ui.cameras

import android.app.Dialog
import android.app.DatePickerDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
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
import com.google.android.exoplayer2.MediaMetadata
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.data.model.Recording
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.databinding.DialogRecordingsBinding
import com.homedatacenter.app.databinding.ItemRecordingBinding
import com.homedatacenter.app.util.ExoPlayerRendererFactory
import com.homedatacenter.app.util.PlayerFullscreenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
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
    private var fullscreenHelper: PlayerFullscreenHelper? = null

    // v1.5.10: virtual pagination state. The backend's
    // /api/v1/cameras/:id/recordings endpoint returns ALL 7 days of
    // 60s-aggregated buckets in one shot (~10080 entries) — loading
    // them into the RecyclerView at once causes a noticeable hitch
    // (DiffUtil + 10k binds). We keep [allRecordings] as the full
    // source of truth and only expose [visibleRecordings] (default
    // 50) to the adapter. When the user scrolls near the bottom we
    // append the next batch from [allRecordings].
    private val allRecordings = mutableListOf<Recording>()
    private val visibleRecordings = mutableListOf<Recording>()
    private var isLoadingMore = false
    private val pageBatchSize = 50

    // v1.5.11: big scrub bar state for the full-day playlist mode.
    // [dayTotalMs] is the full 24h window (constant once a day is
    // picked). [dayClipDurationMs] is the duration of each clip —
    // we use this to compute the window index from a SeekBar
    // progress. We assume 60000ms (60s buckets) since that's what
    // the backend's PlayRecording handler emits; if a clip is
    // shorter (camera offline), ExoPlayer will still report the
    // actual duration via player.duration, but our SeekBar uses
    // fixed 60000ms buckets to keep the math simple + predictable.
    private var dayTotalMs: Long = 24L * 60 * 60 * 1000
    private var dayClipDurationMs: Long = 60_000L
    private val daySeekHandler = Handler(Looper.getMainLooper())
    private var daySeekUserDragging = false
    private val daySeekUpdateRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!daySeekUserDragging) {
                val cur = p.currentWindowIndex.toLong() * dayClipDurationMs + p.currentPosition
                val safe = cur.coerceIn(0L, dayTotalMs)
                binding.daySeekBar.progress = safe.toInt()
                binding.tvDayPosition.text = formatDayTime(safe)
            }
            // Tick every 500ms — frequent enough for smooth scrub
            // updates but cheap (one int assignment + one text set).
            daySeekHandler.postDelayed(this, 500)
        }
    }

    init {
        binding = DialogRecordingsBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)
        setupRecyclerView()
        loadRecordings()
        binding.toolbar.setNavigationOnClickListener { dismiss() }
        binding.toolbar.title = "${camera.name} - 录像"
        // v1.5.10: "Play full day" entry point — opens a date picker,
        // then loads all 60s clips from the selected day into an
        // ExoPlayer playlist so the user can scrub through a full 24h
        // as one continuous video (the challenge the user explicitly
        // asked for). Implementation lives in [playDayAsPlaylist].
        binding.btnPlayDay.setOnClickListener { showDayPicker() }
    }

    private fun setupRecyclerView() {
        adapter = RecordingAdapter(
            baseUrl = baseUrl,
            token = token,
            onPlayRecording = { recording -> playRecording(recording) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
        // v1.5.10: paginate on scroll. Trigger when the user is
        // within 10 items of the end so the next batch is loaded
        // before they reach the bottom (no visible "loading more"
        // pause for fast scrollers).
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoadingMore) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = lm.findLastVisibleItemPosition()
                val total = adapter.itemCount
                if (lastVisible >= total - 10) {
                    loadMoreRecordings()
                }
            }
        })
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
                    // v1.5.10: keep the full list, expose only the
                    // first page to the adapter. Subsequent pages
                    // are appended on scroll via [loadMoreRecordings].
                    allRecordings.clear()
                    allRecordings.addAll(recordings)
                    visibleRecordings.clear()
                    visibleRecordings.addAll(recordings.take(pageBatchSize))
                    adapter.submitList(visibleRecordings.toList())
                    binding.progressBar.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    binding.tvEmpty.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
                    if (recordings.isEmpty()) {
                        binding.tvEmpty.text = if (resp.isSuccess) "暂无录像" else "加载失败: ${resp.message}"
                    }
                    // v1.5.10: only show the "Play full day" button
                    // when there's at least one recording to play.
                    binding.btnPlayDay.visibility =
                        if (recordings.isNotEmpty()) View.VISIBLE else View.GONE
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

    /**
     * v1.5.10: append the next page of recordings to the visible
     * list. No-op when we've already exposed everything from
     * [allRecordings]. Uses [ListAdapter.submitList] with a copy
     * so DiffUtil can compute the minimal diff (one append).
     */
    private fun loadMoreRecordings() {
        if (isLoadingMore) return
        if (visibleRecordings.size >= allRecordings.size) return
        isLoadingMore = true
        val nextEnd = (visibleRecordings.size + pageBatchSize).coerceAtMost(allRecordings.size)
        visibleRecordings.addAll(allRecordings.subList(visibleRecordings.size, nextEnd))
        adapter.submitList(visibleRecordings.toList()) {
            isLoadingMore = false
        }
    }

    /**
     * v1.5.10: opens a date picker for the "play full day" feature.
     * Constraint: the date is clamped to the recordings' time range
     * (default is today; the picker allows any date but we filter
     * to the actual recordings we have for that day).
     */
    private fun showDayPicker() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val dayCal = Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                playDayAsPlaylist(dayCal)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    /**
     * v1.5.10: plays a full 24h of recordings as a single ExoPlayer
     * playlist. The user explicitly asked for "integrate playback as
     * one big video with max duration 1 day". Implementation:
     *
     *  - Filter [allRecordings] to entries whose [Recording.startAt]
     *    falls on the same day as [dayStart] (using LOCAL timezone).
     *  - Sort ascending by [Recording.id] (which is a unix-minute
     *    timestamp) so the playlist plays chronologically.
     *
     * v1.5.12 FIX: previously we filtered by UTC date, but the
     * backend returns start_at as UTC ISO string (e.g.
     * "2026-07-19T00:00:00Z"). Filtering by UTC date "7.19" gave
     * UTC 00:00..24:00, which when displayed in local time (UTC+8)
     * became 7.19 08:00 .. 7.20 08:00 — an 8-hour offset. The fix
     * converts each recording's UTC instant to local time BEFORE
     * comparing against the user-picked local date.
     *
     * @param dayStart Calendar set to 00:00:00 LOCAL of the day to play.
     */
    private fun playDayAsPlaylist(dayStart: Calendar) {
        // v1.5.12: dayStart is in the user's local timezone (Calendar
        // default). Convert it to UTC instant for comparison with
        // backend's UTC ISO strings.
        val dayStartLocalMillis = dayStart.timeInMillis
        val dayEndLocalMillis = dayStartLocalMillis + 24L * 60 * 60 * 1000

        val parseFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dayRecordings = allRecordings.filter { rec ->
            try {
                val t = parseFmt.parse(rec.startAt)?.time ?: return@filter false
                // v1.5.12: compare in local time. rec.startAt is UTC,
                // dayStart/dayEnd are local — but timeInMillis is
                // always UTC-anchored so direct comparison works.
                // The bug was previously: we filtered by UTC date
                // which is 8 hours behind local date. Now we filter
                // by LOCAL date boundaries converted to UTC millis.
                t in dayStartLocalMillis until dayEndLocalMillis
            } catch (_: Exception) { false }
        }.sortedBy { it.id }

        if (dayRecordings.isEmpty()) {
            Toast.makeText(context, "该日期无录像", Toast.LENGTH_SHORT).show()
            return
        }

        android.util.Log.d("RecordingsDialog",
            "Playing full day: ${dayRecordings.size} clips starting at ${dayRecordings.first().startAt}")

        // v1.5.12: load alert ranges for this camera on this day so
        // the AlertRangeOverlay can paint red segments on the SeekBar.
        // The backend's listAlerts returns unix seconds (float) — we
        // convert each alert's start/end into day-relative ms for the
        // overlay's coordinate system.
        loadAlertRangesForDay(dayStartLocalMillis, dayEndLocalMillis)

        binding.videoContainer.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE

        player?.release()
        // v1.5.11: stop any pending scrub updates from a previous
        // playlist session before we (re)build the player.
        daySeekHandler.removeCallbacks(daySeekUpdateRunnable)
        val renderersFactory = ExoPlayerRendererFactory.create(context)
        player = ExoPlayer.Builder(context, renderersFactory).build().apply {
            setAudioAttributes(
                com.google.android.exoplayer2.audio.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
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
            val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
            // Build the playlist. Each MediaItem carries its own URL
            // + a title formatted as "HH:mm" so the user sees the
            // current time position in the controller.
            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val items = dayRecordings.map { rec ->
                val title = try {
                    parseFmt.parse(rec.startAt)?.let { timeFmt.format(it) } ?: ""
                } catch (_: Exception) { "" }
                MediaItem.Builder()
                    .setUri(buildRecordingUrl(rec.id))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(title)
                            .build()
                    )
                    .build()
            }
            // setMediaSources is more efficient than setMediaItems
            // when each item shares the same MediaSource factory.
            val mediaSources = items.map { mediaSourceFactory.createMediaSource(it) }
            setMediaSources(mediaSources, /* startWindowIndex = */ 0, /* startPositionMs = */ 0L)
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

        // v1.5.11: hide ExoPlayer's built-in controller scrubber in
        // playlist mode so the user doesn't see two competing
        // progress bars — we show the day-spanning SeekBar instead.
        // The built-in controller still shows the play/pause button
        // + current clip's timestamp, which is useful context.
        binding.playerView.useController = false
        binding.dayScrubBarContainer.visibility = View.VISIBLE
        binding.daySeekBar.max = dayTotalMs.toInt()
        binding.daySeekBar.progress = 0
        binding.tvDayPosition.text = "00:00:00"
        binding.tvDayDuration.text = "24:00:00"
        // Start the periodic update — it re-posts itself every 500ms
        // until [stopDaySeekUpdates] is called (in dismiss / back).
        daySeekHandler.post(daySeekUpdateRunnable)

        // v1.5.11: drag listener — while the user drags the big
        // SeekBar we pause the live-update loop (so we don't fight
        // their thumb) and update the position label from the
        // dragged progress. On release we seek ExoPlayer to the
        // matching clip + offset.
        binding.daySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                binding.tvDayPosition.text = formatDayTime(progress.toLong())
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {
                daySeekUserDragging = true
            }
            override fun onStopTrackingTouch(seek: SeekBar?) {
                daySeekUserDragging = false
                val progress = seek?.progress?.toLong() ?: return
                val p = player ?: return
                // Map the day-relative ms to a playlist window index
                // + position-in-window. We assume 60s buckets — if
                // the actual clip is shorter (camera was offline
                // part of the minute), ExoPlayer clamps our seek to
                // the clip's actual duration, which is fine.
                val targetWindow = (progress / dayClipDurationMs).toInt()
                    .coerceAtMost(p.mediaItemCount - 1)
                val targetPosition = (progress % dayClipDurationMs)
                android.util.Log.d("RecordingsDialog",
                    "Day seek: progress=${progress}ms -> window=$targetWindow pos=${targetPosition}ms")
                p.seekTo(targetWindow, targetPosition)
            }
        })

        binding.btnBack.setOnClickListener {
            player?.release()
            player = null
            fullscreenHelper?.release()
            fullscreenHelper = null
            binding.btnPlaybackSpeed.visibility = View.GONE
            binding.btnFullscreen.visibility = View.GONE
            binding.dayScrubBarContainer.visibility = View.GONE
            binding.playerView.useController = true
            daySeekHandler.removeCallbacks(daySeekUpdateRunnable)
            binding.videoContainer.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }

        if (fullscreenHelper == null) {
            val helper = PlayerFullscreenHelper(
                playerView = binding.playerView,
                hostView = binding.root,
                hideOnFullscreen = listOf(binding.toolbar, binding.btnBack),
                speedButton = binding.btnPlaybackSpeed,
                fullscreenButton = binding.btnFullscreen,
            )
            helper.attach()
            fullscreenHelper = helper
        }
        fullscreenHelper?.onPlayerChanged(player)
        binding.btnPlaybackSpeed.visibility = View.VISIBLE
        binding.btnFullscreen.visibility = View.VISIBLE
    }

    /**
     * v1.5.11: formats a day-relative millisecond offset as
     * HH:mm:ss on a 24-hour clock. Used by both the SeekBar update
     * loop and the user-drag listener to render the current playback
     * position in the full-day playlist.
     */
    private fun formatDayTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    /**
     * v1.5.12: fetches alerts from the backend, filters them to this
     * camera + the selected day, then converts each alert's
     * (start_time, end_time) into a day-relative (startMs, endMs)
     * pair that [AlertRangeOverlay] can render.
     *
     * v1.5.13 FIX: previously we filtered by `alert.camera_id == camId`
     * only. The backend's `alertEntry.CameraID` uses `omitempty` —
     * when `LookupByFrigateSlug` fails (slug mismatch), the JSON
     * response drops the field entirely and Android parses it as
     * null. So matching by id alone missed all alerts whose Frigate
     * slug doesn't match the current camera's streamName.
     *
     * New strategy: match by EITHER camera_id OR camera_slug (the
     * slug derived from camera.streamName via the same slugifyName
     * algorithm the backend uses). camera_slug is always present in
     * the JSON (no omitempty) so this is the more reliable signal.
     *
     * Threading:
     *  - Network call on Dispatchers.IO
     *  - UI update (setAlertRanges) on Dispatchers.Main
     *
     * @param dayStartLocalMillis Day boundary in LOCAL time.
     * @param dayEndLocalMillis Day boundary + 24h in LOCAL time.
     */
    private fun loadAlertRangesForDay(
        dayStartLocalMillis: Long,
        dayEndLocalMillis: Long,
    ) {
        // Reset the overlay so a stale red segment doesn't persist
        // if the network call fails.
        binding.alertOverlay.setMax(dayEndLocalMillis - dayStartLocalMillis)
        binding.alertOverlay.setAlertRanges(emptyList())

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val authHeader = if (!token.isNullOrEmpty()) "Bearer $token" else ""
                // v1.5.14: drop limit from 200 to 100 — the backend
                // clamps to 100 anyway, and asking for 200 just means
                // we wait for the same payload. 100 covers ~24h of
                // motion events for a single camera at the default
                // detection FPS (2 fps, ~5 events/hour).
                val resp = container.getApi().listAlerts(authHeader, limit = 100)
                if (!resp.isSuccess) {
                    android.util.Log.w("RecordingsDialog",
                        "listAlerts for overlay returned code=${resp.code} msg=${resp.message}")
                    return@launch
                }
                val decoded = resp.decodeData<
                    com.homedatacenter.app.data.model.AlertListData>()
                val alerts = decoded?.alerts ?: emptyList()

                val camId = camera.id
                val expectedSlug = slugifyName(camera.name)

                // v1.5.14: detailed diagnostic log. The user reported
                // "标红依旧没有" even after v1.5.13's slug-matching fix,
                // and v1.5.14's slugifyName lowercase+hash fallback
                // doesn't change the slug for cameras in the hardcoded
                // Chinese map (so "前门" still maps to "front_door").
                // The real root cause must be elsewhere — empty alerts
                // list, time filter, or view rendering. This log entry
                // dumps enough info to pinpoint it without a second
                // round-trip.
                android.util.Log.d("RecordingsDialog",
                    "Alert overlay diag: camId=$camId name='${camera.name}' " +
                    "expectedSlug='$expectedSlug' " +
                    "dayStart=${dayStartLocalMillis} dayEnd=${dayEndLocalMillis} " +
                    "alertsTotal=${alerts.size}")
                if (alerts.isNotEmpty()) {
                    // Dump first 3 alerts for inspection (slug, camId, time)
                    alerts.take(3).forEachIndexed { i, a ->
                        val startMs = (a.startTime * 1000).toLong()
                        val inDay = startMs in dayStartLocalMillis until dayEndLocalMillis
                        android.util.Log.d("RecordingsDialog",
                            "  alert[$i]: slug='${a.cameraSlug}' camId=${a.cameraId} " +
                            "label='${a.label}' startUnix=${a.startTime} " +
                            "startMs=$startMs inDay=$inDay")
                    }
                }

                val dayRanges = alerts
                    .filter { alert ->
                        val camMatch = alert.cameraId == camId ||
                            (alert.cameraSlug.isNotEmpty() &&
                                alert.cameraSlug == expectedSlug)
                        if (!camMatch) {
                            // v1.5.14: log WHY the alert didn't match
                            // so we can see if it's a slug mismatch
                            // (Frigate config not pushed with new algo)
                            // or a camera_id mismatch (LookupByFrigateSlug
                            // failed).
                            android.util.Log.d("RecordingsDialog",
                                "  filter reject: slug='${alert.cameraSlug}' " +
                                "vs expected='$expectedSlug'; " +
                                "camId=${alert.cameraId} vs $camId")
                            return@filter false
                        }
                        val startMs = (alert.startTime * 1000).toLong()
                        val inDay = startMs in dayStartLocalMillis until dayEndLocalMillis
                        if (!inDay) {
                            android.util.Log.d("RecordingsDialog",
                                "  filter reject (time): startMs=$startMs " +
                                "not in [${dayStartLocalMillis}, ${dayEndLocalMillis})")
                        }
                        inDay
                    }
                    .map { alert ->
                        val startMs = (alert.startTime * 1000).toLong() - dayStartLocalMillis
                        val endMs = (alert.endTime * 1000).toLong() - dayStartLocalMillis
                        val clampedStart = startMs.coerceAtLeast(0L)
                        val clampedEnd = endMs.coerceAtMost(
                            dayEndLocalMillis - dayStartLocalMillis
                        )
                        clampedStart to clampedEnd
                    }
                    .filter { (s, e) -> e > s }

                android.util.Log.d("RecordingsDialog",
                    "Alert overlay: ${dayRanges.size} ranges for camera ${camera.id} " +
                    "(slug=$expectedSlug, name=${camera.name}) " +
                    "ranges=${dayRanges.take(3)}")
                withContext(Dispatchers.Main) {
                    binding.alertOverlay.setAlertRanges(dayRanges)
                }
            } catch (e: Exception) {
                android.util.Log.w("RecordingsDialog",
                    "Failed to load alert ranges: ${e.message}", e)
            }
        }
    }

    /**
     * v1.5.13: replicates the backend's `slugifyName` algorithm
     * (see home-datacenter/services/api/internal/camera/registry.go).
     * Used to match alerts by camera_slug — the backend's
     * `alertEntry.CameraID` uses `omitempty` and is missing when
     * `LookupByFrigateSlug` fails, so we can't rely on camera_id
     * alone.
     *
     * Algorithm: keep [a-zA-Z0-9_-], replace any other character
     * with underscore, no case conversion.
     *
     * v1.5.14: match the backend's updated algorithm — lowercase
     * ASCII letters (so "Front Door" -> "front_door"), and for pure
     * non-ASCII names (which produce an empty slug) use a stable
     * hash prefix "cam_<8hex>". Java's String.hashCode() is used
     * instead of sha256 (simpler, no extra deps) — the backend uses
     * sha256 but both produce stable per-input outputs that we
     * then compare against each other, so the exact hash function
     * doesn't matter as long as the client computes the SAME slug
     * the backend would.
     *
     * NOTE: the backend's hash uses sha256(name)[:4] hex (8 chars).
     * To stay byte-identical with the backend, the client uses
     * Java's MessageDigest with SHA-256 and takes the first 4 bytes.
     */
    private fun slugifyName(name: String): String {
        if (name.isEmpty()) return ""
        // v1.5.14: well-known Chinese names — same hardcoded map
        // as the backend (registry.go). Sync if backend changes.
        when (name) {
            "前门" -> return "front_door"
            "后门" -> return "back_door"
            "客厅" -> return "living_room"
            "卧室" -> return "bedroom"
            "厨房" -> return "kitchen"
            "院子" -> return "yard"
            "车库" -> return "garage"
        }
        val sb = StringBuilder(name.length)
        var prevUnderscore = false
        for (c in name) {
            when {
                c in 'a'..'z' || c in '0'..'9' -> {
                    sb.append(c); prevUnderscore = false
                }
                c in 'A'..'Z' -> {
                    // v1.5.14: lowercase ASCII letters to match
                    // the backend's updated slugifyName.
                    sb.append(c + ('a' - 'A')); prevUnderscore = false
                }
                c == '_' || c == '-' -> {
                    sb.append('_'); prevUnderscore = false
                }
                else -> {
                    if (!prevUnderscore && sb.isNotEmpty()) {
                        sb.append('_'); prevUnderscore = true
                    }
                }
            }
        }
        var out = sb.toString().trim('_')
        if (out.isEmpty()) {
            // v1.5.14: pure non-ASCII names — compute cam_<8hex>
            // from SHA-256(name)[:4] to match the backend exactly.
            try {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val h = md.digest(name.toByteArray(Charsets.UTF_8))
                val hex = StringBuilder(8)
                for (i in 0 until 4) {
                    val v = h[i].toInt() and 0xFF
                    hex.append(v.toString(16).padStart(2, '0'))
                }
                out = "cam_$hex"
            } catch (_: Exception) {
                out = "cam_${(name.hashCode().toLong() and 0xFFFFFFFFL).toString(16)}"
            }
        }
        return out
    }

    private fun playRecording(recording: Recording) {
        val url = buildRecordingUrl(recording.id)
        if (url.isEmpty()) return

        android.util.Log.d("RecordingsDialog", "Playing recording ${recording.id} from $url")

        binding.videoContainer.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE

        player?.release()
        // Delegate to ExoPlayerRendererFactory: on emulators it filters
        // out the broken goldfish decoders, on real devices it just
        // enables decoder fallback. See util/ExoPlayerRendererFactory.kt.
        val renderersFactory = ExoPlayerRendererFactory.create(context)
        player = ExoPlayer.Builder(context, renderersFactory).build().apply {
            // Route audio through the media stream and let ExoPlayer
            // manage AudioFocus — pauses on phone call, resumes after.
            setAudioAttributes(
                com.google.android.exoplayer2.audio.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
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
            fullscreenHelper?.release()
            fullscreenHelper = null
            binding.btnPlaybackSpeed.visibility = View.GONE
            binding.btnFullscreen.visibility = View.GONE
            binding.videoContainer.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }

        // Attach fullscreen + speed button handlers. The fullscreen
        // helper hides the toolbar and rotates the underlying Activity
        // to landscape; the speed button opens a popup with 0.5x /
        // 1x / 1.5x / 2x options. v1.5.3: the ExoPlayer 2.x default
        // controller layout doesn't ship a fullscreen button, so we
        // pass a standalone overlay (btnFullscreen) that the helper
        // wires to [toggleFullscreen].
        if (fullscreenHelper == null) {
            val helper = PlayerFullscreenHelper(
                playerView = binding.playerView,
                hostView = binding.root,
                hideOnFullscreen = listOf(binding.toolbar, binding.btnBack),
                speedButton = binding.btnPlaybackSpeed,
                fullscreenButton = binding.btnFullscreen,
            )
            helper.attach()
            fullscreenHelper = helper
        }
        fullscreenHelper?.onPlayerChanged(player)
        // Speed + fullscreen buttons are only meaningful when a
        // recording is loaded.
        binding.btnPlaybackSpeed.visibility = View.VISIBLE
        binding.btnFullscreen.visibility = View.VISIBLE
    }

    private fun buildRecordingUrl(recId: Long): String {
        if (baseUrl.isNullOrBlank()) return ""
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return "${base}api/v1/cameras/${camera.id}/recordings/$recId/file"
    }

    override fun dismiss() {
        // v1.5.11: stop the day-scrub update loop BEFORE releasing
        // the player — otherwise the runnable can fire one last
        // time after player is null and silently no-op, but it's
        // cleaner to cancel explicitly.
        daySeekHandler.removeCallbacks(daySeekUpdateRunnable)
        player?.release()
        player = null
        fullscreenHelper?.release()
        fullscreenHelper = null
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
