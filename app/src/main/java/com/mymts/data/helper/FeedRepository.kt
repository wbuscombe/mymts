package com.mymts.data.helper

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the wall's view of `/api/feed`. Polls on a fixed cadence while
 * [start] is in effect; exposes the current snapshot + a freshness
 * signal — the same shape as [ChannelsRepository].
 *
 * Trust Bar **C3 boundary**: a fetch error never silently replaces the
 * last good snapshot with an empty list. The UI inspects
 * [State.lastFetchOk] + [staleAfterMs] to decide whether the feed pane
 * should advertise "not updating" rather than presenting stale items as
 * current.
 *
 * Why a longer cadence than the channels repository? Channel state
 * (live ↔ unavailable) changes on the order of seconds; feed items
 * arrive on the order of minutes. Polling RSS more often than the
 * upstream cadence just burns work — the helper itself polls upstreams
 * every 5 minutes.
 */
class FeedRepository(
    private val client: HelperClient,
    private val pollIntervalMs: Long = 60_000L,
    private val staleAfterMs: Long = 10 * 60_000L,
    private val limit: Int = 80,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class State(
        val snapshot: FeedSnapshot?,
        val lastFetchOk: Boolean,
        val lastFetchAtMs: Long,
    ) {
        companion object {
            val Initial = State(snapshot = null, lastFetchOk = false, lastFetchAtMs = 0L)
        }
    }

    private val _state = MutableStateFlow(State.Initial)
    val state: StateFlow<State> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null

    fun start() {
        if (pollJob?.isActive == true) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        pollJob = s.launch {
            while (isActive) {
                refreshOnce()
                delay(pollIntervalMs)
            }
        }
    }

    suspend fun refreshOnce() {
        when (val r = client.fetchFeed(limit = limit)) {
            is HelperClient.Result.Ok -> _state.value = State(
                snapshot = r.value,
                lastFetchOk = true,
                lastFetchAtMs = clock(),
            )
            is HelperClient.Result.Err -> {
                Log.w(TAG, "refreshOnce err: ${r.cause.message}")
                _state.value = _state.value.copy(
                    lastFetchOk = false,
                    lastFetchAtMs = clock(),
                )
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        scope?.cancel()
        scope = null
    }

    /** True when the repository hasn't successfully refreshed in [staleAfterMs]. */
    fun isStale(now: Long = clock()): Boolean {
        val s = _state.value
        if (s.snapshot == null) return true
        if (!s.lastFetchOk) return true
        return (now - s.lastFetchAtMs) > staleAfterMs
    }

    companion object {
        private const val TAG = "MyMTS.FeedRepo"
    }
}
