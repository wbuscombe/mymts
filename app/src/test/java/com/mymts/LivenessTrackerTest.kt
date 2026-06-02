package com.mymts

import com.mymts.player.LivenessTracker
import com.mymts.player.LivenessTracker.State
import com.mymts.player.LivenessTracker.TickAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LivenessTrackerTest {

    /** Fake clock. Each call returns the current `now`; the test advances it. */
    private class FakeClock(var nowMs: Long = 0L) : () -> Long {
        override fun invoke(): Long = nowMs
        fun advance(ms: Long) { nowMs += ms }
    }

    private fun newTracker(
        clock: FakeClock,
        threshold: Long = 15_000L,
        maxAttempts: Int = 3,
        backoff: List<Long> = listOf(2_000L, 8_000L, 30_000L),
        transitions: MutableList<Pair<State, State>> = mutableListOf(),
    ): Pair<LivenessTracker, MutableList<Pair<State, State>>> {
        val tracker = LivenessTracker(
            clock = clock,
            staleThresholdMs = threshold,
            maxRecoveryAttempts = maxAttempts,
            backoffMs = backoff,
            onTransition = { from, to -> transitions.add(from to to) },
        )
        return tracker to transitions
    }

    @Test
    fun initialStateIsConnecting() {
        val clock = FakeClock()
        val (tracker, _) = newTracker(clock)
        assertEquals(State.CONNECTING, tracker.state)
    }

    @Test
    fun firstFrameTransitionsConnectingToLive() {
        val clock = FakeClock(100L)
        val (tracker, transitions) = newTracker(clock)
        tracker.onFrameRendered()
        assertEquals(State.LIVE, tracker.state)
        assertEquals(listOf(State.CONNECTING to State.LIVE), transitions)
    }

    @Test
    fun liveStaysLiveBelowThreshold() {
        val clock = FakeClock(0L)
        val (tracker, transitions) = newTracker(clock)
        tracker.onFrameRendered()
        clock.advance(14_999L)
        assertEquals(TickAction.NONE, tracker.onTick())
        assertEquals(State.LIVE, tracker.state)
        // Only the initial CONNECTING→LIVE.
        assertEquals(1, transitions.size)
    }

    @Test
    fun liveTransitionsToStaleAtThreshold() {
        val clock = FakeClock(0L)
        val (tracker, transitions) = newTracker(clock)
        tracker.onFrameRendered()
        clock.advance(15_001L)
        assertEquals(TickAction.NONE, tracker.onTick())
        assertEquals(State.STALE, tracker.state)
        assertEquals(State.LIVE to State.STALE, transitions.last())
    }

    @Test
    fun staleWaitsBackoffBeforeFirstStrike() {
        val clock = FakeClock(0L)
        val (tracker, _) = newTracker(clock)
        tracker.onFrameRendered()        // → LIVE
        clock.advance(20_000L)
        tracker.onTick()                  // → STALE
        // Backoff[0] = 2s; one second later, no strike yet.
        clock.advance(1_000L)
        assertEquals(TickAction.NONE, tracker.onTick())
        assertEquals(State.STALE, tracker.state)
        // After backoff elapses, strike 1 fires.
        clock.advance(1_500L)
        assertEquals(TickAction.ATTEMPT_PREPARE, tracker.onTick())
        assertEquals(State.RECOVERING, tracker.state)
        assertEquals(1, tracker.recoveryAttempts)
    }

    @Test
    fun secondStrikeIsReinitAfterLongerBackoff() {
        val clock = FakeClock(0L)
        val (tracker, _) = newTracker(clock)
        tracker.onFrameRendered()
        clock.advance(20_000L); tracker.onTick()                  // STALE
        clock.advance(2_001L); tracker.onTick()                   // RECOVERING (strike 1, PREPARE)
        clock.advance(15_001L); tracker.onTick()                  // strike failed → STALE
        // Backoff[1] = 8s; not enough time yet.
        clock.advance(7_000L)
        assertEquals(TickAction.NONE, tracker.onTick())
        clock.advance(1_001L)
        assertEquals(TickAction.ATTEMPT_REINIT, tracker.onTick())
        assertEquals(State.RECOVERING, tracker.state)
        assertEquals(2, tracker.recoveryAttempts)
    }

    @Test
    fun frameDuringRecoveryReturnsToLiveAndResetsAttempts() {
        val clock = FakeClock(0L)
        val (tracker, _) = newTracker(clock)
        tracker.onFrameRendered()
        clock.advance(20_000L); tracker.onTick()                  // STALE
        clock.advance(2_001L); tracker.onTick()                   // RECOVERING (strike 1)
        // A frame arrives — the recovery succeeded.
        clock.advance(500L)
        tracker.onFrameRendered()
        assertEquals(State.LIVE, tracker.state)
        assertEquals(0, tracker.recoveryAttempts)
    }

    @Test
    fun threeStrikesThenDead() {
        val clock = FakeClock(0L)
        val actions = mutableListOf<TickAction>()
        val (tracker, transitions) = newTracker(clock)
        tracker.onFrameRendered()                                  // → LIVE
        clock.advance(20_000L); actions += tracker.onTick()        // → STALE
        clock.advance(2_001L); actions += tracker.onTick()         // strike 1 PREPARE
        clock.advance(15_001L); actions += tracker.onTick()        // RECOVERING failed → STALE
        clock.advance(8_001L); actions += tracker.onTick()         // strike 2 REINIT
        clock.advance(15_001L); actions += tracker.onTick()        // RECOVERING failed → STALE
        clock.advance(30_001L); actions += tracker.onTick()        // strike 3 REINIT
        clock.advance(15_001L); actions += tracker.onTick()        // RECOVERING failed → DEAD
        assertEquals(State.DEAD, tracker.state)
        assertEquals(
            "ladder must be: PREPARE, REINIT, REINIT, SETTLE_DEAD",
            listOf(
                TickAction.NONE,
                TickAction.ATTEMPT_PREPARE,
                TickAction.NONE,
                TickAction.ATTEMPT_REINIT,
                TickAction.NONE,
                TickAction.ATTEMPT_REINIT,
                TickAction.SETTLE_DEAD,
            ),
            actions,
        )
        // Final transition is STALE→DEAD or RECOVERING→DEAD; in either case ends DEAD.
        assertEquals(State.DEAD, transitions.last().second)
    }

    @Test
    fun deadIsAbsorbing_noFurtherStrikes() {
        val clock = FakeClock(0L)
        val (tracker, _) = newTracker(clock)
        tracker.onFrameRendered()
        clock.advance(20_000L); tracker.onTick()
        clock.advance(2_001L); tracker.onTick()
        clock.advance(15_001L); tracker.onTick()
        clock.advance(8_001L); tracker.onTick()
        clock.advance(15_001L); tracker.onTick()
        clock.advance(30_001L); tracker.onTick()
        clock.advance(15_001L); tracker.onTick()
        assertEquals(State.DEAD, tracker.state)
        // Subsequent ticks must not produce more actions.
        repeat(10) {
            clock.advance(60_000L)
            assertEquals(TickAction.NONE, tracker.onTick())
            assertEquals(State.DEAD, tracker.state)
        }
    }

    @Test
    fun deadIgnoresLateArrivingFrames() {
        val clock = FakeClock(0L)
        val (tracker, _) = newTracker(clock)
        tracker.onFrameRendered()
        clock.advance(20_000L); tracker.onTick()
        clock.advance(2_001L); tracker.onTick()
        clock.advance(15_001L); tracker.onTick()
        clock.advance(8_001L); tracker.onTick()
        clock.advance(15_001L); tracker.onTick()
        clock.advance(30_001L); tracker.onTick()
        clock.advance(15_001L); tracker.onTick()
        assertEquals(State.DEAD, tracker.state)
        // A late frame should NOT silently revive a DEAD tile (anti-loop:
        // we already released the decoder, the user-facing surface shows
        // dead). A future explicit user-action re-init handles revival.
        tracker.onFrameRendered()
        assertEquals(State.DEAD, tracker.state)
    }

    @Test
    fun resetReturnsToConnecting() {
        val clock = FakeClock(0L)
        val (tracker, _) = newTracker(clock)
        tracker.onFrameRendered()
        clock.advance(20_000L); tracker.onTick()
        assertEquals(State.STALE, tracker.state)
        tracker.reset()
        assertEquals(State.CONNECTING, tracker.state)
        assertEquals(LivenessTracker.NO_FRAME, tracker.lastFrameAtMs)
        assertEquals(0, tracker.recoveryAttempts)
        assertEquals(-1L, tracker.frameAgeMs())
    }

    @Test
    fun configurableThresholdHonored() {
        val clock = FakeClock(0L)
        val (tracker, _) = newTracker(clock, threshold = 5_000L)
        tracker.onFrameRendered()
        clock.advance(4_999L)
        tracker.onTick()
        assertEquals(State.LIVE, tracker.state)
        clock.advance(2L)
        tracker.onTick()
        assertEquals(State.STALE, tracker.state)
    }

    @Test
    fun frameAgeMsTracksClock() {
        val clock = FakeClock(0L)
        val (tracker, _) = newTracker(clock)
        assertEquals(-1L, tracker.frameAgeMs())
        clock.nowMs = 100L
        tracker.onFrameRendered()
        clock.nowMs = 5_100L
        assertEquals(5_000L, tracker.frameAgeMs())
    }

    @Test
    fun connectingWithoutFirstFrameEventuallyEntersRecoveryLadder() {
        val clock = FakeClock(0L)
        val (tracker, _) = newTracker(clock)
        // No frame ever arrives. Tracker stays CONNECTING until
        // connectingThresholdMs (default 30s) elapses, then STALE.
        clock.advance(29_000L)
        tracker.onTick()
        assertEquals(State.CONNECTING, tracker.state)
        clock.advance(2_000L)
        tracker.onTick()
        assertEquals(State.STALE, tracker.state)
        // From STALE, the recovery ladder runs.
        clock.advance(2_001L)
        assertEquals(TickAction.ATTEMPT_PREPARE, tracker.onTick())
    }

    @Test
    fun connectingToDeadIfStreamNeverProducesAFrame() {
        // Full lifecycle for an unreachable URL: CONNECTING → STALE →
        // 3 strikes → DEAD, with no frame ever arriving. Anti-loop holds.
        val clock = FakeClock(0L)
        val (tracker, _) = newTracker(clock)
        clock.advance(31_000L); tracker.onTick()              // CONNECTING → STALE
        clock.advance(2_001L); tracker.onTick()               // strike 1
        clock.advance(15_001L); tracker.onTick()              // → STALE
        clock.advance(8_001L); tracker.onTick()               // strike 2
        clock.advance(15_001L); tracker.onTick()              // → STALE
        clock.advance(30_001L); tracker.onTick()              // strike 3
        clock.advance(15_001L); tracker.onTick()              // → DEAD
        assertEquals(State.DEAD, tracker.state)
    }

    @Test
    fun configurableConnectingThresholdHonored() {
        val clock = FakeClock(0L)
        val (tracker, _) = newTracker(clock)
        // Default is 30s, but a custom tracker with 5s should bail faster.
        val tightTransitions = mutableListOf<Pair<State, State>>()
        val tightTracker = LivenessTracker(
            clock = clock,
            staleThresholdMs = 15_000L,
            connectingThresholdMs = 5_000L,
            onTransition = { f, t -> tightTransitions.add(f to t) },
        )
        clock.advance(4_999L)
        tightTracker.onTick()
        assertEquals(State.CONNECTING, tightTracker.state)
        clock.advance(2L)
        tightTracker.onTick()
        assertEquals(State.STALE, tightTracker.state)
        // The default tracker (30s) at the same wall-clock is still CONNECTING.
        tracker.onTick()
        assertEquals(State.CONNECTING, tracker.state)
    }

    @Test
    fun staleNeverHappensWhileFramesArrive() {
        val clock = FakeClock(0L)
        val (tracker, _) = newTracker(clock)
        tracker.onFrameRendered()
        // Frames every 5 s for an hour — well inside threshold.
        repeat(720) {
            clock.advance(5_000L)
            tracker.onFrameRendered()
            tracker.onTick()
        }
        assertEquals(State.LIVE, tracker.state)
        assertTrue(tracker.recoveryAttempts == 0)
    }
}
