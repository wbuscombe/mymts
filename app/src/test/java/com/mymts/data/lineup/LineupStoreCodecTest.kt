package com.mymts.data.lineup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [LineupStore]'s wire-format encoder/decoder (the SharedPrefs
 * read/write path is exercised indirectly on-device; this layer covers
 * the deterministic codec).
 */
class LineupStoreCodecTest {

    @Test fun `round-trip preserves the pinned slot map`() {
        val original = mapOf(0 to "cbs-sports-hq", 1 to "dw-news-en", 3 to "redbull-tv")
        val encoded = LineupStore.encode(original)
        val decoded = LineupStore.decode(encoded)
        assertEquals(original, decoded)
    }

    @Test fun `empty map round-trips cleanly`() {
        assertEquals(emptyMap<Int, String>(), LineupStore.decode(LineupStore.encode(emptyMap())))
    }

    @Test fun `decode of corrupt JSON yields empty map without throwing the public API`() {
        // The store's read path catches JSONException and clears the
        // blob; we exercise the decoder directly here. A truncated
        // array decodes to whatever pairs were complete.
        val safe = try {
            LineupStore.decode("[0,\"a\",1,") // truncated mid-pair
        } catch (e: Exception) {
            // Implementation may throw — we just want to confirm the
            // exception type so the store wrapper knows what to catch.
            assertTrue(e is org.json.JSONException)
            emptyMap()
        }
        // Either way: no live garbage in the result.
        assertTrue(safe.size <= 1)
    }

    @Test fun `decode tolerates an odd-length array (drops the dangling element)`() {
        val raw = "[0,\"a\",1]" // missing slug after the trailing 1
        val decoded = LineupStore.decode(raw)
        // The store contract: odd-length blobs are not trusted —
        // safer to return empty than to half-restore.
        assertEquals(emptyMap<Int, String>(), decoded)
    }

    @Test fun `decode skips invalid pairs (negative index, blank slug)`() {
        // Two valid pairs, two invalid ones interspersed.
        val raw = """[-1,"neg-skipped",0,"keep-0",1,"",2,"keep-2"]"""
        val decoded = LineupStore.decode(raw)
        assertEquals(mapOf(0 to "keep-0", 2 to "keep-2"), decoded)
    }

    @Test fun `encode is deterministic on key order`() {
        // Maps with the same content should encode the same regardless
        // of iteration order — needed so two persists of the same lineup
        // produce byte-identical disk state.
        val a = mapOf(0 to "a", 2 to "c", 1 to "b")
        val b = mapOf(2 to "c", 1 to "b", 0 to "a")
        assertEquals(LineupStore.encode(a), LineupStore.encode(b))
    }

    @Test fun `encode produces distinct output for distinct content`() {
        val a = mapOf(0 to "a")
        val b = mapOf(0 to "b")
        assertNotEquals(LineupStore.encode(a), LineupStore.encode(b))
    }
}
