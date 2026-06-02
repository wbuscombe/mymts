package com.mymts.soak

import android.util.Log

/**
 * Single tagged logcat channel for the soak harness. The host-side script
 * (`scripts/soak.sh`) tails this tag, parses pipe-delimited fields, and
 * writes CSV. Keeping the format simple-and-pinned means the parser is a
 * one-liner — and any drift in the line format is a CI-detectable
 * regression (see tests/SoakLogFormatTest).
 *
 * Lines are pipe-delimited because pipes don't show up in HLS URLs or
 * decoder names, but commas do (so CSV-quoting would be needed for some
 * fields). The host-side parser does the CSV write.
 *
 * Field order is fixed; new fields go at the end of a line so old parsers
 * don't break. Field types are pure ASCII.
 */
object SoakLog {
    const val TAG = "MYMTS_SOAK"

    fun start(tiles: Int, resolutionHint: String) =
        Log.i(TAG, "EV=START|tiles=$tiles|resolution=$resolutionHint")

    fun tileMount(id: String, url: String) =
        Log.i(TAG, "EV=TILE_MOUNT|id=$id|url=$url")

    fun tileReady(id: String) =
        Log.i(TAG, "EV=TILE_READY|id=$id|ts_ms=${System.currentTimeMillis()}")

    fun decoderInit(id: String, decoder: String, initMs: Long) =
        Log.i(TAG, "EV=DECODER|id=$id|decoder=$decoder|init_ms=$initMs")

    fun droppedFrames(id: String, dropped: Int, elapsedMs: Long) =
        Log.w(TAG, "EV=DROPPED|id=$id|dropped=$dropped|elapsed_ms=$elapsedMs")

    fun error(id: String, code: String, message: String) =
        Log.w(TAG, "EV=ERROR|id=$id|code=$code|msg=${sanitize(message)}")

    /** State machine transition. `from` and `to` are LivenessTracker.State names. */
    fun stateChange(id: String, from: String, to: String) =
        Log.i(TAG, "EV=STATE|id=$id|from=$from|to=$to|ts_ms=${System.currentTimeMillis()}")

    /** A recovery strike is starting. `kind` is "prepare" or "reinit". */
    fun recoveryStrike(id: String, attempt: Int, kind: String) =
        Log.w(TAG, "EV=RECOVERY|id=$id|attempt=$attempt|kind=$kind|ts_ms=${System.currentTimeMillis()}")

    /** Recovery exhausted; tile is now in honest DEAD state. */
    fun settledDead(id: String, attempts: Int) =
        Log.w(TAG, "EV=DEAD|id=$id|attempts=$attempts|ts_ms=${System.currentTimeMillis()}")

    fun heartbeat(
        idx: Int,
        id: String,
        state: String,
        isPlaying: Boolean,
        positionMs: Long,
        dropped: Int,
        lastFrameAtMs: Long,
    ) {
        // last_frame_age_ms is misleading on its own: ExoPlayer's
        // onRenderedFirstFrame only fires on initial render and after variant
        // switches, not on every frame. So a large value can mean "the player
        // has been steadily rendering a single variant for a while" rather
        // than "the surface is stale." Pair with isPlaying + position_ms (the
        // current playback head) to know whether the surface is actually
        // advancing.
        val age = if (lastFrameAtMs > 0) System.currentTimeMillis() - lastFrameAtMs else -1
        Log.i(
            TAG,
            "EV=BEAT|idx=$idx|id=$id|state=$state|playing=$isPlaying|" +
                "pos_ms=$positionMs|dropped=$dropped|last_frame_age_ms=$age",
        )
    }

    private fun sanitize(s: String): String =
        s.replace('|', '/').replace('\n', ' ')
}
