# Finding 17 — Panel fit (overscan inset + global UI scale) + native feed → agnostic with source-per-headline

> **Status: BUILT + TESTED + DEPLOYED 2026-06-07.** Two hands-on issues on the new box's 720p panel: the wall clipped at the panel edges, and the operator decided to switch the native feed from per-source sections to an agnostic chronological list. App **245** unit tests (focus model 49 unchanged); deployed to `.92` (health-gate PASS, 127 `TILE_READY`, 0 dead). Same release key. Operator confirms the visual on the panel.

## Part A — the panel-fit diagnosis

**The box output is correct.** `<LAN_IP>` reports `wm size 1280x720`, `wm density 213` (tvdpi), `dumpsys display → real 1280 x 720` — a clean 720p at a standard TV density. So it is **not** a resolution/density mismatch.

**The clip is physical panel overscan.** The same app fit the other (WyzeGrid) box's 720p panel but clips on this box's panel → the difference is the panel, not the box. The panel (or its converter) cuts the outer ~edge of the frame; the box outputs a full 1280×720, the panel just doesn't show it all. `wm overscan` (the old software overscan-compensation command) was **removed in modern Android**, so there's no box-side software overscan knob — the robust, panel-agnostic fix is **app-side**.

### The fix (app-side, two levers)

- **Overscan-safe inset** (`WallSettings.Overscan`: `None 0% / Small 3% / Medium 5% / Large 7%`, default **Medium**). A fraction-of-screen margin reserved on every edge so content renders inside the visible area. Crucially it's measured in **real screen space** — `WallScreen` uses `BoxWithConstraints` to read `maxWidth/maxHeight` **before** the density override, so `inset = max{W,H} × fraction` is a true physical margin (not itself scaled by the UI scale). The black root background shows through the inset margin. Default 5% = the classic TV action-safe; a clean (non-overscan) panel just gets a small black border, an honest cost for a panel-agnostic default. Existing installs gain the inset on first run after the update (absent pref key → `Medium`, not `None`).
- **Global UI scale** (`WallSettings.UiScale`: `Compact 0.80 / Default 1.0 / Roomy 1.15`). A single `LocalDensity` override — `Density(base.density × multiplier, base.fontScale)` — wrapping the entire wall, so **everything scales together** (ticker, feed, grid chrome, labels, overlays). Scaling `density` scales both dp and sp uniformly. This is the lever when content is simply too big for the panel; **Compact** shrinks-to-fit. The per-piece feed width / feed font controls still tune **within** the scaled layout (they compose — the global scale is "shrink the whole wall", feed-font is "additionally tune the feed text").

Both are discrete D-pad-cyclable presets in Settings (the new **"Display size"** + **"Overscan inset"** rows lead the settings card, so the operator lands on the fit controls), persisted on-device via `LineupStore`. This **cashes in the deferred "global UI sizing" BACKLOG item** (it was explicitly "reconsider once on the real TV").

**Box-side note (operator, optional):** if the panel has its own "picture size / aspect / just-scan / overscan" menu setting, switching it to "just scan"/1:1 reduces the physical overscan and may let the operator dial the app inset back down. The HDMI output mode itself is correct (720p); only the panel's display geometry overscans.

### Update 2026-06-09 — Position offset (the panel also shifts off-center)

Hands-on follow-up: this panel doesn't just zoom, it overscans **off-center** (the image is shifted), and it has **no hardware menu** to adjust itself. Scale + inset are symmetric/centered — they can't recenter a shifted image. Added a third lever, **Position offset** (`WallSettings.offsetXDp`/`offsetYDp`, ±64 dp / 8 dp step, default 0,0): a `Modifier.offset` on the inset Box (real screen space, **outside** the density scale, like the inset), nudging the whole wall — ticker + feed + grid + overlays — to recenter it. `Modifier.offset` shifts layout + hit-testing together, so D-pad focus is unaffected (49 focus tests still pass). D-pad-adjustable **live** via new "Position X" / "Position Y" rows (LEFT/RIGHT, an `AdjustRow`), persisted per keypress (clamped on nudge AND read). The set is now complete: **scale + inset + offset = full in-app compensation for a non-adjustable panel.** Adversarial-reviewed (offset confirmed real-space, hit-testing intact, clamp on both paths) — clean. App 255 tests; deployed `.92` (health-gate PASS, 125 `TILE_READY`, 0 dead). Operator dials the three by eye on the panel.

## Part B — native feed → agnostic chronological with source-per-headline

The operator decided (after living with the Stage-7 per-source sections on hardware) to switch the **native** feed to the agnostic style the web client uses: one newest-first list across all sources, source label per headline.

- `FeedListBuilder.build(items)` now returns a flat `List<FeedItem>` sorted **newest-first across all sources** (published-preferred, fetched-fallback, no-timestamp-last, id tiebreak). The per-source grouping, `FeedListEntry`/`SectionFreshness`/`flattenItems`/`entriesIndexForFocus` are removed; `sourceLabel(blank → "Unknown source")` added (mirrors the web client). `applyFilters` (source denylist + recency) and `distinctSources` (the source-filter UI) are unchanged.
- `FeedPane` renders one river — each row: **source** (accent, glanceable, uppercase) + **age** on a meta line, then the headline (dominant), then the summary. Matches the web client's `.feed-source`/`.feed-title` hierarchy.
- **Focus model unchanged.** The feed was already a flat N-item focus zone; with no headers, `feedIndex == list index` directly (the `LaunchedEffect` scrolls `focusedIndex` straight in). All **49** `WallFocusModelTest` cases pass unchanged — no focus-model edit.
- **C3 honesty without sections:** the section freshness chips are gone; their job splits between the **per-item age** on each row and the **pane header** ("feed not updating" / "helper unreachable"). Nothing shows stale-as-fresh.
- **A1 held:** every field is native Compose `Text`; no WebView, no HTML; the summary is the helper's already-stripped plain text, expanded in place.

Both clients' feeds are now the same model — the per-client divergence noted in finding 16 is resolved in favour of agnostic.

## Verification

- **Box diagnosis:** `wm size`/`density`/`dumpsys display` captured (1280×720 / 213 / clean) — overscan is panel-physical, ruled in by the WyzeGrid-panel-fits / this-panel-clips contrast.
- **App:** 245 unit tests. FeedListBuilderTest rewritten (agnostic interleave: multi-source items interleave by time not grouped; filters; sourceLabel). WallSettingsTest +panel-fit presets (Compact<Default<Roomy; None<Small<Medium<Large; fromOrdinal fallbacks — overscan out-of-range falls back to the safe Medium). Focus model 49 unchanged.
- **Deployed** `.92` (same key) — health-gate PASS, 127 `TILE_READY`, 0 dead, wall foreground, no crashes.
- **Operator's eyes (can't be done headless):** does the wall now fit inside the visible area (nothing clipping), is the feed text sized right, and does the feed read well as an agnostic source-per-headline list? Tune **Display size** (→ Compact) and **Overscan inset** (→ up if still clipping, → None on a clean panel) to the panel.

## Standing rules

- Same release key (not regenerated/reprinted). `.182`/`.158` untouched. unrelated host services never touched. No secrets/absolute-paths. Box ends clean known-good.
