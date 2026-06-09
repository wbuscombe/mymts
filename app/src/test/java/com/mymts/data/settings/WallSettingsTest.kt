package com.mymts.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pin the wall-settings enum + persistence-helper invariants.
 *
 * The full SharedPreferences round-trip path lives in `LineupStore`
 * and is exercised on-device; this layer covers the pure helpers that
 * convert between persisted integer ordinals and enum values, plus
 * the legibility-floor + default invariants the chapter promises.
 *
 * Why ordinal-based persistence instead of name strings: the operator's
 * stored value should survive minor enum reorderings of NEW values
 * appended at the END (a default fallback covers any drift); name
 * strings would silently break if a preset is ever renamed. The
 * `_FromOrdinal` helpers fall back to safe defaults for any
 * out-of-range value, so the store never throws on read.
 */
class WallSettingsTest {

    // ============== Defaults ==============

    @Test fun `WallSettings Default has Default values across all three knobs`() {
        val d = WallSettings.Default
        assertEquals(FeedWidth.Default, d.feedWidth)
        assertEquals(FeedFontScale.Default, d.feedFontScale)
        assertEquals(FeedSide.Left, d.feedSide)
    }

    // ============== Width presets ==============

    @Test fun `FeedWidth presets are ordered Narrow lt Default lt Wide`() {
        assertTrue(FeedWidth.Narrow.fraction < FeedWidth.Default.fraction)
        assertTrue(FeedWidth.Default.fraction < FeedWidth.Wide.fraction)
    }

    @Test fun `FeedWidth Default fraction matches the prior hardcoded layout`() {
        // Stage 3 hardcoded 0.28f for the feed pane modifier. The
        // settings chapter preserves this exactly so an operator who
        // never opens settings sees no layout change.
        assertEquals(0.28f, FeedWidth.Default.fraction, 0.0001f)
    }

    @Test fun `FeedWidth Narrow stays above legibility floor`() {
        // Narrow is the smallest preset; it must still be wide enough
        // to render at least a short headline at 10 ft. We treat 0.2f
        // (one-fifth of the screen) as a hard lower bound — anything
        // tighter is hostile to the wall's "calm + readable" stance.
        assertTrue(
            "Narrow fraction ${FeedWidth.Narrow.fraction} must be >= 0.2",
            FeedWidth.Narrow.fraction >= 0.2f,
        )
    }

    @Test fun `FeedWidth Wide stays below grid-starvation ceiling`() {
        // The grid is the wall's primary surface (Vision §3 — ambient
        // video). Wide must not eat so much room that the 2×2 grid is
        // too small to read. 0.4f is the operator-friendly ceiling.
        assertTrue(
            "Wide fraction ${FeedWidth.Wide.fraction} must be <= 0.4",
            FeedWidth.Wide.fraction <= 0.4f,
        )
    }

    // ============== Font scale presets ==============

    @Test fun `FeedFontScale presets are ordered Small lt Default lt Large`() {
        assertTrue(FeedFontScale.Small.multiplier < FeedFontScale.Default.multiplier)
        assertTrue(FeedFontScale.Default.multiplier < FeedFontScale.Large.multiplier)
    }

    @Test fun `FeedFontScale Default is 1`() {
        assertEquals(1.0f, FeedFontScale.Default.multiplier, 0.0001f)
    }

    @Test fun `FeedFontScale Small respects the 10-ft legibility floor`() {
        // The smallest preset is the floor — anything smaller risks the
        // operator squinting from across the room. With the feed
        // title's baseline 15sp, Small (0.88) gives ~13.2sp — within
        // the 12-14sp range Android TV guidelines call "readable from
        // 10 ft." Smaller would not be operator-friendly.
        assertTrue(
            "Small multiplier ${FeedFontScale.Small.multiplier} must be >= 0.85",
            FeedFontScale.Small.multiplier >= 0.85f,
        )
    }

