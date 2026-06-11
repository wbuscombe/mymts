package com.mymts.ticker

import com.mymts.data.ticker.HelperTickerSource
import com.mymts.data.ticker.HelperTickerSource.Mode
import com.mymts.data.ticker.SampleTickerSource
import com.mymts.data.ticker.TickerEntry
import com.mymts.data.ticker.TickerGame
import com.mymts.data.ticker.TickerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure mode-selection + honest-fallback rules of
 * [HelperTickerSource.entriesFor] — the part that decides what the
 * strip shows for the current mode, including the honesty rule that an
 * unreachable helper falls back to SAMPLE rather than frozen-live data.
 */
class HelperTickerSourceTest {

    private val sample = SampleTickerSource.SAMPLE_ENTRIES

    private fun marketsSnap(vararg e: TickerEntry) =
        TickerSnapshot(mode = "markets", asOfIso = "t", stale = false, entries = e.toList())

    private fun sportsSnap(vararg e: TickerEntry) =
        TickerSnapshot(mode = "sports", asOfIso = "t", stale = false, entries = e.toList())

    private val realSpx = TickerEntry("S&P 500", "7,509.40", TickerEntry.Direction.DOWN, isSample = false)
    private val gameRow = TickerEntry("MLB", "SD 4–6 PHI · Final", TickerEntry.Direction.NONE, isSample = false)

    @Test fun `markets mode reachable shows helper entries verbatim`() {
        val out = HelperTickerSource.entriesFor(
            mode = Mode.MARKETS,
            markets = marketsSnap(realSpx),
            sports = null,
            marketsReachable = true,
            sportsReachable = false,
            sampleFallback = sample,
        )
        assertEquals(listOf(realSpx), out)
    }

    @Test fun `markets mode unreachable falls back to SAMPLE, never frozen-live`() {
        // Even though we have a prior real snapshot, an unreachable helper
        // must NOT re-publish those numbers as current — fall to sample.
        val out = HelperTickerSource.entriesFor(
            mode = Mode.MARKETS,
            markets = marketsSnap(realSpx),
            sports = null,
            marketsReachable = false,
            sportsReachable = false,
            sampleFallback = sample,
        )
        assertSame(sample, out)
        assertTrue("fallback must be all-sample", out.all { it.isSample })
    }

    @Test fun `markets mode before first fetch shows sample`() {
        val out = HelperTickerSource.entriesFor(
            mode = Mode.MARKETS,
            markets = null,
            sports = null,
            marketsReachable = false,
            sportsReachable = false,
            sampleFallback = sample,
        )
        assertSame(sample, out)
    }

    @Test fun `sports mode reachable shows scores`() {
        val out = HelperTickerSource.entriesFor(
            mode = Mode.SPORTS,
            markets = null,
            sports = sportsSnap(gameRow),
            marketsReachable = false,
            sportsReachable = true,
            sampleFallback = sample,
        )
        assertEquals(listOf(gameRow), out)
    }

    @Test fun `sports mode reachable but empty shows honest unavailable line`() {
        val out = HelperTickerSource.entriesFor(
            mode = Mode.SPORTS,
            markets = null,
            sports = sportsSnap(),  // reachable, zero games
            marketsReachable = false,
            sportsReachable = true,
            sampleFallback = sample,
        )
        assertEquals(listOf(HelperTickerSource.SPORTS_UNAVAILABLE), out)
    }

    @Test fun `sports mode unreachable shows honest unavailable line, not sample-faked scores`() {
        val out = HelperTickerSource.entriesFor(
            mode = Mode.SPORTS,
            markets = null,
            sports = sportsSnap(gameRow),
            marketsReachable = false,
            sportsReachable = false,
            sampleFallback = sample,
        )
        assertEquals(listOf(HelperTickerSource.SPORTS_UNAVAILABLE), out)
    }

    @Test fun `sports unavailable line is a truthful state, not sample data`() {
        // is_sample=false: "scores unavailable" is a true statement, not
        // a fabricated sample score wearing a SAMPLE pill.
        assertEquals(false, HelperTickerSource.SPORTS_UNAVAILABLE.isSample)
        assertEquals(TickerEntry.Direction.NONE, HelperTickerSource.SPORTS_UNAVAILABLE.direction)
    }

    // ---- C3: stale real data is flagged, not shown as live (2026-06-10) ----

    @Test fun `isStale true only when the current mode is reachable AND aged`() {
        val staleSports = TickerSnapshot("sports", "t", stale = true, entries = listOf(gameRow))
        assertTrue(HelperTickerSource.isStale(Mode.SPORTS, null, staleSports, false, sportsReachable = true))
        // unreachable → falls to honest sample, not "stale"
        assertFalse(HelperTickerSource.isStale(Mode.SPORTS, null, staleSports, false, sportsReachable = false))
        // a fresh snapshot is not stale
        assertFalse(HelperTickerSource.isStale(Mode.SPORTS, null, sportsSnap(gameRow), false, true))
        // news has no separate stale signal
        assertFalse(HelperTickerSource.isStale(Mode.NEWS, null, staleSports, false, true))
    }

    @Test fun `filterLeagues matches on game league, not just the entry symbol`() {
        val e = TickerEntry(
            "Baseball", "x", TickerEntry.Direction.NONE, false,
            game = TickerGame("MLB", "A", "1", "B", "2", "in", "1st"),
        )
        assertEquals(0, HelperTickerSource.filterLeagues(listOf(e), setOf("MLB")).size)  // hidden by game.league
        assertEquals(1, HelperTickerSource.filterLeagues(listOf(e), setOf("NHL")).size)  // not hidden → kept
    }
}
