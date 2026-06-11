package com.mymts.ui.wall

import com.mymts.data.ticker.TickerEntry

/**
 * Pure paging logic for the whole-ticker flip (2026-06-11). Every ticker mode
 * becomes a flip **page** with consistent motion: the markets quotes are one
 * page, each sports league is its own page, news is one page — and the strip
 * flips between them all with the same animation (markets → league blocks →
 * back). A page wider than the panel scrolls horizontally; the flip happens
 * between pages. No Compose/Android here — unit-tested in `TickerPagingTest`.
 *
 * Each page carries a [Page.key] so the flip triggers on a real page change
 * (advance to the next league, or a mode rotation markets↔sports↔news) and not
 * on incidental content equality.
 */
object TickerPaging {

    sealed interface Page {
        val key: String

        /**
         * The text of this page's PINNED left-edge marker (the ESPN-BottomLine
         * "curtain" bug). Every page has one so the marker is consistent across
         * markets, sports, and news; cards scroll and vanish at its right edge.
         */
        val markerLabel: String
    }

    /** All market quotes as one (carded, horizontally-scrolling) page. */
    data class Markets(val quotes: List<TickerEntry>) : Page {
        override val key: String get() = "markets"
        override val markerLabel: String get() = "MARKETS"
    }

    /** One league's game cards as a page (the BottomLine block). */
    data class League(val block: SportsTicker.LeagueBlock) : Page {
        override val key: String get() = "league:${block.label}"
        override val markerLabel: String get() = block.label
    }

    /** News headlines as one (carded, horizontally-scrolling) page. */
    data class News(val items: List<TickerEntry>) : Page {
        override val key: String get() = "news"
        override val markerLabel: String get() = "NEWS"
    }

    /**
     * Build the flip pages for one mode's entries (the source publishes one
     * mode at a time; this turns it into pages the strip flips through):
     *  - entries carry games → one [League] page per league block (live-first
     *    within each, from the helper);
     *  - entries have a direction arrow (UP/DOWN/FLAT) → one [Markets] page;
     *  - otherwise (NONE-direction headlines / honest lines) → one [News] page.
     * Empty → `[]` (the strip shows nothing). Distinguishing markets from news
     * by the presence of a direction arrow needs no mode flag on the wire.
     */
    fun pagesFor(entries: List<TickerEntry>): List<Page> {
        if (entries.isEmpty()) return emptyList()
        val blocks = SportsTicker.blocks(entries)
        if (blocks.isNotEmpty()) return blocks.map { League(it) }
        val isMarkets = entries.any { it.direction != TickerEntry.Direction.NONE }
        return listOf(if (isMarkets) Markets(entries) else News(entries))
    }

    /** Advance the flip index, wrapping. count <= 0 → 0 (nothing to show). */
    fun nextPage(index: Int, count: Int): Int = if (count <= 0) 0 else (index + 1) % count
}
