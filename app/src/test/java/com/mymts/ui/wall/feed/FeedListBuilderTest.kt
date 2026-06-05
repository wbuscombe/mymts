package com.mymts.ui.wall.feed

import com.mymts.data.helper.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Pin grouping + freshness logic for the wall's sectioned feed.
 *
 * The grouping function is pure; these tests don't need Compose or a
 * real clock — `build()` accepts `now` as a parameter so the test
 * suite controls staleness classification deterministically.
 *
 * Invariants pinned:
 *   - Sections are grouped by source, alphabetical case-insensitive
 *     (predictable layout, no reshuffle on poll).
 *   - Within a section, items are newest-first (published time when
 *     present, fetched time as fallback).
 *   - Per-section freshness is computed from the **newest** item's
 *     age; thresholds default to 2h Fresh→Warm, 12h Warm→NotUpdating.
 *   - The flat-item order (after dropping headers) IS the focus
 *     navigation order, so the navigation chapter's `feedIndex`
 *     semantics keep working without model changes.
 *   - [FeedListBuilder.entriesIndexForFocus] maps a focus index to the
 *     correct entries-list row even with intervening headers.
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

    // ============== Trivial / empty ==============

    @Test fun `empty input → empty output`() {
        assertEquals(emptyList<FeedListEntry>(), FeedListBuilder.build(emptyList(), NOW))
    }

    @Test fun `single-source input → one header + items`() {
        val items = listOf(
            item(1, "BBC", "headline a", publishedAtMinutesAgo = 5),
            item(2, "BBC", "headline b", publishedAtMinutesAgo = 12),
        )
        val out = FeedListBuilder.build(items, NOW)
        assertEquals(3, out.size)
        assertTrue(out[0] is FeedListEntry.Header)
        assertEquals("BBC", (out[0] as FeedListEntry.Header).source)
        assertEquals(2, (out[0] as FeedListEntry.Header).itemCount)
        assertTrue(out[1] is FeedListEntry.Item)
        assertTrue(out[2] is FeedListEntry.Item)
    }

    // ============== Section ordering ==============

    @Test fun `sections are alphabetical case-insensitive`() {
        val items = listOf(
            item(1, "Guardian World", "g", publishedAtMinutesAgo = 10),
            item(2, "BBC", "b", publishedAtMinutesAgo = 10),
            item(3, "al jazeera", "aj", publishedAtMinutesAgo = 10),
        )
        val headers = FeedListBuilder.build(items, NOW)
            .filterIsInstance<FeedListEntry.Header>()
            .map { it.source }
        assertEquals(listOf("al jazeera", "BBC", "Guardian World"), headers)
    }

    @Test fun `items with blank source land in a single Unknown source section`() {
        val items = listOf(
            item(1, "", "blank1", publishedAtMinutesAgo = 5),
            item(2, "BBC", "bbc1", publishedAtMinutesAgo = 5),
            item(3, "", "blank2", publishedAtMinutesAgo = 5),
        )
        val headers = FeedListBuilder.build(items, NOW)
            .filterIsInstance<FeedListEntry.Header>()
            .map { it.source }
        // Two sections: "BBC" + an unknown bucket
        assertEquals(2, headers.size)
        assertTrue(headers.any { it.equals("BBC", ignoreCase = true) })
        assertTrue(headers.any { it.contains("nknown", ignoreCase = true) })
    }

    // ============== Within-section ordering ==============

    @Test fun `within a section, items are newest first by published time`() {
        val items = listOf(
            item(10, "BBC", "older", publishedAtMinutesAgo = 60),
            item(11, "BBC", "newest", publishedAtMinutesAgo = 5),
            item(12, "BBC", "middle", publishedAtMinutesAgo = 30),
        )
        val sectionItems = FeedListBuilder.build(items, NOW)
            .filterIsInstance<FeedListEntry.Item>()
            .map { it.item.title }
        assertEquals(listOf("newest", "middle", "older"), sectionItems)
    }

    @Test fun `published time wins over fetched time when both present`() {
        // publishedAt 30 min ago, fetchedAt 5 min ago — published wins (older sort key).
        // Compared against another item where publishedAt is null and fetchedAt is 60 min ago.
        val a = item(1, "BBC", "a", publishedAtMinutesAgo = 30, fetchedAtMinutesAgo = 5)
        val b = item(2, "BBC", "b", fetchedAtMinutesAgo = 60)  // no publishedAt
        val sectionItems = FeedListBuilder.build(listOf(a, b), NOW)
            .filterIsInstance<FeedListEntry.Item>()
            .map { it.item.title }
        // a's published 30min < b's fetched 60min, so a is newer → first.
        assertEquals(listOf("a", "b"), sectionItems)
    }

    @Test fun `fetched time falls back when published is null`() {
        val items = listOf(
            item(10, "BBC", "no-times"),  // both null
            item(11, "BBC", "fetched-only", fetchedAtMinutesAgo = 5),
            item(12, "BBC", "published-only", publishedAtMinutesAgo = 30),
        )
        val titles = FeedListBuilder.build(items, NOW)
            .filterIsInstance<FeedListEntry.Item>()
            .map { it.item.title }
        // fetched-only (5 min) is newest, published-only (30 min) next, no-times last.
        assertEquals(listOf("fetched-only", "published-only", "no-times"), titles)
    }

    // ============== Freshness classification ==============

    @Test fun `section newest 5min ago is Fresh`() {
        val items = listOf(item(1, "BBC", "x", publishedAtMinutesAgo = 5))
        val header = FeedListBuilder.build(items, NOW).first() as FeedListEntry.Header
        assertEquals(SectionFreshness.Fresh, header.freshness)
    }

    @Test fun `section newest 3 hours ago is Warm`() {
        val items = listOf(item(1, "BBC", "x", publishedAtMinutesAgo = 3 * 60))
        val header = FeedListBuilder.build(items, NOW).first() as FeedListEntry.Header
        assertEquals(SectionFreshness.Warm, header.freshness)
    }

    @Test fun `section newest 18 hours ago is NotUpdating`() {
        val items = listOf(item(1, "BBC", "x", publishedAtMinutesAgo = 18 * 60))
        val header = FeedListBuilder.build(items, NOW).first() as FeedListEntry.Header
        assertEquals(SectionFreshness.NotUpdating, header.freshness)
    }

    @Test fun `freshness is computed from the NEWEST item, not the oldest`() {
        // Mix of fresh + ancient — should classify as Fresh (newest wins).
        val items = listOf(
            item(1, "BBC", "fresh", publishedAtMinutesAgo = 5),
            item(2, "BBC", "ancient", publishedAtMinutesAgo = 24 * 60),
        )
        val header = FeedListBuilder.build(items, NOW).first() as FeedListEntry.Header
        assertEquals(SectionFreshness.Fresh, header.freshness)
    }

    @Test fun `section with no timestamps gets Unknown freshness`() {
        val items = listOf(item(1, "BBC", "no-times"))
        val header = FeedListBuilder.build(items, NOW).first() as FeedListEntry.Header
        assertEquals(SectionFreshness.Unknown, header.freshness)
        assertNull(header.newestAgeMs)
    }

    @Test fun `boundary at staleAfter is exclusive for Fresh inclusive for Warm`() {
        // Custom thresholds for sharp boundary test: stale=60min, dead=120min.
        val stale = 60 * 60_000L
        val dead = 120 * 60_000L
        // 59 min → Fresh; 60 min → Warm; 119 min → Warm; 120 min → NotUpdating.
        fun freshAt(min: Long): SectionFreshness {
            val items = listOf(item(1, "X", "x", publishedAtMinutesAgo = min))
            return (FeedListBuilder.build(items, NOW, stale, dead).first() as FeedListEntry.Header)
                .freshness
        }
        assertEquals(SectionFreshness.Fresh, freshAt(59))
        assertEquals(SectionFreshness.Warm, freshAt(60))
        assertEquals(SectionFreshness.Warm, freshAt(119))
        assertEquals(SectionFreshness.NotUpdating, freshAt(120))
    }

    // ============== Flat-item invariant (focus-index mapping) ==============

    @Test fun `flat-item order matches focus traversal order`() {
        val items = listOf(
            item(1, "Guardian World", "g1", publishedAtMinutesAgo = 5),
            item(2, "BBC", "b1", publishedAtMinutesAgo = 5),
            item(3, "BBC", "b2", publishedAtMinutesAgo = 15),
            item(4, "Guardian World", "g2", publishedAtMinutesAgo = 20),
        )
        val out = FeedListBuilder.build(items, NOW)
        val flat = FeedListBuilder.flattenItems(out)
        // Sections alphabetical: BBC first then Guardian World.
        // Within each, newest-first: BBC[b1, b2], Guardian[g1, g2].
        assertEquals(listOf("b1", "b2", "g1", "g2"), flat.map { it.title })
    }

    @Test fun `entriesIndexForFocus maps past headers correctly`() {
        val items = listOf(
            item(1, "BBC", "b1", publishedAtMinutesAgo = 5),
            item(2, "BBC", "b2", publishedAtMinutesAgo = 15),
            item(3, "Guardian", "g1", publishedAtMinutesAgo = 5),
        )
        val out = FeedListBuilder.build(items, NOW)
        // out indices: 0=Header(BBC), 1=Item(b1), 2=Item(b2), 3=Header(Guardian), 4=Item(g1)
        assertEquals(1, FeedListBuilder.entriesIndexForFocus(out, 0))
        assertEquals(2, FeedListBuilder.entriesIndexForFocus(out, 1))
        assertEquals(4, FeedListBuilder.entriesIndexForFocus(out, 2))
    }

    @Test fun `entriesIndexForFocus returns -1 for out-of-range focus`() {
        val items = listOf(item(1, "BBC", "b1", publishedAtMinutesAgo = 5))
        val out = FeedListBuilder.build(items, NOW)
        assertEquals(-1, FeedListBuilder.entriesIndexForFocus(out, -1))
        assertEquals(-1, FeedListBuilder.entriesIndexForFocus(out, 5))
    }

    @Test fun `flat-item count equals total input item count`() {
        val items = (1..15).map { i ->
            val src = listOf("BBC", "Guardian", "NPR")[i % 3]
            item(i.toLong(), src, "title $i", publishedAtMinutesAgo = i.toLong())
        }
        val out = FeedListBuilder.build(items, NOW)
        assertEquals(15, FeedListBuilder.flattenItems(out).size)
    }

    @Test fun `newestAgeMs on header equals NOW minus newest items timestamp`() {
        val items = listOf(
            item(1, "BBC", "newest", publishedAtMinutesAgo = 5),
            item(2, "BBC", "older", publishedAtMinutesAgo = 60),
        )
        val header = FeedListBuilder.build(items, NOW).first() as FeedListEntry.Header
        assertNotNull(header.newestAgeMs)
        // 5 minutes = 300_000 ms
        assertEquals(5 * 60_000L, header.newestAgeMs)
    }
}
