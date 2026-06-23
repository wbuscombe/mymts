package com.mymts.ui.wall.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the data-driven feed-source GENRE taxonomy ([FeedGenres]) — the §1
 * mapping the two-level News filter + the [FeedListBuilder] genre level both
 * read. Pure; no Compose. Invariants:
 *   - every seed source label categorises into its intended genre,
 *   - an unmapped label falls through to [FeedGenres.GENERAL] (never dropped),
 *   - [FeedGenres.sectioned] groups in [FeedGenres.ORDER], omits empty genres
 *     (so Weather — which has no feed source — never appears), and sorts within
 *     a genre.
 */
class FeedGenresTest {

    @Test fun `global-news outlets categorise as Global News`() {
        for (label in listOf("BBC World", "Al Jazeera", "Guardian World", "NPR World")) {
            assertEquals(label, FeedGenres.GLOBAL_NEWS, FeedGenres.genreOf(label))
        }
    }

    @Test fun `us-news outlets categorise as US News`() {
        for (label in listOf(
            "PBS NewsHour", "Christian Science Monitor", "CBS News", "NBC News",
            "Politico", "The Dispatch", "National Review", "Reason",
        )) {
            assertEquals(label, FeedGenres.US_NEWS, FeedGenres.genreOf(label))
        }
    }

    @Test fun `markets outlet categorises as Business`() {
        assertEquals(FeedGenres.BUSINESS, FeedGenres.genreOf("Bloomberg Markets"))
    }

    @Test fun `the eight ESPN league desks categorise as Sports`() {
        for (label in listOf("NFL", "NCAAF", "UFL", "NBA", "WNBA", "NCAAB", "MLB", "NHL")) {
            assertEquals(label, FeedGenres.SPORTS, FeedGenres.genreOf(label))
        }
    }

    @Test fun `an unmapped label falls through to General (never dropped)`() {
        assertEquals(FeedGenres.GENERAL, FeedGenres.genreOf("Brand New Wire"))
        assertEquals(FeedGenres.GENERAL, FeedGenres.genreOf(""))
        assertEquals(FeedGenres.GENERAL, FeedGenres.genreOf("Unknown source"))
    }

    @Test fun `lookup is case-insensitive and trims`() {
        assertEquals(FeedGenres.US_NEWS, FeedGenres.genreOf("politico"))
        assertEquals(FeedGenres.SPORTS, FeedGenres.genreOf("  nfl  "))
        assertEquals(FeedGenres.GLOBAL_NEWS, FeedGenres.genreOf("BBC WORLD"))
    }

    @Test fun `isSports is true only for the Sports genre`() {
        assertTrue(FeedGenres.isSports(FeedGenres.SPORTS))
        assertFalse(FeedGenres.isSports(FeedGenres.US_NEWS))
        assertFalse(FeedGenres.isSports(FeedGenres.GENERAL))
    }

    // ---- sectioned: grouping for the two-level overlay ----

    @Test fun `sectioned groups in ORDER and omits empty genres including Weather`() {
        // A representative cross-genre source set; no Weather source exists.
        val sources = listOf("Reason", "BBC World", "Bloomberg Markets", "NFL", "CBS News")
        val sectioned = FeedGenres.sectioned(sources)
        // US News, Global News, Business, Sports — in ORDER; Weather absent.
        assertEquals(
            listOf(FeedGenres.US_NEWS, FeedGenres.GLOBAL_NEWS, FeedGenres.BUSINESS, FeedGenres.SPORTS),
            sectioned.map { it.first },
        )
        assertFalse("Weather has no feed source → never a group", sectioned.any { it.first == "Weather" })
    }

    @Test fun `sectioned sorts sources within a genre case-insensitively`() {
        val sources = listOf("Reason", "CBS News", "NBC News", "Politico")
        val usNews = FeedGenres.sectioned(sources).first { it.first == FeedGenres.US_NEWS }.second
        assertEquals(listOf("CBS News", "NBC News", "Politico", "Reason"), usNews)
    }

    @Test fun `sectioned puts unmapped sources in a trailing General group`() {
        val sources = listOf("Mystery Feed", "BBC World")
        val sectioned = FeedGenres.sectioned(sources)
        assertEquals(FeedGenres.GENERAL, sectioned.last().first)
        assertEquals(listOf("Mystery Feed"), sectioned.last().second)
    }

    @Test fun `sectioned of empty input is empty (no dead groups)`() {
        assertTrue(FeedGenres.sectioned(emptyList()).isEmpty())
    }
}
