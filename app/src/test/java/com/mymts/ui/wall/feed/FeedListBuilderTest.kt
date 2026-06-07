package com.mymts.ui.wall.feed

import com.mymts.data.helper.FeedItem
import com.mymts.data.settings.FeedRecency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Pin the AGNOSTIC feed ordering (panel-fit-&-agnostic-feed chapter,
 * 2026-06-07): one newest-first list across ALL sources, with the source
 * carried per item (no per-source sections). Pure — no Compose / clock.
 *
 * Invariants pinned:
 *   - One flat list, newest-first across all sources (interleaved by time,
 *     NOT grouped by source).
 *   - Published time preferred, fetched fallback; items missing both sort
 *     last; ties broken by id (newest-id first).
 *   - The flat order IS the focus order (feedIndex == list index).
 *   - `sourceLabel` collapses blank to the Unknown bucket.
 *   - `applyFilters` (source denylist + recency) + `distinctSources`
 *     behave over the agnostic list.
 */
class FeedListBuilderTest {

    private val NOW = Instant.parse("2026-06-04T20:00:00Z").toEpochMilli()

    private fun item(
        id: Long,
        source: String,
        title: String,
        publishedAtMinutesAgo: Long? = null,
        fetchedAtMinutesAgo: Long? = null,
    ): FeedItem {
        val publishedIso = publishedAtMinutesAgo?.let {
            Instant.ofEpochMilli(NOW - it * 60_000L).toString()
        }
        val fetchedIso = fetchedAtMinutesAgo?.let {
            Instant.ofEpochMilli(NOW - it * 60_000L).toString()
        }
        return FeedItem(
            id = id,
            source = source,
            title = title,
            summary = "summary $id",
            link = null,
            publishedAtIso = publishedIso,
            fetchedAtIso = fetchedIso,
        )
    }

    // ============== build: agnostic newest-first ==============

    @Test fun `empty input to empty output`() {
        assertEquals(emptyList<FeedItem>(), FeedListBuilder.build(emptyList()))
    }

    @Test fun `single source stays newest-first`() {
        val items = listOf(
            item(1, "BBC", "older", publishedAtMinutesAgo = 60),
            item(2, "BBC", "newest", publishedAtMinutesAgo = 5),
            item(3, "BBC", "middle", publishedAtMinutesAgo = 30),
        )
        assertEquals(
            listOf("newest", "middle", "older"),
            FeedListBuilder.build(items).map { it.title },
        )
    }

    @Test fun `multiple sources interleave by time, NOT grouped by source`() {
        val items = listOf(
            item(1, "BBC", "bbc-5", publishedAtMinutesAgo = 5),
            item(2, "Guardian", "guardian-3", publishedAtMinutesAgo = 3),
            item(3, "BBC", "bbc-10", publishedAtMinutesAgo = 10),
            item(4, "NPR", "npr-1", publishedAtMinutesAgo = 1),
        )
        // Newest-first across ALL sources: npr-1, guardian-3, bbc-5, bbc-10.
        // If it were grouped by source, BBC's two would be adjacent — they
        // are NOT here, proving the agnostic interleave.
        assertEquals(
            listOf("npr-1", "guardian-3", "bbc-5", "bbc-10"),
            FeedListBuilder.build(items).map { it.title },
        )
    }

    @Test fun `published time preferred over fetched`() {
        val a = item(1, "BBC", "a", publishedAtMinutesAgo = 30, fetchedAtMinutesAgo = 5)
        val b = item(2, "NPR", "b", fetchedAtMinutesAgo = 60)  // no publishedAt
        // a's published 30min < b's fetched 60min → a newer → first.
        assertEquals(listOf("a", "b"), FeedListBuilder.build(listOf(a, b)).map { it.title })
    }

    @Test fun `fetched falls back when published null, no-timestamp items sort last`() {
        val items = listOf(
            item(10, "BBC", "no-times"),                        // both null
            item(11, "NPR", "fetched-only", fetchedAtMinutesAgo = 5),
            item(12, "Guardian", "published-only", publishedAtMinutesAgo = 30),
        )
        assertEquals(
            listOf("fetched-only", "published-only", "no-times"),
            FeedListBuilder.build(items).map { it.title },
        )
    }

