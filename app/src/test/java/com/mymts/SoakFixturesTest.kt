package com.mymts

import com.mymts.soak.SoakFixtures
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Light unit tests for the fixture invariants. The harness behavior itself
 * is exercised by the on-device soak; here we just guard against the kind
 * of typos that would silently break a 6-hour run.
 */
class SoakFixturesTest {

    @Test
    fun pick_returnsRequestedCount() {
        val picked = SoakFixtures.pick(tiles = 4, pool = SoakFixtures.LIVE)
        assertEquals(4, picked.size)
    }

    @Test
    fun pick_idsAreUnique() {
        val picked = SoakFixtures.pick(tiles = 12, pool = SoakFixtures.LIVE)
        val ids = picked.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun pick_cyclesPoolWhenTilesExceedPoolSize() {
        val picked = SoakFixtures.pick(tiles = SoakFixtures.LIVE.size + 2, pool = SoakFixtures.LIVE)
        // First and (pool.size)-th picks share the same source URL.
        assertEquals(picked[0].url, picked[SoakFixtures.LIVE.size].url)
        // But the cycled copies have distinct ids (so the manager treats them
        // as separate tiles, not a re-entry of the same one).
        assertNotEquals(picked[0].id, picked[SoakFixtures.LIVE.size].id)
    }

    @Test
    fun pick_rejectsZeroTiles() {
        try {
            SoakFixtures.pick(0, SoakFixtures.LIVE)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun liveFixtures_areAllHttps() {
        // Stage-1 hard rule (mirrors the helper's RSS https-only rule):
        // public live HLS is over https. A plain-http fixture here would be
        // a foot-gun against the spirit of the trust model.
        SoakFixtures.LIVE.forEach {
            assertTrue("non-https fixture: ${it.id} -> ${it.url}", it.url.startsWith("https://"))
        }
        SoakFixtures.STABLE.forEach {
            assertTrue("non-https fixture: ${it.id} -> ${it.url}", it.url.startsWith("https://"))
        }
    }
}
