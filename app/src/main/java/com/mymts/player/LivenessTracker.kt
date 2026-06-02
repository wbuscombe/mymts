package com.mymts.player

/**
 * Pure-Kotlin state machine for "is this stream actually rendering frames?"
 *
 * The Stage 1 player reported state=LIVE while the surface received zero
 * frames for 10+ hours (Trust Bar **C3** violation: "staleness is never
 * silent"). The fix has to be derived from *actual frame arrival*, not
 * from ExoPlayer's `playWhenReady`/`playbackState` — ExoPlayer happily
 * reports STATE_READY while the decoder is starved.
 *
 * This class is the brain. It has no ExoPlayer dependency, takes a clock
 * for testability, and returns [TickAction] values that the caller
 * (`StreamPlayer`) executes. The state machine itself is unit-testable
 * without any Android dependency at all.
 *
 * Chosen values + reasoning (also recorded in `ARCHITECTURE.md`):
 *
 * - `staleThresholdMs = 15_000` — 15 s covers a normal HLS buffer drain
 *   (Stage 1 buffer was min=1.5 s / max=4 s) plus a brief network
 *   hiccup, while staying tight enough that an actually-frozen surface
 *   becomes STALE within a noticeable window. **30 s would have been a
 *   conservative number; 15 s honors C3 more aggressively.**
 *
 * - `maxRecoveryAttempts = 3` — three strikes (prepare, re-init, re-init)
 *   before settling DEAD. Stage 1 evidence: dw-news-en's high
 *   variant-switch rate suggests some streams recover after one re-init
 *   cycle; three attempts gives a couple of cycles' headroom before we
 *   call it dead. More than three burns CPU + decoder slots on the
 *   constrained Onn box without adding signal.
 *
 * - `backoffMs = [2_000, 8_000, 30_000]` — exponential with a soft cap.
 *   Strike 1 fires 2 s after we notice STALE (give a brief network blip
 *   a chance to self-resolve); strike 2 after 8 s; strike 3 after 30 s.
 *   The point is **anti-loop discipline** (Trust Bar **C2**: a dead feed
 *   is a non-event) — a legitimately-offline stream should settle into
 *   DEAD within ~45 s of going stale, not thrash recovery forever on a
 *   2 GB box.
 */
