package com.mymts.data.ticker

import android.util.Log
import com.mymts.data.helper.HelperClient
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
 * The real ticker source: polls the helper's `/api/ticker/markets` and
 * `/api/ticker/sports` endpoints and alternates the strip between the
 * two modes on a calm timer.
 *
 * This is exactly what the [TickerSource] interface was built for — the
 * strip consumes `state` and never learns where the data came from.
 * [SampleTickerSource] stays the honest fallback: if the helper is
 * unreachable, the markets mode shows clearly-labelled SAMPLE entries
 * rather than frozen real numbers, and the sports mode shows an honest
 * "scores unavailable" line.
 *
 * **Honesty (C3):** the helper owns the real-vs-sample decision per
 * entry (`isSample`); this class never upgrades a sample entry to live.
 * On a failed fetch it falls back to honest sample, never re-publishing
 * the last real numbers as if they were current.
 *
 * **Calm (Vision §4):** modes rotate on a slow timer (defaults: markets
 * ~22 s, sports ~14 s) — long enough to read, not a frantic flip. The
 * marquee restart on each swap is the intended "mode changed" cue.
 */
class HelperTickerSource(
    private val client: HelperClient,
    private val pollIntervalMs: Long = 60_000L,
    private val marketsDwellMs: Long = 22_000L,
    private val sportsDwellMs: Long = 14_000L,
    private val sampleFallback: List<TickerEntry> = SampleTickerSource.SAMPLE_ENTRIES,
) : TickerSource {

    private val _state = MutableStateFlow(sampleFallback)
    override val state: StateFlow<List<TickerEntry>> = _state.asStateFlow()

    // Latest fetch results per mode (null = not yet fetched). Held so the
    // rotation loop can publish whichever mode is currently due without
    // re-fetching, and so a failed poll can fall back honestly.
    @Volatile private var marketsSnapshot: TickerSnapshot? = null
    @Volatile private var sportsSnapshot: TickerSnapshot? = null
    @Volatile private var marketsReachable = false
    @Volatile private var sportsReachable = false
    @Volatile private var mode: Mode = Mode.MARKETS

    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null
    private var rotateJob: Job? = null

    enum class Mode { MARKETS, SPORTS }

    override fun start() {
        if (pollJob?.isActive == true) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        pollJob = s.launch {
            while (isActive) {
                pollOnce()
                publishCurrent()
                delay(pollIntervalMs)
            }
        }
        rotateJob = s.launch {
            while (isActive) {
                delay(if (mode == Mode.MARKETS) marketsDwellMs else sportsDwellMs)
                mode = if (mode == Mode.MARKETS) Mode.SPORTS else Mode.MARKETS
                publishCurrent()
            }
        }
    }

    override fun stop() {
        pollJob?.cancel(); pollJob = null
        rotateJob?.cancel(); rotateJob = null
        scope?.cancel(); scope = null
    }

    private suspend fun pollOnce() {
        when (val r = client.fetchMarketsTicker()) {
            is HelperClient.Result.Ok -> { marketsSnapshot = r.value; marketsReachable = true }
            is HelperClient.Result.Err -> {
                Log.w(TAG, "markets ticker fetch failed: ${r.cause.message}")
                marketsReachable = false
            }
        }
        when (val r = client.fetchSportsTicker()) {
            is HelperClient.Result.Ok -> { sportsSnapshot = r.value; sportsReachable = true }
            is HelperClient.Result.Err -> {
                Log.w(TAG, "sports ticker fetch failed: ${r.cause.message}")
                sportsReachable = false
            }
        }
    }

    private fun publishCurrent() {
        _state.value = entriesFor(
            mode = mode,
            markets = marketsSnapshot,
            sports = sportsSnapshot,
            marketsReachable = marketsReachable,
            sportsReachable = sportsReachable,
            sampleFallback = sampleFallback,
        )
    }

    companion object {
        private const val TAG = "MyMTS.HelperTicker"

        /**
         * Pure selection: given the current mode and the latest per-mode
         * fetch state, return the entries to show. Extracted as a pure
         * function so the honest-fallback rules are unit-tested without
         * coroutines.
         *
         * Rules:
         *  - MARKETS reachable → its entries (helper already labelled
         *    real-vs-sample per entry).
         *  - MARKETS unreachable → the SAMPLE fallback (all isSample),
         *    NOT the last real numbers (no frozen-as-live).
         *  - SPORTS reachable → its entries (may itself be a "no games"
         *    line or the helper's sample slate).
         *  - SPORTS unreachable → a single honest "scores unavailable"
         *    line (isSample = false: it's a true statement, not sample
         *    data).
         */
        fun entriesFor(
            mode: Mode,
            markets: TickerSnapshot?,
            sports: TickerSnapshot?,
            marketsReachable: Boolean,
            sportsReachable: Boolean,
            sampleFallback: List<TickerEntry>,
        ): List<TickerEntry> = when (mode) {
            Mode.MARKETS ->
                if (marketsReachable && markets != null) markets.entries
                else sampleFallback
            Mode.SPORTS ->
                if (sportsReachable && sports != null) {
                    sports.entries.ifEmpty { listOf(SPORTS_UNAVAILABLE) }
                } else {
                    listOf(SPORTS_UNAVAILABLE)
                }
        }

        /** Honest "no sports data" line — a true state, not sample data. */
        val SPORTS_UNAVAILABLE = TickerEntry(
            symbol = "SPORTS",
            display = "scores unavailable",
            direction = TickerEntry.Direction.NONE,
            isSample = false,
        )
    }
}