    @Test fun `ties among distinct ids break newest-id first`() {
        val items = listOf(
            item(1, "BBC", "lo-id", publishedAtMinutesAgo = 10),
            item(2, "NPR", "hi-id", publishedAtMinutesAgo = 10),
        )
        assertEquals(listOf("hi-id", "lo-id"), FeedListBuilder.build(items).map { it.title })
    }

    // ============== rowKey (LazyColumn key uniqueness) ==============

    @Test fun `rowKey uses the helper id when present`() {
        assertEquals(7L, FeedListBuilder.rowKey(item(7, "BBC", "x", publishedAtMinutesAgo = 1), 3))
    }

    @Test fun `rowKey never collides for id-less duplicate items`() {
        // Two cross-posted wire stories: identical source+title, no id (-1).
        // A title-hash key would collide and crash the LazyColumn; the
        // index-qualified fallback must keep them distinct.
        val a = FeedItem(id = -1, source = "AP", title = "Breaking: same", summary = "", link = null, publishedAtIso = null, fetchedAtIso = null)
        val k0 = FeedListBuilder.rowKey(a, 0)
        val k1 = FeedListBuilder.rowKey(a, 1)
        assertTrue("id-less keys must differ by index", k0 != k1)
    }

    @Test fun `flat count equals input count`() {
        val items = (1..15).map { i ->
            val src = listOf("BBC", "Guardian", "NPR")[i % 3]
            item(i.toLong(), src, "t$i", publishedAtMinutesAgo = i.toLong())
        }
        assertEquals(15, FeedListBuilder.build(items).size)
    }

    // ============== sourceLabel ==============

    @Test fun `sourceLabel collapses blank to Unknown bucket`() {
        assertEquals("BBC", FeedListBuilder.sourceLabel(item(1, "BBC", "x")))
        assertEquals("Unknown source", FeedListBuilder.sourceLabel(item(2, "", "x")))
        assertEquals("Unknown source", FeedListBuilder.sourceLabel(item(3, "   ", "x")))
    }

    // ============== applyFilters ==============

    @Test fun `source denylist drops case-insensitively, blank via Unknown bucket`() {
        val items = listOf(
            item(1, "BBC", "b"),
            item(2, "Reason", "r"),
            item(3, "", "blank"),
        )
        val kept = FeedListBuilder.applyFilters(items, setOf("reason"), FeedRecency.All, NOW)
        assertEquals(listOf("b", "blank"), kept.map { it.title })
        // Hiding the Unknown bucket drops blank-source items.
        val noBlank = FeedListBuilder.applyFilters(items, setOf("Unknown source"), FeedRecency.All, NOW)
        assertEquals(listOf("b", "r"), noBlank.map { it.title })
    }

    @Test fun `recency drops old, keeps no-timestamp under All, drops under bounded`() {
        val items = listOf(
            item(1, "BBC", "recent", publishedAtMinutesAgo = 30),
            item(2, "BBC", "old", publishedAtMinutesAgo = 5 * 60),
            item(3, "BBC", "no-ts"),  // no timestamp
        )
        // All → everything (no-ts kept).
        assertEquals(3, FeedListBuilder.applyFilters(items, emptySet(), FeedRecency.All, NOW).size)
        // Last hour → only "recent" (old dropped, no-ts dropped — not provably recent).
        val hour = FeedListBuilder.applyFilters(items, emptySet(), FeedRecency.Hour, NOW)
        assertEquals(listOf("recent"), hour.map { it.title })
    }

    // ============== distinctSources ==============

    @Test fun `distinctSources alphabetical case-insensitive, blank to Unknown`() {
        val items = listOf(
            item(1, "Guardian World", "g"),
            item(2, "BBC", "b"),
            item(3, "al jazeera", "aj"),
            item(4, "", "blank"),
            item(5, "BBC", "b2"),
        )
        assertEquals(
            listOf("al jazeera", "BBC", "Guardian World", "Unknown source"),
            FeedListBuilder.distinctSources(items),
        )
    }
}
