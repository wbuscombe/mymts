package com.mymts.ui.wall.feed

import com.mymts.data.helper.FeedItem
import com.mymts.data.settings.FeedRecency

/**
 * Pure transform from a raw `List<FeedItem>` into the wall's feed display
 * order: a single **agnostic, newest-first list across ALL sources**, with
 * the source shown next to each headline (the operator's decision,
 * 2026-06-07, after living with the per-source sectioned version — now
 * matching the reworked web client's `feedChronological` + `sourceLabel`).
 * There is no per-source grouping; the source label rides on each row.
 *
 * Plain Kotlin, no Compose / Android — unit-tested in `FeedListBuilderTest`
 * so a regression is visible without booting the UI.
 *
 * **Flat-index invariant:** the order of items in [build]'s output IS the
 * focus-navigation order. [com.mymts.ui.nav.WallFocusModel] treats the feed
 * zone as a single contiguous list indexed `0..count-1`; the agnostic list
 * means `feedIndex == list index` directly (no headers to skip), so the
 * navigation chapter's no-trap invariants hold unchanged — only the visible
 * layout changed (one river instead of sections).
 */
object FeedListBuilder {

    /**
     * Pure feed-filter step (feed-filtering chapter). Applied to the raw
     * items BEFORE [build] orders them — so the visible list, the source
     * label per row, and the focus flat-index all operate on exactly the
     * filtered set the operator chose to see.
     *
     * **Two-level precedence (news-genre-groups chapter, Part E §4).** The
     * filters compose top-down so the levels never conflict:
     *   1. **Genre denylist ([hiddenGenres], the master switch):** drop items
     *      whose [FeedGenres.genreOf] genre is switched off. A genre off hides
     *      ALL its sources REGARDLESS of any per-source state below — so the
     *      Sports genre off removes every league's news even if no league is in
     *      [hiddenLeagues]. A denylist ⇒ a newly-added genre shows by default.
     *   2. **Per-source denylist ([hiddenSources]):** within a SHOWN genre,
     *      drop NON-sports items whose source is hidden (case-insensitive; the
     *      blank-source "Unknown source" bucket is hidden iff that label is in
     *      the set).
     *   3. **Sports-leagues pool ([hiddenLeagues]):** the Sports genre's
     *      per-source level. Sports-news items (source IS a league) are gated by
     *      the SAME pool as the ticker scores + the standalone Sports-leagues
     *      filter — disable a league and both its scores and its news disappear,
     *      consistently. The genre toggle (level 1) sits above this pool.
     * - **Recency window:** when [recency] is bounded, drop items older than its
     *   window. Items with no parseable timestamp are kept under `All` and
     *   dropped under a bounded window (we can't prove recent).
     *
     * [hiddenGenres] is appended (defaulted to empty) so the pre-genre filter
     * call sites read as "no genre filter" unchanged; `now` stays the
     * positional clock so existing 5-arg callers keep compiling.
     *
     * Operates only on already-fetched inert plain text — no fetch, no
     * web, no new surface (A1 holds). Pure + unit-tested.
     */
    fun applyFilters(
        items: List<FeedItem>,
        hiddenSources: Set<String>,
        hiddenLeagues: Set<String>,
        recency: FeedRecency,
        now: Long,
        hiddenGenres: Set<String> = emptySet(),
    ): List<FeedItem> {
        val hiddenLower = hiddenSources.map { it.lowercase() }.toSet()
        val hiddenLeagueLower = hiddenLeagues.map { it.lowercase() }.toSet()
        val hiddenGenreLower = hiddenGenres.map { it.lowercase() }.toSet()
        val maxAge = recency.maxAgeMs
        val kept = items.filter { item ->
            val sourceKey = item.source.ifBlank { UNATTRIBUTED_KEY }.lowercase()
            // Level 1 — genre master switch: a genre switched off hides ALL its
            // sources, overriding any per-source state below.
            if (FeedGenres.genreOf(item.source).lowercase() in hiddenGenreLower) return@filter false
            // Level 2 — per-source denylist. This check is general (any source
            // in the set drops); the News overlay only ROUTES non-sports sources
            // here (sports sources go to the leagues pool, level 3), so in
            // practice this is the non-sports per-source level.
            if (sourceKey in hiddenLower) return@filter false
            // Level 3 — Sports-leagues pool (sports sources' per-source level),
            // shared with the ticker scores + the Sports-leagues filter.
            if (sourceKey in SPORTS_LEAGUES && sourceKey in hiddenLeagueLower) return@filter false
            if (maxAge == null) return@filter true
            val ts = item.itemTimestampMs() ?: return@filter false  // no timestamp → not provably recent
            (now - ts) <= maxAge
        }
        // Blend, don't dominate: cap sports-news to the newest [MAX_SPORTS_NEWS]
        // so a busy sports day can't flood the river. General news is uncapped;
        // [build] then interleaves both chronologically.
        val (sports, general) = kept.partition { it.source.lowercase() in SPORTS_LEAGUES }
        if (sports.size <= MAX_SPORTS_NEWS) return kept
        val cappedSports = sports.sortedByDescending { it.itemTimestampIso() ?: "" }.take(MAX_SPORTS_NEWS)
        return general + cappedSports
    }

