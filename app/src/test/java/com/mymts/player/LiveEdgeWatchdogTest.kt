package com.mymts.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure live-edge watchdog predicate ([shouldSeekToLive]) — the "drop the
 * backlog, stay current" decision (Part B). The watchdog seeks to live ONLY when
 * the measured offset behind live exceeds the drift threshold; a small offset
 * (normal) or a non-live/unknown window (negative `C.TIME_UNSET`) must NOT seek.
 */
class LiveEdgeWatchdogTest {

    private val maxDrift = StreamPlayer.MAX_LIVE_DRIFT_MS  // 8000ms

    @Test fun `seeks only when drift exceeds the threshold`() {
        assertTrue(shouldSeekToLive(maxDrift + 1, maxDrift))   // just over → seek
        assertTrue(shouldSeekToLive(20_000L, maxDrift))        // far behind → seek
        assertFalse(shouldSeekToLive(maxDrift, maxDrift))      // exactly at → hold (no seek)
        assertFalse(shouldSeekToLive(3_000L, maxDrift))        // within target → hold
        assertFalse(shouldSeekToLive(0L, maxDrift))            // at live edge → hold
    }

    @Test fun `a non-live or unknown window (negative offset) never seeks`() {
        // C.TIME_UNSET is Long.MIN_VALUE + 1 (a large negative) — below any
        // positive threshold, so the predicate correctly returns false.
        assertFalse(shouldSeekToLive(Long.MIN_VALUE + 1, maxDrift))
        assertFalse(shouldSeekToLive(-1L, maxDrift))
    }

    @Test fun `the tuning constants are sane (target below drift, both positive)`() {
        assertTrue(StreamPlayer.TARGET_LIVE_OFFSET_MS > 0)
        assertTrue("drift threshold above the target", StreamPlayer.MAX_LIVE_DRIFT_MS > StreamPlayer.TARGET_LIVE_OFFSET_MS)
        assertTrue(StreamPlayer.MIN_PLAYBACK_SPEED < 1f && StreamPlayer.MAX_PLAYBACK_SPEED > 1f)
        assertTrue("imperceptible speed window", StreamPlayer.MAX_PLAYBACK_SPEED - StreamPlayer.MIN_PLAYBACK_SPEED <= 0.1f)
    }
}
