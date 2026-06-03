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

    @Test fun `companion factory wires the operator's lineup`() {
        val s = LineupSelector.forWall(maxCount = 4)
        val playable = listOf("cbs-sports-hq", "bbc-news", "cnn", "livenow-fox", "extra").map(::ch)
        val pick = s(playable).map { it.slug }
        assertEquals(listOf("cbs-sports-hq", "bbc-news", "cnn", "livenow-fox"), pick)
    }
}
