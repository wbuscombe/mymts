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
) {
    init {
        require(maxCount >= 0) { "maxCount must be >= 0 (was $maxCount)" }
    }

    operator fun invoke(playable: List<Channel>): List<Channel> {
        if (maxCount == 0 || playable.isEmpty()) return emptyList()

        val bySlug = playable.associateBy { it.slug }
        val picked = LinkedHashMap<String, Channel>(maxCount)

        fun tryAdd(slug: String) {
            if (picked.size >= maxCount) return
            if (slug in picked) return
            bySlug[slug]?.let { picked[slug] = it }
        }

        preferredSlugs.forEach(::tryAdd)
        fallbackSlugs.forEach(::tryAdd)
        // Top up with any remaining playable channels we haven't picked.
        playable.forEach { tryAdd(it.slug) }

        return picked.values.toList()
    }

    companion object {
        /**
         * Operator's preferred lineup for the 2×2 default (Stage 3 polish).
         */
        val PREFERRED: List<String> = listOf(
            "cbs-sports-hq",
            "bbc-news",
            "cnn",
            "livenow-fox",
        )

        /**
         * Fallback list walked in order whenever a preferred channel
         * fails to resolve.
         */
        val FALLBACK: List<String> = listOf(
            "c-span",
            "nasa-tv",
            "white-house-tv",
            "newsmax",
            "cnn-international",
        )

        /** Convenience factory matching the wall's default tile count. */
        fun forWall(maxCount: Int): LineupSelector =
            LineupSelector(preferredSlugs = PREFERRED, fallbackSlugs = FALLBACK, maxCount = maxCount)
    }
}
