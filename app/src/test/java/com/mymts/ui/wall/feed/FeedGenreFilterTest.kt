package com.mymts.ui.wall.feed

import com.mymts.data.helper.FeedItem
import com.mymts.data.settings.FeedRecency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Pins the TWO-LEVEL feed filter (news-genre-groups chapter, Part E §3 + §4):
 * the genre master switch composed with the per-source levers, and the
 * Sports-genre / Sports-leagues-pool reconciliation. Pure — operates on
 * already-fetched plain-text items (A1), no fetch/web.
 *
 * Precedence under test (see [FeedListBuilder.applyFilters]):
 *   1. a GENRE switched off ([hiddenGenres]) removes ALL its sources,
 *      overriding any per-source state below;
 *   2. within a SHOWN genre, a non-sports source off ([hiddenSources]) removes
 *      just that source;
 *   3. the Sports genre's per-source level IS the shared Sports-leagues pool
 *      ([hiddenLeagues]) — the same pool the ticker scores use; the Sports
 *      genre toggle sits above it.
 */
class FeedGenreFilterTest {

    private val NOW = Instant.parse("2026-06-22T12:00:00Z").toEpochMilli()

    private fun item(id: Long, source: String): FeedItem =
        FeedItem(
            id = id, source = source, title = source, summary = null, link = null,
            publishedAtIso = Instant.ofEpochMilli(NOW - id * 60_000L).toString(),
            fetchedAtIso = null,
        )

    /** A cross-genre sample: US News, Global News, Business, Sports, + a blank. */
    private fun sample(): List<FeedItem> = listOf(
        item(1, "CBS News"),         // US News
        item(2, "Politico"),         // US News
        item(3, "BBC World"),        // Global News
        item(4, "Bloomberg Markets"),// Business
        item(5, "NFL"),              // Sports (league)
        item(6, "MLB"),              // Sports (league)
        item(7, ""),                 // unmapped → General (blank)
    )

    private fun keptSources(
        items: List<FeedItem>,
        hiddenSources: Set<String> = emptySet(),
        hiddenLeagues: Set<String> = emptySet(),
        hiddenGenres: Set<String> = emptySet(),
    ): List<String> = FeedListBuilder.applyFilters(
        items, hiddenSources, hiddenLeagues, FeedRecency.All, NOW, hiddenGenres = hiddenGenres,
    ).map { it.source }

    // ---- Level 1: the genre master switch ----

    @Test fun `genre off removes ALL of that genre's sources`() {
        val kept = keptSources(sample(), hiddenGenres = setOf(FeedGenres.US_NEWS))
        assertTrue("both US-News sources gone", kept.none { it == "CBS News" || it == "Politico" })
        assertTrue("other genres untouched", kept.containsAll(listOf("BBC World", "Bloomberg Markets")))
    }

    @Test fun `hiding the General genre drops unmapped and blank sources`() {
        val kept = FeedListBuilder.applyFilters(
            sample(), emptySet(), emptySet(), FeedRecency.All, NOW,
            hiddenGenres = setOf(FeedGenres.GENERAL),
        )
        // The blank-source item (id 7) is General → dropped.
        assertTrue(kept.none { it.id == 7L })
        assertEquals(6, kept.size)
    }

    @Test fun `empty hiddenGenres keeps every genre (new genre shows by default)`() {
        assertEquals(sample().size, keptSources(sample()).size)
    }

    // ---- Level 2: per-source within a shown genre ----

    @Test fun `within a shown genre only the disabled source is removed`() {
        // Hide one US-News source; its sibling + other genres stay.
        val kept = keptSources(sample(), hiddenSources = setOf("CBS News"))
        assertTrue(kept.none { it == "CBS News" })
        assertTrue(kept.contains("Politico"))
    }

    @Test fun `genre off and per-source compose - genre off wins over a still-enabled source`() {
        // US News genre OFF but no per-source hidden → both still gone (level 1).
        val kept = keptSources(
            sample(),
            hiddenSources = emptySet(),
            hiddenGenres = setOf(FeedGenres.US_NEWS),
        )
        assertTrue(kept.none { it == "CBS News" || it == "Politico" })
    }

    // ---- §4: Sports genre ⇄ Sports-leagues pool reconciliation ----

    @Test fun `Sports genre off removes every league's news even with an empty leagues pool`() {
        val kept = keptSources(sample(), hiddenLeagues = emptySet(), hiddenGenres = setOf(FeedGenres.SPORTS))
        assertTrue("no league news survives a Sports-genre-off", kept.none { it == "NFL" || it == "MLB" })
        // Non-sports genres are unaffected by the Sports genre toggle.
        assertTrue(kept.containsAll(listOf("CBS News", "BBC World", "Bloomberg Markets")))
    }

    @Test fun `Sports genre on - the leagues pool still filters individual leagues`() {
        // Genre ON (not hidden); hide NFL via the SHARED pool → only NFL drops.
        val kept = keptSources(sample(), hiddenLeagues = setOf("NFL"))
        assertTrue(kept.none { it == "NFL" })
        assertTrue(kept.contains("MLB"))
    }

    @Test fun `Sports genre off OVERRIDES the leagues pool (precedence is genre-then-pool)`() {
        // MLB is NOT in the pool, but the Sports GENRE is off → MLB still drops.
        val kept = keptSources(
            sample(),
            hiddenLeagues = setOf("NFL"),               // only NFL in the pool
            hiddenGenres = setOf(FeedGenres.SPORTS),    // but the whole genre is off
        )
        assertTrue("genre off beats a pool that would have kept MLB", kept.none { it == "MLB" || it == "NFL" })
    }

    @Test fun `pool casing is reconciled with the league source labels (case-insensitive)`() {
        // The Sports-leagues filter may store "nfl"; the feed source is "NFL".
        val kept = keptSources(sample(), hiddenLeagues = setOf("nfl"))
        assertTrue(kept.none { it == "NFL" })
        assertTrue(kept.contains("MLB"))
    }

    // ---- all three levels compose ----

    @Test fun `genre, source, and league levels compose in one pass`() {
        val items = sample()
        val kept = keptSources(
            items,
            hiddenSources = setOf("CBS News"),          // level 2: one US-News source
            hiddenLeagues = setOf("MLB"),               // level 3: one league
            hiddenGenres = setOf(FeedGenres.GLOBAL_NEWS),// level 1: a whole genre
        )
        assertTrue(kept.none { it == "CBS News" })       // per-source
        assertTrue(kept.none { it == "BBC World" })      // genre off
        assertTrue(kept.none { it == "MLB" })            // league pool
        assertTrue(kept.contains("Politico"))            // US-News survivor
        assertTrue(kept.contains("NFL"))                 // Sports on, NFL not pooled
        assertTrue(kept.contains("Bloomberg Markets"))   // Business untouched
    }

    @Test fun `sports cap still applies when the Sports genre is on`() {
        // 20 NFL + 2 general; the newest-14 sports cap holds independent of genres.
        val nfl = (1..20).map { item(it.toLong(), "NFL") }
        val general = listOf(item(100, "BBC World"), item(101, "Reason"))
        val kept = FeedListBuilder.applyFilters(nfl + general, emptySet(), emptySet(), FeedRecency.All, NOW)
        assertEquals(14, kept.count { it.source == "NFL" })
        assertEquals(2, kept.count { it.source != "NFL" })
    }
}
