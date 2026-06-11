package com.mymts.ui.wall

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the below-video label-strip geometry. The label sits in a fixed strip
 * below the video with a bottom buffer so the title isn't flush against the
 * cell's bottom border (operator preference). These are pure dp invariants —
 * the actual no-clip property is structural (strip is a fixed child of the
 * tile Column, inside the section's overscan-safe band) and verified on-device.
 */
class WallTileLabelStripTest {

    /** Approx line height of the 11sp title — the strip must leave at least this
     *  much room above the buffer so the text never gets squeezed or clipped. */
    private val MIN_TEXT_DP = 14f

    @Test fun `bottom buffer is a small positive value`() {
        val buffer = LABEL_BOTTOM_BUFFER.value
        assertTrue("buffer must be > 0 (the operator wants breathing room)", buffer > 0f)
        assertTrue("buffer stays small so it can't crowd out the video", buffer <= 8f)
    }

    @Test fun `strip is tall enough for the title plus the bottom buffer`() {
        // height - buffer is the region the centered title lives in; it must
        // still comfortably fit the line so the buffer doesn't cause a re-clip.
        val room = LABEL_STRIP_HEIGHT.value - LABEL_BOTTOM_BUFFER.value
        assertTrue("strip too short for title + buffer (room=$room)", room >= MIN_TEXT_DP)
    }
}
