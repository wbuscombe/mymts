package com.mymts.player

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Owns N StreamPlayers and their lifecycle. The soak harness is the only
 * caller in Stage 1; future stages will use this from the grid screen as
 * well.
 *
 * Lifecycle policy (adapted from WyzeGrid): players (re)initialize on
 * onStart, release on onDestroy. onStop merely pauses by clearing playWhenReady
 * (Stage 6 will tune this for the dim-window + watchdog interplay).
 */
class StreamPlayerManager(
    private val context: Context,
    val specs: List<StreamSpec>,
) : DefaultLifecycleObserver {

    private val _players = mutableMapOf<Int, StreamPlayer>()
    val players: Map<Int, StreamPlayer> get() = _players

    fun player(idx: Int): StreamPlayer? = _players[idx]

    override fun onStart(owner: LifecycleOwner) {
        if (_players.isEmpty()) {
            specs.forEachIndexed { idx, spec ->
                val p = StreamPlayer(context, spec)
                _players[idx] = p
                p.initialize()
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        _players.values.forEach { it.release() }
        _players.clear()
    }
}
