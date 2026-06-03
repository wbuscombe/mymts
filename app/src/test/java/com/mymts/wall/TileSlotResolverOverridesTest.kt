package com.mymts.wall

import com.mymts.data.helper.Channel
import com.mymts.ui.wall.TileSlotResolver
import com.mymts.ui.wall.TileSlotResolver.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 5: the resolver now accepts an `overrides` map (slotIndex →
 * slug) carried in from [com.mymts.data.lineup.LineupStore]. These
 * tests pin the override semantics — operator-pinned slots, honest
 * offline rendering when the assigned channel isn't live, graceful
 * fallback when the saved slug vanishes from the helper.
 */
class TileSlotResolverOverridesTest {

    private fun live(slug: String) = Channel(
        slug = slug,
        label = slug.uppercase(),
        kind = "hls",
        currentUrl = "https://example.test/$slug.m3u8",
        status = Channel.Status.LIVE,
        lastSuccessAt = "2026-06-03T00:00:00Z",
        lastError = null,
        errorCount = 0,
    )

    private fun offline(slug: String) = Channel(
        slug = slug,
        label = slug.uppercase(),
        kind = "hls",
        currentUrl = null,
        status = Channel.Status.UNAVAILABLE,
        lastSuccessAt = null,
        lastError = "fetch:dns_failure",
        errorCount = 5,
    )

    @Test fun `override to a live channel produces Playing slot for that index`() {
        val all = listOf(live("a"), live("b"), live("c"))
        val playable = all
        val slots = TileSlotResolver.resolve(
            tileCount = 2,
            defaultChannels = playable,
            allChannels = all,
            overrides = mapOf(0 to "c"),
        )
        val s0 = slots[0]
        assertTrue("slot 0 should be Playing(c)", s0 is Slot.Playing)
        assertEquals("c", (s0 as Slot.Playing).channel.slug)
        assertEquals("slot-0/c", s0.id)
    }

    @Test fun `override to an offline channel produces Offline slot, labelled`() {
        val all = listOf(live("a"), offline("cnn"))
        val playable = listOf(live("a"))
        val slots = TileSlotResolver.resolve(
            tileCount = 2,
            defaultChannels = playable,
            allChannels = all,
            overrides = mapOf(0 to "cnn"),
        )
        val s0 = slots[0]
        assertTrue("slot 0 must surface the operator's pick honestly as Offline", s0 is Slot.Offline)
        assertEquals("cnn", (s0 as Slot.Offline).channel.slug)
        assertEquals("CNN", s0.channel.label)
        assertEquals("slot-0/offline-cnn", s0.id)
    }

    @Test fun `override to a slug not in the helper set falls through to default cycler`() {
        val all = listOf(live("a"), live("b"))
        val playable = all
        // Saved slug "ghost" no longer exists in helper's set — fall through.
        val slots = TileSlotResolver.resolve(
            tileCount = 2,
            defaultChannels = playable,
            allChannels = all,
            overrides = mapOf(0 to "ghost"),
        )
        val s0 = slots[0]
        // Default cycler fills slot 0 with the first available channel
        // (a). The stale "ghost" pick is silently dropped — the operator
        // never sees a label for a channel that's gone.
        assertTrue("slot 0 falls through to default cycler", s0 is Slot.Playing)
        assertEquals("a", (s0 as Slot.Playing).channel.slug)
    }

    @Test fun `unpinned slots cycle the default channels, skipping operator-pinned slugs`() {
        // The operator pinned slot 0 to "b"; the default cycler picks
        // for slot 1 should not also use "b" — otherwise the wall ends
        // up showing "b" twice when there's another playable channel.
        val all = listOf(live("a"), live("b"))
        val playable = listOf(live("a"), live("b"))
        val slots = TileSlotResolver.resolve(
            tileCount = 2,
            defaultChannels = playable,
            allChannels = all,
            overrides = mapOf(0 to "b"),
        )
        assertEquals("b", (slots[0] as Slot.Playing).channel.slug)
        assertEquals(
            "slot 1 must NOT cycle to b — it's already pinned to slot 0",
            "a",
            (slots[1] as Slot.Playing).channel.slug,
        )
    }

    @Test fun `every slot pinned and helper has no others — unpinned tail is empty, not duplicated`() {
        val all = listOf(live("a"))
        // Slot 0 pinned to a; slot 1 has no override and no remaining
        // default after filtering pinned slugs.
        val slots = TileSlotResolver.resolve(
            tileCount = 2,
            defaultChannels = all,
            allChannels = all,
            overrides = mapOf(0 to "a"),
        )
        assertEquals("a", (slots[0] as Slot.Playing).channel.slug)
        assertTrue("slot 1 honestly empty rather than duplicating a", slots[1] is Slot.Empty)
    }

    @Test fun `no overrides — behaves exactly like the legacy cycler`() {
        val all = listOf(live("a"), live("b"))
        val slots = TileSlotResolver.resolve(
            tileCount = 4,
            defaultChannels = all,
        )
        assertEquals("a", (slots[0] as Slot.Playing).channel.slug)
        assertEquals("b", (slots[1] as Slot.Playing).channel.slug)
        assertEquals("a", (slots[2] as Slot.Playing).channel.slug)
        assertEquals("b", (slots[3] as Slot.Playing).channel.slug)
    }

    @Test fun `Offline slot carries no spec — only a Channel for the label`() {
        val all = listOf(offline("cnn"))
        val slots = TileSlotResolver.resolve(
            tileCount = 1,
            defaultChannels = emptyList(),
            allChannels = all,
            overrides = mapOf(0 to "cnn"),
        )
        val s = slots.single()
        assertTrue(s is Slot.Offline)
        // Offline is intentionally specless — it must not be paired
        // with a player, so BoundTile.init's `require` cannot misfire.
        // (Verified at the type-system level: Slot.Offline has no
        //  `spec` property to begin with.)
        assertNull(
            "Offline slot id encodes the offline-channel slug, never empty",
            slots.singleOrNull()?.let { it.id }?.takeIf { it.isBlank() },
        )
        assertEquals("slot-0/offline-cnn", s.id)
    }
}
