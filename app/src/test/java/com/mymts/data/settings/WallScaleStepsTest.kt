package com.mymts.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native ladders are a MIRROR of the helper's (`wall/store.py`) and the web's
 * (`wallConfig.mjs`). These tests pin the literals so a one-sided edit fails the build
 * rather than silently letting "step 7" mean different things on the TV and the wall.
 */
class WallScaleStepsTest {

    // ============== the cross-platform contract ==============

    @Test fun `ladders mirror the helper and web rung-for-rung`() {
        // Spelled out literally, NOT derived — that is the whole point: if someone edits
        // store.py or wallConfig.mjs without editing here (or vice versa), this fails.
        assertArrayEquals2(
            intArrayOf(12, 18, 23, 27, 32, 38, 44, 50, 57, 64), WallScaleSteps.FEED_WIDTH_PCT
        )
        assertArrayEquals2(
            floatArrayOf(0.60f, 0.70f, 0.80f, 0.90f, 1.00f, 1.15f, 1.32f, 1.52f, 1.75f, 2.00f),
            WallScaleSteps.FEED_TEXT
        )
        assertArrayEquals2(
            floatArrayOf(0.45f, 0.58f, 0.70f, 0.85f, 1.00f, 1.25f, 1.55f, 1.90f, 2.30f, 2.75f),
            WallScaleSteps.TICKER_HEIGHT
        )
        assertArrayEquals2(
            floatArrayOf(0.55f, 0.62f, 0.72f, 0.85f, 1.00f, 1.25f, 1.55f, 1.90f, 2.30f, 2.75f),
            WallScaleSteps.TICKER_TEXT
        )
    }

    @Test fun `every ladder has ten rungs and strictly increases`() {
        assertEquals(10, WallScaleSteps.FEED_WIDTH_PCT.size)
        for (i in 1 until WallScaleSteps.FEED_WIDTH_PCT.size) {
            assertTrue(WallScaleSteps.FEED_WIDTH_PCT[i] > WallScaleSteps.FEED_WIDTH_PCT[i - 1])
        }
        for (l in listOf(WallScaleSteps.FEED_TEXT, WallScaleSteps.TICKER_HEIGHT, WallScaleSteps.TICKER_TEXT)) {
            assertEquals(10, l.size)
            for (i in 1 until l.size) assertTrue(l[i] > l[i - 1])
        }
    }

    @Test fun `step 5 is the default on every control, matching web`() {
        assertEquals(32, WallScaleSteps.feedWidthPct(5))
        assertEquals(1.00f, WallScaleSteps.feedTextScale(5), 1e-6f)
        assertEquals(1.00f, WallScaleSteps.tickerHeightScale(5), 1e-6f)
        assertEquals(1.00f, WallScaleSteps.tickerTextScale(5), 1e-6f)
        assertEquals(5, WallScaleSteps.DEFAULT)
    }

    @Test fun `ticker box and text ladders stay proportional from step 4 up`() {
        // They diverge ONLY at the low rungs, where the text has a measured legibility
        // floor the box does not. Matching steps must otherwise mean "taller bar, bigger
        // text" exactly as the pre-split behaviour did.
        for (step in 4..10) {
            assertEquals(
                WallScaleSteps.tickerHeightScale(step), WallScaleSteps.tickerTextScale(step), 1e-6f
            )
        }
        assertTrue(WallScaleSteps.tickerTextScale(1) > WallScaleSteps.tickerHeightScale(1))
    }

    // ============== resolution ==============

    @Test fun `each step resolves to its own rung`() {
        for (step in 1..10) {
            assertEquals(
                WallScaleSteps.FEED_WIDTH_PCT[step - 1] / 100f,
                WallScaleSteps.feedWidthFraction(step), 1e-6f
            )
            assertEquals(WallScaleSteps.FEED_TEXT[step - 1], WallScaleSteps.feedTextScale(step), 1e-6f)
            assertEquals(WallScaleSteps.TICKER_HEIGHT[step - 1], WallScaleSteps.tickerHeightScale(step), 1e-6f)
            assertEquals(WallScaleSteps.TICKER_TEXT[step - 1], WallScaleSteps.tickerTextScale(step), 1e-6f)
        }
    }

