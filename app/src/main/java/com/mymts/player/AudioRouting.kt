package com.mymts.player

/**
 * Pure single-audible-tile audio routing — no Media3, no Android, so it's
 * JVM-unit-testable. The wall plays at most ONE tile's audio at a time
 * ([audioEnabled]); selecting a tile moves audio to it, re-selecting it mutes
 * the wall ([nextAudible]). Mirrors the web client's `nextAudible`/`isAudible`.
 *
 * The renderer-enable decision drives [StreamPlayer.setAudible], which DISABLES
 * the audio renderer (stops decoding) on inaudible tiles — so the default state
 * (no audible slot, -1) leaves every tile's audio renderer off.
 */
object AudioRouting {

    /** No tile audible — the wall starts (and resets) here. */
    const val NONE: Int = -1

    /**
     * The next audible slot when the operator taps slot [clicked], given the
     * [current] audible slot. Tapping the already-audible slot mutes the wall
     * ([NONE]); tapping any other slot moves audio to it. A negative [clicked]
     * is ignored (returns [current]).
     */
    fun nextAudible(current: Int, clicked: Int): Int = when {
        clicked < 0 -> current
        current == clicked -> NONE
        else -> clicked
    }

    /**
     * Whether slot [index]'s audio renderer should be ENABLED (decode + play).
     * True for exactly the single [audibleSlot]; false for every other slot and
     * for all slots when [audibleSlot] is [NONE] (the muted-wall default).
     */
    fun audioEnabled(audibleSlot: Int, index: Int): Boolean =
        index >= 0 && index == audibleSlot
}
