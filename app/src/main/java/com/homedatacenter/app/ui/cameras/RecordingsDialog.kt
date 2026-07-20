package com.homedatacenter.app.ui.cameras

import android.app.Dialog
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
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.MediaMetadata
import com.homedatacenter.app.R
import com.homedatacenter.app.data.model.Camera
import com.homedatacenter.app.data.model.Recording
import com.homedatacenter.app.di.AppContainer
import com.homedatacenter.app.databinding.DialogRecordingsBinding
import com.homedatacenter.app.databinding.ItemDayRecordingBinding
import com.homedatacenter.app.util.ExoPlayerRendererFactory
import com.homedatacenter.app.util.PlayerFullscreenHelper
import com.homedatacenter.app.util.PlayerGestureHelper
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
import kotlin.math.abs

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
    private lateinit var dayAdapter: DayRecordingAdapter
    private val baseUrl = container.getApiBaseUrl()
    private val token = container.prefsManager.token
    private var player: ExoPlayer? = null
    private var fullscreenHelper: PlayerFullscreenHelper? = null
    // v1.6.4 rev6: gesture helper for pause button + double-tap seek
    // + long-press fast-forward. See [PlayerGestureHelper] for the
    // interaction contract.
    private var gestureHelper: PlayerGestureHelper? = null

    // v1.6.2: source-of-truth for the per-day list view. v1.5.x
    // kept [allRecordings] + [visibleRecordings] for virtual
    // pagination of 60s buckets (~10k items for 7 days). v1.6.2
    // changed the default entry to a per-DAY list (~7 items max),
    // so pagination is no longer needed — we just hand the whole
    // grouped list to [dayAdapter] in one shot.
    private val allRecordings = mutableListOf<Recording>()

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
    // v1.6.6 rev8: maps container child index → MotionChip reference.
    // Used by [seekToMotionStart] to find the container child that
    // corresponds to a tapped chip (so we can scrollToCenterChip it).
    // Previously we matched by formatted "HH:mm" label, but collapsed
    // "⋯" chips share the same "⋯" label across multiple runs — a
    // label match would find the wrong chip. This map gives us O(1)
    // lookup by chip reference instead.
    private val childIndexToMotionChip = mutableMapOf<Int, MotionChip>()
    private var dayStartLocalMillis: Long = 0L
    private val daySeekHandler = Handler(Looper.getMainLooper())
    private var daySeekUserDragging = false
    // v1.6.0: one-shot flag for alert-click seek. Set when an alert
    // opens the dialog with initialTimestamp; cleared after the first
    // STATE_READY so subsequent state changes don't re-seek.
    private var pendingAlertSeekMs: Long = 0L
    // v1.6.1: deferred initial action for alert-click jump. When the
    // dialog is opened with initialTimestamp > 0 (alert click), we
    // can't call openDayForTimestamp directly from [init] because
    // [loadRecordings] hasn't populated [allRecordings] yet — the
    // playlist filter step would find nothing and show "该日期无录像".
    // v1.6.0 used binding.root.post which raced with the network
    // request (post always won). v1.6.1 introduced this flag and
    // fires it from [loadRecordings]'s main-thread completion callback.
    // v1.6.2: the other mode (auto-open day picker) was removed —
    // the per-day list is now the default entry, so no deferred
    // action is needed when initialTimestamp == 0.
    private var pendingInitialTimestamp: Long = 0L
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
    // v1.6.1: 30s -> 120s. The v1.6.0 30s radius was ~0.25px on a
    // 720px-wide 24h timeline — the user reported "没感受到吸附效果"
    // because their finger release position was almost never within
    // 0.25px of a range edge. 120s = ~1px, which matches the user's
    // practical release precision (the thumb is ~12px wide; releasing
    // at a given screen position is good to ~2-3px = 240-360s).
    // 120s is a good compromise: noticeable when the user wants to
    // seek near a motion edge, but not so large that it yanks the
    // thumb to a far-away range when the user just wanted to scrub.
    private val motionSnapRadiusMs: Long = 120_000L
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
        // v1.6.2: btnPlayDay is no longer used. v1.5.x opened a
        // DatePickerDialog to pick a day for full-day playback; but
        // v1.6.2 made the per-DAY list the default entry, so the
        // user picks a day by tapping a row directly. The button
        // stays in the layout (GONE) to avoid touching every binding
        // call site — cheap to keep.
        binding.btnPlayDay.visibility = View.GONE

        // v1.6.1: defer the alert-click jump to [loadRecordings]
        // completion. v1.6.0 used binding.root.post which races with
        // the network request — see [pendingInitialTimestamp] doc.
        // v1.6.2: the auto-open-day-picker mode was removed; the
        // default entry is now the per-day list itself, so no
        // deferred action is needed when initialTimestamp == 0.
        if (initialTimestamp > 0L) {
            pendingInitialTimestamp = initialTimestamp
        }
    }

    private fun setupRecyclerView() {
        // v1.6.2: switched from RecordingAdapter (per-60s-clip list)
        // to DayRecordingAdapter (per-day card list). Each row shows
        // the date + weekday + recording count + total duration +
        // a green "N 段" status chip. Tapping a row launches
        // playDayAsPlaylist for that date — no separate date picker
        // dialog needed.
        dayAdapter = DayRecordingAdapter(
            onPlayDay = { dayRec -> playDayAsPlaylist(dayRec.dayStartCalendar) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = dayAdapter
        // v1.6.2: removed the pagination scroll listener. The
        // backend returns 7 days of 60s buckets (~10k items), but we
        // group them into ~7 day cards — far below any pagination
        // threshold.
    }

    private fun loadRecordings() {
        binding.progressBar.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
        // v1.6.8: toggle the whole empty-state container (icon +
        // title + hint) instead of just tvEmpty — the container is
        // the unit of visibility now.
        binding.emptyStateContainer.visibility = View.GONE

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
                    // v1.6.2: group recordings by LOCAL day and
                    // hand the per-day list to [dayAdapter]. Each
                    // DayRecording aggregates the count + total
                    // duration of all 60s buckets that fall on that
                    // LOCAL date. Days with zero recordings are NOT
                    // synthesized — only days that have at least
                    // one recording appear, since the user explicitly
                    // asked "标注每一天是否有录像数据" and the
                    // backend only returns days with recordings
                    // (the /recordings endpoint returns the last 7
                    // days' buckets, omitting empty days entirely).
                    val dayList = groupRecordingsByDay(recordings)
                    dayAdapter.submitList(dayList)
                    binding.progressBar.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    // v1.6.8: empty-state container holds icon +
                    // tvEmpty + tvEmptyHint — toggle as a unit.
                    binding.emptyStateContainer.visibility =
                        if (recordings.isEmpty()) View.VISIBLE else View.GONE
                    if (recordings.isEmpty()) {
                        binding.tvEmpty.text = if (resp.isSuccess) "暂无录像" else "加载失败"
                        binding.tvEmptyHint.text = if (resp.isSuccess)
                            "请稍后再试或检查摄像头状态"
                        else "错误: ${resp.message}"
                    }

                    // v1.6.1: now that allRecordings is populated,
                    // fire the deferred alert-click jump if any.
                    // v1.6.2: removed the auto-open-day-picker
                    // branch — the per-day list is the default.
                    if (recordings.isNotEmpty() && pendingInitialTimestamp > 0L) {
                        val ts = pendingInitialTimestamp
                        pendingInitialTimestamp = 0L
                        openDayForTimestamp(ts)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("RecordingsDialog", "Exception loading recordings: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.emptyStateContainer.visibility = View.VISIBLE
                    binding.tvEmpty.text = "加载失败"
                    binding.tvEmptyHint.text = e.message ?: "未知错误"
                }
            }
        }
    }

    /**
     * v1.6.2: groups a flat list of [Recording] (each a 60s bucket)
     * by LOCAL date and produces a list of [DayRecording] items for
     * [DayRecordingAdapter]. Days with no recordings are NOT
     * synthesized — the backend's /recordings endpoint only returns
     * buckets that actually exist (last 7 days), so empty days
     * never reach this function.
     *
     * The resulting list is sorted by date DESC (most recent first)
     * because that matches the typical mental model: "what happened
     * today / yesterday" is at the top, older days at the bottom.
     *
     * Timezone: hardcoded Asia/Shanghai — see [formatDayTime] for
     * why (JVM-default tz is unreliable on the user's device).
     */
    private fun groupRecordingsByDay(recordings: List<Recording>): List<DayRecording> {
        val parseFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val tz = TimeZone.getTimeZone("Asia/Shanghai")
        val dayMap = mutableMapOf<String, MutableList<Recording>>()
        for (rec in recordings) {
            val instant = try { parseFmt.parse(rec.startAt)?.time ?: continue } catch (_: Exception) { continue }
            val cal = Calendar.getInstance(tz).apply { timeInMillis = instant }
            // Truncate to LOCAL 00:00 — used both as the map key and
            // (later) as the dayFilterStart for playDayAsPlaylist.
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val key = String.format(Locale.US, "%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH))
            dayMap.getOrPut(key) { mutableListOf() }.add(rec)
        }
        return dayMap.entries.map { (key, recs) ->
            DayRecording(
                dayStartCalendar = Calendar.getInstance(tz).apply {
                    // Re-parse the key into a Calendar — easier than
                    // passing the truncated Calendar through the map.
                    val parts = key.split("-")
                    set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(),
                        0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                },
                recordingCount = recs.size,
                totalDurationSeconds = recs.sumOf { it.durationSeconds },
                totalSizeBytes = recs.sumOf { it.sizeBytes },
            )
        }.sortedByDescending { it.dayStartCalendar.timeInMillis }
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
     *
     * v1.6.2: deleted. The per-day list is the default entry now,
     * so no separate date picker dialog is needed — the user just
     * taps a row in the list to play that day.
     */

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
            // v1.6.4 rev6: release gesture helper + hide the centered
            // pause button so it doesn't linger if the user re-opens
            // playback for another day.
            gestureHelper?.release()
            gestureHelper = null
            binding.btnCenterPause.visibility = View.GONE
            binding.btnPlaybackSpeed.visibility = View.GONE
            // v1.6.5 rev7: hide the new slider bar + seek-hint overlays
            // so they don't linger between day-playback sessions.
            binding.speedBarContainer.visibility = View.GONE
            binding.tvSeekRewindHint.visibility = View.GONE
            binding.tvSeekForwardHint.visibility = View.GONE
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
                hideOnFullscreen = listOf(binding.toolbar),
                speedButton = binding.btnPlaybackSpeed,
                // v1.6.4: pass playerHostFrame as the secondary view
                // so applyHeight expands it to MATCH_PARENT in
                // fullscreen (covers the chip scroller + scrub bar
                // area). Without this, the playerHostFrame stays at
                // its XML 200dp and playerView (even when set to
                // MATCH_PARENT) is still clipped to 200dp tall.
                secondaryPlayerView = binding.playerHostFrame,
                fullscreenButton = binding.btnFullscreen,
                // v1.6.4: views that follow the ExoPlayer controller
                // visibility while in fullscreen — the user asked:
                // "全屏时，点击播放器收起进度条等的插件，再次点击
                // 是在展现". These views show when the controller shows
                // and hide when it hides (auto-timeout or tap).
                controllerSyncViews = listOf(
                    binding.dayScrubBarContainer,
                    binding.motionChipScroller,
                    binding.btnBack,
                    binding.btnPlaybackSpeed,
                    // v1.6.4 rev6: include the centered pause button
                    // so it follows the same show/hide cycle as the
                    // rest of the chrome in fullscreen.
                    binding.btnCenterPause,
                    // v1.6.5 rev7: include the new speed slider bar
                    // + seek hint overlays so they follow the same
                    // show/hide cycle in fullscreen.
                    binding.speedBarContainer,
                ),
            )
            helper.attach()
            fullscreenHelper = helper
        }
        fullscreenHelper?.onPlayerChanged(player)
        // v1.6.4 rev6: attach the gesture helper once. Subsequent
        // day-changes call onPlayerChanged to keep the helper's
        // player reference fresh without re-wiring touch listeners.
        // v1.6.5 rev7: pass the seek hint TextViews so double-tap
        // can blink the 《》 overlays.
        if (gestureHelper == null) {
            val gh = PlayerGestureHelper(
                playerView = binding.playerView,
                pauseButton = binding.btnCenterPause,
                // v1.6.5 rev7: pass the seek hint TextViews so
                // double-tap can blink the 《》 overlays twice.
                seekRewindHint = binding.tvSeekRewindHint,
                seekForwardHint = binding.tvSeekForwardHint,
            )
            gh.attach(player)
            gestureHelper = gh
            // v1.6.5 rev7: wire the speed Slider. Replaces the
            // dropdown ListPopupWindow as the primary speed control
            // per user request "变速条改为可滑动的，最高切到5x吧".
            binding.speedSlider.value = 1.0f
            binding.tvSpeedLabel.text = "1x"
            binding.speedSlider.addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                val p = player ?: return@addOnChangeListener
                p.playbackParameters = PlaybackParameters(value)
                binding.tvSpeedLabel.text = formatSpeedLabel(value)
                // Sync gesture helper so a subsequent long-press
                // release restores the slider's value (not a stale
                // pre-slider snapshot).
                gestureHelper?.onSpeedChangedBySlider(value)
            }
        } else {
            gestureHelper?.onPlayerChanged(player)
        }
        // v1.6.5 rev7: show the slider bar; keep btnPlaybackSpeed
        // hidden (it's only kept for PlayerFullscreenHelper's
        // attachSpeedButton backward compat — the slider is the
        // primary control now).
        binding.speedBarContainer.visibility = View.VISIBLE
        binding.btnFullscreen.visibility = View.VISIBLE
    }

    /**
     * v1.6.5 rev7: formats a float speed as "1x" / "1.5x" / "5x" for
     * the speed slider's label. Strips trailing ".0" for integer
     * speeds so the label doesn't jump between "1.0x" and "1.5x"
     * visually (matches the previous btnPlaybackSpeed text style).
     */
    private fun formatSpeedLabel(speed: Float): String {
        return if (speed == speed.toInt().toFloat()) "${speed.toInt()}x"
               else "${speed}x"
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
        // v1.6.3: clear any old chips.
        binding.motionChipContainer.removeAllViews()
        binding.motionChipScroller.visibility = View.GONE

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
                // a @Serializable data class for List<MotionRange>
                // because the response shape (a bare JSON array of
                // objects with start/end/duration/motion_score/...)
                // doesn't match the standard envelope shape that
                // ApiResponse.decodeData<T>() expects (a JSON object
                // T directly under data).
                // v1.6.3: parse the new enriched fields (duration,
                // motion_score, segment_count, peak_objects) so we
                // can populate the chip list with intensity coloring.
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
                // v1.6.3: per-range enriched metadata for the chip list.
                val chipData = mutableListOf<MotionChip>()
                val aiIndices = mutableSetOf<Int>()
                // v1.6.5 rev7: per-range tier info for the AlertRangeOverlay
                // so the SeekBar markers match the chip tier colors.
                val tieredOverlayRanges = mutableListOf<AlertRangeOverlay.TieredRange>()
                for ((idx, item) in rangesArr.withIndex()) {
                    val obj = try { item.jsonObject } catch (_: Exception) { continue }
                    val startUnix = try { obj["start"]?.jsonPrimitive?.long ?: continue } catch (_: Exception) { continue }
                    val endUnix = try { obj["end"]?.jsonPrimitive?.long ?: continue } catch (_: Exception) { continue }
                    val duration = try { obj["duration"]?.jsonPrimitive?.long ?: (endUnix - startUnix) } catch (_: Exception) { endUnix - startUnix }
                    val score = try { obj["motion_score"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 } catch (_: Exception) { 0 }
                    val segCount = try { obj["segment_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1 } catch (_: Exception) { 1 }
                    val peak = try { obj["peak_objects"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 } catch (_: Exception) { 0 }
                    val startMs = startUnix * 1000L - dayStartLocalMillis
                    val endMs = endUnix * 1000L - dayStartLocalMillis
                    val clampedStart = startMs.coerceAtLeast(0L)
                    val clampedEnd = endMs.coerceAtMost(dayTotal)
                    if (clampedEnd > clampedStart) {
                        dayRanges.add(clampedStart to clampedEnd)
                        chipData.add(MotionChip(
                            startUnixSec = startUnix,
                            startRelativeMs = clampedStart,
                            endRelativeMs = clampedEnd,
                            durationSec = duration,
                            motionScore = score,
                            segmentCount = segCount,
                            peakObjects = peak,
                        ))
                        if (peak > 0) aiIndices.add(idx)
                        // v1.6.5 rev7: classify this range into a tier
                        // matching the chip color buckets. The thresholds
                        // mirror backend tierOf(): peak>0=ALERT, motion<=2=LOW,
                        // motion<=5=MID, else HIGH.
                        val tier = when {
                            peak > 0 -> AlertRangeOverlay.TIER_ALERT
                            score <= 2 -> AlertRangeOverlay.TIER_LOW
                            score <= 5 -> AlertRangeOverlay.TIER_MID
                            else -> AlertRangeOverlay.TIER_HIGH
                        }
                        tieredOverlayRanges.add(
                            AlertRangeOverlay.TieredRange(clampedStart, clampedEnd, tier)
                        )
                    }
                }

                android.util.Log.d("RecordingsDialog",
                    "Motion overlay: ${dayRanges.size} ranges for camera ${camera.id} " +
                    "name='${camera.name}' ai=${aiIndices.size}")

                withContext(Dispatchers.Main) {
                    motionRangesRelative = dayRanges
                    // v1.6.5 rev7: use setTieredRanges so the SeekBar
                    // markers match the chip tier colors (teal/amber/
                    // orange/red). User said "把展示出来的chip区间大概
                    // 标注再进度条上面吧".
                    binding.alertOverlay.setTieredRanges(tieredOverlayRanges)
                    // v1.6.3: populate the horizontal chip scroller.
                    populateMotionChips(chipData, dayStartLocalMillis)
                }
            } catch (e: Exception) {
                android.util.Log.w("RecordingsDialog",
                    "Failed to load motion ranges: ${e.message}", e)
            }
        }
    }

    /**
     * v1.6.3: populates the [motionChipScroller] with one chip per
     * motion range. Each chip is an [item_motion_chip.xml] TextView
     * with background color encoding the motion_score (see
     * [bg_chip_motion_*] drawables). Tapping a chip seeks ExoPlayer
     * to the range's start via [seekToMotionStart].
     *
     * Chips are sorted by start time (oldest first, left-to-right)
     * so the user can scrub through the day chronologically. We cap
     * the chip count at 200 to prevent pathological days from
     * slowing the UI (a 24h Frigate recording with constant motion
     * could produce ~360 chips at the v1.6.3 2s merge threshold).
     * When capped, we keep the highest-motion_score chips — the
     * quiet ones are less interesting anyway.
     *
     * [dayStartLocalMillis] is passed for formatting the time label
     * on each chip (e.g. "14:32:05") — chip.startUnixSec is in UTC,
     * we convert to LOCAL for display.
     */
    private fun populateMotionChips(
        chips: List<MotionChip>,
        dayStartLocalMillis: Long,
    ) {
        val container = binding.motionChipContainer
        container.removeAllViews()
        if (chips.isEmpty()) {
            binding.motionChipScroller.visibility = View.GONE
            return
        }
        // v1.6.3: cap chip count. Sort by motion_score desc, take top N,
        // then re-sort by start time asc for display. This keeps the
        // most significant motion events while preventing pathological
        // days from slowing the UI.
        // v1.6.4 rev4: reduced from 200 -> 60 (fit-to-screen mode).
        // v1.6.4 rev5: bumped back to 100 since we returned to scrolling.
        // Scrolling means more chips is fine — the user can swipe through
        // them; the fisheye transform keeps the centered chip readable
        // while edge chips collapse to thin colored bars. 100 is a
        // compromise between "show the whole day's activity" and "don't
        // load 1000+ TextViews on a busy day".
        val cap = 100
        val sorted = if (chips.size > cap) {
            chips.sortedByDescending { it.motionScore }.take(cap).sortedBy { it.startRelativeMs }
        } else {
            chips.sortedBy { it.startRelativeMs }
        }
        // Find the max motion_score in the visible set so we can
        // bucket chips into low/mid/high color tiers.
        val maxScore = sorted.maxOf { it.motionScore }.coerceAtLeast(1)
        // v1.6.3: time formatter — chip.startUnixSec is UTC unix seconds,
        // convert to LOCAL for display using Asia/Shanghai tz. Reuses
        // the same SimpleDateFormat pattern as formatDayTime().
        val tz = TimeZone.getTimeZone("Asia/Shanghai")
        val fmt = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }

        val inflater = LayoutInflater.from(context)

        // v1.6.6 rev8: clear the chip-ref map before re-populating.
        childIndexToMotionChip.clear()

        // v1.6.6 rev8: collapse runs of consecutive LOW-tier chips into
        // a single "⋯" chip. User said: "一系列连续绿色的之间就用省
        // 略号或其他形式代替，你觉咋好看咋来，就算翻到中间也不用展
        // 开". Previously 100+ green chips filled the scroller with low-
        // value "quiet period" markers; now each run of >= 3 consecutive
        // LOW-tier chips collapses into a single ellipsis chip (teal
        // background, "⋯" label, non-time formatted). Clicking the
        // ellipsis chip seeks to the middle of the run's time range so
        // the user can still jump there if they want.
        //
        // The ellipsis chip's label is "⋯" (U+22EF HORIZONTAL ELLIPSIS)
        // — a single character that renders as three centered dots.
        // FisheyeChipScroller's textThreshold logic treats it as text
        // (visible when centered, hidden when collapsed to edge) so
        // it visually behaves like other chips.
        //
        // Run-length threshold: 3. Two consecutive LOW chips stay as
        // individual chips (they might be brief quiet moments between
        // events); 3+ in a row is clearly a "quiet stretch" worth
        // collapsing.
        val minRunLen = 3
        data class ChipRun(val startIdx: Int, val endIdx: Int, val isLow: Boolean)
        val runs = mutableListOf<ChipRun>()
        var runStart = 0
        var runIsLow = isLowMotionTier(sorted[0], maxScore)
        for (i in 1 until sorted.size) {
            val curIsLow = isLowMotionTier(sorted[i], maxScore)
            if (curIsLow != runIsLow) {
                runs.add(ChipRun(runStart, i - 1, runIsLow))
                runStart = i
                runIsLow = curIsLow
            }
        }
        runs.add(ChipRun(runStart, sorted.size - 1, runIsLow))

        // Container's child index → MotionChip lookup, used by
        // scrollToCenterChip after population to find the chip closest
        // to current playback. We track an "logical chip index" so the
        // ellipsis chips still count toward the "find nearest" pass —
        // the user's current position might be inside a quiet stretch.
        val containerToSortedIdx = mutableListOf<Int>()

        for (run in runs) {
            val runLen = run.endIdx - run.startIdx + 1
            if (run.isLow && runLen >= minRunLen) {
                // v1.6.6 rev8: collapse this LOW run into a single
                // "⋯" chip. Click → seek to the middle chip of the
                // run (visually meaningful — lands in the middle of
                // the quiet stretch).
                val tv = inflater.inflate(R.layout.item_motion_chip, container, false) as android.widget.TextView
                tv.text = "⋯"
                tv.tag = "⋯" // tag used by FisheyeChipScroller for text hide/restore
                tv.setBackgroundResource(R.drawable.bg_chip_motion_low)
                val midChip = sorted[(run.startIdx + run.endIdx) / 2]
                tv.setOnClickListener { seekToMotionStart(midChip) }
                container.addView(tv)
                // v1.6.6 rev8: track chip ref so seekToMotionStart can
                // find this ellipsis chip by midChip reference.
                childIndexToMotionChip[container.childCount - 1] = midChip
                containerToSortedIdx.add((run.startIdx + run.endIdx) / 2)
            } else {
                // Non-collapsed run: emit each chip individually.
                for (i in run.startIdx..run.endIdx) {
                    val chip = sorted[i]
                    val tv = inflater.inflate(R.layout.item_motion_chip, container, false) as android.widget.TextView
                    val label = fmt.format(Date(chip.startUnixSec * 1000L))
                    tv.text = label
                    tv.tag = label
                    val bgRes = when {
                        chip.peakObjects > 0 -> R.drawable.bg_chip_motion_alert
                        chip.motionScore >= maxScore * 3 / 4 -> R.drawable.bg_chip_motion_high
                        chip.motionScore >= maxScore / 4 -> R.drawable.bg_chip_motion_mid
                        else -> R.drawable.bg_chip_motion_low
                    }
                    tv.setBackgroundResource(bgRes)
                    tv.setOnClickListener { seekToMotionStart(chip) }
                    container.addView(tv)
                    // v1.6.6 rev8: track chip ref so seekToMotionStart
                    // can find this chip by reference.
                    childIndexToMotionChip[container.childCount - 1] = chip
                    containerToSortedIdx.add(i)
                }
            }
        }
        binding.motionChipScroller.visibility = View.VISIBLE
        // v1.6.4 rev5: scroll the fisheye scroller so the chip nearest
        // to the current ExoPlayer playback position is centered in
        // the viewport. This gives the user an immediate "you are here"
        // focal point when the chip list first appears — the centered
        // chip is the one scaled to full size with its "HH:mm" label
        // visible. Without this, the scroller opens at position 0 and
        // the user has to scroll to find their current playback moment.
        val p = player
        if (p != null && clipStartOffsets.isNotEmpty() &&
            p.currentWindowIndex in clipStartOffsets.indices) {
            val currentMs = clipStartOffsets[p.currentWindowIndex] + p.currentPosition
            // Find the LOGICAL chip (in [sorted]) whose startMs is
            // closest to current playback, then translate to the
            // CONTAINER child index via containerToSortedIdx.
            var bestSortedIdx = 0
            var bestDelta = Long.MAX_VALUE
            for (i in sorted.indices) {
                val delta = abs(sorted[i].startRelativeMs - currentMs)
                if (delta < bestDelta) {
                    bestDelta = delta
                    bestSortedIdx = i
                }
            }
            // Find the container child whose sorted-idx is closest
            // to bestSortedIdx. For collapsed runs, the ellipsis chip
            // represents the middle of the run, so we pick the ellipsis
            // chip whose midIdx is closest to bestSortedIdx.
            var bestContainerIdx = 0
            var bestContainerDelta = Int.MAX_VALUE
            for (cIdx in containerToSortedIdx.indices) {
                val sortedIdx = containerToSortedIdx[cIdx]
                val delta = abs(sortedIdx - bestSortedIdx)
                if (delta < bestContainerDelta) {
                    bestContainerDelta = delta
                    bestContainerIdx = cIdx
                }
            }
            binding.motionChipScroller.scrollToCenterChip(bestContainerIdx)
        }
    }

    /**
     * v1.6.6 rev8: returns true if [chip] falls into the LOW color
     * tier (the teal/green bucket) — peakObjects==0 AND motionScore
     * is in the bottom quartile of the visible chip set. Mirrors the
     * tier assignment in populateMotionChips's `bgRes` when block.
     */
    private fun isLowMotionTier(chip: MotionChip, maxScore: Int): Boolean {
        if (chip.peakObjects > 0) return false
        return chip.motionScore < maxScore / 4
    }

    /**
     * v1.6.3: seeks ExoPlayer to the start of the given motion range.
     * Reuses the same binary-search + seekTo(window, position) logic
     * as the SeekBar onStopTrackingTouch handler. After seeking we
     * also scroll the chip scroller so the tapped chip becomes
     * visible (helpful when the user taps a chip that's off-screen
     * after a previous chip tap scrolled the list).
     * v1.6.4 rev5: now also calls [FisheyeChipScroller.scrollToCenterChip]
     * so the tapped chip is centered in the viewport (full-size with
     * label visible). The user said "我想要滑动的时候，中间大的chip跟
     * 着替换" — tapping a chip should make it the new focal point.
     */
    private fun seekToMotionStart(chip: MotionChip) {
        val p = player ?: return
        val progress = chip.startRelativeMs
        // Binary-search clipStartOffsets for the target window.
        val raw = clipStartOffsets.binarySearch(progress)
        val idx = if (raw >= 0) raw else (-raw - 2).coerceAtLeast(0)
        val targetWindow = idx.coerceIn(0, clipStartOffsets.size - 1)
            .coerceAtMost(p.mediaItemCount - 1)
        val targetPosition = (progress - clipStartOffsets[targetWindow])
            .coerceIn(0L, dayClipDurationMs)
        p.seekTo(targetWindow, targetPosition)
        binding.daySeekBar.progress = progress.toInt()
        binding.tvDayPosition.text = formatDayTime(progress)
        // v1.6.4 rev5: center the tapped chip in the viewport so it
        // becomes the focal point (full-size, label visible). The
        // user said "我想要滑动的时候，中间大的chip跟着替换".
        // v1.6.6 rev8: previously we matched by formatted "HH:mm" label,
        // but collapsed "⋯" chips share the same label across multiple
        // runs — a label match would find the wrong chip. Now we look
        // up by chip reference via [childIndexToMotionChip] (populated
        // in [populateMotionChips]). Falls back to label matching for
        // backward-compat if the map isn't populated (e.g. legacy
        // chips emitted before the map was added).
        var foundIdx = -1
        for ((idx, ref) in childIndexToMotionChip) {
            if (ref === chip) {
                foundIdx = idx
                break
            }
        }
        if (foundIdx < 0) {
            // Fallback: match by formatted label (only works for
            // non-ellipsis chips).
            val tz2 = TimeZone.getTimeZone("Asia/Shanghai")
            val fmt2 = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz2 }
            val targetLabel = fmt2.format(Date(chip.startUnixSec * 1000L))
            val container = binding.motionChipContainer
            for (i in 0 until container.childCount) {
                val tv = container.getChildAt(i)
                if ((tv.tag as? String) == targetLabel) {
                    foundIdx = i
                    break
                }
            }
        }
        if (foundIdx >= 0) {
            binding.motionChipScroller.scrollToCenterChip(foundIdx)
        }
        android.util.Log.d("RecordingsDialog",
            "Chip tap: seek to window=$targetWindow pos=${targetPosition}ms " +
            "(day-relative ${progress}ms = ${formatDayTime(progress)})")
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

    /**
     * v1.6.2: deleted [playRecording]. v1.5.x exposed a per-clip
     * list (item_recording.xml + RecordingAdapter) where tapping a
     * row played that single 60s bucket via this method. v1.6.2
     * replaced the per-clip list with the per-day list, so this
     * method became dead code. The fullscreen + speed button setup
     * that lived here is duplicated in [playDayAsPlaylist] (which is
     * the only playback path now).
     */

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
        // v1.6.4 rev6: release the gesture helper's auto-hide handler
        // + touch listener so the dismissed dialog doesn't leak.
        gestureHelper?.release()
        gestureHelper = null
        super.dismiss()
    }

    /**
     * v1.6.3: data class for a single motion chip in the horizontal
     * scroller above the SeekBar. Mirrors the v1.6.3 backend
     * [MotionRange] struct but holds both unix-seconds (for the seek
     * call) and day-relative-millis (for SeekBar/overlay positioning).
     *
     * [startUnixSec] is needed for time formatting (UTC -> LOCAL),
     * [startRelativeMs] / [endRelativeMs] are needed for the seek logic.
     * [motionScore] / [peakObjects] drive the chip background color.
     */
    data class MotionChip(
        val startUnixSec: Long,
        val startRelativeMs: Long,
        val endRelativeMs: Long,
        val durationSec: Long,
        val motionScore: Int,
        val segmentCount: Int,
        val peakObjects: Int,
    )

    /**
     * v1.6.2: data class for the per-day list view. Each instance
     * represents one LOCAL day's worth of recordings (aggregated
     * from the backend's 60s buckets). The [dayStartCalendar] is
     * LOCAL 00:00 of that day — pass it directly to
     * [playDayAsPlaylist] as the dayFilterStart.
     */
    data class DayRecording(
        val dayStartCalendar: Calendar,
        val recordingCount: Int,
        val totalDurationSeconds: Int,
        val totalSizeBytes: Long,
    )

    /**
     * v1.6.2: replaces v1.5.x's [RecordingAdapter] as the default
     * list adapter for [RecordingsDialog]. Each row shows one day's
     * recording summary and is tappable to launch full-day playback.
     *
     * Layout: [item_day_recording.xml]
     *  - Large day-of-month + small month/year on the left
     *  - Weekday + recording count + total duration in the middle
     *  - Green "N 段" status chip on the right (always green since
     *    the backend only returns days with recordings — empty days
     *    are not synthesized)
     *
     * Removed: the v1.5.x thumbnail + per-60s-bucket duration list
     * (item_recording.xml + RecordingAdapter). The per-clip view is
     * not reachable from the UI anymore — the user explicitly asked
     * for "以整天查看为先", so the per-day list is the only entry.
     */
    class DayRecordingAdapter(
        private val onPlayDay: (DayRecording) -> Unit,
    ) : ListAdapter<DayRecording, DayRecordingAdapter.DayViewHolder>(DiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
            val b = ItemDayRecordingBinding.inflate(
                LayoutInflater.from(parent.context), parent, false)
            return DayViewHolder(b)
        }

        override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class DayViewHolder(
            private val binding: ItemDayRecordingBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(day: DayRecording) {
                val cal = day.dayStartCalendar
                // Date block.
                binding.tvDayOfMonth.text =
                    cal.get(Calendar.DAY_OF_MONTH).toString()
                binding.tvMonthYear.text = String.format(Locale.US,
                    "%04d年%d月", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
                // Weekday — Locale.CHINA gives "星期X" in Chinese.
                binding.tvWeekday.text = String.format(Locale.CHINA, "%tA", cal)
                // Stats: "N 段 · HH:mm:ss 总时长"
                val h = day.totalDurationSeconds / 3600
                val m = (day.totalDurationSeconds % 3600) / 60
                val s = day.totalDurationSeconds % 60
                val durStr = if (h > 0) String.format("%d时%02d分", h, m)
                             else String.format("%02d分%02d秒", m, s)
                binding.tvStats.text = "${day.recordingCount} 段 · $durStr"
                // v1.6.8: status chip now shows the total duration in
                // compact form (e.g. "5h12m" or "42m") instead of
                // duplicating the segment count already shown in
                // tvStats. This gives the user two complementary
                // pieces of info at a glance: row text = "N 段 · HH时MM分"
                // (precise), chip = "5h12m" (compact). Compact form
                // is built without locale-specific separators so it
                // stays short inside the pill.
                val compactDur = if (h > 0) "${h}h${m}m"
                                 else "${m}m${s}s"
                binding.tvStatus.text = compactDur
                // v1.6.8: show "今天" badge when the card's LOCAL day
                // matches the current LOCAL day. Compare by ERA + YEAR
                // + DAY_OF_YEAR to avoid false matches across year
                // boundaries. The badge is gone by default in the
                // layout; we only flip to VISIBLE here.
                val today = Calendar.getInstance()
                val isToday = cal.get(Calendar.ERA) == today.get(Calendar.ERA) &&
                              cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                              cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
                binding.tvTodayBadge.visibility =
                    if (isToday) View.VISIBLE else View.GONE
                binding.root.setOnClickListener { onPlayDay(day) }
            }
        }

        class DiffCallback : DiffUtil.ItemCallback<DayRecording>() {
            override fun areItemsTheSame(oldItem: DayRecording, newItem: DayRecording): Boolean {
                return oldItem.dayStartCalendar.get(Calendar.YEAR) == newItem.dayStartCalendar.get(Calendar.YEAR) &&
                       oldItem.dayStartCalendar.get(Calendar.DAY_OF_YEAR) == newItem.dayStartCalendar.get(Calendar.DAY_OF_YEAR)
            }
            override fun areContentsTheSame(oldItem: DayRecording, newItem: DayRecording): Boolean {
                return oldItem == newItem
            }
        }
    }
}
