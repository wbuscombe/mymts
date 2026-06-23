package com.mymts.ui.wall.feed

/**
 * Feed-source GENRE taxonomy (news-genre-groups chapter, Part E).
 *
 * Groups the feed's RSS source LABELS into the SAME genre names the channel
 * picker ([com.mymts.ui.menu.ChannelCategory]) and the helper's
 * `feeds/category.py` use — US News / Global News / Business / Sports — so the
 * native two-level feed-source filter reads identically to the web's grouped
 * filter and the picker's channel sections. One taxonomy, three surfaces.
 *
 * **Data-driven (the one-line rule):** adding a feed source later is a single
 * line in [BY_LABEL]. An UNMAPPED label falls through to [GENERAL] — a newly-
 * added source still groups (under General) and is never silently dropped,
 * exactly mirroring the helper's `source_category` default. There is **no feed
 * RSS source for Weather**, so the Weather genre simply never appears in the
 * grouped filter (empty genres are omitted by [sectioned]) — honest, not a dead
 * toggle.
 *
 * **Sports is special (the reconciliation seam):** the Sports genre's source
 * labels (NFL, NBA, …) ARE the leagues, so the Sports group's per-source
 * toggles route to the SHARED Sports-leagues pool (`hiddenLeagues`) rather than
 * the generic `hiddenSources` denylist — the same pool the ticker scores and
 * the standalone Sports-leagues filter use. The Sports *genre* toggle sits
 * ABOVE that pool: genre off ⇒ the whole Sports contribution leaves the feed,
 * regardless of any per-league setting. See [isSports] + [FeedListBuilder].
 *
 * Pure Kotlin (no Compose/Android) → unit-tested in `FeedGenresTest`, and used
 * by BOTH the pure [FeedListBuilder] genre filter and the `NewsFilterOverlay`
 * presentation, so the grouping the operator sees and the filtering the feed
 * applies can never disagree.
 */
object FeedGenres {
    const val US_NEWS = "US News"
    const val GLOBAL_NEWS = "Global News"
    const val BUSINESS = "Business"
    const val SPORTS = "Sports"

    /** The catch-all for an unmapped source — mirrors the helper's GENERAL
     *  default + [com.mymts.ui.menu.ChannelCategory.GENERAL]. Never hidden by
     *  omission; a future source groups here until it earns a [BY_LABEL] line. */
    const val GENERAL = "General"

    /**
     * Genre render order in the two-level filter. Weather is intentionally
     * absent — no feed RSS source maps to it (Weather is a *channel* category
     * only), and [sectioned] omits empty genres, so it never shows as a dead
     * toggle. General is last: the catch-all bucket for unmapped sources.
     */
    val ORDER: List<String> = listOf(US_NEWS, GLOBAL_NEWS, BUSINESS, SPORTS, GENERAL)

    // Source label (lowercased) -> genre. Mirrors
    // helper/src/mymts_helper/feeds/category.py + .../feeds/seed.json. Keys are
    // lowercased so the lookup is case-insensitive (a feed item's `source`
    // echoes the seed label, but we normalize defensively). Adding a source =
    // one line here.
    private val BY_LABEL: Map<String, String> = mapOf(
        // Global News — international desks.
        "bbc world" to GLOBAL_NEWS,
        "al jazeera" to GLOBAL_NEWS,
        "guardian world" to GLOBAL_NEWS,
        "npr world" to GLOBAL_NEWS,
        // US News — US outlets, public broadcasters, opinion/policy.
        "pbs newshour" to US_NEWS,
        "christian science monitor" to US_NEWS,
        "cbs news" to US_NEWS,
        "nbc news" to US_NEWS,
        "politico" to US_NEWS,
        "the dispatch" to US_NEWS,
        "national review" to US_NEWS,
        "reason" to US_NEWS,
        // Business / markets.
        "bloomberg markets" to BUSINESS,
        // Sports — ESPN league desks. These labels ARE the leagues, so the
        // Sports genre's per-source toggles share the `hiddenLeagues` pool with
        // the ticker + the Sports-leagues filter (see [isSports]). Kept in sync
        // with WallScreen.CURATED_LEAGUES' eight team leagues (the bespoke
        // PGA/UFC/Tennis/F1 cards have no RSS feed source — ticker-only).
        "nfl" to SPORTS,
        "ncaaf" to SPORTS,
        "ufl" to SPORTS,
        "nba" to SPORTS,
        "wnba" to SPORTS,
        "ncaab" to SPORTS,
        "mlb" to SPORTS,
        "nhl" to SPORTS,
    )

    /** The genre a feed source label belongs to ([GENERAL] if unmapped). */
    fun genreOf(sourceLabel: String): String =
        BY_LABEL[sourceLabel.trim().lowercase()] ?: GENERAL

    /**
     * True iff [genre] is the Sports genre, whose per-source toggles route to
     * the shared Sports-leagues pool (`hiddenLeagues`) rather than the generic
     * `hiddenSources` denylist — the reconciliation seam (Part E §4).
     */
    fun isSports(genre: String): Boolean = genre == SPORTS

    /**
     * Group [sources] (distinct feed source labels) into `(genre, its sources)`
     * pairs in [ORDER], dropping any genre with no present source (so empty
     * genres — notably Weather — never render). Within a genre the sources are
     * sorted case-insensitively for a stable, glanceable list. Pure →
     * unit-tested; drives the two-level overlay's group layout.
     */
    fun sectioned(sources: List<String>): List<Pair<String, List<String>>> {
        val byGenre = sources.groupBy { genreOf(it) }
        return ORDER.mapNotNull { genre ->
            byGenre[genre]?.sortedBy { it.lowercase() }?.let { genre to it }
        }
    }
}