    /**
     * The distinct source labels present in [items], alphabetical
     * (case-insensitive) — the list the source-filter UI offers as toggles.
     * Blank sources collapse to the [UNATTRIBUTED_KEY] bucket label.
     */
    fun distinctSources(items: List<FeedItem>): List<String> =
        items.map { it.source.ifBlank { UNATTRIBUTED_KEY } }
            .distinct()
            .sortedBy { it.lowercase() }

    /**
     * Order the feed for display: a single **newest-first** list across all
     * sources — published time preferred, fetched time fallback; items
     * missing both sort to the end. Ties among items with **distinct** ids
     * break newest-id-first; items that share an id (notably the id-less
     * population — all default to -1 in `HelperClient`) keep their stable
     * input order (Kotlin's `sortedWith` is stable). Either way the result
     * is deterministic. No grouping. The returned order IS the
     * focus-navigation order, so `feedIndex` indexes directly into it.
     *
     * ISO-8601 strings compare lexicographically as time, so a descending
     * string compare is a descending time sort.
     */
    fun build(items: List<FeedItem>): List<FeedItem> =
        items.sortedWith(
            compareByDescending<FeedItem> { it.itemTimestampIso() ?: "" }
                .thenByDescending { it.id },
        )

    /** The display source label for an item (blank → the Unknown bucket). */
    fun sourceLabel(item: FeedItem): String = item.source.ifBlank { UNATTRIBUTED_KEY }

    /**
     * A unique+stable key for a feed row in the LazyColumn. A LazyColumn
     * THROWS on a duplicate key, so this must never collide. A real helper
     * id (>= 0) is unique+stable; id-less items all default to -1
     * (`HelperClient`), so two cross-posted wire stories with the same
     * headline + no id would collide on a title hash — folding in the row
     * index makes the fallback unique (index is unique per the
     * deterministic ordered list). Pure → unit-testable.
     */
    fun rowKey(item: FeedItem, index: Int): Any =
        if (item.id >= 0) item.id else "idx:$index:${item.source}|${item.title}"

    private const val UNATTRIBUTED_KEY = "Unknown source"

    /**
     * The sports leagues whose ESPN-news feed items are gated by the
     * Sports-leagues pool (lowercased; kept in sync with the helper's
     * `feeds/seed.json` ESPN-news source labels + `WallScreen.CURATED_LEAGUES`).
     * A feed item whose source matches one of these is a sports-news item.
     */
    private val SPORTS_LEAGUES: Set<String> =
        setOf("nfl", "ncaaf", "ufl", "nba", "wnba", "ncaab", "mlb", "nhl")

    /** Cap on sports-news items so a busy day blends, not floods (tunable). */
    private const val MAX_SPORTS_NEWS = 14
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
