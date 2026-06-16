package com.mymts.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure single-audible-tile routing: default none → all renderers off; enabling a
 *  tile enables ONLY it; re-enabling it mutes the wall. Drives the audio-renderer
 *  disable that wins back the inaudible-tile decode CPU. */
class AudioRoutingTest {

    @Test fun `default none audible disables every tile's renderer`() {
        // NONE (the muted-wall default) → no slot's audio renderer is enabled.
        for (i in 0..8) assertFalse(AudioRouting.audioEnabled(AudioRouting.NONE, i))
    }

    @Test fun `exactly the audible slot is enabled, all others disabled`() {
        val audible = 2
        assertTrue(AudioRouting.audioEnabled(audible, 2))
        for (i in listOf(0, 1, 3, 4, 8)) {
            assertFalse("slot $i must be disabled when slot 2 is audible",
                AudioRouting.audioEnabled(audible, i))
        }
    }

    @Test fun `nextAudible moves audio, re-tap mutes the wall, bad index is a no-op`() {
        // From muted, tapping slot 1 makes 1 audible.
        assertEquals(1, AudioRouting.nextAudible(AudioRouting.NONE, 1))
        // Tapping a different slot MOVES audio (single source — never two enabled).
        assertEquals(3, AudioRouting.nextAudible(1, 3))
        // Re-tapping the audible slot mutes the whole wall.
        assertEquals(AudioRouting.NONE, AudioRouting.nextAudible(2, 2))
        // A negative clicked index leaves the current selection unchanged.
        assertEquals(2, AudioRouting.nextAudible(2, -1))
    }

    @Test fun `at most one renderer is ever enabled across the grid`() {
        for (audible in listOf(AudioRouting.NONE, 0, 1, 2, 3)) {
            val enabled = (0..3).count { AudioRouting.audioEnabled(audible, it) }
            assertTrue("at most one audible tile (was $enabled for audible=$audible)", enabled <= 1)
        }
    }
}
