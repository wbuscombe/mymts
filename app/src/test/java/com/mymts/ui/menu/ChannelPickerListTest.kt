package com.mymts.ui.menu

import com.mymts.data.helper.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the channel LIST picker's pure logic (2026-06-11, replacing the cycler):
 * the list opens focused on the slot's current channel, or the top when unset /
 * no longer present. (Navigation, scroll-follow, and focus restoration are
 * Compose behaviours, verified on-device.)
 */
class ChannelPickerListTest {

    private fun ch(slug: String, playable: Boolean = true): Channel = Channel(
        slug = slug,
        label = slug,
        kind = "hls",
        currentUrl = if (playable) "https://example.com/$slug.m3u8" else null,
        status = if (playable) Channel.Status.LIVE else Channel.Status.UNAVAILABLE,
        lastSuccessAt = null,
        lastError = null,
        errorCount = 0,
    )

    private val channels = listOf(ch("cnn"), ch("bbc-news"), ch("nasa-tv", playable = false))

    @Test fun `opens on the slot's current channel`() {
        assertEquals(1, initialChannelIndex(channels, "bbc-news"))
        assertEquals(2, initialChannelIndex(channels, "nasa-tv"))
    }

    @Test fun `opens at the top when selection is null or missing`() {
        assertEquals(0, initialChannelIndex(channels, null))
        assertEquals(0, initialChannelIndex(channels, "no-such-channel"))
    }

    @Test fun `empty channel list resolves to index 0 (overlay no-ops anyway)`() {
        assertEquals(0, initialChannelIndex(emptyList(), "cnn"))
    }
}
