package com.mymts.ui.wall

import com.mymts.player.StreamPlayer

/**
 * A tile slot paired with the [StreamPlayer] responsible for **that
 * slot's** channel.
 *
 * **Honesty rule made structural:** the label, the channel identity,
 * and the playing stream all come from the same [TileSlotResolver.Slot.Playing]
 * — and the [require] check below makes it impossible to construct a
 * [BoundTile] whose player belongs to a different slot. A tile labelled
 * "CBS Sports HQ" *cannot* end up playing a different channel's stream
 * because the type system + the runtime check forbid it.
 *
 * This is the channel-identity sibling of Trust Bar **C3** (no silent
 * staleness): a label that doesn't match its stream is a *labelling lie*
 * — the same class of dishonesty as a LIVE badge over a frozen surface,
 * at the identity layer instead of the liveness layer.
 *
 * The pairing happens once, at the VideoGrid layer, when the manager
 * and slot list are both known. Downstream — `WallTile` — receives the
 * already-bound pair and never does its own player lookup.
 */
internal data class BoundTile(
    val slot: TileSlotResolver.Slot,
    val player: StreamPlayer?,
) {
    init {
        if (slot is TileSlotResolver.Slot.Playing && player != null) {
            require(player.specId == slot.spec.id) {
                "tile binding desync: slot=${slot.id} (spec ${slot.spec.id}) " +
                    "would be drawn with player.specId=${player.specId}"
            }
        }
    }

    /** The Compose `key()` identity for this tile — slot.id alone is enough. */
    val key: String get() = slot.id
}

/**
 * Pair each slot with the player whose `spec.id` matches that slot's
 * spec — by **identity match**, never by index.
 *
 * If the manager doesn't have a matching player for a Playing slot
 * (e.g. mid-recomposition during a snapshot update), the pair carries
 * `player = null` and the tile renders the same C2 dead panel it
 * would for a settled-DEAD player — preferable to drawing the wrong
 * channel's video under this slot's label.
 */
internal fun bindTiles(
    slots: List<TileSlotResolver.Slot>,
    findPlayer: (String) -> StreamPlayer?,
): List<BoundTile> = slots.map { slot ->
    when (slot) {
        is TileSlotResolver.Slot.Empty -> BoundTile(slot = slot, player = null)
        is TileSlotResolver.Slot.Playing -> {
            val candidate = findPlayer(slot.spec.id)
            // The init {} check inside BoundTile guarantees the player's
            // specId equals slot.spec.id whenever non-null. If lookup
            // returns a stale player from a prior recomposition, we
            // simply don't pair it — better to render quiet than wrong.
            val safe = candidate?.takeIf { it.specId == slot.spec.id }
            BoundTile(slot = slot, player = safe)
        }
    }
}
