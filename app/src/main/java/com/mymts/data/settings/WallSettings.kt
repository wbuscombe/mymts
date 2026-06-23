package com.mymts.data.settings

import androidx.compose.runtime.Immutable

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
 *
 * `@Immutable` (P-N2): every field is a primitive, an enum, or a read-only `Set`
 * that is REBUILT (never mutated) on any change, so the object's observable value
 * is fixed once constructed. Compose infers a `data class` with `Set` fields as
 * UNSTABLE (the `Set` interface isn't a known-stable type), which makes composables
 * that take a `WallSettings` (or one of its `Set` fields) recompose even when their
 * value didn't change. The annotation restores correct skipping — a settings nudge
 * (a new `WallSettings`) only recomposes consumers whose specific value differs.
 */
@Immutable
data class WallSettings(
    val feedWidth: FeedWidth = FeedWidth.Default,
    val feedFontScale: FeedFontScale = FeedFontScale.Default,
    val feedSide: FeedSide = FeedSide.Left,
    // Feed-filtering chapter (2026-06-06). `hiddenSources` is a DENYLIST
    // of source labels the operator has switched off — stored as "hide
    // these" (not "show these") so a NEWLY-added feed source shows by
    // default rather than being silently hidden. `feedRecency` narrows
    // the feed to a time window. Both operate purely on the already-
    // fetched plain-text items (no new fetch; A1 holds).
    val hiddenSources: Set<String> = emptySet(),
    // News-genre-groups chapter (Part E, 2026-06-22). `hiddenGenres` is a
    // DENYLIST of feed GENRES the operator switched off — the top level of the
    // two-level News filter (`FeedGenres` → US News / Global News / Business /
    // Sports). A genre in this set hides ALL its sources from the feed,
    // overriding any per-source state. Stored as "hide these" (like
    // `hiddenSources`) so a newly-added genre shows by default. Composes with
    // the per-source levers: a source contributes iff its genre is NOT here AND
    // (non-sports) its label ∉ `hiddenSources` / (sports) its league ∉
    // `hiddenLeagues`. See `FeedListBuilder.applyFilters`.
    val hiddenGenres: Set<String> = emptySet(),
    val feedRecency: FeedRecency = FeedRecency.All,
    // Curation & preferences chapter (2026-06-06). `hiddenLeagues` is a
    // DENYLIST of sports-league labels the operator has switched off in
    // the ticker's sports mode (filtered TV-side; the helper still
    // serves all leagues). `tickerNewsEnabled` adds news as a third
    // ticker rotation mode (markets → sports → news) — **default OFF**,
    // a FEEL-TEST item the operator confirms after using the wall.
    val hiddenLeagues: Set<String> = emptySet(),
    val tickerNewsEnabled: Boolean = false,
    // Panel-fit chapter (2026-06-07). `uiScale` is a single global
    // density multiplier applied to the WHOLE wall (ticker, feed, grid
    // chrome, labels) via a LocalDensity override — the lever to shrink
    // everything together when the content is simply too big for the
    // panel. `overscan` is a safe-area inset (fraction per edge) so
    // content doesn't clip on a panel that overscans; default 5% is the
    // TV action-safe standard (a panel that doesn't overscan just gets a
    // small black border, an honest cost for a panel-agnostic default).
    val uiScale: UiScale = UiScale.Default,
    val overscan: Overscan = Overscan.Medium,
    // Position offset (panel-fit, 2026-06-09): nudge the WHOLE wall by these
    // dp in REAL screen space to recenter a panel that overscans
    // asymmetrically / shifts the image and has no hardware menu to adjust
    // itself. Display size (scale) + Overscan inset are symmetric/centered —
    // they can't recenter a shifted image; this can. Default 0,0 (no nudge —
    // correct on a clean/centered panel). Clamped to ±[OFFSET_RANGE_DP].
    val offsetXDp: Int = 0,
    val offsetYDp: Int = 0,
    // Fit scale (panel-fit, 2026-06-10): a uniform downscale of the WHOLE wall
    // ANCHORED AT THE TOP-LEFT corner (graphicsLayer scale with
    // transformOrigin = top-left, in WallScreen). This is the lever for a panel
    // that renders the wall larger than its visible area FROM A TOP-LEFT ORIGIN
    // (top-left seated correctly, bottom-right overflowing off-screen) — shrink
    // toward the pinned top-left until the bottom-right comes into view; black
    // fills the freed bottom/right. Distinct from `overscan` (which insets
    // toward CENTRE, the wrong anchor here) and `uiScale` (which resizes chrome
    // but not the footprint). Stored as a percent; 100 = no scale. See
    // [FIT_SCALE_MIN_PCT]..[FIT_SCALE_MAX_PCT].
    val fitScalePct: Int = 100,
    // Vertical stretch (panel-fit, 2026-06-10): an EXTRA height-only scale on
    // top of [fitScalePct], anchored top-left, to close a residual black band
    // at the BOTTOM after a uniform Fit scale has seated the sides edge-to-edge
    // (growing Fit scale itself would push the sides off — this stretches height
    // ONLY). Slight aspect distortion (content a touch taller); the operator
    // dials the minimum needed. Default 100 (no stretch) so it's panel-specific
    // and never affects a display that doesn't need it. See [FIT_STRETCH_Y_MIN_PCT].
    val fitStretchYPct: Int = 100,
    // Calibration border (panel-fit aid, 2026-06-09). A toggleable bright
    // outline + labelled corners (TL/TR/BL/BR) drawn at the EXACT wall edge,
    // so an operator can SEE which edges the panel's overscan is cropping —
    // the catch-22 is you can't otherwise tell what's off-screen. Turn on,
    // raise the inset / nudge the offset until all four corners + the whole
    // border are visible, then turn off. Default OFF (it's a diagnostic).
    val calibrationBorder: Boolean = false,
    // Video grid dimensions (2026-06-11): independent ROWS × COLUMNS, each 1–3
    // (so 2×2, 2×3, 1×3, 3×2, … up to 3×3 = 9). Default 2×2 on the Onn box. The
    // measured-area cell layout is grid-agnostic, so any R×C lays out cleanly
    // (each cell a [video + label] unit in the safe area). The operator's
    // per-slot channel choices (overrides, keyed by slot index) survive an R×C
    // change for the slots that still exist. Cell count = rows × cols.
    val gridRows: Int = 2,
    val gridCols: Int = 2,
    // Ticker speed, as percentages adjusted by D-pad LEFT/RIGHT sliders; higher =
    // faster, lower = slower, clamped to [TICKER_SPEED_MIN_PCT]..MAX.
    // `tickerScrollPct` scales the crawl/marquee velocity (× CRAWL_BASE_DP_PER_SEC)
    // AND the flip's per-page reveal; `tickerFlipPct` scales the page-flip dwell
    // (higher = shorter dwell). The scroll default is a calm mid-speed
    // ([TICKER_SPEED_DEFAULT_PCT]) — the old 100% default ran too fast for 10 ft.
    // Live-applied + persisted.
    val tickerScrollPct: Int = TICKER_SPEED_DEFAULT_PCT,
    val tickerFlipPct: Int = 100,
    // Ticker MOTION (2026-06-13, cross-platform parity). The strip's motion is
    // an explicit setting offered on BOTH platforms; only the per-platform
    // DEFAULT differs. `Flip` is the TV wall's established paged vertical flip
    // (paced by `tickerFlipPct`); `Crawl` is the web wall's continuous
    // horizontal marquee (paced by `tickerScrollPct`). Default `Flip` preserves
    // the TV's established feel; the web client mirrors this setting and
    // defaults to `Crawl`. Live-applied + persisted.
    val tickerMotion: TickerMotion = TickerMotion.Flip,
) {
    /** Total cells the wall shows — drives the slot resolver + the layout. */
    val gridCells: Int get() = gridRows * gridCols

    companion object {
        val Default: WallSettings = WallSettings()
    }
}

/** Grid rows/cols are each clamped to this range (1–3 → up to a 3×3 = 9 grid). */
const val GRID_DIM_MIN = 1
const val GRID_DIM_MAX = 3

/** Clamp a stored/edited grid dimension into [GRID_DIM_MIN]..[GRID_DIM_MAX]. */
internal fun clampGridDim(value: Int): Int = value.coerceIn(GRID_DIM_MIN, GRID_DIM_MAX)

/** Ticker-speed slider range + step + default (D-pad LEFT/RIGHT). The percent
 *  scales the crawl/marquee velocity (and the flip's per-page reveal); the FLOOR
 *  is deliberately low (10%) so the slow end is a genuinely calm, readable crawl
 *  at 10 ft, and the default sits comfortably below the old too-fast value — a
 *  usable slow→fast range the operator dials in-app, no redeploy to chase a speed.
 *  The crawl's absolute base lives in `TickerStrip.CRAWL_BASE_DP_PER_SEC`. */
const val TICKER_SPEED_MIN_PCT = 10
const val TICKER_SPEED_MAX_PCT = 200
const val TICKER_SPEED_STEP_PCT = 10
const val TICKER_SPEED_DEFAULT_PCT = 50

/** Clamp a ticker-speed percentage into [TICKER_SPEED_MIN_PCT]..[TICKER_SPEED_MAX_PCT]. */
internal fun clampTickerSpeedPct(value: Int): Int =
    value.coerceIn(TICKER_SPEED_MIN_PCT, TICKER_SPEED_MAX_PCT)

/**
 * Ticker MOTION — how the top-of-wall strip animates. A cross-platform setting
 * (both modes exist on the native wall AND the web client); only the per-
 * platform DEFAULT differs.
 *   [Flip]  — one page at a time, vertically flipping to the next on a dwell
 *             (the native wall's established motion; `tickerFlipPct` paces it).
 *             The Android DEFAULT.
 *   [Crawl] — a single continuous horizontal marquee of all the cards (the web
 *             wall's established motion; `tickerScrollPct` paces it). The web
 *             DEFAULT.
 * The operator can switch either wall to the other motion.
 */
enum class TickerMotion(val displayName: String) {
    Flip("Flip"),
    Crawl("Crawl"),
}

/** Resolve a persisted ordinal back to a [TickerMotion]; an out-of-range/corrupt
 *  ordinal falls back to the Android default ([TickerMotion.Flip]). */
internal fun tickerMotionFromOrdinal(ordinal: Int): TickerMotion =
    TickerMotion.values().getOrNull(ordinal) ?: TickerMotion.Flip

/** Position-offset bounds (dp), real screen space, symmetric around 0. The
 *  range covers a typical overscan shift (~±5% of a 1280-wide panel); the
 *  step is the per-keypress nudge the operator dials in by eye. */
const val OFFSET_RANGE_DP: Int = 64
const val OFFSET_STEP_DP: Int = 8

/** Clamp a position offset to the allowed range (defensive on read + nudge). */
fun clampOffsetDp(value: Int): Int = value.coerceIn(-OFFSET_RANGE_DP, OFFSET_RANGE_DP)

/** Top-left-anchored fit-scale bounds (percent). 100 = full size (no scale);
 *  the floor lets a badly-overscanning panel shrink the wall to ~half so the
 *  off-screen bottom-right edge comes back into the visible area. Stepped for
 *  per-keypress D-pad nudges the operator dials in by eye. */
const val FIT_SCALE_MIN_PCT: Int = 50
const val FIT_SCALE_MAX_PCT: Int = 100
const val FIT_SCALE_STEP_PCT: Int = 2

/** Clamp a fit-scale percent to the allowed range (defensive on read + nudge). */
fun clampFitScalePct(value: Int): Int = value.coerceIn(FIT_SCALE_MIN_PCT, FIT_SCALE_MAX_PCT)

/** Vertical-stretch bounds (percent of the fit-scaled height). 100 = no extra
 *  stretch; the ceiling lets a residual bottom band close (≈ the inverse of a
 *  20%-band fit scale) while capping aspect distortion. Stepped for D-pad. */
const val FIT_STRETCH_Y_MIN_PCT: Int = 100
const val FIT_STRETCH_Y_MAX_PCT: Int = 130
const val FIT_STRETCH_Y_STEP_PCT: Int = 2

/** Clamp a vertical-stretch percent to the allowed range. */
fun clampFitStretchYPct(value: Int): Int = value.coerceIn(FIT_STRETCH_Y_MIN_PCT, FIT_STRETCH_Y_MAX_PCT)

/**
 * Global UI scale — a single density multiplier applied to the entire
 * wall at once (a `LocalDensity` override in `WallScreen`), so the
 * operator can shrink/grow ALL chrome together to fit the panel. Above
 * the per-piece feed-width/feed-font controls (those still apply within
 * the scaled layout). Discrete presets cycle cleanly on the D-pad.
 * `Compact` is the shrink-to-fit lever for a too-big-for-the-panel wall.
 */
enum class UiScale(val multiplier: Float, val displayName: String) {
    Compact(0.80f, "Compact"),
    Default(1.00f, "Default"),
    Roomy(1.15f, "Roomy"),
}

/**
 * Overscan-safe inset — a fraction of the screen reserved as a margin on
 * every edge so wall content survives a panel that cuts the outer edge
 * (physical overscan, common on cheap panels / some HDMI converters; the
 * box outputs a full frame, the panel just doesn't show it all). `None`
 * is edge-to-edge (clean panels); the others inset by the named fraction
 * per edge. Default `Medium` (5%) = the classic TV action-safe margin.
 *
 * NOTE: the inset is the ONLY lever that shrinks the wall's actual footprint
 * (the wall Column fills the inset area; `UiScale`/Display-size only scales the
 * chrome WITHIN that footprint, it does not shrink the box). So on a panel with
 * severe overscan the wall is fitted by raising this inset until the footprint
 * sits inside the visible area, then nudging it into place with the position
 * offset. The high presets (`XLarge`..`Max`) exist for exactly those panels —
 * the .92 panel needed >7% on the bottom/right edges (2026-06-09).
 */
enum class Overscan(val fraction: Float, val displayName: String) {
    None(0.00f, "None"),
    Small(0.03f, "3%"),
    Medium(0.05f, "5%"),
    Large(0.07f, "7%"),
    XLarge(0.10f, "10%"),
    XXLarge(0.13f, "13%"),
    Huge(0.16f, "16%"),
    Max(0.20f, "20%"),
}

internal fun uiScaleFromOrdinal(ordinal: Int): UiScale =
    UiScale.values().getOrNull(ordinal) ?: UiScale.Default

internal fun overscanFromOrdinal(ordinal: Int): Overscan =
    Overscan.values().getOrNull(ordinal) ?: Overscan.Medium

/**
 * Recency window for the feed. `All` shows everything the helper
 * retains; the bounded windows hide items whose timestamp is older than
 * [maxAgeMs] (published time preferred, fetched time fallback — items
 * with no parseable timestamp are kept under `All` and dropped under a
 * bounded window, since we can't prove they're recent).
 */
enum class FeedRecency(val maxAgeMs: Long?, val displayName: String) {
    All(null, "All"),
    Hour(60 * 60 * 1000L, "Last hour"),
    SixHours(6 * 60 * 60 * 1000L, "Last 6h"),
    Day(24 * 60 * 60 * 1000L, "Last 24h"),
}

internal fun feedRecencyFromOrdinal(ordinal: Int): FeedRecency =
    FeedRecency.values().getOrNull(ordinal) ?: FeedRecency.All

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
