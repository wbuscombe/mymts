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
- **Phantom mode** (`PHANTOM_MODE=1`) replaces the fetcher's resolver with one that raises on every hostname lookup, and preloads synthetic fixtures into the DB. A CI contract test asserts that a complete app boot + `/api/feed` + `/api/channels` request makes zero outbound calls.

### Stage 2 NAS deploy

- Image pinned by digest (`python:3.13.1-slim-bookworm@sha256:031ebf3cde…`). Tag stays in `FROM` for human readability; digest is the source of truth.
- Container runs non-root (uid 10001), `read_only: true` rootfs, `cap_drop: [ALL]`, `no-new-privileges`, tmpfs `/tmp`, explicit `cpus`/`mem_limit`, no `docker.sock`.
- State lives in a **named volume** (`mymts-helper-data`) — fresh volumes inherit ownership from the in-image `/data` (uid 10001), so no host-side privileged step is needed.
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
    ├── TickerStrip.kt             ← basicMarquee scroller; per-cell SAMPLE pill
    ├── FeedPane.kt                ← 10-foot UI list; native-text only (no WebView path)
    ├── RelativeTime.kt            ← "now/Nm/Nh/Nd/date" relative-time chip
    ├── VideoGrid.kt               ← autofit grid; equal weight() rows × columns
    ├── WallTile.kt                ← state-aware cell: surface + badge + label
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

### Autofit + fit-width-letterbox

The grid (`VideoGrid`) divides its parent region evenly: rows × columns each take `weight(1f)`, so each cell is `parentWidth/columns × parentHeight/rows`. Aspect-ratio correctness moves **inside the tile**: `StreamSurface` hosts a Media3 `PlayerView` with `RESIZE_MODE_FIT`, which fills the cell's width while preserving source aspect ratio and letterboxing with black bars where dimensions differ. Net effect: the grid fills the right-hand region edge-to-edge; each tile renders video centered with clean bars rather than floating at native size or stretching.

### Lineup selection

`LineupSelector.forWall(maxCount)` walks three lists in priority order:
1. Operator's preferred slugs (`cbs-sports-hq`, `bbc-news`, `cnn`, `livenow-fox`) — picked first if playable.
2. Fallback slugs (`c-span`, `nasa-tv`, `white-house-tv`, `newsmax`, `cnn-international`) — fill if preferred didn't resolve.
3. Any remaining playable channels — top up so the grid isn't half-empty when other channels work.

Result is capped at `tileCount`. If fewer than `tileCount` channels resolve across all three lists, the remaining slots are `Slot.Empty` and the tile renders the same quiet OFFLINE panel as a `DEAD` slot. **The wall never fakes a tile** (C2 + the prompt's "do not fake completeness" rule).

### Helper-host boundary

The wall's only inbound is the helper at `BuildConfig.HELPER_BASE_URL` (sourced from `MYMTS_HELPER_BASE_URL` in `gradle.properties`). `network_security_config.xml` allows cleartext **only** for `<LAN_IP>`; every other host on the wall is HTTPS-only by Android policy. TLS to the helper is deferred to Stage 6 hardening (with operator-issued internal-CA pinning); documented in `THREAT-MODEL.md T-T4`.

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

`SettingsOverlay` is a centered popup (WyzeGrid-family modal) with three focusable rows — one for each enum setting. **UP/DOWN** navigate between rows via Compose focus. **LEFT/RIGHT** on a focused row cycle its value; each press live-applies the setting (wall recomposes immediately) and persists to disk atomically. **SELECT** also cycles forward (useful when the operator is not certain of LEFT vs. RIGHT direction). **BACK** dismisses the overlay without persisting unsaved state (each cycle is immediate, so there is no unsaved state). Menu slide direction follows `feedSide` — anchored to the feed's outer edge, the menu opens where the operator's eye is already pointed.

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
Stooq CSV          ┐
CoinGecko JSON     ├─▶ MarketsPoller ──▶ in-memory snapshot ──▶ /api/ticker/markets
ESPN scoreboard    │                     SportsPoller      ──▶ /api/ticker/sports
(+ SAMPLE entries) ┘

/api/ticker/{markets,sports} ─▶ HelperClient (parse, pin schema_version) ─▶ HelperTickerSource
                                                       (rotate modes: 22s markets, 14s sports)
                                                                    ↓
                                                            TickerStrip (render real + sample pills)
```

The TV **never** fetches market/sports data directly — same boundary as feed/channels. The helper is the source-of-truth gatekeeper.

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

All policy logic is pure and deterministic — 8 `KioskPolicyTest` cases cover the boot allowlist, the both-conditions gate (the `.182`-safety property), the relaunch decision, and the backoff schedule + streak window. So the device session validates *wiring*, not *logic*.

**Not verified until the box arrives** (and honestly labelled so): the service actually holding the foreground across hours; the boot receiver relaunching via a real power-cycle; low-memory survival; the full runbook end-to-end; and the accumulated nav/feed/config/ticker feel-test (now on the MyMTS box rather than borrowed `.182`). The helper redeploy (13 feed sources + ticker endpoints) is a migration prerequisite.

### What's deliberately NOT here

- Coexistence / foreground-reclaim (Model A makes it unnecessary); anything on `.182`.
- A settings-menu kiosk toggle — the `--ez kiosk` adb intent is the v1 provisioning mechanism.

---

## 18. What this document deliberately does NOT specify yet

- Exact on-device persistence mechanism — chosen in Stage 5 (lineup/presets).
- Update mechanism details — chosen in Stage 6.
- yt-dlp channel resolution (`channels.kind = 'youtube'`) — a future migration extends the CHECK constraint when the yt-dlp sidecar pattern lands.
- Operator-driven add/remove of channels and RSS sources via API — Stage 5 (settings UI). For Stage 2, `seed.json` is the operator-curated list; the helper upserts on every boot so edits flow in without a redeploy.
- Buffer-sizing changes for naturally-bursty streams like dw-news-en — flagged in `docs/findings/02-player-state-machine.md §"Buffer-sizing trade-off"`, decision deferred to Part C under real 6-tile load.
