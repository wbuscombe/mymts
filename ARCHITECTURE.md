# Architecture

> **Status (Stage 0):** Skeleton. The two-piece shape and the boundary are locked here so subsequent stages have a stable target; concrete implementation details, data flow diagrams, and module boundaries will be filled in stage-by-stage.

For the *why* behind every decision below, see [`docs/foundation/04-TECHNICAL-APPROACH.md`](docs/foundation/04-TECHNICAL-APPROACH.md). When this document conflicts with the foundation docs, the foundation docs win.

---

## 1. The two pieces

```
┌─────────────────────────────┐         ┌──────────────────────────────┐
│   The TV app (the wall)     │         │  The helper (back-of-house)  │
│   Onn 4K, Android TV        │  ◀───▶  │  NAS, Docker, internal-only  │
│   Kotlin + Compose for TV   │         │  Aggregates news,            │
│   + Media3 / ExoPlayer      │         │  resolves stream addresses   │
│   Native text + native      │         │  Nothing else.               │
│   player. No browser.       │         │                              │
└─────────────────────────────┘         └──────────────────────────────┘
       │                                          │
       │ trusts the helper                        │ trusts nothing external
       │ talks only to helper + video sources     │ minimal egress
       │ persists operator content on-device      │ no operator content lives here
       └──────────────────────────────────────────┘
```

### The TV app owns
- The video wall and the operator's view of it.
- The news feed pane, rendered as **native text** (never a WebView).
- The ticker.
- All operator interaction (D-pad-first), the lineup, presets, settings, health-at-a-glance.
- Live video playback in the **native player** (Media3/ExoPlayer).
- **On-device persistence** of the operator's content and configuration.

### The TV app does not
- Fetch raw web content.
- Execute remote code or markup.
- Resolve live-stream addresses by itself.
- Hold any credentials for upstream services.

### The helper owns
- **News aggregation.** Pulls the operator's chosen sources on schedule, parses defensively, and serves the TV a clean, pre-vetted, structured feed.
- **Stream-address resolution.** Where a live-source playable address must be discovered or refreshed, the helper does it in isolation, with strict outbound limits, and hands the TV a ready-to-play address.
- Its own health and freshness reporting.

### The helper does not
- Store the operator's content (the lineup, presets, sources are on-device; the helper is *told* what to pull, it is not the thing the operator edits).
- Become a general backend.
- Reach anything on the operator's internal network beyond what its two jobs require.
- Fail-open: when egress controls cannot do their job, it denies and surfaces, never silently allows.

## 2. The boundary

| Property | How it's achieved (Stage 0 commitment) |
|---|---|
| TV cannot execute hostile remote markup | No browser engine; feed is native text; video plays in Media3 |
| Helper compromise cannot reach operator's other systems | Helper container has least-authority + bounded egress + no shared trust |
| Operator's session is not a skeleton key | No shared web session/cookie jar exists across the operator's other subdomains |
| Secrets stay secret | Helper holds any upstream credentials, never on-screen, never in logs, never shipped in the app |
| Boundary is observable | Helper health and freshness are first-class; the TV surfaces them; alerting fires when protections quietly fail |
| Compromise is recoverable | App is reinstallable to known-good; helper redeployable; operator data restorable; no physical heroics required |

Each row above will be exercised by a test in the Stage 2 boundary suite.

## 3. Data flow (Stage 0 sketch)

```
[news sources]                 [live video sources]
       │                                │
       ▼                                ▼
   [helper] ──── clean structured ────▶ TV app ──── native player ──▶ pixel
   [helper] ──── resolved address ────▶ TV app ──── native player ──▶ pixel
       ▲                                │
       │  freshness/health probe        │
       └────────────────────────────────┘
                                        │
                                        ├──▶ on-device persistence
                                        └──▶ health-at-a-glance UI
```

## 4. Portability discipline

The TV app targets the Onn 4K **today**; the helper follows the operator's standard NAS/Docker patterns. The clean two-piece boundary means either side can be re-homed independently. Hard dependencies on a single box (vendor-specific SDKs, hardcoded device assumptions) are forbidden. This is a design discipline, not a feature.

## 5. Stage 1 — toolchain + skeletons pinned

### Versions (matched to `wbuscombe/wyzegrid` — the validated stack on this hardware)

| Component | Version |
|---|---|
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.0 |
| Compose BOM | 2024.11.00 |
| Compose-for-TV foundation | 1.0.0-alpha12 |
| Compose-for-TV material | 1.1.0-alpha01 |
| Media3 (ExoPlayer) | 1.5.0 (HLS only — see §6 boundary) |
| compileSdk / targetSdk | 35 |
| minSdk | 23 (Onn 4K runs API 34; 23 leaves headroom) |
| Java / Kotlin target | 17 |
| Helper Python | 3.13 |
| FastAPI | 0.118.0 |
| uvicorn[standard] | 0.37.0 |

`gradle.properties` carries `MYMTS_DEFAULT_MAX_TILES`, baked into `BuildConfig.DEFAULT_MAX_TILES` at compile time. The actual sustained-budget number that ships as the default lands once the soak gate clears (see `docs/findings/01-onn4k-tile-budget.md`); the field is config-driven from day one so re-homing to higher-capacity hardware later is a config change, not a code change.

### On-device modules (Stage 1)

```
app/
└── src/main/java/com/mymts/
    ├── MyMtsApp.kt              # Application class (crash handler / watchdog → Stage 6)
    ├── MainActivity.kt          # single Activity; switches modes via intent extras
    ├── player/
    │   ├── StreamSpec.kt        # value object; enforces http(s)-only scheme (no RTSP)
    │   ├── StreamPlayer.kt      # ExoPlayer wrapper; HLS source factory only
    │   └── StreamPlayerManager.kt  # owns N players + their Lifecycle bridge
    ├── ui/
    │   ├── theme/Theme.kt
    │   └── components/
    │       ├── StreamSurface.kt    # SurfaceView Compose host (cleanup discipline matters)
    │       └── PlaceholderScreen.kt # Stage 1 default — surfaces version/SHA/max-tiles
    ├── soak/
    │   ├── SoakSpec.kt          # run inputs
    │   ├── SoakFixtures.kt      # LIVE + STABLE fixture pools
    │   ├── SoakHarness.kt       # multi-tile playback with telemetry
    │   └── SoakLog.kt           # tagged logcat (parsed by scripts/parse-soak-log.py)
    └── util/CrashLog.kt         # file-backed log (mirrors WyzeGrid)
```

### Helper modules (Stage 1)

```
helper/
└── src/mymts_helper/
    ├── app.py        # FastAPI factory (no docs/openapi — internal-only)
    ├── health.py     # /health pinned to schema_version: 1
    ├── config.py     # env-driven; no file-based config in v1
    ├── log.py        # structured JSON to stdout + paranoid redaction pass
    └── __main__.py   # uvicorn entry point
```

### Boundary tests landed in Stage 1

- `app/StreamSpec` rejects `rtsp://` and `file://` schemes — guards against accidental copy of WyzeGrid's camera plumbing.
- `helper/log.redact` strips Bearer, Authorization-with-payload, X-API-Key, and every internal IPv4 class — extension of the v1.x web-app review's log-redaction requirement.
- `helper/health` schema_version is asserted in tests with a comment stating that bumping it requires a coordinated update in `claude-status-bot`. Stage 2 wires the actual consumer contract test.

## 6. WyzeGrid pattern reuse — and the hard boundary

### What was reused (skeleton only)
- Gradle layout: `libs.versions.toml` version catalog + single `:app` module + plugin aliases.
- Compose-for-TV entry pattern: `ComponentActivity` + `setContent { … }` + leanback launcher intent.
- ExoPlayer lifecycle discipline: per-tile `StreamPlayer` + manager that observes `Lifecycle`, with the strict surface-cleanup + listener-removal-before-release ordering.
- Soak-side observability shape: a `dumpsys meminfo` host-side poller writing CSV (parallels `scripts/memcheck.sh`).
- Deploy script shape: `assembleDebug` → `adb install -r` → `adb shell am start`.
- `versionName = getVersionFromGit()` pattern so release builds are self-describing.

### What was deliberately NOT reused (the hard boundary)
- **`RtspMediaSource` and all camera plumbing.** MyMTS plays public web video, not authenticated camera feeds. `StreamSpec` enforces this with a constructor `require` plus a unit test.
- **Wyze bridge / Frigate API code, `Camera` data model, `ConnectionState` specifics, `FakeFrigateApi`.** The source-trust model is fundamentally different — public web HLS is treated as unauthenticated and best-effort; cameras are authenticated and load-bearing. Copying the camera trust assumptions would silently weaken MyMTS's posture.
- **`WEATHER_API_KEY` build-time secret + the `whenReady` keyless-build guard.** The MyMTS app holds no secrets — Trust Bar A7 says secrets stay with the helper. The shape is good (failing the build on missing secret > silently shipping a broken feature) and will be reused in the helper when it gains a real upstream that needs credentials.
- **`WatchdogService` foreground service, `BootReceiver` autostart.** Stage 6 work, not Stage 1. Adding them before the budget number lands would be measuring the wrong thing.
- **`network_security_config.xml` for cleartext-to-local-bridge.** WyzeGrid needs it to talk to `<LAN_IP>`; MyMTS does not (the helper terminates inside a private docker network the TV never reaches directly).

The boundary is enforced by code (`StreamSpec` rejects non-http schemes), by test (`StreamSpecTest`), and by review discipline: if a future change finds itself reaching into `~/code/_reference/wyzegrid/app/.../api/Frigate*` or `.../weather/*`, that change is on the wrong side of the boundary.

## 7. Stage 2 — helper internals + the TV↔helper contract

The Stage 1 helper was a hardcoded `/health` skeleton. Stage 2 made the helper do its two real jobs (per `docs/foundation/04-TECHNICAL-APPROACH.md §2 "Piece 2"`).

### Modules

```
helper/src/mymts_helper/
├── app.py                  # FastAPI factory + lifespan (start pollers, seed channels)
├── config.py               # env-driven Config (no file config)
├── log.py                  # structured JSON + paranoid redaction (from Stage 1)
├── db.py                   # sqlite WAL + numbered SQL migrations
├── migrations/
│   └── 001_initial.sql     # sources, feed_items, channels, meta
├── fetcher.py              # SSRF-safe outbound HTTP (shared by feeds + channels)
├── health.py               # /health (schema_version: 1, additive feeds + channels)
├── phantom.py              # PHANTOM_MODE: fixtures + no-network resolver
├── feeds/
│   ├── parser.py           # feedparser + defusedxml + HTML-strip
│   ├── store.py            # sqlite CRUD + dedup + retention
│   ├── poller.py           # async task: 5-min poll + 14-day retention sweep
│   └── api.py              # /api/feed[/sources]
└── channels/
    ├── registry.py         # structured URL validation + seed loader
    ├── prober.py           # async task: 30-min reachability probe
    ├── api.py              # /api/channels
    └── seed.json           # operator-curated channel list (ships with image)
```

### TV ↔ helper contract (pinned)

All response envelopes carry `schema_version: 1`. Adding fields is backward-compatible; removing or renaming is a bump that requires a coordinated TV update.

| Endpoint | Returns |
|---|---|
| `GET /health` | `{schema_version, ok, ready, phantom, build_sha, version, uptime_seconds, feeds:{sources_count, items_count, stale_sources, last_poll_at}, channels:{channels_count, live_count, unavailable_count, last_probe_at}}` |
| `GET /api/feed?limit=200&since=<iso>` | `{schema_version, items:[{id, guid, source, source_url, title, summary, link, published_at, fetched_at}]}` — summary is **plain text** (HTML stripped) |
| `GET /api/feed/sources` | `{schema_version, sources:[{id, url, label, enabled, last_fetch_at, last_success_at, last_error, error_count}]}` |
| `GET /api/channels` | `{schema_version, channels:[{slug, label, kind, current_url, status, enabled, last_check_at, last_success_at, last_error, error_count}]}` — `current_url` is `null` when `status != "live"` (Trust Bar C3: never expose a stale URL labelled live) |

Contract tests pin every field name and the C3 invariant ("non-live channels expose `current_url: null`"). Located in `helper/tests/test_api.py` and `helper/tests/test_health.py`.

### Boundary mechanics (Stage 2 commitments)

