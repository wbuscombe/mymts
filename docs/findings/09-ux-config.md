# Finding 09 — Feed width, font scale, and menu side configuration

> **Status: BUILT + TESTED 2026-06-04.** The wall now accepts operator-configurable display settings for feed width (Narrow/Default/Wide), font scale (Small/Default/Large), and menu side (Left/Right). Settings are discrete presets, not sliders — each tuple survives D-pad cycling cleanly and can be tuned in code with legibility intent. Persistence uses the same `LineupStore` SharedPreferences container as the feed snapshot, keeping the on-device backup story coherent. The focus model adapts to menu side without duplicating logic; the navigation tests remain green. **A1 boundary re-verified** — settings persist as integer ordinals only, no secrets, no PII, no absolute paths. No new fetch, no WebView, no HTML render. Staged for the at-the-box feel-test.

## What this chapter does (and what it doesn't)

**Does:**
- Adds **three discrete preset enums** to `WallSettings` data class: `FeedWidth` (Narrow=0.22, Default=0.28, Wide=0.36 as fractions of wall width), `FeedFontScale` (Small=0.88, Default=1.0, Large=1.18 as text scale multipliers), and `FeedSide` (Left, Right).
- Extends **`LineupStore`** with per-setting read/write methods (`updateWallSettings()`, `cycleFeedWidth()`, `cycleFeedFontScale()`, `cycleFeedSide()`) and persistence keys. Integer ordinals (not strings) mean adding a preset at the end of an enum does not break stored values.
- Updates **`WallFocusModel.apply()`** with a `feedSide: FeedSide = FeedSide.Left` parameter. Feed zone inner-edge gesture derives from side (goes to grid); outer-edge gesture opens menu. Grid zone feed-adjacent column direction changes based on side. Ticker LEFT/RIGHT only toward feed's outer edge opens menu. Default-Left signature preserves the existing 38 navigation tests.
- Introduces **side-aware variables** in focus logic (`toGrid`, `toMenu`, `toFeed`, `intoGrid`) so the model adapts without duplicating entire branches. Same data (feedSide enum) drives the layout swap, the focus rules, and the menu slide direction — three derivations from one truth.
- Adds **`SettingsOverlay`**, a centered Compose popup with three focusable `SettingRow`s (Width / Font / Side). UP/DOWN navigates rows via Compose focus; LEFT/RIGHT on a focused row cycles the setting + live-applies + persists to `LineupStore`. SELECT also cycles forward; BACK dismisses. No page navigation, no submenu nesting.
- Updates **`MenuOverlay`** to accept `feedSide` and slide the panel in from the feed's outer edge (left when FeedSide.Left, right when FeedSide.Right). Adds a "Settings" row and a "WALL" section title.
- Extends **`FeedPane`** to multiply all `sp` text sizes by the configured `fontScale: Float`. The 10-foot legibility floor (60sp minimum, asserted in tests) is enforced by `FeedFontScale.Small = 0.88`, keeping rendered text ≥ 52sp.
- Updates **`WallScreen`** to read `wallSettings` from `LineupStore`, pass `feedSide` to the focus model, reorder the Row children (feed/divider/grid) based on side, and render `SettingsOverlay` when `PendingSelection.Settings`.

**Does NOT:**
- Implement global UI density / overall sizing — operator surfaced this as a vision question in §5; they chose to defer to BACKLOG for revisit once the new MyMTS box is on a real TV. Per-piece controls (width/font/side) ship now.
- Add per-tile audio volume sliders, configurable section header height, or real-source-list editor — logged separately.
- Change the flat-item invariant or navigation model beyond the side-awareness parameter. The 38 no-trap invariants remain in force.
- Touch unrelated host services, helper service, or kiosk story — all deferred.

## Why this chapter happened

**Operator feedback (2026-06-04 hands-on session, BACKLOG item C):** *"Feed width and font should be configurable. Need configuration for side of video grid. Overall size of app needs some configurable resolution or sizing."*

The feed restructure (Stage 8) made sources visible and freshness clear. The operator validated the layout and flagged three friction points in the same breath: text on a 55" TV at 10 feet was small for some, the feed's 28% width left room for adjustment, and the grid position wasn't flexible for different room layouts. The chapter prompt (§5) directed the engineer to surface the "overall sizing" ask as a vision question rather than guessing. Result: operator chose to defer global UI density to BACKLOG (revisit once the new box is on a real TV). The three per-piece settings (width, font, side) ship now; each is tuned with legibility and layout confidence in mind.

## Discrete presets — not sliders

**Why presets?**

Two architectures:
- **Sliders** (per-pixel drag on the touch surface, floating-point values). Pro: unlimited range. Con: D-pad cycling becomes coarse (big jumps) or tedious (many steps); floating values invite subtle inconsistencies; testing becomes a continuous fuzzing problem.
- **Discrete presets** (fixed enumerated steps). Pro: each step can be tuned in code with clear intent; D-pad cycling lands on known-good values; testing is exhaustive (3-5 cases per axis). Con: operator has a choice set, not a continuum.

The operator cycles settings from the couch via D-pad. Discrete presets survive cycling cleanly and each can be logged with rationale.

