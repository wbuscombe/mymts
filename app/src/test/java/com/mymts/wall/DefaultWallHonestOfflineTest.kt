package com.mymts.wall

import com.mymts.data.helper.Channel
import com.mymts.ui.wall.LineupSelector
import com.mymts.ui.wall.TileSlotResolver
import com.mymts.ui.wall.TileSlotResolver.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy A — honest-offline on the DEFAULT ("news") wall (MYMTS-014).
 *
 * A configured channel that isn't live keeps the slot it would hold if every channel were
 * live, and resolves to the existing [Slot.Offline] (the C2 OFFLINE tile). It is no longer
 * filtered out first, which let later channels shift up and the cycler repeat a live
 * channel into the gap.
 *
 * [wall] is the default wall's slot list exactly as `WallScreen` builds it, minus Compose:
 * [LineupSelector.defaultWallLineup] → [TileSlotResolver.resolve]. [legacyWall] is the
 * pre-MYMTS-014 wiring (filter to playable, then select) — kept as a control so each
 * fixture is shown to discriminate, and so the all-live case can assert "identical to
 * today" literally.
 *
 * Slot assignment and slot type only: drawn appearance has no harness in this module.
 */
class DefaultWallHonestOfflineTest {

    private fun live(slug: String) = Channel(
        slug = slug,
        label = slug.uppercase(),
        kind = "hls",
        currentUrl = "https://example.test/$slug.m3u8",
        status = Channel.Status.LIVE,
        lastSuccessAt = "2026-09-11T00:00:00Z",
        lastError = null,
        errorCount = 0,
    )

    private fun dead(slug: String) = Channel(
        slug = slug,
        label = slug.uppercase(),
        kind = "hls",
        currentUrl = null,
        status = Channel.Status.UNAVAILABLE,
        lastSuccessAt = null,
        lastError = "down",
        errorCount = 3,
    )

    /**
     * A channel set in the helper's own order — deliberately NOT lineup order — holding
     * every PREFERRED and FALLBACK slug plus the deny-listed `nasa-tv`.
     */
    private val helperOrder = listOf(
        "cnn", "c-span", "nasa-tv", "bbc-news", "livenow-fox", "iss-feed", "cbs-sports-hq",
        "newsmax", "fox-weather", "bloomberg-tv", "white-house-tv", "cnn-international",
    )

    private fun helperSet(down: Set<String>) =
        helperOrder.map { if (it in down) dead(it) else live(it) }

    private fun wall(all: List<Channel>, tiles: Int): List<Slot> = TileSlotResolver.resolve(
        tileCount = tiles,
        defaultChannels = LineupSelector.defaultWallLineup(all, tiles),
        allChannels = all,
    )

    private fun legacyWall(all: List<Channel>, tiles: Int): List<Slot> = TileSlotResolver.resolve(
        tileCount = tiles,
        defaultChannels = LineupSelector.forWall(tiles).invoke(all.filter { it.isPlayable }),
        allChannels = all,
    )

    private fun Slot.slug(): String? = when (this) {
        is Slot.Playing -> channel.slug
        is Slot.Offline -> channel.slug
        is Slot.Radar -> channel.slug
        is Slot.Empty -> null
    }

    private val default2x2 = listOf("livenow-fox", "fox-weather", "bbc-news", "cbs-sports-hq")

    @Test fun `(a) a dead configured channel keeps its ORIGINAL slot index`() {
        assertEquals(default2x2, wall(helperSet(down = emptySet()), tiles = 4).map { it.slug() })

        val all = helperSet(down = setOf("fox-weather"))
        val slots = wall(all, tiles = 4)
        assertEquals(default2x2, slots.map { it.slug() })
        assertEquals(1, slots.indexOfFirst { it.slug() == "fox-weather" })
        assertEquals(1, slots[1].index)

        // Control: the pre-policy-A wiring drops it from the wall entirely.
        assertTrue(legacyWall(all, tiles = 4).none { it.slug() == "fox-weather" })
    }

    @Test fun `(b) the dead channel's slot is the existing Offline slot type`() {
        val all = helperSet(down = setOf("fox-weather"))
        val s1 = wall(all, tiles = 4)[1]
        assertTrue("slot 1 should be Offline but was $s1", s1 is Slot.Offline)
        s1 as Slot.Offline
        assertEquals(1, s1.index)
        assertEquals("fox-weather", s1.channel.slug)
        assertEquals("slot-1/offline-fox-weather", s1.id)

        // Control: the pre-policy-A wiring shifts a LIVE channel (bbc-news) into slot 1.
        val legacy1 = legacyWall(all, tiles = 4)[1]
        assertTrue("legacy slot 1 was $legacy1", legacy1 is Slot.Playing && legacy1.channel.slug == "bbc-news")
    }

