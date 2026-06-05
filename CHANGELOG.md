# Changelog

All notable changes to MyMTS will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## Feed sources — expanded to 13 reputable balanced RSS sources + SQLite cross-thread fix (2026-06-05)

The helper feed seed grows from 4 to 13 verified public RSS sources with a deliberate left/center/right balance, and the intermittent SQLite cross-thread 500 on `/api/feed` is fixed. Every new source was verified by fetching through the helper's real SSRF-safe fetcher and parsing through the real defensive parser before commit — only feeds returning valid plain-text items were seeded.

### Added
- 9 new seeded RSS sources in `feeds/seed.json`: PBS NewsHour, Christian Science Monitor, CBS News, NBC News, Politico, Bloomberg Markets, The Dispatch, National Review, Reason. (Original 4 retained: BBC World, Al Jazeera, Guardian World, NPR World.)
- `db.connection_scope(path)` — a context manager that opens, uses, and closes the SQLite connection inside the sync route body (one `run_in_threadpool` call = one thread), so the connection never crosses an anyio threadpool thread boundary.
- 2 shipped-seed guard tests in `tests/test_feeds_seeder.py` — all-https + unique URLs/labels validation; seeds every entry; originals retained.
- 2 SQLite concurrency regression tests in `tests/test_api.py` — `/api/feed` and `/api/channels` hammered by 8 client threads × 64 requests; all 200 + stable envelope asserted.

### Fixed
- **SQLite cross-thread bug** (resolves the logged BACKLOG entry). Root cause: the FastAPI `Depends()` yield-dependency (`_conn`) drove the connection's open and close through two separate `run_in_threadpool` calls that could land on different threadpool threads — closing a `sqlite3.Connection` on a different thread than it was opened on raised `ProgrammingError` → HTTP 500. `connection_scope()` keeps the whole lifecycle on one thread. The fix keeps sqlite's thread guard ON (no `check_same_thread=False`).

### Changed
- `feeds/api.py` and `channels/api.py` — removed the `_conn()` yield-dependency; both routes open the connection via `db.connection_scope(db_path)` inside the route body. No change to the SSRF fetcher, parser, store, or response envelope.

### Verification
Every seeded feed was fetched through the real SSRF-safe fetcher and parsed through the real defensive parser before commit. Two candidates were tested and deliberately left out:
- **Associated Press** (`apnews.com/index.rss`): official feed returns a SAXParseException; the only working feed is a third-party mirror (feedx.net), which would misrepresent provenance → rejected on honesty grounds.
- **Reuters** (`reutersagency.com/feed`): returns HTML; Reuters discontinued public RSS years ago → no clean public feed exists.
Verified-but-omitted (operator can swap in via `seed.json`): ABC News, CNBC, The Hill, Axios, The Atlantic, Vox, MarketWatch, Washington Examiner.

### Tests
Helper suite: **140 passed** (was 136). The new regression tests confirm `/api/feed` and `/api/channels` are stable under concurrent threaded load.

### Deferred
- Per-source bias tags / bias-labeling UI — future backlog idea; this chapter chooses a balanced set without labels.
- Dual-uvicorn-instance tidy-up — separate low-priority BACKLOG sub-item, independent of this fix.
- AP / Reuters — reconsider if either ships a clean public RSS feed.

### Operator action
Helper redeploy required to pick up the new sources: pull → rebuild → restart → verify `/health` returns 200 and `feeds.sources_count` reads **13**. Deploy profile unchanged: **non-root, read_only, cap_drop ALL, dedicated bridge — never the unrelated host container**.

### Standing rules
- **unrelated host services: never touched.** Helper runs on its own bridge network.
- A1: every source treated as hostile, parsed defensively, served as inert plain text — same parser path, more sources.
- No secrets / absolute paths committed or logged.

## UX & Config — configurable feed width / font / side, settings overlay in menu (2026-06-04)

The operator can now tune the wall layout to their space and eyesight without code changes. Three new discrete-preset settings (feed width, feed font scale, feed side) are exposed in a Settings overlay accessed from the side menu. Each setting cycles through operator-chosen presets; LEFT/RIGHT on a focused setting advances to the next preset, applies it live, and persists it to the device's SharedPreferences. The focus model derives LEFT/RIGHT spatial rules from the configured feed side — when the feed is on the right, the grid's spatial-right direction spills to the feed (not off-wall), and the menu slides in from the right instead. Navigation stays spatially correct and trap-free in both orientations.

### Added
- `data/settings/WallSettings.kt` — data class holding three operator-tunable settings: `feedWidth` (enum `Narrow=0.22, Default=0.28, Wide=0.36` screen fraction), `feedFontScale` (enum `Small=0.88, Default=1.0, Large=1.18` multiplier), and `feedSide` (enum `Left` or `Right`). Internal helpers `feedWidthFromOrdinal()` / `feedFontScaleFromOrdinal()` / `feedSideFromOrdinal()` apply safe-fallback defaults for out-of-range or missing storage values.
- `data/settings/WallSettingsTest.kt` — 17 invariant tests pinning default values, enum ordering, legibility floor (Small `multiplier >= 0.85`), overflow ceilings (Wide `fraction <= 0.4`, Large `multiplier <= 1.25`), ordinal robustness (out-of-range fallback), copy semantics, and field-by-field equality.
- `ui/menu/SettingsOverlay.kt` — centered WyzeGrid-family modal with three focusable `SettingRow`s (Width, Font, Side). UP/DOWN navigate between rows via Compose focus traversal. LEFT/RIGHT on a focused row cycle the enum forward, apply the new value live to `FeedPane` / `WallScreen`, and persist to SharedPreferences. SELECT also cycles forward. BACK dismisses the overlay.
- `MenuState.PendingSelection.Settings` + `openSettings()` — menu state variant and dispatcher for opening the Settings overlay over the side menu.
- 11 new `WallFocusModelTest` cases for feed-right orientation — verify spatial-rule mirrors (LEFT/RIGHT semantics flip when feed is right), FEED↔GRID round-trip with preserved indices in the right-side layout, no-trap exitability invariant in the swapped layout, and ticker LEFT/RIGHT only the gesture toward the feed's outer edge opens the menu.

### Changed
- `data/lineup/LineupStore.kt` — `State<WallSettings>` added. New methods `updateWallSettings()`, `cycleFeedWidth()`, `cycleFeedFontScale()`, `cycleFeedSide()` read current values, compute the next enum ordinal, persist as integer keys (`KEY_FEED_WIDTH`, `KEY_FEED_FONT`, `KEY_FEED_SIDE`) to SharedPreferences, and emit the new state. `readWallSettingsFromDisk()` applies per-component default fallback if a key is missing or the stored ordinal is out of range.
- `ui/nav/WallFocusModel.kt` — `apply()` signature extended: `feedSide: FeedSide = FeedSide.Left` parameter (default preserves the existing 38 navigation tests unchanged). Feed zone inner/outer edge gestures now derive from `feedSide`. Grid zone LEFT/RIGHT branches use `toFeed` / `intoGrid` variables that flip based on `feedSide`. Ticker LEFT/RIGHT: only the gesture toward the feed's outer edge opens the menu.
- `ui/menu/MenuOverlay.kt` — accepts `feedSide` parameter. The side panel now slides in from the feed's outer edge (LEFT side of screen when `feedSide=Left`, RIGHT side when `feedSide=Right`). Added "Settings" row to the menu + "WALL" section title.
- `ui/wall/FeedPane.kt` — accepts `fontScale: Float` parameter (default `1.0`). All `sp` values in title, summary, time chip, and section headers are multiplied by `fontScale`. Legibility floor at 0.88× is asserted in `WallSettingsTest`.
- `ui/wall/WallScreen.kt` — reads `wallSettings` from `LineupStore.wallSettings`. Passes `feedSide` to `WallFocusModel.apply()` for spatial-direction derivation. Renders the Row as `Row{FeedPane, Divider, VideoGrid}` when `feedSide=Left`, or `Row{VideoGrid, Divider, FeedPane}` when `feedSide=Right`, with both children defined once so a feed-side swap does not recreate the `FeedRepository` or `StreamPlayerManager`. Renders `SettingsOverlay` when `PendingSelection.Settings`.

### Tests
28 new app tests — 17 in `WallSettingsTest` (defaults, ordering, legibility floor / overflow ceiling invariants, ordinal fallback on missing/out-of-range, copy) + 11 in `WallFocusModelTest` (feed-right orientation mirror checks, spatial-rule derivation, no-trap and round-trip preservation). Full app suite: **203 tests, all green**. Navigation invariants verified in both orientations (Left and Right feed side).

### Deferred (deliberately NOT in this chapter)
- **Overall UI sizing / global density scale** — operator was asked the vision question per chapter §5 and chose to defer to BACKLOG for revisit on the new MyMTS box; logged in `docs/BACKLOG.md §"Overall UI sizing"`.
- **Feed filtering / search UI** — separate chapter; logged in BACKLOG.
- **Section collapse / jump-by-source** — logged in BACKLOG as the operator-feedback-driven follow-on.
- **Ticker modes** — sample data only in v1; deferred to the ticker-modes chapter.
- **Kiosk story** — long-uptime foreground watchdog + boot receiver; deferred to the new MyMTS box.
- **In-app full-article web reading** — closed door, permanent.

### Adversarially verified — A1 + B1 boundaries unchanged
Settings persistence uses SharedPreferences integer ordinals only — **no secrets, no PII, no absolute paths**, no new endpoints, no fetch. `SettingsOverlay` is pure Compose `Text` + focusable `Row` widgets. LEFT/RIGHT on a focused setting calls `LineupStore.cycle*()` (local SharedPreferences write only). **No new web-fetch, no new WebView, no new HTML render.** The feed-expand path is unchanged. `WallFocusModel.apply()` is still pure Kotlin. Full entry: `docs/THREAT-MODEL.md §"UX & Config — A1 + B1 reverify (2026-06-04)"`.

### Operator-validated
- **Feel-test STAGED** for the operator's next at-the-box session — folded into the existing nav-feel-test checklist in `docs/OPERATIONS.md`. Adds Width/Font/Side cycling steps + D-pad navigation checks in the feed-right orientation.

### Standing rules
- **unrelated host services: never touched.**
- Helper: non-root, read_only, cap_drop ALL, dedicated bridge — unchanged.
- No secrets / absolute paths in source.

## Feed restructure — sectioned by source + per-source honest staleness (2026-06-04)

The continuous chronological feed scroll is replaced with a sectioned list grouped by news source. Each source (BBC, Guardian, Al Jazeera, NPR, …) gets its own labelled section with a per-source freshness chip applying Trust Bar **C3** at the section layer — the operator sees at a glance which sources are flowing and which have gone quiet. Items within each section stay newest-first (published time when present, fetched time as fallback). The flat-item invariant is preserved: `WallFocusModel`'s `feedIndex` still traverses 0..itemCount-1 in visible list order; headers are visual only, never focusable.

### Added
- `ui/wall/feed/FeedListBuilder.kt` — pure transform from `List<FeedItem>` to `List<FeedListEntry>` (sealed `Header | Item`). Groups by source (case-insensitive alphabetical), sorts within section newest-first by published time falling back to fetched time. Per-section freshness classified (`Fresh` < 2h, `Warm` < 12h, `NotUpdating`, `Unknown`).
- `ui/wall/feed/FeedListBuilderTest.kt` — 18 invariant tests pinning grouping, sorting, freshness boundaries, flat-item order = focus traversal order, `entriesIndexForFocus` mapping (entries-list index to focus index past headers).
- `SectionFreshness` enum — per-section staleness signal (`Fresh`, `Warm`, `NotUpdating`, `Unknown`) applied independently per source rather than once for the whole feed.
- `PickerGroupTest.kt` — 6 tests pinning live/offline position math for the picker's new orientation chip.
- `pickerGroupAt()` helper in `ChannelPickerOverlay` — computes 1-based position within the live/offline group for the cycler cursor, drives the slot header's "LIVE n/m" / "OFFLINE n/m" orientation chip.

### Changed
- `ui/wall/FeedPane.kt` — **rewritten**. Renders the sectioned `LazyColumn`: per-source `SectionHeader` (uppercase source name, item count, freshness chip with coloured age badge) + `FeedRow` with per-item time chip (source label removed from rows since it now lives in the header). Auto-scroll uses `entriesIndexForFocus` to map the focus model's `feedIndex` into the entries-list position even with intervening headers. Headers are visual only — never focusable; focus model unchanged.
- `ChannelPickerOverlay` slot header — now shows "SLOT n · LIVE i/j" or "SLOT n · OFFLINE i/j" — the orientation chip flips color and label text as the cursor crosses the live↔offline boundary so the operator always knows which group they're in.