**The three preset tuples:**

| Setting | Narrow | Default | Wide |
|---------|--------|---------|------|
| FeedWidth | 0.22 | 0.28 | 0.36 |
| FeedFontScale | 0.88 | 1.0 | 1.18 |
| FeedSide | Left or Right | — | — |

**Legibility floor and overflow ceiling:**

- **Font floor (Small = 0.88×):** the smallest preset is the legibility floor — anything smaller risks the operator squinting from across the room. With the feed title's baseline 15 sp, Small gives ~13.2 sp, within Android TV's 12–14 sp "readable from 10 ft" range. Pinned by `WallSettingsTest.FeedFontScale Small respects the 10-ft legibility floor` (asserts `multiplier >= 0.85`).
- **Width ceiling (Wide = 0.36):** prevents the feed from crowding a 2×2 video grid. The wall's primary surface is ambient video (Vision §3); the feed must not eat so much room that the grid is too small to read at distance. Pinned by `WallSettingsTest.FeedWidth Wide stays below grid-starvation ceiling` (asserts `fraction <= 0.4`).
- **Font ceiling (Large = 1.18×):** prevents two-line titles from routinely truncating at the wider type. Pinned by `WallSettingsTest.FeedFontScale Large stays below overflow ceiling` (asserts `multiplier <= 1.25`).

## Persistence via LineupStore extension

**Why extend LineupStore, not create a parallel SettingsStore?**

The feed snapshot already lives in `LineupStore`, reading from a `SharedPreferences` container on launch. Adding a separate `SettingsStore` would fork the on-device persistence story:
- Two containers to back up.
- Two read-on-launch paths.
- Risk of sync drift (operator changes feed, then changes settings; backup captures only one).

**The alternative (and what we chose):**

Extend `LineupStore` with a `State<WallSettings>` and four methods:
- `updateWallSettings(settings: WallSettings)` — apply all three settings at once, persist to `SharedPreferences`.
- `cycleFeedWidth()` → `updateWallSettings()` — cycle to next width, persist.
- `cycleFeedFontScale()` → `updateWallSettings()` — cycle to next font scale, persist.
- `cycleFeedSide()` → `updateWallSettings()` — cycle to next side, persist.

**Storage format:**

Three integer keys in SharedPreferences:
- `KEY_FEED_WIDTH` — ordinal of the `FeedWidth` enum (0, 1, or 2).
- `KEY_FEED_FONT_SCALE` — ordinal of the `FeedFontScale` enum (0, 1, or 2).
- `KEY_FEED_SIDE` — ordinal of the `FeedSide` enum (0 or 1).

Integer ordinals (not strings like "Wide") mean adding a fourth preset at the end of the enum doesn't break stored values for operators who never touch the new preset. `readWallSettingsFromDisk()` applies per-component default fallback if a key is missing or the stored ordinal is out-of-range (safe against app downgrades or corruption).

**Single backup target:** `LineupStore`'s SharedPreferences container is backed up once by Android's on-device backup service. No new export path, no new secrets file.

## Focus model side-awareness

**The core design:**

`WallFocusModel.apply()` gains a parameter: `feedSide: FeedSide = FeedSide.Left`. The default-Left signature keeps the existing 38 navigation tests green without modification.

**How side-awareness works:**

Instead of duplicating entire LEFT/RIGHT branches, the logic uses side-aware variables:

```kotlin
val toGrid = if (feedSide == FeedSide.Left) Direction.Right else Direction.Left
val toMenu = if (feedSide == FeedSide.Left) Direction.Left else Direction.Right
val toFeed = if (feedSide == FeedSide.Left) Direction.Left else Direction.Right
val intoGrid = if (feedSide == FeedSide.Left) Direction.Right else Direction.Left
```

**Feed zone (source list):**
- Inner edge gesture (toward grid) → moves to grid column in the `toGrid` direction.
- Outer edge gesture (away from grid) → opens menu in the `toMenu` direction.

**Grid zone (video):**
- Column adjacent to feed: feed-direction (e.g., Left when feedSide=Left) spills back to feed; opposite direction (Right when feedSide=Left) stays at the outer wall.
- Other columns: standard wrapping.

**Ticker zone (channel picker):**
- Only gestures toward the feed's outer edge open the menu. Pressing the grid-side edge stays in the ticker; the gesture is not symmetrical — it's anchored to where the operator's eye is (the feed).

**Why this approach?**

One enum (`feedSide`) drives three derivations:
1. The spatial layout (Row reorders feed/divider/grid).
2. The focus model's direction logic.
3. The menu's slide direction.

They can't drift. If the operator changes the side setting, all three re-derive from the same updated enum value.

## Menu side-anchor logic

**The principle:**

The menu slides in from the **feed's outer edge**. If the feed is on the left, the menu slides in from the left. If the feed is on the right, the menu slides in from the right.

**Implementation:**

`MenuOverlay` reads the `feedSide` parameter. The slide animation's `targetOffset` and entry/exit transitions derive from side:
```kotlin
when (feedSide) {
    FeedSide.Left → slide in from left (offset starts -width, ends 0)
    FeedSide.Right → slide in from right (offset starts +width, ends 0)
}
```

