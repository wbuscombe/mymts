package com.mymts.data.lineup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec tests for the audio + captions persistence added 2026-06-04.
 *
 * The audible-slot state is a single Int saved as a SharedPreferences
 * primitive (no codec needed). Captions-on-slots is a Set<Int>; this
 * test pins the JSON-array round-trip plus the corruption/edge cases.
 */
class LineupStoreIntSetCodecTest {

    @Test fun `round-trip preserves the captions-on set`() {
        val original = setOf(0, 2, 3)
        val encoded = LineupStore.encodeIntSet(original)
        val decoded = LineupStore.decodeIntSet(encoded)
        assertEquals(original, decoded)
    }

    @Test fun `empty set round-trips cleanly`() {
        assertEquals(emptySet<Int>(), LineupStore.decodeIntSet(LineupStore.encodeIntSet(emptySet())))
    }

    @Test fun `encode is deterministic on insertion order`() {
        val a = setOf(2, 0, 1)
        val b = setOf(1, 0, 2)
        assertEquals(LineupStore.encodeIntSet(a), LineupStore.encodeIntSet(b))
    }

    @Test fun `negative indices are dropped on decode`() {
        // Persistence must never restore a slot index < 0 — the rest of
        // the wall validates with `require(slotIndex >= 0)`, and we
        // prefer skipping garbage over crashing the store at load.
        val raw = "[-1,0,2,-5,3]"
        val decoded = LineupStore.decodeIntSet(raw)
        assertEquals(setOf(0, 2, 3), decoded)
    }

    @Test fun `non-int entries are skipped silently`() {
        // optInt(default = -1) catches anything that isn't an int and
        // we drop it. Mirrors the lineup-overrides codec's tolerance.
        val raw = """[0,"two",3]"""
        val decoded = LineupStore.decodeIntSet(raw)
        // "two" -> -1 via optInt, dropped. 0 and 3 survive.
        assertEquals(setOf(0, 3), decoded)
    }
}
