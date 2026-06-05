package com.mymts.data.settings

/**
 * On-device, operator-tunable wall layout settings.
 *
 * The UX & Config chapter (2026-06-04) adds three operator-facing knobs
 * for the wall layout: how wide the feed pane is, how big its type
 * runs, and which side of the wall it lives on. The values are
 * discrete presets — discrete steps survive D-pad cycling cleanly and
 * each preset can be tuned in code with intent (e.g. the legibility
 * floor for the font scale is asserted by the smallest preset, never
 * by a slider's lower bound).
 *
 * These settings are persisted by `LineupStore` alongside lineup
 * overrides — same SharedPreferences container, same on-device-only
 * discipline. The persisted format is plain integers and strings; no
 * secrets, no PII, no absolute paths.
 *
 * The settings reach the focus model via `feedSide`: when the operator
 * swaps the feed to the right, the model's LEFT/RIGHT spatial rules
 * must follow so navigation still feels spatially consistent (a
 * RIGHT-from-feed in the swapped layout still leads out of the feed
 * toward the grid). The `WallFocusModelTest` suite covers both
 * orientations with separate tests.
 */
data class WallSettings(
    val feedWidth: FeedWidth = FeedWidth.Default,
    val feedFontScale: FeedFontScale = FeedFontScale.Default,
    val feedSide: FeedSide = FeedSide.Left,
) {
    companion object {
        val Default: WallSettings = WallSettings()
    }
}

/**
 * Width of the feed pane, expressed as a fraction of the wall's
 * horizontal extent. The grid fills the remainder (it already
 * autofits its space).
 *
 * Presets were chosen for the operator's "is the feed too narrow to
 * skim from 10 ft / too wide and stealing video real estate" trade —
 * each value is a meaningful step the operator can feel at a glance
 * from the couch, not a slider tweak.
 */
enum class FeedWidth(val fraction: Float, val displayName: String) {
    Narrow(0.22f, "Narrow"),
    Default(0.28f, "Default"),
    Wide(0.36f, "Wide"),
}

/**
 * Multiplier applied to the feed pane's text sizes. The smallest
 * preset is the **legibility floor** — we deliberately don't expose
 * a smaller scale, since anything smaller risks the operator squinting
 * from across the room and is hostile to the wall's "calm + readable
 * at 10 ft" stance (Vision §4).
 *
 * The expanded-article body text and the section-header type all
 * scale together so the visual hierarchy is preserved at every preset.
 */
enum class FeedFontScale(val multiplier: Float, val displayName: String) {
    Small(0.88f, "Small"),
    Default(1.00f, "Default"),
    Large(1.18f, "Large"),
}

/**
 * Which side of the wall the feed pane occupies. Default is `Left`
 * (the original Stage 3 layout); `Right` flips the feed to the right
 * edge and moves the grid to the left.
 *
 * Switching sides has two cascading effects:
 *   1. The wall lays out `Row { Feed; Grid }` vs `Row { Grid; Feed }`.
 *   2. The focus model's LEFT/RIGHT spatial rules invert
 *      (`WallFocusModel.apply(feedSide = ...)`): RIGHT-from-feed (left
 *      layout) opens the menu; LEFT-from-feed (right layout) opens
 *      the menu — the gesture always means "off the feed's outer
 *      edge."
 * Both effects are derived from this enum at the call site so the
 * two never disagree.
 */
enum class FeedSide(val displayName: String) {
    Left("Feed Left"),
    Right("Feed Right"),
}

internal fun feedWidthFromOrdinal(ordinal: Int): FeedWidth =
    FeedWidth.values().getOrNull(ordinal) ?: FeedWidth.Default

internal fun feedFontScaleFromOrdinal(ordinal: Int): FeedFontScale =
    FeedFontScale.values().getOrNull(ordinal) ?: FeedFontScale.Default

internal fun feedSideFromOrdinal(ordinal: Int): FeedSide =
    FeedSide.values().getOrNull(ordinal) ?: FeedSide.Left
