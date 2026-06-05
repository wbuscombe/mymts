package com.mymts.data.ticker

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

    /** Begin emitting entries. Idempotent. */
    fun start()

    /** Stop emitting; cancel any background work. */
    fun stop()
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
) {
    /**
     * [NONE] is for non-directional data (sports scores) — the UI draws
     * no arrow glyph at all, since up/down is meaningless for a score
     * line. Markets entries use UP/DOWN/FLAT.
     */
    enum class Direction { UP, DOWN, FLAT, NONE }
}
