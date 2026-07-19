# Finding 07 — Whole-wall D-pad navigation (the pure focus model)

> **Status: BUILT + TESTED 2026-06-04.** The wall's entire D-pad navigation — feed, grid, ticker, menu integration — now moves through a single pure-function focus model (`WallFocusModel`), unit-tested at 38 invariant cases, with zero runtime focus traps. The model is layered cleanly: pure transitions at the bottom, per-zone actions at the surface. **Staged for the at-the-box feel-test** — the navigation graph is locked; the real-remote D-pad confirmation is the operator's call when next at the box (see `OPERATIONS.md §"Navigation chapter feel-test on the remote"`).

## What this chapter does (and what it doesn't)

**Does:**
- Adds a **pure focus model** (`WallFocus` data class + `WallFocusModel.apply()` function) that owns D-pad navigation across three zones: Ticker, Feed, Grid. No Compose, no Android — the navigation graph is unit-testable in isolation.
- Adds **inter-zone transitions** with spatial sense: UP reaches the ticker, LEFT from the grid's leftmost column lands on the feed, DOWN from the ticker returns to whichever zone the operator came up from (not always the feed).
- Adds **per-zone state preservation**: switching zones doesn't clobber the other zone's index; returning to a zone lands where you left off. Feed focus + grid focus are both remembered.
- Adds **per-zone actions** (layered cleanly on top of the pure model, not entangled): SELECT on a grid cell opens the controls overlay, SELECT on a feed item toggles in-place expansion, SELECT on the ticker toggles pause.
- Adds **visual focus signals** — single-color WyzeGrid green accent (`WallColors.BadgeLive`) across all zones: a 3 dp left-edge bar in the feed pane, a 3 dp border on the grid cell, a 2 dp border on the ticker strip, and a green PAUSED chip in the ticker when paused.
- Adds **no-trap invariant** — tested at 38 cases: every zone is reachable from every other zone in ≤4 moves, and every zone is exitable from some direction. The test (`WallFocusModelTest`) pins the reachability graph itself.