- **SSRF-safe fetcher** (`fetcher.py`) is the only outbound primitive. Both the RSS poller and the channel prober use it. It enforces: https-only, DNS lookup up-front, rejection of RFC1918 / loopback / link-local / CGNAT / IPv6 ULA / IPv6 loopback before opening a socket, bounded body size, bounded total time, redirect re-validation.
- **Hostile-input quarantine**: feed bodies go through `feedparser` (which auto-loads `defusedxml` because we declare it as a runtime dep — neutralises XXE / entity-bomb / DOCTYPE attacks before our code sees the parse tree). Item titles + summaries are stripped to plain text via an `html.parser.HTMLParser` subclass that drops `script`/`style`/`iframe`/`object`/`embed` contents entirely. The TV is told the truth: this is plain text and only plain text.
- **Channel URL validation** is structured (not regex over the URL string): scheme + IDNA hostname + no userinfo + port {None, 443} + path that looks like an `.m3u8`. Rejects `rtsp://`, `file://`, ports we don't expect, etc.
- **Per-source error isolation**: one bad/500ing RSS source records its failure on its own row; the poller continues to the next. Same for channels and the prober.
- **Phantom mode** (`PHANTOM_MODE=1`) replaces the fetcher's resolver with one that raises on every hostname lookup, and preloads synthetic fixtures into the DB. A contract test (`helper/tests/test_phantom.py`) asserts that a complete app boot + `/api/feed` + `/api/channels` request makes zero outbound calls — CI runs it on every push/PR (`.github/workflows/ci.yml`), and you can run it locally with `uv run pytest`.

### Stage 2 NAS deploy

- Image pinned by digest (`python:3.13.1-slim-bookworm@sha256:031ebf3cde…`). Tag stays in `FROM` for human readability; digest is the source of truth.
- Container runs non-root (uid 10001), `read_only: true` rootfs, `cap_drop: [ALL]`, `no-new-privileges`, tmpfs `/tmp`, explicit `cpus`/`mem_limit`, no `docker.sock`.
- State lives in a **named volume** (`mymts-helper-data`) mounted at `/data` — fresh volumes inherit ownership from the in-image `/data` (uid 10001), so no host-side privileged step is needed. **Both** composes now provide it: the operator's `deploy/docker-compose.nas.yml` and (since 2026-06-18) the generic `docker-compose.yml`, so a clean clone's `docker compose up` brings up a working helper instead of crash-looping (the read-only rootfs had nowhere to create the SQLite DB without it). `config._default_data_dir()` prefers a writable `/data` and falls back to a per-user dir for a bare local run, so first-run init (migrate-to-head + seed) needs zero config either way.
- Helper listens on host port `8091` (NAS LAN). The TV reaches it at `http://<nas-lan-ip>:8091/api/...`.
- Deploy script `scripts/deploy-helper.sh` does the full `pull → rebuild → restart → /health verify` cycle and refuses to declare success until `/health.build_sha` matches the deployed SHA.
- The unrelated host container is **never** referenced or networked into. The helper's compose declares its own dedicated bridge network (`mymts-net`) with no upstream link.

## 8. Stage 2 Part B — `StreamPlayer` state machine + recovery

Per Trust Bar **C3** (*staleness is never silent*) and **C2** (*a dead feed is a non-event*). The Stage 1 player reported `state=LIVE` while the surface received zero frames for 10+ hours on four of six tiles in v3 — Stage 2 Part B replaces the ad-hoc state with a frame-age-aware state machine.

### The state machine (`com.mymts.player.LivenessTracker`)

```
                       ┌─────────────┐
                  ┌───▶│ CONNECTING  │── 30s without frame ──┐
                  │    └─────────────┘                       │
                  │           │                              │
                  │      onFrameRendered                     │
                  │           │                              ▼
                  │           ▼                       ┌──────────┐
              onFrame─────▶ ┌─────┐ ── 15s ──▶───────▶│  STALE   │
                            │LIVE │                   └──────────┘
                            └─────┘                         │
                              ▲                       backoff elapsed
                              │                             │
                              │                             ▼
                              │                       ┌────────────┐
                              └──── onFrameRendered ──┤ RECOVERING │
                                                      └────────────┘
                                                            │
                                            no frame in 15s window
                                                            │
                                                ┌───────────┴───────┐
                                            attempts < 3     attempts ≥ 3
                                                │                   │
                                                ▼                   ▼
                                              STALE              ┌──────┐
                                                                 │ DEAD │
                                                                 └──────┘
                                                            (release decoder,
                                                             anti-loop holds)
```

States in detail are documented in `docs/findings/02-player-state-machine.md`. Key invariants:

- A tile reports `state=LIVE` **only** when `last_frame_age_ms < staleThresholdMs`. ExoPlayer's `STATE_READY` alone is not sufficient — that was Stage 1's bug.
- A tile that cannot recover settles into `DEAD` and stops consuming CPU. The recovery ladder runs at most three times before this happens (`maxRecoveryAttempts = 3`).
- A tile that has never produced a frame (DNS dead, 4xx, manifest invalid) follows the same ladder via the `connectingThresholdMs = 30 s` timeout — it does not hang in CONNECTING forever.

### Chosen values

| Knob | Value | One-line reason |
|---|---|---|
| `staleThresholdMs` | 15 s | Tighter than 30 s honors C3 aggressively; covers normal buffer drain + brief hiccup. |
| `connectingThresholdMs` | 30 s | Longer because initial manifest fetch + buffer can take 10–15 s on the Onn box. |
| `maxRecoveryAttempts` | 3 | PREPARE, then two REINITs. More burns decoder slots without adding signal. |
| `backoffMs` | [2 000, 8 000, 30 000] | Exponential with a soft cap. Anti-loop: a dead stream settles into DEAD within ~115 s. |
| `tickIntervalMs` | 2 000 | Fast enough to catch threshold violations inside the threshold window. |

Rationale + on-device evidence are in the finding doc; the state machine is asserted by 17 unit tests in `app/src/test/java/com/mymts/LivenessTrackerTest.kt`.

### Telemetry the harness sees

`SoakLog` emits these on every transition:

- `EV=STATE|id=…|from=<state>|to=<state>|ts_ms=…`
- `EV=RECOVERY|id=…|attempt=<n>|kind=prepare|reinit|ts_ms=…`
- `EV=DEAD|id=…|attempts=<n>|ts_ms=…`

Beats (`EV=BEAT`) now include `state` over the wider {LIVE, STALE, RECOVERING, DEAD} set. `scripts/parse-soak-log.py` counts state transitions, recovery strikes, and DEAD settlements per tile — what Part C will use to read the capacity probe.

### Where the C3 contract is now enforced

Both ends of the TV ↔ helper chain honor "staleness is never silent":

| Layer | Mechanism |
|---|---|
| Helper API boundary (Part A) | `/api/channels` masks `current_url → null` whenever `status != "live"`. |
| TV player (Part B) | `StreamPlayer.state` derives from actual frame arrival; `STALE`/`DEAD` are surfaced honestly to the UI; the wall **cannot** show a `LIVE` badge over a frozen surface. |

## 9. Stage 3 — the wall UI (what the operator actually sees)

The TV-app side gains a single screen (`com.mymts.ui.wall.WallScreen`) composed of three regions consuming three independent data sources:

```
app/src/main/java/com/mymts/
├── data/
│   ├── helper/
│   │   ├── Channel.kt             ← /api/channels row + ChannelsSnapshot envelope
│   │   ├── ChannelsRepository.kt  ← 30 s poll, freshness signal, never silent empty
│   │   ├── FeedRepository.kt      ← 60 s poll, 10 min staleness window
│   │   └── HelperClient.kt        ← HttpURLConnection + org.json, schema_version=1 pinned
│   └── ticker/
│       ├── TickerSource.kt        ← interface (real markets impl. drops in here)
│       └── SampleTickerSource.kt  ← every entry isSample=true (clearly-labeled placeholder)
└── ui/wall/
    ├── WallScreen.kt              ← assembled layout: ticker top / feed left / grid right
    ├── TickerStrip.kt             ← paged flip; per-page PINNED marker (BottomLine curtain) over a clipped marquee
    ├── TickerPaging.kt            ← pure paging: markets/league/news pages, each with a markerLabel
    ├── FeedPane.kt                ← 10-foot UI list; native-text only (no WebView path)
    ├── RelativeTime.kt            ← "now/Nm/Nh/Nd/date" relative-time chip
    ├── VideoGrid.kt               ← grid-agnostic cell grid (cols×rows for 1/2/4/6/9) + section safe-bottom
    ├── WallTile.kt                ← [video + label] cell unit: weight(1f) video over a reserved label strip
    ├── LineupSelector.kt          ← preferred → fallback → rest, capped at N
    ├── TileSlotResolver.kt        ← cycles K live channels across N slots
    └── WallColors.kt              ← dark-newsroom palette
```

### Region behaviors (Stage 3 polish-pass shape)

| Region | Source | Refresh | Failure shape |
|---|---|---|---|
| Ticker (top, 40 dp) | `SampleTickerSource` (static; real source drops in via `TickerSource`) | n/a — static | Empty source → empty strip (no error chrome) |
| Feed pane (left, 28% width) | `/api/feed?limit=80` via `FeedRepository` | 60 s poll | Header carries `"feed not updating"` / `"helper unreachable"`; old items don't pretend to be current |
| Video grid (right, autofit fill) | `/api/channels` via `ChannelsRepository` → `LineupSelector` → `TileSlotResolver` | 30 s poll | Per-tile honest state from `StreamPlayer.state`; dead tile = quiet near-black panel (C2) |

### Ticker: paged flip + pinned marker (BottomLine curtain)

The ticker (`TickerStrip`) flips between **pages** — markets, each sports league, news — built by the pure `TickerPaging.pagesFor`. Each `Page` carries a `markerLabel` (`MARKETS` / the league / `NEWS`).

Within a page, the cards (market quotes / game cards) sit in a `basicMarquee` row that scrolls horizontally when they overflow the panel. The page's marker is **pinned to the left edge** and **drawn on top** of that scroll inside a `clipToBounds` box, so:

- the marker is a full-height **opaque** curtain that **persists** through the scroll (it does not move with the cards);
- a card scrolling left is occluded by the marker and **vanishes cleanly at the marker's right edge** — the ESPN BottomLine "curtain" — rather than visibly sliding under a translucent block;
- a leading `Spacer(MARKER_WIDTH)` inside the marquee holds a *static* (non-overflowing) page's first card to the right of the marker; on overflow that reserve scrolls away with the content.

The marker overlays the page Box's true left edge and the STALE flag overlays the **right** edge, so a stale-data pill can never push the curtain inboard. Marker label selection is unit-tested (`TickerPagingTest`); the clip/pin is a layout property verified on-device.

### Measured area → cells → [video + label] units (grid-agnostic)

The video section lays out structurally so the channel label is always a clean strip **below** the picture, inside the panel's safe area, at any grid size — not a per-tile heuristic. A small `LABEL_BOTTOM_BUFFER` (4 dp, strip height 22 dp) keeps the title off the cell's bottom border; the buffer comes out of the video `weight(1f)`, so it stays inside the safe band.

1. **Section safe area.** `VideoGrid` fills its parent region but reserves a bottom band (`SECTION_SAFE_BOTTOM = 28dp`) so the lowest row's label strip stays inside the panel's *visible* area. That band is the residual overscan clip left by the locked panel-fit (Fit 80% / Stretch 110% / Overscan None / Position 0,0) — the section **reads** that fit to size the safe band; it never changes it.
2. **Cells.** The safe area is divided into `gridColumnsFor(count) × gridRowsFor(count, columns)` equal cells — 1×1 / 2×1 / 2×2 / 3×2 / 3×3 for the 1 / 2 / 4 / 6 / 9 configurable counts. Each cell takes `weight(1f)` in both axes, so a cell is `safeWidth/columns × safeHeight/rows`.
3. **[video + label] unit.** Each cell is a `Column`: a `weight(1f)` video area above a fixed-height `LABEL_STRIP` (18dp). The video area letterboxes itself — `StreamSurface` hosts a Media3 `PlayerView` with `RESIZE_MODE_FIT`, which preserves source `videoAspect` and adds black bars where the cell and source dimensions differ (no manual aspect math in layout). The label renders in the reserved strip beneath, on the tile-gap background.

Net effect: every cell — including the bottom row — shows its video letterboxed with a uniform label strip below it, never clipped, at any grid count. The bottom row stopped being a special case the moment label space became structural rather than overlaid.

### Configurable grid — independent Rows × Columns (2026-06-11)

