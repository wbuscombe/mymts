package com.mymts.ui.wall

import com.mymts.data.ticker.TickerEntry
import com.mymts.data.ticker.TickerGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the whole-ticker paging: markets → one page, each sports league → a
 *  page, news → one page, with stable flip keys + wrap-around advance. */
class TickerPagingTest {

    private fun market(sym: String, dir: TickerEntry.Direction) =
        TickerEntry(sym, "1.0", dir, isSample = false)

    private fun game(league: String, away: String) =
        TickerEntry(
            league, "x", TickerEntry.Direction.NONE, isSample = false,
            game = TickerGame(league, away, "1", "B", "2", "in", "1st"),
        )

    private fun news(src: String) = TickerEntry(src, "a headline", TickerEntry.Direction.NONE, isSample = false)

    @Test fun `sports entries become one league page per block, keyed by league`() {
        val pages = TickerPaging.pagesFor(listOf(game("MLB", "A"), game("MLB", "C"), game("NHL", "E")))
        assertEquals(2, pages.size)
        assertEquals("league:MLB", pages[0].key)
        assertEquals("league:NHL", pages[1].key)
        assertTrue(pages[0] is TickerPaging.League)
    }

    @Test fun `markets entries (with arrows) become one markets page`() {
        val pages = TickerPaging.pagesFor(
            listOf(market("S&P", TickerEntry.Direction.UP), market("BTC", TickerEntry.Direction.DOWN)),
        )
        assertEquals(1, pages.size)
        assertEquals("markets", pages[0].key)
        assertEquals(2, (pages[0] as TickerPaging.Markets).quotes.size)
    }

    @Test fun `news entries (NONE direction, no game) become one news page`() {
        val pages = TickerPaging.pagesFor(listOf(news("BBC"), news("CNN")))
        assertEquals(1, pages.size)
        assertEquals("news", pages[0].key)
        assertTrue(pages[0] is TickerPaging.News)
    }

    @Test fun `empty entries yield no pages`() {
        assertEquals(0, TickerPaging.pagesFor(emptyList()).size)
    }

    @Test fun `nextPage wraps and guards empty`() {
        assertEquals(1, TickerPaging.nextPage(0, 3))
        assertEquals(0, TickerPaging.nextPage(2, 3)) // wrap
        assertEquals(0, TickerPaging.nextPage(5, 0)) // guard
    }
}