### Tests
24 new app tests:
- **FeedListBuilderTest (18):** sections alphabetical + stable, within-section newest-first, freshness computed from newest item's age, boundaries sharp at thresholds, flat-item order matches focus traversal, `entriesIndexForFocus` maps past headers, out-of-range focus returns -1, flat item count equals input item count, `newestAgeMs` on header equals NOW minus newest item's timestamp.
- **PickerGroupTest (6):** cursor position on live/offline boundaries, all-live and all-offline lists, group-size and position-within-group correct at edges.
- **Navigation chapter (38 WallFocusModelTest cases):** re-run **green** without change. The focus model is unchanged; the flat-item invariant holds (test "flat-item order matches focus traversal order" pins this); no-trap and state-preservation properties survive the restructure.

Full app test suite: **all green** (175 tests including 18 new FeedListBuilder + 6 new PickerGroup + 38 navigation).

### Deferred (deliberately NOT in this chapter)
- **Feed filtering / search UI** — separate later chapter; logged in BACKLOG.
- **Section collapse / jump-by-source** — logged in BACKLOG as the operator-feedback-driven follow-on.
- **Configurable feed width, font, item-count-per-scroll** — typographic polish; "UX & config" push.
- **Ticker markets / sports modes** — sample data only in v1; deferred to the ticker-modes chapter.
- **Kiosk story** — long-uptime foreground watchdog + boot receiver; deferred to the new MyMTS box per Model A.

### Adversarially verified — A1 boundary unchanged
The feed-restructure introduces **no** new web-fetch, **no** new WebView, **no** new HTML render. The new code is pure Kotlin (grouping logic in `FeedListBuilder`) + Compose `Text` widgets (rendering headers and items). Section headers are inert-text labels. SELECT-on-focused-item expands the item's `summary` (already HTML-stripped by the helper's `feeds/parser.py`) by flipping the `Text` widget's `maxLines` — no fetch, no markup render. The feed-restructure adds **no new input surface**. Full entry: `docs/THREAT-MODEL.md §"Feed restructure — A1 reverify (2026-06-04)"`.

### Operator-validated
- **Feel-test STAGED** for the operator's next at-the-box session — folded into the existing nav-feel-test in `docs/OPERATIONS.md`. The sectioned layout and per-source freshness chips work; the skim-ability and muscle-memory delta on the real Onn remote is the operator's call at the box.

### Standing rules
- **unrelated host services: never touched.**
- Helper: non-root, read_only, cap_drop ALL, dedicated bridge — unchanged.
- No secrets / absolute paths in source.

## Whole-wall D-pad navigation — global focus model + per-zone actions (2026-06-04)

A single coherent D-pad navigation graph now covers every zone on the wall. The pure focus model (`WallFocusModel`) computes zone-to-zone transitions as a testable function, eliminating navigation traps and spatial inconsistencies that plague TVs where each layer re-invents focus independently. The per-zone action layer (cell SELECT → controls overlay, article SELECT → in-place summary expansion, ticker SELECT → pause/resume) layers orthogonally on top so navigation and action are decoupled; a future change to either layer doesn't require unpicking the other.

