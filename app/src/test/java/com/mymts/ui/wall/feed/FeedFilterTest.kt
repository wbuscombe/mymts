package com.mymts.ui.wall.feed

import com.mymts.data.helper.FeedItem
import com.mymts.data.settings.FeedRecency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Pin the pure feed-filter logic (source denylist + recency window).
 * Operates on already-fetched plain-text items — no fetch, no web (A1).
 */
class FeedFilterTest {

    private val NOW = Instant.parse("2026-06-06T12:00:00Z").toEpochMilli()

    private fun item(id: Long, source: String, minutesAgo: Long?): FeedItem {
        val iso = minutesAgo?.let { Instant.ofEpochMilli(NOW - it * 60_000L).toString() }
        return FeedItem(
            id = id, source = source, title = "t$id", summary = null, link = null,
            publishedAtIso = iso, fetchedAtIso = iso,
        )
    }

    // ---- source denylist ----

    @Test fun `hidden source is filtered out, others kept`() {
        val items = listOf(item(1, "BBC", 5), item(2, "Reason", 5), item(3, "NPR", 5))
        val out = FeedListBuilder.applyFilters(items, setOf("Reason"), emptySet(), FeedRecency.All, NOW)
        assertEquals(listOf("BBC", "NPR"), out.map { it.source })
    }

    @Test fun `denylist match is case-insensitive`() {
        val items = listOf(item(1, "BBC World", 5), item(2, "NPR", 5))
        val out = FeedListBuilder.applyFilters(items, setOf("bbc world"), emptySet(), FeedRecency.All, NOW)
        assertEquals(listOf("NPR"), out.map { it.source })
    }

    @Test fun `empty denylist keeps everything (new sources show by default)`() {
        val items = listOf(item(1, "BBC", 5), item(2, "Brand New Source", 5))
        val out = FeedListBuilder.applyFilters(items, emptySet(), emptySet(), FeedRecency.All, NOW)
        assertEquals(2, out.size)
    }

    @Test fun `blank source hidden via the Unknown source bucket label`() {
        val items = listOf(item(1, "", 5), item(2, "BBC", 5))
        val out = FeedListBuilder.applyFilters(items, setOf("Unknown source"), emptySet(), FeedRecency.All, NOW)
        assertEquals(listOf("BBC"), out.map { it.source })
    }

    // ---- recency window ----

    @Test fun `recency All keeps every item regardless of age`() {
        val items = listOf(item(1, "BBC", 5), item(2, "BBC", 48 * 60))
        assertEquals(2, FeedListBuilder.applyFilters(items, emptySet(), emptySet(), FeedRecency.All, NOW).size)
    }

    @Test fun `recency window drops items older than the window`() {
        val items = listOf(
            item(1, "BBC", 30),        // 30 min — inside 1h
            item(2, "BBC", 90),        // 90 min — outside 1h, inside 6h
            item(3, "BBC", 10 * 60),   // 10h — outside 6h, inside 24h
            item(4, "BBC", 48 * 60),   // 48h — outside 24h
        )
        assertEquals(setOf(1L), FeedListBuilder.applyFilters(items, emptySet(), emptySet(), FeedRecency.Hour, NOW).map { it.id }.toSet())
        assertEquals(setOf(1L, 2L), FeedListBuilder.applyFilters(items, emptySet(), emptySet(), FeedRecency.SixHours, NOW).map { it.id }.toSet())
        assertEquals(setOf(1L, 2L, 3L), FeedListBuilder.applyFilters(items, emptySet(), emptySet(), FeedRecency.Day, NOW).map { it.id }.toSet())
    }

    @Test fun `item with no timestamp is kept under All but dropped under a bounded window`() {
        val noTs = item(9, "BBC", null)
        assertTrue(FeedListBuilder.applyFilters(listOf(noTs), emptySet(), emptySet(), FeedRecency.All, NOW).isNotEmpty())
        assertTrue(FeedListBuilder.applyFilters(listOf(noTs), emptySet(), emptySet(), FeedRecency.Day, NOW).isEmpty())
    }

    @Test fun `source + recency compose`() {
        val items = listOf(
            item(1, "BBC", 5), item(2, "Reason", 5),
            item(3, "BBC", 48 * 60), item(4, "Reason", 48 * 60),
        )
        val out = FeedListBuilder.applyFilters(items, setOf("Reason"), emptySet(), FeedRecency.Hour, NOW)
        assertEquals(setOf(1L), out.map { it.id }.toSet())  // BBC + recent only
    }

    // ---- distinctSources ----

    @Test fun `distinctSources alphabetical, blank to Unknown source`() {
        val items = listOf(item(1, "Guardian", 1), item(2, "bbc", 1), item(3, "", 1), item(4, "bbc", 1))
        assertEquals(listOf("bbc", "Guardian", "Unknown source"), FeedListBuilder.distinctSources(items))
    }
}
