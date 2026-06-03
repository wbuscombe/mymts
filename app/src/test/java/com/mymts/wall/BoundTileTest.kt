package com.mymts.wall

import com.mymts.data.helper.Channel
import com.mymts.player.StreamPlayer
import com.mymts.player.StreamSpec
import com.mymts.ui.wall.BoundTile
import com.mymts.ui.wall.LineupSelector
import com.mymts.ui.wall.TileSlotResolver
import com.mymts.ui.wall.bindTiles
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression guard for the channel-identity honesty rule:
 * a tile's label and its playing stream MUST come from the same
 * resolved [Channel] — including after [LineupSelector] reorders
 * the lineup because preferred channels fail and fallbacks
 * backfill. The honesty rule is enforced by [BoundTile.init]; this
 * test verifies the enforcement is real, not theoretical.
 */
class BoundTileTest {

    private fun channelOf(slug: String, label: String, url: String) = Channel(
        slug = slug,
        label = label,
        kind = "hls",
        currentUrl = url,
        status = Channel.Status.LIVE,
        lastSuccessAt = "2026-06-03T12:00:00Z",
        lastError = null,
        errorCount = 0,
    )

    /** A pretend player whose `specId` returns the given fixed id. */
    private fun fakePlayer(specId: String): StreamPlayer = mockk(relaxed = true) {
        every { this@mockk.specId } returns specId
        every { this@mockk.state } returns MutableStateFlow(StreamPlayer.State.LIVE)
    }

    /**
     * The honesty scenario the operator described:
     *   - preferred = [a, b, c, d]; only `a` resolves.
     *   - fallback  = [e, f]; both resolve.
     *   - rest      = [g] also resolves.
     *   - The selector returns [a, e, f, g] for a 4-slot wall.
     *   - The resolver builds 4 slots, each backed by a distinct channel.
     *
     * The test then BINDS each slot with the player whose specId matches
     * that slot's spec — and asserts that for every tile, the visible
     * label and the playing player's URL come from the SAME channel.
     */
    @Test fun `label, channel, and player all come from one Channel after backfill`() {
        val a = channelOf("a", "ALabel", "https://hosts.test/a.m3u8")
        val e = channelOf("e", "ELabel", "https://hosts.test/e.m3u8")
        val f = channelOf("f", "FLabel", "https://hosts.test/f.m3u8")
        val g = channelOf("g", "GLabel", "https://hosts.test/g.m3u8")
        val playable = listOf(a, e, f, g)

        val selector = LineupSelector(
            preferredSlugs = listOf("a", "b", "c", "d"),
            fallbackSlugs = listOf("e", "f"),
            maxCount = 4,
        )
        val chosen = selector(playable)
        assertEquals(listOf("a", "e", "f", "g"), chosen.map { it.slug })

        val slots = TileSlotResolver.resolve(tileCount = 4, defaultChannels =chosen)
        val players = slots.filterIsInstance<TileSlotResolver.Slot.Playing>()
            .associate { it.spec.id to fakePlayer(it.spec.id) }

        val bound = bindTiles(slots) { specId -> players[specId] }

        assertEquals(4, bound.size)
        for ((i, b) in bound.withIndex()) {
            val slot = b.slot
            assertTrue(
                "expected Playing slot at index $i but got $slot",
                slot is TileSlotResolver.Slot.Playing,
            )
            slot as TileSlotResolver.Slot.Playing

            val channel = slot.channel
            // Label rendered to the user equals channel.label.
            assertEquals(channel.label, slot.spec.label)
            // Player paired with this tile carries the channel's URL.
            assertEquals("slot $i: paired player must match this slot's spec",
                slot.spec.id, b.player?.specId)
            // The channel.currentUrl IS what's behind the spec.
            assertEquals(channel.currentUrl, slot.spec.url)
        }
    }

    /**
     * The dangerous pairing — passing a player whose specId belongs to a
     * different slot — must throw at construction. This is the
     * structural guarantee that a labelling lie cannot ship.
     */
    @Test fun `binding the wrong player to a slot throws`() {
        val a = channelOf("a", "ALabel", "https://hosts.test/a.m3u8")
        val b = channelOf("b", "BLabel", "https://hosts.test/b.m3u8")
        val slots = TileSlotResolver.resolve(2, listOf(a, b))
        val playingA = slots[0] as TileSlotResolver.Slot.Playing
        val playingB = slots[1] as TileSlotResolver.Slot.Playing

        // Player for B paired with slot A — must fail at construction.
        try {
            BoundTile(slot = playingA, player = fakePlayer(playingB.spec.id))
            fail("expected IllegalArgumentException — binding desync")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("tile binding desync"))
        }
    }

    /**
     * Empty slots accept a null player — they render the C2 honest gap.
     * They must NOT accept a non-null player (no "ghost" pairing).
     */
    @Test fun `empty slot pairs with null player`() {
        val empty = TileSlotResolver.Slot.Empty(index = 0)
        val bound = BoundTile(slot = empty, player = null)
        assertNull(bound.player)
        assertEquals("slot-0/empty", bound.key)
    }

    /**
     * Lookup miss — the player isn't there for this slot's spec.id —
     * results in null pairing (the tile renders the same C2 dead panel
     * as a settled-DEAD player would). The wall never falls back to a
     * different player to "fill" the slot.
     */
    @Test fun `lookup miss leaves the tile with null player, not a wrong-channel player`() {
        val a = channelOf("a", "ALabel", "https://hosts.test/a.m3u8")
        val b = channelOf("b", "BLabel", "https://hosts.test/b.m3u8")
        val slots = TileSlotResolver.resolve(2, listOf(a, b))

        // Map only has player for slot B; slot A's player is missing.
        val playingB = slots[1] as TileSlotResolver.Slot.Playing
        val onlyB = mapOf(playingB.spec.id to fakePlayer(playingB.spec.id))

        val bound = bindTiles(slots) { specId -> onlyB[specId] }
        // Slot A gets null — NOT slot B's player.
        assertNull(
            "slot-A must not be paired with slot-B's player",
            bound[0].player,
        )
        // Slot B is paired correctly.
        assertSame(
            "slot-B's player must be the one whose specId matches",
            onlyB[playingB.spec.id],
            bound[1].player,
        )
    }

    /**
     * Stale player from a prior recomposition (specId no longer matches)
     * must not be paired with the new slot. The defensive `.takeIf` in
     * bindTiles is the second layer of the structural guarantee.
     */
    @Test fun `stale player whose specId mismatches is discarded, not drawn`() {
        val a = channelOf("a", "ALabel", "https://hosts.test/a.m3u8")
        val slots = TileSlotResolver.resolve(1, listOf(a))
        val playingA = slots.single() as TileSlotResolver.Slot.Playing

        // Map returns a player whose specId is "stale-from-earlier-recomp".
        val stale = fakePlayer("stale-from-earlier-recomp")
        val bound = bindTiles(slots) { _ -> stale }

        assertNull(
            "a stale player whose specId doesn't match this slot must not be paired",
            bound[0].player,
        )
    }
}
