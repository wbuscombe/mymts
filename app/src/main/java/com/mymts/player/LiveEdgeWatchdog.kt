package com.mymts.player

// --- Bounding thresholds (MYMTS-034). A correction is a hard seek that flushes the
//     buffer, so each one costs a rebuffer; these stop it re-firing on its own landing
//     point or rebuffer. Derived from StreamPlayer's live-edge tuning and the
//     LivenessTracker recovery ladder, not invented. ---

/**
 * Hysteresis threshold: correct only when MORE than this far behind live.
 * = MAX_LIVE_DRIFT_MS + TARGET_LIVE_OFFSET_MS (12 s). A seek-to-live lands at the
 * target offset or later (Media3 snaps the default position back to a segment start,
 * and the flush + refetch adds its own rebuffer), so the old 8 s trigger, only 4 s
 * above the 4 s aim point, could re-fire on the correction's own landing one tick
 * later. This leaves 8 s (four ticks) above the aim point and a 4 s (two-tick) band
 * above [LIVE_EDGE_RECOVERED_MS].
 */
internal const val LIVE_EDGE_TRIGGER_MS: Long =
    StreamPlayer.MAX_LIVE_DRIFT_MS + StreamPlayer.TARGET_LIVE_OFFSET_MS

/**
 * Recovered level: at or below this the tile is back in its normal band, so the
 * watchdog re-arms fully (the consecutive count resets). The existing drift line,
 * MAX_LIVE_DRIFT_MS (8 s, 2× target).
 */
internal const val LIVE_EDGE_RECOVERED_MS: Long = StreamPlayer.MAX_LIVE_DRIFT_MS

/**
 * Cooldown after a correction before the watchdog judges or repeats it: 15 s, the
 * LivenessTracker staleness window — what the recovery ladder already grants each
 * disruptive strike to produce frames, sized for "a normal HLS buffer drain plus a
 * brief network hiccup". A seek-to-live is the same kind of disruption.
 */
internal const val LIVE_EDGE_COOLDOWN_MS: Long = 15_000L

/**
 * Corrections in a row allowed without the tile recovering before the watchdog
 * holds: 3, as LivenessTracker's maxRecoveryAttempts (more strikes burn CPU and
 * decoder slots on the Onn box without adding signal).
 */
internal const val LIVE_EDGE_MAX_CONSECUTIVE_CORRECTIONS: Int = 3

/**
 * What the live-edge watchdog carries between ticks: when it last corrected
 * (monotonic ms, null = never) and how many corrections it has made in a row.
 */
internal data class LiveEdgeHistory(
    val lastCorrectionAtMs: Long? = null,
    val consecutiveCorrections: Int = 0,
)

/** One tick's verdict: seek to live now or not, plus the history to carry forward. */
internal data class LiveEdgeDecision(
    val seekToLive: Boolean,
    val history: LiveEdgeHistory,
)

/**
 * Pure: the live-edge watchdog's per-tick decision, given the measured offset
 * behind live, a monotonic clock reading and the correction history. No Media3,
 * so it is unit-testable. [StreamPlayer]'s tick executes the seek.
 *
 * Bounded, in order: an unknown offset holds; inside [LIVE_EDGE_COOLDOWN_MS] of the
 * last correction it holds (that seek's own rebuffer is still settling); at or under
 * [LIVE_EDGE_RECOVERED_MS] the tile has recovered and the history resets; past
 * [LIVE_EDGE_TRIGGER_MS] it corrects, at most [LIVE_EDGE_MAX_CONSECUTIVE_CORRECTIONS]
 * times without recovering. At the bound it holds — the tile plays through at its
 * offset, as web and VLC do — until it recovers or the stream changes (the caller
 * resets the history on a new player or url).
 */
internal fun decideLiveEdgeCorrection(
    currentLiveOffsetMs: Long,
    nowMs: Long,
    history: LiveEdgeHistory,
): LiveEdgeDecision {
    val hold = LiveEdgeDecision(seekToLive = false, history = history)
    // No usable measurement: a non-live / unknown window reports negative C.TIME_UNSET.
    if (currentLiveOffsetMs < 0) return hold
    val last = history.lastCorrectionAtMs
    if (last != null && nowMs - last < LIVE_EDGE_COOLDOWN_MS) return hold
    if (currentLiveOffsetMs <= LIVE_EDGE_RECOVERED_MS) {
        return LiveEdgeDecision(seekToLive = false, history = LiveEdgeHistory())
    }
    if (shouldSeekToLive(currentLiveOffsetMs, LIVE_EDGE_TRIGGER_MS) &&
        history.consecutiveCorrections < LIVE_EDGE_MAX_CONSECUTIVE_CORRECTIONS
    ) {
        return LiveEdgeDecision(
            seekToLive = true,
            history = LiveEdgeHistory(nowMs, history.consecutiveCorrections + 1),
        )
    }
    // Inside the hysteresis band, or at the bound.
    return hold
}
