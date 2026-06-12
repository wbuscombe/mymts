package com.mymts.ui.menu

import com.mymts.data.helper.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the channel LIST picker's pure logic: which channel it opens focused on,
 * and the category sectioning. (Navigation, scroll-follow, and focus restoration
 * are Compose behaviours, verified on-device.)
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

    @Test fun `opens focused on the slot's current channel`() {
        assertEquals("bbc-news", channelToFocus(channels, "bbc-news"))
        assertEquals("nasa-tv", channelToFocus(channels, "nasa-tv"))
    }

    @Test fun `opens on the first channel when selection is null or missing`() {
        assertEquals("cnn", channelToFocus(channels, null))
        assertEquals("cnn", channelToFocus(channels, "no-such-channel"))
    }

    @Test fun `empty list focuses nothing`() {
        assertEquals(null, channelToFocus(emptyList(), "cnn"))
    }

    // ---- category taxonomy ----

    @Test fun `channels map to their categories, unmapped fall to General`() {
        assertEquals(ChannelCategory.SPORTS, ChannelCategory.of("cbs-sports-hq"))
        assertEquals(ChannelCategory.US_NEWS, ChannelCategory.of("livenow-fox"))
        assertEquals(ChannelCategory.GLOBAL_NEWS, ChannelCategory.of("bbc-news"))
        assertEquals(ChannelCategory.BUSINESS, ChannelCategory.of("bloomberg-tv"))
        assertEquals(ChannelCategory.WEATHER, ChannelCategory.of("fox-weather"))
        assertEquals(ChannelCategory.GENERAL, ChannelCategory.of("redbull-tv"))   // unmapped
    }

    @Test fun `sectioned groups in taxonomy order and drops empty sections`() {
        val list = listOf(
            ch("fox-weather"), ch("cbs-sports-hq"), ch("bbc-news"), ch("cnn"),
        )
        val sections = ChannelCategory.sectioned(list) { it.slug }
        // Order: Sports, US News, Global News, (no Business), Weather — General empty/dropped.
        assertEquals(
            listOf("Sports", "US News", "Global News", "Weather"),
            sections.map { it.first },
        )
        assertEquals(listOf("cbs-sports-hq"), sections[0].second.map { it.slug })
        assertEquals(listOf("fox-weather"), sections.last().second.map { it.slug })
    }
}
