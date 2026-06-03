package com.mymts.wall

import com.mymts.data.helper.Channel
import com.mymts.ui.wall.LineupSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LineupSelectorDenyTest {

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

    @Test fun `denied slug never appears in the result, even via the rest tier`() {
        val selector = LineupSelector(
            preferredSlugs = listOf("a"),
            fallbackSlugs = listOf("b"),
            maxCount = 3,
            denySlugs = setOf("c"),
        )
        // c IS in playable and IS in the rest tier — but explicitly denied.
        val playable = listOf("a", "b", "c", "d").map(::ch)
        val pick = selector(playable).map { it.slug }
        assertFalse("denied 'c' must not appear", pick.contains("c"))
        assertEquals(listOf("a", "b", "d"), pick)
    }

    @Test fun `denied slug listed in preferred is ignored`() {
        val selector = LineupSelector(
            preferredSlugs = listOf("c", "a"),
            fallbackSlugs = emptyList(),
            maxCount = 2,
            denySlugs = setOf("c"),
        )
        val playable = listOf("a", "c").map(::ch)
        val pick = selector(playable).map { it.slug }
        assertEquals(listOf("a"), pick)
    }

    @Test fun `forWall denies nasa-tv (master-OK variant-FAIL)`() {
        val selector = LineupSelector.forWall(maxCount = 4)
        val playable = listOf("nasa-tv", "dw-news-en", "redbull-tv").map(::ch)
        val pick = selector(playable).map { it.slug }
        // NASA TV is explicitly excluded; the wall picks only what's
        // actually playable end-to-end.
        assertFalse("nasa-tv must not occupy a default slot", pick.contains("nasa-tv"))
        assertEquals(setOf("dw-news-en", "redbull-tv"), pick.toSet())
    }
}
