package com.mymts.ui.wall.feed

import com.mymts.data.helper.FeedItem
import com.mymts.data.settings.FeedRecency

/**
 * Pure transform from a flat `List<FeedItem>` into a section-ordered
 * `List<FeedListEntry>` for the wall's feed pane.
 *
 * Lives in a feed/ sub-package because, like the navigation focus
 * model, it's plain Kotlin with no Compose / Android dependencies —
 * the entire grouping + freshness-classification logic is unit-tested
 * in `FeedListBuilderTest` so a regression here is visible without
 * booting the UI.
 *
 * The flat-index invariant: the **order of [FeedListEntry.Item] within
 * the returned list IS the focus-navigation order**. The
 * [com.mymts.ui.nav.WallFocusModel] keeps treating the feed zone as a
 * single contiguous list indexed 0..itemCount-1; sectioning only
 * changes the visible layout, not the navigation graph. This preserves
 * the no-trap invariants the navigation chapter pinned without
 * touching the focus model.
 */
object FeedListBuilder {

    /**
     * Pure feed-filter step (feed-filtering chapter). Applied to the raw
     * items BEFORE [build] groups them — so the sectioned layout, the
     * per-source freshness chips, and the focus flat-index all operate on
     * exactly the filtered set the operator chose to see.
     *
     * - **Source denylist:** drop items whose source is in
     *   [hiddenSources] (matched case-insensitively; the blank-source
     *   "Unknown source" bucket is hidden iff that label is in the set).
     *   A denylist means a newly-added source shows by default.
     * - **Recency window:** when [recency] is bounded, drop items older
     *   than its window. Items with no parseable timestamp are kept under
     *   `All` and dropped under a bounded window (we can't prove recent).
     *
     * Operates only on already-fetched inert plain text — no fetch, no
     * web, no new surface (A1 holds). Pure + unit-tested.
     */
    fun applyFilters(
        items: List<FeedItem>,
        hiddenSources: Set<String>,
        recency: FeedRecency,
        now: Long,
    ): List<FeedItem> {
        val hiddenLower = hiddenSources.map { it.lowercase() }.toSet()
        val maxAge = recency.maxAgeMs
        return items.filter { item ->
            val sourceKey = item.source.ifBlank { UNATTRIBUTED_KEY }.lowercase()
            if (sourceKey in hiddenLower) return@filter false
            if (maxAge == null) return@filter true
            val ts = item.itemTimestampMs() ?: return@filter false  // no timestamp → not provably recent
            (now - ts) <= maxAge
        }
    }

    /**
     * The distinct source labels present in [items], in the same
     * alphabetical (case-insensitive) order the sections render — the
     * list the source-filter UI offers as toggles. Blank sources collapse
     * to the [UNATTRIBUTED_KEY] bucket label.
     */
    fun distinctSources(items: List<FeedItem>): List<String> =
        items.map { it.source.ifBlank { UNATTRIBUTED_KEY } }
            .distinct()
            .sortedBy { it.lowercase() }

    /**
     * Build the entry list.
     *
     * @param items flat item list, in whatever order the helper returned
     *   (typically newest-first across all sources).
     * @param now current wall-clock time, milliseconds — used to age
     *   per-section freshness. Injected so tests can pin exact values.
     * @param sectionStaleAfterMs threshold beyond which a section's
     *   newest item is "warm" (older but not yet "not updating").
     *   Default 2 hours — feeds usually refresh several times within
     *   that window.
     * @param sectionDeadAfterMs threshold beyond which a section is
     *   "not updating" (the helper's poller may have failed for this
     *   source or the source itself stopped publishing). Default 12
     *   hours — past this, the freshness chip turns honest about it.
     */
    fun build(
        items: List<FeedItem>,
        now: Long,
        sectionStaleAfterMs: Long = 2 * 60 * 60 * 1000L,
        sectionDeadAfterMs: Long = 12 * 60 * 60 * 1000L,
    ): List<FeedListEntry> {
        if (items.isEmpty()) return emptyList()

        // Group by source. Use a LinkedHashMap so insertion order is
        // preserved (we re-sort to alphabetical below; this keeps the
        // intermediate state stable for any later iteration).
        val bySource = LinkedHashMap<String, MutableList<FeedItem>>()
        for (item in items) {
            val key = item.source.ifBlank { UNATTRIBUTED_KEY }
            bySource.getOrPut(key) { mutableListOf() } += item
        }

        // Section order is alphabetical case-insensitive on the
        // displayed source name. Predictable / stable so the wall
        // doesn't reshuffle itself on every poll — the operator's
        // muscle memory survives.
        val orderedSources = bySource.keys.sortedBy { it.lowercase() }

        val out = ArrayList<FeedListEntry>(items.size + orderedSources.size)
        for (source in orderedSources) {
            val sectionItems = bySource.getValue(source).sortedByPublishedNewestFirst()
            val newestAge = sectionItems.firstOrNull()
                ?.itemTimestampMs()
                ?.let { now - it }
            out += FeedListEntry.Header(
                source = source,
                itemCount = sectionItems.size,
                freshness = classifyFreshness(
                    newestAgeMs = newestAge,
                    staleAfter = sectionStaleAfterMs,
                    deadAfter = sectionDeadAfterMs,
                ),
                newestAgeMs = newestAge,
            )
            out += sectionItems.map { FeedListEntry.Item(it) }
        }
        return out
    }

