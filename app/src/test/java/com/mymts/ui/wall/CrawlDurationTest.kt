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
 * FRAMEWORK-TIMED: a real-clock `animateTo` drives a sub-pixel graphicsLayer
 * translation, so the velocity stays CONSTANT regardless of frame load (the fix
 * for the old clamped-per-frame-delta accumulator, which lost motion and looked
 * slow/stalled under the box's variable CPU load). The pure parts are the
 * velocity ([crawlPxPerSec]) and the tween DURATION ([crawlDurationMs]).
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

    // ---- the framework-timed DURATION: distance / speed, in millis ----

    @Test fun `crawlDurationMs is distance over speed in milliseconds`() {
        // 64 px at 64 px/sec → exactly 1000 ms; 128 px → 2000 ms.
        assertEquals(1000, crawlDurationMs(64f, 64f))
        assertEquals(2000, crawlDurationMs(128f, 64f))
        // sub-pixel / float math: 1.5 px at 3 px/sec → 500 ms (no integer-px stutter).
        assertEquals(500, crawlDurationMs(1.5f, 3f))
    }

    @Test fun `crawlDurationMs returns 0 when there is nothing to crawl`() {
        assertEquals(0, crawlDurationMs(0f, 64f))
        assertEquals(0, crawlDurationMs(-10f, 64f))   // negative distance can't drive motion
    }

    @Test fun `crawlDurationMs is monotonic in distance and inverse in speed`() {
        // More distance at a fixed speed → strictly longer.
        assertTrue(crawlDurationMs(200f, 64f) > crawlDurationMs(100f, 64f))
        // Faster speed over a fixed distance → strictly shorter (constant velocity).
        assertTrue(crawlDurationMs(100f, 128f) < crawlDurationMs(100f, 64f))
    }

    @Test fun `crawlDurationMs floors a zero speed so it never divides by zero`() {
        // A degenerate 0 px/sec is floored to 1 px/sec, not a crash / Infinity.
        assertEquals(100_000, crawlDurationMs(100f, 0f))
    }

    // ---- the seamless-loop repeat period MUST include the inter-copy gap ----

    @Test fun `crawlPeriodPx includes the inter-copy gap so the wrap is seamless`() {
        // The two-copy marquee lays out [copy1][gap][copy2]; the loop period is the
        // copy width PLUS the gap, NOT the copy width alone (that pops by a gap each
        // loop — the seam bug). Period must exceed the bare copy width.
        assertEquals(1008f, crawlPeriodPx(1000, 8f), 0.001f)
        assertTrue("period spans copy + gap", crawlPeriodPx(1000, 8f) > 1000f)
    }

    @Test fun `crawlPeriodPx floors a degenerate measurement to a non-zero period`() {
        // A 0-width / 0-gap reading is floored so the driver never wraps on a 0 period.
        assertTrue(crawlPeriodPx(0, 0f) >= 1f)
        assertEquals(1f, crawlPeriodPx(0, 0f), 0.001f)
    }

    // ---- the end-of-crawl dwell (slip time) ----

    @Test fun `CRAWL_DWELL_MS is a positive end-of-crawl rest`() {
        // The still-pause at the loop point after a completed pass. Named constant.
        assertEquals(1500L, CRAWL_DWELL_MS)
        assertTrue("dwell is a real rest", CRAWL_DWELL_MS >= 1000L)
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

    // The same setting drives the framework-timed duration too: a faster slider
    // (more px/sec) yields a SHORTER pass over the same content width.
    @Test fun `a faster speed setting shortens the crawl duration over fixed content`() {
        val d = 2f
        val widthPx = 4000f
        val slow = crawlDurationMs(widthPx, crawlPxPerSec(CRAWL_BASE_DP_PER_SEC, 20, d))
        val fast = crawlDurationMs(widthPx, crawlPxPerSec(CRAWL_BASE_DP_PER_SEC, 100, d))
        assertTrue("faster setting → shorter pass", fast < slow)
    }
}
