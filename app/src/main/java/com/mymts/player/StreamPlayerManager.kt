package com.mymts.player

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Owns N StreamPlayers and their lifecycle.
 *
 * Lifecycle policy (adapted from a sibling TV app): players (re)initialize on
 * onStart, release on onDestroy. onStop merely pauses by clearing
 * playWhenReady (Stage 6 will tune this for the dim-window + watchdog
 * interplay).
 *
 * **Compose-observable readiness signal ([readyVersion]).** Composables
 * that bind a UI element to a player (e.g. the wall's [com.mymts.ui.wall.VideoGrid])
 * must subscribe to this state so they recompose when players actually
 * become available. The regression in `b19b013` (caught by telemetry —
 * `EV=TILE_READY=0` against a known-good stream) was that VideoGrid
 * cached `manager.player(idx)` in a `remember(manager, specs)` block
 * that evaluates **during composition**, before the `DisposableEffect`
 * that registers the lifecycle observer has run — so every cached
 * reference was null. With no subsequent invalidation, the tiles
 * permanently rendered the dead-panel path (visually identical to a
 * genuine C2 dead tile, which is why the regression was invisible).
 *
 * The fix: increment [readyVersion] inside [onStart] after players are
 * created. A composable that reads `readyVersion` subscribes to it,
 * causing Compose to invalidate its `remember` and re-bind the now-
 * non-null players. See `VideoGrid.kt` for the call-site pattern.
 */
class StreamPlayerManager(
    private val context: Context,
    val specs: List<StreamSpec>,
    /**
     * Override the player constructor for tests. Production passes the
     * default (the real [StreamPlayer]); tests pass a mock factory so
     * the readiness-signal behaviour can be exercised without spinning
     * up real ExoPlayer instances.
     */
    private val playerFactory: (Context, StreamSpec) -> StreamPlayer = { ctx, spec ->
        StreamPlayer(ctx, spec)
    },
) : DefaultLifecycleObserver {

    private val _players = mutableMapOf<Int, StreamPlayer>()
    val players: Map<Int, StreamPlayer> get() = _players

    /**
     * The specs currently bound, per tile index. Starts at the construction [specs]
     * and is re-pointed by [updateSpecs] when a channel's RESOLVED url rotates — so
     * a freshly-built player (onStart, or a recovery REINIT) uses the latest url,
     * and [updateSpecs] knows which tiles' urls actually changed. The manager is
     * keyed (by the call site) on the STABLE set of spec **ids**, so this tracks
     * url-only drift WITHIN a stable channel set (P-N3).
     */
    private var liveSpecs: List<StreamSpec> = specs

    /**
     * Increments each time the manager's player set becomes ready (after
     * `onStart`) or is torn down (after `onDestroy`). Composables observe
     * this so they re-bind after lifecycle events the call-site otherwise
     * has no synchronous handle on. Backed by a snapshot int state so
     * Compose tracks reads automatically.
     */
    private val _readyVersion = mutableIntStateOf(0)
    val readyVersion: State<Int> get() = _readyVersion

    fun player(idx: Int): StreamPlayer? = _players[idx]

    /** Manually reconnect ONE tile's stream (operator refresh of a drifted feed). */
    fun reconnect(idx: Int) {
        _players[idx]?.reconnect()
    }

    /** Manually reconnect EVERY tile's stream (the menu's "Refresh all video"). */
    fun reconnectAll() {
        _players.values.forEach { it.reconnect() }
    }

    /**
     * Quick RESYNC of ONE tile (Part D): jump to the live edge — dropping any
     * behind-live backlog — or RECONNECT if the tile is actually dead/absent.
     * Lighter than a full reconnect for a healthy-but-drifted tile (no manifest
     * refetch / player rebuild), reusing the live-edge primitive from Part B.
     */
    fun resync(idx: Int) {
        _players[idx]?.let { if (it.needsReconnect()) it.reconnect() else it.seekToLive() }
    }

    /** Quick RESYNC of EVERY tile (the wall's "Resync all feeds"). */
    fun resyncAll() {
        _players.values.forEach { if (it.needsReconnect()) it.reconnect() else it.seekToLive() }
    }

    /**
     * Push RESOLVED-url rotations into the EXISTING players in place — no grid
     * rebuild (P-N3). The set of channels (ids) is unchanged (the call site keys the
     * manager on that set, so an actual channel change recreates the manager
     * instead); only resolved urls may have rotated (a YouTube `expire` token). A
     * tile whose url changed swaps its media source in place via
     * [StreamPlayer.updateUrl], preserving its surface + audio/captions state +
     * honest-offline recovery. Guards on size so a mismatched call is a safe no-op.
     */
    fun updateSpecs(newSpecs: List<StreamSpec>) {
        if (newSpecs.size != liveSpecs.size) return
        newSpecs.forEachIndexed { idx, spec ->
            if (spec.url != liveSpecs[idx].url) _players[idx]?.updateUrl(spec.url)
        }
        liveSpecs = newSpecs
    }

    override fun onStart(owner: LifecycleOwner) {
        if (_players.isEmpty()) {
            liveSpecs.forEachIndexed { idx, spec ->
                val p = playerFactory(context, spec)
                _players[idx] = p
                p.initialize()
            }
            // Flip the signal LAST so callers that observe it see all
            // players in place when they re-bind. Compose subscribers
            // recompose on the next snapshot apply.
            _readyVersion.intValue++
        }
    }

    override fun onDestroy(owner: LifecycleOwner) = releaseAll()

    /**
     * Release every player and clear the set. Called by [onDestroy] AND explicitly
     * by the call site's `DisposableEffect` onDispose when the manager is replaced
     * (a genuine channel-set change) — because `Lifecycle.removeObserver` does NOT
     * fire [onDestroy], so without this an old manager would LEAK its ExoPlayer
     * decoders until the activity is destroyed. Idempotent.
     */
    fun releaseAll() {
        _players.values.forEach { it.release() }
        _players.clear()
        // Bump again so observers can collapse to the C2 panel cleanly
        // rather than holding the prior players' references.
        _readyVersion.intValue++
    }
}
