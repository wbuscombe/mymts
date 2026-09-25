package com.mymts.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One watchdog tick: the production cadence. */
private const val TICK_MS = StreamPlayer.TICK_INTERVAL_MS
/** Well past the hysteresis threshold. */
private const val FAR_BEHIND_MS = LIVE_EDGE_TRIGGER_MS * 2
/** `C.TIME_UNSET`: the offset of a non-live or unknown window. */
private const val OFFSET_UNSET_MS = Long.MIN_VALUE + 1
/** Arbitrary non-zero start for the fake monotonic clock. */
private const val T0_MS = 1_000_000L
/** How long case (e) keeps ticking a tile that never converges: 30 minutes. */
private const val SOAK_MS = 30 * 60 * 1_000L

/**
 * Pins the pure live-edge watchdog predicate ([shouldSeekToLive]) — the "drop the
 * backlog, stay current" decision (Part B). The watchdog seeks to live ONLY when
 * the measured offset behind live exceeds the drift threshold; a small offset
 * (normal) or a non-live/unknown window (negative `C.TIME_UNSET`) must NOT seek.
 *
 * And the bounded per-tick decision ([decideLiveEdgeCorrection], MYMTS-034): a
 * hysteresis threshold, a post-correction cooldown, and a cap on consecutive
 * corrections that never converge — so a seek whose own landing point or rebuffer
 * leaves the tile behind cannot fire the next seek every tick forever.
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

    // --- The bounded correction decision (MYMTS-034). ---

    private fun decide(offsetMs: Long, nowMs: Long, history: LiveEdgeHistory = LiveEdgeHistory()) =
        decideLiveEdgeCorrection(offsetMs, nowMs, history)

    /** A fresh, far-behind tile corrects at [T0_MS]; returns the history after it. */
    private fun correctedAtT0(): LiveEdgeHistory {
        val first = decide(FAR_BEHIND_MS, T0_MS)
        assertTrue("precondition: a fresh far-behind tile corrects", first.seekToLive)
        return first.history
    }

    @Test fun `(a) at the live edge - no correction`() {
        assertFalse(decide(0L, T0_MS).seekToLive)
        assertFalse(decide(StreamPlayer.TARGET_LIVE_OFFSET_MS, T0_MS).seekToLive)
    }

    @Test fun `(b) behind by less than the hysteresis threshold - no correction`() {
        // The band between the recovered line and the trigger: where a correction's
        // own landing point sits, and where the old 8 s trigger fired.
        assertFalse(decide(LIVE_EDGE_RECOVERED_MS + 1, T0_MS).seekToLive)
        assertFalse(decide(LIVE_EDGE_TRIGGER_MS - 1, T0_MS).seekToLive)
        assertFalse(decide(LIVE_EDGE_TRIGGER_MS, T0_MS).seekToLive)  // strictly greater corrects
    }

    @Test fun `(c) behind by more than the hysteresis threshold - correction`() {
        assertTrue(decide(LIVE_EDGE_TRIGGER_MS + 1, T0_MS).seekToLive)
        assertTrue(decide(FAR_BEHIND_MS, T0_MS).seekToLive)
    }

    @Test fun `(d) immediately after a correction - no second correction within the cooldown`() {
        val history = correctedAtT0()
        assertFalse(decide(FAR_BEHIND_MS, T0_MS + TICK_MS, history).seekToLive)
        assertFalse(decide(FAR_BEHIND_MS, T0_MS + LIVE_EDGE_COOLDOWN_MS - 1, history).seekToLive)
    }

    @Test fun `(e) repeated corrections that never converge stop at the bound`() {
        var history = LiveEdgeHistory()
        var corrections = 0
        var now = T0_MS
        while (now <= T0_MS + SOAK_MS) {
            val d = decide(FAR_BEHIND_MS, now, history)
            if (d.seekToLive) corrections++
            history = d.history
            now += TICK_MS
        }
        assertEquals(LIVE_EDGE_MAX_CONSECUTIVE_CORRECTIONS, corrections)
    }

    @Test fun `(f) after the cooldown a still-large offset may be corrected again`() {
        val history = correctedAtT0()
        val again = decide(FAR_BEHIND_MS, T0_MS + LIVE_EDGE_COOLDOWN_MS, history)
        assertTrue(again.seekToLive)
        assertEquals(2, again.history.consecutiveCorrections)
    }

    @Test fun `at the bound the watchdog holds until the offset recovers, then re-arms`() {
        var history = LiveEdgeHistory()
        var now = T0_MS
        repeat(LIVE_EDGE_MAX_CONSECUTIVE_CORRECTIONS) {
            val d = decide(FAR_BEHIND_MS, now, history)
            assertTrue(d.seekToLive)
            history = d.history
            now += LIVE_EDGE_COOLDOWN_MS
        }
        assertFalse("bound reached: hold", decide(FAR_BEHIND_MS, now, history).seekToLive)

        val recovered = decide(StreamPlayer.TARGET_LIVE_OFFSET_MS, now, history)
        assertFalse(recovered.seekToLive)
        assertEquals("recovery re-arms fully", LiveEdgeHistory(), recovered.history)
        assertTrue(decide(FAR_BEHIND_MS, now + TICK_MS, recovered.history).seekToLive)
    }

    @Test fun `a correction that only reaches the hysteresis band does not re-arm the bound`() {
        var history = LiveEdgeHistory()
        var corrections = 0
        var now = T0_MS
        repeat(LIVE_EDGE_MAX_CONSECUTIVE_CORRECTIONS + 2) {
            val d = decide(FAR_BEHIND_MS, now, history)
            if (d.seekToLive) corrections++
            history = d.history
            now += LIVE_EDGE_COOLDOWN_MS
            // Settles inside the band (never back to the recovered line), then drifts again.
            history = decide(LIVE_EDGE_TRIGGER_MS - 1, now, history).history
            now += TICK_MS
        }
        assertEquals(LIVE_EDGE_MAX_CONSECUTIVE_CORRECTIONS, corrections)
    }

    @Test fun `an unknown offset never corrects and leaves the history untouched`() {
        val history = correctedAtT0()
        val d = decide(OFFSET_UNSET_MS, T0_MS + LIVE_EDGE_COOLDOWN_MS, history)
        assertFalse(d.seekToLive)
        assertEquals(history, d.history)
    }

    @Test fun `the bounding constants are derived and sane`() {
        assertEquals(StreamPlayer.MAX_LIVE_DRIFT_MS + StreamPlayer.TARGET_LIVE_OFFSET_MS, LIVE_EDGE_TRIGGER_MS)
        assertEquals(StreamPlayer.MAX_LIVE_DRIFT_MS, LIVE_EDGE_RECOVERED_MS)
        assertTrue("recovered line above the aim point", LIVE_EDGE_RECOVERED_MS > StreamPlayer.TARGET_LIVE_OFFSET_MS)
        assertTrue(
            "hysteresis band spans at least two ticks",
            LIVE_EDGE_TRIGGER_MS - LIVE_EDGE_RECOVERED_MS >= 2 * StreamPlayer.TICK_INTERVAL_MS,
        )
        assertTrue(
            "cooldown spans at least one liveness staleness window",
            LIVE_EDGE_COOLDOWN_MS >= LivenessTracker().staleThresholdMs,
        )
        assertTrue("cooldown skips ticks", LIVE_EDGE_COOLDOWN_MS > StreamPlayer.TICK_INTERVAL_MS)
        assertTrue(LIVE_EDGE_MAX_CONSECUTIVE_CORRECTIONS >= 1)
    }
}
