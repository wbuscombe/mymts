package com.mymts.wall

import com.mymts.data.helper.Channel
import com.mymts.ui.wall.LineupSelector
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the operator's preferred lineup ordering after the 2026-06-11 default
 * change: the first four fill the 2×2 in row-major order — TL LiveNOW from FOX,
 * TR Fox Weather, BL BBC News, BR CBS Sports HQ — then the financials + CNN.
 */
class LineupSelectorPreferredOrderTest {

    private fun playable(slug: String) = Channel(
        slug = slug,
        label = slug.uppercase(),
        kind = "hls",
        currentUrl = "https://example.test/$slug.m3u8",
        status = Channel.Status.LIVE,
        lastSuccessAt = "2026-06-04T00:00:00Z",
        lastError = null,
        errorCount = 0,
    )

    @Test fun `PREFERRED begins with the 2x2 default — fox, weather, bbc, sports`() {
        val expected = listOf(
            "livenow-fox", "fox-weather", "bbc-news", "cbs-sports-hq",
            "bloomberg-tv", "cnn",
        )
        assertEquals(expected, LineupSelector.PREFERRED)
    }

    @Test fun `the 2x2 default cells map to the row-major slot order`() {
        // first 4 PREFERRED = TL, TR, BL, BR
        assertEquals("livenow-fox", LineupSelector.PREFERRED[0])   // TL
        assertEquals("fox-weather", LineupSelector.PREFERRED[1])   // TR
        assertEquals("bbc-news", LineupSelector.PREFERRED[2])      // BL
        assertEquals("cbs-sports-hq", LineupSelector.PREFERRED[3]) // BR
    }

    @Test fun `dw-news-en is NOT in the preferred list`() {
        // DW News stays seeded for manual assignment but isn't a default slot.
        assertEquals(false, "dw-news-en" in LineupSelector.PREFERRED)
    }

    @Test fun `the default 2x2 four win in order when all live`() {
        val selector = LineupSelector.forWall(maxCount = 4)
        val all = listOf(
            "livenow-fox", "fox-weather", "bbc-news", "cbs-sports-hq",
            "bloomberg-tv", "dw-news-en", "redbull-tv",
        ).map(::playable)
        val pick = selector(all).map { it.slug }
        assertEquals(listOf("livenow-fox", "fox-weather", "bbc-news", "cbs-sports-hq"), pick)
    }

    @Test fun `dw-news-en falls into the rest tier when no preferred channel resolves`() {
        // No preferred + no fallback resolves; DW + Red Bull fall to
        // the "rest" tier (with nasa-tv deny-listed as before).
        val selector = LineupSelector.forWall(maxCount = 4)
        val playable = listOf("dw-news-en", "redbull-tv").map(::playable)
        val pick = selector(playable).map { it.slug }
        // Both make it; nasa-tv would have been denied, but it's not
        // in the input here.
        assertEquals(setOf("dw-news-en", "redbull-tv"), pick.toSet())
    }
}