class LivenessTracker(
    private val clock: () -> Long = { System.currentTimeMillis() },
    val staleThresholdMs: Long = 15_000L,
    /**
     * How long the tracker waits in CONNECTING for the very first frame
     * before treating it as STALE and triggering the recovery ladder.
     * Longer than `staleThresholdMs` because live HLS initial-buffer
     * + manifest fetch can take 10–15 s on the Onn box; we don't want
     * to false-positive a slow start.
     */
    val connectingThresholdMs: Long = 30_000L,
    val maxRecoveryAttempts: Int = 3,
    val backoffMs: List<Long> = listOf(2_000L, 8_000L, 30_000L),
    private val onTransition: (State, State) -> Unit = { _, _ -> },
) {
    enum class State {
        /** Initial. No frame has been rendered yet. */
        CONNECTING,
        /** Frames have been arriving within the staleness threshold. */
        LIVE,
        /** Last frame was older than the threshold; waiting for next strike. */
        STALE,
        /** A recovery strike is in flight; window before declaring it failed. */
        RECOVERING,
        /** All recovery attempts exhausted. Honest dead surface; no more strikes. */
        DEAD,
        /**
         * Caller-only sentinel: the player was explicitly released (e.g. on
         * Activity destroy). The tracker itself never transitions into this;
         * StreamPlayer flips its StateFlow to OFFLINE so the UI distinguishes
         * "we gave up tracking this" from "we tried and failed."
         */
        OFFLINE,
    }

    /** Returned by [onTick] so the caller can execute the side effect. */
    enum class TickAction {
        NONE,
        /** Strike 1: re-attempt the existing source via ExoPlayer.prepare(). */
        ATTEMPT_PREPARE,
        /** Strike 2+: full release + re-create of the player. */
        ATTEMPT_REINIT,
        /** Transition to DEAD just happened; release decoder + surface. */
        SETTLE_DEAD,
    }

    var state: State = State.CONNECTING
        private set

    /** Wall-clock ms of the last frame-arrival signal. `NO_FRAME` until the first. */
    var lastFrameAtMs: Long = NO_FRAME
        private set

    var recoveryAttempts: Int = 0
        private set

    private var staleAtMs: Long = 0L
    private var recoveryStartedAtMs: Long = 0L
    /** Time the tracker entered CONNECTING (set on reset). */
    private var connectingStartedAtMs: Long = clock()

    /**
     * Called when ExoPlayer signals a frame-arrival event:
     * `onRenderedFirstFrame` (first frame after surface/prepare/variant switch)
     * OR `onDroppedVideoFrames` (the decoder is alive, even if some frames
     * were dropped). Either is a positive "decoder isn't starved" signal.
     */
    fun onFrameRendered() {
        lastFrameAtMs = clock()
        when (state) {
            State.CONNECTING, State.STALE, State.RECOVERING -> {
                if (state != State.LIVE) transition(State.LIVE)
                recoveryAttempts = 0
                staleAtMs = 0L
            }
            State.LIVE -> { /* heartbeat; nothing to do */ }
            State.DEAD -> { /* once dead, ignore late-arriving frames — we already released */ }
            State.OFFLINE -> { /* explicitly released by the caller; do nothing */ }
        }
    }

    /**
     * Called periodically (e.g. every 2 s) by the caller's tick loop.
     * Returns the action the caller should perform on the actual player.
     */
    fun onTick(): TickAction {
        val now = clock()
        return when (state) {
            State.CONNECTING -> {
                // First-frame timeout: a stream that never connects must
                // still be reachable via the recovery ladder (otherwise an
                // unreachable URL hangs in CONNECTING forever — exactly
                // the silent-staleness failure C3 forbids).
                if (now - connectingStartedAtMs > connectingThresholdMs) {
                    staleAtMs = now
                    transition(State.STALE)
                }
                TickAction.NONE
            }
            State.DEAD -> TickAction.NONE
            State.OFFLINE -> TickAction.NONE
            State.LIVE -> {
                if (lastFrameAtMs != NO_FRAME && (now - lastFrameAtMs) > staleThresholdMs) {
                    staleAtMs = now
                    transition(State.STALE)
                }
                TickAction.NONE
            }
            State.STALE -> {
                val backoffIdx = recoveryAttempts.coerceAtMost(backoffMs.lastIndex)
                val backoff = backoffMs[backoffIdx]
                if (now - staleAtMs >= backoff) {
                    recoveryAttempts++
                    recoveryStartedAtMs = now
                    transition(State.RECOVERING)
                    if (recoveryAttempts == 1) TickAction.ATTEMPT_PREPARE
                    else TickAction.ATTEMPT_REINIT
                } else {
                    TickAction.NONE
                }
            }
            State.RECOVERING -> {
                // Give the strike one staleness-window to produce a frame.
                if (now - recoveryStartedAtMs > staleThresholdMs) {
                    if (recoveryAttempts >= maxRecoveryAttempts) {
                        transition(State.DEAD)
                        TickAction.SETTLE_DEAD
                    } else {
                        staleAtMs = now
                        transition(State.STALE)
                        TickAction.NONE
                    }
                } else {
                    TickAction.NONE
                }
            }
        }
    }

    /** Reset to CONNECTING. Used when the caller fully re-initializes the player. */
    fun reset() {
        state = State.CONNECTING
        lastFrameAtMs = NO_FRAME
        recoveryAttempts = 0
        staleAtMs = 0L
        recoveryStartedAtMs = 0L
        connectingStartedAtMs = clock()
    }

    /** Frame-age at `now`, or -1 if no frame has been rendered yet. */
    fun frameAgeMs(now: Long = clock()): Long =
        if (lastFrameAtMs != NO_FRAME) now - lastFrameAtMs else -1L

    private fun transition(to: State) {
        val from = state
        if (from == to) return
        state = to
        onTransition(from, to)
    }

    companion object {
        /**
         * Sentinel for "no frame yet". We can't use 0 because tests use a
         * fake clock that starts at 0; real Android wall-clock is in the
         * trillions, but the abstraction shouldn't assume that.
         */
        const val NO_FRAME: Long = Long.MIN_VALUE
    }
}
