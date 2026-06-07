package com.mymts.ui.wall

import com.mymts.data.ticker.TickerEntry

/**
 * Groups consecutive ticker entries that share a [TickerEntry.symbol] into
 * one labelled run — the ESPN-BottomLine pattern the operator asked for:
 * the league/market marker appears ONCE, then its rows follow without the
 * redundant per-item prefix.
 *
 * Pure Kotlin, no Compose / Android — unit-tested in `TickerGroupingTest`
 * so the grouping is verified without booting the UI. Mirrors the web
 * client's `render.mjs::groupTickerByLeague` exactly, so both clients read
 * identically (the cross-client consistency Part 1 asked for):
 *  - Sports: a league's games are emitted contiguously by the helper, so a
 *    run of eight "MLB …" entries collapses to one "MLB" marker + 8 rows.
 *  - Markets: every symbol is distinct, so each entry is its own single-row
 *    run — the symbol still shows per value, unchanged in meaning.
 *
 * Grouping is by ADJACENCY (not a global bucket), so we never reorder the
 * marquee out of the helper's emit order.
 */
object TickerGrouping {

    /** One labelled run: the [label] (symbol/league) shown once, then its [entries]. */
    data class Run(val label: String, val entries: List<TickerEntry>)

    fun group(entries: List<TickerEntry>): List<Run> {
        val runs = ArrayList<Run>()
        for (e in entries) {
            val last = runs.lastOrNull()
            if (last != null && last.label == e.symbol) {
                runs[runs.lastIndex] = last.copy(entries = last.entries + e)
            } else {
                runs.add(Run(e.symbol, listOf(e)))
            }
        }
        return runs
    }
}