    @Test fun `FeedFontScale Large stays below overflow ceiling`() {
        // Large must not blow up the headline so much that two-line
        // titles routinely truncate. 1.25f is the operator-friendly
        // ceiling for a 15sp baseline.
        assertTrue(
            "Large multiplier ${FeedFontScale.Large.multiplier} must be <= 1.25",
            FeedFontScale.Large.multiplier <= 1.25f,
        )
    }

    // ============== Side presets ==============

    @Test fun `FeedSide Left is the default`() {
        assertEquals(FeedSide.Left, WallSettings.Default.feedSide)
    }

    @Test fun `FeedSide values are Left and Right only`() {
        assertEquals(2, FeedSide.values().size)
        assertTrue(FeedSide.Left in FeedSide.values())
        assertTrue(FeedSide.Right in FeedSide.values())
    }

    // ============== Ordinal mapping (persistence robustness) ==============

    @Test fun `feedWidthFromOrdinal maps in-range values to their enum`() {
        assertEquals(FeedWidth.Narrow, feedWidthFromOrdinal(FeedWidth.Narrow.ordinal))
        assertEquals(FeedWidth.Default, feedWidthFromOrdinal(FeedWidth.Default.ordinal))
        assertEquals(FeedWidth.Wide, feedWidthFromOrdinal(FeedWidth.Wide.ordinal))
    }

    @Test fun `feedWidthFromOrdinal falls back to Default for out-of-range`() {
        assertEquals(FeedWidth.Default, feedWidthFromOrdinal(-1))
        assertEquals(FeedWidth.Default, feedWidthFromOrdinal(99))
    }

    @Test fun `feedFontScaleFromOrdinal falls back to Default for out-of-range`() {
        assertEquals(FeedFontScale.Default, feedFontScaleFromOrdinal(-5))
        assertEquals(FeedFontScale.Default, feedFontScaleFromOrdinal(42))
    }

    @Test fun `feedSideFromOrdinal falls back to Left for out-of-range`() {
        // Left is the chapter's default; out-of-range MUST land here
        // so a partial-corruption read can't silently flip the
        // operator's layout.
        assertEquals(FeedSide.Left, feedSideFromOrdinal(-1))
        assertEquals(FeedSide.Left, feedSideFromOrdinal(7))
    }

    // ============== WallSettings copy semantics ==============

    @Test fun `WallSettings copy preserves untouched fields`() {
        val s = WallSettings(
            feedWidth = FeedWidth.Wide,
            feedFontScale = FeedFontScale.Large,
            feedSide = FeedSide.Right,
        )
        val next = s.copy(feedWidth = FeedWidth.Narrow)
        assertEquals(FeedWidth.Narrow, next.feedWidth)
        assertEquals(FeedFontScale.Large, next.feedFontScale)
        assertEquals(FeedSide.Right, next.feedSide)
    }

    @Test fun `WallSettings equality differentiates each field`() {
        val a = WallSettings.Default
        assertNotEquals(a, a.copy(feedWidth = FeedWidth.Wide))
        assertNotEquals(a, a.copy(feedFontScale = FeedFontScale.Large))
        assertNotEquals(a, a.copy(feedSide = FeedSide.Right))
    }

    // ============== Panel-fit: UI scale + overscan (2026-06-07) ==============

    @Test fun `defaults are Default scale and Medium (5pct) overscan`() {
        // Overscan defaults to the TV-safe 5% so an overscan panel fits
        // without operator action; UI scale defaults to 1.0 (unchanged look).
        assertEquals(UiScale.Default, WallSettings.Default.uiScale)
        assertEquals(Overscan.Medium, WallSettings.Default.overscan)
        assertEquals(1.0f, UiScale.Default.multiplier, 0.0001f)
        assertEquals(0.05f, Overscan.Medium.fraction, 0.0001f)
    }

    @Test fun `UiScale Compact shrinks (below 1) so it can fit a too-big wall`() {
        assertTrue("Compact must be < Default to shrink", UiScale.Compact.multiplier < UiScale.Default.multiplier)
        assertTrue("Roomy must be > Default", UiScale.Roomy.multiplier > UiScale.Default.multiplier)
    }

