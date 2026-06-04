package com.mymts.wall

import com.mymts.data.helper.Channel
import com.mymts.ui.wall.LineupSelector
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the operator's preferred lineup ordering after the 2026-06-04
 * push: Bloomberg TV + CNBC lead, then the prior preferred 4, then
 * the fallback list, then the rest.
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

    @Test fun `PREFERRED begins with bloomberg-tv then cnbc`() {
        val preferred = LineupSelector.PREFERRED
        assertEquals("bloomberg-tv", preferred[0])
        assertEquals("cnbc", preferred[1])
    }

    @Test fun `prior preferred 4 follow the financial pair`() {
        val expected = listOf(
            "bloomberg-tv", "cnbc",
            "cbs-sports-hq", "bbc-news", "cnn", "livenow-fox",
        )
        assertEquals(expected, LineupSelector.PREFERRED)
    }

    @Test fun `dw-news-en is NOT in the preferred list`() {
        // After the swap, DW News stays seeded for manual assignment but
        // drops out of the default cycler's preferred tier.
        assertEquals(false, "dw-news-en" in LineupSelector.PREFERRED)
    }

    @Test fun `bloomberg + cnbc + cbs all live — those three win in order`() {
        val selector = LineupSelector.forWall(maxCount = 4)
        val all = listOf(
            "bloomberg-tv", "cnbc", "cbs-sports-hq", "bbc-news",
            "dw-news-en", "redbull-tv",
        ).map(::playable)
        val pick = selector(all).map { it.slug }
        assertEquals(listOf("bloomberg-tv", "cnbc", "cbs-sports-hq", "bbc-news"), pick)
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
