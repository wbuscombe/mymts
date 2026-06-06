package com.mymts.ticker

import com.mymts.data.helper.FeedItem
import com.mymts.data.ticker.HelperTickerSource
import com.mymts.data.ticker.HelperTickerSource.Mode
import com.mymts.data.ticker.SampleTickerSource
import com.mymts.data.ticker.TickerEntry
import com.mymts.data.ticker.TickerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the curation-pass pure logic: sports league filtering, the
 * markets→sports→news rotation, and the honest news-entry builder
 * (no fabricated urgency).
 */
class CurationTest {

    private fun game(league: String) =
        TickerEntry(league, "AAA 1–0 BBB · Final", TickerEntry.Direction.NONE, isSample = false)

    private fun sportsSnap(vararg e: TickerEntry) =
        TickerSnapshot(mode = "sports", asOfIso = "t", stale = false, entries = e.toList())

    // ---- sports league filter ----

    @Test fun `hidden league dropped from sports mode`() {
        val out = HelperTickerSource.entriesFor(
            mode = Mode.SPORTS,
            markets = null, sports = sportsSnap(game("MLB"), game("NBA"), game("NHL")),
            marketsReachable = false, sportsReachable = true,
            sampleFallback = emptyList(),
            hiddenLeagues = setOf("NBA"),
        )
        assertEquals(listOf("MLB", "NHL"), out.map { it.symbol })
    }

    @Test fun `league filter is case-insensitive`() {
        assertEquals(
            listOf("NHL"),
            HelperTickerSource.filterLeagues(listOf(game("MLB"), game("NHL")), setOf("mlb")).map { it.symbol },
        )
    }

    @Test fun `all-hidden leagues fall to honest scores-unavailable`() {
        val out = HelperTickerSource.entriesFor(
            mode = Mode.SPORTS,
            markets = null, sports = sportsSnap(game("MLB"), game("NBA")),
            marketsReachable = false, sportsReachable = true,
            sampleFallback = emptyList(),
            hiddenLeagues = setOf("MLB", "NBA"),
        )
        assertEquals(listOf(HelperTickerSource.SPORTS_UNAVAILABLE), out)
    }

    @Test fun `status line (SPORTS) survives a league denylist`() {
        // A "no games" status line has symbol "SPORTS" — not a league —
        // so it must not be filtered out by a league denylist.
        val statusLine = TickerEntry("SPORTS", "no games right now", TickerEntry.Direction.NONE, false)
        val out = HelperTickerSource.filterLeagues(listOf(statusLine), setOf("MLB", "NBA"))
        assertEquals(listOf(statusLine), out)
    }

    // ---- rotation ----

    @Test fun `rotation is 2-cycle when news off, 3-cycle when on`() {
        // news off: markets <-> sports
        assertEquals(Mode.SPORTS, HelperTickerSource.nextMode(Mode.MARKETS, false))
        assertEquals(Mode.MARKETS, HelperTickerSource.nextMode(Mode.SPORTS, false))
        // news on: markets -> sports -> news -> markets
        assertEquals(Mode.SPORTS, HelperTickerSource.nextMode(Mode.MARKETS, true))
        assertEquals(Mode.NEWS, HelperTickerSource.nextMode(Mode.SPORTS, true))
        assertEquals(Mode.MARKETS, HelperTickerSource.nextMode(Mode.NEWS, true))
    }

    // ---- news mode entries ----

    private fun item(id: Long, source: String, title: String, iso: String?) =
        FeedItem(id, source, title, summary = null, link = null, publishedAtIso = iso, fetchedAtIso = iso)

    @Test fun `news mode shows entries when enabled, honest line when off or empty`() {
        val news = listOf(TickerEntry("BBC", "Headline", TickerEntry.Direction.NONE, false))
        // enabled + entries
        assertEquals(news, HelperTickerSource.entriesFor(
            mode = Mode.NEWS, markets = null, sports = null,
            marketsReachable = false, sportsReachable = false, sampleFallback = emptyList(),
            news = news, newsEnabled = true,
        ))
        // enabled but empty → honest "no headlines"
        assertEquals(listOf(HelperTickerSource.NEWS_UNAVAILABLE), HelperTickerSource.entriesFor(
            mode = Mode.NEWS, markets = null, sports = null,
            marketsReachable = false, sportsReachable = false, sampleFallback = emptyList(),
            news = emptyList(), newsEnabled = true,
        ))
    }

    @Test fun `newsEntries newest-first, drops hidden sources, real (not sample), capped`() {
        val items = listOf(
            item(1, "BBC", "old", "2026-06-01T10:00:00.000Z"),
            item(2, "BBC", "new", "2026-06-06T10:00:00.000Z"),
            item(3, "Reason", "hidden-src", "2026-06-06T11:00:00.000Z"),
            item(4, "NPR", "blank-title-skipped".let { "" }, "2026-06-06T12:00:00.000Z"),
        )
        val out = HelperTickerSource.newsEntries(items, hiddenSources = setOf("Reason"), cap = 12)
        assertEquals(listOf("new", "old"), out.map { it.display })  // Reason hidden, blank-title dropped, newest-first
        assertTrue(out.all { !it.isSample })                        // real headlines, never sample
        assertTrue(out.all { it.direction == TickerEntry.Direction.NONE })
    }

    @Test fun `newsEntries respects the cap`() {
        val items = (1..30).map { item(it.toLong(), "BBC", "t$it", "2026-06-%02dT00:00:00.000Z".format(it % 28 + 1)) }
        assertEquals(5, HelperTickerSource.newsEntries(items, emptySet(), cap = 5).size)
    }
}
