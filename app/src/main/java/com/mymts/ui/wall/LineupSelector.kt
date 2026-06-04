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
) {
    init {
        require(maxCount >= 0) { "maxCount must be >= 0 (was $maxCount)" }
    }

    operator fun invoke(playable: List<Channel>): List<Channel> {
        if (maxCount == 0 || playable.isEmpty()) return emptyList()

        // Filter out denied slugs at the source — they can't sneak back in
        // via the "rest" tier later in this function.
        val allowed = playable.filterNot { it.slug in denySlugs }
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
        // Top up with any remaining (allowed) playable channels we haven't picked.
        allowed.forEach { tryAdd(it.slug) }

        return picked.values.toList()
    }

    companion object {
        /**
         * Operator's preferred lineup for the 2×2 default.
         *
         * Updated 2026-06-04 — Bloomberg TV + CNBC at the top, then the
         * prior preferred 4 (CBS Sports HQ, BBC News, CNN, LiveNOW from
         * FOX), then the fallback list, then the rest of the helper's
         * playable set. DW News English is no longer in the default
         * lineup but remains available in the menu picker for manual
         * assignment.
         */
        val PREFERRED: List<String> = listOf(
            "bloomberg-tv",
            "cnbc",
            "cbs-sports-hq",
            "bbc-news",
            "cnn",
            "livenow-fox",
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
    }
}
