package com.mymts.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import com.mymts.soak.SoakLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wraps a single ExoPlayer instance. HLS only.
 *
 * Stage 2 Part B rewrite: the player's surface state is now driven by
 * [LivenessTracker] — a frame-age-aware state machine that honors Trust
 * Bar **C3** ("staleness is never silent") and **C2** ("a dead feed is a
 * non-event"). The Stage 1 player reported `LIVE` while the surface was
 * frozen for 10+ hours; this version transitions to `STALE` within a
 * tight window, runs a bounded recovery ladder (prepare → re-init), and
 * settles into `DEAD` rather than thrashing forever.
 *
 * Frame-arrival signals fed into the tracker:
 *   - `Player.Listener.onRenderedFirstFrame` (initial render + variant switches)
 *   - `AnalyticsListener.onDroppedVideoFrames` (any callback = decoder is alive,
 *      even when zero frames were actually dropped)
 *
 * Tick cadence: every 2 s on the main thread. That's fast enough to catch
 * a 15-s threshold violation inside the threshold window, slow enough that
 * 6 concurrent tickers cost essentially nothing on the Onn box.
 */
@OptIn(UnstableApi::class)
class StreamPlayer(
    private val context: Context,
    private val spec: StreamSpec,
    /** Override for tests / Stage 6 hardening. Default per `ARCHITECTURE.md`. */
    staleThresholdMs: Long = 15_000L,
    maxRecoveryAttempts: Int = 3,
    backoffMs: List<Long> = listOf(2_000L, 8_000L, 30_000L),
    private val tickIntervalMs: Long = TICK_INTERVAL_MS,
) {
    /** Re-exported so callers (SoakHarness, future grid UI) work in one type. */
    enum class State {
        CONNECTING, LIVE, STALE, RECOVERING, DEAD, OFFLINE;

        companion object {
            fun from(s: LivenessTracker.State): State = when (s) {
                LivenessTracker.State.CONNECTING -> CONNECTING
                LivenessTracker.State.LIVE -> LIVE
                LivenessTracker.State.STALE -> STALE
                LivenessTracker.State.RECOVERING -> RECOVERING
                LivenessTracker.State.DEAD -> DEAD
                LivenessTracker.State.OFFLINE -> OFFLINE
            }
        }
    }

    private var player: ExoPlayer? = null
    private var listener: Player.Listener? = null
    private var analyticsListener: AnalyticsListener? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * The stream URL currently bound to the player. Starts at [spec].url and is
     * re-pointed by [updateUrl] when the channel's RESOLVED url rotates (e.g. a
     * YouTube manifest `expire` token) WITHOUT rebuilding the player — so a token
     * refresh re-prepares only this one tile, never the whole grid (P-N3). The
     * recovery ladder ([createPlayer] via REINIT) reads this so it reconnects to
     * the latest url, not the stale construction-time one.
     */
    @Volatile private var currentUrl: String = spec.url

    /** The live-edge watchdog's correction history (see [decideLiveEdgeCorrection]). */
    private var liveEdgeHistory = LiveEdgeHistory()

    private val tracker = LivenessTracker(
        staleThresholdMs = staleThresholdMs,
        maxRecoveryAttempts = maxRecoveryAttempts,
        backoffMs = backoffMs,
        onTransition = { from, to ->
            // Re-publish into our StateFlow + the soak log in one place.
            _state.value = State.from(to)
            SoakLog.stateChange(spec.id, from.name, to.name)
        },
    )

    private val _state = MutableStateFlow(State.CONNECTING)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Whether this stream currently advertises a **soft caption / subtitle
     * track** (CEA-608/708, WebVTT, etc.). Updates on `onTracksChanged`
     * — false at construction, flips to true if the master / variant
     * resolves to a manifest with an `#EXT-X-MEDIA:TYPE=SUBTITLES` or
     * `TYPE=CLOSED-CAPTIONS` declaration that ExoPlayer parses.
     *
     * The wall's [com.mymts.ui.menu.SlotControlsOverlay] reads this so
     * the Captions row can surface honest "not available on this
     * channel" when the stream has no track to toggle — distinct from
     * "captions off" (a track exists but the operator hasn't enabled it).
     */
    private val _hasSoftCaptionTrack = MutableStateFlow(false)
    val hasSoftCaptionTrack: StateFlow<Boolean> = _hasSoftCaptionTrack.asStateFlow()

    /**
     * The rendered video's aspect ratio (width/height, PAR-corrected), or null
     * until the first decoded frame reports a size. The wall reads this to lay
     * the channel label in the letterbox BELOW the actual rendered video (its
     * original aesthetic) using real dimensions — a 16:9 stream in a taller cell
     * leaves a black bar below the picture where the label sits, in the visible
     * area rather than the overscan-clipped tile edge.
     */
    private val _videoAspect = MutableStateFlow<Float?>(null)
    val videoAspect: StateFlow<Float?> = _videoAspect.asStateFlow()

    /** Cumulative dropped frames since initialize(). Read by the soak harness. */
    @Volatile var droppedFrames: Int = 0
        private set

    /** Wall-clock ms of last frame-arrival signal; NO_FRAME until the first. */
    val lastFrameAtMs: Long get() = tracker.lastFrameAtMs

    val recoveryAttempts: Int get() = tracker.recoveryAttempts

    /** The id from the StreamSpec — exposed so the harness can attribute beats correctly. */
    val specId: String get() = spec.id

    private val tickRunnable: Runnable = object : Runnable {
        override fun run() {
            val action = tracker.onTick()
            when (action) {
                LivenessTracker.TickAction.NONE -> Unit
                LivenessTracker.TickAction.ATTEMPT_PREPARE -> {
                    Log.i(TAG, "[${spec.label}] recovery strike PREPARE")
                    SoakLog.recoveryStrike(spec.id, tracker.recoveryAttempts, "prepare")
                    try {
                        player?.prepare()
                    } catch (e: Exception) {
                        Log.w(TAG, "[${spec.label}] prepare() threw: $e")
                    }
                }
                LivenessTracker.TickAction.ATTEMPT_REINIT -> {
                    Log.i(TAG, "[${spec.label}] recovery strike REINIT")
                    SoakLog.recoveryStrike(spec.id, tracker.recoveryAttempts, "reinit")
                    releaseInternal()
                    createPlayer()
                }
                LivenessTracker.TickAction.SETTLE_DEAD -> {
                    Log.w(TAG, "[${spec.label}] settling DEAD after exhausting recovery")
                    SoakLog.settledDead(spec.id, tracker.recoveryAttempts)
                    releaseInternal()
                    // Do not re-schedule the tick — anti-loop. The tile shows
                    // an honest dead indicator and consumes no further CPU.
                    return
                }
            }
            // Live-edge watchdog (Part B): if this tile has drifted too far behind
            // live (a backlog accumulated), JUMP to the live edge — drop the stale
            // backlog instead of playing through it. Real-time over catch-up.
            // Bounded (MYMTS-034): a seek flushes the buffer and can land, or
            // rebuffer, past the threshold again, so decideLiveEdgeCorrection applies
            // hysteresis, a cooldown and a consecutive cap rather than re-seeking
            // every tick.
            player?.let { p ->
                val offsetMs = p.currentLiveOffset
                val decision = decideLiveEdgeCorrection(offsetMs, SystemClock.elapsedRealtime(), liveEdgeHistory)
                liveEdgeHistory = decision.history
                if (decision.seekToLive) {
                    val n = decision.history.consecutiveCorrections
                    Log.i(
                        TAG,
                        "[${spec.label}] live drift ${offsetMs}ms > ${LIVE_EDGE_TRIGGER_MS}ms — seek to live " +
                            "($n/$LIVE_EDGE_MAX_CONSECUTIVE_CORRECTIONS before holding until recovered or the stream changes)",
                    )
                    p.seekToDefaultPosition()
                }
            }
            handler.postDelayed(this, tickIntervalMs)
        }
    }

    fun initialize() {
        release()
        tracker.reset()
        _state.value = State.CONNECTING
        droppedFrames = 0
        createPlayer()
        handler.postDelayed(tickRunnable, tickIntervalMs)
    }

    /**
     * MANUAL reconnect/reload — a fresh player + manifest fetch, for when the
     * operator suspects the live feed has drifted or stalled (a deliberate
     * refresh, not an auto-recovery strike, so it resets the recovery state via
     * [initialize]). Honest (C3): if the fresh stream still can't play, the tile
     * settles into its real state — never faked-live.
     */
    fun reconnect() {
        Log.i(TAG, "[${spec.label}] manual reconnect requested")
        initialize()
    }

    private fun createPlayer() {
        // A new player is a new stream: the live-edge watchdog re-arms.
        liveEdgeHistory = LiveEdgeHistory()
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val loadControl = DefaultLoadControl.Builder()
            // Tight buffers so latency can't pile up behind live: get to playing
            // fast, never hoard a deep behind-live cushion (real-time over catch-up).
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, BUFFER_FOR_PLAYBACK_MS, BUFFER_FOR_REBUFFER_MS)
            .setTargetBufferBytes(4 * 1024 * 1024)
            .setBackBuffer(0, false)
            .build()

        val exo = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .build()

        val pl = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "[${spec.label}] error ${error.errorCodeName}: ${error.message}")
                SoakLog.error(spec.id, error.errorCodeName, error.message ?: "")
                // The error itself doesn't transition state — the tracker
                // decides based on actual frame age. The error is data on
                // the wire for the soak harness.
            }

            override fun onRenderedFirstFrame() {
                tracker.onFrameRendered()
                SoakLog.tileReady(spec.id)
            }

            override fun onTracksChanged(tracks: Tracks) {
                // Soft caption availability flips as soon as the manifest
                // parses and ExoPlayer reports its track groups. The
                // controls overlay observes the StateFlow we write here
                // so the Captions row can show "not available" honestly
                // for streams without a text track.
                val hasText = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
                _hasSoftCaptionTrack.value = hasText
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val w = videoSize.width
                val h = videoSize.height
                if (w > 0 && h > 0) {
                    val par = if (videoSize.pixelWidthHeightRatio > 0f) videoSize.pixelWidthHeightRatio else 1f
                    _videoAspect.value = (w * par) / h
                }
            }
        }
        listener = pl
        exo.addListener(pl)

        val al = object : AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                this@StreamPlayer.droppedFrames += droppedFrames
                SoakLog.droppedFrames(spec.id, droppedFrames, elapsedMs)
                // Decoder fired its dropped-frames callback — frames are
                // being processed (with some dropped). Treat as a positive
                // liveness signal alongside onRenderedFirstFrame.
                tracker.onFrameRendered()
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                SoakLog.decoderInit(spec.id, decoderName, initializationDurationMs)
            }
        }
        analyticsListener = al
        exo.addAnalyticsListener(al)

        exo.volume = 0f

        // AUDIO + TEXT renderers OFF by default at creation.
        //
        // AUDIO: the wall is muted by default (no audible slot), and an inaudible
        // tile must not DECODE audio (~16% CPU on a 4-tile wall, per the read-only
        // perf pass). `setAudible(true)` re-enables this renderer for the single
        // audible tile. Disabling it HERE (not just via the VideoGrid apply) closes
        // the gap a recovery REINIT opened: createPlayer() runs without re-firing
        // the VideoGrid audible-apply effect, so a freshly re-init'd tile (the
        // flaky-channel churn) would otherwise resume decoding audio nobody hears —
        // measured as media.swcodec bouncing 0→~15% as a tile re-init'd. Off-at-
        // create keeps the win robust across the recovery ladder.
        //
        // TEXT: a stream's soft caption track (CEA-608/708 / WebVTT) would auto-
        // select for the device language; we disable the whole TEXT renderer so
        // captions are deterministically off until toggled. Burned-in captions
        // (pixels in the video) are not a track and are unaffected.
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()

        // Live-edge adherence: aim for a small offset behind live, with an
        // imperceptibly narrow speed window for micro-correction only. GROSS drift
        // is corrected by the watchdog's seek-to-live (in the tick), NOT by
        // sprinting playback through the backlog (the catch-up feel to avoid).
        exo.setMediaSource(buildHlsSource(currentUrl))
        exo.playWhenReady = true
        exo.prepare()

        player = exo
    }

    /** Build the live HLS source for [url] (live-offset config shared by
     *  [createPlayer] and [updateUrl]). */
    private fun buildHlsSource(url: String): MediaSource {
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(TARGET_LIVE_OFFSET_MS)
                    .setMinPlaybackSpeed(MIN_PLAYBACK_SPEED)
                    .setMaxPlaybackSpeed(MAX_PLAYBACK_SPEED)
                    .build(),
            )
            .build()
        return HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
            .createMediaSource(mediaItem)
    }

    /**
     * Re-point this tile to a new RESOLVED url IN PLACE — same ExoPlayer, same
     * surface, no grid rebuild (P-N3). Used when a channel's resolved url rotates
     * (a YouTube manifest expire token) but it's the SAME channel: only this one
     * tile swaps its media source + re-prepares; the other tiles are untouched
     * (the old behaviour rebuilt every player when one url changed). The
     * audio-disabled / captions-off [TrackSelectionParameters] and the attached
     * surface persist (they live on the ExoPlayer, not the media source), so the
     * mute/captions/audible state is preserved. A no-op when the url is unchanged.
     * If the new url is dead, the existing liveness/recovery ladder settles the
     * tile honest-offline — unchanged. The tracker is intentionally NOT reset (this
     * is a continuation, not a fresh connect), so a quick re-prepare keeps LIVE.
     */
    fun updateUrl(newUrl: String) {
        if (newUrl == currentUrl) return
        currentUrl = newUrl
        liveEdgeHistory = LiveEdgeHistory() // a new source: the live-edge watchdog re-arms
        Log.i(TAG, "[${spec.label}] resolved url rotated — swapping media source in place")
        val exo = player ?: return // not yet created; createPlayer() will use currentUrl
        exo.setMediaSource(buildHlsSource(newUrl))
        exo.prepare()
    }

    /**
     * Make this tile audible (decode + play its audio) or inaudible.
     *
     * Inaudible tiles **DISABLE the audio renderer** (`setTrackTypeDisabled(
     * TRACK_TYPE_AUDIO, true)`) — not merely `volume = 0f` — so the AAC track
     * is NOT decoded. The wall is muted by default (no audible slot), so by
     * default every tile's audio renderer is OFF; on a 4-tile wall that's the
     * ~16% CPU the soft codec was spending decoding inaudible audio. The audible
     * tile ENABLES its renderer and plays at full volume. Single-audible-tile
     * ownership lives one layer up (LineupStore.audibleSlot → VideoGrid).
     *
     * `buildUpon()` copies the current params, so the TEXT-disabled (captions-off)
     * state set at create time is preserved — AUDIO + TEXT toggle independently.
     */
    fun setAudible(audible: Boolean) {
        val exo = player ?: return
        exo.volume = if (audible) 1f else 0f
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audible)
            .build()
    }

    /**
     * Toggle the soft caption track on/off for this player.
     *
     * Returns `true` if the stream carries a text track at all (whether
     * captions are now on or off); `false` if the stream has no text
     * track to toggle. Burned-in captions are pixels in the video and
     * cannot be removed here — see the per-channel caption table for
     * the honest map of what's a track vs what's burned in.
     */
    fun setCaptionsEnabled(enabled: Boolean): Boolean {
        val exo = player ?: return false
        val hasTextTrack = exo.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_TEXT
        }
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
        return hasTextTrack
    }

    /** True iff the underlying stream advertises a text/caption track. */
    fun hasSoftCaptionTrack(): Boolean {
        val exo = player ?: return false
        return exo.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
    }

    fun getPlayer(): ExoPlayer? = player

    /**
     * Jump to the live edge NOW, dropping any behind-live backlog (Part B/D). The
     * quick "resync" gesture and the drift watchdog both use this. A no-op if the
     * player is gone (released/dead) — the caller decides whether to reconnect.
     */
    fun seekToLive() {
        player?.seekToDefaultPosition()
    }

    /** True iff this player is in a non-recoverable/absent state where a resync
     *  should RECONNECT (fresh player) rather than just seek to live. */
    fun needsReconnect(): Boolean =
        player == null || _state.value == State.DEAD || _state.value == State.OFFLINE

    /**
     * Detach the player but DO NOT touch the tracker state machine — used
     * internally by the recovery ladder when we need a fresh ExoPlayer
     * without resetting the tracker's strike count.
     */
    private fun releaseInternal() {
        player?.let { p ->
            listener?.let { p.removeListener(it) }
            analyticsListener?.let { p.removeAnalyticsListener(it) }
            p.clearVideoSurface()
            p.stop()
            p.release()
        }
        listener = null
        analyticsListener = null
        player = null
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        releaseInternal()
        _state.value = State.OFFLINE
    }

    companion object {
        private const val TAG = "MyMTS.StreamPlayer"

        /** The liveness + live-edge watchdog tick cadence (see the class doc). */
        const val TICK_INTERVAL_MS = 2_000L

        // --- Live-edge + buffer tuning (Part B). The operator feel-tests + tunes
        //     these; they prioritize CURRENCY/real-time, not the hardware ceiling
        //     (a too-busy grid still needs fewer concurrent tiles — out of scope). ---
        /** Lag behind the live edge ExoPlayer aims to hold (small = current). */
        const val TARGET_LIVE_OFFSET_MS = 4_000L
        /** The drift line, ~2× target. The watchdog JUMPS to live (drops the backlog)
         *  only past [LIVE_EDGE_TRIGGER_MS] (this + the target), and counts a tile back
         *  at or under this line as recovered. */
        const val MAX_LIVE_DRIFT_MS = 8_000L
        /** Imperceptible micro-correction window only — gross drift is the seek, not this. */
        const val MIN_PLAYBACK_SPEED = 0.97f
        const val MAX_PLAYBACK_SPEED = 1.03f
        // Tight buffers: get-to-playing fast, don't accumulate a deep behind-live cushion.
        const val MIN_BUFFER_MS = 1_500
        const val MAX_BUFFER_MS = 3_000
        const val BUFFER_FOR_PLAYBACK_MS = 500
        const val BUFFER_FOR_REBUFFER_MS = 1_500
    }
}

/**
 * Pure: should the live-edge watchdog seek to live? True only when the measured
 * offset behind live exceeds [maxDriftMs]. A non-live / unknown window reports a
 * negative offset (`C.TIME_UNSET`), which is below any positive threshold → false.
 * Pure (no Media3) so it's unit-testable. The watchdog applies it at
 * [LIVE_EDGE_TRIGGER_MS] inside [decideLiveEdgeCorrection].
 */
internal fun shouldSeekToLive(currentLiveOffsetMs: Long, maxDriftMs: Long): Boolean =
    currentLiveOffsetMs > maxDriftMs
