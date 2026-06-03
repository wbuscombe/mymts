package com.mymts.wall

import com.mymts.data.helper.Channel
import com.mymts.ui.wall.TileSlotResolver
import com.mymts.ui.wall.TileSlotResolver.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TileSlotResolverTest {

    private fun playable(slug: String, url: String = "https://example.test/$slug.m3u8") = Channel(
        slug = slug,
        label = slug.uppercase(),
        kind = "hls",
        currentUrl = url,
        status = Channel.Status.LIVE,
        lastSuccessAt = "2026-06-02T00:00:00Z",
        lastError = null,
        errorCount = 0,
    )

    @Test fun `zero channels with N=4 yields four Empty slots`() {
        val slots = TileSlotResolver.resolve(tileCount = 4, defaultChannels =emptyList())
        assertEquals(4, slots.size)
        slots.forEachIndexed { i, s ->
            assertTrue("slot $i should be Empty but was $s", s is Slot.Empty)
            assertEquals(i, s.index)
        }
    }

    @Test fun `one channel with N=4 cycles the same channel four times`() {
        val a = playable("a")
        val slots = TileSlotResolver.resolve(4, listOf(a))
        assertEquals(4, slots.size)
        slots.forEach {
            assertTrue(it is Slot.Playing)
            assertEquals(a, (it as Slot.Playing).channel)
        }
    }

    @Test fun `two channels with N=4 cycle A B A B (the v2 long-soak shape)`() {
        val a = playable("a")
        val b = playable("b")
        val slots = TileSlotResolver.resolve(4, listOf(a, b))
        val channels = slots.map { (it as Slot.Playing).channel }
        assertEquals(listOf(a, b, a, b), channels)
    }

    @Test fun `four channels with N=4 yields each channel exactly once`() {
        val cs = listOf("a", "b", "c", "d").map { playable(it) }
        val slots = TileSlotResolver.resolve(4, cs)
        val channels = slots.map { (it as Slot.Playing).channel }
        assertEquals(cs, channels)
    }

    @Test fun `more channels than N=4 respects the ceiling and uses the first N`() {
        val cs = listOf("a", "b", "c", "d", "e").map { playable(it) }
        val slots = TileSlotResolver.resolve(4, cs)
        val channels = slots.map { (it as Slot.Playing).channel }
        assertEquals(cs.take(4), channels)
    }

    @Test fun `slot ids are stable and distinguish slot index from channel`() {
        val a = playable("a")
        val b = playable("b")
        val slots = TileSlotResolver.resolve(4, listOf(a, b)).filterIsInstance<Slot.Playing>()
        val ids = slots.map { it.id }
        assertEquals(listOf("slot-0/a", "slot-1/b", "slot-2/a", "slot-3/b"), ids)
        val specIds = slots.map { it.spec.id }
        assertEquals(listOf("slot-0-a", "slot-1-b", "slot-2-a", "slot-3-b"), specIds)
    }

    @Test fun `tileCount=0 yields empty list regardless of channels`() {
        val slots = TileSlotResolver.resolve(0, listOf(playable("a")))
        assertTrue(slots.isEmpty())
    }

    @Test fun `negative tileCount rejected`() {
        try {
            TileSlotResolver.resolve(-1, emptyList())
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("tileCount"))
        }
    }
}