### Added
- `ui/nav/WallFocus.kt` — focus data class (`active`, `feedIndex`, `gridIndex`, `lastLowerZone`, `feedExpanded`, `tickerPaused`) + `WallZone` enum (Ticker, Feed, Grid).
- `ui/nav/WallFocusModel.kt` — pure function emitting `NavResult` sum type (`Focus`, `OpenMenu`, `OpenSlotControls`, `BackBubble`, `Stay`). No Compose, no Android; the entire navigation graph is testable from a single point.
- `nav/WallFocusModelTest.kt` — 38 invariant tests pinning no-traps (every zone reachable + exitable), spatial sense (UP from grid/feed reaches ticker; LEFT from grid col 0 lands on feed), state preservation (switching zones preserves the other zone's index), BACK collapsing feed-expand first then bubbling, and ticker SELECT toggling `tickerPaused`.
- Focus indicators on the wall: each focused zone (feed item, grid cell, ticker) is adorned with a WyzeGrid-family green-accent border so the operator sees D-pad movement from 10 feet. PAUSED chip in the ticker when paused.

### Changed
- `ui/wall/WallScreen.kt` — owns focus state (`WallFocus`), dispatches every D-pad event + SELECT + BACK through `WallFocusModel.apply()`, wires per-zone action results into the appropriate state holders (menu, slot controls overlay, ticker pause flag).
- `ui/wall/FeedPane.kt` — accepts `focusedIndex` (renders green-accent on that item) + `expandedIndex` (shows MORE of the helper's already-fetched plain-text `summary`; never any web fetch, WebView, or HTML render). Expanded rows handle BACK → collapse semantics. Expanded type ramps up (15 → 18 sp title, 12 → 14 sp summary) for 10-ft read.
- `ui/wall/VideoGrid.kt` — accepts `focusedCellIndex`, applies green-accent border to the focused tile. `gridColumnsFor(slotCount)` extracted as the single source of truth for column math (same logic the focus model uses for D-pad row/column navigation).
- `ui/wall/TickerStrip.kt` — accepts `focused` (green-accent border) + `paused` (marquee animation disabled, PAUSED chip surfaced). SELECT toggles `paused` via the model.
- Modal overlay gating — `SlotControlsOverlay` / `ChannelPickerOverlay` now render on `pending != null` rather than `menu.isOpen && pending`, so a SELECT on a focused grid cell opens the controls modal without dragging the side menu in.
- Root focus restoration — `DisposableEffect(menu.isOpen, menu.pendingSelection)` instead of just `menu.isOpen`, so the wall reclaims focus after a modal opened directly from a grid cell dismisses.

### Tests
Invariants pinned across the 38 navigation tests:
- **No traps.** Every zone is reachable from every other in ≤4 moves; every zone is exitable in at least one direction.
- **Spatial sense.** UP from feed/grid reaches ticker. LEFT from grid column 0 lands on feed at preserved `feedIndex`. DOWN from ticker returns to `lastLowerZone` (the zone the operator came up from), not always the feed.
- **State preservation.** Switching zones does NOT clobber the other zone's index. FEED → GRID → FEED round-trips return to the same feed item; GRID → TICKER → GRID preserves `lastLowerZone`.
- **BACK collapsing.** Feed with `feedExpanded=true` BACK collapses in place; feed with `feedExpanded=false` BACK bubbles.
- **Action layer non-trap.** Adding SELECT actions (grid → OpenSlotControls, feed → expand, ticker → pause) preserves the no-trap property; every zone still exits via a directional intent.
- **Ticker pause persistence.** `tickerPaused` survives zone transitions and round-trips; toggling SELECT on the ticker and leaving doesn't reset it.

Full app test suite: **all green** (151 tests including the 38 new navigation tests). Helper test suite: unchanged.

### Deferred (deliberately NOT in this chapter)
- **Kiosk story** — long-uptime foreground watchdog + boot receiver. Deferred to the new MyMTS box (in transit) per Model A.
- **Feed list/sections restructure** (BACKLOG item B). Operator's "the feed is a continuous individual-scroll, unintuitive" call needs structural decisions about grouping; this chapter delivers the navigation prerequisites.
- **Ticker markets/sports modes** (BACKLOG item D). Sample data only in v1.
- **QR-to-phone / send-to-phone full article read** — newly logged in BACKLOG as a closed-door-compatible alternative for richer reading; not built here.

### Adversarially verified — A1 boundary holds at feed-expand
The feed-expand path was independently verified to introduce **no** web-fetch surface and **no** HTML-render surface. The verifier checked the pure model SELECT semantics, the `FeedPane` render code, the `FeedRepository` / `HelperClient` call graph, and the helper-side parser. Result: feed-expand only flips a Boolean that changes `Text` widget `maxLines`; the summary text is the helper's already-stripped plain-text product. Full entry: `docs/THREAT-MODEL.md §"Navigation chapter — feed-expand A1 confirmation (2026-06-04)"`.

### Operator-validated
- **Feel-test STAGED** for the operator's next at-the-box session — see `docs/OPERATIONS.md §"Navigation chapter feel-test on the remote"`. The focus model logic works; the spatial feel on the real Onn remote is the operator's call at the box (WyzeGrid's 80-min foreground reclaim on `.182` defines the window).

### Standing rules
- **unrelated host services: never touched.**
- Helper: non-root, read_only, cap_drop ALL, dedicated bridge — unchanged.
- No secrets/absolute-paths in source.

## At-the-box finale Step 4 — `.182` restored to WyzeGrid + kiosk deferred (2026-06-04)

The away-from-box + safe-on-`.182` roadmap is now complete. The kiosk / foreground-coexistence story waits for the new MyMTS box (hardware in transit).

### Decision recorded — Model A (one kiosk app per box)
`onn-office` (`.182`) is WyzeGrid's permanent home for the cameras kiosk; MyMTS gets its own dedicated Onn box. The kiosk / foreground-coexistence work is therefore deferred — running it on `.182` would reintroduce the Stage 1 / Stage 2 two-watchdog thrash on the camera box, pointlessly.

### `.182` restored
- Launched `com.wyzegrid/.MainActivity` to put WyzeGrid back in the foreground.
- `topResumedActivity = com.wyzegrid/.MainActivity` (cameras UI).
- `com.wyzegrid/.WatchdogService` `isForeground=true` (the persistent watchdog, alive).
- MyMTS install (release-signed, on the known-good APK from Step 3) remains on `.182` — harmless, will be evicted by WyzeGrid's WatchdogService over the next ~80 min per the Stage 1 finding, and that's fine because the wall has its own box coming.

### Added
- `docs/OPERATIONS.md` — new section "New MyMTS box provisioning (pending — hardware in transit)" with the explicit checklist (DHCP reservation, `ONN-BOXES.md` row, signed-install via `deploy-app.sh`, kiosk story validation: holds the foreground ≥ 80 min, survives reboot, survives low-memory). Also documents why the kiosk work is *not* attempted on `.182`.
- `docs/BACKLOG.md` — two new entries:
  1. **Kiosk / foreground / boot story — pending the new MyMTS box.** Model A confirmed; build + validate the kiosk story on the dedicated MyMTS box when it arrives.
  2. **Helper feeds API — SQLite cross-thread bug** (latent since the TLS dual-listener; surfaces as occasional HTTP 500 on `/api/feed`). Noted with the small-fix path: thread-local connection or aiosqlite; also revisit collapsing the dual-uvicorn-instance architecture now that HTTP is gone.

### Standing rules
- **unrelated host services: never touched.**
- **WyzeGrid foreground + `WatchdogService` healthy on `.182`** at session end — verified by `dumpsys activity activities` + `dumpsys activity services`.
- App tests green; helper tests green: 136. No code changes in this commit (docs + BACKLOG only).
- **The at-the-box session is now complete.** Remaining work waits for the new MyMTS hardware.

## At-the-box finale Step 3 — rollback live-test verified on `.182` (2026-06-04)

The Stage 6 signed-update + auto-rollback path was operationally verified on real hardware. The dry-run had already exercised the decision logic; this session exercised the end-to-end install + health-gate + rollback flow with a deliberately-failing build.

### Sequence on `.182`

1. **Baseline established.** `./scripts/deploy-app.sh --archive-dir $HOME/.mymts/release`. Build → archive `mymts-0.0.0+5576551-20260604T230502Z.apk` → install → launch → health-gate **PASS** (`117 EV=TILE_READY events, 0 dead`) → promoted to known-good.
2. **Deliberately-failing build pushed.** `gradle.properties` temporarily flipped to `MYMTS_HELPER_BASE_URL=https://192.168.99.99:9999` (unreachable). Force-clean rebuild (gradle's incremental build had been masking the URL change — see gotcha below) + deploy.
3. **Auto-rollback fired.** Health gate returned `FAIL_NOT_READY` (`0 EV=TILE_READY events; need ≥ 2`) after the 90 s capture window. Script automatically reinstalled the known-good APK and relaunched.
4. **Wall came back up.** 4 distinct slots LIVE within 60 s: `bloomberg-tv`, `cbs-sports-hq`, `bbc-news`, `cnn`. `known-good` pointer unchanged. Failed APK retained in `$MYMTS_ARCHIVE_DIR/archive/` for diagnosis.
5. **Manual rollback exercised.** `./scripts/deploy-app.sh --manual-rollback` reinstalled the known-good in ~6 seconds; wall up; `known-good` pointer unchanged.

### Operational Bar properties confirmed on hardware
- **B1 (never bricks).** The wall was always running — either on the just-installed build (briefly, while the gate ran) or on the known-good.
- **B2 (always a way back).** Both auto and manual rollback paths exercised; both restored the wall to a known-working state.
- **B5 (no silent bad-bundle cascade).** A build that produced zero `EV=TILE_READY` events in 90 s was **never** declared the new known-good. The promotion gate held.

### Fixes shipped this commit
- `scripts/deploy-app.sh` — `log()` now writes to stderr instead of stdout. The prior bug: `$(archive_release)` was capturing the log line as part of the filename, producing a corrupted install path. Found at first deploy attempt; fixed before any state was persisted.
- `docs/OPERATIONS.md` — "live device test STAGED" flipped to "**verified on `.182` on 2026-06-04**" with the exact sequence above + a gotcha about `:app:clean` being required before any `gradle.properties` change.

### Gotcha — gradle incremental builds can mask config changes
When `gradle.properties` is edited (e.g. to point at a different `MYMTS_HELPER_BASE_URL`), gradle may report `:app:assembleRelease` as up-to-date and reuse the prior APK. The generated `BuildConfig.java` correctly reflects the new value, but the assembled APK doesn't. Discovered during the rollback test: an APK that was supposed to be "the failing build pointed at 192.168.99.99" had quietly been re-archived as the prior good build. Always force `:app:clean` before changing config-driven `buildConfigField` values. Runbook addition recorded in `docs/OPERATIONS.md`.

## At-the-box finale Step 2 — TLS cutover complete (2026-06-04)

The TLS migration staged in the Stage 6 baseline commit (`b8240b7`) is now finished. With the operator physically at the `.182` box (the camera box, MyMTS borrowed for development), the transitional cleartext path was removed in two safe phases — app first, then helper — each independently telemetry-verifiable.

### Phase A — App: cleartext exception removed
`app/src/main/res/xml/network_security_config.xml` flipped `cleartextTrafficPermitted="true"` → `false` for `<LAN_IP>`. Trust pinning to `@raw/helper_cert` unchanged: only the helper's own self-signed cert is accepted for this host; the system CA bundle is explicitly NOT a trust anchor here, so a global-CA-signed MITM cert is refused.

Rebuilt + uninstalled + freshly installed on `.182`. Telemetry confirmed:
- 4 distinct slots reached `EV=TILE_READY` over HTTPS only.
- **Zero** `MyMTS.HelperClient` / `ChannelsRepo` / `FeedRepo` cleartext errors.
- Lineup default (clean prefs) — Bloomberg TV / CBS Sports HQ / BBC News / CNN — all live.

### Phase B — Helper: HTTPS-only listener
`helper/deploy/docker-compose.nas.yml` updated:
- Dropped `PORT: 8091` env var.
- Dropped `8091:8091` port mapping.
- Healthcheck now hits `https://127.0.0.1:8443/health` (with `-k` since the loopback fetch isn't a MITM concern).
- HTTPS port mapping (`8443:8443`) is the only exposed port.

Helper redeployed (full `docker compose up -d --build --force-recreate`):
- Container shows `0.0.0.0:8443->8443/tcp` only — HTTP 8091 is unreachable from the LAN (curl from dev Mac to `http://<LAN_IP>:8091/health` times out).
- `/health` over HTTPS returns deployed SHA `a21f37c` + `ready: true` + 10 live channels.
- App on `.182` after force-stop + relaunch: all 4 tiles reach `EV=TILE_READY` over HTTPS-only helper.

### Updated
- `app/src/main/res/xml/network_security_config.xml` — cleartext exception removed; comment updated to record the cutover date.
- `helper/deploy/docker-compose.nas.yml` — HTTP listener stripped; healthcheck moved to HTTPS.
- `docs/THREAT-MODEL.md` — T-T4 updated to reflect "HTTPS-only, cleartext refused" rather than "transitional cleartext fallback retained." Residuals trimmed accordingly.
- `docs/OPERATIONS.md` — the dual-port migration table flipped from "staged" to **COMPLETE 2026-06-04**.

### Standing rules
- **unrelated host services: never touched.**
- **WyzeGrid** still as-found on `.182` — install was `am force-stop` + `install -r` + `am start`; `WatchdogService` foreground stayed alive through the whole cutover. WyzeGrid is the camera box's intended foreground owner and is restored to foreground at session-end Step 4.
- App tests green; helper tests green: 136. No behavior change in either test surface from this commit (compose + xml config + comment edits only).
- Helper redeployed via the standing standard — non-root, read_only, cap_drop ALL, dedicated bridge network — **never the unrelated host container**.

## Tile controls + captions OFF by default + lineup swap (2026-06-04)

The "big bite" — per-tile audio/volume controls, a captions toggle (default OFF), and a swap of the default lineup to put Bloomberg TV + CNBC at the top. Build + unit-test + telemetry-verify here; the real-remote feel pass on the controls overlay is **staged for the at-the-box finale**.

### Captions OFF by default

`StreamPlayer.createPlayer()` now sets `TrackSelectionParameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)` before `prepare()`. The wall never auto-selects a soft caption track at startup; the operator turns captions on per-tile from the new controls overlay.

Burned-in captions (pixels in the video) are NOT removable here — the per-channel caption table in `docs/findings/06-tile-controls-and-captions.md` documents which channels have a soft track (toggleable) vs. which have a burned-in transcription bar (operator picks a different channel or lives with it). Per-channel mechanisms were verified by actually fetching the master manifests and looking for `#EXT-X-MEDIA:TYPE=SUBTITLES` / `TYPE=CLOSED-CAPTIONS` lines:

| Channel | Mechanism | Toggle behaviour |
|---|---|---|
| CBS Sports HQ | Soft CEA-608 | Works |
| CNN (slate feed) | No captions declared | "not available" |
| LiveNOW from FOX | **Soft WebVTT** (not burned-in on this Akamai CDN) | Works |
| Newsmax | No captions on `index.m3u8` (master with captions doesn't resolve — variant 404) | "not available" |
| France 24 English | No captions declared | "not available" |
| Sky News | No captions declared (single-rendition variant) | "not available" |
| BBC News (worldwide shard) | No captions | "not available" |
| DW News English | Soft WebVTT (DEFAULT=NO) | Works |
| Bloomberg TV (new) | Soft track expected | Works |

### Per-tile controls overlay

SELECT on a slot row in the side menu now opens a small actions popup (the new `SlotControlsOverlay`) instead of jumping directly to the channel picker. WyzeGrid-family centered card, focusable rows:

- **Channel** → re-enters the existing channel picker; on assign or cancel, returns to the controls overlay so the operator can immediately toggle audio/captions on the chosen channel.
- **Audio** → toggles this tile audible. Single-audible-tile model — selecting "audible" here automatically mutes every other tile (`WallAudio` discipline enforced in `VideoGrid` via `setAudible(slot.index == audibleSlot)` for every player).
- **Captions** → toggles the soft caption track. Surface shows `not available on this channel` when the stream has no text track to toggle.
- **Close** → dismiss the controls overlay; the side menu stays open.

BACK dismisses just the controls overlay; the side menu stays open so the operator can navigate to another slot without reopening MENU. Consistent with the Stage 5 channel-picker's BACK semantics.

### Preferences persist via LineupStore

Existing `SharedPreferences("mymts_lineup")` gets two new keys:
- `audible_slot` → `Int` (`-1` = wall muted; default)
- `captions_on_slots` → JSON array of slot indices

5 new codec tests in `LineupStoreIntSetCodecTest` pin the round-trip, deterministic encoding, negative-index rejection, and non-int tolerance.

### Lineup priority swap — Bloomberg TV + CNBC at the top

`LineupSelector.PREFERRED` updated:
```
[bloomberg-tv, cnbc, cbs-sports-hq, bbc-news, cnn, livenow-fox]
```

DW News English stays seeded for manual assignment via the menu picker; it just drops out of the default cycler's preferred tier. 5 new tests in `LineupSelectorPreferredOrderTest` pin the order.

### Seed updates

- **Added** `bloomberg-tv` → `https://bloomberg.com/media-manifest/streams/phoenix-us.m3u8` (resolves live; Bloomberg TV+ Phoenix-us feed via the official direct origin).
- **Added** `cnbc` → `.invalid` placeholder URL. CNBC has no free public HLS endpoint as of 2026-06-04 (paywalled cable, no FAST-platform free stream). Seeded honestly so the prober reports `dns_failure` correctly; logged to BACKLOG as a carry-forward.
- **Reverted** `newsmax` from `master.m3u8` back to `index.m3u8` — the master exposes captions but its variants 404, taking the channel offline entirely. Honest trade-off: keep the channel playing without an optional toggle (captions default OFF anyway) rather than lose the channel for a feature off by default.

### Resolution map after this push

| status | count | channels |
|---|---|---|
| **live** | **10** | bbc-news, **bloomberg-tv** (new), cbs-sports-hq, cnn, dw-news-en, france24-en, livenow-fox, newsmax, redbull-tv, sky-news |
| unavailable | 9 | al-jazeera-en, c-span, cgtn-en, **cnbc** (new — placeholder URL, no public HLS exists), cnn-international, iss-feed, nasa-tv, trt-world, white-house-tv |

### Clean-slate default lineup on a 4-slot wall — telemetry-verified

After `pm clear com.mymts` + relaunch:
```
slot-0 = bloomberg-tv   (PREFERRED #1 — new)
slot-1 = cbs-sports-hq   (PREFERRED #3 — cnbc unavailable, skipped)
slot-2 = bbc-news        (PREFERRED #4)
slot-3 = cnn             (PREFERRED #5)
```
`EV=TILE_READY` fired for each within 60 s. LiveNOW from FOX sits behind CNN and would fill slot 5 at N=5; available for manual assignment.

### BACKLOG additions
- **Video crop to hide burned-in captions** — out of scope by engineering default. Three reasons all operator-decision rather than engineering defaults.
- **CNBC — no free public HLS endpoint exists** — placeholder URL recorded; resolves honestly as unavailable.

### What's staged for the at-the-box finale
- **Tile controls real-remote pass.** D-pad logic works in the away-from-box telemetry layer (Compose focus on each `ControlRow`, focusable popups, BACK semantics), but the **felt experience** on the actual Onn remote needs the operator there.

### Updated
- `app/src/main/java/com/mymts/player/StreamPlayer.kt` — captions disabled by default; `setAudible(Boolean)`, `setCaptionsEnabled(Boolean): Boolean`, `hasSoftCaptionTrack()`.
- `app/src/main/java/com/mymts/data/lineup/LineupStore.kt` — `audibleSlot`, `captionsOnSlots` state + `toggleAudible`, `toggleCaptions`, `muteAll`.
- `app/src/main/java/com/mymts/ui/menu/MenuState.kt` — new `PendingSelection.SlotControls`.
- `app/src/main/java/com/mymts/ui/menu/SlotControlsOverlay.kt` — new centered popup (Channel / Audio / Captions / Close).
- `app/src/main/java/com/mymts/ui/wall/WallScreen.kt` — controls overlay wired in; SELECT on slot row → controls (not directly to picker); picker on dismiss → returns to controls.
- `app/src/main/java/com/mymts/ui/wall/VideoGrid.kt` — `LaunchedEffect(bound, audibleSlot, captionsOnSlots)` applies audio + captions to each player.
- `app/src/main/java/com/mymts/ui/wall/LineupSelector.kt` — PREFERRED list updated.
- `helper/src/mymts_helper/channels/seed.json` — Bloomberg + CNBC added; Newsmax URL reverted.
- 5 new app tests in `LineupStoreIntSetCodecTest`, 5 new in `LineupSelectorPreferredOrderTest`.
- `docs/findings/06-tile-controls-and-captions.md` — new finding doc.
- `docs/BACKLOG.md` — video-crop + CNBC entries.

### Standing rules
- **unrelated host services: never touched.**
- **WyzeGrid** as-found on `.182` — install was force-stop + install -r + am start; WyzeGrid foreground service stays alive.
- App tests green: ~80+ (now includes 10 new for this push). Helper tests green: 136.
- Helper redeployed per the standing standard (non-root, read_only, cap_drop ALL, dedicated bridge — never the unrelated host container).

## Stage 6 — TLS to the helper, baseline (2026-06-03)

The TV ↔ helper link is now encrypted with certificate pinning at the app layer. Operator-away-safe baseline: the helper serves HTTPS 8443 **alongside** HTTP 8091, and the app keeps a cleartext fallback for `<LAN_IP>` so a botched HTTPS path can't strand the box. The full cutover (cleartext exception removed + HTTP 8091 dropped from compose) is staged for the "at-the-box" finale Step 1, where the operator can physically recover the box if anything goes wrong.

### Trust mechanism — Option A (self-signed cert + pinned trust)

Per the prompt's recommendation. A single-operator LAN service doesn't need a CA — the simplest correct posture is "the app trusts that specific cert, and nothing else for that host."

- Self-signed RSA-4096 cert generated on the NAS, 10-year validity, SAN `IP:<LAN_IP>, DNS:mymts-helper`.
- The private key (`helper.key`) lives **only** on the NAS at `/srv/docker/mymts-helper/_secrets/`, mounted read-only into the container at `/etc/ssl/mymts/`, owned UID 10001 mode 600.
- The public cert (`helper.crt`) is committed at `app/src/main/res/raw/helper_cert.pem` (public material — committable). `.gitignore` excludes `*.key` and any other `*.crt`/`*.pem` under `helper/` with a narrow `!` re-include for the trust-anchor file.
- `network_security_config.xml` carries a `<domain-config>` for `<LAN_IP>` whose `<trust-anchors>` contains **only** `@raw/helper_cert`. The system CA bundle is **explicitly NOT** a trust anchor for this host — a global-CA-signed MITM cert is refused.

### Helper — dual-port serving from one app instance

`helper/src/mymts_helper/__main__.py` rewritten to build the FastAPI app once and run two `uvicorn.Server` instances concurrently sharing that one app:

| Listener | Port | Lifespan | Pollers |
|---|---|---|---|
| HTTP (transitional) | 8091 | `on` | yes — RSS poller + channel prober run once via the app's lifespan |
| HTTPS (target) | 8443 | `off` | no, but serves identical endpoints from the same app instance |

When the transitional HTTP is dropped at the finale, the HTTPS listener's lifespan flips to `on` and becomes the sole listener.

### App — pinned trust + HTTPS default

- `app/src/main/res/raw/helper_cert.pem` embedded (public cert, no key).
- `app/src/main/res/xml/network_security_config.xml` rewritten: base config refuses cleartext; the `<LAN_IP>` domain-config pins only `@raw/helper_cert`. Cleartext fallback retained transitionally.
- `gradle.properties` → `MYMTS_HELPER_BASE_URL=https://<LAN_IP>:8443`.

### Telemetry — proof (operator was away from the TV)

| Signal | Result |
|---|---|
| Helper `/health` over HTTPS 8443 from a US IP | `HTTP 200`, JSON with `build_sha`, 8 live channels reported |
| Helper `/health` over HTTP 8091 (transitional) | `HTTP 200`, same payload |
| App on `.182` after install + relaunch | 4 distinct `EV=TILE_READY` events: `slot-0-cnn`, `slot-1-dw-news-en`, `slot-2-livenow-fox`, `slot-3-cbs-sports-hq` |
| `MyMTS.HelperClient` errors in logcat | **zero** — no trust failures, no SSL exceptions, no fallback into HTTP |

The operator's preferred lineup (CBS Sports HQ + LiveNOW from FOX + the rest) is now actually playing over HTTPS — the channel-resolution + TLS tracks compound.

### Hiccup recorded for the runbook

First helper restart crash-looped with `PermissionError: [Errno 13]` on the key file — UID mismatch between the cert files (owned by host user `cargo`, UID 1000) and the container (UID 10001). Fixed without `sudo` using a short-lived root Alpine container (`cargo` has docker group membership). Documented in `OPERATIONS.md` so the next rotation doesn't trip the same way.

### What's NOT in this commit (staged for the at-the-box finale)

- Remove cleartext exception from `network_security_config.xml`.
- Remove `8091:8091` port mapping + `PORT=8091` env from helper compose.
- Flip HTTPS listener lifespan to `on`.
- Verify on `.182` with the operator at the box.

### Updated

- `helper/src/mymts_helper/__main__.py` — dual-server entry point.
- `helper/src/mymts_helper/config.py` — `https_port`, `ssl_keyfile`, `ssl_certfile` + `has_https()` predicate.
- `helper/tests/test_config_tls.py` — 4 new tests for the TLS config surface.
- `helper/deploy/docker-compose.nas.yml` — HTTPS port + cert mount + env wiring.
- `app/src/main/res/raw/helper_cert.pem` — embedded public cert (trust anchor).
- `app/src/main/res/xml/network_security_config.xml` — cert pinning + retained cleartext transitional.
- `gradle.properties` — default URL flipped to HTTPS.
- `.gitignore` — narrow private-key + helper-side cert/pem exclusion with `!` re-include for the app trust anchor.
- `docs/THREAT-MODEL.md` — T-T4 rewritten from "cleartext narrow exception" to "encrypted + pinned, transitional cleartext fallback documented, full cutover staged."
- `docs/OPERATIONS.md` — TLS section with cert generation runbook, ownership-fix gotcha, dual-port migration sequence.
- `ARCHITECTURE.md` §12 — TLS mechanism + trust model + cert-rotation contract.

### Standing rules
- **unrelated host services: never touched** — helper continues to run on its own bridge network (`mymts-net`), never the VPN container's.
- **WyzeGrid** as-found on `.182`, foreground + `WatchdogService` healthy.
- Helper tests green: **136** (132 prior + 4 new).
- App tests green (unchanged count — only resource + config changes on the app side).
- Helper redeployed to `<USER>@<HOST>` per the standing standard (non-root, read_only, cap_drop ALL, dedicated bridge network, **never the unrelated host container**).

## Channel-resolution investigation (helper-side) — 8 channels live, up from 2 (2026-06-03)

The deferred channel-resolution follow-on, now done. Three honest tracks:

### Prober deepened — master + variant validation

The prober previously validated only the master HLS manifest (`#EXTM3U` prefix check). NASA TV exposed the gap: its master fetches fine, but its variants return HTTP 404 — the helper would say `live` while the player couldn't actually play. The Stage 3 polish-pass `LineupSelector.DENY` workaround masked the truth at the menu layer; this commit fixes the cause at the helper layer.

After this commit (`helper/src/mymts_helper/channels/prober.py`):
1. Fetch master through the SSRF-safe `fetcher` (unchanged).
2. Validate `#EXTM3U` (unchanged).
3. If the master is a **media playlist** (`#EXTINF` present, no `#EXT-X-STREAM-INF`) → mark live with master URL.
4. Otherwise parse the master, find the first `#EXT-X-STREAM-INF` entry's URL line, absolutize against the master URL, and **fetch that variant** through the same SSRF-safe `fetcher`. Variant must respond `2xx + #EXTM3U`. Anything else → record precise error (`variant_http_<code>`, `variant_fetch:<reason>`, `variant_not_hls_manifest`, `variant_missing_in_master`).

All SSRF guards (https-only, DNS-pinned, RFC1918/loopback/CGNAT/ULA rejection, bounded body, bounded time, redirect re-validation) apply to the variant fetch verbatim. **No new egress surface.** 10 unit tests in `helper/tests/test_channels_prober.py` cover the master/media/variant cases including downgrade-rejection (http variant in master refused) and conservative None-on-ambiguity (intermediate tag-line skipped → return None rather than picking wrong rendition).

### Seed URL rotation — verified working candidates

Each new candidate was fetched and confirmed `HTTP 200 + #EXTM3U` from a US residential IP before being written into `helper/src/mymts_helper/channels/seed.json`:

- `cbs-sports-hq` → CBSi airspace CDN
- `bbc-news` → BBC worldwide shard (`-ww-live` instead of UK-only `-uk-live`)
- `cnn` → Turner/Warner slate feed (publicly-streamable, AES-128)
- `livenow-fox`, `newsmax` → Akamai direct
- `france24-en` → official `live.france24.com` origin (old `f24hls-i.akamaihd.net` decommissioned)
- `sky-news` → Sky CDN direct (in flight — works from dev + NAS curl, deploy-time probe returned 403; next 30-min cycle will retry)

### Net result on the helper

| status | count | channels |
|---|---|---|
| **live** | **8** | bbc-news, cbs-sports-hq, cnn, dw-news-en, france24-en, livenow-fox, newsmax, redbull-tv |
| unavailable | 9 | al-jazeera-en, c-span, cgtn-en, cnn-international, iss-feed, **nasa-tv** (variant-FAIL honestly reclassified — the deepened prober caught what the old prober missed), sky-news (in flight), trt-world, white-house-tv |

The operator's preferred lineup (CBS Sports HQ → BBC News → CNN → LiveNOW from FOX) now resolves **all four** to playable channels. The wall no longer cycles two channels into four slots.

### Carried forward to BACKLOG as honest "no public endpoint" cases

- **Geo-block circumvention** for region-locked broadcasters — explicit operator-decision item. **Not** added by default — would require re-doing the helper's egress threat-model and the operator's call on legal/TOS posture for specific broadcasters.
- **ISS Live standalone HLS** — UStream is dead and CloudFront origin DNS-fails; NASA's current standalone ISS feed is YouTube-only. NASA TV NTV1 is the honest substitute.
- **CNN International / C-SPAN** — no public free linear HLS exists; C-SPAN wraps its current web player in a session-token handshake; CNN International is no longer on any FAST platform.

### NASA TV deny-list no longer load-bearing

`LineupSelector.DENY = setOf("nasa-tv")` from the Stage 3 polish-pass was a workaround for the master-OK / variant-FAIL boundary gap. With the deepened prober, NASA TV is now honestly `unavailable` from the helper, so the deny list is documentation-of-the-shape rather than load-bearing logic. Left in place; if the variant becomes reachable again, the next probe cycle re-marks it live and the deny list can be removed in a future commit.

### Updated

- `helper/src/mymts_helper/channels/prober.py` — variant-fetch step added.
- `helper/src/mymts_helper/channels/seed.json` — 7 candidate URLs rotated.
- `helper/tests/test_channels_prober.py` — 10 new tests for the variant-validation logic.
- `docs/findings/05-channel-resolution.md` — full investigation record + resolution map.
- `docs/BACKLOG.md` — geo-block-circumvention (operator-decision), ISS-Live-standalone, CNN International / C-SPAN entries.
- `docs/THREAT-MODEL.md` — T-H5 unchanged in spirit; the prober's "live" definition is now tighter and matches the player.

### Standing rules
- **unrelated host services: never touched.**
- **WyzeGrid** untouched (helper-side; no `.182` involvement).
- Helper tests: 132 green (122 prior + 10 new).
- Helper redeployed to `<USER>@<HOST>:/srv/docker/mymts-helper/`; non-root, read_only, cap_drop ALL, dedicated bridge network — **never the unrelated host container**.

## Stage 6 — signed-install update path + rollback (2026-06-03)

Per `03-OPERATIONAL-BAR.md` B1/B2/B5 — never bricks, always a way back, no silent bad-bundle cascade. App-side + release-infra track only (does NOT touch the helper, so safe in parallel with the channel-resolution work above).

### Release signing

`app/build.gradle.kts` gains a release signing config sourced from either `app/keystore.properties` (gitignored; modelled on `app/keystore.properties.example`) or four environment variables of the same name. The keystore + credentials are **secrets** — `.gitignore` excludes `*.jks`, `*.keystore`, `keystore.properties`. Loss of the keystore means losing the ability to push updates (Android refuses upgrades signed with a different key); backup-off-device responsibility documented.

A `BuildConfig.IS_RELEASE_SIGNED` boolean flips when a real keystore is wired up; the deploy script reads the actual signing certificate via `apksigner verify --print-certs` and refuses to ship a debug-signed APK (subject `CN=Android Debug,O=Android,C=US`).

### Update flow: build → install → health-gate → promote-or-rollback

`scripts/deploy-app.sh` orchestrates:
1. `:app:assembleRelease` (signed if keystore configured; falls back to debug-sign for dev, refused at install time).
2. Archive to `$MYMTS_ARCHIVE_DIR/archive/mymts-<v>+<sha>-<utc>.apk`. Older APKs are retained — manual rollback to any prior version is one command.
3. `adb install -r -t` on the target.
4. Launch.
5. Capture `MYMTS_SOAK` telemetry for `--deadline-seconds` (default 90).
6. Run `scripts/health_check.py` against the capture — pure-Python decision logic.
7. On **PASS**: atomic write of the new APK filename to `$MYMTS_ARCHIVE_DIR/known-good`.
8. On **FAIL**: reinstall the prior known-good APK; log the rollback.

The decision function returns one of `PASS / FAIL_ALL_DEAD / FAIL_DECODER_THRASH / FAIL_NOT_READY / FAIL_TIMEOUT`. The `FAIL_DECODER_THRASH` outcome catches exactly the b19b013 regression shape (decoders init repeatedly but `EV=TILE_READY` never fires) — the Stage 3 fix-forward lesson is now wired permanently into the update path. **15 unit tests** in `scripts/test_health_check.py` exercise the decision matrix.

### Operator-away safety — no destructive on-device install in this commit

The operator is currently away from `.182` and cannot physically recover a botched install. Per the prompt's `Section 3` guidance:
- **The decision logic is fully unit-tested.**
- **Dry-run validated end-to-end on this Mac**: `./scripts/deploy-app.sh --dry-run` built a signed-keystore-absent fallback APK and archived it as `mymts-0.0.0+6be42c4-20260603T232010Z.apk` without touching `.182`.
- A **live install + force-failed-health + automatic-rollback** test is **staged for when the operator is near the box**. Running it now while the operator is away carries device-recovery risk if the script misfires (e.g. if `adb` install fails to restore the prior APK). The staged test is documented in `OPERATIONS.md` so it can be picked up immediately when the recovery posture is acceptable.

### Manual rollback — the always-a-way-back guarantee

```bash
./scripts/deploy-app.sh --manual-rollback
```
Reads the `known-good` pointer (which never names a failed APK because promotion happens only on PASS), reinstalls that APK with `adb install -r -d` (`-d` allows downgrade for versionCode), restarts the activity. Manual recovery to any *older* archived APK is `adb install -r -d <archive>/mymts-X.Y.Z+sha-…apk`.

### Updated

- `app/build.gradle.kts` — signing config + `IS_RELEASE_SIGNED` BuildConfig field.
- `app/keystore.properties.example` — template; real file gitignored.
- `scripts/deploy-app.sh` — orchestrator.
- `scripts/health_check.py` — decision logic.
- `scripts/test_health_check.py` — 15 unit tests covering PASS / FAIL_ALL_DEAD / FAIL_DECODER_THRASH (b19b013 shape) / FAIL_NOT_READY / partial-dead-below-threshold / decoder-thrash-only-when-zero-TILE_READY / decision matrix.
- `docs/OPERATIONS.md` — Install + Update + rollback rows populated with the deploy flow, the artifact layout, the manual-rollback path, the dry-run mode, and the operator-away staged-pending status.
- `ARCHITECTURE.md` §11 — release/update/rollback flow + the four states an APK can be in.
- `docs/THREAT-MODEL.md` — T-A2 populated covering signed installs / never-bricks / always-a-way-back / no-silent-bad-bundle-cascade. Residual risks: keystore loss and the window-bounded health gate.

### Standing rules
- **unrelated host services: never touched.**
- **WyzeGrid** as-found on `.182` (no device install in this commit).
- App tests: ~50+ prior cases still green. Decision logic tests: 15 new cases.
- Helper untouched in this track.

## Stage 5 checkpoint 2 — channel picker + persistence, lineup control complete (2026-06-03)

Stage 5 makes the wall operable from the couch. Checkpoint 1 (commit `e803fee`) shipped the menu shell — open/navigate/back. Checkpoint 2 (this commit) wires the actual feature: real per-slot channel labels, the centered TV-style channel picker with honest live/offline marking, on-device lineup persistence, and telemetry-verified reassignment.

### Added — TV-side menu (channel/lineup control only — settings/layout/diagnostics deferred to BACKLOG)

- **`ui/menu/ChannelPickerOverlay`** — centered TV-style popup (WyzeGrid signature pattern). Shows the focused channel as `< Channel Name (status) >`; D-pad LEFT/RIGHT cycles the helper's channels (wrapping at the ends); SELECT/CENTER assigns; BACK cancels. Each channel decorated with its real current status (`live` / `offline`); the operator cannot mistake an offline channel for one that will play (Trust Bar C3 at the menu layer).
- **`data/lineup/LineupStore`** — SharedPreferences-backed persistence. Format: `SharedPreferences("mymts_lineup") → key "lineup_overrides"` holding a JSON array `[slot, slug, slot, slug, …]`. Deterministic encode (sorted by key) so two writes of the same lineup produce byte-identical disk state. Tolerant of corrupt blobs (resets + logs once). **No secrets, no PII, no absolute paths** — only short slug strings the operator chose.
- **Mark-and-allow** policy for offline-pinned slots: the picker permits assigning an offline channel but marks it visibly; the wall renders the C2 honest panel for that slot with the assigned channel's label, distinct from an unassigned blank tile. (Rationale recorded in `docs/findings/04-stage-5-menu.md` — the wall already degrades honestly, and the operator may want a channel queued for when it returns.)

### Refactored — TileSlotResolver and the slot list as a single source of truth

- **`TileSlotResolver.Slot.Offline(index, channel)`** — new variant for an operator-pinned-but-not-currently-live channel. Renders the C2 panel with the channel's label. **Has no `spec` field by construction** — so the structural label/stream binding from `9d5b0ad` (`BoundTile.init { require(player.specId == slot.spec.id) }`) cannot misfire on an offline slot; the type system rules out a mispaired player.
- **`TileSlotResolver.resolve(tileCount, defaultChannels, allChannels, overrides)`** — new signature accepts the override map. Overrides take priority over the default cycler; the cycler's pool filters out operator-pinned slugs so a pinned channel never double-fills another slot. Saved slugs that no longer match any helper channel **fall through gracefully** to the default cycler — the operator never sees a label for a vanished channel.
- **`WallScreen` is now the owner** of the slot list. Computes it once per recomposition from `(allChannels, defaultOrder, overrides)` and passes the same `List<Slot>` to both `VideoGrid` and `MenuOverlay`. The menu and the wall **cannot disagree** about which channel is in which slot.

### Telemetry — the proof (operator was away from the TV)

The Stage 3 lesson held: a tile that fails to start is pixel-identical to an honest dead tile, so appearance proves nothing. Verified via telemetry only:

| Behavior | Evidence |
|---|---|
| **Reassign slot 0 to a different live channel** | After `MENU → SELECT → RIGHT × 2 → SELECT` to pin `redbull-tv` to slot 0: `EV=DECODER\|id=slot-0-redbull-tv` at 17:56:35 → `EV=TILE_READY\|id=slot-0-redbull-tv` at 17:56:36. New spec id = new player = real first frame, not just a label swap. |
| **Persistence across force-stop and relaunch** | `shared_prefs/mymts_lineup.xml` on disk contained `[0,"redbull-tv"]`. After `am force-stop` + relaunch: `EV=TILE_READY\|id=slot-0-redbull-tv` (restored from disk), not `slot-0-dw-news-en` (the default cycler's first pick). |
| **Offline assignment never starts a player** | After pinning `slot 0 → cnn` (offline) via direct prefs write: decoders started for slot-1-dw-news-en, slot-2-redbull-tv, slot-3-redbull-tv; **zero events** for `slot-0-cnn` or `slot-0/offline-cnn`. The C2 panel rendered with `CNN` as the ghost label (screencap evidence). |
| **No capacity blowout** | ~10 decoder inits across the full test (4 baseline + 3 rebind + 3 post-restart). Zero recovery strikes. Zero `EV=DEAD`. Stage 2 N=4 ceiling holds. |

Evidence: `docs/findings/runs/stage-5-checkpoint-2-20260603-1755/` (picker screencap + wall-with-cnn-offline screencap + notes.md).

### Tests added

- **`MenuStateTest`** (8 cases, checkpoint 1) — visibility + sub-overlay state.
- **`TileSlotResolverOverridesTest`** (7 cases) — override-to-live → Playing, override-to-offline → Offline with label, override-to-vanished-slug → graceful fallback, no double-fill, every-slot-pinned-and-no-spares → honest Empty tail, Offline carries no spec.
- **`LineupStoreCodecTest`** (7 cases) — round-trip preservation, corrupt-JSON safety, odd-length blob handling, invalid-pair skipping, deterministic encoding.

### Updated

- **`ARCHITECTURE.md`** §10 — new menu module map, slot-list-as-single-source-of-truth diagram, slot-variant table, persistence format, D-pad model, honesty rules carried through.
- **`docs/THREAT-MODEL.md`** — new `T-T6` (LineupStore persistence; no-secrets-by-construction) and `T-T7` (menu UI honesty; picker reads same `Channel.isPlayable` the player does). Stage 5 row in the gate table marked done.
- **`docs/findings/04-stage-5-menu.md`** — new finding doc covering the interaction model, the mark-and-allow decision, the telemetry proof, the persistence format.

### Standing rules held
- **unrelated host services: never touched.**
- **WyzeGrid** as-found on `.182`, foreground + `WatchdogService` healthy. No disable.
- Helper untouched (UI-only commit).
- App test count ~50+ cases.

## Stage 3 fix-forward — video startup regression repaired (2026-06-03)

The Stage 3 follow-up commit `b19b013` (label/stream binding made structural) introduced a Compose-timing bug that broke video startup. The honesty rule itself was correct; the wiring was wrong. Telemetry — not visual inspection — surfaced it.

### What broke

`VideoGrid` cached the player-by-spec-id lookup in `remember(manager, specs) { … manager.player(idx) … }`. That `remember` block evaluates **during composition**, before the `DisposableEffect` that registers the manager as a lifecycle observer has run. So `manager.player(idx)` returned `null` for every idx, the materialised map permanently cached nulls, and — because `(manager, specs)` was stable across recompositions — nothing invalidated the cache once `manager.onStart` later populated real players. Each `BoundTile` permanently held `player == null`; `WallTile → PlayingTile` early-returned the C2 dead panel; `StreamSurface` was never mounted; no SurfaceView attached to any ExoPlayer; `onRenderedFirstFrame` never fired; `EV=TILE_READY` stayed at 0. The LivenessTracker honestly settled all 4 tiles into `DEAD` after the recovery ladder — correct mechanism, wrong cause.

The C2 dead-panel UI made the regression **visually identical** to a genuine network failure. The original commit message attributed it to an "Akamai blip" — that hypothesis was false. Both Akamai origins responded `HTTP 200` from the dev Mac and were pingable from `.182` at ~12–36 ms RTT with 0% loss.

### The fix

- **`StreamPlayerManager`** now exposes `val readyVersion: State<Int>` — a Compose-observable signal backed by `mutableIntStateOf(0)`, incremented inside `onStart` *after* players are created (and again inside `onDestroy` so observers can collapse cleanly).
- **`VideoGrid`** now reads `manager.readyVersion.value` (subscribing the composable to its changes) and includes it in `remember(slots, manager, readyVersion) { bindTiles(…) }`. When `onStart` flips the version, Compose invalidates the cached binding, the lookup re-runs with the now-populated manager, and the bound tiles carry real players. `StreamSurface` mounts, surface attaches, video renders, `EV=TILE_READY` fires.
- `StreamPlayerManager` also gains a `playerFactory` constructor parameter (defaults to `::StreamPlayer`) so the readiness mechanism is unit-testable without spinning up real ExoPlayer instances.
- **The structural honesty rule** (`BoundTile.init { require(player.specId == slot.spec.id) }` + `bindTiles` identity-pairing + Compose `key(tile.key)` per tile) is **unchanged**. The regression was in *when/how* the binding was wired, not in the rule.

### Telemetry — the proof

| Signal (~90 s after launch) | `b19b013` | After this commit |
|---|---|---|
| `EV=TILE_READY` | **0** | **4** (one per slot, within 60 s) |
| `EV=DECODER` | 12 (3 strikes × 4 tiles) | 4 (one per slot, no recovery thrashing) |
| `EV=DEAD` | 4 (all tiles settled DEAD) | **0** |
| LIVE transitions | 0 | 7 (4 initial + 3 bursty self-resolutions) |
| Wall appearance | 4 C2 dead panels | 4 live video tiles (verified by screencap) |

Stream URLs and network conditions were identical across both runs. The variable was the code. Screencap evidence: `docs/findings/runs/stage-3-fixforward-20260603-1658/`.

### Regression tests

- **`StreamPlayerManagerReadinessTest`** (4 cases) — direct probe of the readiness mechanism. Verifies `readyVersion == 0` and `player(idx) == null` before `onStart`; verifies `readyVersion` increments and players are populated by the factory after `onStart`; verifies `onStart` is idempotent; verifies `onDestroy` releases + bumps. **These tests would not compile against `b19b013`** because the `readyVersion` property does not exist there — the strongest form of "fails on broken / passes on fix."
- **`VideoGridBindingTest`** (3 cases) — documents the broken vs fixed Compose-`remember` patterns by simulating cache semantics in plain Kotlin. The "buggy wiring without readyVersion key" test demonstrates that the regression's pattern caches nulls forever; the "fixed wiring keyed on readyVersion" test demonstrates that the fix invalidates correctly when readiness flips.

The pre-existing `BoundTileTest` (4 cases) guards the honesty rule but did **not** catch this wiring bug — those tests pass a fully-populated player map, exercising correctness *assuming* players exist. That gap is closed by the two new test suites above.

### Meta-lesson — for the permanent record

**Honest-degradation UI can mask a startup regression.** A `BoundTile` with `player == null` rendered the C2 quiet dead-panel — pixel-identical to a tile whose player legitimately settled DEAD after the recovery ladder ran. The C2 rule (graceful degradation, no error chrome) is correct; it just turns out to be the worst-case UI signal during a startup regression because it normalises "no video" as "honest about being offline."

**Corollary: after any change to the video-pipeline wiring, telemetry (`EV=TILE_READY`, `EV=DECODER`, state-machine transitions) is the verification standard — not visual inspection.** This stage's first verification was visual ("the wall looks right") and the regression slipped past. The operator's directive to verify with telemetry (no display needed) is what surfaced it.

### Standing rules held
- **unrelated host services untouched.**
- **WyzeGrid** as-found on `.182`, foreground + `WatchdogService` healthy.
- App tests green: `StreamPlayerManagerReadinessTest` (4) + `VideoGridBindingTest` (3) added; total app unit-test count ~36+.
- Helper untouched (the bug was TV-only).

## Stage 3 follow-up — channel-identity honesty + lineup corrections (2026-06-03)

### Honesty fix — label and stream cannot drift (the important one)

Operator review of the polished wall identified a tile-label / stream desync — a tile labelled "X" could end up playing channel "Y" as the resolution/backfill order changed. **This is a labelling lie**, the channel-identity sibling of Trust Bar C3 (no silent staleness). Fixed structurally:

- **New `com.mymts.ui.wall.BoundTile`** pairs a slot with the player whose `spec.id` matches that slot. `init { require(player.specId == slot.spec.id) }` throws at construction if the pairing is wrong. A tile labelled "X" cannot end up playing channel "Y" because the type system + runtime check forbid it.
- **New `bindTiles(slots, findPlayer)`** pairs by identity match on `spec.id`, never by index. A stale player from a prior recomposition is discarded (`.takeIf { specId match }`); the tile renders the same C2 panel as a settled-DEAD tile rather than drawing the wrong channel's video.
- **`WallTile` rewritten** to take a `BoundTile` parameter; no internal player lookup. Label, state, and player all flow from the same already-paired object.
- **`Compose key(tile.key)`** wraps each `WallTile` in `VideoGrid` so when a slot's channel changes the tile is rebuilt from scratch — no stale label or surface state can leak across the identity change.
- **4 regression tests** in `app/src/test/java/com/mymts/wall/BoundTileTest.kt` exercise the operator's described scenario (preferred fail, fallbacks backfill, labels still match), the runtime-check fires for a deliberately-wrong pairing, the lookup-miss case renders null rather than a wrong-channel player, and the stale-specId player is discarded.

### Lineup corrections

- **CBS Sports HQ**: prior candidate was CBS *News*, not Sports HQ — replaced with a Samsung TV+ Wurl pattern guess (`cbssports-cbssports-1-us.samsung.wurl.tv/playlist.m3u8`). Helper prober verdict: **DNS failure** — that candidate doesn't resolve from this network. Honest plain-text result recorded: a working current public HLS endpoint for CBS Sports HQ could not be reliably found without web access. Per the operator's "honest OFFLINE rather than the wrong channel mislabeled" instruction, the slug stays in the seed but doesn't occupy a default slot until a working endpoint is found.
- **ISS feed**: added new `iss-feed` channel slug in `LineupSelector.FALLBACK` in NASA TV's place. ISS candidate URL: `iphone-streaming.ustream.tv/uhls/17074538/streams/live/iphone/playlist.m3u8`. Helper prober verdict: **SSL certificate hostname mismatch** — UStream's cert is no longer valid for that hostname. Honest result: candidate doesn't resolve.
- **NASA TV moved to a deny list**: new `LineupSelector.DENY = setOf("nasa-tv")`. NASA TV's master HLS manifest passes the prober but its variant playlist fails for ExoPlayer; the slug stays seeded (so the operator can re-enable it once the prober is deepened to variant-fetch — already in BACKLOG) but is structurally excluded from the default lineup, including from the "rest" backfill tier. 3 unit tests in `LineupSelectorDenyTest.kt` pin this.

### Net result on the wall

Helper currently reports `dw-news-en`, `redbull-tv`, and `nasa-tv` as live. `LineupSelector.forWall(4)`: preferred + fallback (10 slugs total) all unavailable, NASA TV denied in the "rest" tier → only `dw-news-en` and `redbull-tv` survive, `TileSlotResolver` cycles `[dw, redbull, dw, redbull]` into the 4 slots. Same shape as the Stage 2 v2 long-soak.

Evidence: `docs/findings/runs/stage-3-followup-20260603-1235/wall-followup-c2-panels-honest.png` — captured during an external Akamai network blip when all 4 tiles were settled in honest C2 OFFLINE panels. Each label correctly matches its assigned channel; NASA TV is denied even though the helper has it as live. The honesty rules hold even under adverse network conditions.

### Standing rules held

- **unrelated host services untouched.**
- **WyzeGrid** as-found on `.182`.
- App tests green: 4 `BoundTileTest` + 3 `LineupSelectorDenyTest` added on top of prior suite. Helper tests green (122).

### Carried forward to BACKLOG

The channel-resolution-investigation entry was extended to cover four sub-tracks: (1) find current working candidate URLs for CBS Sports HQ + ISS feed, (2) deepen the prober to variant-fetch (turns NASA TV and any other master-OK-variant-FAIL into real lineup options), (3) geo-blocks (BBC News, C-SPAN — needs an egress strategy with non-trivial threat-model implications), (4) DNS-blocked candidate-URL rotation (CNN, LiveNOW, Newsmax, CNN International).

## Stage 3 — the wall on the TV (CLOSED 2026-06-03)

### Added — TV-side wall UI

- `data/helper/HelperClient` + `Channel` / `ChannelsSnapshot` / `FeedItem` / `FeedSnapshot` — minimal `HttpURLConnection`-based reader for the helper's `/api/channels` + `/api/feed`. `schema_version == 1` pinned; unknown versions refused. Connect 4 s / read 6 s timeouts. Real `org.json` on the unit-test classpath; Android's bundled copy is a stub there.
- `data/helper/ChannelsRepository` (30 s poll) + `FeedRepository` (60 s poll). Neither ever silently empties the last good snapshot on error — both expose a freshness signal the UI reads to decide between "current and calm" and "honest stale" (Trust Bar C3).
- `ui/wall/WallScreen` — single-screen assembly: `TickerStrip` across the top, `Row { FeedPane(weight 0.28) | divider | VideoGrid(rest) }` beneath.
- `ui/wall/VideoGrid` + `WallTile` — the N=4 tile grid. Polish-pass: **autofit** by equal `weight(1f)` rows × columns; **fit-width-letterbox** scaling moves into `StreamSurface` via Media3 `PlayerView` + `RESIZE_MODE_FIT`. Each tile fills its cell width with clean black bars top/bottom where dimensions differ.
- `ui/wall/WallTile` state-aware rendering: LIVE shows no badge (the picture is the signal), CONNECTING / STALE / RECOVERING dim the surface and label transparently, DEAD / OFFLINE collapse to a quiet near-black panel with a ghost channel label (Trust Bar C2 — graceful degradation made visible).
- `ui/wall/TileSlotResolver` — pure cycler. Given K live channels and N slots, deterministically returns `[c0, c1, …, c0, c1, …]` (the v2-long-soak shape) with stable slot ids so Compose `key()`s reuse `ExoPlayer` instances across recompositions. 8 unit tests pin the 0/1/2/4/5 × N=4 cases + tileCount edge cases.
- `ui/wall/LineupSelector` — preferred → fallback → rest, capped at N. `forWall(N)` exposes the operator's preferred lineup (`cbs-sports-hq`, `bbc-news`, `cnn`, `livenow-fox`) + 5-slug fallback (`c-span`, `nasa-tv`, `white-house-tv`, `newsmax`, `cnn-international`). 10 unit tests pin priority order, partial-resolution backfill, dedup across lists, and the maxCount cap.
- `ui/wall/FeedPane` — 10-foot UI list of `FeedRow`s with `RelativeTime` chips (`now/Nm/Nh/Nd/date`, 7 unit tests). PaneHeader carries a calm staleness label. **Every field renders as Compose `Text` — no `WebView`, no HTML rendering path on the wall.** Trust Bar A1 boundary held at the UI layer.
- `data/ticker/TickerSource` + `SampleTickerSource` — pluggable interface for a future real markets feed; the Stage 3 implementation emits 16 static placeholder entries with `isSample = true` on every one. 4 unit tests pin the `isSample` contract + asset-class variety.
- `ui/wall/TickerStrip` — `basicMarquee` scroller. Per-cell `SYMBOL · value · ▲/▼/■` plus a visible `SAMPLE` pill so the operator can never mistake the values for live quotes (C3 applied to the ticker).
- `MainActivity` routes the default mode to the wall; `--es mode soak` still launches the Stage 1/2 soak harness; `--es mode placeholder` reaches the build-identity smoke screen. `MYMTS_HELPER_BASE_URL` in `gradle.properties` → `BuildConfig.HELPER_BASE_URL`.
- `app/src/main/res/xml/network_security_config.xml` — narrow cleartext allowance for `<LAN_IP>` only; the base config is `cleartextTrafficPermitted=false`. Stage 6 will replace this with TLS + internal-CA pinning.

### Added — helper

- `feeds/seed.json` + `feeds/seeder.py` — idempotent boot-time RSS-source upserter mirroring the channels-seed pattern. 4 known-good general-news sources seeded by default (BBC World, Al Jazeera, Guardian World, NPR World). 7 unit tests pin idempotency, malformed-input handling, and skip-invalid-row behavior.
- `channels/seed.json` expanded from 7 to 16 entries — operator's preferred 4 + fallback 5 added to the existing 7 candidates.

### Polish pass — fit-width-letterbox + autofit + preferred-lineup

- `StreamSurface` rewritten from raw `SurfaceView` to Media3 `PlayerView` with `useController=false`, `setShowBuffering(NEVER)`, `resizeMode = RESIZE_MODE_FIT`. The wall's tile UI is the single source of truth for state; player chrome is hidden.
- `VideoGrid` switched from `aspectRatio(16:9)`-per-tile to equal-weight rows × columns. Letterboxing now happens inside each tile, not by sizing the tile.
- `LineupSelector` wired into `VideoGrid` via the `lineupSelector` lambda; `WallScreen` passes `LineupSelector.forWall(maxCount = tileCount)::invoke`.

### Channel-resolution result (the start of the resolution-investigation record)

After the polish-pass redeploy seeded all 16 channels and the helper's prober ran one sweep:

- **4 channels live and playable**: `cbs-sports-hq` (operator's #1 preference), `dw-news-en`, `redbull-tv`, plus `nasa-tv` whose master manifest passes the prober but whose variant playlist fails for ExoPlayer with `ERROR_CODE_IO_BAD_HTTP_STATUS`. The wall renders the C2 OFFLINE panel for NASA TV — honest. Full per-channel result in `docs/findings/03-stage-3-wall.md §3`.
- **12 channels unavailable**: a mix of `http_403` (geo-block on BBC News + C-SPAN), DNS failure (CNN / LiveNOW from FOX / Newsmax / CNN International — candidate Rakuten / Samsung TV+ / Akamai endpoints didn't resolve), `http_400` (manifest reject — France 24, Sky News), `http_404` (White House TV URL guessed), and an SSL handshake failure (TRT World).
- **Stage 3 ships with what resolves;** the broader channel-resolution investigation (DNS-over-HTTPS, geo-egress, candidate-URL rotation, deepening the prober to variant-fetch) is **carried forward to BACKLOG** as its own scoped effort, not folded into Stage 3.

### Honest constraints recorded at Stage 3 close
1. **Channels: real but currently limited** (4/16 playable; cycler fills the wall from whatever resolves).
2. **Feed: real** (BBC World, Al Jazeera, Guardian World, NPR World — render as native text).
3. **Ticker: real styling, sample data** (`SAMPLE` pill on every cell; honest by construction).

### Updated

- `docs/THREAT-MODEL.md` — Stage 3 row populated with `T-T1` (re-confirmed Stage 2 mechanism is what the UI surfaces) through `T-T5`: HTML-injection-into-feed (no WebView), non-helper-URL ingestion (by construction), cleartext-on-LAN (narrow allowance, Stage 6 TLS), schema-version pinning + structural parsing of `/api/channels` + `/api/feed`.
- `ARCHITECTURE.md` — Stage 3 §9: UI module map, the autofit + fit-width-letterbox model, lineup-selection priority order, helper-host boundary.
- `docs/BACKLOG.md` — 5 new entries (partial channel-resolution investigation, configurable feed-pane width, configurable / scalable panes, in-app menu / settings (which IS Stage 5), browser / PWA client with the load-bearing security caveat from `04-TECHNICAL-APPROACH.md §1` preserved verbatim).

### On-device evidence

- Checkpoint A (2026-06-02 20:03): 4 tiles cycled `[dw, redbull, dw, redbull]` reached LIVE after one PREPARE strike; screencap at `docs/findings/runs/stage-3-checkpoint-a-20260602-2004/`.
- Checkpoint B (2026-06-02 20:18): full assembled wall on the TV; Red Bull origin went down ~24 min in and settled `DEAD` honestly via the full ladder — C2 panels rendered; screencap at `docs/findings/runs/stage-3-checkpoint-b-20260602-2018/`.
- Polish (2026-06-03 11:09): `[cbs-sports-hq, nasa-tv, dw-news-en, redbull-tv]` lineup; NASA TV's master-OK/variant-FAIL settled DEAD per the 3-strike ladder, C2 panel rendered; screencap at `docs/findings/runs/stage-3-polish-20260603-1109/`.

### Standing rules held
- **unrelated host services: never touched** for any reason across the stage.
- **WyzeGrid:** re-enabled on `.182` at the end of every device run. No soak windows opened during Stage 3.
- App test suites green: `TileSlotResolverTest`, `HelperClientParseTest`, `HelperClientFeedParseTest`, `RelativeTimeTest`, `SampleTickerSourceTest`, `LineupSelectorTest` + Stage 1/2 carry-over.
- Helper test suites green: `test_feeds_seeder` (7) + prior 115 = 122.


### Stage 1 (apparatus + skeletons; GATE preliminary — long soak pending)

#### Added — TV app
- Compose-for-TV + Media3 (HLS only) Android TV app skeleton, target Onn 4K (Android 14 / API 34).
- `app/build.gradle.kts` pinned to WyzeGrid's validated versions (AGP 8.7.3, Kotlin 2.0.0, Compose BOM 2024.11.00, Media3 1.5.0).
- `MainActivity` with two modes via intent extras: default placeholder + soak harness.
- `StreamSpec` / `StreamPlayer` / `StreamPlayerManager` — ExoPlayer wrapper with lifecycle discipline; HLS-only by construction (RTSP rejected by `StreamSpec` constructor + unit test).
- Soak harness (`com.mymts.soak.*`) emitting structured pipe-delimited logcat lines on tag `MYMTS_SOAK` for host-side parsing.
- Public-live-HLS fixture pools (`SoakFixtures.LIVE` + `SoakFixtures.STABLE`).
- `BuildConfig.DEFAULT_MAX_TILES` config-driven from `gradle.properties:MYMTS_DEFAULT_MAX_TILES` so re-homing later is a property change, not a code change.
- 9 unit tests (StreamSpec scheme guard, SoakFixtures invariants).

#### Added — helper
- Python/FastAPI helper skeleton with pinned `/health` schema (`schema_version: 1`).
- Structured JSON logging with paranoid redaction pass (Bearer/Authorization/X-API-Key/internal-IPv4s).
- Local-dev Dockerfile + docker-compose (non-root, all caps dropped, read-only rootfs, tmpfs `/tmp`, `no-new-privileges`, cpus/mem caps).
- Phantom (demo / offline) mode hook surfaced via `/health.phantom`.
- 12 helper unit tests (health schema, log-redaction).

#### Added — apparatus
- `scripts/soak.sh` — host-side soak runner; installs APK, starts soak mode, captures `events.log` + `meminfo.csv` per run.
- `scripts/parse-soak-log.py` — turns a run directory into the markdown table that lives in the finding doc.
- `scripts/deploy.sh` — Stage 1 dev-sideload (Stage 6 will replace with signed-install update path).
- `docs/findings/01-onn4k-tile-budget.md` — method + interim default + the long-soak command.
- `docs/findings/runs/20260530-method-validation-6t-stable/` — first run, 12 min at 6 tiles, stable pool, PSS ~100 MB, no leak signal in that short window. Method-validation only.

#### Documented
- `ARCHITECTURE.md` — Stage 1 module map; WyzeGrid pattern reuse + hard boundary (no RTSP/camera/Wyze-bridge wiring).
- `docs/OPERATIONS.md` — toolchain versions, single-command runners, soak instructions.
- `docs/THREAT-MODEL.md` — unchanged; still skeleton.

### Gate status — Stage 1 closed 2026-06-01

- **Leak behavior: PASS.** v3 captured 681 post-warmup samples over 11.5 h; PSS slope −15.97 KB/min, median 124.0 MB (band 114.3–142.0 MB). No leak.
- **6-tile sustained capacity: NOT YET MEASURED, deferred to Stage 2.** Three soaks degenerated to ~1 active tile within 5–8 min because (a) the validated fixture pool was still mostly VOD-as-live test assets that fall off the live window and never re-enter, and (b) `StreamPlayer` does not recover stalled tiles and dishonestly continues to report `state=LIVE` over a frozen surface (a direct Trust Bar **C3** violation, observed in all three soaks). Both fixes are Stage 2 work; the capacity re-soak runs after both land. See `docs/STAGE-2-PLAN.md`.
- **Configurable default:** `MYMTS_DEFAULT_MAX_TILES=6` unchanged; annotated as a *target to be validated in Stage 2*, not a measured-safe number.
- **Stage 2 unblocked.** Entry point is `docs/STAGE-2-PLAN.md §B.1` (player robustness) → §A (the helper) → §C (the capacity re-soak).

### Closing housekeeping
- WyzeGrid re-enabled on `.182` and verified back to normal operation (foreground, watchdog service active).
- `docs/findings/runs/**/events.log` is gitignored going forward (raw captures are 30+ MB and regenerable; the small files + the parsed summary in the finding doc are the durable record).
- The v1 and v2 run directories were removed (superseded). The v3 directory is retained as the closing record.

## Stage 2 Part A — the helper (shield first)

Per the Stage 2 prompt's `A → B → C` sequencing override (recorded in `docs/STAGE-2-PLAN.md`).

### Added — helper internals
- `db.py` + numbered SQL migrations + `meta.schema_version` (initial migration creates `sources`, `feed_items`, `channels`, `meta`).
- SSRF-safe `fetcher` — single outbound primitive used by both the RSS poller and the channel prober. Enforces https-only, up-front DNS resolution, rejection of RFC1918 / loopback / link-local / CGNAT / IPv6 ULA / IPv6 loopback addresses, bounded body, bounded time, re-validated redirects. Injectable resolver for tests.
- `feeds/` — defensive RSS/Atom parser (feedparser + defusedxml auto-loaded, HTML stripped to plain text), sqlite store with dedup + retention sweep, 5-min poller with per-source error isolation, `/api/feed` + `/api/feed/sources`.
- `channels/` — structured URL validation (scheme + IDNA host + port + path), idempotent seed loader (operator-curated `seed.json` upserts on every boot), 30-min prober that validates upstream `#EXTM3U` before marking a channel `live`, `/api/channels` masking `current_url` to `null` whenever `status != "live"` (Trust Bar C3).
- `phantom.py` — phantom resolver that raises on every hostname lookup + a preload that seeds synthetic fixtures so phantom mode shows real state in `/health` / `/api/feed` / `/api/channels` with zero outbound traffic.

### Added — `/health` (still `schema_version: 1`)
- `feeds: {sources_count, items_count, stale_sources, last_poll_at}`.
- `channels: {channels_count, live_count, unavailable_count, last_probe_at}`.
- Adding fields is backward-compatible; bump only on remove/rename. Contract test pins the shape in `tests/test_health.py` + `tests/test_api.py`.

### Added — NAS deploy
- `helper/Dockerfile` pins the runtime base by digest (`python:3.13.1-slim-bookworm@sha256:031ebf3cde…`); the tag stays in `FROM` for human readability but the digest is source of truth.
- `helper/deploy/docker-compose.nas.yml` — non-root (uid 10001), `read_only: true`, `cap_drop: [ALL]`, `no-new-privileges`, tmpfs `/tmp`, cpu/mem caps, healthcheck wired to `/health`, named Docker volume for state, dedicated bridge network, host port `8091` bound for the TV.
- `scripts/deploy-helper.sh` — rsync source → render compose → render `.env` → `docker compose build --pull && up -d` → poll `/health` until the new SHA is reflected. **Deployed to `<USER>@<HOST>:/srv/docker/mymts-helper/`**; verified `/health` reports the deployed SHA, both seeded channels probed live from the NAS network.
- Deploy doc, backup/restore commands, image-digest-refresh recipe, and the claude-status-bot `/health` contract recorded in `docs/OPERATIONS.md`.

### Standards held
- All 115 helper tests green. ruff clean.
- Conventional commits, secrets only via `.env`, no absolute paths committed, the unrelated host container untouched.

### Stage 2 progress
- **Part A — helper: DONE** (this entry).
- **Part B — player robustness:** next session per the Stage 2 prompt's checkpoint instruction.
- **Part C — capacity probe:** after B.

## Stage 2 Part B — player robustness (Trust Bar C3 fix)

Full details + on-device evidence in `docs/findings/02-player-state-machine.md`.

### Added
- **`com.mymts.player.LivenessTracker`** — pure Kotlin state machine that derives liveness from actual frame arrival, not ExoPlayer's reported `playWhenReady`/`playbackState`. States: `CONNECTING / LIVE / STALE / RECOVERING / DEAD / OFFLINE`. Configurable thresholds + recovery-ladder shape + backoff schedule, injectable clock for tests.
- **17 unit tests** in `app/src/test/java/com/mymts/LivenessTrackerTest.kt` covering: threshold boundaries, the recovery ladder (PREPARE → REINIT × 2 → SETTLE_DEAD), anti-loop discipline (DEAD is absorbing; late frames don't silently revive), the never-connected path (CONNECTING → DEAD via the 30 s connecting timeout), configurable thresholds.
- **`StreamPlayer` rewrite** — drives the tracker via a 2 s handler tick. Feeds the tracker from both `onRenderedFirstFrame` and `onDroppedVideoFrames` (any decoder callback = liveness signal). Emits `EV=STATE`/`EV=RECOVERY`/`EV=DEAD` on transitions. Widens `state` over `{CONNECTING, LIVE, STALE, RECOVERING, DEAD, OFFLINE}`. SoakHarness tile UI updated with color mappings for the new states.
- **`scripts/parse-soak-log.py`** counts state transitions, recovery strikes (by kind), and DEAD settlements per tile.

### Chosen values (with reasoning in the finding doc)
- `staleThresholdMs = 15 s`
- `connectingThresholdMs = 30 s`
- `maxRecoveryAttempts = 3`
- `backoffMs = [2 s, 8 s, 30 s]`
- `tickIntervalMs = 2 s`

### On-device demonstration (`.182`, helper at `<LAN_IP>:8091`)
- **Healthy stream (helper-resolved `redbull-tv`, 75 s):** stayed `LIVE`; one transition `CONNECTING → LIVE`; no spurious `STALE`.
- **Unreachable URL (`httpbin.org/status/404`, 150 s):** full recovery lifecycle captured — `CONNECTING → STALE → PREPARE → STALE → REINIT → STALE → REINIT → DEAD` in ~120 s. After `DEAD`, no further events fire for the tile. This is the Stage 1 v3 silent-staleness failure shape, now caught and surfaced honestly within a bounded window.

### B.2 — dw-news-en investigation
- Stage 2 measurement (180 s solo, WyzeGrid disabled): **0.12 callbacks/s** (down ~70× from Stage 1's 8.2 / s under 6-tile contention).
- 21 STATE transitions over 180 s, all `LIVE ↔ STALE` oscillations with median recovery of ~75 ms — well before strike 1's 2 s backoff fires. The state machine handles bursty streams correctly.
- **Buffer-sizing change deferred to Part C** per the Part B prompt — the loosen-vs-keep decision interacts with memory-per-tile, which directly affects the capacity ceiling Part C measures.

### Trust Bar C3 now enforced at both ends
- Helper API masks `current_url → null` whenever `status != "live"` (Part A).
- TV player surfaces `STALE`/`DEAD` to the UI; the wall **cannot** show a `LIVE` badge over a frozen surface (Part B).
- T-T1 in `docs/THREAT-MODEL.md` closed with this evidence.

### Standards held
- All app unit tests green (17 LivenessTracker + 5 SoakFixtures + 4 StreamSpec).
- WyzeGrid was disabled on `.182` for the integration runs and **re-enabled afterward** per the documented recipe.
- unrelated host services untouched.
- No secrets / absolute paths in history.

### Stage 2 progress
- **Part A — helper: DONE.**
- **Part B — player robustness: DONE** (this entry).
- **Part C — capacity probe: next**. Now unblocked: the helper resolves real streams (A) and the player is honest about staleness (B), so Part C can measure the true sustainable tile ceiling on this hardware.

## Stage 2 Part C — capacity probe bracket (historical entry; superseded by the closeout section below)

Full details in `docs/findings/01-onn4k-tile-budget.md §"Stage 2 Part C — Escalating-probe bracket"`.

### Bracketed
- **Sustainable ceiling on Onn 4K (Amlogic S905Y4) = N=4** based on an escalating 1→6 sweep at 8 min each on `.182` with WyzeGrid disabled, helper-resolved real live channels.
    - N=1..4 — all tiles LIVE, low drops, no recovery strikes (HEALTHY)
    - N=5 — dw-news-en tiles drop ~37% of frames; `dumpsys` itself starts timing out under system_server contention (DEGRADED)
    - N=6 — every redbull-tv tile fires 2–3 RECOVERY PREPARE strikes inside the window; dw-news-en tiles never produce a first frame (DEGRADED)

### Added
- `scripts/probe-tile-count.sh` — host-side escalating-probe runner. Pulls live channels from the helper's `/api/channels`, cycles them across N tiles, runs an 8 min sweep, captures `MYMTS_SOAK` events + `dumpsys meminfo`, emits a per-tile `summary.json`. The Part C procedure is itself the **per-device portability deliverable**: re-homing to fresh hardware is "run the probe, record that box's number, set the config."
- Multi-URL ad-hoc mode in `MainActivity` (`--es urls "U1,U2" --es labels "L1,L2" --es ids "I1,I2"`) so the soak harness can be driven against arbitrary URL lists without rebuilding.
- Expanded helper seed (`france24-en`, `al-jazeera-en`, `sky-news`, `cgtn-en`, `trt-world`) — same Stage 1 finding reproduced (those 5 are blocked from this network path). The 2 helper-verified-live channels (`dw-news-en`, `redbull-tv`) cycle across N tiles for the probe.
- Helper Dockerfile dependency-pinning fix (hatchling 1.30 rejects the duplicate-path `force-include` directive 1.27 tolerated).

### Changed
- `MYMTS_DEFAULT_MAX_TILES = 4` in `gradle.properties` with bracket-evidence rationale embedded as a comment. The number is no longer "validation-pending"; it's the measured-safe ceiling for this device profile.

### dw-news-en — answered
- Stage 1 v3's 8.2 / s `onRenderedFirstFrame` rate was a **6-tile contention effect**. Solo measurement at N=1 produced 0.13 / s; healthy N=4 produced the same ~0.13 / s per tile. The stream is fine as a fixture under healthy load.

### Buffer floor — decision
- Kept at Stage 1 values (min=1.5 s / max=4 s / playback=0.5 s / 4 MB target). Rationale in the finding doc — changing two variables at once (floor + tile count) would muddy the bracket signal, and the ceiling at N=4 holds cleanly. Re-visit if/when memory budgets tighten for unrelated reasons.

### Long soak — in flight
- Run id: `long-soak-4t-20260601-2100`. 4 tiles, 6 h, helper-resolved live channels (cycled `dw-news-en` × 2 + `redbull-tv` × 2). `caffeinate -i nohup`. Early-render check passed (all 4 tiles reached LIVE within 90 s of launch). ETA `2026-06-02 ~03:00 PDT`. Results in a follow-up commit; the closeout session re-enables WyzeGrid on `.182`.

### Standards held
- 17 LivenessTracker tests + 9 prior app tests still green.
- Helper 115 tests green.
- WyzeGrid is disabled on `.182` for the long soak window; re-enable command documented and queued for closeout.
- unrelated host services untouched.

### Stage 2 status (this section — interim; final state in the closeout section below)
- ✅ Part A — helper.
- ✅ Part B — player robustness.
- 🟡 Part C — bracket done; long soak unattended (at this point in the changelog timeline). _Final Part C state recorded in the next section._
- Stage 2 closes when the long soak ends and the closeout session re-enables WyzeGrid + commits the long-soak result. _(That closeout happened — see next section.)_

## Stage 2 Part C — harness telemetry fix + v2 long-soak + closeout

### Harness telemetry bug + fix
- The first long-soak attempt (`long-soak-4t-20260601-2100`) came back with a clean memory trace but **zero per-tile telemetry** (`events.log` empty, `summary.json.per_tile = {}`). Root cause: `scripts/probe-tile-count.sh` pulled logcat as a **one-shot `adb logcat -d -s MYMTS_SOAK` at the END of the 6 h sleep**. Over 6 hours the Android logcat ring buffer wrapped many times over; every `MYMTS_SOAK` event was overwritten before the dump fired.
- Secondary bug: the meminfo sampler subshell inherited the parent script's `set -euo pipefail` and exited on the first transient `dumpsys` reply that didn't match a field — sampling died ~114 min in (revealed when bash flushed `Terminated: 15` at end-of-run).
- Fix (commit `3c101c7`): continuous background `adb logcat -s MYMTS_SOAK` stream throughout the run (with a supervisor that re-launches if `adb` blips), `adb shell logcat -G 16M` to grow the on-device buffer, `set +e` inside both background subshells, `pkill -TERM -P` at end-of-run to actually tear down the supervisor's `adb` children.
- Proof-of-fix (18 min N=4 run, `probe-fixproof-20260602-0838-4t`): events.log grew monotonically (5.8 KB → 76 KB by +11 min), all 4 tiles LIVE, 270 STATE transitions captured, meminfo flowed at 1/min throughout.
- This failure mode is on permanent record so the same trap doesn't catch a future stage.

### Long-soak v2 (`long-soak-4t-v2-20260602-0857`) — 5.13 h healthy + synchronized external-event DEAD
- **5.13 h of clean N=4 LIVE evidence.** Per-tile lifecycle now fully verifiable (the harness fix held over the full 6 h): each tile ~1,140 LIVE↔STALE oscillations from the dw-news-en burst pattern characterized in Part B, every one self-resolving in ~75 ms — zero false LIVE labels over a frozen surface across 5+ hours.
- **PSS in mature steady state (minutes 120 → 305): slope −9.53 KB/min over 184 min — PASS** the ±50 KB/min leak threshold. The +74 KB/min aggregate across the full healthy window is dominated by a one-time settling step ~h1.5 (112 → 127 MB) as caches/connection pools reach equilibrium; per-hour median h2=129, h3=128, h4=127 — flat steady state, not a leak.
- At h5.13 all 4 tiles lost frames within a **12-second window**, hitting two distinct Akamai CDN origins (`rbmn-live.akamaized.net` + `dwamdstream102.akamaized.net`) simultaneously. The state machine ran the same 3-strike ladder on each tile (PREPARE → REINIT → REINIT → SETTLE_DEAD) and all 4 settled `DEAD` within a 13-second window. After `DEAD`: no thrashing, no CPU burn, PSS drops to 82.4 MB as `releaseInternal()` frees the 4 decoders. This is the C2 anti-loop discipline working exactly per design under a real-world adverse event.
- **What the v2 run proves:** the harness fix held; the state machine is honest under sustained real load; the recovery ladder + anti-loop behave correctly under a real network outage; no memory leak in the mature steady state.
- **What it does NOT prove:** 6 h of continuous 4-tile LIVE. The strict criterion was missed by a network event, not by capacity.

### Stage 2 closeout — **CLOSED 2026-06-02**

**Sustainable ceiling on the Onn 4K (Amlogic S905Y4) = N=4** — bracket-evidenced (1→6 escalating sweep) and **5.13h-LIVE-confirmed** under verified per-tile telemetry in the v2 long soak. The strict "6.0h continuous LIVE" criterion was missed by a synchronized external Akamai network event at h5.13, **not** by capacity or memory pressure — failure was synchronized across two unrelated CDN origins inside a 12-second window (capacity failures are staggered and load-correlated; this signature is unambiguously external). Closed on the evidence rather than chase a pristine run a random network blip can deny.

**Three permanent records carried forward in this stage:**
1. **N=4 ceiling with honest framing.** Stated as bracket-evidenced + 5.13h-LIVE-confirmed (not "6h confirmed"). Re-readers see the precise evidence and the network-event caveat together; the closing rationale lives in `docs/findings/01-onn4k-tile-budget.md §"Stage 2 closeout"`.
2. **Harness telemetry bug + fix as a failure-mode-on-record.** Documented above: one-shot end-of-run logcat dump → ring-buffer wrap → zero per-tile evidence over 6h. Fix is continuous background stream + 16 MB buffer growth + `set +e` in subshells + `pkill -TERM -P` cleanup. Recorded so this trap cannot catch a future stage silently.
3. **Graceful degradation as C2/C3 validation.** When the external network event hit at h5.13, the recovery ladder ran (PREPARE → REINIT → REINIT → SETTLE_DEAD), the anti-loop discipline held (DEAD is absorbing), all 4 tiles settled honestly within 13 s, and PSS dropped to 82.4 MB confirming the decoders were released. The state machine **succeeded** at its hardest job: surfacing real-world adverse failure honestly. C2 + C3 validated under real-world conditions, not just synthetic ones.

`MYMTS_DEFAULT_MAX_TILES = 4` in `gradle.properties` — the **measured-safe ceiling** for this device profile (no longer "validation-pending"). The `Awaiting long-soak confirmation` annotation has been dropped from the comment block.

**What closing Stage 2 means in total:** the security shield (helper), the honest self-recovering player, and the verified hardware capacity are all done. Stage 3 (the real wall UI) is unblocked.

### Standing rules held
- WyzeGrid re-enabled on `.182` at end of session (foreground, watchdog service running). Verified.
- unrelated host services untouched.
- App + helper test suites green.

### Run-artifact hygiene at closeout
- Dropped `docs/findings/runs/long-soak-4t-20260601-2100/` (telemetry-bug-invalidated v1 — the failure mode is permanently recorded above; the run dir itself is dead weight).
- Dropped `docs/findings/runs/probe-fixproof-20260602-0838-4t/` (proof-of-fix run, superseded by the 6h v2 which independently demonstrates the fix held).
- Kept `docs/findings/runs/long-soak-4t-v2-20260602-0857/` — the durable evidence record (5.13h healthy + DEAD evidence + memory record).
- Kept the `probe-20260601-2007-{1..6}t` bracket-sweep dirs.

### Stage 2 status (closed)
- ✅ Part A — helper.
- ✅ Part B — player robustness.
- ✅ Part C — capacity probe. **N=4 ceiling, bracket-evidenced + 5.13h-LIVE-confirmed, terminated by external network event.**

### Known limitations carried forward
- `SoakFixtures.LIVE` URLs are public broadcaster HLS endpoints and decay over time. The first long-soak run may need updated URLs before it produces useful data; the rule is to edit the fixture file in a single commit, never silently drop dead fixtures from a result.
- Helper Dockerfile pins by tag (`python:3.13.1-slim-bookworm`), not by digest. Digest pinning lands in Stage 6 hardening.
