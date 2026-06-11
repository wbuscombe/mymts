package com.mymts.ui.wall

import com.mymts.data.ticker.TickerEntry
import com.mymts.data.ticker.TickerGame
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the pure sports-flip logic: league blocking, status classification +
 *  ESPN-convention formatting, and the flip-index advance. */
class SportsTickerTest {

    private fun game(
        league: String,
        away: String = "A",
        home: String = "B",
        state: String = "in",
        status: String = "1st",
    ) = TickerEntry(
        symbol = league,
        display = "$away v $home",
        direction = TickerEntry.Direction.NONE,
        isSample = false,
        game = TickerGame(league, away, "1", home, "2", state, status),
    )

    @Test fun `blocks groups adjacent same-league games preserving order`() {
        val b = SportsTicker.blocks(listOf(game("MLB", "A"), game("MLB", "C"), game("NHL", "E")))
        assertEquals(2, b.size)
        assertEquals("MLB", b[0].label); assertEquals(2, b[0].games.size)
        assertEquals(listOf("A", "C"), b[0].games.map { it.game!!.away })
        assertEquals("NHL", b[1].label); assertEquals(1, b[1].games.size)
    }

    @Test fun `block keeps entry so the card can honour isSample (C3)`() {
        val sample = game("MLB", "A").copy(isSample = true)
        val b = SportsTicker.blocks(listOf(sample))
        assertEquals(true, b[0].games[0].isSample)
    }

    @Test fun `blocks groups by the GAME league, not the entry symbol`() {
        // symbol diverges from game.league → grouping + label follow the game.
        val e = TickerEntry(
            symbol = "Baseball", display = "x", direction = TickerEntry.Direction.NONE, isSample = false,
            game = TickerGame("MLB", "A", "1", "B", "2", "in", "1st"),
        )
        val b = SportsTicker.blocks(listOf(e))
        assertEquals("MLB", b[0].label)
    }

    @Test fun `blocks skips non-game entries (markets, news, honest status lines)`() {
        val entries = listOf(
            TickerEntry("S&P 500", "5,820", TickerEntry.Direction.UP, false),            // market
            game("MLB", "A"),
            TickerEntry("SPORTS", "scores unavailable", TickerEntry.Direction.NONE, false), // honest line
        )
        val b = SportsTicker.blocks(entries)
        assertEquals(1, b.size); assertEquals("MLB", b[0].label)
    }

    @Test fun `empty or all-non-game yields no blocks (caller shows fallback)`() {
        assertEquals(0, SportsTicker.blocks(emptyList()).size)
        assertEquals(0, SportsTicker.blocks(listOf(TickerEntry("X", "y", TickerEntry.Direction.FLAT, false))).size)
    }

    @Test fun `kindOf maps state to bucket, unknown is upcoming`() {
        assertEquals(SportsTicker.StatusKind.LIVE, SportsTicker.kindOf("in"))
        assertEquals(SportsTicker.StatusKind.FINAL, SportsTicker.kindOf("post"))
        assertEquals(SportsTicker.StatusKind.UPCOMING, SportsTicker.kindOf("pre"))
        assertEquals(SportsTicker.StatusKind.UPCOMING, SportsTicker.kindOf("garbage"))
    }

    @Test fun `formatStatus follows ESPN convention`() {
        assertEquals("FINAL", SportsTicker.formatStatus("post", "Final"))
        assertEquals("5:42 1ST", SportsTicker.formatStatus("in", "5:42 - 1st"))
        assertEquals("TOP 2ND", SportsTicker.formatStatus("in", "Top 2nd"))
        assertEquals("7:30 PM ET", SportsTicker.formatStatus("pre", "7:30 PM ET"))
        assertEquals("LIVE", SportsTicker.formatStatus("in", ""))   // never blank for a live game
        assertEquals("—", SportsTicker.formatStatus("pre", ""))
    }

    @Test fun `nextBlock wraps and guards empty`() {
        assertEquals(1, SportsTicker.nextBlock(0, 3))
        assertEquals(0, SportsTicker.nextBlock(2, 3))   // wrap
        assertEquals(0, SportsTicker.nextBlock(5, 0))   // guard: nothing to show
    }
}
