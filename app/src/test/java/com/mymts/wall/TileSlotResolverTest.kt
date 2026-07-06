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

    // ---- weather-radar WIDGET slots (unified registry, 2026-07) ----

    private fun radar(region: String, live: Boolean = true) = Channel(
        slug = "weather-radar-$region",
        label = "Radar $region",
        kind = Channel.KIND_WEATHER_RADAR,
        currentUrl = if (live) "/api/weather/radar/$region" else null,
        status = if (live) Channel.Status.LIVE else Channel.Status.UNAVAILABLE,
        lastSuccessAt = null, lastError = null, errorCount = 0,
    )

    @Test fun `a radar override resolves to a Radar slot with an absolute image url`() {
        // A radar channel's current_url is a RELATIVE proxy path; the resolver joins it
        // onto the helper base so the tile has an absolute URL to load — it is NOT a
        // Playing slot (no ExoPlayer / StreamSpec, which would reject the non-http url).
        val slots = TileSlotResolver.resolve(
            tileCount = 2,
            defaultChannels = listOf(playable("a")),
            allChannels = listOf(playable("a"), radar("kilx")),
            overrides = mapOf(0 to "weather-radar-kilx"),
            helperBaseUrl = "http://192.168.50.92:8080",
        )
        val s0 = slots[0]
        assertTrue("slot 0 should be Radar but was $s0", s0 is Slot.Radar)
        s0 as Slot.Radar
        assertEquals("weather-radar-kilx", s0.channel.slug)
        assertEquals("http://192.168.50.92:8080/api/weather/radar/kilx", s0.imageUrl)
        assertEquals("slot-0/radar-weather-radar-kilx", s0.id)
        // no radar slug leaks a Playing slot
        assertTrue(slots.none { it is Slot.Playing && it.channel.isRadar })
    }

    @Test fun `an offline radar channel resolves to Offline, never a broken Radar`() {
        val slots = TileSlotResolver.resolve(
            tileCount = 1,
            defaultChannels = emptyList(),
            allChannels = listOf(radar("kilx", live = false)),
            overrides = mapOf(0 to "weather-radar-kilx"),
            helperBaseUrl = "http://h:8080",
        )
        assertTrue(slots[0] is Slot.Offline)
    }

    @Test fun `absolutizeRadarUrl joins relative paths and passes absolutes through`() {
        assertEquals("http://h:8080/api/weather/radar/kilx",
            TileSlotResolver.absolutizeRadarUrl("http://h:8080", "/api/weather/radar/kilx"))
        assertEquals("http://h:8080/api/weather/radar/kilx",
            TileSlotResolver.absolutizeRadarUrl("http://h:8080/", "/api/weather/radar/kilx"))
        assertEquals("http://h:8080/x",
            TileSlotResolver.absolutizeRadarUrl("http://h:8080", "x"))
        // already-absolute passes through untouched
        assertEquals("https://cdn/x.gif",
            TileSlotResolver.absolutizeRadarUrl("http://h:8080", "https://cdn/x.gif"))
        // blank base leaves the path unchanged (tile honestly fails to load, no crash)
        assertEquals("/api/weather/radar/kilx",
            TileSlotResolver.absolutizeRadarUrl("", "/api/weather/radar/kilx"))
    }
}