    @Test fun `Overscan presets are ordered None lt Small lt Medium lt Large`() {
        assertEquals(0.0f, Overscan.None.fraction, 0.0001f)
        assertTrue(Overscan.None.fraction < Overscan.Small.fraction)
        assertTrue(Overscan.Small.fraction < Overscan.Medium.fraction)
        assertTrue(Overscan.Medium.fraction < Overscan.Large.fraction)
        // A safe-area inset shouldn't eat more than ~10% of an edge.
        assertTrue("Large overscan stays sane", Overscan.Large.fraction <= 0.10f)
    }

    @Test fun `uiScaleFromOrdinal maps in-range and falls back to Default`() {
        assertEquals(UiScale.Compact, uiScaleFromOrdinal(UiScale.Compact.ordinal))
        assertEquals(UiScale.Roomy, uiScaleFromOrdinal(UiScale.Roomy.ordinal))
        assertEquals(UiScale.Default, uiScaleFromOrdinal(-1))
        assertEquals(UiScale.Default, uiScaleFromOrdinal(99))
    }

    @Test fun `overscanFromOrdinal maps in-range and falls back to Medium`() {
        assertEquals(Overscan.None, overscanFromOrdinal(Overscan.None.ordinal))
        assertEquals(Overscan.Large, overscanFromOrdinal(Overscan.Large.ordinal))
        // Out-of-range falls back to the TV-safe Medium, not None — a
        // corrupt read keeps the safe inset rather than risking clipping.
        assertEquals(Overscan.Medium, overscanFromOrdinal(-1))
        assertEquals(Overscan.Medium, overscanFromOrdinal(42))
    }

    @Test fun `equality and copy cover the new fields`() {
        val a = WallSettings.Default
        assertNotEquals(a, a.copy(uiScale = UiScale.Compact))
        assertNotEquals(a, a.copy(overscan = Overscan.None))
        // copy preserves the panel-fit fields when other knobs change.
        val next = a.copy(feedWidth = FeedWidth.Wide)
        assertEquals(UiScale.Default, next.uiScale)
        assertEquals(Overscan.Medium, next.overscan)
    }

    // ============== Position offset (2026-06-09) ==============

    @Test fun `position offset defaults to 0,0 (no nudge)`() {
        assertEquals(0, WallSettings.Default.offsetXDp)
        assertEquals(0, WallSettings.Default.offsetYDp)
    }

    @Test fun `offset bounds are symmetric and the step divides the range`() {
        assertTrue("range positive", OFFSET_RANGE_DP > 0)
        assertTrue("step positive + smaller than range", OFFSET_STEP_DP in 1 until OFFSET_RANGE_DP)
        // A whole number of steps reaches the bound exactly (clean by-eye dial).
        assertEquals(0, OFFSET_RANGE_DP % OFFSET_STEP_DP)
    }

    @Test fun `clampOffsetDp keeps in-range values and clamps out-of-range, symmetric`() {
        assertEquals(0, clampOffsetDp(0))
        assertEquals(OFFSET_STEP_DP, clampOffsetDp(OFFSET_STEP_DP))
        assertEquals(-OFFSET_STEP_DP, clampOffsetDp(-OFFSET_STEP_DP))
        assertEquals(OFFSET_RANGE_DP, clampOffsetDp(OFFSET_RANGE_DP))
        assertEquals(OFFSET_RANGE_DP, clampOffsetDp(OFFSET_RANGE_DP + 999))   // over → clamped
        assertEquals(-OFFSET_RANGE_DP, clampOffsetDp(-OFFSET_RANGE_DP - 999)) // under → clamped
    }

    @Test fun `equality and copy cover the offset fields`() {
        val a = WallSettings.Default
        assertNotEquals(a, a.copy(offsetXDp = 8))
        assertNotEquals(a, a.copy(offsetYDp = -8))
        assertEquals(0, a.copy(uiScale = UiScale.Compact).offsetXDp)  // untouched by other changes
    }
}
