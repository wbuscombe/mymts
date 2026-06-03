package com.mymts.data.ticker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sample-data adapter for the ticker.
 *
 * Stage 3 records this honestly in the docs: the helper does not yet
 * resolve markets data; the ticker shows shape, not truth. Every
 * entry returned here has `isSample = true` so the UI can mark it
 * visibly — the operator (and anyone in the room) sees at a glance
 * that these are placeholder values, not live quotes.
 *
 * The numbers are static — they don't pretend to update in real time.
 * A "ticking" sample source would look more like the production
 * shape, but it would also nudge the operator toward thinking the
 * values are real. The motionless display is intentionally honest.
 */
class SampleTickerSource : TickerSource {

    private val _state = MutableStateFlow(SAMPLE_ENTRIES)
    override val state: StateFlow<List<TickerEntry>> = _state.asStateFlow()

    override fun start() { /* no-op — values are static */ }
    override fun stop() { /* no-op */ }

    companion object {
        /**
         * Plausible-looking placeholder values across asset classes — but
         * marked `isSample = true` and never claimed as live. A future
         * markets source replaces these by implementing [TickerSource].
         */
        val SAMPLE_ENTRIES: List<TickerEntry> = listOf(
            TickerEntry("S&P 500",  "5,820.14", TickerEntry.Direction.UP,   isSample = true),
            TickerEntry("DOW",      "44,910.65", TickerEntry.Direction.UP,   isSample = true),
            TickerEntry("NASDAQ",   "19,772.18", TickerEntry.Direction.DOWN, isSample = true),
            TickerEntry("FTSE",     "8,344.20",  TickerEntry.Direction.UP,   isSample = true),
            TickerEntry("DAX",      "19,388.81", TickerEntry.Direction.DOWN, isSample = true),
            TickerEntry("Nikkei",   "39,500.37", TickerEntry.Direction.FLAT, isSample = true),
            TickerEntry("Hang Seng","20,997.93", TickerEntry.Direction.DOWN, isSample = true),
            TickerEntry("EUR/USD",  "1.0834",    TickerEntry.Direction.UP,   isSample = true),
            TickerEntry("GBP/USD",  "1.2671",    TickerEntry.Direction.DOWN, isSample = true),
            TickerEntry("USD/JPY",  "154.18",    TickerEntry.Direction.UP,   isSample = true),
            TickerEntry("Brent",    "73.42",     TickerEntry.Direction.DOWN, isSample = true),
            TickerEntry("WTI",      "69.15",     TickerEntry.Direction.DOWN, isSample = true),
            TickerEntry("Gold",     "2,742.30",  TickerEntry.Direction.UP,   isSample = true),
            TickerEntry("BTC",      "67,210",    TickerEntry.Direction.UP,   isSample = true),
            TickerEntry("ETH",      "3,452",     TickerEntry.Direction.FLAT, isSample = true),
            TickerEntry("10Y UST",  "4.41%",     TickerEntry.Direction.DOWN, isSample = true),
        )
    }
}
