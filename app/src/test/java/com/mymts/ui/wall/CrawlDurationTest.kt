package com.mymts.ui.wall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the CRAWL-motion pacing policy ([crawlDurationMs]) — the cross-platform
 * ticker motion (the web wall's continuous marquee, offered on the native wall
 * too). One copy of the concatenated content scrolls a full copy-width at the
 * configured velocity; the duration is `copyWidthPx / (velocity*density)`.
 */
class CrawlDurationTest {

    // density 1.0 so px == dp (legible arithmetic); velocity 32 dp/sec (BASE).
    private val density = 1f
    private val velocity = 32f

    @Test fun `no width yields no duration (nothing to crawl)`() {
        assertEquals(0, crawlDurationMs(0, velocity, density))
        assertEquals(0, crawlDurationMs(-100, velocity, density))   // defensive
    }

    @Test fun `duration is width over velocity`() {
        // 320px at 32px/sec → 10s = 10000ms. No cap (a crawl runs as long as the
        // content is wide — unlike the flip's per-page reveal, there's no dwell).
        assertEquals(10000, crawlDurationMs(320, velocity, density))
        assertEquals(5000, crawlDurationMs(160, velocity, density))
    }

    @Test fun `density scales px-per-second (denser screen crawls the same px faster)`() {
        // 320px at density 2 → velocity 64px/sec → 5s.
        assertEquals(5000, crawlDurationMs(320, velocity, 2f))
    }

    @Test fun `a faster speed pref shortens the duration and never returns zero`() {
        val slow = crawlDurationMs(1000, velocity, density)          // 100% speed
        val fast = crawlDurationMs(1000, velocity * 2f, density)     // 200% speed
        assertTrue("faster speed → shorter crawl", fast < slow)
        // Even an absurd velocity can't return < 1ms (a 0 would make tween throw).
        assertTrue(crawlDurationMs(1, 100000f, density) >= 1)
    }
}
