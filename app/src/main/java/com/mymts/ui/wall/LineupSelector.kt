package com.mymts.ui.wall

import com.mymts.data.helper.Channel

/**
 * Picks which playable channels fill the wall's N tile slots in what
 * order.
 *
 * Stage 3 polish: the operator has a preferred lineup of 4, but not
 * every channel resolves on every network. The selector walks the
 * preferred list first, then a fallback list, and finally any
 * remaining playable channels — taking at most [maxCount]. Slots that
 * can't be filled are left to the tile cycler / OFFLINE rendering
 * (C2 — never fake a tile).
 *
 * The selector is **pure** — it operates on whatever channels the
 * helper currently reports playable. Slugs that no longer resolve
 * vanish naturally on the next channels poll; slugs that come back
 * online re-enter the lineup. No state, no persistence.
 */
class LineupSelector(
    private val preferredSlugs: List<String>,
    private val fallbackSlugs: List<String>,
    private val maxCount: Int,
    private val denySlugs: Set<String> = emptySet(),
    // When false (an "exact" wall preset), fill ONLY with the preferred slugs — no
    // topping up with other playable channels, so a curated preset stays curated.
    // Default true preserves the wall's existing default behavior (no regression).
    private val topUp: Boolean = true,
) {
    init {
        require(maxCount >= 0) { "maxCount must be >= 0 (was $maxCount)" }
    }

    operator fun invoke(playable: List<Channel>): List<Channel> {
        if (maxCount == 0 || playable.isEmpty()) return emptyList()

        // Filter out denied slugs at the source — they can't sneak back in
        // via the "rest" tier later in this function. Radar WIDGET sources are
        // excluded too: since the unified registry (2026-07) lists radar to the
        // native picker, `playable` now includes the weather-radar pseudo-channels
        // (they report status=live), but radar is an EXPLICIT per-cell pick — it must
        // never be auto-swept into a default/top-up video slot. Parity with the web
        // client's isRadarSlug exclusion. An operator still assigns it from the picker.
        val allowed = playable.filterNot { it.slug in denySlugs || Channel.isRadarSlug(it.slug) }
        if (allowed.isEmpty()) return emptyList()

        val bySlug = allowed.associateBy { it.slug }
        val picked = LinkedHashMap<String, Channel>(maxCount)

        fun tryAdd(slug: String) {
            if (picked.size >= maxCount) return
            if (slug in picked) return
            if (slug in denySlugs) return
            bySlug[slug]?.let { picked[slug] = it }
        }

        preferredSlugs.forEach(::tryAdd)
        fallbackSlugs.forEach(::tryAdd)
        // Top up with any remaining (allowed) playable channels we haven't picked —
        // unless this is an "exact" preset, which fills ONLY with its own slugs.
        if (topUp) allowed.forEach { tryAdd(it.slug) }

        return picked.values.toList()
    }

    companion object {
        /**
         * Operator's preferred lineup. The first four fill the default 2×2
         * (slot order is row-major): **TL LiveNOW from FOX (live US news), TR
         * Fox Weather (national weather), BL BBC News (global), BR CBS Sports
         * HQ (sports)** — the operator's chosen default set (2026-06-11). The
         * rest follow for when the grid grows past 4. Explicit per-slot
         * `overrides` (LineupStore) still win, so this only sets the default
         * where the operator hasn't customized; everything stays available in
         * the picker for manual assignment.
         */
        val PREFERRED: List<String> = listOf(
            "livenow-fox",    // TL — Fox live news
            "fox-weather",    // TR — national weather
            "bbc-news",       // BL — global news
            "cbs-sports-hq",  // BR — sports
            "bloomberg-tv",
            "cnn",
        )

        /**
         * Fallback list walked in order whenever a preferred channel
         * fails to resolve.
         *
         * **NASA TV is NOT in the fallback list** even though it
         * resolves in the helper. Its master HLS manifest passes the
         * prober's `#EXTM3U` check, but the variant playlist returns
         * HTTP non-2xx for ExoPlayer and the tile settles `DEAD`. The
         * **ISS live feed** takes its place — separate stream, separate
         * variant chain, generally reliable. NASA TV stays in the seed
         * (so the operator can wire it in once the prober is deepened
         * to variant-fetch — already in BACKLOG) but does not occupy a
         * default slot while it's only-master-OK.
         */
        val FALLBACK: List<String> = listOf(
            "c-span",
            "iss-feed",
            "white-house-tv",
            "newsmax",
            "cnn-international",
        )

        /**
         * Slugs that the helper may report as `live`, but which must
         * not occupy a default tile slot. NASA TV's master HLS manifest
         * passes the prober's check yet its variant playlist returns
         * HTTP non-2xx for ExoPlayer — the tile would honestly settle
         * `DEAD`, but a default slot showing an `OFFLINE` panel
         * indefinitely is worse than the slot simply not being there.
         * The slug stays in the seed (so the operator can re-enable it
         * once the prober is deepened to variant-fetch — already in
         * BACKLOG); the wall's default lineup just doesn't pick it.
         */
        val DENY: Set<String> = setOf("nasa-tv")

        /** Convenience factory matching the wall's default tile count. */
        fun forWall(maxCount: Int): LineupSelector =
            LineupSelector(
                preferredSlugs = PREFERRED,
                fallbackSlugs = FALLBACK,
                maxCount = maxCount,
                denySlugs = DENY,
            )

        /**
         * Lineup for an **exact** wall preset: the preset's [slugs] in order,
         * resolved against the helper's FULL channel set ([allChannels], live OR
         * offline) — capped at [maxCount].
         *
         * Unlike the default wall (and [forWall]), this is **not** filtered to
         * playable and **does not** apply [DENY]: an exact preset is the
         * operator's explicit selection, so a listed channel that's currently
         * down stays in its slot and renders as an honest C2 OFFLINE panel
         * (`TileSlotResolver.Slot.Offline`) rather than vanishing — that's why
         * e.g. NASA TV is *fine* in the Space preset (it's a selection, not a
         * liveness claim). Slugs that match no channel at all are dropped (no
         * fake tiles); duplicates collapse to first occurrence.
         */
        fun exactLineup(
            slugs: List<String>,
            allChannels: List<Channel>,
            maxCount: Int,
        ): List<Channel> {
            if (maxCount <= 0) return emptyList()
            val bySlug = allChannels.associateBy { it.slug }
            val picked = LinkedHashMap<String, Channel>(maxCount)
            for (slug in slugs) {
                if (picked.size >= maxCount) break
                if (slug in picked) continue
                bySlug[slug]?.let { picked[slug] = it }
            }
            return picked.values.toList()
        }
    }
}
