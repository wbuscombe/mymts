package com.mymts.ui.menu

import com.mymts.data.helper.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pin the picker's live/offline group orientation math.
 *
 * The channel list is sorted live-first by the call site (see
 * `WallScreen.sortedByLiveFirst`), so live channels form a contiguous
 * prefix and offline ones a contiguous suffix.
 * [pickerGroupAt] translates the cursor into a 1-based position
 * within whichever group the cursor is currently in — that drives the
 * "LIVE 3/8" or "OFFLINE 2/5" orientation chip the picker renders.
 */
class PickerGroupTest {

    private fun ch(slug: String, playable: Boolean): Channel = Channel(
        slug = slug,
        label = slug,
        kind = "hls",
        currentUrl = if (playable) "https://example.com/$slug.m3u8" else null,
        status = if (playable) Channel.Status.LIVE else Channel.Status.UNAVAILABLE,
        lastSuccessAt = null,
        lastError = null,
        errorCount = 0,
    )

    private val mixed = listOf(
        ch("a", true),
        ch("b", true),
        ch("c", true),  // 3 live
        ch("d", false),
        ch("e", false),  // 2 offline
    )

    @Test fun `cursor on first live channel reports LIVE 1 of 3`() {
        val g = pickerGroupAt(mixed, 0)
        assertEquals(true, g.isLive)
        assertEquals(1, g.positionWithinGroup)
        assertEquals(3, g.groupSize)
    }

    @Test fun `cursor on last live channel reports LIVE 3 of 3`() {
        val g = pickerGroupAt(mixed, 2)
        assertEquals(true, g.isLive)
        assertEquals(3, g.positionWithinGroup)
        assertEquals(3, g.groupSize)
    }

    @Test fun `cursor on first offline channel reports OFFLINE 1 of 2`() {
        val g = pickerGroupAt(mixed, 3)
        assertEquals(false, g.isLive)
        assertEquals(1, g.positionWithinGroup)
        assertEquals(2, g.groupSize)
    }

    @Test fun `cursor on last offline channel reports OFFLINE 2 of 2`() {
        val g = pickerGroupAt(mixed, 4)
        assertEquals(false, g.isLive)
        assertEquals(2, g.positionWithinGroup)
        assertEquals(2, g.groupSize)
    }

    @Test fun `all-live list reports LIVE positions correctly`() {
        val onlyLive = listOf(ch("a", true), ch("b", true))
        val g = pickerGroupAt(onlyLive, 1)
        assertEquals(true, g.isLive)
        assertEquals(2, g.positionWithinGroup)
        assertEquals(2, g.groupSize)
    }

    @Test fun `all-offline list reports OFFLINE positions correctly`() {
        val onlyOffline = listOf(ch("a", false), ch("b", false), ch("c", false))
        val g = pickerGroupAt(onlyOffline, 2)
        assertEquals(false, g.isLive)
        assertEquals(3, g.positionWithinGroup)
        assertEquals(3, g.groupSize)
    }
}
