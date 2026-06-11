package com.mymts.data.ticker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A pluggable source of ticker entries.
 *
 * Stage 3 intentionally ships only [SampleTickerSource] (clearly-
 * labeled placeholder data — see field `isSample` on every entry).
 * A real markets feed lands in a later stage by implementing this
 * interface and swapping it in at [com.mymts.ui.wall.WallScreen]
 * construction; no UI code needs to change.
 *
 * **Honesty rule (the same C3 discipline as the grid + feed):** an
 * entry's `isSample` field travels with the data all the way to the
 * UI, which decorates sample data with a visible "SAMPLE" tag. The
 * source does not get to pretend its values are live.
 */
interface TickerSource {
    /** The entries to scroll. Newer entries replace older ones in place. */
    val state: StateFlow<List<TickerEntry>>

    /**
     * True when the currently-shown entries are REAL data that has aged past
     * the helper's freshness threshold (envelope `stale`) — the UI marks it
     * with a STALE pill so aged scores/quotes are never passed off as live
     * (C3). Defaults to never-stale for sources that don't age (sample).
     */
    val stale: StateFlow<Boolean> get() = NEVER_STALE

    /** Begin emitting entries. Idempotent. */
    fun start()

    /** Stop emitting; cancel any background work. */
    fun stop()

    companion object {
        /** Shared constant for sources that never go stale (avoids per-access allocation). */
        val NEVER_STALE: StateFlow<Boolean> = MutableStateFlow(false)
    }
}

/**
 * One ticker entry — the smallest unit the marquee draws.
 *
 * `symbol` is the canonical short code (e.g. "AAPL", "EUR/USD",
 * "BTC", "S&P 500"). `display` is the formatted value to draw
 * (e.g. "+0.42%", "1.0834", "$67,210"). The UI is responsible for
 * arrows/colors per the chosen visual register; the source stays out
 * of presentation.
 */
data class TickerEntry(
    val symbol: String,
    val display: String,
    val direction: Direction,
    /** True iff this value is placeholder/sample, NOT a live quote. */
    val isSample: Boolean,
    /**
     * Structured sports game (BottomLine cards, 2026-06-10) — present only on
     * sports entries; null for markets/news. When non-null the ticker draws a
     * real game card (abbrs + scores + a weighted status block) instead of the
     * flat [display] string. [display] stays as a fallback for any renderer
     * that doesn't yet understand games.
     */
    val game: TickerGame? = null,
    /**
     * Structured card for the **individual** sports (PGA/UFC/tennis/F1,
     * 2026-06-11) that don't fit the team-vs-team [game] shape — a
     * leaderboard / fight / match / race. Present only on those entries;
     * null otherwise. [display] stays the fallback string.
     */
    val card: SportCard? = null,
) {
    /**
     * [NONE] is for non-directional data (sports scores) — the UI draws
     * no arrow glyph at all, since up/down is meaningless for a score
     * line. Markets entries use UP/DOWN/FLAT.
     */
    enum class Direction { UP, DOWN, FLAT, NONE }
}

/**
 * One individual-sport card, mirrored from the helper's `SportCardDTO`.
 * [kind] is the dispatch ("leaderboard" | "fight" | "match" | "race");
 * [title] the headline (tournament/event/matchup/GP); [state] the ESPN
 * lifecycle token ("pre"|"in"|"post") for status colour; [status] the short
 * status block; [lines] the content rows (leaderboard players / set scores /
 * podium). Inert primitives only (A1).
 */
data class SportCard(
    val league: String,
    val kind: String,
    val title: String,
    val state: String,
    val status: String,
    val lines: List<String>,
)

/**
 * One sports game in the ESPN-BottomLine shape, mirrored from the helper's
 * `GameDTO`. [state] is the lifecycle token ("pre" | "in" | "post"); [status]
 * is ESPN's `shortDetail` ("Final", "5:42 - 1st", "7:30 PM ET"). Scores are the
 * helper's already-cleaned digit strings (blank for a `pre` matchup, so the
 * card shows the time, not a phantom 0–0). Inert primitives only (A1).
 */
data class TickerGame(
    val league: String,
    val away: String,
    val awayScore: String,
    val home: String,
    val homeScore: String,
    val state: String,
    val status: String,
)