**Does NOT:**
- Rebuild the menu. `MenuOverlay` is unchanged in this chapter — it remains a separate overlay that captures focus orthogonally. The menu's per-row D-pad handling stays in Compose's standard focusable system. The integration between the wall focus model and the menu is at the *boundary* (the model emits `OpenMenu`; the menu's own internals handle the rest).
- Kiosk story / long-uptime watchdog / boot receiver — deferred to the new MyMTS box (in transit).
- Restructure the feed — the chronological river is preserved; the operator's "list view + sections" feedback (BACKLOG item B) is its own decision.
- Touch the player layer. No `StreamPlayerManager` change; no player churn from focus or action events.

## Why this chapter happened

**Operator feedback (2026-06-04 hands-on session, item A):** *"The wall isn't fully navigable from the couch. I can't D-pad into the feed to focus an article, can't focus a video cell to act on it. The menu feels clunky."*

The wall at Stage 5 was passable from the couch for channel assignment (MENU + picker was usable), but the inner zones — feed items, grid cells, the ticker — had no navigation at all. The operator had to break the ambient-wall posture to do anything beyond "stare at live tiles." Stage 6 before this point would have added per-zone actions (audio, captions) without any way to *reach* the zones that matter from the couch. Item A flagged this as a load-bearing gap. This chapter lands the entire navigation graph so every zone can be reached and acted on without leaving the remote.

## The pure focus model design

**Why pure (no Compose, no Android)?**

Focus bugs are the worst TV-UX failures. A trap (a zone you can't escape), an off-by-one (wrong cell is highlighted), a silent stay where the operator pressed RIGHT and nothing happened — these are invisible to integration tests because they depend on the full Compose focus-request semantics, the actual remote timing, and the live layout. By extracting the navigation graph into a pure function (`WallFocusModel.apply(focus, intent, feedItemCount, gridTileCount, gridColumns) → NavResult`), the entire transition graph becomes unit-testable from a single point, without booting Compose or running against a real TV. The test suite (`WallFocusModelTest`, 38 cases) covers:
- Every zone × every D-pad direction (3 zones × 4 directions = 12 core moves) plus SELECT and BACK.
- SELECT semantics per zone (toggle, request modal, etc.).
- The no-trap graph (reachability BFS, exitability check).
- State preservation across zone transitions (round-trip tests for `feedIndex` ↔ `gridIndex` ↔ `lastLowerZone`).
- Edge cases (empty feed, empty grid, both zones empty — the model still has a sane answer).

**Zones and intra-zone state:**

```
+-------------------------------+
|             TICKER            |   Thin strip, single focus position
+-----+-------------------------+
|     |                         |
| FEED|         GRID            |   Feed: scrollable; Grid: 2×2 default
|     |                         |
+-----+-------------------------+
```

- **`WallZone.Ticker`** — the top strip. Single focus position (no intra-navigation in v1; UP stays, RIGHT stays). `tickerPaused` Boolean lives here in the focus state.
- **`WallZone.Feed`** — the left pane, ~28% width. `feedIndex` (0…itemCount-1), `feedExpanded` (Boolean — item is showing its full plain-text summary). UP/DOWN move within the feed or to the ticker. RIGHT enters the grid. LEFT opens the menu.
- **`WallZone.Grid`** — the video cells. `gridIndex` (0…tileCount-1). Row/column math uses `gridColumnsFor(tileCount)` (extracted as the single source of truth for column count — same function the `VideoGrid` layout uses, so D-pad math matches the visible layout exactly). UP/DOWN/LEFT/RIGHT move within the grid's spatial grid, or UP/LEFT spill to other zones.

**Inter-zone transitions and `lastLowerZone` memo:**

When the operator presses UP from the feed or grid, they reach the ticker. When they press DOWN from the ticker, they should return to whichever zone they came up from, not always the feed. This is tracked by `lastLowerZone` (a `WallZone` field, never `Ticker`):
- Entering the ticker from the feed: `lastLowerZone ← Feed`.
- Entering the ticker from the grid: `lastLowerZone ← Grid`.
- Leaving the ticker downward: return to `lastLowerZone` (if it has items; if empty, fallback to the other zone; if both empty, stay).

This mirrors WyzeGrid-family usability: focus remembers where you came from. Test `ticker round-trip preserves the lastLowerZone memo across multiple bounces` verifies this survives several bounces without drift.

## The action layer design

Per the chapter prompt's "keep actions layered on top of the focus model, so if the later feel-test surfaces a focus-model tweak, the actions don't have to be unpicked," actions are **layered**, not entangled.

**Why layered?**

If SELECT logic were baked into `WallFocusModel.apply()` directly, future changes to a per-zone action (e.g., "what does SELECT do on the grid?") would require unpicking the navigation layer. By keeping them separate:
- `WallFocusModel.apply(intent)` returns a `NavResult` (sealed class):
  - `NavResult.Focus(newFocus)` — adopt this focus state.
  - `NavResult.Stay` — no change; consume the event.
  - `NavResult.OpenMenu` — side-effect: delegate to `MenuState.open()`.
  - `NavResult.OpenSlotControls(slotIndex)` — side-effect: delegate to `menu.openControls(slotIndex)`.
  - `NavResult.BackBubble` — no deeper state to collapse; caller handles BACK (e.g., system close).
- `WallScreen` (the call site) owns the action layer: it dispatches `NavResult` into `MenuState`, `LineupStore`, the ticker `paused` flag, etc. without touching the pure model again.

**Per-zone SELECT behavior:**
- **Ticker:** toggles `tickerPaused` (pause the marquee so the operator can read a value sliding off-screen). No UI change beyond the pause + PAUSED chip, lowest-friction action.
- **Feed:** toggles `feedExpanded` (show the item's full plain-text summary in place; collapse back to the clipped row). The summary is the helper's pre-rendered plain text — no fetch, no new rendering, just revealing more of what's already on the TV.
- **Grid:** emits `NavResult.OpenSlotControls(slotIndex)`, which `WallScreen` routes to the existing per-tile controls overlay (audio, captions, channel re-pick).

**BACK semantics:**
- If the feed item is expanded, BACK collapses it first (stays in the feed, doesn't leave the zone).
- If already collapsed, BACK bubbles to `WallScreen`, which then routes to the menu-close handler or system back (preserving the Stage 5 "BACK closes the deepest overlay" habit).

## No-trap invariant — what it is, how the test suite pins it

A **trap** is a focus state from which the operator cannot escape. Examples:
- Locked in a single zone (no direction leaves it).
- An off-by-one that prevents reaching the last row of the grid.
- Silent stay where every direction does nothing.

The test suite checks two properties:

**1. Every zone is reachable from every other** — a live reachability BFS through the navigation graph, max 4 moves between any pair of zones. Implemented in `WallFocusModelTest.every zone is reachable from every other zone`.

**2. Every zone is exitable** — for each zone, at least one of UP/DOWN/LEFT/RIGHT must produce a result that LEAVES the zone (either to a new zone, `OpenMenu`, or `OpenSlotControls`). Implemented in `WallFocusModelTest.every zone is exitable (no trap)`.

The action layer (adding per-zone SELECT) **must not introduce new traps**. The dedicated test `actions layer does not introduce a trap (re-verify the no-trap invariant)` re-runs the exitability check after SELECT is added — if any zone becomes trapped (because SELECT consumed a key that was the only exit), the test fails and forces a deliberate decision.

## Visual focus signals — the WyzeGrid-family single-color accent

Every zone uses the same accent color (`WallColors.BadgeLive` — the WyzeGrid family's bright green) so the operator's 10-foot read of "where am I?" stays consistent across the whole wall.

- **Feed pane:** 3 dp left-edge bar, full height of the focused row. Matches the `focused` background tint.
- **Grid cell:** 3 dp border on all sides of the focused cell. Stands out against the dark video background. Implemented as a Box wrapper around `WallTile` — no player recomposition, no churn.
- **Ticker strip:** 2 dp border on the outside of the strip. When paused, a small chip in the leading edge reads "PAUSED" in the same green.

The consistency matters: when the operator's eyes flick from the feed to the grid, they're looking for the same green-accent shape, not relearning a new signal per zone.

## A1 boundary in the feed-expand path — adversarially confirmed

**Closed door — an early A1 design decision** (line 81): *"Opening an item on the TV shows whatever the helper safely provides (e.g., a text excerpt). Do **not** build a flow that requires the TV to fetch arbitrary web pages."*

When the operator presses SELECT on a feed item, `feedExpanded` toggles to `true`. The `FeedPane` then renders the item's `summary` field as a larger `Text` widget with `maxLines = Int.MAX_VALUE`, still as native Compose `Text`. The summary is the helper's pre-rendered plain text (HTML stripped at `feeds/parser.py` in the helper; the wall treats it as inert). No WebView, no fetch, no HTML render.

**Adversarial verification (parallel agent, refute-first):** A dedicated verifier traced the entire feed-expand code path — pure model SELECT, `WallScreen` parameter passing, `FeedPane` rendering, `FeedRepository`/`HelperClient` call graph, and the helper-side parser. Result: claim **not refuted**, 9 evidence citations. Full record in `docs/THREAT-MODEL.md §"Navigation chapter — feed-expand A1 confirmation (2026-06-04)"` along with the four red-flag patterns future code review must reject.

## What's deferred — the roadmap

- **Kiosk story.** Building the foreground service + boot-receiver (long-uptime watchdog) belongs on the new MyMTS box (in transit), not on `.182` (WyzeGrid's permanent camera device). Testing it on `.182` would reintroduce the Stage 1/2 two-watchdog conflict. Deferred to the new-box session per Model A.
- **Feed list/sections restructure** (BACKLOG item B — *"Feed UX — list view, live/offline sections, selectable items"*). Operator flagged the current chronological river as "unintuitive and inefficient." Restructuring as a true list with sections depends partly on this chapter (items are now selectable via focus) but also on deeper feed state changes (how items group, live/offline marker at the list level). The navigation prerequisites are now in place; the redesign is its own push.
- **Ticker markets/sports modes** (BACKLOG item D). The ticker was built to accept additional modes without rework via the `TickerSource` interface. Markets-only ships in v1; sports mode is a future addition with its own sports-data source. Navigation works identically across modes.
- **QR-to-phone for richer reading** — newly logged in BACKLOG as a closed-door-compatible alternative to the forever-forbidden in-app full-article web reading. A future feature where SELECT on a focused feed item could surface a QR that opens the article on the operator's phone. The TV never fetches HTML; the phone is the operator's own device. Not built here; logged so the option is visible.

## Open questions for the operator's at-the-box feel-test

The navigation graph is locked and tested. The open question is the *felt experience* on the actual Onn remote from the couch:

- **D-pad fluency.** Does the D-pad navigate smoothly? Any subtle timing surprises (a diagonal press resolving to an unexpected direction)?
- **Feed expansion read.** Does the in-place expansion read intuitively from 10 ft? Title growing larger + summary unclipping — coherent transition?
- **Ticker pause clarity.** Does the marquee stop + "PAUSED" chip read clearly? Is the chip visible enough at 10 ft?
- **Accent coherence.** Does the green WyzeGrid accent feel like a single language across feed / grid / ticker, or does one zone's signal feel weak/confusing?
- **Position memory.** Does returning to a zone at the remembered index feel right, or does it ever feel like "went somewhere and came back to the wrong spot"?
- **Menu integration.** When the menu opens (LEFT from any zone), the menu's own Compose focus takes over; closing the menu restores wall focus to whichever zone was last active. Does the round-trip feel natural?
- **Modal handoff.** Pressing BACK from a controls / picker modal dismisses to the wall. Does the operator ever feel "stuck" in a modal?

The OPERATIONS section "Navigation chapter feel-test on the remote" carries the checklist for these. The chapter is closeable as soon as the operator confirms the feel; any tweaks become a small follow-on, not a rebuild.

## Standing rules at this stage

- **unrelated host services: never touched.**
- **WyzeGrid** stays untouched on `.182` until the operator runs the feel-test; restored to foreground at session end via the documented `am start` (see `OPERATIONS.md`). Model A: one kiosk app per box; `.182` is WyzeGrid's box.
- App test count: **151 total** (38 new in `WallFocusModelTest` covering the pure focus model + actions layer; the rest pre-existing and unchanged by this chapter).
- The navigation model is **pure Kotlin**, compiled with zero Compose dependencies — it runs in plain `org.junit.Test` without a framework or emulator. A test failure is an actual bug in the navigation graph, not a flaky UI test.