The grid is configured as **independent rows × columns**, each **1–3** (so 2×2, 2×3, 1×3, 3×2 … up to 3×3 = 9) — `WallSettings.gridRows` / `gridCols` (default 2×2, clamped by `clampGridDim`, persisted by `LineupStore` as two ints; the old single-count `gridSize` enum is retired). `WallScreen` feeds `gridCells = rows × cols` to the slot resolver and passes the explicit `gridCols` to both `VideoGrid` (which lays out exactly `cols × rows`, not a count-derived shape) and the focus model (so D-pad row/column nav matches the visible R×C). Channel choices survive an R×C change: explicit per-slot `overrides` are keyed by slot index, and the default fill is a stable prefix (see *Lineup selection*). *(Backlog: restrict the offered dims to those sensible for the panel's real resolution.)*

### Focus restoration on overlay dismiss (the Onn-box focus-escape fix)

The wall's focus is driven by a **single root focusable Box** + the pure `WallFocusModel` (the `focus` state is the cursor; the model handles all D-pad via `onPreviewKeyEvent`). When a menu/overlay opens, its focusable rows take Compose focus; on dismiss, focus must return **deterministically** to the wall — Compose-for-TV's implicit return is unreliable on this hardware (focus lands nowhere, or escapes to a focusable video `PlayerView` = the "main panel hijack"). Three guards make it deterministic:
1. **Video surfaces are non-focusable** (`StreamSurface`: `isFocusable=false` + `FOCUS_BLOCK_DESCENDANTS`) — focus can never escape to a tile.
2. The **side menu re-homes** its first row whenever a sub-overlay dismisses (a focus request keyed on the sub-overlay's presence, not a one-shot).
3. The **wall root re-homes** on full menu close via a frame-yielded `requestFocus` (`LaunchedEffect` + `withFrameNanos` + `runCatching`, replacing a racy `DisposableEffect`) — yield first so the dismissed overlay releases focus, then claim it.

Verified on-box across repeated open/close cycles (the focused node returns to the full-screen root). The same per-row explicit `BringIntoViewRequester` keeps a focused menu/picker row scrolled into the visible area on this overscan-clipped panel.

### Lineup selection

`LineupSelector.forWall(maxCount)` walks three lists in priority order:
1. Operator's preferred slugs (`cbs-sports-hq`, `bbc-news`, `cnn`, `livenow-fox`) — picked first if playable.
2. Fallback slugs (`c-span`, `nasa-tv`, `white-house-tv`, `newsmax`, `cnn-international`) — fill if preferred didn't resolve.
3. Any remaining playable channels — top up so the grid isn't half-empty when other channels work.

Result is capped at `tileCount`. If fewer than `tileCount` channels resolve across all three lists, the remaining slots are `Slot.Empty` and the tile renders the same quiet OFFLINE panel as a `DEAD` slot. **The wall never fakes a tile** (C2 + the prompt's "do not fake completeness" rule).

### Helper-host boundary

The wall's only inbound is the helper, whose base URL is resolved at **runtime** (2026-06-18, `HelperUrl.resolve`) through a precedence chain, first valid wins: a user-set, persisted value (the first-run setup screen / the Settings "Helper URL" field, reachability-tested against `/health`) **>** an adb `helper` intent-extra **>** the compile-time `BuildConfig.HELPER_BASE_URL` — the last used only when actually configured at build time (`BuildConfig.HELPER_URL_CONFIGURED`; a stock APK's `localhost` demo fallback is not a resolution). This closed the compile-time-only blocker: a **stock APK can be pointed at any helper with no rebuild**, while the operator's configured build (`MYMTS_HELPER_BASE_URL` in `gradle.properties` / `local.properties`) resolves out-of-box and skips setup. `network_security_config.xml` allows cleartext **only** for `<LAN_IP>`; every other host on the wall is HTTPS-only by Android policy. TLS to the helper is deferred to Stage 6 hardening (with operator-issued internal-CA pinning); documented in `THREAT-MODEL.md T-T4`.

## 10. Stage 5 — in-app menu + channel/lineup control

The wall gains a WyzeGrid-style left side panel for **channel/lineup control only**. Settings, layout config, diagnostics, and other future menu rows are explicitly deferred.

```
app/src/main/java/com/mymts/
├── data/lineup/
│   └── LineupStore.kt           ← SharedPreferences-backed slot→slug persistence
└── ui/menu/
    ├── MenuState.kt             ← isOpen + pendingSelection (PendingSelection.SlotPicker)
    ├── MenuOverlay.kt           ← left side panel, focusable rows, version footer
    ├── ChannelPickerOverlay.kt  ← centered TV-style popup (cycle + assign + cancel)
    └── MenuColors.kt            ← WyzeGrid-family palette (green focus, dark translucent)
```

### Slot list — single source of truth

`WallScreen` computes the slot list **once** per recomposition from three inputs:

```
ChannelsRepository  ─► allChannels  ─┐
                                     ├─► TileSlotResolver.resolve(N, default, all, overrides)
LineupStore         ─► overrides  ───┤   = List<Slot.Playing | Slot.Offline | Slot.Empty>
LineupSelector      ─► defaultOrder ─┘                              │
                                                                    ▼
                                                ┌───────────────────┴──────────────────┐
                                                ▼                                       ▼
                                          VideoGrid(slots)                       slots.map { it.toRow() }
                                                                                       ▼
                                                                                 MenuOverlay(slotRows)
```

The menu and the wall **cannot disagree** about a slot's channel because they read the same list. When the picker writes a new override via `LineupStore.assign(slotIndex, slug)`, the store's `mutableStateOf` flips, the wall recomposes, `slots` recomputes, and both surfaces update atomically.

### Slot variants (refactor of Stage 3's TileSlotResolver.Slot)

| Variant | When | Renders as |
|---|---|---|
| `Slot.Playing(idx, channel, spec)` | helper says channel is live + has `current_url`; paired with a real player | live video |
| `Slot.Offline(idx, channel)` | operator-pinned channel exists in helper but `status != live` (mark-and-allow) | C2 honest panel **with the channel's label** |
| `Slot.Empty(idx)` | no operator pin and no default available | C2 blank panel, no label |

`Slot.Offline` has no `spec` field by construction — the type system rules out a mispaired player, so the structural `BoundTile.init { require(player.specId == slot.spec.id) }` from `9d5b0ad` cannot misfire on an offline-pinned slot.

### Persistence format

`SharedPreferences("mymts_lineup")` → key `lineup_overrides` → JSON array `[slot, slug, slot, slug, …]`. Codec is deterministic (sorted by key), tolerant of corrupt blobs (returns empty + clears), and unit-tested in `LineupStoreCodecTest`. **No secrets, no PII, no absolute paths** — values are short slug strings.

### D-pad model

| Gesture | Menu closed | Menu open | Picker open |
|---|---|---|---|
| MENU | open menu | close menu | (picker handles) |
| LEFT | open menu | (panel focus) | cycle channel back |
| RIGHT | n/a | (panel focus) | cycle channel forward |
| UP/DOWN | n/a | move focused row | n/a |
| CENTER/OK | n/a | open picker for focused slot | **assign** + dismiss |
| BACK | n/a | close menu | **cancel** — slot unchanged |

BACK is caught both via `BackHandler` and via the root `onPreviewKeyEvent`. On TV, `Modifier.focusable` consumes BACK to exit a focus group before `BackHandler`'s dispatcher sees it; the dual path closes that gap.

### Honesty rules at the menu layer

- **C3 — staleness never silent at the picker.** Each channel in the cycle is decorated with its real current status (`live` / `offline`); the operator cannot mistake an offline channel for one that will play.
- **C2 — graceful degradation made visible.** An offline-pinned slot renders the C2 panel **with the assigned channel's label** so the operator sees what's planned for the slot vs. an unassigned tile.
- **9d5b0ad — label/stream binding holds through reassignment.** A reassignment swaps the `Slot.Playing.spec.id`, which forces a fresh player via `bindTiles`'s identity-match. `BoundTile.init { require }` enforces this at construction; a tile labelled "X" cannot end up playing channel "Y" even mid-rebind.

## 11. Stage 6 — signed-install update path + rollback

Per `03-OPERATIONAL-BAR.md` B1/B2/B5 — never bricks, always a way back, no silent bad-bundle cascade.

```
scripts/
├── deploy-app.sh         ← orchestrator (bash)
└── health_check.py       ← post-install decision logic (pure-Python, unit-tested)
                            scripts/test_health_check.py — 15 cases
```

```
app/
├── build.gradle.kts                  ← release signing config (keystore from .properties or env)
├── keystore.properties.example       ← template (real file gitignored)
└── (release key file)                ← NEVER in repo; .gitignore excludes *.jks
```

### The four states an APK can be in

1. **Built** — produced by `:app:assembleRelease`. Signed with the operator's release key if `keystore.properties` exists; otherwise debug-signed. The deploy script refuses to install a debug-signed APK as a release.
2. **Archived** — copied into `$MYMTS_ARCHIVE_DIR/archive/mymts-<v>+<sha>-<utc>.apk`. Older versions are kept; manual rollback to any of them is one command.
3. **Installed** — `adb install -r` on the target. Not yet known-good.
4. **Known-good** — pointer at `$MYMTS_ARCHIVE_DIR/known-good` (single filename, atomic write) names the APK currently in production. Only updated after the health gate passes.

### Promotion is gated, not automatic

After install + launch, the deploy script captures `MYMTS_SOAK` telemetry for a bounded window (default 90 s) and runs `health_check.decide()` over it. The decision is purely a function of the telemetry — same logic the Stage 3 fix-forward used to verify itself, now wired into the update path.

| Decision | Trigger | Action |
|---|---|---|
| `PASS` | ≥ `minimum_ready` `EV=TILE_READY` and zero `EV=DEAD` in window | atomic write of the new APK name to `known-good` |
| `FAIL_ALL_DEAD` | all 4 tiles settled DEAD (the b19b013 shape) | reinstall the prior known-good; record |
| `FAIL_DECODER_THRASH` | `EV=DECODER` ≥ expected_tiles but `EV=TILE_READY` == 0 | rollback (the exact regression shape Stage 3 caught) |
| `FAIL_NOT_READY` | not enough tiles came up | rollback |
| `FAIL_TIMEOUT` | window elapsed with insufficient evidence | rollback |

The "rollback" path is the same code path as `--manual-rollback`: read the `known-good` pointer (which never names the failed APK — promotion happens only on success), `adb install -r -d` the file, restart. Because older APKs are retained, manual recovery to *any* prior version is one command if the auto-restored known-good itself is bad.

### Honesty discipline preserved at the update layer

- **No silent promotion.** A new build that doesn't pass telemetry is *never* the known-good — the operator's wall keeps running whatever last worked.
- **No debug-signed release.** The deploy script reads the signing certificate via `apksigner verify --print-certs` and refuses if the subject is `CN=Android Debug,O=Android,C=US`. Configured environments only.
- **Loss of keystore = loss of update ability** (Android refuses upgrades signed with a different key). The keystore is backed up off-device per the operator's secret-handling practice.
- **Audit log of every deploy.** `$MYMTS_ARCHIVE_DIR/deploy.log` records every build, install, health-gate result, and promotion or rollback decision.

## 12. Stage 6 — TLS to the helper (baseline)

The TV ↔ helper link is now encrypted with **certificate pinning at the app layer**. The trust posture is intentionally narrow for a single-operator LAN service:

```
                                           helper.crt (public)
                                                  │
                                                  │  embedded as trust anchor
                                                  ▼
        ┌────────────────┐                ┌──────────────────┐
        │ TV app          │  HTTPS 8443 / │  helper          │
        │ (network-sec-   │ ◀──pinned───▶ │  (uvicorn TLS)   │
        │  config)        │   trust       │                  │
        └────────────────┘                └──────────────────┘
                                                  │
                                                  │  reads at startup
                                                  ▼
                                  /etc/ssl/mymts/helper.{key,crt}
                                  (NAS-only volume mount, UID 10001)
```

### What the app trusts

The TV app's `network_security_config.xml` carries:

- A `<base-config>` with `cleartextTrafficPermitted="false"` and system CAs only — the default for anywhere that isn't the helper.
- A `<domain-config>` for `<LAN_IP>` whose `<trust-anchors>` contain **only** `@raw/helper_cert` (the helper's self-signed cert). The system CA bundle is explicitly NOT a trust anchor for this host — a global-CA-signed MITM cert is refused.
- The cleartext exception for `<LAN_IP>` is retained transitionally as a recovery seatbelt; removed in the at-the-box finale Step 1.

### What the helper does

`helper/src/mymts_helper/__main__.py` builds the FastAPI app **once** and runs two `uvicorn.Server` instances concurrently sharing that one app instance:

| Listener | Port | Lifespan | Pollers |
|---|---|---|---|
| HTTP (transitional) | 8091 | `on` — starts the RSS poller + channel prober via the app's lifespan handler | yes |
| HTTPS (target) | 8443 | `off` — would double-start pollers if `on` | no, but serves all the same endpoints from the same app instance |

When the transitional HTTP is dropped (at-the-box finale Step 1), the HTTPS listener flips its lifespan to `on` and becomes the only listener. The cert + key are mounted read-only from `/srv/docker/mymts-helper/_secrets/` (gitignored, UID 10001) into the container at `/etc/ssl/mymts/`.

### Cert rotation contract

Because the trust anchor is the **specific cert** (not an issuer), rotating the cert is a **coordinated APK + helper pair**: generate the new cert on the NAS, replace `app/src/main/res/raw/helper_cert.pem`, rebuild + ship the APK via the Stage 6 signed-update path. Until the new APK is installed, only the old cert is trusted. This is acceptable for a single-operator-on-private-LAN posture — the rotation cadence is "rarely" (10-year cert validity).

## 13. Stage 6.x — whole-wall D-pad focus model + per-zone actions

The wall gains a **unified, global focus model** — a pure function that consumes every D-pad event and computes where focus should move next. This isolates focus bugs to a single, unit-tested layer, making navigation regressions visible at test time rather than from the couch. Per-zone actions (what SELECT does in each zone) layer on top of the pure model, kept deliberately separate so a focus tweak never entangles the action semantics and vice versa.

```
app/src/main/java/com/mymts/ui/nav/
├── WallFocus.kt                ← data class: active zone + preserved indices + in-place state
├── WallFocusModel.kt           ← pure function: (focus, intent, counts, gridColumns) → NavResult
└── NavIntent + NavResult       ← enum for input; sealed class for output (sum type)

app/src/test/java/com/mymts/nav/
├── WallFocusModelTest.kt       ← 38 unit tests covering every transition + no-trap invariants

app/src/main/java/com/mymts/ui/wall/
├── WallScreen.kt              ← owns focus state; dispatches D-pad through the model
├── VideoGrid.kt               ← gridColumnsFor() = single source of truth for column math
├── FeedPane.kt                ← accepts focusedIndex + expandedIndex (in-place state)
└── TickerStrip.kt             ← accepts focused + paused (in-place state)
```

### The three zones and their screen layout

The wall is spatially organized into three navigable zones — **Ticker, Feed, Grid** — arranged to match how the operator scans from the couch.

```
    +-------------------------------+
    |         TICKER (top)          |   thin strip, 40 dp; marquee or static
    +-----+-------------------------+
    |     |                         |
    | FEED|       GRID (right)      |   ~28% feed width, rest fills grid
    |     |                         |   2×2 default per device budget
    +-----+-------------------------+
```

**Ticker** (top strip, 40 dp) — a horizontal marquee or static row. One focus position; no intra-zone navigation. Accessible via UP from feed/grid; returns DOWN to whichever zone the operator came up from (`lastLowerZone` memo).

**Feed** (left column, 28% width) — vertical list of news items. Each item renders as a collapsed row (title + summary clipped). UP/DOWN move within the list or to/from the ticker. RIGHT enters the grid at the grid's preserved index. LEFT opens the menu.

**Grid** (right, remainder) — rows × columns of video tiles. LEFT/RIGHT move within a row (never wrap); UP/DOWN move between rows at the same column. TOP row UP goes to ticker. LEFTMOST column LEFT spills back to the feed. Grid's column count (`gridColumns`) is computed once via `gridColumnsFor(slotCount)` — the single source of truth so the focus model's row/col math and the visible layout never disagree.

### Pure function + immutable result

`WallFocusModel.apply(focus, intent, feedItemCount, gridTileCount, gridColumns)` is **entirely pure** — no Compose, no Android, no side effects — returning a `NavResult` sum type:

```kotlin
sealed class NavResult {
    data object Stay                          // no state change
    data class Focus(val focus: WallFocus)    // new focus state
    data object OpenMenu                      // request menu overlay
    data class OpenSlotControls(slotIndex)    // request per-tile controls overlay
    data object BackBubble                    // no deeper state; caller handles BACK
}
```

The **reason for this separation**: focus model changes (adding a zone, tweaking boundary conditions) do not touch action logic, and vice versa. The action layer (what SELECT does in each zone) is driven by `NavResult.Focus` variants; if a future change swaps SELECT behavior for a zone, the model's transition graph stays intact.

### Focus state ownership: WallScreen as the single holder

`WallScreen` owns the `WallFocus` state via a `mutableStateOf`. Every D-pad event flows through the model and updates the state:

```kotlin
var focus by remember { mutableStateOf(WallFocus.Initial) }

// In the key-event handler:
val result = WallFocusModel.apply(
    focus = focus,
    intent = navIntent,
    feedItemCount = feedItemCount,
    gridTileCount = slots.size,
    gridColumns = gridColumnsFor(slots.size),
)
when (result) {
    is NavResult.Focus -> focus = result.focus
    is NavResult.OpenMenu -> menu.open()
    is NavResult.OpenSlotControls(slotIndex) -> menu.openControls(slotIndex)
    // ...
}
```

The model is passed the **current** counts and column math because those are derived data (feed size, grid tile count, column layout depend on the data sources). The model does not hold them. This lets the model be tested in isolation — the test suite is free to pass any counts and verify the transitions work correctly regardless of what the wall is currently rendering.

### gridColumnsFor: single source of truth for column layout

Both the focus model and the visible grid must agree on how many columns exist. `gridColumnsFor(slotCount)` is a **top-level function**, not a method, so it can be called before the composable runs:

```kotlin
fun gridColumnsFor(slotCount: Int): Int = when (slotCount) {
    0, 1 -> 1
    in 2..4 -> 2
    else -> ceil(sqrt(slotCount.toDouble())).toInt().coerceAtLeast(1)
}
```

Every row/column calculation in the focus model uses this exact function. The visible grid also uses it to lay out tiles via `weight()`. This single source of truth is **enforced by code, not by convention** — if the grid's column count drifts from the model's math, D-pad navigation will trap.

### Per-zone actions: the action layer

Each zone has a **SELECT action** and **BACK semantics**, kept separate from the pure transition function:

| Zone | SELECT | BACK |
|---|---|---|
| **Ticker** | Toggle `tickerPaused` in place. No zone change, no scroll, no side effect beyond the pause flag. Useful at 10 ft when a ticker value is sliding off-screen — operator presses OK to stop, reads, presses OK again to resume. | Bubbles to caller (e.g., to close a modal if one is open). |
| **Feed** | Toggle `feedExpanded` in place. When true, the focused item shows its full plain-text summary (already fetched and stored by the helper; no web fetch, no HTML parse, no WebView). Expanding is a **structural no-op** — only the `expanded` render property changes; focus stays, item stays, feed doesn't scroll. | If expanded, collapse; then bubbles. So BACK collapses first, then (if already collapsed) bubbles. Mirrors the Stage 5 menu's BACK nesting. |
| **Grid** | Open the `SlotControlsOverlay` for the focused tile. Allows the operator to reassign the channel or adjust audio/captions. Overlay is a modal; wall focus is preserved but inactive. | Bubbles (no nested state in the grid itself). |

All three zones obey the **no-trap invariant**: every zone is reachable from every other, and every zone has at least one direction that exits it (either into another zone or by opening the menu). The invariant is verified by two tests: `every zone is reachable from every other zone` (BFS through the graph) and `every zone is exitable` (each zone can reach ≥1 of {new zone, menu, controls modal}).

### Feed expansion: the closed-door load-bearing boundary

The feed-expand action is where **Trust Bar A1** (hostile input assumed) meets **Trust Bar C3** (staleness never silent). When the operator SELECTs a feed item and `feedExpanded` flips to true:

- `FeedPane` renders the item's `summary` field — **plain text**, pre-stripped of HTML by the helper's `feeds/parser.py`.
- No WebView, no HTML parser, no fetch, no async network call. The summary is already in the app's memory, safe and ready.
- The A1 boundary is: the helper did the parsing and stripping; the TV renders only what the helper gave. An operator can read an excerpt safely, offline, without triggering a web fetch.

This is the "safe excerpt is the v1 answer; richer reading is deferred" principle from `docs/BUILD-PROMPT.md §4` (lines 81 / 130 / 178). It satisfies A1 while keeping the feed interactive. No compromise. Adversarially verified in `docs/THREAT-MODEL.md §"Navigation chapter — feed-expand A1 confirmation"`.

### What's deliberately NOT in this chapter

- **Kiosk story** — long-uptime foreground watchdog + boot receiver. Deferred to the new MyMTS box (in transit) per Model A. Focus model is unaffected by kiosk mechanics.
- **Feed list/sections restructure** — the feed could later gain sub-lists (by source, by topic), changing how UP/DOWN behave and what `feedIndex` means. Decision deferred (BACKLOG item B).
- **Ticker markets/sports modes** — real ticker source plug-in. Future extension; the current stub is a single-row marquee that participates in the focus model cleanly (BACKLOG item D).
- **QR-to-phone for richer reading** — a future closed-door-compatible alternative for reading the full article on the operator's phone. The TV never fetches HTML; the phone is the operator's own device. Logged in BACKLOG.

---

## 14. Stage 7 — feed restructure (sectioned list + per-source freshness)

The wall's feed pane moves from a flat chronological river to a **sectioned list grouped by source**, with per-source freshness signals. This addresses the operator's feedback on the continuous-scroll feed — "list, not individual-scroll, very unintuitive and inefficient" — while preserving the navigation chapter's focus invariants without model changes.

### Section structure and ordering

Each **source** (BBC, Guardian, Al Jazeera, NPR, etc.) becomes a labelled section with:
- A header carrying the uppercase source name, item count within the section, and a per-section freshness chip (see below).
- Items within the section sorted newest-first by published time, falling back to fetched time for items with missing published timestamps.

**Section order is alphabetical case-insensitive on source name** — predictable so the layout does not reshuffle on every poll. The operator's muscle memory survives: a source always appears in the same screen position relative to others, not scattered by recency. Within-section recency is still visible (newest item at the top of each source's subsection); the global newest item across all sources is no longer necessarily at the top, but the tradeoff — "can I skim what X is reporting?" — wins for a 10-foot viewing distance.

### The flat-item invariant (no focus-model change)

The wall's focus model (`WallFocusModel`) **continues to treat the feed as a single flat list indexed 0..N-1**. Headers are visual only — never focusable. The flat-item order (after dropping headers) is the navigation order: `feedIndex` traverses items in the same sequence they appear in the visible list (alphabetical-source, newest-first-within-source).

`FeedListBuilder.entriesIndexForFocus()` maps a focus index to the correct row in the entries list, skipping over non-focusable headers. This preserves the no-trap invariants the navigation chapter pinned without touching the focus model's transition graph or tests.

### Per-section freshness (C3 at the section layer)

Each source gets its own `SectionFreshness` signal derived from the **newest item's age**. The type is a simple sum:

```
Fresh        (< 2h)       ← operator-visible "now/Nm/Nh" age chip
Warm         (< 12h)      ← operator-visible age chip with warm-colored badge
NotUpdating  (≥ 12h)      ← honest "not updating" text instead of age
Unknown      (no items)   ← "no items" label; should not occur for a real source
```

Thresholds default to **2h / 12h** and are configurable (tests pin classification at each boundary). A source that goes quiet — because the helper's poller failed, or the source itself stopped publishing — is honest at the section layer: the operator sees "not updating" and does not mistake stale items for current news.

### The channel-picker companion: live/offline orientation

The menu's channel picker gains a small parallel feature: a "LIVE n/m" / "OFFLINE n/m" orientation chip in the slot header. The channel list is sorted live-first by the call site, so live channels form a contiguous prefix and offline ones a contiguous suffix. As the operator cycles through channels, the chip reflects which group the cursor is in — same "sections for live and offline" feedback applied at the cycler's orientation layer rather than the feed itself. This is a low-cost win: visible orientation without changing the picker's core behavior.

### What's deliberately NOT here

- **Feed filtering / search UI** — a future chapter will layer search + source filtering on top of the sectioned list. Deferred to a separate workflow.
- **Section collapse / jump-to-source** — the current navigation (UP/DOWN within a section) suffices for pilot-phase workflows. Future enhancement if the operator wants quick access to a specific source header.
- **Configurable feed width / font** — grouped with general "UX & config push" (Stage 7's scope is grouping logic; typography polish follows later).

---

## 15. Stage 8 — UX & Config (configurable wall layout)

The wall gains a **settings model** enabling the operator to tune the feed pane's width, text scale, and side positioning — all discrete presets rather than continuous sliders, so each preset is tuned in code with intent and D-pad cycling handles selection cleanly.

### The settings model

`WallSettings` is a data class holding three enum fields:

```
WallSettings(
  feedWidth: FeedWidth,       // Narrow (0.22), Default (0.28), Wide (0.36)
  feedFontScale: FeedFontScale, // Small (0.88×), Default (1.0×), Large (1.18×)
  feedSide: FeedSide            // Left, Right
)
```

Each enum is discrete. `FeedWidth` multipliers {0.22, 0.28, 0.36} are applied at the row-layout level; `FeedFontScale` multipliers {0.88, 1.0, 1.18} scale all font sizes within `FeedPane` so the 10-foot legibility floor (asserted in `WallSettingsTest`) and overflow ceilings (tested at each preset boundary) hold. **Why discrete and not sliders?** Each preset has been tuned in code with specific intent — Small preserves legibility at distance, Large trades space for readability — and the D-pad's binary cycling (LEFT/RIGHT) maps cleanly to stepping through presets. Sliders require pointer-style interaction; discrete steps survive the operator's D-pad vocabulary without UI overhead.

### Persistence in LineupStore

`WallSettings` is persisted by **extending** `LineupStore` (the existing `SharedPreferences` container from Stage 5) rather than creating a parallel store. New integer-ordinal keys (`KEY_FEED_WIDTH`, `KEY_FEED_FONT`, `KEY_FEED_SIDE`) hold the ordinal position in each enum. Safe-fallback helpers — `feedWidthFromOrdinal()`, `feedFontScaleFromOrdinal()`, `feedSideFromOrdinal()` — default to the preset's initial value if a stored ordinal is out-of-range, so future enum extensions (e.g., adding `ExtraLarge` to `FeedWidth`) do not corrupt deployed settings. The same single-source-of-truth invariant from Stage 5 carries forward: menu and wall read the same `State<WallSettings>`, so they never disagree about the operator's choice.

### Focus model derives spatial directions from feedSide

`WallFocusModel.apply()` gains a `feedSide: FeedSide = FeedSide.Left` parameter (default preserves all 38 existing navigation tests without change). The feed zone's inner-edge gesture (gesture toward the grid) and outer-edge gesture (gesture toward the menu) are now computed from `feedSide`. When feed is on the left, RIGHT (inner edge) goes to grid and LEFT (outer edge) opens the menu; when on the right, directions flip. Similarly, grid-zone LEFT/RIGHT branches use side-aware variable names (`toGrid` / `toMenu`, `toFeed` / `intoGrid`) so the spatial logic reads clearly in both orientations. **11 new tests** in `WallFocusModelTest` pin the feed-right orientation: spatial-rule mirror checks, no-trap exitability invariant under feed-right, and FEED→GRID→FEED round-trip with preserved indices. Existing 38 tests remain untouched.

### The settings overlay

`SettingsOverlay` is a centered popup (WyzeGrid-family modal). It grew across many chapters and is now organized into labelled **sections** (2026-06-11) — **Display & Fit**, **Layout & Feed**, **Sports** — rendered as non-focusable `SectionHeader` rows between the setting rows. **UP/DOWN** navigate between rows via Compose focus; the headers are non-focusable so traversal skips them — **single-level nav, every row reachable, no two-level menus and no focus traps** (the `WallFocusModel` zone graph is unchanged; nav tests pass unmodified). The card is **height-capped to the panel and vertical-scrolls**, auto-following focus (Compose bring-into-view), so the longer grouped list never clips — important now that a 6/9 grid pushes Settings further down the side menu. **LEFT/RIGHT** on a focused row cycle/adjust its value; each press live-applies (wall recomposes immediately) and persists atomically. **SELECT** also cycles forward. **BACK** dismisses (each cycle is immediate, so there's no unsaved state). The grouping is presentational only — every setting keeps its persisted value, the locked panel-fit included. Menu slide direction follows `feedSide`.

### Composables preserve subscribers

`WallScreen` reads `wallSettings` from `LineupStore` once at the top level. The Row composable (`Row{feed, divider, grid}` or `Row{grid, divider, feed}` per `feedSide`) is composed once at the call site with the feed and grid composables defined as separate values **before** the Row is assembled. This pattern ensures that a `feedSide` swap does not recreate the `FeedRepository` subscription (held by `FeedPane`) or the `StreamPlayerManager` (held by `VideoGrid`) — both of which are expensive to initialize. The layout order changes; the subscriptions survive.

`FeedPane` accepts `fontScale: Float` and multiplies all `sp` values by it; the legibility floor (`0.88×` at Small) and overflow ceiling (`1.18×` at Large) are asserted at test time in `WallSettingsTest`.

### What's deliberately NOT here

- **Feed filtering / search UI** — grouped with the Stage 7 feed-restructure chapter; filtering is orthogonal to layout.
- **Section collapse / jump-by-source** — future enhancement if the operator wants to skip to a specific source header. Current UP/DOWN within sections suffice.
- **Global UI density scale** — the operator raised this question during Stage 8 planning; surfaced as the vision question called for in the chapter prompt and the operator chose to defer to the BACKLOG (`"Overall UI sizing"`) for revisit once the new MyMTS box is on a real TV.
- **Kiosk story** — long-uptime foreground watchdog. Unchanged; still deferred to the new MyMTS box.
- **In-app full-article reading** — closed door, permanent. Feed expand remains a plain-text excerpt only.

---

## 16. Stage 9 — ticker real data + modes (markets + sports)

The ticker previously showed **SAMPLE markets data** behind honest SAMPLE pills (via `SampleTickerSource`). This stage makes it **REAL** and adds a **sports mode** plus **markets/sports rotation**. The operator chose "build both" at the source-investigation checkpoint.

### Data flow: helper poller to TV TickerStrip

The helper's `mymts_helper/ticker/` package owns ticker data via **in-memory snapshots** — ephemeral by design, no DB table, no cross-thread sqlite exposure:

```
Yahoo Finance JSON ┐   (v8 chart, per-symbol, concurrent)
CoinGecko JSON     ├─▶ MarketsPoller ──▶ in-memory snapshot ──▶ /api/ticker/markets
ESPN scoreboard    │                     SportsPoller      ──▶ /api/ticker/sports
(SAMPLE on miss)   ┘

/api/ticker/{markets,sports} ─▶ HelperClient (parse, pin schema_version) ─▶ HelperTickerSource
                                                       (rotate modes: 22s markets, 14s sports)
                                                                    ↓
                                                            TickerStrip (render real + sample pills)
```

The TV **never** fetches market/sports data directly — same boundary as feed/channels. The helper is the source-of-truth gatekeeper.

#### Markets source — Yahoo Finance (2026-06-11, replacing Stooq)

`MarketsPoller` originally fetched **Stooq** CSV for indices/FX/gold, but Stooq **bot-walls the NAS egress IP** (the prober gets a JS-challenge page, not data) — so those symbols fell back to honest SAMPLE while only CoinGecko crypto was live. The fix is sourced on the **NAS-reachability gate** (the WeatherNation lesson: *Mac-works ≠ NAS-works* — validate from inside the helper container, not the dev machine). **Yahoo Finance's keyless v8 chart endpoint** (`query1.finance.yahoo.com/v8/finance/chart/<symbol>`) IS reachable from the NAS egress and covers the whole set — indices, FX, gold, oil (Brent/WTI), and the 10Y yield (`^TNX`) — so the entire markets ticker is now **live**; crypto stays on CoinGecko.

- **Per-symbol isolation (C2):** one HTTP request per symbol, fetched **concurrently** (`asyncio.gather`) — a single symbol failing samples only that symbol, never the whole snapshot; wall time is bounded by the slowest single fetch.
- **Honesty (C3) preserved:** `parse_yahoo_chart` reads `meta.regularMarketPrice` + `chartPreviousClose` (direction = up/down/flat); a symbol whose fetch fails or parses empty still falls back to an honest SAMPLE placeholder. Real-when-reachable, honest-SAMPLE-only-on-failure — the label logic is unchanged, it just no longer triggers in the normal case. The DTO shape is identical, so the TV needs no change.

### Why in-memory, not a DB table

Ticker data is **ephemeral** (only the latest snapshot matters). No history is kept; no retention sweep runs. This eliminates the schema-migration burden, keeps the ticker path free of the feed path's cross-thread sqlite exposure, and is fail-closed: a restart wipes the snapshot and the next poll refills it.

### Sources: all keyless, no new secret

| Source | Symbols | Fetch | Direction |
|--------|---------|-------|-----------|
| Stooq | ^SPX, ^DJI, ^NDQ, ^FTM, ^DAX, ^NKX, ^HSI (indices); EURUSD, GBPUSD, USDJPY (FX); XAUUSD (gold) | CSV (`/q/l/?s=…&f=sd2t2ohlcvn`) | `open` vs `close` |
| CoinGecko | BTC, ETH spot + 24h change | JSON (`/api/v3/simple/price`) | 24h change sign |
| SAMPLE-ONLY | Brent, WTI, 10Y UST | — | stay `is_sample=true`, never faked |
| ESPN | MLB, NFL, NBA, NHL scoreboards | JSON (`/apis/site/v2/sports/SPORT/LEAGUE/scoreboard`) | `NONE` (no arrow) |

Stooq occasionally throttles rapid repeats; pollers isolate per-source, falling back to honest SAMPLE for that cycle and recovering next cycle. **No API key**, so the helper holds **zero new secret** — the helper-holds-a-secret line was deliberately not crossed.

### Real-vs-sample + honesty (C3)

Each `TickerEntryDTO` carries `is_sample` (helper decides, TV renders verbatim):
- **Real** → pill dropped, live value shown.
- **Sample** → SAMPLE pill stays visible.
- **Unreachable** (markets) → honest all-SAMPLE fallback, never frozen old numbers as current.
- **Sports unavailable** (ESPN unreachable) → a `"scores unavailable"` entry (`is_sample=false` — a true state, not sample data).

The envelope carries a `stale` flag (set when real data aged past 15 min) so staleness is never silent. An all-sample pre-poll snapshot is **not** stale — sample is honest, not stale.

### Modes + rotation

`HelperTickerSource` polls both endpoints (60 s) and rotates presentation on a calm timer — markets dwell ~22 s, sports dwell ~14 s. The marquee restart on each swap is the intended "mode changed" cue. The `TickerSource` abstraction (built in Stage 3 for exactly this) needed no change at the consuming `TickerStrip`. In phantom mode no pollers run: markets render all-SAMPLE, sports render the SAMPLE slate, zero outbound.

### Code structure

```
helper/src/mymts_helper/ticker/
├── __init__.py   TickerEntryDTO, DIR_{UP,DOWN,FLAT,NONE}, TICKER_SCHEMA_VERSION=1
├── markets.py    parse_stooq_csv, parse_coingecko, build_snapshot (honest mix)
├── sports.py     parse_scoreboard, SAMPLE_SPORTS slate, no_games_entry
├── pollers.py    MarketsPoller, SportsPoller (latest snapshot, no DB)
└── api.py        GET /api/ticker/markets + /api/ticker/sports
```

App side: `TickerEntry.Direction.NONE` (no arrow for sports); `HelperClient.parseTicker` (schema_version pinned, missing `is_sample` defaults TRUE = fail-safe, unknown direction → FLAT); `TickerSnapshot`; `HelperTickerSource` (rotation + the pure `entriesFor` fallback rules); `WallScreen` swapped `SampleTickerSource` → `HelperTickerSource(client)`.

### What's deliberately NOT here

- **Per-team / per-league curation UI** — a default league set (MLB/NFL/NBA/NHL) + the mechanism ship now; richer curation is deferred (BACKLOG).
- **Sample-only symbols** — Brent/WTI/10Y UST stay honest SAMPLE pending a keyless source.
- **Paid market/sports sources** — keyless-only by policy; if only paid exists for something, it stays sample.

---

## 17. Stage 10 — kiosk / foreground / boot (own-the-box, Model A)

The dedicated MyMTS Onn box arrives today. This stage builds the kiosk, foreground, and boot scaffolding that does not need the physical device — on-hardware validation is explicitly staged for the migration session. **Model A**: one kiosk app per box. The new box runs MyMTS as the sole kiosk; `.182` stays WyzeGrid's and is untouched.

### Load-bearing safety property: opt-in, off by default

Kiosk mode is **opt-in and disabled by default**. The same signed APK on a non-kiosk box (notably `.182`, where a MyMTS dev install may linger) must not autostart on boot or launch a foreground service. Only the provisioning runbook flips it on for the dedicated box via `adb ... --ez kiosk true`.

This gate eliminates the Stage 1/2 risk: two watchdogs thrashing over foreground-service reclaim. Model A says "own the box, stay foreground reliably" — not "coexist and reclaim." The opt-in flag is the load-bearing property that makes this safe, and is what guarantees the shared APK does nothing kiosk-ish on `.182`.

### Components and wiring

- **`KioskPolicy.kt`** — pure Kotlin (no Android). `BOOT_ACTIONS` allowlist `{BOOT_COMPLETED, LOCKED_BOOT_COMPLETED, QUICKBOOT_POWERON, htc QUICKBOOT}`; `isBootAction(action)`; `shouldStartOnBoot(action, kioskEnabled) = kioskEnabled AND isBootAction` (both conditions). Crash-loop backoff in `restartBackoffMs()`: 0/2/5/15/30/60 s cap, windowed by `isSameCrashStreak()` over a 5-min streak-reset window.
- **`KioskPrefs.kt`** — `SharedPreferences` flag, `DEFAULT_ENABLED=false`. `isEnabled` / `setEnabled` / static `isEnabled(context)`. No secrets, no PII.
- **`KioskService.kt`** — foreground `Service` with an ongoing low-importance notification + dedicated channel. `START_STICKY`; `onTaskRemoved()` relaunches the wall (gated on `KioskPrefs`). `startForeground()` uses `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` guarded by API 34 (`Build.VERSION_CODES.UPSIDE_DOWN_CAKE`), else plain `startForeground`. Companion `startIfEnabled(context)` no-ops when kiosk off, `stop(context)`, `launchWall(context)`.
- **`BootReceiver.kt`** — `BroadcastReceiver`; `onReceive()` returns early unless `KioskPolicy.shouldStartOnBoot(action, KioskPrefs.isEnabled(context))`; then `startIfEnabled()` + `launchWall()`.
- **`AndroidManifest.xml`** — permissions `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`; `<service .kiosk.KioskService exported="false" foregroundServiceType="specialUse">` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification; `<receiver .kiosk.BootReceiver exported="true">` filtered to the 4 boot actions. Holding the permissions starts nothing — the prefs gate is the only switch.
- **`MainActivity.kt`** — `applyKioskExtraIfPresent()` persists the kiosk flag **only** when the launch intent carries the `kiosk` extra (`--ez kiosk true|false`); a normal launch never changes kiosk state. `KioskService.startIfEnabled(this)` runs on launch (no-op if off). Keep-screen-on retained for the wall role.

### Own-the-box, not coexistence

Model A means the box is MyMTS's alone, so there is **no** foreground-reclaim / coexistence machinery — the risky part of the Stage 1/2 two-watchdog story is simply not built. If a future shared-box use case ever arose, reclaim logic would need fresh threat-modelling; it is out of scope here by design.

### Pure policy + unit tests now; on-hardware validation STAGED

All policy logic is pure and deterministic — 7 `KioskPolicyTest` cases cover the boot allowlist, the both-conditions gate (the `.182`-safety property), the relaunch decision, and the backoff schedule + streak window. So the device session validates *wiring*, not *logic*.

**Not verified until the box arrives** (and honestly labelled so): the service actually holding the foreground across hours; the boot receiver relaunching via a real power-cycle; low-memory survival; the full runbook end-to-end; and the accumulated nav/feed/config/ticker feel-test (now on the MyMTS box rather than borrowed `.182`). The helper redeploy (13 feed sources + ticker endpoints) is a migration prerequisite.

### What's deliberately NOT here

- Coexistence / foreground-reclaim (Model A makes it unnecessary); anything on `.182`.
- A settings-menu kiosk toggle — the `--ez kiosk` adb intent is the v1 provisioning mechanism.

---

## 18. Stage 11 — LAN web client (a second, origin-isolated client)

MyMTS gains a **second client** alongside the native TV app: a minimal,
credential-free web view for a laptop/phone on the home network. This is
the multi-client model the architecture was always built for — one
hardened helper, many dumb consumers — and is the *separate client*
path the founding native-over-web decision (§1) explicitly sanctioned,
NOT a reversal of it.

### Why a web client doesn't reopen the threat the native decision closed

The native-over-web decision (§1) was about one specific threat: a
browser client sharing cookie/origin space with the operator's other
`*.<DOMAIN>` services (behind Cloudflare Access) becomes a
cross-service path. This web client closes that off by construction:

- **Separate origin, LAN-only.** Served on the helper's bare LAN address
  (`https://<LAN_IP>:8443/app/`) — not a `*.<DOMAIN>`
  subdomain, not tunneled, not behind Cloudflare Access. It shares no
  origin and no cookie jar with the operator's other services, so the
  browser's same-origin policy enforces the isolation.
- **Credential-free.** No login, cookies, session, or tokens; `fetch`
  uses `credentials: "omit"`. The helper data is already inert public
  plain text — nothing to steal, no session to hijack.
- **The helper stays the only boundary.** The web client does no
  hostile-input work, no article-page fetch, no stream resolution — it
  consumes `/api/feed`, `/api/channels`, `/api/ticker/*`, `/health`
  exactly as the native app does.

### Same-origin → no CORS

The SPA is served *by the helper* (static mount at `/app`), so it calls
`/api/...` same-origin — the helper opens **no** CORS to any other
origin. The mount is config-gated (`WEB_CLIENT_DIR`, off by default):
when unset the helper is byte-for-byte its prior self; when set it
serves the bundled `web/` directory as GET-only static files, mounted
last so it can never shadow an `/api/*` or `/health` route. No change to
the SSRF-safe fetcher, parsers, or the non-root/read-only/cap-drop
posture.

### Structure + honesty

`web/` is a dependency-free vanilla-JS SPA: `js/render.mjs` holds the
pure label/group/honesty logic (unit-tested with `node --test`),
`js/api.mjs` the same-origin fetch wrappers, `js/app.mjs` the DOM wiring
(writes results via `textContent` only — no helper string is ever
interpreted as markup). It renders the feed (sectioned by source,
newest-first), the ticker (markets + sports with SAMPLE pills + stale
notes preserved), channel live/offline/unknown status, and health — the
same honesty discipline as the native app (sample/stale/offline never
shown as live). A page-level CSP (`connect-src 'self'`, `frame-src
'none'`) is a belt-and-braces A1 guard: the browser itself forbids
reaching an article page or iframing one. **A1 closed door holds — no
in-browser web reader.** In-browser HLS video is a documented follow-on
(the live grid lives on the TV wall).

### Rework (2026-06-06) — mirror the wall + in-browser video + scrolling ticker

After hands-on use, the web client was reworked to **read as the same app as the TV wall**, not a separate dashboard: a **scrolling ticker** (a Web-Animations marquee — see the crawl-parity note in §24, markets↔sports rotation, hover-to-pause) across the top, a **feed pane** left, and a video grid right (then a 2×2 mirroring `WallScreen`; the web default is now **2×3** — see §24). The dashboard channel-roster moved into a settings panel. Video is played in-browser via the vendored **`hls.js`** (`web/vendor/`, pinned, loaded as `script-src 'self'` — no CDN) on the same public HLS URLs the helper resolves; a stream that won't load shows an honest **offline** tile. A mouse **gear** opens settings (no D-pad in a browser): video-grid size (slider + draggable splitter), feed text size, feed source show/hide, channel roster — all **browser-local view prefs** (localStorage), distinct from the TV's on-device settings (the web client has no write path to those; per-client helper state is the deferred cross-platform-profiles fork). The **CSP** widened only `connect-src`/`media-src` to `https:` for arbitrary stream CDNs (hls.js fetches `.m3u8` + segments); `frame-src 'none'`/`object-src 'none'`/`script-src 'self'` stay locked — **video playback is not a web reader; the A1 closed door holds**, and the credential-free client has nothing to exfiltrate over the broader `connect-src`.

### Ticker — scroll + ESPN current-games sports (shared by both clients)

Both clients render the ticker as a real scroller (native `TickerStrip`; web Web-Animations marquee). The substantive shared fix is **sports content grounded in ESPN's live state** (helper `ticker/sports.py`): the ESPN scoreboard returns the *next scheduled* games even off-season (e.g. the NFL endpoint serves September preseason fixtures in June), which a live ticker must not show. `parse_scoreboard` now keeps a game only if it's **current** — in-progress always, a final within ~12 h back ("today"), or scheduled within ~12 h forward ("later today") — and **drops far-future fixtures + stale results**; a league with no current games is **omitted entirely** (NFL-in-June → gone; MLB-in-June → shown). This is C3 honesty applied to sports: the ticker reflects what's *current*, and shows the honest "no games" line rather than padding with stale fixtures. The window logic is pure + unit-tested (mock in-season/off-season/live/final/scheduled). It composes with the curation chapter's league toggles: a league shows only when it is both curated-on AND currently-in-season-with-games.

### Rework round 2 (2026-06-06) — league markers · agnostic feed · cell-count grid · channel picker · mixed-content honesty

**Parity pass (2026-06-12, Campaign 3 HALF 1).** The web ticker now renders bespoke **per-sport cards from the structured ticker data** (`game`/`card`, dispatched on `card.kind` — leaderboard/fight/match/race — never by parsing the legacy `display` string), team game cards carry the ESPN status block (color-coded live/final/upcoming), and **news** is in the web ticker. The honesty flags carry verbatim: per-entry `is_sample` → SAMPLE pill, envelope `stale` → STALE — the real "no games" entry shows no pill. A **`schema_version` guard** (`api.mjs`, against `TICKER_SCHEMA_VERSION`) makes a wire-contract bump surface a visible "client out of date" state instead of silently rendering an unknown shape (closes review ARCH-1). Web **settings** now mirror the native content/layout controls (leagues pool, grid R×C, feed recency, ticker scroll/news) in a gear modal, localStorage-persisted; the TV-panel-only fit controls are deliberately absent (a browser isn't fighting panel overscan). Per-client *helper-side* state (different lineups per display, a VLC/M3U playlist) remains the cross-platform-profiles fork.

A second hands-on pass refined the web client and corrected a video diagnosis.

- **Ticker league markers (both clients).** Sports repeated the league per game. `render.mjs::groupTickerByLeague` and the native `ui/wall/TickerGrouping.kt` (a pure, unit-tested object) group **consecutive** same-symbol entries into runs, so the league/market marker shows **once** (an accent pill) then its games/values follow — the ESPN-BottomLine pattern. Markets symbols are distinct → each is its own one-cell run (unchanged); sports collapse. Both clients group identically (cross-client consistency). SAMPLE pills survive grouping.
- **Agnostic web feed (web only).** `render.mjs::feedChronological` flattens to one newest-first river across all sources; `app.mjs` renders the **source next to each headline** (`sourceLabel`) — the original Onn style, not per-source sections. The **native** feed keeps its Stage-7 per-source sections **on purpose** (a per-client preference, not a reversal); whether to also make the native feed agnostic is an open operator question logged in `BACKLOG.md`. Honest staleness stays per-item via the time/age.
- **Cell-count grid.** The freeform size slider + draggable splitter were replaced with **cell-count** configuration — `render.mjs::gridLayout(1|2|4|6|9)` → near-square `{cols,rows}` driving `--grid-cols/--grid-rows`. Matches the native wall's tile-count model; the browser host isn't the constrained S905Y4, so it goes past 4. Feed width became a clean Settings control. Cell count + per-cell channel assignment + feed width persist in `localStorage` (`mymts.web.prefs.v2`).
- **Click-to-pick channels.** A cell is a click target → a channel picker (`app.mjs::openPicker`) listing every channel with an honest badge (plays-in-browser / on-the-TV-wall-only / offline), browser-playable first, plus "Clear this cell". The tile labels its channel and shows a "click to change" chip; empty cells show "＋ Add channel".
- **Mixed-content honesty (no proxy).** Diagnosis (finding 16, when the lineup was 10 channels) showed all of them HTTPS-clean **and** CORS-allowed — so mixed content was *not* why tiles were blank for that set. Reported truthfully. *(The lineup is now 53 and that "all clean" snapshot isn't re-verified across it — but it doesn't need to be: the runtime `<video>` load result below is the live authority, so any not-playable channel self-flags honestly regardless.)* The suspected real cause — hls.js's `blob:` worker blocked by the locked CSP — is fixed with **`enableWorker:false`** (no CSP widening) plus a click-to-play autoplay fallback. The honest **play-what-works** system is built regardless: the helper classifies a per-channel `browser_playable` hint (`channels/prober.py::classify_browser_playable`, migration 002, on `/api/channels`) by scanning the master+variant bodies for `http://`; the client uses the tri-state `browserPlayability` hint **and** the runtime `<video>` load result (the ground truth, which also catches CORS/geo/dead) to flip a failing tile to the honest "on the TV wall" state. The helper never proxies video — it stays the resolver/shield; the **TV remains the full-fidelity client**.

### Native vs web: why the TV is the full-fidelity client (Browser Client Refocus, 2026-06-13)

The two clients are **not** peers, and the web client is honest about that. The
native wall plays HLS with Media3/**ExoPlayer**, which has none of a browser's
playback limits: no mixed-content block, no CORS gate on manifests, no missing
codec/DRM support, no autoplay policy. The web client plays the *same* helper-
resolved public URLs with vendored **hls.js** (or Safari's native HLS) inside a
locked same-origin CSP — so a real subset of channels the TV plays cleanly
simply **cannot** play in a browser. **The dead web tiles are the evidence of
the boundary, not a bug to chase away.** The helper never proxies the video to
erase the gap (that would make it a gateway, breaking the no-proxy / SSRF-shield
posture) — so the TV stays the full-fidelity client by construction.

The refocus made the web client *honest and self-healing about that boundary*
instead of leaving dead tiles:

- **Auto-recovery that knows why it failed (Part 1).** `render.mjs::classify
  VideoFailure` (pure, unit-tested) splits a failure into **transient** (network
  blip / decode hiccup / a new stall watchdog firing → worth a fresh attempt) vs
  **genuinely-unplayable** (DRM / codec / no-browser-HLS / unsupported source → a
  browser can never play it). `videoRetryDecision` retries the transient class on
  an exponential backoff (2→4→8→16s, cap 30s) up to a bounded `VIDEO_MAX_RETRIES`,
  then falls to the honest terminal state with reason `exhausted` — **never an
  infinite retry on a hopeless stream.** A genuinely-unplayable failure is marked
  immediately with reason-specific honest copy ("Protected stream (DRM) — on the
  TV wall", etc.), never retried. Manual escape hatches: a per-tile ↻ and a
  whole-wall ↻ (keyboard-accessible). DOM orchestration (`video.mjs`/`app.mjs`)
  uses a per-attach generation guard so a late event from a superseded handle
  can't resurrect a torn-down tile or cancel a pending reconnect.
- **Grid cap is native parity, not a limit to lift (Part 2).** The web grid is
  rows × cols, each clamped **1–3** (`GRID_DIM_MAX`, up to 3×3 = 9) — the *same*
  clamp as the native `WallSettings`. This is a deliberate constraint mirroring
  the TV wall (legible/performant on a 1080p panel driven by the constrained
  S905Y4), **not** a feed-width or CSS limit (the CSS grid is `repeat(var(--grid-
  cols), 1fr)` and would render more fine). Raising it on web alone would break
  the "same wall on both screens" contract; it is documented in-product (the
  settings note) and in code rather than widened. *(This supersedes the older
  cell-count rework note above, which let the web "go past 4" under the retired
  `gridSize` model.)*
- **Ticker motion is one cross-platform setting (Part 3).** Both motions now
  exist on **both** clients: the web wall's continuous **crawl** and the native
  wall's paged **flip** (`TickerMotion` / web `tickerMotion` pref). Only the
  per-platform **default** differs (web = crawl, native = flip), preserving each
  wall's established feel; the operator can switch either. The web side ships;
  the native side is **built + unit-tested but not yet deployed** — it rides the
  next on-device (`.92`) release, the same as any native-wall change.
- **Crawl-motion parity (2026-06-18).** The web crawl now shares the native
  crawl's consistency model (see *Native perf* below): it's **time-based** — a
  Web-Animations marquee whose duration is `period / pxPerSec` (distance ÷ speed),
  so the velocity is constant regardless of frame rate / CPU load (compositor-
  timed, sub-pixel; verified ~60 px/sec under 6× throttle) — with a **scroll-then-
  dwell** (`CRAWL_DWELL_MS` 3000 ms hold per pass), a **seamless** loop period of
  `copyWidth + gap` (the measured first-clone offset, no seam pop), and a re-key
  **only on genuine content/speed change** (a routine poll no longer restarts the
  scroll). This replaces the old CSS `infinite` marquee, matching native's
  framework-timed `Animatable` crawl so both walls feel identical.

### Remote access is a deferred future chapter

Exposing the web client beyond the LAN — a genuinely separate public
origin, its own threat-model pass, an auth story plus the credential
tradeoff that introduces, rate-limiting — is a conscious later decision,
scoped in `docs/BACKLOG.md`, **not built**. The LAN-only version
sidesteps all of it by being unreachable from outside the network.

---

## 19. Stage 12 — feed filtering (by source + recency)

The feed gains operator-controlled **filtering** — narrow it to chosen sources and/or a recency window — the filtering half of usage-feedback item B (the sectioning half shipped in Stage 7). All of it operates on the plain-text items the helper already serves: **no new fetch, no web, A1 holds.**

### What's built (and the search decision)

- **Source filter (denylist).** `WallSettings.hiddenSources: Set<String>` — toggle which of the feed's sources appear. Stored as a *denylist* ("hide these"), so a newly-added feed source shows by default rather than being silently hidden. Also satisfies the "feed source enable/disable" curation idea (item C).
- **Recency filter.** `WallSettings.feedRecency` (`All` / `Last hour` / `Last 6h` / `Last 24h`) — drop items older than the window (published time preferred, fetched fallback; items with no parseable timestamp are kept under `All`, dropped under a bounded window since we can't prove them recent).
- **Free-text search — DELIBERATELY DEFERRED.** D-pad free-text entry on a 10-ft wall is high-friction for low ambient value; rich source + recency filtering delivers most of the "narrow the feed" value without an on-screen keyboard. Logged in `docs/BACKLOG.md`; revisit if the operator finds source/recency insufficient after using the wall.

### Where it lives + the pure core

The filter is a **pure step** in `FeedListBuilder.applyFilters(items, hiddenSources, recency, now)`, applied BEFORE `build()` groups the items — so the sectioned layout, the per-source freshness chips, and the focus flat-index all operate on exactly the visible set. Controls live in the settings surface (`SettingsOverlay`): a "Feed recency" cycle row + a "Feed sources…" row that opens `SourceFilterOverlay` (a D-pad toggle list of the feed's distinct sources). Both persist on-device via `LineupStore` (denylist as a JSON string-set, recency as an ordinal).

### Focus model unchanged; honest empty states

The filter UIs are **modal overlays** (`MenuState.PendingSelection.Settings` / `SourceFilter`) — they don't change `WallFocusModel`'s zone graph, so the no-trap invariants hold unmodified (the 49 nav tests pass unchanged). The one focus-relevant subtlety: `FeedPane` reports the **filtered** item count to the focus model (`onItemCountChanged`), so `feedIndex` can't run off the end of a filtered list. When a filter hides everything, the pane shows an honest, filter-aware empty state ("No items match your feed filters…") — never a blank pane that looks broken — and per-source freshness chips persist on the sections that remain.

### Reuse note for a future ticker-news mode

The "which items matter" notion here (source subset + recency) is the same shape a future ticker-news mode would consume to decide which headlines to scroll. The filter logic is kept pure + parameterized so it can be reused there without rework; the ticker-news mode itself is a separate chapter (not built here).

### What's deferred

- Free-text search (D-pad friction; BACKLOG).
- Topic/keyword **auto-classification** — a foundation v2 idea; topic filtering = keyword search, not auto-tagging (BACKLOG, stays deferred).

---

## 20. Stage 13 — curation & preferences (sports curation, ticker news, source toggles)

The "tune what I see" controls, in the settings menu: which sports leagues the ticker shows, whether news rides the ticker as a third mode, and (from Stage 12) which feed sources appear. All persisted on-device; all honoring the no-faked-data discipline. **Several choices here are FEEL-TEST items — the mechanism is built and configurable, but the exact granularity is flagged for the operator to confirm after using the wall on real hardware** (see `docs/findings/14-curation-pass.md`).

### A — Sports curation (league-level, TV-side)

`WallSettings.hiddenLeagues` is a denylist of league labels; the ticker's sports mode drops entries for hidden leagues (`HelperTickerSource.filterLeagues`, matched on the entry's league symbol). Filtering is **TV-side** — the helper keeps serving all leagues, so per-device curation needs no helper state (deliberately avoiding the cross-platform-profiles fork). Status lines ("no games") survive a denylist; if curation empties the list, the honest "scores unavailable" line shows. Offered leagues are the helper's default set (MLB/NFL/NBA/NHL). **FEEL-TEST:** league-level toggles ship; **team-level** favorites (the operator's Chicago teams) is logged as a follow-on to confirm post-hardware — building a team-picker before seeing scores flow risks the wrong granularity.

### B — Ticker news (third rotation mode, default OFF)

When `WallSettings.tickerNewsEnabled` is on, the ticker rotates **markets → sports → news** (`HelperTickerSource.nextMode` is a pure 2- or 3-cycle depending on the flag). News entries are built by `HelperTickerSource.newsEntries(feedItems, hiddenSources)` — **newest-first headlines from the operator's non-hidden feed sources** (reusing the Stage 12 source denylist), capped, drawn from the feed the wall already polls (no duplicate fetch). Each is a real headline (`isSample=false`), inert plain text, `Direction.NONE` (no arrow). **Honest-engineering line:** RSS cannot reliably flag urgency, so there is **no fabricated "breaking news"/urgency detection** — the achievable honest version (source-subset + recency, newest-first) is what's built; true urgency detection needs a real signal source and is logged as a separate future item. **FEEL-TEST:** default OFF — whether the operator wants news in the ticker at all, and from which sources, is confirmed after use.

### C — Feed-source toggles

Built in Stage 12 (the feed-filtering chapter) as the source denylist; reused here (the ticker-news source set is the same `hiddenSources`). Not rebuilt.

### Where it lives + focus

All controls are rows in `SettingsOverlay` ("Ticker news" on/off, "Sports leagues…" opener) + the reused `SourceFilterOverlay` toggle list (parameterized with a title) for leagues. They're modal overlays — `WallFocusModel`'s zone graph is unchanged (no-trap invariants intact; nav tests pass unmodified). Curation is pushed into the running ticker via `HelperTickerSource.setCuration(...)` from a `WallScreen` effect that re-fires when settings or the feed change.

### What's deferred (BACKLOG)

- **Team-level sports curation** (favorites/pinning) — FEEL-TEST follow-on.
- **True breaking-news / urgency detection** — needs a real signal source; not faked.
- **A dedicated ticker-news source subset** distinct from the feed denylist — if the operator wants the ticker narrower than the feed.

---

## 21. Panel fit (global UI scale + overscan inset) + native agnostic feed (2026-06-07)

On the dedicated box's 720p panel, two things surfaced. **(1) Panel fit.** The box outputs a clean 1280×720 @ density 213, but the *panel physically overscans* (cuts the outer edge) where the WyzeGrid box's panel doesn't, and modern Android removed the software overscan knob (`wm overscan`). The fix is app-side + panel-agnostic, via two `WallSettings` presets persisted in `LineupStore` and cycled from Settings ("Display size" + "Overscan inset" rows lead the card):

- **`UiScale`** (Compact 0.80 / Default 1.0 / Roomy 1.15) — a single `LocalDensity` override in `WallScreen` (`Density(base.density × multiplier, base.fontScale)`) wrapping the entire wall, so all chrome (ticker, feed, grid, labels, overlays) scales together. Scaling `density` scales dp and sp uniformly; the per-piece feed width/font tune *within* the scaled layout. Cashes in the deferred "global UI sizing" item.
- **`Overscan`** (None 0% / 3% / 5% / 7%, default 5% = TV action-safe) — a safe-area inset measured in **real screen space** (`BoxWithConstraints` *outside* the density override, so it's a true physical margin, not itself scaled); the black root shows through the inset.
- **`offsetXDp` / `offsetYDp`** (±64 dp, 8 dp step, default 0,0 — added 2026-06-09) — a **position offset** that nudges the whole wall via `Modifier.offset` on the inset Box (also real screen space, outside the scale), to **recenter** a panel that overscans *off-center* / shifts the image and has no hardware menu. Scale + inset are symmetric and can't recenter a shifted image; this can — completing the set (**scale + inset + offset** = full software compensation for a non-adjustable panel). `Modifier.offset` shifts layout + hit-testing together, so D-pad focus is unaffected. D-pad-adjustable live (LEFT/RIGHT) so the operator dials it in by eye.

**(2) Native agnostic feed.** Per the operator's on-hardware decision, `FeedListBuilder.build` now returns a flat newest-first `List<FeedItem>` across all sources (per-source sections + `FeedListEntry`/`SectionFreshness` removed); `FeedPane` shows the source label (accent) + age per headline — matching the web client. The `WallFocusModel` is **unchanged** (the feed was already a flat N-item focus zone; `feedIndex == list index` now, no headers to skip — 49 focus tests pass). C3 honesty moves from per-section chips to per-item age + the pane header's "not updating"/"unreachable"; A1 unchanged (inert plain text). Both clients' feeds are now the same model.

---

## 22. What this document deliberately does NOT specify yet

- Exact on-device persistence mechanism — chosen in Stage 5 (lineup/presets).
- Update mechanism details — chosen in Stage 6.
- ~~yt-dlp channel resolution (`channels.kind = 'youtube'`)~~ — **SHIPPED** (2026-06): migration 003 widened the CHECK and the resolver is **in-process** (`import yt_dlp`), NOT the predicted out-of-process sidecar. A second **free government-stream** resolver (`channels.kind = 'cspan'`, migration 004 — the Senate floor + Senate committee hearings via the senate.gov ISVP, token-free) also shipped. See §24.
- Operator-driven add/remove of channels and RSS sources via API — Stage 5 (settings UI). For Stage 2, `seed.json` is the operator-curated list; the helper upserts on every boot so edits flow in without a redeploy. **Per-deployment lineup override (2026-06-18):** a downstream self-hoster can customize the lineup WITHOUT editing the shipped `seed.json` — an OPTIONAL `lineup.local.json` in the writable data dir (gitignored) is reconciled on top of the shipped seed at boot (`channels/override.py`): **add** new channels, **disable** shipped slugs (`enabled=0`, excluded from the picker + prober), **override** a shipped channel's label/category/source_url/kind. Precedence: override field-merges win, disable beats override, an `add` colliding with a shipped slug is skipped; each effective entry is validated + probed like any channel (a bad entry is skipped + logged, never crashing the lineup), and orphaned adds are pruned so removing the override reverts cleanly. Category became storable (migration 005 — a nullable `category` column; `/api/channels` serves `category or category_of(slug)`, so NULL = the shipped taxonomy and **no override == identical to today**). This closed the last distribution make-or-break (the arc — runtime helper-URL, clean-clone compose, signed-APK publishing, per-deployment lineup — is complete).
- Buffer-sizing changes for naturally-bursty streams like dw-news-en — flagged in `docs/findings/02-player-state-machine.md §"Buffer-sizing trade-off"`, decision deferred to Part C under real 6-tile load.

---

## 23. Playlist / M3U endpoint + profile abstraction (2026-06-13 — the cross-platform-profiles fork, foundation)

The helper exposes the resolved channel lineup as a standard **M3U playlist**, so a generic player — VLC on an Apple TV in another room — can consume MyMTS's channels directly. This is the deliberate, scoped *start* of the cross-platform-profiles fork (BACKLOG item H): a multi-profile backend with VLC as one client. Foundation, not a finished multi-tenant system.

**Endpoints** (`helper/src/mymts_helper/playlist/`):
- `GET /api/playlist.m3u` — the built-in `default` profile (every channel **live now**).
- `GET /api/playlist/{name}.m3u` — a named profile (an ordered channel subset); unknown name → 404.

Both return `audio/x-mpegurl`: `#EXTM3U`, then per channel `#EXTINF:-1 tvg-id="<slug>" tvg-name="<label>",<label>` followed by the channel's resolved upstream URL.

**The helper stays the resolver/shield — no video proxy.** Each entry points at the channel's probed `current_url` (the URL the TV plays), NOT a helper-relayed path; the helper does not enter the video bytestream (the no-proxy decision). The M3U is a *channel list*, not a gateway — so the SSRF/egress boundary and the "native app is the full-fidelity client" framing stay intact. No new outbound fetch (it reads the snapshot the prober already maintains). Note VLC is a more capable client than the LAN web grid — it plays plain-`http://` HLS — so `browser_playable` (a browser mixed-content hint) is *not* used to filter the M3U; the playlist equals the native wall's resolvable set.

**Honest degradation (C3).** Only `status==live` channels are emitted — the same gate `/api/channels` uses (`current_url` is masked unless live). A channel that doesn't resolve — even one a profile names — is dropped, never listed as a working endpoint; a fully-down lineup is a valid, empty `#EXTM3U`, never a fabricated list. Rendering collapses CR/LF and strips quotes from labels so a label can't inject a playlist line (the DATA-2 "one bad row must not break the batch" lesson, applied at the output edge).

**Profiles** (`playlist/profiles.py`). A `Profile` is `(name, slugs)`: `slugs is None` ⇒ "every live channel" (the built-in `default`); otherwise an explicit, ordered allow-list. The registry is the built-in `default` plus optional operator-defined named profiles loaded from a JSON file at `PROFILES_FILE` (`helper/profiles.example.json` documents the shape) — read **once at startup**, operator data kept out of git, the loader tolerant (a bad/again-bad file degrades to default-only so the wall still boots; an invalid entry is skipped; a file entry named `default` is ignored — the built-in wins). The endpoint stays **stateless**: it returns what is live *now*, narrowed/ordered by the requested profile.

**This is not the TV's lineup.** The profiles file is a small, optional, read-only-at-startup operator config (like `seed.json`), distinct from the TV app's mutable on-device `LineupStore`. Per-client server-side prefs (audio/caption/layout), identity, and cross-device **sync** — the full item-H question — stay deferred; this lays the backend shape (named profile → channel selection → playlist) to build on, and is deliberately stateless so it does not prejudge that decision.

**Posture.** LAN-only, like the rest of the helper — one more route on the existing listener, no new public surface, no new dependency. The remote/public web client remains a separate, deferred security item; this endpoint does not touch it.

**Operator validation** (post-deploy): `curl https://<helper>:8443/api/playlist.m3u` returns valid M3U; load that URL in VLC, then VLC-on-Apple-TV (add a network stream) → a clean-resolving channel plays. See `docs/findings/22-playlist-profiles.md`.

---

## 24. Recent surface (2026-06): yt-dlp resolver, rebuilt web client, desktop executable

A cluster of work that post-dates the section structure above; the stage sections stay as the historical record, this is the current state.

**yt-dlp YouTube resolution (`channels.kind = 'youtube'`) — shipped, in-process.** Migration 003 widened the `kind` CHECK to admit `'youtube'`; the resolver lives in `helper/src/mymts_helper/channels/youtube_resolver.py` and `import yt_dlp` directly (a library call, **not** the once-predicted out-of-process sidecar). On the prober's cycle it resolves a YouTube `/live` URL to its HLS manifest, **gating on `is_live`** so a non-24/7 feed resolves to an honest offline rather than a dead URL; a thread-safe cache keyed off the manifest's own `expire` refreshes before expiry. The resolved manifest is then validated by the **same** SSRF-safe master/variant fetch as a direct-HLS channel — egress is the helper's normal (residential) path, never PIA. Lineup is now **53 channels** (26 direct-HLS + 25 YouTube-resolved + 2 free-government `cspan`; US-vantage), across the news sections plus the 2026-06 ambient categories **Space / Nature / Cameras** (NASA ISS-HD, explore.org + Monterey Bay Aquarium nature cams, EarthCam / earthTV) — restoring the original wall vision. yt-dlp is pinned and needs periodic bumps (the desktop app self-updates it — below).

**Free government-stream resolution (`channels.kind = 'cspan'`) — shipped, in-process.** Migration 004 widened the `kind` CHECK to also admit `'cspan'`; the resolver (`channels/cspan_resolver.py`) sources C-SPAN's **free, no-login** government livestreams from the government's *own* players — never the entitlement-gated C-SPAN/C-SPAN2/C-SPAN3 networks (Adobe Pass / MVPD), which stay out of scope. It is a **session/filename-refresh** resolver (NOT a token resolver — the senate.gov ISVP feeds carry no Akamai token, no auth, no DRM), serving two modes off one Akamai master shape: the **U.S. Senate floor** (`floor_schedule.json` → `convenedSessionStream`) and **U.S. Senate committee hearings** (the first-party `hearings.xml` schedule → an aggregate tile that surfaces whichever committee is *genuinely* live — a finished hearing whose variant carries `#EXT-X-ENDLIST` is rejected as a frozen VOD, so an ended hearing is never shown live). Both are **honest-offline** when nothing is in session. The House floor + House committees + federal agencies (White House / State / Pentagon / DHS / DOJ) ride the YouTube resolver above. Together these generalize the gov-stream recipe — *read a .gov schedule/ISVP → build the open token-free Akamai master → gate on live* — into a reusable pattern; egress is the helper's normal residential path, never PIA, and every resolved manifest still passes the SSRF-safe master/variant fetch before a channel is marked live.

**Server-authoritative channel categories (2026-06-18).** Both clients now group the channel picker by the `category` the helper serves on `/api/channels` — the LAN web client always did, and the **native** picker now does too (`ChannelCategory.sectionedByCategory`, consuming `Channel.category`) instead of its old compiled slug→section map. This closes the drift where a channel absent from the native map (the gov feeds, the YouTube additions) fell to "General" on the TV: a channel added helper-side now sections correctly with **no app rebuild**, and an unrecognized server category (e.g. a future "Government" section) is shown (appended before General) rather than hidden. The compiled `ChannelCategory.BY_SLUG` survives only as a fallback for an older helper. Verified on-device (`.92`). The app's `versionCode` was also bumped off the stale pinned `1` to a version-derived `102` (0.1.2).

**Server-authoritative wall presets (2026-06-18).** Switchable channel-sets the operator applies to the grid — **News Wall / Nature / Space / Chill** — defined and served by the helper on `/api/presets` (`channels/presets.py`), so both clients render the *same* set and a new or edited preset flows with **no app rebuild** (the same pattern as categories above). Each preset carries an ordered slug list, a suggested grid, and a `fill` mode: **`topup`** (preferred + remaining playable — today's News behavior) or **`exact`** (only the listed slugs, curated). **Default = `news`, no regression:** that preset — and the value on a fresh install — routes through each client's *existing* default lineup unchanged (native `LineupSelector.forWall`, web `newsLineup`/`WEB_DEFAULT_LINEUP`), so the wall is identical to before presets existed until the operator switches. A preset's grid uses the existing grid levers only; the locked panel-fit levers are untouched. The native side menu's WALL section gains a **Preset** row → `PresetPickerOverlay` (the served presets, the active one marked); the web menu gains the parallel selector; applying one clears per-slot overrides, applies the grid, and persists the choice per-device (native `LineupStore.activePreset`, web `prefs.activePreset`). An **`exact` preset renders its listed channels honestly** — `LineupSelector.exactLineup` (web `presetLineup`) resolves the slugs against the *full* channel set with **no deny-list**, so an explicitly-chosen channel that's currently down (e.g. NASA TV in Space) keeps its slot as an honest C2 OFFLINE tile instead of vanishing (a preset is a *selection*, not a liveness claim); the ambient default wall keeps its deny-list (`nasa-tv`: master-only HLS) since the operator didn't choose it there. Both clients pin the presets `schema_version` (refuse an unknown contract). When the active preset isn't currently served (not yet fetched, or removed helper-side) the menu label and lineup both fall back to News, and the persisted id reactivates the moment the helper serves it again. Verified on-device (`.92`).

**Rebuilt web client (2026-06-15..16).** The flat gear→`WALL SETTINGS` modal was replaced with a **native-style side menu** (the web analog of `MenuOverlay`): the gear opens a CHANNELS list (one row per slot) + WALL actions (Settings, Resync). Clicking a tile (or a menu channel row) opens **per-slot controls** (`SlotControlsOverlay` analog): Channel · Audio · Reconnect · Close. **Captions** were removed as a per-tile control and default **OFF** (these streams' captions are burned-in / unremovable; a wall-wide Settings toggle renders soft tracks where a manifest carries one). Feed-source toggles are **grouped by category** (the same taxonomy the channel picker uses, served by the helper). The web default grid is **2×3** (`DEFAULT_GRID_ROWS=2`/`COLS=3`), with a **draggable feed↔video divider**.

**Native perf (2026-06-16).** The audio renderer is disabled on inaudible tiles and at player creation (only the single audible tile decodes audio), relieving CPU on the Onn box. The ticker **crawl** is **framework-timed** (`Animatable<Float>` + `animateTo(LinearEasing)`) so its velocity stays constant regardless of frame load (replacing a clamped-per-frame-delta accumulator).

**Desktop executable (`tools/desktop/`, released v0.1.1).** The helper packaged as a launchable desktop app (PyInstaller) so a user runs the **web wall** with no clone / Docker / Python: a **menu-bar / system-tray** app (macOS/Windows) that serves the helper on `http://127.0.0.1:PORT` (localhost — a browser secure context, no TLS) and opens the wall; **headless server mode** on Linux / no-display. It reuses the helper's `create_app()` (no new server logic), keeps the seeded DB in a per-user data dir (never the read-only bundle), and runs a **yt-dlp self-update** — fetch the latest from PyPI's official hosts over HTTPS, **SHA256-verify** against PyPI's digest, and prefer it over the frozen copy via a `meta_path` finder ahead of PyInstaller's `FrozenImporter`, with an offline/launch-safe fallback — so the YouTube channels stay current independent of app releases. Signing/notarization is wired but **gated on credentials** (unsigned builds are honestly marked + carry a right-click→Open note). A tag-triggered GitHub Actions matrix builds macOS/Windows/Linux and publishes a GitHub Release. The same `v*` tag also builds the **native Android APK** (`assembleRelease`) and — **gated on the Android keystore secrets** (the same present/absent gating as macOS notarization) — signs it (`apksigner verify`) and attaches `mymts-<version>.apk` to the Release; with no secrets it builds for validation only and attaches nothing (an unsigned APK won't install). The CI APK is a *stock, runtime-configurable* build (no baked helper URL → first-run setup). The gradle signing reads the keystore from `MYMTS_RELEASE_*` env in CI and from the gitignored `keystore.properties` locally (the operator's flow, unchanged). The bundle/APK are gitignored; only the tooling/workflows are committed. See `CHANGELOG.md` + `SECURITY-PRACTICES.md` (the two signing paths) + `docs/DOWNLOAD-AND-RUN.md`.
