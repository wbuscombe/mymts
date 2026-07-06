package com.mymts.wall

import com.mymts.data.helper.Channel
import com.mymts.ui.wall.LineupSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineupSelectorTest {

    private fun ch(slug: String) = Channel(
        slug = slug,
        label = slug.uppercase(),
        kind = "hls",
        currentUrl = "https://example.test/$slug.m3u8",
        status = Channel.Status.LIVE,
        lastSuccessAt = "2026-06-03T00:00:00Z",
        lastError = null,
        errorCount = 0,
    )

    private val selector = LineupSelector(
        preferredSlugs = listOf("a", "b", "c", "d"),
        fallbackSlugs = listOf("e", "f", "g"),
        maxCount = 4,
    )

    @Test fun `all preferred resolve — those four win, order preserved`() {
        val playable = listOf("d", "a", "c", "b", "f", "g").map(::ch)
        val pick = selector(playable).map { it.slug }
        assertEquals(listOf("a", "b", "c", "d"), pick)
    }

    @Test fun `partial preferred — fallback fills in order`() {
        // a + c resolve; b + d don't. Selector walks fallback (e, f, g).
        val playable = listOf("a", "c", "e", "f", "g").map(::ch)
        val pick = selector(playable).map { it.slug }
        assertEquals(listOf("a", "c", "e", "f"), pick)
    }

    @Test fun `zero preferred — fallback fills then 'rest'`() {
        val playable = listOf("e", "f", "g", "z").map(::ch)
        val pick = selector(playable).map { it.slug }
        // e, f, g (fallback in order) then z (anything else still playable).
        assertEquals(listOf("e", "f", "g", "z"), pick)
    }

    @Test fun `not enough resolved — returns what it can, no fakes`() {
        // Only a and e resolve. Two slots can be filled; the other two
        // are NOT padded with placeholders — that's the cycler's job.
        val playable = listOf("a", "e").map(::ch)
        val pick = selector(playable).map { it.slug }
        assertEquals(listOf("a", "e"), pick)
    }

    @Test fun `radar widget slugs are never swept into the default or top-up lineup`() {
        // Unified registry (2026-07): /api/channels now lists the radar widgets to the
        // native client, and they report status=live (so they're in `playable`). Radar
        // is an EXPLICIT per-cell pick — it must NOT auto-fill a default/top-up video
        // slot (parity with the web isRadarSlug exclusion). Even with room to spare, a
        // weather-radar-* slug is skipped by both the preferred/fallback and rest tiers.
        val playable = listOf(ch("a"), ch("weather-radar-kilx"), ch("weather-radar-conus"), ch("z"))
        val pick = selector(playable).map { it.slug }
        assertEquals(listOf("a", "z"), pick)   // radar excluded; z tops up the rest
        assertTrue(pick.none { Channel.isRadarSlug(it) })
    }

    @Test fun `zero playable — empty result`() {
        assertTrue(selector(emptyList()).isEmpty())
    }

    @Test fun `playable channels not on either list still fill remaining slots`() {
        // Only one preferred and none of the fallback resolve, but two
        // off-list channels do. Selector tops up so the grid isn't
        // half-empty for no reason.
        val playable = listOf("a", "x", "y").map(::ch)
        val pick = selector(playable).map { it.slug }
        assertEquals(listOf("a", "x", "y"), pick)
    }

    @Test fun `maxCount caps the result regardless of playable count`() {
        val small = LineupSelector(
            preferredSlugs = listOf("a", "b", "c"),
            fallbackSlugs = listOf("d"),
            maxCount = 2,
        )
        val playable = listOf("a", "b", "c", "d").map(::ch)
        val pick = small(playable).map { it.slug }
        assertEquals(listOf("a", "b"), pick)
    }

    @Test fun `maxCount=0 always returns empty`() {
        val none = LineupSelector(
            preferredSlugs = listOf("a"),
            fallbackSlugs = emptyList(),
            maxCount = 0,
        )
        assertTrue(none(listOf(ch("a"))).isEmpty())
    }

    @Test fun `duplicate slug across preferred and fallback only appears once`() {
        val dup = LineupSelector(
            preferredSlugs = listOf("a"),
            fallbackSlugs = listOf("a", "b"),
            maxCount = 3,
        )
        val playable = listOf("a", "b").map(::ch)
        val pick = dup(playable).map { it.slug }
        assertEquals(listOf("a", "b"), pick)
    }

    @Test fun `exact preset (topUp=false) fills ONLY its slugs — no top-up`() {
        // An "exact" wall preset: curated stays curated. Off-list playable
        // channels (x, y) must NOT pad the grid, even with slots to spare.
        val exact = LineupSelector(
            preferredSlugs = listOf("a", "b"),
            fallbackSlugs = emptyList(),
            maxCount = 4,
            topUp = false,
        )
        val playable = listOf("a", "b", "x", "y").map(::ch)
        val pick = exact(playable).map { it.slug }
        assertEquals(listOf("a", "b"), pick)
    }

    @Test fun `exact preset still honors fallback before stopping`() {
        // topUp only governs the off-list "rest" tier; an exact preset that
        // names a fallback still uses it (preferred → fallback), just no top-up.
        val exact = LineupSelector(
            preferredSlugs = listOf("a"),
            fallbackSlugs = listOf("b"),
            maxCount = 4,
            topUp = false,
        )
        val playable = listOf("a", "b", "x").map(::ch)
        val pick = exact(playable).map { it.slug }
        assertEquals(listOf("a", "b"), pick)
    }

    @Test fun `default topUp=true is unchanged — off-list channels still fill`() {
        // Regression guard: the default selector (topUp defaulted true) keeps
        // topping up exactly as before presets existed.
        val topup = LineupSelector(
            preferredSlugs = listOf("a", "b"),
            fallbackSlugs = emptyList(),
            maxCount = 4,
        )
        val playable = listOf("a", "b", "x", "y").map(::ch)
        val pick = topup(playable).map { it.slug }
        assertEquals(listOf("a", "b", "x", "y"), pick)
    }

    // ---- exactLineup: honest-offline curated presets (resolve vs ALL channels) ----

    private fun offlineCh(slug: String) = Channel(
        slug = slug,
        label = slug.uppercase(),
        kind = "hls",
        currentUrl = null,
        status = Channel.Status.UNAVAILABLE,
        lastSuccessAt = null,
        lastError = "down",
        errorCount = 1,
    )

    @Test fun `exactLineup keeps a listed OFFLINE channel in its slot (honest-offline)`() {
        // Space-like: iss live, nasa offline. Both must survive in order so the
        // offline one renders as an OFFLINE tile, not vanish.
        val all = listOf(ch("iss-feed"), offlineCh("nasa-tv"))
        val pick = LineupSelector.exactLineup(listOf("iss-feed", "nasa-tv"), all, maxCount = 2)
        assertEquals(listOf("iss-feed", "nasa-tv"), pick.map { it.slug })
    }

    @Test fun `exactLineup does NOT apply DENY (explicit operator selection)`() {
        // nasa-tv is in the default-wall DENY set, but an exact preset that LISTS
        // it shows it (offline) — DENY only governs the ambient default wall.
        val all = listOf(offlineCh("nasa-tv"))
        val pick = LineupSelector.exactLineup(listOf("nasa-tv"), all, maxCount = 2)
        assertEquals(listOf("nasa-tv"), pick.map { it.slug })
    }

    @Test fun `exactLineup preserves order, drops unknown slugs, no top-up`() {
        val all = listOf(ch("a"), ch("b"), ch("x"))
        // "ghost" matches no channel → dropped; x is not listed → never added.
        val pick = LineupSelector.exactLineup(listOf("b", "ghost", "a"), all, maxCount = 4)
        assertEquals(listOf("b", "a"), pick.map { it.slug })
    }

    @Test fun `exactLineup caps at maxCount and collapses duplicates`() {
        val all = listOf(ch("a"), ch("b"), ch("c"))
        assertEquals(listOf("a", "b"), LineupSelector.exactLineup(listOf("a", "b", "c"), all, 2).map { it.slug })
        assertEquals(listOf("a"), LineupSelector.exactLineup(listOf("a", "a"), all, 4).map { it.slug })
    }

    @Test fun `companion factory wires the operator's lineup`() {
        val s = LineupSelector.forWall(maxCount = 4)
        // fox-weather (PREFERRED[1]) isn't in this set, so it's skipped; the rest
        // come in PREFERRED order: livenow-fox, bbc-news, cbs-sports-hq, then cnn.
        val playable = listOf("cbs-sports-hq", "bbc-news", "cnn", "livenow-fox", "extra").map(::ch)
        val pick = s(playable).map { it.slug }
        assertEquals(listOf("livenow-fox", "bbc-news", "cbs-sports-hq", "cnn"), pick)
    }
}
