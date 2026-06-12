package com.mymts.ui.wall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the per-page reveal-duration policy (the fix for the "flips almost
 * instantly once the right edge is revealed" bug). The reveal scrolls a page's
 * overflow past the panel in a single pass, then HOLDS at the revealed end;
 * [revealDurationMs] is capped so `startHold + reveal + endHold <= dwellMs`,
 * guaranteeing the end-hold is always visible before [PagedTicker] flips.
 */
class RevealDurationTest {

    // density 1.0 so px == dp (keeps the arithmetic legible); velocity 32 dp/sec.
    private val density = 1f
    private val velocity = 32f
    private val dwell = 9000L
    private val startHold = 800L
    private val endHold = 750L

    @Test fun `no overflow yields no reveal (the page just holds)`() {
        assertEquals(0, revealDurationMs(0, velocity, density, dwell, startHold, endHold))
        // A negative (defensive) overflow is also treated as nothing to reveal.
        assertEquals(0, revealDurationMs(-50, velocity, density, dwell, startHold, endHold))
    }

    @Test fun `a small overflow reveals at the natural duration (overflow over velocity)`() {
        // 160px overflow at 32px/sec → 5s = 5000ms natural, comfortably under the
        // 7450ms cap, so it is returned as-is (NOT capped) — a single readable pass.
        val natural = revealDurationMs(160, velocity, density, dwell, startHold, endHold)
        assertEquals(5000, natural)
    }

    @Test fun `a huge overflow is CAPPED so the end-hold always fits`() {
        // A very wide page (100_000px) would naturally take far longer than the
        // dwell; it must be capped to dwell - startHold - endHold so the reveal +
        // both holds still fit inside the between-page dwell.
        val cap = (dwell - startHold - endHold).toInt() // 7450
        val capped = revealDurationMs(100_000, velocity, density, dwell, startHold, endHold)
        assertEquals(cap, capped)
        // And the invariant the cap exists to protect: holds + reveal <= dwell.
        assertTrue(startHold + capped + endHold <= dwell)
    }

    @Test fun `density scales px-per-second (denser screen reveals the same px faster)`() {
        // 320px overflow: at density 1 that's 10s natural (capped to 7450); at
        // density 2 the velocity is 64px/sec → 5s natural, which fits uncapped.
        assertEquals(5000, revealDurationMs(320, velocity, 2f, dwell, startHold, endHold))
    }
}
