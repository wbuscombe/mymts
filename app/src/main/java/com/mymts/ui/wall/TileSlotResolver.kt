package com.mymts.ui.wall

import com.mymts.data.helper.Channel
import com.mymts.player.StreamSpec

/**
 * Resolves the wall's N tile slots from however many distinct live
 * channels the helper currently reports.
 *
 * Stage 3 reality (recorded in `docs/findings/03-stage-3-wall.md`):
 * the helper currently resolves only 2 channels live on this network
 * path; the wall must adapt — to 0, 1, 2, 4, or more later — without
 * code changes. The resolver cycles the available list to fill N slots:
 *
 *   0 channels, N=4 → 4 empty slots
 *   1 channel,  N=4 → [c0, c0, c0, c0]
 *   2 channels, N=4 → [c0, c1, c0, c1]      (the v2 long-soak shape)
 *   4 channels, N=4 → [c0, c1, c2, c3]
 *   5 channels, N=4 → [c0, c1, c2, c3]      (extras unused; respect ceiling)
 *
 * Each slot carries a stable [Slot.id] (e.g. `slot-0/redbull-tv`) so
 * Compose can `key()` on it and ExoPlayer instances are reused across
 * recompositions when the same channel re-occupies the same slot.
 */
object TileSlotResolver {

    sealed class Slot {
        abstract val index: Int
        abstract val id: String

        /** A playable slot — has a StreamSpec the player can mount. */
        data class Playing(
            override val index: Int,
            val channel: Channel,
            val spec: StreamSpec,
        ) : Slot() {
            override val id: String get() = "slot-$index/${channel.slug}"
        }

        /** An empty slot — no channel available. Shows a quiet, honest gap (C2). */
        data class Empty(override val index: Int) : Slot() {
            override val id: String get() = "slot-$index/empty"
        }
    }

    /**
     * Build N slots from the given live channels.
     *
     * @param tileCount how many tiles the wall has (config-driven —
     *     `BuildConfig.DEFAULT_MAX_TILES`). Must be ≥ 0.
     * @param liveChannels the helper's currently-playable channels.
     *     Only channels with [Channel.isPlayable] should be passed in;
     *     the resolver does not double-check.
     */
    fun resolve(tileCount: Int, liveChannels: List<Channel>): List<Slot> {
        require(tileCount >= 0) { "tileCount must be >= 0 (was $tileCount)" }
        if (tileCount == 0) return emptyList()
        if (liveChannels.isEmpty()) {
            return (0 until tileCount).map { Slot.Empty(it) }
        }
        return (0 until tileCount).map { i ->
            val channel = liveChannels[i % liveChannels.size]
            Slot.Playing(
                index = i,
                channel = channel,
                spec = StreamSpec(
                    id = "slot-$i-${channel.slug}",
                    label = channel.label,
                    url = channel.currentUrl
                        ?: error("isPlayable channel ${channel.slug} had null current_url"),
                ),
            )
        }
    }
}
