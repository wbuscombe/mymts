package com.mymts.data.settings

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The four view-tunable LADDERS — the native mirror of the web/helper scale model.
 *
 * **These values are a MIRROR, not a native design.** They are copied rung-for-rung from
 * `helper/src/mymts_helper/wall/store.py` (the server-side source of truth) and
 * `web/js/wallConfig.mjs` (the web client's copy), so **step N means the same thing on
 * the TV as it does on the rendered wall and in `/control/`**. Changing a value here
 * without changing it in both of those breaks that promise — `WallScaleStepsTest` pins
 * the literals so a one-sided edit fails the build rather than silently diverging.
 *
 * Why steps rather than a continuous slider: this is a D-pad remote. Ten discrete rungs
 * are traversed end-to-end in ten presses, each one lands on a value that was chosen and
 * verified, and the legibility floor is asserted by rung 1 rather than by a slider's
 * lower bound. That was already the native philosophy (the retired
 * [FeedWidth]/[FeedFontScale] 3-preset enums); PR-026 keeps the philosophy and adopts the
 * web's resolution and range.
 *
 * The endpoints were derived on the web side from what actually renders at 1920×1080 and
 * 1280×720 rather than picked as round numbers — see ARCHITECTURE §44 for the
 * measurements, including the two candidates that were rendered and rejected.
 */
object WallScaleSteps {

    const val MIN = 1
    const val MAX = 10

    /** Step 5 is the default on every control, matching web exactly. */
    const val DEFAULT = 5

    /**
     * Feed pane width. The web ladder is a PERCENT of the wall; native needs a fraction
     * for `Modifier.fillMaxWidth(...)`, so [feedWidthFraction] divides by 100. Same
     * ladder, expressed in the unit each platform's layout takes.
     *
     * Rung 2 (18) and rung 5 (32) sit exactly on the web range's old minimum and default.
     */
    val FEED_WIDTH_PCT = intArrayOf(12, 18, 23, 27, 32, 38, 44, 50, 57, 64)

    /** Multiplier on the feed pane's text sizes. Rung 1 is the measured legibility floor. */
    val FEED_TEXT = floatArrayOf(0.60f, 0.70f, 0.80f, 0.90f, 1.00f, 1.15f, 1.32f, 1.52f, 1.75f, 2.00f)

    /**
     * Ticker BOX scale (bar height, gaps, paddings, corner radii) and ticker TEXT scale
     * (font sizes + their letter spacing) — deliberately independent, so a tall bar with
     * small text (or the reverse) is expressible.
     *
     * The two ladders are IDENTICAL from rung 4 up, so matching steps reproduce the old
     * single-unit proportional feel (taller bar ⇒ bigger text) exactly. They diverge only
     * at rungs 1–3, the one place a measured constraint forces it: the text has a
     * legibility floor the box does not.
     */
    val TICKER_HEIGHT = floatArrayOf(0.45f, 0.58f, 0.70f, 0.85f, 1.00f, 1.25f, 1.55f, 1.90f, 2.30f, 2.75f)
    val TICKER_TEXT = floatArrayOf(0.55f, 0.62f, 0.72f, 0.85f, 1.00f, 1.25f, 1.55f, 1.90f, 2.30f, 2.75f)

    /** Clamp any int to a legal rung. Absent/garbage upstream should pass [DEFAULT]. */
    fun clampStep(step: Int): Int = step.coerceIn(MIN, MAX)

    /** Nudge a step by ±1, CLAMPED (not wrapped): a D-pad held at an end must stop there,
     *  not silently jump from widest to narrowest. */
    fun nudge(step: Int, delta: Int): Int = clampStep(clampStep(step) + delta)

    fun feedWidthFraction(step: Int): Float = FEED_WIDTH_PCT[clampStep(step) - 1] / 100f
    fun feedWidthPct(step: Int): Int = FEED_WIDTH_PCT[clampStep(step) - 1]
    fun feedTextScale(step: Int): Float = FEED_TEXT[clampStep(step) - 1]
    fun tickerHeightScale(step: Int): Float = TICKER_HEIGHT[clampStep(step) - 1]
    fun tickerTextScale(step: Int): Float = TICKER_TEXT[clampStep(step) - 1]

    /** The operator-facing label for a rung: the STEP is what they set, the resolved value
     *  is the honest hint about what it means. Mirrors `/control/`'s "5 · 32%" format so
     *  the two surfaces read the same. */
    fun feedWidthLabel(step: Int): String = "${clampStep(step)} · ${feedWidthPct(step)}%"
    fun scaleLabel(step: Int, value: Float): String =
        "${clampStep(step)} · ${(value * 100).roundToInt() / 100.0}×"

    /**
     * MIGRATION: the 1-based rung whose value is closest to a legacy continuous value.
     * Ties go to the LOWER rung (`<` keeps the first index at equal distance), so the
     * mapping is deterministic and therefore idempotent.
     */
    fun nearestStep(value: Float, ladder: FloatArray): Int {
        var best = 0
        for (i in ladder.indices) {
            if (abs(ladder[i] - value) < abs(ladder[best] - value)) best = i
        }
        return best + 1
    }

    fun nearestStep(value: Int, ladder: IntArray): Int {
        var best = 0
        for (i in ladder.indices) {
            if (abs(ladder[i] - value) < abs(ladder[best] - value)) best = i
        }
        return best + 1
    }
}
