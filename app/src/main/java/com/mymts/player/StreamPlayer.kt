package com.mymts.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionParameters
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
    private val tickIntervalMs: Long = 2_000L,
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

    private fun createPlayer() {
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 4000, 500, 1500)
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

        // Captions/subtitles off by default. Where the stream carries a
        // soft text track (CEA-608/708 or WebVTT — declared via the
        // HLS manifest's `#EXT-X-MEDIA:TYPE=SUBTITLES` /
        // `TYPE=CLOSED-CAPTIONS` lines), Media3 would auto-select one
        // for the device's default language; we disable the entire
        // TEXT renderer instead, deterministically off until the
        // operator toggles captions on per-tile from the menu. Burned-
        // in captions (pixels in the video itself — e.g. LiveNOW from
        // FOX's scrolling bar) are NOT a track and are unaffected;
        // those are an entirely separate concern documented in the
        // per-channel caption table.
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()

        val mediaItem = MediaItem.fromUri(spec.url)
        val source: MediaSource = HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
            .createMediaSource(mediaItem)
        exo.setMediaSource(source)
        exo.playWhenReady = true
        exo.prepare()

        player = exo
    }

    /**
     * Toggle the player's volume between muted and the audible level.
     *
     * The wall is muted by default and only one tile is meant to be
     * audible at a time (see `WallAudio`). This is a small surface for
     * the controls overlay; ownership of "which tile is audible" lives
     * one layer up.
     */
    fun setAudible(audible: Boolean) {
        player?.volume = if (audible) 1f else 0f
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
    }
}
