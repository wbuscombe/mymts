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

    private fun ch(
        slug: String,
        playable: Boolean = true,
        category: String = ChannelCategory.of(slug),
    ): Channel = Channel(
        slug = slug,
        label = slug,
        kind = "hls",
        category = category,
        currentUrl = if (playable) "https://example.com/$slug.m3u8" else null,
        status = if (playable) Channel.Status.LIVE else Channel.Status.UNAVAILABLE,
        lastSuccessAt = null,
        lastError = null,
        errorCount = 0,
    )

    /** Mirrors the picker's real grouping path: server `category`, falling back to
     *  the compiled map only when blank. */
    private fun pickerSections(list: List<Channel>) =
        ChannelCategory.sectionedByCategory(list) { it.category.ifBlank { ChannelCategory.of(it.slug) } }

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
        val sections = pickerSections(list)
        // flat: 0=Sports hdr, 1=cbs-sports-hq, 2=US News hdr, 3=cnn, 4=livenow-fox, 5=Weather hdr, 6=fox-weather
        assertEquals(1, initialFocusIndex(sections, "cbs-sports-hq")) // first channel, first section
        assertEquals(3, initialFocusIndex(sections, "cnn"))           // first in a multi-channel section
        assertEquals(4, initialFocusIndex(sections, "livenow-fox"))   // second in that section
        // the bug case: the slot's current channel sits in the LAST section, off the
        // initial viewport — its index must be found so it can be scrolled into view.
        assertEquals(6, initialFocusIndex(sections, "fox-weather"))
    }

    @Test fun `initialFocusIndex is -1 for a null, missing, or empty target`() {
        val sections = pickerSections(listOf(ch("bbc-news")))
        assertEquals(-1, initialFocusIndex(sections, null))
        assertEquals(-1, initialFocusIndex(sections, "no-such-channel"))
        assertEquals(-1, initialFocusIndex(emptyList(), "bbc-news"))
    }

    @Test fun `channelToFocus and initialFocusIndex agree — the focused slug resolves to a real row`() {
        val list = listOf(ch("cbs-sports-hq"), ch("cnn"), ch("fox-weather"))
        val sections = pickerSections(list)
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
        val sections = pickerSections(list)
        // Order: Sports, US News, Global News, (no Business), Weather — General empty/dropped.
        assertEquals(
            listOf("Sports", "US News", "Global News", "Weather"),
            sections.map { it.first },
        )
        assertEquals(listOf("cbs-sports-hq"), sections[0].second.map { it.slug })
        assertEquals(listOf("fox-weather"), sections.last().second.map { it.slug })
    }

    // ---- server-authoritative grouping (the durable fix) ----

    @Test fun `new gov feeds group by SERVER category even though the compiled map omits them`() {
        // us-senate-floor / us-house-oversight / us-state-dept are NOT in the compiled
        // BY_SLUG map (of() would dump them in General). With the helper-served
        // category "US News" they group correctly — the exact drift this fix closes.
        val list = listOf(
            ch("us-senate-floor", category = "US News"),
            ch("us-house-oversight", category = "US News"),
            ch("us-state-dept", category = "US News"),
            ch("bbc-news", category = "Global News"),
        )
        val sections = pickerSections(list)
        assertEquals(listOf("US News", "Global News"), sections.map { it.first })
        assertEquals(
            listOf("us-senate-floor", "us-house-oversight", "us-state-dept"),
            sections[0].second.map { it.slug },
        )
        // Sanity: the compiled map alone WOULD have mis-sorted these to General.
        assertEquals(ChannelCategory.GENERAL, ChannelCategory.of("us-senate-floor"))
    }

    @Test fun `server category wins over the compiled slug map`() {
        // of("cnn") is US News, but if the helper says Global News, the server wins.
        val sections = pickerSections(listOf(ch("cnn", category = "Global News")))
        assertEquals(listOf("Global News"), sections.map { it.first })
    }

    @Test fun `an unrecognized server category is shown, ordered just before General`() {
        // A future server-side section the app's compiled ORDER doesn't know must still
        // appear — appended (alphabetically) before General — never silently dropped.
        // ("Podcasts" is deliberately NOT in ChannelCategory.ORDER; Government/Cameras/
        // Nature/Space now ARE, so they no longer exercise this unknown-append path.)
        val list = listOf(
            ch("cbs-sports-hq", category = "Sports"),
            ch("some-show", category = "Podcasts"),
            ch("redbull-tv", category = "General"),
        )
        val sections = pickerSections(list)
        assertEquals(listOf("Sports", "Podcasts", "General"), sections.map { it.first })
    }

    // ---- weather-radar WIDGET section (unified registry, 2026-07) ----

    private fun radar(region: String) = Channel(
        slug = "weather-radar-$region",
        label = "Radar $region",
        kind = Channel.KIND_WEATHER_RADAR,
        category = ChannelCategory.WEATHER_RADAR,
        currentUrl = "/api/weather/radar/$region",
        status = Channel.Status.LIVE,
        lastSuccessAt = null, lastError = null, errorCount = 0,
    )

    @Test fun `radar widgets section under Weather Radar, right after Weather`() {
        // The native picker now lists the radar widgets (no more web-only gate) and
        // sections them under "Weather Radar", immediately after "Weather" — identical
        // ordering to the helper CATEGORY_ORDER + web CHANNEL_CATEGORY_ORDER.
        val list = listOf(
            ch("cnn", category = "US News"),
            ch("fox-weather", category = "Weather"),
            radar("kilx"),
            radar("conus"),
            ch("earthcam-live", category = "Cameras"),
        )
        val sections = pickerSections(list)
        assertEquals(
            listOf("US News", "Weather", "Weather Radar", "Cameras"),
            sections.map { it.first },
        )
        val radarSection = sections.first { it.first == "Weather Radar" }
        assertEquals(listOf("weather-radar-kilx", "weather-radar-conus"),
            radarSection.second.map { it.slug })
    }

    @Test fun `ChannelCategory ORDER matches the helper and web taxonomy exactly`() {
        // The cross-surface parity lock (also enforced by scripts/check_channel_parity.py):
        // native section order MUST equal the helper CATEGORY_ORDER / web CHANNEL_CATEGORY_ORDER.
        assertEquals(
            listOf("Sports", "US News", "Global News", "Business", "Weather",
                "Weather Radar", "Cameras", "Government", "Nature", "Space", "General"),
            ChannelCategory.ORDER,
        )
    }

    @Test fun `blank server category falls back to the compiled slug map`() {
        // An older helper that doesn't serve `category` → graceful degradation, not
        // everything-in-General: cnn still lands in US News via of().
        val sections = pickerSections(listOf(ch("cnn", category = "")))
        assertEquals(listOf("US News"), sections.map { it.first })
    }
}