    @Test fun `(c) live channels hold exactly the indices they hold when nothing is dead`() {
        val tiles = 9
        val original = wall(helperSet(down = emptySet()), tiles).associate { it.slug() to it.index }
        assertEquals(tiles, original.size)   // nine distinct channels, no cycling

        // One preferred (fox-weather, slot 1) and one fallback (c-span, slot 6) down.
        val all = helperSet(down = setOf("fox-weather", "c-span"))
        val slots = wall(all, tiles)
        val playing = slots.filterIsInstance<Slot.Playing>()
        assertEquals(7, playing.size)
        playing.forEach { assertEquals("${it.channel.slug} moved", original[it.channel.slug], it.index) }
        assertEquals(
            listOf(1 to "fox-weather", 6 to "c-span"),
            slots.filterIsInstance<Slot.Offline>().map { it.index to it.channel.slug },
        )

        // Control: the pre-policy-A wiring shifts every later live channel (bbc-news 2 → 1).
        assertEquals(1, legacyWall(all, tiles).indexOfFirst { it.slug() == "bbc-news" })
    }

    @Test fun `(d) no live channel is repeated into a dead channel's slot`() {
        // The default 2x2 with exactly its four channels and one down — the shape that used
        // to compact to three and cycle a live channel into the fourth tile.
        val four = default2x2.map { if (it == "fox-weather") dead(it) else live(it) }
        val slots = wall(four, tiles = 4)
        assertTrue("slot 1 should be Offline but was ${slots[1]}", slots[1] is Slot.Offline)
        val liveSlugs = slots.filterIsInstance<Slot.Playing>().map { it.channel.slug }
        assertEquals(listOf("livenow-fox", "bbc-news", "cbs-sports-hq"), liveSlugs)

        // A 2x3 over the six preferred channels with two down: still no repeats.
        val six = LineupSelector.PREFERRED.map { if (it == "fox-weather" || it == "cnn") dead(it) else live(it) }
        val wide = wall(six, tiles = 6)
        val wideLive = wide.filterIsInstance<Slot.Playing>().map { it.channel.slug }
        assertEquals(wideLive.distinct(), wideLive)
        assertEquals(listOf(1, 5), wide.filterIsInstance<Slot.Offline>().map { it.index })

        // Control: the pre-policy-A wiring repeats a live channel in both shapes.
        assertEquals(
            listOf("livenow-fox", "bbc-news", "cbs-sports-hq", "livenow-fox"),
            legacyWall(four, tiles = 4).map { it.slug() },
        )
        val legacyWide = legacyWall(six, tiles = 6).map { it.slug() }
        assertTrue("legacy did not repeat: $legacyWide", legacyWide.size > legacyWide.distinct().size)
    }

    @Test fun `(e) all channels dead — every slot is an OFFLINE tile, none blank, no crash`() {
        val all = helperSet(down = helperOrder.toSet())
        for (tiles in 1..9) {
            val slots = wall(all, tiles)
            assertEquals(tiles, slots.size)
            slots.forEachIndexed { i, s ->
                assertTrue("tiles=$tiles slot $i should be Offline but was $s", s is Slot.Offline)
                assertEquals(i, s.index)
            }
            // DENY still holds: nasa-tv never takes a default slot, live or dead.
            assertTrue(slots.none { it.slug() == "nasa-tv" })
        }
        // Everything down, and the original default order still holds.
        assertEquals(default2x2, wall(all, tiles = 4).map { it.slug() })

        // Control: the pre-policy-A wiring blanks the whole wall.
        assertTrue(legacyWall(all, tiles = 4).all { it is Slot.Empty })
    }

    @Test fun `(f) all channels live — the wall is identical to today's at every grid size`() {
        val all = helperSet(down = emptySet())
        for (tiles in 1..9) {
            assertEquals("tiles=$tiles", legacyWall(all, tiles), wall(all, tiles))
        }
        // A sparse helper (fewer channels than tiles) keeps today's cycling too.
        val sparse = listOf("bbc-news", "livenow-fox").map(::live)
        for (tiles in 1..9) {
            assertEquals("sparse tiles=$tiles", legacyWall(sparse, tiles), wall(sparse, tiles))
        }
        assertEquals(default2x2, wall(all, tiles = 4).map { it.slug() })
        assertTrue(wall(all, tiles = 9).all { it is Slot.Playing })
    }
}
