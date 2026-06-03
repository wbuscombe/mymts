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

## 11. What this document deliberately does NOT specify yet

- Exact on-device persistence mechanism — chosen in Stage 5 (lineup/presets).
- Update mechanism details — chosen in Stage 6.
- yt-dlp channel resolution (`channels.kind = 'youtube'`) — a future migration extends the CHECK constraint when the yt-dlp sidecar pattern lands.
- Operator-driven add/remove of channels and RSS sources via API — Stage 5 (settings UI). For Stage 2, `seed.json` is the operator-curated list; the helper upserts on every boot so edits flow in without a redeploy.
- Buffer-sizing changes for naturally-bursty streams like dw-news-en — flagged in `docs/findings/02-player-state-machine.md §"Buffer-sizing trade-off"`, decision deferred to Part C under real 6-tile load.