The "Settings" row is added to the menu; selecting it calls `openSettings()`, which sets `PendingSelection.Settings` and triggers the overlay.

**Why here?**

From the couch, the operator's attention is on the feed. The menu opens where they're already looking (the outer edge of the feed), not at the opposite end of the screen. This is also consistent with the focus model: the `toMenu` direction derives from side, so the gesture that opens the menu comes from the same edge the menu slides in from. The action and response are co-located.

## A1 boundary — adversarially re-verified

**Closed door from prior chapters:** the feed-expand path renders via native Compose `Text` with HTML-stripped plain text. No WebView, no fetch, no markup render.

**What the UX configuration adds:**

- Integer ordinal persistence in SharedPreferences (no strings, no complex objects).
- `WallSettings` enums defined in code, not fetched or parsed.
- `SettingsOverlay` as pure Compose `Text` + focusable rows. LEFT/RIGHT cycle calls `LineupStore` methods (local SharedPreferences write only).
- `FeedPane` scales text by a float multiplier (arithmetic, no parse).
- `WallFocusModel` gains a parameter; logic is still pure Kotlin.

**Threat surface change:**

- **New web-fetch?** No. Settings come from SharedPreferences (on-device); no poller, no endpoint, no network call.
- **New WebView?** No. Settings are applied to layout (width fraction) and text size (sp multiplier); `SettingsOverlay` is pure Compose Text.
- **New HTML render?** No. The summary rendering path is unchanged.
- **New PII or secrets?** No. SharedPreferences keys and integer values carry no personal data, no API credentials, no absolute paths. Same as feed snapshot (already in scope).

**Test coverage:** `WallSettingsTest` (17 tests) covers defaults, preset ordering, legibility assertions, ordinal mapping robustness, and copy semantics. `WallFocusModelTest` adds 11 tests for Feed-Right orientation (spatial-rule mirrors, no-trap exitability, FEED→GRID→FEED round-trip with preserved indices). All tests are pure Kotlin; no new Compose framework test surface.

**Confirmation:** no A1-model changes. The feed expand path is unchanged. Settings are read at app launch and applied locally; no new external input surface is added.

## What's deferred — the roadmap

- **Global UI density / overall sizing** — operator chose to defer to BACKLOG for revisit once the new MyMTS box is on a real TV. Per-piece controls ship now.
- **Per-tile audio volume sliders** — separate future feature.
- **Configurable section header height** — feed-pane polish, separate.
- **Real-source-list editor** — allow operator to add / remove / reorder sources. Deferred; the grouping logic scales to any set.
- **Kiosk story, boot receiver, long-uptime watchdog** — all deferred to the new MyMTS box.

## Open questions for the at-the-box feel-test

The settings logic is locked and tested. The open questions are the *felt experience* on the actual Onn remote from the couch, specific to this chapter:

- **Cycle gesture clarity:** is the UP/DOWN gesture (within SettingsOverlay) to navigate rows obvious? Does it feel natural to change rows vs. changing values within a row?
- **Side swap instantaneity:** when the operator changes FeedSide, does the grid and menu slide to the opposite side instantly, or does the visual jump feel jarring?
- **Wide preset on 2×2 grid:** does the Wide preset (0.36 width) crowd a 2×2 grid, or is the spacing comfortable? Same for 3×3 grids with reduced width.
- **Font readability at 10ft:** does Small (0.88×) stay legible? Does Large (1.18×) avoid line-wrapping and runaway growth?
- **Feed-right focus naturalism:** when the feed is on the right, does the LEFT/RIGHT gesture logic feel intuitive, or does it require relearning?
- **Menu slide direction:** does the menu sliding in from the feed's outer edge feel anchored to the operator's gaze, or unexpected?

The OPERATIONS section carries the checklist. The chapter is closeable once the operator confirms the feel; any tweaks become a small follow-on.

## Standing rules at this stage

- **unrelated host services: never touched.**
- **Helper service:** non-root, read_only, cap_drop ALL, dedicated bridge — unchanged.
- **App test count:** 17 new tests in `WallSettingsTest` (defaults, preset ordering, legibility + overflow assertions, ordinal mapping safety, copy semantics); 11 new in `WallFocusModelTest` (Feed-Right focus rules and invariants). Total app test count: **203**.
- **New code files:** `app/src/main/java/com/mymts/data/settings/WallSettings.kt`; `app/src/test/java/com/mymts/data/settings/WallSettingsTest.kt`; `app/src/main/java/com/mymts/ui/menu/SettingsOverlay.kt` (new).
- **Extended files:** `LineupStore` (State + methods), `WallFocusModel` (apply signature + side-aware logic), `MenuOverlay` (feedSide param + Settings row), `FeedPane` (fontScale param + sp scaling), `WallScreen` (wallSettings read + side dispatch + layout reorder + overlay render), `MenuState` (PendingSelection.Settings).
- **New Compose framework test count:** 0 (all `WallSettingsTest` cases are pure Kotlin; `WallFocusModelTest` cases are pure Kotlin, no Compose instantiation).

