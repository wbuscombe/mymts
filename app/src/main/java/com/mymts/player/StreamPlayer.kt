package com.mymts.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
 * Adapted from WyzeGrid's StreamPlayer pattern — same player-lifecycle
 * discipline (surface cleanup before release, listener removal before release,
 * frame watchdog) — but with the camera/RTSP wiring removed per the
 * technical-approach hard boundary.
 *
 * Reconnect policy is light in Stage 1: log the failure, set state, let
 * the manager decide. Stage 6 will add the auto-recovery + exponential
 * backoff loop once the dead-tile budget for C2 is measured.
 */
@OptIn(UnstableApi::class)
class StreamPlayer(
    private val context: Context,
    private val spec: StreamSpec,
) {
    enum class State { CONNECTING, LIVE, RECONNECTING, OFFLINE }

    private var player: ExoPlayer? = null
    private var listener: Player.Listener? = null
    private var analyticsListener: AnalyticsListener? = null
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(State.CONNECTING)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Cumulative dropped frames since initialize(). Read by the soak harness. */
    @Volatile var droppedFrames: Int = 0
        private set

    /** Wall-clock ms of last rendered frame; 0 until first frame. */
    @Volatile var lastFrameAtMs: Long = 0L
        private set

    /** The id from the StreamSpec — exposed so the harness can attribute beats correctly. */
    val specId: String get() = spec.id

    fun initialize() {
        release()
        droppedFrames = 0
        lastFrameAtMs = 0L
        _state.value = State.CONNECTING
        createPlayer()
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
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> _state.value = State.LIVE
                    Player.STATE_BUFFERING -> if (_state.value == State.LIVE) {
                        _state.value = State.RECONNECTING
                    }
                    Player.STATE_ENDED, Player.STATE_IDLE -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "[${spec.label}] error ${error.errorCodeName}: ${error.message}")
                SoakLog.error(spec.id, error.errorCodeName, error.message ?: "")
                _state.value = State.RECONNECTING
            }

            override fun onRenderedFirstFrame() {
                lastFrameAtMs = System.currentTimeMillis()
                _state.value = State.LIVE
                SoakLog.tileReady(spec.id)
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
        val mediaItem = MediaItem.fromUri(spec.url)
        val source: MediaSource = HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
            .createMediaSource(mediaItem)
        exo.setMediaSource(source)
        exo.playWhenReady = true
        exo.prepare()

        player = exo
    }

    fun getPlayer(): ExoPlayer? = player

    fun release() {
        handler.removeCallbacksAndMessages(null)
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
        _state.value = State.OFFLINE
    }

    companion object {
        private const val TAG = "MyMTS.StreamPlayer"
    }
}
