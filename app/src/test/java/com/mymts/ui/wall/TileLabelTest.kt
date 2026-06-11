package com.mymts.ui.wall

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the dimension-aware tile-label placement: below when the bottom
 *  letterbox clears the clip, above when it's tight, overlay when pillarboxed. */
class TileLabelTest {

    private val w16x9 = 16f / 9f
    private val slot = 18f
    private val clip = 22f

    @Test fun `tall cell with 16x9 video places label BELOW`() {
        // 320x300 cell, 16:9 video → videoH≈180, letterbox≈60 ≥ 40 → BELOW
        assertEquals(TileLabel.Placement.BELOW, TileLabel.placement(320f, 300f, w16x9, slot, clip))
    }

    @Test fun `tight bottom letterbox places label ABOVE (away from the clip)`() {
        // 320x230 cell → videoH≈180, letterbox≈25: fits the label but not the clip margin → ABOVE
        assertEquals(TileLabel.Placement.ABOVE, TileLabel.placement(320f, 230f, w16x9, slot, clip))
    }

    @Test fun `wide cell (pillarbox, no vertical bar) → OVERLAY`() {
        // 400x180 cell (aspect 2.22 > 1.78) → fit to height → letterbox 0 → OVERLAY
        assertEquals(TileLabel.Placement.OVERLAY, TileLabel.placement(400f, 180f, w16x9, slot, clip))
    }

    @Test fun `unknown or degenerate size → OVERLAY (safe)`() {
        assertEquals(TileLabel.Placement.OVERLAY, TileLabel.placement(320f, 300f, null, slot, clip))
        assertEquals(TileLabel.Placement.OVERLAY, TileLabel.placement(0f, 300f, w16x9, slot, clip))
    }

    @Test fun `rendered video height + edges`() {
        assertEquals(180f, TileLabel.renderedVideoHeight(320f, 300f, w16x9), 0.5f)
        assertEquals(240f, TileLabel.videoBottom(300f, 180f), 0.5f)
        assertEquals(60f, TileLabel.videoTop(300f, 180f), 0.5f)
    }
}
