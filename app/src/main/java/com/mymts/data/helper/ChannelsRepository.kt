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
 * Owns the wall's view of `/api/channels`. Polls on a fixed cadence
 * while [start] is in effect; exposes the current snapshot + a
 * derived freshness signal as [StateFlow]s so the UI re-composes
 * exactly when state changes.
 *
 * Failure handling — Trust Bar **C3**: a fetch error never replaces
 * the last good snapshot with empty state silently. The repository
 * stamps a [lastFetchOk] = false alongside the last good list, and
 * the UI surfaces "stale" when [staleness] exceeds [staleAfterMs].
 */
class ChannelsRepository(
    private val client: HelperClient,
    private val pollIntervalMs: Long = 30_000L,
    private val staleAfterMs: Long = 90_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class State(
        val snapshot: ChannelsSnapshot?,
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

    /** Begin polling. Idempotent — repeated calls don't fan out parallel pollers. */
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

    /** One refresh; safe to call manually (e.g. on resume). */
    suspend fun refreshOnce() {
        when (val r = client.fetchChannels()) {
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

    /** Milliseconds since the last fetch attempt (regardless of outcome). */
    fun ageMs(now: Long = clock()): Long {
        val at = _state.value.lastFetchAtMs
        return if (at == 0L) Long.MAX_VALUE else (now - at).coerceAtLeast(0L)
    }

    /** True when the repository hasn't successfully refreshed in [staleAfterMs]. */
    fun isStale(now: Long = clock()): Boolean {
        val s = _state.value
        if (!s.lastFetchOk) return true
        return (now - s.lastFetchAtMs) > staleAfterMs
    }

    companion object {
        private const val TAG = "MyMTS.ChannelsRepo"
    }
}
