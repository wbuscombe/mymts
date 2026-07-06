package com.mymts.ui.wall

import com.mymts.data.helper.Channel
import com.mymts.player.StreamSpec

/**
 * Resolves the wall's N tile slots from the helper's channel set, the
 * operator's saved per-slot overrides, and the default (preferred →
 * fallback → rest) cycler order.
 *
 * Single source of truth: the slot list this resolver produces drives
 * **both** the video grid (`VideoGrid` binds each slot to a player) and
 * the menu (`SlotRow` shows the channel for each slot). The menu and the
 * wall can never disagree about which channel is in which slot because
 * they read the same list.
 *
 * **Stage 5 reality recorded in `docs/findings/04-stage-5-menu.md`:**
 * the operator may assign a channel that's currently *not* live. The
 * wall renders an honest C2 OFFLINE panel for that slot, **labelled
 * with the channel's name** so the operator sees what's planned vs.
 * an unassigned tile. That distinction is the [Slot.Offline] variant
 * below.
 */
object TileSlotResolver {

    sealed class Slot {
        abstract val index: Int
        abstract val id: String

        /** A slot with a currently-playable channel — gets a real player. */
        data class Playing(
            override val index: Int,
            val channel: Channel,
            val spec: StreamSpec,
        ) : Slot() {
            override val id: String get() = "slot-$index/${channel.slug}"
        }

        /**
         * A slot whose operator-chosen channel exists in the helper's
         * channel set but is currently *not* live (no `current_url`).
         * Renders the C2 honest panel **with the channel's label**, no
         * player, no surface. When the helper marks the channel live
         * again, the next poll re-resolves this slot to [Playing].
         */
        data class Offline(
            override val index: Int,
            val channel: Channel,
        ) : Slot() {
            override val id: String get() = "slot-$index/offline-${channel.slug}"
        }

        /**
         * A slot holding a non-video WIDGET source — currently an NWS weather-radar
         * loop (unified registry, 2026-07). Renders an animated image (Coil), NOT an
         * ExoPlayer surface, so it is deliberately NOT a [Playing] slot: the video
         * grid's player manager skips it and `WallTile` routes it to the radar tile.
         * [imageUrl] is the ABSOLUTE helper URL (the channel's relative `current_url`
         * proxy path resolved against the helper base) the tile loads as its image.
         * No audio, no captions — the per-tile toggles are hidden for it.
         */
        data class Radar(
            override val index: Int,
            val channel: Channel,
            val imageUrl: String,
        ) : Slot() {
            override val id: String get() = "slot-$index/radar-${channel.slug}"
        }

        /**
         * A genuinely empty slot — no operator assignment and no
         * default available. Renders a blank C2 tile with no label.
         */
        data class Empty(override val index: Int) : Slot() {
            override val id: String get() = "slot-$index/empty"
        }
    }

    /**
     * Build N slots.
     *
     * @param tileCount how many tiles the wall has — must be ≥ 0.
     * @param defaultChannels the cycler input — the helper's playable
     *     channels in the `LineupSelector` order (preferred → fallback
     *     → rest, with deny-listed slugs already removed). Used to fill
     *     any slot the operator hasn't explicitly assigned.
     * @param allChannels the helper's full channel set (live + offline).
     *     The override lookup reads this so an operator-assigned slot
     *     can resolve to [Slot.Offline] when its channel isn't currently
     *     live, instead of silently falling back to the default.
     * @param overrides slot-index → channel slug. The operator's saved
     *     lineup, persisted by [com.mymts.data.lineup.LineupStore]. A
     *     slug that doesn't match any channel in [allChannels] falls
     *     through to the default cycler for that slot — never crashes.
     */
    fun resolve(
        tileCount: Int,
        defaultChannels: List<Channel>,
        allChannels: List<Channel> = defaultChannels,
        overrides: Map<Int, String> = emptyMap(),
        helperBaseUrl: String = "",
    ): List<Slot> {
        require(tileCount >= 0) { "tileCount must be >= 0 (was $tileCount)" }
        if (tileCount == 0) return emptyList()

        val byOverrideSlug = overrides.values.toSet()
        val allBySlug = allChannels.associateBy { it.slug }

        return (0 until tileCount).map { i ->
            val overrideSlug = overrides[i]
            val overrideChannel = overrideSlug?.let { allBySlug[it] }

            when {
                overrideChannel != null -> slotFromChannel(i, overrideChannel, helperBaseUrl)
                defaultChannels.isEmpty() -> Slot.Empty(i)
                else -> {
                    // Cycle the default list, skipping any channel the
                    // operator has explicitly pinned to another slot —
                    // an operator-assigned channel should not also fill
                    // an unassigned slot through the cycler.
                    val pool = defaultChannels.filterNot { it.slug in byOverrideSlug }
                    if (pool.isEmpty()) Slot.Empty(i)
                    else slotFromChannel(i, pool[i % pool.size], helperBaseUrl)
                }
            }
        }
    }

    private fun slotFromChannel(index: Int, channel: Channel, helperBaseUrl: String): Slot = when {
        // A WIDGET source (radar): render an animated image, not an ExoPlayer stream.
        // Its `current_url` is a RELATIVE helper proxy path (e.g. /api/weather/radar/kilx),
        // not an http(s) stream URL, so it must NOT go through the StreamSpec path (which
        // requires http(s)). Resolve it against the helper base into an absolute image URL.
        channel.isWidget ->
            if (channel.status == Channel.Status.LIVE && !channel.currentUrl.isNullOrBlank()) {
                Slot.Radar(
                    index = index,
                    channel = channel,
                    imageUrl = absolutizeRadarUrl(helperBaseUrl, channel.currentUrl),
                )
            } else {
                Slot.Offline(index = index, channel = channel)
            }

        channel.isPlayable && channel.currentUrl != null ->
            Slot.Playing(
                index = index,
                channel = channel,
                spec = StreamSpec(
                    id = "slot-$index-${channel.slug}",
                    label = channel.label,
                    url = channel.currentUrl,
                ),
            )

        else -> Slot.Offline(index = index, channel = channel)
    }

    /**
     * Resolve a radar channel's proxy path into an absolute URL the image loader
     * can fetch. The helper serves radar's `current_url` as a same-origin RELATIVE
     * path (`/api/weather/radar/<region>`) — the web client loads it same-origin, but
     * the native app must join it onto the helper base it fetched `/api/channels` from.
     * An already-absolute url passes through; a blank base leaves the path unchanged
     * (the tile then honestly fails to load rather than crashing — production always
     * supplies the base). Pure — unit-tested.
     */
    internal fun absolutizeRadarUrl(baseUrl: String, path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        baseUrl.isBlank() -> path
        else -> baseUrl.trimEnd('/') + if (path.startsWith("/")) path else "/$path"
    }
}
