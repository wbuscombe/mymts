package com.mymts.ui.wall

import com.mymts.data.settings.TICKER_SPEED_DEFAULT_PCT
import com.mymts.data.settings.TICKER_SPEED_MAX_PCT
import com.mymts.data.settings.TICKER_SPEED_MIN_PCT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the CRAWL-motion pacing policy — the cross-platform ticker motion (the
 * web wall's continuous marquee, offered on the native wall too). Motion is now
 * driven by a CHEAP graphicsLayer translation advanced per frame; the pure parts
 * are the velocity ([crawlPxPerSec]) and the CLAMPED per-frame advance
 * ([crawlAdvancePx]) — the "drop, don't sprint" lever that keeps a stall from
 * catching up in one big jump and starving video decode.
 */
class CrawlDurationTest {

    // density 1.0 so px == dp (legible arithmetic); 32 dp/sec base (BASE_SCROLL_VELOCITY).
    private val density = 1f
    private val base = 32f

    @Test fun `crawlPxPerSec scales base by density and the speed percent`() {
        assertEquals(32f, crawlPxPerSec(base, 100, density), 0.001f)       // 100% at density 1
        assertEquals(64f, crawlPxPerSec(base, 100, 2f), 0.001f)           // denser screen → faster px/sec
        assertEquals(64f, crawlPxPerSec(base, 200, density), 0.001f)      // 200% speed → 2x
        assertTrue("never zero", crawlPxPerSec(base, 1, density) >= 1f)   // floor (a 0 would freeze motion)
    }

    @Test fun `crawlAdvancePx is pxPerSec times the frame delta in seconds`() {
        // 64 px/sec over a 16ms frame → ~1.024 px.
        assertEquals(64f * 0.016f, crawlAdvancePx(64f, 16L, CRAWL_MAX_FRAME_DELTA_MS), 0.001f)
        // zero delta → no advance.
        assertEquals(0f, crawlAdvancePx(64f, 0L, CRAWL_MAX_FRAME_DELTA_MS), 0.001f)
    }

    // ---- the speed SETTING drives the crawl, across a usable slow→fast range ----

    @Test fun `the slider range maps monotonically from a genuinely-slow floor to fast`() {
        val d = 2f  // ~1080p density on the Onn box
        val floor = crawlPxPerSec(CRAWL_BASE_DP_PER_SEC, TICKER_SPEED_MIN_PCT, d)
        val mid = crawlPxPerSec(CRAWL_BASE_DP_PER_SEC, TICKER_SPEED_DEFAULT_PCT, d)
        val top = crawlPxPerSec(CRAWL_BASE_DP_PER_SEC, TICKER_SPEED_MAX_PCT, d)
        // strictly increasing — more slider = faster.
        assertTrue("floor < default < max", floor < mid && mid < top)
        // the floor is genuinely slow (a calm ambient crawl), and the top is
        // meaningfully faster than the floor (a real range, not all-fast).
        assertTrue("floor is slow (<= 15 px/s)", floor <= 15f)
        assertTrue("top is many× the floor", top >= floor * 5f)
    }

    @Test fun `the same setting value changes the speed (the setting is wired, not hardcoded)`() {
        val d = 2f
        // Two distinct slider values MUST produce distinct speeds — the regression
        // guard for "the slider appears dead": speed is a function of the setting.
        assertTrue(
            crawlPxPerSec(CRAWL_BASE_DP_PER_SEC, 40, d) > crawlPxPerSec(CRAWL_BASE_DP_PER_SEC, 20, d),
        )
        assertTrue(
            crawlPxPerSec(CRAWL_BASE_DP_PER_SEC, 200, d) > crawlPxPerSec(CRAWL_BASE_DP_PER_SEC, 100, d),
        )
    }

    @Test fun `crawlAdvancePx CLAMPS a stalled frame so it drops missed motion (no sprint)`() {
        val cap = CRAWL_MAX_FRAME_DELTA_MS
        // A 500ms stall is clamped to the cap — it advances by AT MOST one capped
        // frame, not the whole 500ms of "missed" motion.
        val stalled = crawlAdvancePx(64f, 500L, cap)
        val capped = crawlAdvancePx(64f, cap, cap)
        assertEquals(capped, stalled, 0.001f)
        // and the cap is well under the stall (so motion is genuinely dropped).
        assertTrue("stall dropped, not sprinted", stalled < 64f * (500f / 1000f))
        // a negative/garbage delta can't drive motion backwards.
        assertEquals(0f, crawlAdvancePx(64f, -100L, cap), 0.001f)
    }
}