    /**
     * The flat item-only order, in the same sequence the visible list
     * presents items. This is the order [com.mymts.ui.nav.WallFocusModel]'s
     * `feedIndex` indexes into. Provided as a convenience for callers
     * that want to map between focus indices and `FeedItem`s.
     */
    fun flattenItems(entries: List<FeedListEntry>): List<FeedItem> =
        entries.mapNotNull { (it as? FeedListEntry.Item)?.item }

    /**
     * Given the entries list and the focus model's `feedIndex`, return
     * the entries-list index of the item that should be visually
     * highlighted. Used by the LazyColumn to scroll the right row into
     * view.
     *
     * Returns -1 if `feedIndex` is out of range — the caller should
     * treat that as "no focused row" rather than crash.
     */
    fun entriesIndexForFocus(entries: List<FeedListEntry>, feedIndex: Int): Int {
        if (feedIndex < 0) return -1
        var seen = 0
        for ((i, entry) in entries.withIndex()) {
            if (entry is FeedListEntry.Item) {
                if (seen == feedIndex) return i
                seen++
            }
        }
        return -1
    }

    private fun List<FeedItem>.sortedByPublishedNewestFirst(): List<FeedItem> {
        // Use published time when present; fall back to fetched time;
        // items missing both sort to the end. ISO 8601 strings compare
        // lexicographically as time, so descending string compare is
        // descending time.
        return sortedWith(
            compareByDescending<FeedItem> { it.itemTimestampIso() ?: "" }
                .thenByDescending { it.id },
        )
    }

    private fun classifyFreshness(
        newestAgeMs: Long?,
        staleAfter: Long,
        deadAfter: Long,
    ): SectionFreshness = when {
        newestAgeMs == null -> SectionFreshness.Unknown
        newestAgeMs < staleAfter -> SectionFreshness.Fresh
        newestAgeMs < deadAfter -> SectionFreshness.Warm
        else -> SectionFreshness.NotUpdating
    }

    private const val UNATTRIBUTED_KEY = "Unknown source"
}

/**
 * One row in the visible feed list. Sealed so the pane render's
 * `when` is exhaustive — a future entry type (e.g. an inline divider
 * separating today's items from yesterday's) is impossible to forget
 * to render.
 */
sealed class FeedListEntry {
    data class Header(
        val source: String,
        val itemCount: Int,
        val freshness: SectionFreshness,
        /** Age in ms of the section's newest item, or null if unknown. */
        val newestAgeMs: Long?,
    ) : FeedListEntry()

    data class Item(val item: FeedItem) : FeedListEntry()
}

/**
 * Per-section freshness signal — the C3 "honest staleness" boundary
 * applied per source rather than once for the whole feed.
 *
 * The thresholds for [Warm] and [NotUpdating] are
 * [FeedListBuilder.build] parameters with sensible defaults; tests
 * pin classification at each boundary.
 */
enum class SectionFreshness {
    /** Newest item is recent enough to be trusted as current. */
    Fresh,

    /** Newest item is aging — operator-visible but not alarming yet. */
    Warm,

    /** Newest item is old enough to surface "not updating" honestly. */
    NotUpdating,

    /** No items in the section — should not happen for a real source. */
    Unknown,
}

/**
 * Best-effort timestamp string for [FeedItem]. Prefer publisher time;
 * fall back to helper fetch time. Null if both absent. ISO 8601
 * strings compare lexicographically as time, which is why we keep
 * them as strings here.
 */
internal fun FeedItem.itemTimestampIso(): String? =
    publishedAtIso?.takeIf { it.isNotBlank() }
        ?: fetchedAtIso?.takeIf { it.isNotBlank() }

/**
 * Best-effort millisecond timestamp for [FeedItem]. Parses the ISO
 * string with [java.time.Instant.parse] — returns null on parse
 * failure so the feed never crashes on a malformed timestamp.
 */
internal fun FeedItem.itemTimestampMs(): Long? = try {
    itemTimestampIso()?.let { java.time.Instant.parse(it).toEpochMilli() }
} catch (e: Exception) {
    null
}