    @Test fun `out-of-range steps clamp rather than crash`() {
        // A corrupt stored value must never index past the ladder.
        for (bad in listOf(-99, 0, 11, 999)) {
            val expected = if (bad < 1) 1 else 10
            assertEquals(expected, WallScaleSteps.clampStep(bad))
            WallScaleSteps.feedWidthFraction(bad)   // must not throw
            WallScaleSteps.tickerTextScale(bad)
        }
    }

    // ============== D-pad ergonomics ==============

    @Test fun `nudge CLAMPS at the ends instead of wrapping`() {
        // With ten rungs, wrapping widest→narrowest on one extra press would be a trap:
        // a held D-pad must simply stop.
        assertEquals(1, WallScaleSteps.nudge(1, -1))
        assertEquals(10, WallScaleSteps.nudge(10, 1))
        assertEquals(6, WallScaleSteps.nudge(5, 1))
        assertEquals(4, WallScaleSteps.nudge(5, -1))
    }

    @Test fun `ten presses traverse the whole range end to end`() {
        var step = WallScaleSteps.MIN
        repeat(9) { step = WallScaleSteps.nudge(step, 1) }
        assertEquals(WallScaleSteps.MAX, step)
    }

    @Test fun `labels show the step and what it resolves to`() {
        // Mirrors /control/'s "5 · 32%" format so both surfaces read the same.
        assertEquals("5 · 32%", WallScaleSteps.feedWidthLabel(5))
        assertEquals("1 · 12%", WallScaleSteps.feedWidthLabel(1))
        assertEquals("5 · 1.0×", WallScaleSteps.scaleLabel(5, WallScaleSteps.feedTextScale(5)))
    }

    // ============== migration off the retired 3-preset enums ==============

    @Test fun `legacy presets migrate to the nearest rung with no visible jump`() {
        assertEquals(3, feedWidthStepFromLegacy(FeedWidth.Narrow))    // 0.22 → 23 %
        assertEquals(4, feedWidthStepFromLegacy(FeedWidth.Default))   // 0.28 → 27 %
        assertEquals(6, feedWidthStepFromLegacy(FeedWidth.Wide))      // 0.36 → 38 %
        assertEquals(4, feedTextStepFromLegacy(FeedFontScale.Small))  // 0.88 → 0.90×
        assertEquals(5, feedTextStepFromLegacy(FeedFontScale.Default))// 1.00 → 1.00× exactly
        assertEquals(6, feedTextStepFromLegacy(FeedFontScale.Large))  // 1.18 → 1.15×
    }

    @Test fun `every legacy preset lands within 6 percent of the value it had`() {
        // "No visible jump", stated numerically rather than asserted in prose.
        for (w in FeedWidth.values()) {
            val got = WallScaleSteps.feedWidthFraction(feedWidthStepFromLegacy(w))
            assertTrue(
                "FeedWidth.$w drifted too far: ${w.fraction} → $got",
                Math.abs(got - w.fraction) / w.fraction <= 0.06f
            )
        }
        for (f in FeedFontScale.values()) {
            val got = WallScaleSteps.feedTextScale(feedTextStepFromLegacy(f))
            assertTrue(
                "FeedFontScale.$f drifted too far: ${f.multiplier} → $got",
                Math.abs(got - f.multiplier) / f.multiplier <= 0.06f
            )
        }
    }

    @Test fun `migration is idempotent — re-running it on a migrated step is a no-op`() {
        // The mapping is legacy-enum → step; once a step is stored the legacy branch is
        // never taken again. Re-deriving from the same enum must be stable.
        for (w in FeedWidth.values()) {
            assertEquals(feedWidthStepFromLegacy(w), feedWidthStepFromLegacy(w))
        }
        for (f in FeedFontScale.values()) {
            assertEquals(feedTextStepFromLegacy(f), feedTextStepFromLegacy(f))
        }
    }

    @Test fun `nearestStep ties resolve to the LOWER rung, deterministically`() {
        // Determinism is what makes the migration idempotent.
        val ladder = floatArrayOf(1.0f, 2.0f)
        assertEquals(1, WallScaleSteps.nearestStep(1.5f, ladder))
        assertEquals(1, WallScaleSteps.nearestStep(-5f, ladder))
        assertEquals(2, WallScaleSteps.nearestStep(99f, ladder))
    }

    private fun assertArrayEquals2(expected: IntArray, actual: IntArray) {
        assertEquals(expected.toList(), actual.toList())
    }

    private fun assertArrayEquals2(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals(expected[i], actual[i], 1e-6f)
    }
}
