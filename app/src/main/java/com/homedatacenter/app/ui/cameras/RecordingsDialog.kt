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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class RecordingsDialog(
    context: Context,
    private val camera: Camera,
    private val container: AppContainer,
    // v1.6.0: optional initial playback position as a unix timestamp
    // (seconds). When set (e.g. via clicking an alert card in the
    // alerts list), the dialog opens the day picker for that date,
    // loads all recordings of that day into the playlist, and seeks
    // ExoPlayer to the matching clip + offset immediately. The user
    // thus lands at the alert's exact moment without manual scrubbing.
    private val initialTimestamp: Long = 0L,
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
    // v1.5.16: per-clip LOCAL start offset (ms from dayStart, which
    // is LOCAL 08:00 of the picked day as of v1.5.17).
    // The previous SeekBar mapped windowIndex*60000ms directly to
    // progress, which assumed clips were laid out contiguously
    // starting from LOCAL 00:00. In reality the playlist starts at
    // the first available recording (often LOCAL 08:00+), so the
    // SeekBar's "00:00:00" label was wrong — it actually pointed at
    // a clip that started at LOCAL 16:05. Now each clip carries its
    // real LOCAL start offset; SeekBar progress maps to absolute
    // LOCAL time-of-day via binary search.
    // v1.5.17: dayStart is now LOCAL 08:00 (not 00:00), so a clip at
    // LOCAL 08:00 has offset 0 — i.e., the SeekBar's 0% actually
    // corresponds to the first recording of the day.
    private var clipStartOffsets: LongArray = LongArray(0)
    private var dayStartLocalMillis: Long = 0L
    private val daySeekHandler = Handler(Looper.getMainLooper())
    private var daySeekUserDragging = false
    // v1.6.0: one-shot flag for alert-click seek. Set when an alert
    // opens the dialog with initialTimestamp; cleared after the first
    // STATE_READY so subsequent state changes don't re-seek.
    private var pendingAlertSeekMs: Long = 0L
    // v1.6.0: motion ranges in day-relative ms (0..dayTotalMs). Cached
    // from [loadAlertRangesForDay] so the SeekBar snap logic in
    // [onStopTrackingTouch] can find the nearest range edge without
    // re-fetching from the backend on every user release. Empty list
    // means either no motion detected today or the fetch failed —
    // snapping is a no-op in that case.
    private var motionRangesRelative: List<Pair<Long, Long>> = emptyList()
    // v1.6.0: snap radius in ms. When the user releases the SeekBar
    // within this distance of a motion range edge, we snap to the
    // edge. 30s is roughly the smallest visible seek granularity on
    // a 24h timeline at 720px width (24h / 720px = 120s/px, so 30s is
    // well within a single pixel — the snap is felt, not seen).
    private val motionSnapRadiusMs: Long = 30_000L
    private val daySeekUpdateRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!daySeekUserDragging) {
                // v1.5.16: SeekBar progress = absolute LOCAL time-of-day
                // (ms from LOCAL 00:00), not playlist-internal position.
                val cur = if (clipStartOffsets.isNotEmpty() &&
                              p.currentWindowIndex in clipStartOffsets.indices) {
                    clipStartOffsets[p.currentWindowIndex] + p.currentPosition
                } else {
                    p.currentWindowIndex.toLong() * dayClipDurationMs + p.currentPosition
                }
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

        // v1.6.0: if opened with an initialTimestamp (alert click),
        // auto-open the day picker for that date and seek to the
        // matching clip+offset after the playlist loads. We post the
        // call so loadRecordings() runs first (otherwise the recording
        // list is empty when the picker fires).
        if (initialTimestamp > 0L) {
            binding.root.post {
                openDayForTimestamp(initialTimestamp)
            }
        }
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
     *
     * v1.5.21: dayCal is set to LOCAL 00:00 of the picked date — used
     * only as a filter window to find recordings on that date. The
     * actual SeekBar 0% anchor is the first recording's start time
     * (see [playDayAsPlaylist]), so the SeekBar's "00:00:00" label
     * corresponds to the first available recording. The previous
     * v1.5.17 attempt anchored at LOCAL 08:00, but the user's actual
     * recordings start at LOCAL 16:00+ (depends on when motion was
     * detected that day), so even 8am left 8h of empty SeekBar.
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
     * v1.6.0: opens the day playlist for the date containing
     * [unixSeconds] and seeks ExoPlayer to that exact moment after
     * the playlist loads. Used by the alert-click "查看录像" jump —
     * the user clicks an alert and lands at its exact time without
     * manually opening the date picker + scrubbing.
     *
     * Implementation: compute the LOCAL date of [unixSeconds], call
     * [playDayAsPlaylist] with that date (it builds the playlist),
     * then post a seek once ExoPlayer reports STATE_READY. We can't
     * seek immediately after [playDayAsPlaylist] returns because
     * ExoPlayer's prepare() is async — the player reports
     * STATE_BUFFERING until the first clip loads. We register a
     * one-shot Player.Listener that fires on the first STATE_READY
     * and seeks to the requested time.
     */
    private fun openDayForTimestamp(unixSeconds: Long) {
        if (unixSeconds <= 0L) return
        val cal = Calendar.getInstance().apply {
            timeInMillis = unixSeconds * 1000L
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // playDayAsPlaylist builds the playlist and posts a seek-to
        // once ExoPlayer reports STATE_READY — see the one-shot
        // listener we register inside it.
        playDayAsPlaylist(cal, seekToMs = unixSeconds * 1000L)
    }

    /**
     * v1.5.10: plays a full 24h of recordings as a single ExoPlayer
     * playlist. The user explicitly asked for "integrate playback as
     * one big video with max duration 1 day". Implementation:
     *
     *  - Filter [allRecordings] to entries whose [Recording.startAt]
     *    falls within the [dayFilterStart, dayFilterStart+24h) window
     *    (LOCAL tz). dayFilterStart is LOCAL 00:00 of the picked date.
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
     * v1.5.21: the SeekBar's 0% anchor is now the first available
     * recording's start time (not LOCAL 00:00 or 08:00). This way
     * "00:00:00" on the SeekBar actually plays the first recording,
     * and there's no leading empty section. The user explicitly said
     * "进度条和实际都是从00:00:00开始的". We:
     *   1. Find the earliest recording within the picked day.
     *   2. Set dayStartLocalMillis = that recording's UTC instant
     *      (timeInMillis is UTC-anchored, works for comparison).
     *   3. dayEndLocalMillis = dayStartLocalMillis + 24h (window size
     *      stays 24h so the SeekBar's max is still 24h).
     *   4. clipStartOffsets[0] = 0 (first clip is at SeekBar 0%).
     *   5. formatDayTime(ms) = elapsed time since dayStart → 0=00:00:00.
     *
     * @param dayFilterStart Calendar set to 00:00:00 LOCAL of the day
     *   to play. Used only to filter recordings on that date; the
     *   actual playback/SeekBar anchor is the first recording's time.
     * @param seekToMs v1.6.0: optional unix-ms timestamp to seek to
     *   after the playlist loads. When non-zero, a one-shot Player.Listener
     *   is registered that fires on the first STATE_READY and seeks
     *   ExoPlayer to the matching clip + offset. Used by alert-click
     *   "查看录像" jump.
     */
    private fun playDayAsPlaylist(dayFilterStart: Calendar, seekToMs: Long = 0L) {
        // dayFilterStart is in the user's local timezone (Calendar
        // default). Convert it to UTC instant for comparison with
        // backend's UTC ISO strings.
        val dayFilterStartMillis = dayFilterStart.timeInMillis
        val dayFilterEndMillis = dayFilterStartMillis + 24L * 60 * 60 * 1000

        val parseFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dayRecordings = allRecordings.filter { rec ->
            try {
                val t = parseFmt.parse(rec.startAt)?.time ?: return@filter false
                t in dayFilterStartMillis until dayFilterEndMillis
            } catch (_: Exception) { false }
        }.sortedBy { it.id }

        if (dayRecordings.isEmpty()) {
            Toast.makeText(context, "该日期无录像", Toast.LENGTH_SHORT).show()
            return
        }

        // v1.5.21: anchor SeekBar 0% at the first recording's start.
        // This makes "00:00:00" on the SeekBar actually correspond to
        // the first available recording — no leading empty section.
        val dayStartLocalMillis = parseFmt.parse(dayRecordings.first().startAt)?.time
            ?: dayFilterStartMillis
        val dayEndLocalMillis = dayStartLocalMillis + 24L * 60 * 60 * 1000
        // v1.5.16: stash for alert overlay (also uses LOCAL day bounds).
        this.dayStartLocalMillis = dayStartLocalMillis

        // v1.5.16: compute each clip's start offset (ms from
        // dayStartLocalMillis = first recording). The SeekBar maps
        // progress to elapsed time via binary search on this array.
        // v1.5.21: since dayStartLocalMillis IS the first recording's
        // start time, clipStartOffsets[0] == 0 — first clip at SeekBar 0%.
        clipStartOffsets = LongArray(dayRecordings.size) { i ->
            try {
                val t = parseFmt.parse(dayRecordings[i].startAt)?.time ?: 0L
                (t - dayStartLocalMillis).coerceIn(0L, dayTotalMs)
            } catch (_: Exception) { 0L }
        }
        android.util.Log.d("RecordingsDialog",
            "playDayAsPlaylist: ${dayRecordings.size} clips, " +
            "dayStart=first@${dayRecordings.first().startAt} " +
            "(${formatDayTime(0L)}), " +
            "firstOffset=${clipStartOffsets.first()}ms, " +
            "lastOffset=${clipStartOffsets.last()}ms " +
            "(${formatDayTime(clipStartOffsets.last())})")

        android.util.Log.d("RecordingsDialog",
            "Playing full day: ${dayRecordings.size} clips starting at ${dayRecordings.first().startAt}")

        // v1.5.12: load alert ranges for this camera on this day so
        // the AlertRangeOverlay can paint red segments on the SeekBar.
        // v1.5.21: alert ranges now also use the first-recording
        // anchor so red marks line up with their clip positions.
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
                    // v1.6.0: one-shot seek-to when the user opened
                    // the dialog via an alert click. We wait for
                    // STATE_READY (the first clip has loaded) then
                    // compute the matching window+position from the
                    // alert's unix-ms timestamp and seek there.
                    if (state == Player.STATE_READY && pendingAlertSeekMs > 0L) {
                        val seekMs = pendingAlertSeekMs
                        pendingAlertSeekMs = 0L // clear before seeking
                        val progress = (seekMs - dayStartLocalMillis)
                            .coerceIn(0L, dayTotalMs)
                        val raw = clipStartOffsets.binarySearch(progress)
                        val idx = if (raw >= 0) raw
                                  else (-raw - 2).coerceAtLeast(0)
                        val targetWindow = idx.coerceIn(0, clipStartOffsets.size - 1)
                            .coerceAtMost(mediaItemCount - 1)
                        val targetPosition = (progress - clipStartOffsets[targetWindow])
                            .coerceIn(0L, dayClipDurationMs)
                        android.util.Log.d("RecordingsDialog",
                            "Alert-seek: ts=$seekMs progress=$progress " +
                            "-> window=$targetWindow pos=$targetPosition")
                        seekTo(targetWindow, targetPosition)
                        // Update the SeekBar to match.
                        binding.daySeekBar.progress = progress.toInt()
                        binding.tvDayPosition.text = formatDayTime(progress)
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.e("RecordingsDialog", "Player error: ${error.message}")
                }
            })
            prepare()
        }
        binding.playerView.player = player
        // v1.6.0: arm the one-shot alert-seek flag so the listener
        // above can fire on the first STATE_READY.
        if (seekToMs > 0L) {
            pendingAlertSeekMs = seekToMs
        }

        // v1.5.11: hide ExoPlayer's built-in controller scrubber in
        // playlist mode so the user doesn't see two competing
        // progress bars — we show the day-spanning SeekBar instead.
        // The built-in controller still shows the play/pause button
        // + current clip's timestamp, which is useful context.
        binding.playerView.useController = false
        binding.dayScrubBarContainer.visibility = View.VISIBLE
        binding.daySeekBar.max = dayTotalMs.toInt()
        binding.daySeekBar.progress = 0
        // v1.5.22: position label = LOCAL wall-clock time at SeekBar
        // 0% (= first recording's start time, e.g. "08:00:00").
        // Duration label = wall-clock time at SeekBar 100% (= first
        // recording + 24h, may wrap past 24:00 to next-day hours).
        binding.tvDayPosition.text = formatDayTime(0L)
        binding.tvDayDuration.text = formatDayTime(dayTotalMs)
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
                val rawProgress = seek?.progress?.toLong() ?: return
                val p = player ?: return
                // v1.6.0: snap to nearest motion-range edge. If the
                // user released within [motionSnapRadiusMs] of a red
                // segment's start or end, snap the SeekBar to that
                // edge so the user lands precisely at the start of a
                // motion event without having to pixel-hunt on a 24h
                // timeline. Without this, the closest meaningful seek
                // position would be lost in the 120s-per-pixel noise.
                val progress = snapProgressToRangeEdge(rawProgress) ?: rawProgress
                if (progress != rawProgress) {
                    // Update SeekBar + label to reflect the snapped
                    // position so the user sees the thumb jump to
                    // the range edge.
                    binding.daySeekBar.progress = progress.toInt()
                    binding.tvDayPosition.text = formatDayTime(progress)
                    android.util.Log.d("RecordingsDialog",
                        "Snap: ${rawProgress}ms -> ${progress}ms (${formatDayTime(progress)})")
                }
                // v1.5.16: map absolute LOCAL time-of-day (progress) to
                // the containing clip via binary search on
                // clipStartOffsets. The previous math assumed contiguous
                // 60s buckets starting from LOCAL 00:00 — when the
                // playlist actually started at LOCAL 16:05, dragging to
                // "10:00:00" would seek to a non-existent window 600
                // (1000h/60000ms), which ExoPlayer clamped to the last
                // clip, making the SeekBar useless for navigation.
                //
                // Binary search finds the rightmost clip whose start
                // offset <= progress. Then position within that clip =
                // progress - clipStartOffsets[idx].
                val targetWindow: Int
                val targetPosition: Long
                if (clipStartOffsets.isNotEmpty()) {
                    // binarySearch returns the index if found, or
                    // -(insertionPoint+1). For "rightmost clip whose
                    // start <= progress", we want insertionPoint-1
                    // when not found.
                    val raw = clipStartOffsets.binarySearch(progress)
                    val idx = if (raw >= 0) raw
                              else (-raw - 2).coerceAtLeast(0)
                    targetWindow = idx.coerceIn(0, clipStartOffsets.size - 1)
                        .coerceAtMost(p.mediaItemCount - 1)
                    targetPosition = (progress - clipStartOffsets[targetWindow])
                        .coerceIn(0L, dayClipDurationMs)
                } else {
                    // Fallback (shouldn't happen) — old contiguous math.
                    targetWindow = (progress / dayClipDurationMs).toInt()
                        .coerceAtMost(p.mediaItemCount - 1)
                    targetPosition = (progress % dayClipDurationMs)
                }
                android.util.Log.d("RecordingsDialog",
                    "Day seek: progress=${progress}ms (${formatDayTime(progress)}) " +
                    "-> window=$targetWindow pos=${targetPosition}ms " +
                    "(clipStartOffset=${if (clipStartOffsets.isNotEmpty()) clipStartOffsets[targetWindow] else -1})")
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
     *
     * v1.5.22: returns the LOCAL wall-clock time-of-day at the given
     * offset from [dayStartLocalMillis] (which is the first recording's
     * start time as of v1.5.21). So if the first recording is at LOCAL
     * 08:00, formatDayTime(0) = "08:00:00" — the label matches what
     * the user actually sees in the video.
     *
     * v1.5.23: explicitly set Calendar's timezone to Asia/Shanghai.
     * v1.5.22 relied on Calendar.getInstance() which uses the JVM
     * default timezone — but the user's device JVM default is GMT
     * (verified via logcat in v1.5.18: tz=GMT). So formatDayTime(0)
     * for a recording at LOCAL 00:00 (UTC 16:00 prev day) displayed
     * "08:00:00" (UTC) instead of "00:00:00" (LOCAL). The user
     * reported "进度条从00:00:00开始，但实际视频从08:00:00开始".
     *
     * Hardcoded Asia/Shanghai matches RecordingAdapter (v1.5.19) and
     * is safe for this single-region home app.
     */
    private fun formatDayTime(ms: Long): String {
        val instant = dayStartLocalMillis + ms
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        cal.timeInMillis = instant
        return String.format(Locale.US, "%02d:%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND))
    }

    /**
     * v1.6.0: fetches motion ranges from the backend's
     * /api/v1/cameras/:id/motion-ranges endpoint, converts each
     * (startUnix, endUnix) pair into a day-relative (startMs, endMs)
     * pair, and hands them to [AlertRangeOverlay] for rendering.
     *
     * Replaces the v1.5.12 implementation that used /api/v1/cameras/alerts
     * as the overlay source. The alerts endpoint only fires on AI
     * detection (person/car/etc) — for the user's home cameras the
     * Frigate config doesn't reliably cross the AI threshold, so the
     * alerts list was always empty and the SeekBar showed no red marks
     * even though motion was recorded. The motion-ranges endpoint
     * queries Frigate's recording segments directly and returns any
     * segment with motion>0 as a red range — a much truer "activity
     * happened here" signal.
     *
     * The response JSON looks like:
     * `{"code":0,"message":"ok","data":{"ranges":[[s1,e1],[s2,e2]],"total":2}}`
     * where each sN/eN is a unix second timestamp. We parse the bare
     * JSON array manually with kotlinx.serialization.json (the data
     * class is non-@Serializable because List<Pair<Long,Long>> can't
     * be auto-decoded from a bare 2-element array).
     *
     * Side effect: caches the resulting relative ranges in
     * [motionRangesRelative] so the SeekBar snap logic in
     * [onStopTrackingTouch] can find the nearest edge without a
     * re-fetch on every user release.
     *
     * Threading:
     *  - Network call on Dispatchers.IO
     *  - UI update (setAlertRanges) on Dispatchers.Main
     *
     * @param dayStartLocalMillis Day boundary in LOCAL time (ms).
     * @param dayEndLocalMillis Day boundary + 24h in LOCAL time (ms).
     */
    private fun loadAlertRangesForDay(
        dayStartLocalMillis: Long,
        dayEndLocalMillis: Long,
    ) {
        // Reset the overlay so a stale red segment doesn't persist
        // if the network call fails. Also clear the snap cache.
        binding.alertOverlay.setMax(dayEndLocalMillis - dayStartLocalMillis)
        binding.alertOverlay.setAlertRanges(emptyList())
        motionRangesRelative = emptyList()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val authHeader = if (!token.isNullOrEmpty()) "Bearer $token" else ""
                val afterSec = (dayStartLocalMillis / 1000L)
                val beforeSec = (dayEndLocalMillis / 1000L)
                android.util.Log.d("RecordingsDialog",
                    "Motion-ranges fetch: cam=${camera.id} after=$afterSec before=$beforeSec")
                val resp = container.getApi()
                    .listMotionRanges(authHeader, camera.id, afterSec, beforeSec)
                if (!resp.isSuccess) {
                    android.util.Log.w("RecordingsDialog",
                        "listMotionRanges returned code=${resp.code} msg=${resp.message}")
                    return@launch
                }

                // Decode the data field as a raw JsonElement so we
                // can manually walk the `ranges` array. We can't use
                // a @Serializable data class for List<Pair<Long,Long>>
                // because each pair is a bare JSON array [s, e], not
                // an object — kotlinx.serialization can't auto-decode
                // that without a custom KSerializer.
                val dataEl: JsonElement? = resp.data
                if (dataEl == null) {
                    android.util.Log.w("RecordingsDialog", "motion-ranges data is null")
                    return@launch
                }
                val rangesArr: JsonArray = try {
                    dataEl.jsonObject["ranges"]?.jsonArray ?: JsonArray(emptyList())
                } catch (e: Exception) {
                    android.util.Log.w("RecordingsDialog",
                        "motion-ranges parse failed: ${e.message}")
                    return@launch
                }

                val dayTotal = dayEndLocalMillis - dayStartLocalMillis
                val dayRanges = mutableListOf<Pair<Long, Long>>()
                for (item in rangesArr) {
                    val pair = try { item.jsonArray } catch (_: Exception) { continue }
                    if (pair.size < 2) continue
                    val startUnix = try { pair[0].jsonPrimitive.long } catch (_: Exception) { continue }
                    val endUnix = try { pair[1].jsonPrimitive.long } catch (_: Exception) { continue }
                    val startMs = startUnix * 1000L - dayStartLocalMillis
                    val endMs = endUnix * 1000L - dayStartLocalMillis
                    val clampedStart = startMs.coerceAtLeast(0L)
                    val clampedEnd = endMs.coerceAtMost(dayTotal)
                    if (clampedEnd > clampedStart) {
                        dayRanges.add(clampedStart to clampedEnd)
                    }
                }

                android.util.Log.d("RecordingsDialog",
                    "Motion overlay: ${dayRanges.size} ranges for camera ${camera.id} " +
                    "name='${camera.name}' ranges=${dayRanges.take(3)}")

                withContext(Dispatchers.Main) {
                    motionRangesRelative = dayRanges
                    binding.alertOverlay.setAlertRanges(dayRanges)
                }
            } catch (e: Exception) {
                android.util.Log.w("RecordingsDialog",
                    "Failed to load motion ranges: ${e.message}", e)
            }
        }
    }

    /**
     * v1.6.0: finds the nearest motion-range edge to [progressMs].
     * Used by the SeekBar snap-on-release logic — when the user
     * releases within [motionSnapRadiusMs] of a range edge, we snap
     * to that edge so the user lands precisely at the start of a
     * motion event (or just past its end) without having to pixel-hunt.
     *
     * Returns the snapped progress (in day-relative ms), or null
     * when no range edge is within the snap radius — caller should
     * just use the original progress in that case.
     *
     * Snap edges considered:
     *  - range.start — snap to the start of the motion event
     *  - range.end — snap to just past the motion event
     * Both edges are equally useful: snapping to start lets the user
     * watch the motion unfold, snapping to end lets them check what
     * happened right after.
     */
    private fun snapProgressToRangeEdge(progressMs: Long): Long? {
        if (motionRangesRelative.isEmpty()) return null
        var bestDist = motionSnapRadiusMs
        var bestSnap: Long? = null
        for ((s, e) in motionRangesRelative) {
            val distToStart = Math.abs(progressMs - s)
            if (distToStart < bestDist) {
                bestDist = distToStart
                bestSnap = s
            }
            val distToEnd = Math.abs(progressMs - e)
            if (distToEnd < bestDist) {
                bestDist = distToEnd
                bestSnap = e
            }
        }
        return bestSnap
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
                // v1.5.19: hardcode Asia/Shanghai instead of relying on
                // TimeZone.getDefault(). The logcat from v1.5.18 showed
                // tz=GMT on the user's device, even though the device's
                // system setting is Asia/Shanghai — the JVM default
                // was mutated somewhere (likely by a library or by
                // TimeZone.setDefault() in a third-party init path).
                // Since this app is single-user / single-region (the
                // user's home in China), hardcoding Asia/Shanghai is
                // safe and avoids the fragility of JVM-default.
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("Asia/Shanghai")
                }
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
