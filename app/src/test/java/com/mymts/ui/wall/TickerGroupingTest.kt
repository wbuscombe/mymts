package com.mymts.ui.wall

import com.mymts.data.ticker.TickerEntry
import com.mymts.data.ticker.TickerEntry.Direction
import org.junit.Assert.assertEquals
import org.junit.Test

class TickerGroupingTest {

    private fun e(symbol: String, display: String, dir: Direction = Direction.NONE) =
        TickerEntry(symbol = symbol, display = display, direction = dir, isSample = false)

    @Test
    fun `sports games collapse under one league marker`() {
        val runs = TickerGrouping.group(
            listOf(
                e("MLB", "SEA 4–0 DET · Final"),
                e("MLB", "KC 1–2 MIN · Top 9th"),
                e("NHL", "CAR @ VGK · 8:00 PM"),
            ),
        )
        assertEquals(listOf("MLB", "NHL"), runs.map { it.label })
        assertEquals(2, runs[0].entries.size)
        assertEquals("SEA 4–0 DET · Final", runs[0].entries[0].display)
        assertEquals(1, runs[1].entries.size)
    }

    @Test
    fun `markets symbols each become their own single-row run`() {
        val runs = TickerGrouping.group(
            listOf(
                e("S&P 500", "5,820", Direction.UP),
                e("DOW", "44,910", Direction.DOWN),
                e("BTC", "$60,620", Direction.DOWN),
            ),
        )
        assertEquals(3, runs.size)
        assertEquals(1, runs[0].entries.size)
        assertEquals("S&P 500", runs[0].label)
    }

    @Test
    fun `grouping is by adjacency, not a global bucket`() {
        val runs = TickerGrouping.group(
            listOf(e("MLB", "a"), e("NHL", "b"), e("MLB", "c")),
        )
        assertEquals(listOf("MLB", "NHL", "MLB"), runs.map { it.label })
    }

    @Test
    fun `empty input yields no runs`() {
        assertEquals(emptyList<TickerGrouping.Run>(), TickerGrouping.group(emptyList()))
    }

    @Test
    fun `sample flag is preserved through grouping`() {
        val sample = TickerEntry("NBA", "LAL 0–0 BOS · 7:30 ET", Direction.NONE, isSample = true)
        val runs = TickerGrouping.group(listOf(sample))
        assertEquals(true, runs[0].entries[0].isSample)
    }
}
