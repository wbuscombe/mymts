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

    // ---- initial-focus index (the focus-loss fix: scroll the target into view
    //      BEFORE requesting focus, so an off-screen row composes + focuses) ----

    @Test fun `initialFocusIndex is the flat LazyColumn index of the target, counting section headers`() {
        // Sections (taxonomy order): Sports[cbs-sports-hq], US News[cnn, livenow-fox], Weather[fox-weather]
        val list = listOf(ch("cbs-sports-hq"), ch("cnn"), ch("livenow-fox"), ch("fox-weather"))
        val sections = ChannelCategory.sectioned(list) { it.slug }
        // flat: 0=Sports hdr, 1=cbs-sports-hq, 2=US News hdr, 3=cnn, 4=livenow-fox, 5=Weather hdr, 6=fox-weather
        assertEquals(1, initialFocusIndex(sections, "cbs-sports-hq")) // first channel, first section
        assertEquals(3, initialFocusIndex(sections, "cnn"))           // first in a multi-channel section
        assertEquals(4, initialFocusIndex(sections, "livenow-fox"))   // second in that section
        // the bug case: the slot's current channel sits in the LAST section, off the
        // initial viewport — its index must be found so it can be scrolled into view.
        assertEquals(6, initialFocusIndex(sections, "fox-weather"))
    }

    @Test fun `initialFocusIndex is -1 for a null, missing, or empty target`() {
        val sections = ChannelCategory.sectioned(listOf(ch("bbc-news"))) { it.slug }
        assertEquals(-1, initialFocusIndex(sections, null))
        assertEquals(-1, initialFocusIndex(sections, "no-such-channel"))
        assertEquals(-1, initialFocusIndex(emptyList(), "bbc-news"))
    }

    @Test fun `channelToFocus and initialFocusIndex agree — the focused slug resolves to a real row`() {
        val list = listOf(ch("cbs-sports-hq"), ch("cnn"), ch("fox-weather"))
        val sections = ChannelCategory.sectioned(list) { it.slug }
        // whatever channelToFocus picks (current, or first fallback) must have a real index.
        for (sel in listOf("fox-weather", null, "no-such")) {
            val slug = channelToFocus(list, sel)
            assertEquals(true, initialFocusIndex(sections, slug) >= 0)
        }
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
