package com.mymts.data.ticker

import android.util.Log
import com.mymts.data.helper.FeedItem
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
    // Sports holds longer than the other modes: it now FLIPS through league
    // card-sets (~6.5 s each), so the window must be long enough to cycle the
    // pool a lap. Markets/news still scroll on their shorter dwell.
    private val sportsDwellMs: Long = 42_000L,
    private val newsDwellMs: Long = 18_000L,
    private val sampleFallback: List<TickerEntry> = SampleTickerSource.SAMPLE_ENTRIES,
) : TickerSource {

    private val _state = MutableStateFlow(sampleFallback)
    override val state: StateFlow<List<TickerEntry>> = _state.asStateFlow()

    // C3: surface envelope `stale` so the strip flags aged real data instead of
    // showing it as live. True only when the current mode is reachable AND its
    // snapshot is stale (an unreachable mode falls to honest sample, not stale).
    private val _stale = MutableStateFlow(false)
    override val stale: StateFlow<Boolean> = _stale.asStateFlow()

    // Latest fetch results per mode (null = not yet fetched). Held so the
    // rotation loop can publish whichever mode is currently due without
    // re-fetching, and so a failed poll can fall back honestly.
    @Volatile private var marketsSnapshot: TickerSnapshot? = null
    @Volatile private var sportsSnapshot: TickerSnapshot? = null
    @Volatile private var marketsReachable = false
    @Volatile private var sportsReachable = false
    @Volatile private var mode: Mode = Mode.MARKETS

    // Curation (set by the call site from on-device WallSettings; read each
    // publish). hiddenLeagues filters the SPORTS mode TV-side (helper still
    // serves all leagues). newsEnabled adds NEWS as a third rotation mode;
    // newsEntries are pre-built by the call site from the feed it already
    // polls (no duplicate fetch here).
    @Volatile private var hiddenLeagues: Set<String> = emptySet()
    @Volatile private var newsEnabled: Boolean = false
    @Volatile private var newsEntries: List<TickerEntry> = emptyList()

    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null
    private var rotateJob: Job? = null

    enum class Mode { MARKETS, SPORTS, NEWS }

    /**
     * Update curation from the operator's settings. Called by WallScreen
     * whenever `WallSettings` changes. `news` are pre-built ticker entries
     * for the news mode (the call site builds them from the feed it
     * already has, via [newsEntries]); pass an empty list when news is off.
     */
    fun setCuration(hiddenLeagues: Set<String>, newsEnabled: Boolean, news: List<TickerEntry>) {
        this.hiddenLeagues = hiddenLeagues
        this.newsEnabled = newsEnabled
        this.newsEntries = news
        // If news was just turned off and we're parked on it, step away so
        // the operator isn't stuck on a now-disabled mode.
        if (!newsEnabled && mode == Mode.NEWS) mode = Mode.MARKETS
        publishCurrent()
    }

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
                delay(dwellFor(mode))
                mode = nextMode(mode, newsEnabled)
                publishCurrent()
            }
        }
    }

    private fun dwellFor(m: Mode): Long = when (m) {
        Mode.MARKETS -> marketsDwellMs
        Mode.SPORTS -> sportsDwellMs
        Mode.NEWS -> newsDwellMs
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
            hiddenLeagues = hiddenLeagues,
            news = newsEntries,
            newsEnabled = newsEnabled,
        )
        _stale.value = isStale(mode, marketsSnapshot, sportsSnapshot, marketsReachable, sportsReachable)
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
            hiddenLeagues: Set<String> = emptySet(),
            news: List<TickerEntry> = emptyList(),
            newsEnabled: Boolean = false,
        ): List<TickerEntry> = when (mode) {
            Mode.MARKETS ->
                if (marketsReachable && markets != null) markets.entries
                else sampleFallback
            Mode.SPORTS ->
                if (sportsReachable && sports != null) {
                    // Sports curation: drop entries for hidden leagues
                    // (matched on the entry's league symbol, case-
                    // insensitively). Status lines ("SPORTS · …") aren't
                    // leagues so they survive. If curation empties the
                    // list, fall to the honest "no sports" line.
                    filterLeagues(sports.entries, hiddenLeagues).ifEmpty { listOf(SPORTS_UNAVAILABLE) }
                } else {
                    listOf(SPORTS_UNAVAILABLE)
                }
            Mode.NEWS ->
                if (newsEnabled && news.isNotEmpty()) news
                else listOf(NEWS_UNAVAILABLE)
        }

        /**
         * Whether the currently-shown mode is REAL-but-STALE (reachable + its
         * snapshot aged past the helper's threshold). An unreachable mode shows
         * honest sample, not stale; NEWS is built from the feed (no separate
         * stale signal). Pure → unit-tested.
         */
        fun isStale(
            mode: Mode,
            markets: TickerSnapshot?,
            sports: TickerSnapshot?,
            marketsReachable: Boolean,
            sportsReachable: Boolean,
        ): Boolean = when (mode) {
            Mode.MARKETS -> marketsReachable && markets?.stale == true
            Mode.SPORTS -> sportsReachable && sports?.stale == true
            Mode.NEWS -> false
        }

        /**
         * Keep entries whose league is NOT in the denylist. Matches on the
         * GAME's league when present (that's the value the card groups + labels
         * by, via SportsTicker.blocks) and falls back to the entry symbol — so a
         * hidden league can't leak through a symbol/league divergence.
         */
        fun filterLeagues(entries: List<TickerEntry>, hiddenLeagues: Set<String>): List<TickerEntry> {
            if (hiddenLeagues.isEmpty()) return entries
            val hiddenLower = hiddenLeagues.map { it.lowercase() }.toSet()
            return entries.filter { (it.game?.league ?: it.symbol).lowercase() !in hiddenLower }
        }

        /**
         * The next rotation mode. MARKETS → SPORTS → (NEWS if enabled →)
         * MARKETS. Pure so the rotation cycle is unit-tested. When news is
         * off it's a 2-cycle; when on, a 3-cycle.
         */
        fun nextMode(current: Mode, newsEnabled: Boolean): Mode = when (current) {
            Mode.MARKETS -> Mode.SPORTS
            Mode.SPORTS -> if (newsEnabled) Mode.NEWS else Mode.MARKETS
            Mode.NEWS -> Mode.MARKETS
        }

        /**
         * Build news ticker entries from feed items: newest-first, from
         * the operator's NON-hidden sources, capped. Each entry is a real
         * headline (is_sample=false) shown as inert plain text — NO
         * urgency/breaking classification (RSS can't honestly flag that;
         * see BACKLOG). symbol = source, display = title. Pure + tested.
         */
        fun newsEntries(
            items: List<FeedItem>,
            hiddenSources: Set<String>,
            cap: Int = 12,
        ): List<TickerEntry> {
            val hiddenLower = hiddenSources.map { it.lowercase() }.toSet()
            return items.asSequence()
                .filter { (it.source.ifBlank { "Unknown source" }).lowercase() !in hiddenLower }
                .filter { it.title.isNotBlank() }
                .sortedByDescending { (it.publishedAtIso ?: it.fetchedAtIso ?: "") }
                .take(cap)
                .map {
                    TickerEntry(
                        symbol = it.source.ifBlank { "News" },
                        display = it.title,
                        direction = TickerEntry.Direction.NONE,
                        isSample = false,
                    )
                }
                .toList()
        }

        /** Honest "no sports data" line — a true state, not sample data. */
        val SPORTS_UNAVAILABLE = TickerEntry(
            symbol = "SPORTS",
            display = "scores unavailable",
            direction = TickerEntry.Direction.NONE,
            isSample = false,
        )

        /** Honest "no news" line for the NEWS mode — a true state. */
        val NEWS_UNAVAILABLE = TickerEntry(
            symbol = "NEWS",
            display = "no headlines",
            direction = TickerEntry.Direction.NONE,
            isSample = false,
        )
    }
}
