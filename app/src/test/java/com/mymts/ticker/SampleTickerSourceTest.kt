package com.mymts.ticker

import com.mymts.data.ticker.SampleTickerSource
import com.mymts.data.ticker.TickerEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleTickerSourceTest {

    @Test fun `every sample entry is flagged isSample = true`() {
        val src = SampleTickerSource()
        val entries = src.state.value
        assertFalse("sample source must have entries", entries.isEmpty())
        assertTrue(
            "every sample entry must be marked isSample (Trust Bar C3 — never present sample as live)",
            entries.all { it.isSample },
        )
    }

    @Test fun `entries cover multiple asset classes (not just one)`() {
        val symbols = SampleTickerSource().state.value.map { it.symbol }
        // Loose contract — the UI cares about variety, not exact symbol list.
        assertTrue(symbols.any { it == "S&P 500" })
        assertTrue(symbols.any { it.startsWith("EUR/") || it.startsWith("USD/") || it.startsWith("GBP/") })
        assertTrue(symbols.any { it in setOf("BTC", "ETH") })
    }

    @Test fun `start and stop are idempotent no-ops`() {
        val src = SampleTickerSource()
        val before = src.state.value
        src.start()
        src.start()
        src.stop()
        src.stop()
        // State unchanged across the cycle — the source is static by design.
        assertTrue(src.state.value === before)
    }

    @Test fun `direction enum covers up, down, and flat`() {
        val src = SampleTickerSource()
        val dirs = src.state.value.map { it.direction }.toSet()
        assertTrue(dirs.contains(TickerEntry.Direction.UP))
        assertTrue(dirs.contains(TickerEntry.Direction.DOWN))
        // FLAT is a less-common case but present so the UI hooks both glyphs.
        assertTrue(dirs.contains(TickerEntry.Direction.FLAT))
    }
}
