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
| `GET /api/channels[?widgets=1]` | `{schema_version, channels:[{slug, label, kind, category, current_url, browser_playable, status, enabled, last_check_at, last_success_at, last_error, error_count}]}` — `current_url` is `null` when `status != "live"` (Trust Bar C3: never expose a stale URL labelled live); `browser_playable` is a web-client hint (HTTPS-clean=true / mixed-content=false / unclassified=null, meaningful only when live); `category` is the server-authoritative picker section. `?widgets=1` (web-only) appends synthetic **weather-radar** rows; the native TV picker omits the param so its list is unchanged |
| `GET /api/ticker/markets` · `GET /api/ticker/sports` | `{schema_version, mode, as_of, stale, entries:[…]}` — markets quotes / sports game+per-sport cards; each entry carries its own `is_sample` (C3: SAMPLE never passes as live) |
| `GET /api/presets` | `{schema_version, default, presets:[{id, name, fill, grid:{rows, cols}, slugs:[…]}]}` — server-authoritative wall presets; `default` is the no-op `news` |
| `GET /api/playlist.m3u` · `GET /api/playlist/{name}.m3u` | an M3U playlist of live channels' resolved upstream URLs (the `default` profile, or a named operator profile; `404` if the name is unknown) — the helper resolves/shields, never proxies |

Contract tests pin every field name and the C3 invariant ("non-live channels expose `current_url: null`"). Located in `helper/tests/test_api.py` and `helper/tests/test_health.py`.

#### Headless-container endpoints (the wall-config control plane + the rendered stream)

The headless-container version (§26–§33) added these helper endpoints — consumed by `/control/` (writes the config), `/app/?render=1` (the rendered wall), and VLC / an Apple TV (the HLS stream), not the native TV. They carry the same `schema_version: 1` posture.

| Endpoint | Returns |
|---|---|
| `GET /api/wall` | the current wall config (or a server default when none stored), tagged `{…, stored}` — `{schema_version, layout:{rows,cols}, preset, reload_epoch, feed_pct, feed_font, ticker_scale, outputs:{hls,mercury}, cells:[{channel, audio, subtitles, reload}], stored}` (§34: per-cell `audio` + the `outputs` map; the old `render`/`audible_cell` migrate on load) |
| `PUT /api/wall` | **partial-merge (PATCH)**: a field PRESENT in the body overrides; an OMITTED field is PRESERVED from storage (so `/app/` can't clobber a field it doesn't send). The merge deepens to per-output AND per-field (`outputs.hls.bitrate_kbps` alone never clobbers `outputs.mercury`). Validation on the MERGED result; reload + per-output `restart_epoch` counters clamped monotonic; `422` with a field-specific reason. See §30/§34 |
| `GET /api/outputs/status` | per-output runtime state (the config's outputs merged with the renderer's status file, path-safe). HLS: `running`/`stopped`/`disabled` + res/bitrate + playlist path. Mercury: `disabled`/`needs_setup`/`ready_not_wired` + the setup checklist (never "connected"). See §34/§36 |
| `POST /api/outputs/{hls\|mercury}/{start\|stop\|restart}` | a control edit through the SAME validated merge path as `PUT /api/wall` — start/stop flip `enabled`, restart bumps the monotonic `restart_epoch`. `404` on an unknown output/action |
| `GET /api/weather/radar/{region}` | an `image/gif` — the NWS RIDGE radar loop for a region, proxied + **cached 5 min per region** through the SSRF-safe fetcher; on an upstream failure serves the **last-good** frame marked `X-Radar-Stale`, else an honest `5xx`. Unknown region → `404`. See §33 |
| `GET`·`HEAD /api/stream/playlist.m3u8` | the HLS manifest the renderer writes (`application/vnd.apple.mpegurl`); `503` until the renderer has produced it; symlink/traversal-safe |
| `GET`·`HEAD /api/stream/{seg}.ts` | an HLS segment (`video/mp2t`); the name is regex-validated (`seg_NNNNN.ts`) and resolved symlink/traversal-safe |

The `/api/stream/*` routes mount only when `STREAM_DIR` is set; the **same hardened `stream_router`** is ALSO served over plain HTTP (`create_stream_app`, port 8082) so a strict tvOS VLC client opens one un-pinned URL (the API / `/app` / `/control` stay HTTPS-only). §27 covers the engine; §31 the encode envelope.

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
2. Fallback slugs (`c-span`, `nasa-tv`, `white-house-tv`, `newsmax`, `cnn-international`) — fill if preferred didn't resolve. (`c-span` + `cnn-international` were pruned server-side 2026-06-24 — no clean free source — so those two are now inert fallbacks the selector simply skips; the client list is unchanged, server-authoritative.)
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
- **Ticker markets/sports modes** — real ticker source plug-in. *Shipped in Stage 9 (see §16): real markets (Yahoo/CoinGecko) + ESPN sports with per-sport cards, mode rotation, and C3 SAMPLE/STALE honesty; the crawl is now framework-timed on both clients (§24).* The Stage 3 row is the single focus position the modes plug into.
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

The filter is a **pure step** in `FeedListBuilder.applyFilters(...)` (signature grew with the sports-leagues pool and, in §22, the genre level — `(items, hiddenSources, hiddenLeagues, recency, now, hiddenGenres)`), applied BEFORE `build()` orders the items — so the agnostic river, the per-item age, and the focus flat-index all operate on exactly the visible set. Controls live in the settings surface (`SettingsOverlay`): a "Feed recency" cycle row + (originally) a "Feed sources…" row opening `SourceFilterOverlay`. **As of §22 the flat source row is superseded by the two-level News filter** (a "News" section opening `NewsFilterOverlay`); both persist on-device via `LineupStore` (denylists as JSON string-sets, recency as an ordinal).

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

Built in Stage 12 (the feed-filtering chapter) as the source denylist; reused here (the ticker-news source set is the same `hiddenSources`). Not rebuilt. **Superseded for the feed UI in §22** by the two-level News genre/source filter (the genre level is added above this denylist; the ticker-news source subset still reads `hiddenSources`).

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

## 22. News genre groups — two-level feed-source filter (2026-06-23)

The flat "Feed sources…" toggle list grew a **genre level**: feed sources are now grouped into genres and gated at two levels, matching how both clients already group the *channel* picker and the web's feed-source filter. Native-only this release; the web-parity port is a conscious deferral (below). All of it stays a pure transform over the already-fetched plain-text items — **no new fetch, A1 holds.**

### The taxonomy (data-driven)

`FeedGenres` (in `ui.wall.feed`) maps each feed **source label → genre** — US News / Global News / Business / Sports — reusing the same names as `ChannelCategory` and the helper's `feeds/category.py`, so the channel picker, the web feed filter, and the native feed filter all read identically. The map is the single source of truth: **adding a feed source later is one line**; an unmapped label falls through to **General** (a newly-added source still groups, never silently drops, mirroring the helper's `source_category` default); **Weather is omitted** — no feed RSS source maps to it, and `sectioned()` drops empty genres so it never shows as a dead toggle. Pure + unit-tested (`FeedGenresTest`).

### The two-level filter + documented precedence

`FeedListBuilder.applyFilters(items, hiddenSources, hiddenLeagues, recency, now, hiddenGenres)` composes three denylist levels **top-down**, so they never conflict:

1. **Genre (`WallSettings.hiddenGenres`) — the master switch.** A genre switched off hides **all** its sources, overriding any per-source state below.
2. **Per-source, non-sports (`hiddenSources`).** Within a *shown* genre, a hidden source drops just that source (the original Stage 12 denylist, unchanged).
3. **Sports-leagues pool (`hiddenLeagues`).** The Sports genre's per-source level **is** the shared leagues pool — the same set the ticker scores and the standalone "Sports leagues…" filter use.

All three are denylists ("hide these"), so a newly-added genre/source shows by default. Pure + unit-tested (`FeedGenreFilterTest`: each level, the composition, the sports cap).

### Sports reconciliation (genre ⇄ leagues pool)

The Sports genre's source labels (NFL, NBA, …) **are** the leagues, so the News overlay's Sports section and the existing "Sports leagues…" filter are **two views of one state** (`hiddenLeagues`) — toggling a league in either writes the same denylist, matched case-insensitively, never double-stored. The precedence is **genre-then-pool**: the Sports *genre* toggle gates the whole Sports contribution to the feed; with the genre on, the leagues pool further filters individual leagues. This composes rather than forks — disabling a league still removes both its ticker scores and its feed news (Stage 12 behaviour), now under a genre master switch.

### Where it lives + focus

`NewsFilterOverlay` (native, in `ui.menu`) is a D-pad-navigable two-level toggle list — genre rows, each followed by its indented source rows — opened from a new Settings **"News"** section (superseding the flat "Feed sources…" row; the reused `SourceFilterOverlay` component remains for the leagues pool). It uses the menu's proven **`Column` + `verticalScroll` + bring-focused-into-view** scroll-follows-focus discipline, so every genre + source is reachable with **no fold-trap** on the overscan-clipped panel. Names render entirely from the `FeedGenres` data (not hardcoded in the UI); labels are inclusion-framed ("Shown" / per-source "Off" / "genre off" when the genre gates it). `WallScreen` builds the group model from the live feed's distinct sources + the persisted sets and routes toggles (genre → `hiddenGenres`; non-sports source → `hiddenSources`; sports source → `hiddenLeagues`). It's a modal overlay — `WallFocusModel`'s zone graph is unchanged, no-trap invariants intact. Persisted on-device via `LineupStore` (the denylist as a JSON string-set). Verified on-device (`.92`): Sports genre off drops all sports-news from the live feed (76 → 62 items) and survives a force-stop/relaunch.

### Deferred (BACKLOG)

- **Web-feed-filter parity** — porting the two-level genre grouping to the web client's feed-source filter is a conscious deferral, tied to the unresolved **cross-device profile-sharing** decision (per-device denylists vs a shared profile). Native ships first; the web keeps its existing per-category grouping until that fork is decided.

---

## 23. What this document deliberately does NOT specify yet

- Exact on-device persistence mechanism — chosen in Stage 5 (lineup/presets).
- Update mechanism details — chosen in Stage 6.
- ~~yt-dlp channel resolution (`channels.kind = 'youtube'`)~~ — **SHIPPED** (2026-06): migration 003 widened the CHECK and the resolver is **in-process** (`import yt_dlp`), NOT the predicted out-of-process sidecar. A second **free government-stream** resolver (`channels.kind = 'cspan'`, migration 004 — the Senate floor + Senate committee hearings via the senate.gov ISVP, token-free) also shipped. See §24.
- Operator-driven add/remove of channels and RSS sources via API — Stage 5 (settings UI). For Stage 2, `seed.json` is the operator-curated list; the helper upserts on every boot so edits flow in without a redeploy. **Per-deployment lineup override (2026-06-18):** a downstream self-hoster can customize the lineup WITHOUT editing the shipped `seed.json` — an OPTIONAL `lineup.local.json` in the writable data dir (gitignored) is reconciled on top of the shipped seed at boot (`channels/override.py`): **add** new channels, **disable** shipped slugs (`enabled=0`, excluded from the picker + prober), **override** a shipped channel's label/category/source_url/kind. Precedence: override field-merges win, disable beats override, an `add` colliding with a shipped slug is skipped; each effective entry is validated + probed like any channel (a bad entry is skipped + logged, never crashing the lineup), and orphaned adds are pruned so removing the override reverts cleanly. Category became storable (migration 005 — a nullable `category` column; `/api/channels` serves `category or category_of(slug)`, so NULL = the shipped taxonomy and **no override == identical to today**). This closed the last distribution make-or-break (the arc — runtime helper-URL, clean-clone compose, signed-APK publishing, per-deployment lineup — is complete).
- Buffer-sizing changes for naturally-bursty streams like dw-news-en — flagged in `docs/findings/02-player-state-machine.md §"Buffer-sizing trade-off"`, decision deferred to Part C under real 6-tile load.

---

## 24. Playlist / M3U endpoint + profile abstraction (2026-06-13 — the cross-platform-profiles fork, foundation)

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

## 25. Recent surface (2026-06): yt-dlp resolver, rebuilt web client, desktop executable

A cluster of work that post-dates the section structure above; the stage sections stay as the historical record, this is the current state.

**yt-dlp YouTube resolution (`channels.kind = 'youtube'`) — shipped, in-process.** Migration 003 widened the `kind` CHECK to admit `'youtube'`; the resolver lives in `helper/src/mymts_helper/channels/youtube_resolver.py` and `import yt_dlp` directly (a library call, **not** the once-predicted out-of-process sidecar). On the prober's cycle it resolves a YouTube `/live` URL to its HLS manifest, **gating on `is_live`** so a non-24/7 feed resolves to an honest offline rather than a dead URL; a thread-safe cache keyed off the manifest's own `expire` refreshes before expiry. The resolved manifest is then validated by the **same** SSRF-safe master/variant fetch as a direct-HLS channel — egress is the helper's normal (residential) path, never PIA. Lineup is now **52 channels** (22 direct-HLS + 28 YouTube-resolved + 2 free-government `cspan`; US-vantage — a 2026-06-22 quality pass pruned 4 persistently-dead HLS origins: `cnbc` (a `.invalid` placeholder), `al-jazeera-en`, `cgtn-en`, `trt-world`), across the news sections plus the 2026-06 ambient categories **Space / Nature / Cameras** (NASA ISS-HD, explore.org + Monterey Bay Aquarium nature cams, EarthCam / earthTV) — restoring the original wall vision. The **white-whale ocean + eagle cams (2026-06-21)** complete that vision: explore.org **Tropical Reef** + Homosassa **Manatee** (Ocean) and the **Decorah Eagles** nest (seasonal, honest-offline off-season) — all free official YouTube lives, `is_live`-gated, categorized Nature, surfaced via the server-authoritative **Ocean** + **Eagles** presets (both clients pick them up with no rebuild). Per-cam video IDs because explore's `/live` handle rotates; a dead/rotated ID honest-offlines, never fake-live. yt-dlp is pinned and needs periodic bumps (the desktop app self-updates it — below).

**Free government-stream resolution (`channels.kind = 'cspan'`) — shipped, in-process.** Migration 004 widened the `kind` CHECK to also admit `'cspan'`; the resolver (`channels/cspan_resolver.py`) sources C-SPAN's **free, no-login** government livestreams from the government's *own* players — never the entitlement-gated C-SPAN/C-SPAN2/C-SPAN3 networks (Adobe Pass / MVPD), which stay out of scope. It is a **session/filename-refresh** resolver (NOT a token resolver — the senate.gov ISVP feeds carry no Akamai token, no auth, no DRM), serving two modes off one Akamai master shape: the **U.S. Senate floor** (`floor_schedule.json` → `convenedSessionStream`) and **U.S. Senate committee hearings** (the first-party `hearings.xml` schedule → an aggregate tile that surfaces whichever committee is *genuinely* live — a finished hearing whose variant carries `#EXT-X-ENDLIST` is rejected as a frozen VOD, so an ended hearing is never shown live). Both are **honest-offline** when nothing is in session. The House floor + House committees + federal agencies (White House / State / Pentagon / DHS / DOJ) ride the YouTube resolver above. Together these generalize the gov-stream recipe — *read a .gov schedule/ISVP → build the open token-free Akamai master → gate on live* — into a reusable pattern; egress is the helper's normal residential path, never PIA, and every resolved manifest still passes the SSRF-safe master/variant fetch before a channel is marked live.

**Server-authoritative channel categories (2026-06-18).** Both clients now group the channel picker by the `category` the helper serves on `/api/channels` — the LAN web client always did, and the **native** picker now does too (`ChannelCategory.sectionedByCategory`, consuming `Channel.category`) instead of its old compiled slug→section map. This closes the drift where a channel absent from the native map (the gov feeds, the YouTube additions) fell to "General" on the TV: a channel added helper-side now sections correctly with **no app rebuild**, and an unrecognized server category (e.g. a future "Government" section) is shown (appended before General) rather than hidden. The compiled `ChannelCategory.BY_SLUG` survives only as a fallback for an older helper. Verified on-device (`.92`). The app's `versionCode` was also bumped off the stale pinned `1` to a version-derived `102` (0.1.2).

**Server-authoritative wall presets (2026-06-18).** Switchable channel-sets the operator applies to the grid — **News Wall / Nature / Space / Chill** — defined and served by the helper on `/api/presets` (`channels/presets.py`), so both clients render the *same* set and a new or edited preset flows with **no app rebuild** (the same pattern as categories above). Each preset carries an ordered slug list, a suggested grid, and a `fill` mode: **`topup`** (preferred + remaining playable — today's News behavior) or **`exact`** (only the listed slugs, curated). **Default = `news`, no regression:** that preset — and the value on a fresh install — routes through each client's *existing* default lineup unchanged (native `LineupSelector.forWall`, web `newsLineup`/`WEB_DEFAULT_LINEUP`), so the wall is identical to before presets existed until the operator switches. A preset's grid uses the existing grid levers only; the locked panel-fit levers are untouched. The native side menu's WALL section gains a **Preset** row → `PresetPickerOverlay` (the served presets, the active one marked); the web menu gains the parallel selector; applying one clears per-slot overrides, applies the grid, and persists the choice per-device (native `LineupStore.activePreset`, web `prefs.activePreset`). An **`exact` preset renders its listed channels honestly** — `LineupSelector.exactLineup` (web `presetLineup`) resolves the slugs against the *full* channel set with **no deny-list**, so an explicitly-chosen channel that's currently down (e.g. NASA TV in Space) keeps its slot as an honest C2 OFFLINE tile instead of vanishing (a preset is a *selection*, not a liveness claim); the ambient default wall keeps its deny-list (`nasa-tv`: master-only HLS) since the operator didn't choose it there. Both clients pin the presets `schema_version` (refuse an unknown contract). When the active preset isn't currently served (not yet fetched, or removed helper-side) the menu label and lineup both fall back to News, and the persisted id reactivates the moment the helper serves it again. Verified on-device (`.92`).

**Side-menu scroll (2026-06-19).** Adding the Preset row tipped the side menu's content past the panel height on larger grids, clipping the last WALL row — **Resync** — below the fold where D-pad-down couldn't reach it. `MenuOverlay`'s content is now a `weight(1f).verticalScroll` column with a pinned version footer; `focusable()`'s bring-into-view scrolls an off-screen focused row into view (the same mechanism `SettingsOverlay` already used for its capped list), so every CHANNELS + WALL row stays reachable + visible. Verified on-device (`.92`): D-pad-down scrolls Slot 1 off the top, Resync into view + focused + actuating the refetch.

**Rebuilt web client (2026-06-15..16).** The flat gear→`WALL SETTINGS` modal was replaced with a **native-style side menu** (the web analog of `MenuOverlay`): the gear opens a CHANNELS list (one row per slot) + WALL actions (Settings, Resync). Clicking a tile (or a menu channel row) opens **per-slot controls** (`SlotControlsOverlay` analog): Channel · Audio · Reconnect · Close. **Captions** were removed as a per-tile control and default **OFF** (these streams' captions are burned-in / unremovable; a wall-wide Settings toggle renders soft tracks where a manifest carries one). Feed-source toggles are **grouped by category** (the same taxonomy the channel picker uses, served by the helper). The web default grid is **2×3** (`DEFAULT_GRID_ROWS=2`/`COLS=3`), with a **draggable feed↔video divider**.

**Native perf (2026-06-16).** The audio renderer is disabled on inaudible tiles and at player creation (only the single audible tile decodes audio), relieving CPU on the Onn box. The ticker **crawl** is **framework-timed** (`Animatable<Float>` + `animateTo(LinearEasing)`) so its velocity stays constant regardless of frame load (replacing a clamped-per-frame-delta accumulator).

**Native perf — the CPU triad (2026-06-22).** Three avoidable wall-wide invalidations on the UI-thread-bound `.92` box, from a read-only adversarial pass. **(P-N3, the headline)** `VideoGrid` keyed its `StreamPlayerManager` on the tiles' resolved URLs — but `StreamSpec.url` is a channel's `current_url`, which rotates for a YouTube tile (a fresh manifest `expire` token), so one tile's refresh recreated the WHOLE manager (every player released + rebuilt — a full-grid decoder reinit — and the replaced manager's players LEAKED, since `Lifecycle.removeObserver` doesn't fire `onDestroy`). The manager now keys on the STABLE set of spec **ids**; a URL rotation is swapped into the one affected player IN PLACE (`StreamPlayer.updateUrl` → a new `MediaItem` on the same ExoPlayer + surface; the audio-off/captions-off track params + audible state + the honest-offline recovery ladder are preserved), and `releaseAll()` on dispose fixes the leak. The ambient presets are ~100% YouTube, so this removes a periodic full-grid reinit. **(P-N1)** the wall-dim alpha read the menu state into a composition-time `.alpha()`, recomposing the whole wall on every menu toggle; the read moved into a `graphicsLayer { alpha = … }` (draw phase) → no recomposition. **(P-N2)** `@Immutable` on `WallSettings` (Compose inferred it unstable via its `Set` fields) restores skipping on a settings nudge. **Helper (P-H1):** the resolvers now cache a deterministic honest-offline outcome for 10 min (under the ~30-min probe cadence, so live-flip latency is unchanged) instead of re-resolving every dark channel each cycle.

**Channel quality + Government section (2026-06-22).** A new server-authoritative **Government** category groups the session-gated official `.gov` feeds (chamber floors, committee hearings, agency briefings, the White House feed) out of US News (24 → 11 live), so US News reads live-dense and the gov feeds are honestly grouped (dark-when-not-in-session is expected for "Government"). Both clients pick it up server-authoritatively — native via `sectionedByCategory` (which appends an unrecognized server category alphabetically before General, no rebuild), the web via its `CHANNEL_CATEGORY_ORDER`. The **Space preset** dropped `nasa-tv` (DENY'd — its master-only HLS settles DEAD in ExoPlayer — which made the preset ~50% dead) → ISS-only (1×1); `nasa-tv` remains a picker channel. Verified on-device (`.92`).

**Weather pass + dead-news prune (2026-06-24).** The Weather category grew 2 → 4: **WeatherNation** re-added (its NAS-prober TLS handshake fixed — its Stirr CDN offers only an RSA-kx cipher Python's default omits, so the `fetcher` now enables that suite **without weakening cert verification**, SECLEVEL unchanged) and **WeatherSpy** added (free Rakuten/CloudFront FAST weather) — both validated `live` from the NAS prober. The two persistently-dead news channels `cnn-international` (Wurl/Rakuten host DNS-dead; not on any US FAST platform) and the branded `c-span` (cspan1 akamai now `http_403`; the three linear C-SPAN networks online are MVPD-gated) were **re-sourced and, finding no clean free source, pruned** — the token-FREE C-SPAN path remains the gov event streams (the `us-senate-*` `cspan` resolvers, unaffected). Lineup stays **52** (−2 dead HLS, +2 live weather HLS). Server-authoritative → both clients pick up with no rebuild.

**Desktop executable (`tools/desktop/`, released v0.1.1).** The helper packaged as a launchable desktop app (PyInstaller) so a user runs the **web wall** with no clone / Docker / Python: a **menu-bar / system-tray** app (macOS/Windows) that serves the helper on `http://127.0.0.1:PORT` (localhost — a browser secure context, no TLS) and opens the wall; **headless server mode** on Linux / no-display. It reuses the helper's `create_app()` (no new server logic), keeps the seeded DB in a per-user data dir (never the read-only bundle), and runs a **yt-dlp self-update** — fetch the latest from PyPI's official hosts over HTTPS, **SHA256-verify** against PyPI's digest, and prefer it over the frozen copy via a `meta_path` finder ahead of PyInstaller's `FrozenImporter`, with an offline/launch-safe fallback — so the YouTube channels stay current independent of app releases. Signing/notarization is wired but **gated on credentials** (unsigned builds are honestly marked + carry a right-click→Open note). A tag-triggered GitHub Actions matrix builds macOS/Windows/Linux and publishes a GitHub Release. The same `v*` tag also builds the **native Android APK** (`assembleRelease`) and — **gated on the Android keystore secrets** (the same present/absent gating as macOS notarization) — signs it (`apksigner verify`) and attaches `mymts-<version>.apk` to the Release; with no secrets it builds for validation only and attaches nothing (an unsigned APK won't install). The CI APK is a *stock, runtime-configurable* build (no baked helper URL → first-run setup). The gradle signing reads the keystore from `MYMTS_RELEASE_*` env in CI and from the gitignored `keystore.properties` locally (the operator's flow, unchanged). The bundle/APK are gitignored; only the tooling/workflows are committed. See `CHANGELOG.md` + `SECURITY-PRACTICES.md` (the two signing paths) + `docs/DOWNLOAD-AND-RUN.md`.

---

## 26. Headless container version — server-side wall config + picker control surface (2026-06-23)

The **headless NAS-container version** of the wall: instead of a person watching a browser, a headless renderer (next phase) will stream the rendered `/app/` wall to a generic player (VLC / Apple TV). A headless wall has **no device** to hold its lineup, so the wall's state moves **server-side**. First half (this section): the server-side wall config + a lightweight picker control surface that writes it + wiring `/app/` to render from it. Helper + web only — no native, no `.92`.

**Server-side wall config (`helper/.../wall/`).** A helper-hosted resource — the wall's **layout** (rows × cols) + **per-cell channel** + **per-cell subtitles** + a **single audible cell** — modelling the SAME controls as the native app (`LineupStore`'s per-slot channel, per-slot `captionsOnSlots`, single `audibleSlot`), not invented ones. *(Superseded: the single-audible pointer became **per-cell `audio`** — §35 — and the config later grew an `outputs` map + a render-resolution knob — §29/§34.)*
- `GET /api/wall` → the current config, or a server-computed **news-filled default** when none is stored (so `/app/` is never blank), tagged with `stored` (false = an un-customised default).
- `PUT /api/wall` → **validated** against the LIVE channel registry: a write can't name an unknown/disabled channel, send a `cells` array whose length ≠ `rows*cols`, exceed the 1–3 grid bounds, or make an **empty** cell the audio source (the single-audible model). Validation is pure + unit-tested in `wall/store.py`; the router maps a failure to a 422 with a field-specific reason.
- **Persisted** as `wall.local.json` in the data dir (seedless, gitignored — runtime operator state, like `lineup.local.json`); atomic temp-write+rename; a bad/absent file degrades to the default so the wall always renders.

**Picker control surface (`web/control/`, served at `/control`).** The wall's layout in a browser, but **each cell is a feed-PICKER, not a video player** — a category-grouped channel dropdown + a per-cell single-audible audio toggle + a per-cell subtitle toggle, plus grid rows×cols + preset selectors. **No video decode** — it's lightweight (runs on a phone/laptop). Every change PUTs the wall config (surfacing the helper's validation reason on rejection). Mounted at its own `/control` prefix; it shares the bundled pure JS at `/app/js/*` via absolute imports. The pure config transforms (`web/js/wallConfig.mjs` — resize-by-index, single-audible toggle, layout reconciliation, preset fill) are shared with `/app/` and unit-tested, mirroring the server validator so the two never disagree.

**`/app/` renders FROM the config.** The existing web wall now reads the wall config and, when it is **stored**, treats it as authoritative for the grid (layout + per-cell channel + the audible cell + per-cell subtitles, replacing the old wall-wide caption application). It **polls** the config, so a pick in `/control/` appears on the wall within seconds; `/app/`'s own grid edits PUT the config too, so the two surfaces stay one coherent state (a short post-edit grace window prevents a poll mid-PUT from reverting a local change). An un-customised default leaves `/app/` standalone (its news-autofill), so a fresh wall is unchanged. Verified end-to-end in a real browser (a `/control/` feed + audio + subtitle + layout change persist and the `/app/` grid reflects them) and on the deployed NAS container.

**Helper-hosted state by necessity — and the profile decision, resolved for this context.** A headless wall *has* no device, so its state *must* live helper-side; this is the natural home for the headless version. The native app stays device-local (its on-device `LineupStore`), so there is **no conflict** with the deferred cross-device-profile question (§24, BACKLOG item H) — the headless context resolves it server-side **out of necessity**, without prejudging the native sync decision. The **render → HLS → VLC** engine that streams this rendered wall to a generic player is §27.

---

## 27. Headless container version — render → HLS → VLC engine (2026-06-24)

The half that puts the wall on the TV. A new **renderer container** (`mymts-renderer`, `renderer/`) runs the config-driven web wall on a virtual display, captures the composited video + the audible cell's audio, encodes to **HLS**, and the helper serves one URL a generic player (VLC on an Apple TV) opens. Because `/app/` already live-polls `/api/wall` (§26), a `/control/` pick is reflected in the stream with **NO restart**.

**The engine (`renderer/run.py` + a pinned Debian image).** `Xvfb` (a 1080p virtual display) → **Chromium** (kiosk, `/app/?render=1`) → ffmpeg (`x11grab` video + a **PulseAudio null-sink `.monitor`** for the audible cell's audio) → HLS. **Xvfb + a real Chromium window — not `--headless`**, which black-tiles in-container video; verified on-device the tiles genuinely play (software decode, `--disable-gpu` compositing into the framebuffer x11grab captures). Chromium runs `--no-sandbox` (it only ever loads OUR OWN same-origin `/app/`) + `--ignore-certificate-errors` (the helper's self-signed LAN cert) + `--autoplay-policy=no-user-gesture-required` (so the config's audible cell unmutes with no gesture). Render mode (`?render=1`) hides the gear/cursor so the capture shows only the wall.

**Serve path.** The renderer writes `playlist.m3u8` + rolling `seg_NNNNN.ts` to a **shared `mymts-stream` volume** (renderer rw, helper **read-only**); the helper serves them at **`/api/stream/playlist.m3u8`** (correct HLS media types — `application/vnd.apple.mpegurl` / `video/mp2t` — no-store, an honest 503 while the renderer boots, a strict `seg_<n>.ts` match so no request escapes the dir; **GET + HEAD** so a strict player's HEAD-probe gets 200, not a 405).

**Plain HTTP for the stream (2026-06-25 — the Apple-TV fix).** VLC on **Apple TV** hung forever at "please wait" on the HTTPS stream URL though desktop VLC played it. Diagnosed on the live NAS: the helper's cert is **self-signed**, and a strict tvOS client hangs (rather than prompting) on it — and the `.ts` segments ride the same HTTPS, so they'd stall too. A LAN video stream needs no TLS, so the entry point ALSO serves the HLS over **plain HTTP** on a dedicated LAN port (**`8082`**) via a SEPARATE minimal app (`create_stream_app`) exposing **only** the hardened `/api/stream` route — the **API + `/control/` + `/app/` stay HTTPS-only**. It REUSES the same symlink-safe `stream_router` (one hardened serving path; the path-traversal hardening is retained, verified: a planted symlink → the TLS key still 404s on the HTTP path too). The Apple-TV URL is **`http://<nas>:8082/api/stream/playlist.m3u8`** (no cert to accept). A proper LAN cert / HTTPS-everywhere is a future alternative (BACKLOG).

**Supervision + health.** A supervisor (`run.py`, pure policy + tests in `supervisor.py`) starts Pulse + Xvfb once, then restarts Chromium/ffmpeg with backoff and escalates a crash-loop to a stack restart. The docker HEALTHCHECK gates on **fresh segments** (not mere liveness). And because ffmpeg/Chromium can **HANG** — still running, the stream no longer advancing ("frames duplicated" + a blocked pulse queue), which `poll()` never catches and which strands a player at "please wait" — the supervise loop also **self-heals a wedged stream**: once the stream has been healthy, if it goes stale past a threshold (~30s) while the processes are alive, it restarts the stack (`restart: unless-stopped`) instead of streaming a frozen frame forever. (`stale_stack_restart`, unit-tested; never fires during startup or while healthy.)

**Resource posture (CPU-only v1).** Software x264 + N tile decoders is the heavy part — the cell count drives the load (the same concurrent-decoder reality as the web client). The renderer is bounded by the compose **cpus/mem caps** (6 cpu / 4 GiB after the 4K-headroom bump — §29; observed ~3 cores at the 1080p default, ~4.3 at 4K) so it can't starve the NAS; `-thread_queue_size` buffers a transient spike rather than dropping frames. **GPU passthrough is a documented future optimization (BACKLOG), not now.**

**Isolation / posture.** The renderer shares the helper's **`mymts-net`** only, so its egress (the helper + the public HLS the tiles play) takes the **same residential WAN path as the helper — never a VPN** (0 VPN refs in its compose, verified); it never touches the helper's data/cert volumes or any other container. Non-root (uid 10002), `cap_drop ALL`, `no-new-privileges`, tmpfs-confined writable paths, no baked secrets. Verified on the deployed NAS container: valid advancing HLS (h264 1080p + AAC), the wall renders with live tiles + feed + ticker, and a `/control/` change (a 1×1 single-channel wall) reflected in the stream within the poll interval with no restart. The operator's one manual check is opening the URL in VLC on the Apple TV.

## 28. Headless wall — tile playback reliability (hls.js-first, indefinite self-heal, /control/ force-reload) (2026-06-25)

The §27 engine put the wall on the TV; this is the chapter that made the **tiles actually play** there.

**Root cause — confirmed on the live NAS, not assumed.** On the rendered wall, most tiles showed the honest **"Browser can't play this source — on the TV wall"**; only BBC played. The candidates (LiveNOW from FOX, Fox Weather, AccuWeather NOW, Newsmax, CBS Sports HQ) were ALL `kind=hls` / `status=live` / `browser_playable=true` — the *same* path as BBC; their CDNs are **CORS-clean end-to-end** (manifest + media playlist + `.ts` segment each return `Access-Control-Allow-Origin`) and standard **H.264/AAC**. So the failure was **not** CORS, codecs, mixed-content, or a render-mode/IFrame fallback. The string `"Browser can't play this source"` is reason `unsupported-source`, which `classifyVideoFailure` emits **only** for a native `<video>` `MEDIA_ERR_SRC_NOT_SUPPORTED` — i.e. the player took the **native-HLS branch**, which fires when `videoEl.canPlayType("application/vnd.apple.mpegurl")` is truthy. Probing the renderer's Chromium directly: **`Chromium 149` (Debian bookworm) returns `"maybe"` there** (stock desktop Chrome returns `""`), but its half-baked native HLS pipeline can't actually play most live FAST/CDN streams (it plays simple ones like BBC, fails the rest). hls.js + MSE is fully supported in that build (`MediaSource.isTypeSupported(...) === true`) and plays them all. **A renderer-build quirk surfaced through the web client's branch order** — it failed only in the container, never in desktop Chrome.

**Fix — prefer hls.js whenever it's supported (`web/js/video.mjs`).** The branch order is flipped to the hls.js project's own recommended order: `Hls.isSupported()` **first** (Chrome / Chromium / Firefox / Edge / desktop Safari — every MSE engine), and native HLS reserved as the fallback for the one engine with **no MSE for hls.js to use** (iOS Safari). The Chromium "maybe"-but-can't-actually-play trap is sidestepped entirely. Pure web-client change — **no renderer rebuild**; the renderer just reloads its `/app/` page to pick up the new JS. Verified on the live stream: **all six tiles play** (the 5 previously-dead FAST channels + BBC).

**Indefinite per-tile self-heal (`render.mjs` policy).** An *unattended* wall has no operator to press ↻, so a transient drop must heal itself forever. `videoRetryDecision` no longer "exhausts": a **retryable** failure (network / decode / stall / native-transient) re-resolves a fresh URL and reconnects on a **capped exponential backoff** — `videoBackoffMs`: quick early retries (2→4→8→16→32s) then settling at a ~45s cap — **indefinitely**. A **genuinely-unplayable** failure (DRM / codec / no-HLS / unsupported-source) still NEVER auto-retries; it rests at the honest "on the TV wall" state. The retry/give-up split stays pure + unit-tested (`classifyVideoFailure` + `videoBackoffMs` + `videoRetryDecision`). This replaced the old fixed-15s / ~3-min / ~12-attempt window that left a dead tile after the window. **Honest-offline is preserved** — a stream the browser fundamentally can't play is never faked-live nor looped forever.

**Force-reload from `/control/` (monotonic reload epochs).** A control surface can now make the rendered wall re-attach a tile's player with NO channel change, via two **monotonic counters** added to the wall config (additive to schema v1, default 0, validated `>= 0` in `wall/store.py`): wall-level **`reload_epoch`** and per-cell **`reload`**. `/app/` already polls `/api/wall` (§26); `applyReloadSignal` compares each value against the last-seen baseline and reattaches the affected tile(s) **only on an INCREASE** (loading a config never self-triggers; a just-rebuilt grid only adopts the baseline). The helper **clamps the counters monotonically on every write** (`store.clamp_reload_monotonic` in `PUT /api/wall`) — so a write can never rewind a counter (which would make a later `/control/` reload look like it went backwards → missed). (An `/app/`-side echo + baseline-seed that *also* defended this — so an `/app/` edit that omitted the counters wouldn't reset them — were RETIRED once the wall-config write became a **partial-merge** that preserves any omitted field; see §30. The monotonic clamp stays — it's a separate invariant.) `/control/` gains a **"↻ Reload all tiles"** button (bumps `reload_epoch` → reattach every tile) and a per-cell **"↻ Reload"** pill (bumps that cell's `reload` → reattach just it). The pure transforms (`withWallReload` / `withCellReload` + preservation through `normalizeConfig` / `resizeCells` / `withPreset`) are unit-tested both sides (`wallConfig.test.mjs` + `test_wall.py`). Verified live: a per-cell bump reattached that tile (fresh re-resolved content) while the rest of the wall kept playing.

**Why this is web + helper only.** The playback bug was in the web client's path selection (a renderer-build quirk it tripped over), so the fix is web-client JS; the helper merely carries the new config fields. No native app change → no APK; the renderer **image** is unchanged (only the `/app/` JS it serves, picked up by a renderer page reload). PIA / other containers untouched; the symlink-safe stream serving is unchanged.

## 29. Headless wall — responsive scale + normalized layout + configurable resolution (1080p / 4K) (2026-06-25)

The presentation pass on the rendered wall (web `/app/` + the renderer canvas), run AFTER the playback fix so the layout is tuned against a wall that's actually full of playing tiles.

**Responsive scale — one unit, no hardcoded px.** The wall CSS was almost entirely fixed px, which would render at half the relative size on a 4K canvas (tiny text). The whole wall now scales off a single unit. `app.mjs` sets a CSS variable `--ux` (device-px per 1080p design-px) from the live viewport via the pure, unit-tested `computeUx(w, h) = min(w/1920, h/1080)` (in `render.mjs`); `styles.css` defines `--u: calc(1px * var(--ux))` and expresses every fixed dimension as `calc(N * var(--u))`. The renderer's Chromium viewport IS its Xvfb screen, so `--ux` auto-tracks the render resolution with **no knowledge of the resolution** — the CSS can never desync from the framebuffer. At 1080p `--ux=1` (identical to the prior px); at 4K `--ux=2` (everything doubles). Because both targets are exactly 16:9 with heights that are multiples of 1080, `--ux` is an exact integer (no sub-pixel drift). **Crisp at 4K, not upscaled:** the chain stays device-scale-factor 1 (Xvfb `{W}x{H}` = Chromium `--window-size` + `--force-device-scale-factor=1` = ffmpeg `-video_size {W}x{H}`, no `-vf scale`), so a `calc(11*var(--u))` label is *laid out* at 22px and the glyph is rasterized at 22px into the 4K framebuffer. Only each tile's `<video>` is bounded by its upstream HLS bitrate (honest — a 720p feed is 720p on any canvas); the chrome is genuinely sharper.

This was chosen over two alternatives via an adversarial design panel: a *rem-as-design-px* approach (`html{font-size:calc(100vh/1080)}` → `1rem≈1px`) is terser but bakes in an inherited-tiny-font trap (any future `font-size` literal that forgets `rem` renders at ~1px); CSS `zoom` is a one-liner but multiplies the wall's `vw/vh` modal bounds and overflows them. The `--u` custom-property unit is immune to both (it is `calc(1px * number)`, never relative to font-size, and leaves `vw/vh` alone), at the cost of one tested pure function. Hairline `1px` borders, the modal `vw/vh` bounds, `%`/`fr` layout, and unitless line-heights are deliberately left literal. The renderer also passes `--blink-settings=minimumFontSize=0` so Chromium can never clamp a small label *up* and break the scale.

**Normalized layout (one gutter rhythm).** A single `--gap` token (`calc(8 * var(--u))`) binds the composition: the inter-tile gutter == the grid's outer inset == every corner-overlay inset (label / dot / audio / change), so the tiles read as one even mosaic with consistent spacing against the feed. Uniform `object-fit: contain` (letterbox onto `#000`) is the single deliberate fit policy — these are mixed-aspect/SD news feeds, so `cover` would crop a chyron / score-bar / lower-third off-frame (dishonest for a news wall); `contain` shows the whole frame and the uniformity + `--gap` (not cropping) is what makes the grid cohesive. One uniform small per-tile channel caption.

**Configurable render resolution.** *(Superseded by §34: `render.resolution` is retired — render resolution is now DERIVED from the per-output resolutions; an old stored value migrates into `outputs.hls.resolution` on load.)* The wall config gains an additive `render.resolution` (at this stage a 2-option `"1080p"` | `"2160p"`; **§32 later expanded this to an 8-rung 16:9 ladder**, default 1080p, validated against a closed enum in `wall/store.py`; carried through every `wallConfig.mjs` transform incl. `normalizeConfig` so a `/control/` save can SET it, and **preserved on an `/app/` write by the server-side partial-merge** — see §30 — which retired the per-field `/app/` echo + helper carry-forward that originally defended it). `/control/` has a resolution selector. The renderer (`run.py`) reads `render.resolution` from `/api/wall` at startup (reusing the helper's no-verify TLS), maps it via the pure `supervisor.resolution_to_dimensions` / `resolution_to_bitrate` to the Xvfb screen + ffmpeg `-video_size` + bitrate (1080p→8M/16M, 4K→20M/40M — sized for the wall's motion + real-time encode, see §31), and **restarts the container stack on a resolution change** — the canvas is a process-start param for Xvfb/Chromium/ffmpeg, so the change reuses the same self-heal restart path as an Xvfb death (`config > env > default`; an unreachable helper falls back to the running dims, so a blip never restarts).

**4K is opt-in and bounded — the OOM finding.** Verified on the live NAS: 4K software x264 at ~4× the pixels fell behind on the encoder, and the deep `-thread_queue_size 1024` x11grab buffer (a 4K raw frame is ~33MB) ballooned and **OOM-killed ffmpeg (`rc=-9`)** under the original `mem_limit: 2g` → crash-loop. Fixed two ways: (1) the x11grab queue is now **resolution-bound** (`supervisor.grab_queue_size`: deep at 1080p, shallow at 4K — at this stage a width gate, **§32 later generalized it to a per-rung raw-frame *memory* bound** so every ladder rung is OOM-safe, not just 4K) so a behind-the-encoder high-res stream **drops frames within the mem_limit** (the stream still advances) instead of OOM-ballooning; (2) the renderer's own compose ceilings get 4K headroom (`cpus 4→6`, `mem_limit 2g→4g`, `shm 256m→512m`, `tmpfs /tmp 768m→1g`) — still hard ceilings on the multi-core NAS, so 4K can never starve the host or PIA. **1080p stays the default** (uses ~2 cores / ~1g, well under the caps); 4K is a deliberate `/control/` choice and a weak NAS can fall back to `RENDER_FPS=24`. Switching resolution restarts the renderer (a brief stream blip), documented in the compose + the `/control/` selector tooltip.

## 30. Wall-config write contract — server-side partial-merge (PATCH) (2026-06-26)

The wall config (§26) accreted additive fields — `reload_epoch`, per-cell `reload`, `render` (§28–29). Each one was silently REVERTED by an `/app/`-side write that didn't know to echo it (`/app/`'s `buildWallConfig` hand-builds the PUT body), and each was patched field-by-field with a client echo + a helper carry-forward. That is whack-a-mole on a **class**: the next field added would bite a fourth time. This section is the structural fix that killed the class.

**`PUT /api/wall` is a partial-merge (PATCH), not a replace.** `store.merge_wall_config(stored, incoming)` merges the incoming fields onto the stored config: a field PRESENT in the body overrides; a field OMITTED is PRESERVED from the stored config. Two levels, so no client can clobber a field it didn't send at either level: top-level fields, and `cells` (when present) merged **by index** (`_merge_cells` — each incoming cell's provided fields override the stored cell at that index, omitted per-cell fields preserved; the incoming length wins, a resize). Semantics: **absent = preserve, explicit `null` = set** (so `audible_cell: null` still mutes, `channel: null` still clears a cell — both are present keys). The handler is `load existing → merge → validate(merged) → clamp_reload_monotonic → save`: validation runs on the MERGED result, so an inconsistent merge (e.g. a `layout` change with no matching `cells`) is still rejected by the normal `rows*cols` rule (the layout↔cells coupling means a grid resize must send both — which the `wallConfig.mjs` transforms do).

**Why this is the right shape.** Any field a client doesn't manage is now safe BY DEFAULT — a future config field needs zero per-field defence. `/app/` sends only what it owns (layout, preset, audible_cell, cells' channel+subtitles); the merge preserves `reload_epoch` / `render` / per-cell `reload` / anything else. The per-field scaffolding was retired: the helper's `render` carry-forward special-case is gone, and `/app/`'s `lastRenderResolution`, the `buildWallConfig` echoes, and `seedReloadBaseline` are gone. **Kept, deliberately:** `clamp_reload_monotonic` (a *separate* invariant — counters never rewind, which the merge does NOT provide: an explicit lower value would merge through, so the clamp still guards it), and `wallConfig.mjs`'s `normalizeConfig` / `withResolution` (so `/control/` can still SET render/reload). The reload-DETECTION path (`applyReloadSignal`, §28) is unchanged; its baseline now comes from its own first-hydrate branch.

**The invariant test that keeps the class dead.** `test_partial_write_preserves_EVERY_omitted_field` establishes a fully-customized wall, then for EVERY top-level field PUTs a body omitting only that field and asserts it survives — **iterated over the config's own keys**, so a newly-added field is automatically covered (plus a per-cell variant and the merge unit tests). Verified live: an `/app/` edit omitting `render` / `reload_epoch` / per-cell `reload` preserved all three (incl. a non-default 4K `render`); `/control/` still applied a change; a `reload_epoch` rewind clamped. Helper + web only; no native change.

## 31. Renderer — real-time encode + the sustainable-resolution envelope (2026-06-26)

The §27 pipeline streamed the wall; this is the chapter that made the **motion smooth** and characterized **what resolution the CPU-only renderer can actually sustain**.

**Diagnosis (stage by stage, measured on the live NAS).** The chain is: Chromium software-renders the wall into the Xvfb framebuffer → `x11grab` samples it at FPS → `libx264` encodes → HLS. The choppiness was localized by measuring the **unique-frame rate** at each stage (an `mpdecimate` count — how often the content actually changes) at 1080p and at 4K. At **1080p**: the browser renders ~30fps, the encoder takes ~1.1 cores (container ~3.1 of 6), and the HLS output is **30fps CFR with every frame unique** — already smooth (the operator's choppiness was the wall being set to 4K). At **4K**: the browser renders only **~10fps** while the encoder keeps up by duplicating to 30fps — so the bottleneck is the **GPU-less software compositing of the 4K canvas, not the encoder**. The output is 30fps CFR but ~10fps of unique content → judder, on both the video tiles and the continuous ticker crawl.

**Real-time encode tuning (`run.ffmpeg_cmd`).** `-tune zerolatency` runs x264 in a low-latency profile (no B-frames, no rc/sync lookahead, sliced threads) so the encoder never buffers/falls-behind the live capture. **CFR end-to-end** (`-fps_mode cfr` + `-r FPS`) paces the OUTPUT to exactly FPS by duplicating/dropping, so the encoded motion is evenly timed — no judder from grab/encode rate jitter. The bitrate is sized for the wall's motion (a full-width ticker + N tiles) and to offset zerolatency's lower compression efficiency (1080p 8M/16M, 4K 20M/40M, in `supervisor.RENDER_BITRATES`). The grab queue (`grab_queue_size`) is unchanged here and later **generalized to a per-rung memory bound in §32**: a low-res canvas keeps a deep multi-second buffer and the depth scales down with the canvas (≤1 GiB worst-case at any rung), so a sustainable resolution is never needlessly frame-dropped while no rung can OOM. The real-time flags are regression-guarded in `test_supervisor.py` (assert `ffmpeg_cmd` carries `-tune zerolatency` / `-fps_mode cfr` / the FPS rate). Verified: 1080p output is 300/300 unique frames over 10s, CFR 30, no dup/drop warnings.

**The sustainable envelope (this NAS, no GPU).** Smoothness is **render-bound** (the encoder is not the limit): the software render TIME per frame grows roughly **linearly with canvas pixels plus a fixed per-frame floor**, so fps falls **sublinearly** — 4× the pixels (1080p→4K) cost only ~3× the time (measured: 30→10 fps), not 4×. The two **measured** anchors (1080p, 4K) define an affine render-time fit; the 1440p/1800p rows below are **interpolated** on it (not measured):

| Resolution | Canvas | Browser render | Verdict |
|---|---|---|---|
| 1080p (1920×1080) | 2.07 Mpx | ~30 fps *(measured)* | **smooth** |
| 1440p (2560×1440) | 3.69 Mpx | ~20 fps *(interpolated)* | marginal |
| 1800p (3200×1800) | 5.76 Mpx | ~14 fps *(interpolated)* | choppy |
| 2160p / 4K (3840×2160) | 8.29 Mpx | ~10 fps *(measured)* | **not smooth** |

**1080p is the smooth ceiling.** No encode setting lifts a resolution above it into smoothness — the **dominant lever is GPU passthrough** (VA-API hardware compositing + decode), which would offload the per-pixel work the CPU can't sustain at high resolution. (Reducing the tile count is a secondary lever — fewer tiles to decode + composite — but the operator saw choppiness even at 2×2, so the canvas resolution, not the cell count, dominates here; the measurement didn't separately isolate composite from decode cost.) GPU passthrough is a separate, bigger lift (device passthrough on the NAS, never disturbing the helper / other containers / PIA) and is named in BACKLOG, not papered over. This envelope is the honest input to the resolution-freedom pass: offer ≤1080p as smooth, and annotate higher resolutions as heavy / GPU-territory rather than presenting 4K as a smooth option the hardware can't hold.

**GPU lever — hardware-checked, blocked at the VM boundary (2026-06-27).** A follow-up went to wire VA-API at the container level and FIRST checked the prerequisite — a usable GPU exposed to the renderer's VM. There is none: the renderer runs in a **KVM VM** whose only graphics device is the **QXL paravirtual VGA** (`[1b36:0100]`), which has **no DRM render node** (`/dev/dri` carries only `card0`; `renderD128` is absent → no VA-API), and the renderer container sees no `/dev/dri` at all; the host CPU (Threadripper 1920X) has no iGPU and no discrete card is passed through. So VA-API **can't be wired in the container/image** — there's no render node to pass in — and the smooth-ceiling envelope above **stands unchanged**. Lifting it needs a **host-level** step that is explicitly NOT done here (operator's manual hardware/hypervisor work): PCIe-pass-through a discrete GPU to the Ubuntu VM at the TrueNAS host, or add a GPU to the NAS. The check was read-only; nothing at the host / VM / hypervisor / compose / image was touched, and the software pipeline (and its hard caps) is unchanged. See BACKLOG for the unblock recipe.

## 32. Headless wall — finely-tunable resolution + feed + ticker (2026-06-26)

The wall's presentation is now operator-tunable from `/control/`, every adjustment a fine-grained, gradual slider, persisted in the wall config (the §30 partial-merge makes each new field safe by construction — `/app/` never clobbers a field it doesn't send, and the generalized invariant test auto-covers it).

**Resolution freedom — an 8-rung ladder with honest, per-rung fps.** The 2-option (1080p/4K) choice becomes a 16:9 ladder — 720p / 900p / 1080p / 1260p / 1440p / 1620p / 1800p / 2160p — on a stepped `/control/` slider. The renderer (`supervisor.RENDER_RESOLUTIONS` / `RENDER_FPS` / `RENDER_BITRATES`) maps each rung to its canvas, a **sustainable per-resolution constant frame rate**, and a bitrate; `run.py` reassigns `WIDTH/HEIGHT/FPS/bitrate` from the config at startup and restarts the stack on a change. The new intermediate rungs also **generalized the §29 4K OOM guard**: `grab_queue_size(w, h)` now bounds the x11grab queue by raw-frame *memory* (`min(1024, max(32, ~1 GiB / (w·h·4)))` → ~129 frames at 1080p down to 32 at 4K), so every rung's worst-case raw-frame buffer stays ≤1 GiB rather than only 4K getting the shallow guard (a rung between 1080p and 4K would otherwise have kept the deep 1024-frame queue and reopened the OOM). The per-rung fps is the key honesty: from the §31 envelope, the GPU-less browser can't render 30fps above ~1080p, so each high-res rung targets the fps it CAN sustain (1260p→24, 1440p→20, 1620p→16, 1800p→14, 2160p→10) — a *consistent* lower fps beats a juddery 30 (which drops the frames the browser can't draw). The `/control/` slider annotates each rung with its fps + smoothness zone (≤1080p **smooth**, 1260p/1440p **marginal**, ≥1620p **heavy** / GPU-territory) from `wallConfig.mjs RESOLUTION_INFO`, so the operator's choice is informed rather than presented as "all smooth." **1080p stays the default.** The web wall needs no resolution knowledge — it scales to any rung via `--ux` (§29).

**Feed width / feed font / ticker height — proportional, within `--ux`.** Three additive config fields: `feed_pct` (18–58 % of the wall), `feed_font` (0.7–1.6× the feed text), and `ticker_scale` (0.6–2.0× the ticker). The helper **clamps** these continuous knobs on read (a boundary value lands sane, not rejected — unlike the strict resolution enum + reload counters). The web applies them proportionally: `--feed-pct` (a %) sizes the feed column (the grid reflows into the remainder); `--feed-font` scales the feed text within `--ux`; and **`--ticker-scale` drives a ticker unit `--tu = calc(var(--u) * var(--ticker-scale))`** that every ticker dimension (bar height, gaps, paddings, AND the card text) is authored against — so a taller ticker has bigger content, not a tall empty bar with the same tiny text. The **rendered wall** (`?render=1`) applies the config values; the laptop `/app/` keeps its own local feed-width/font sliders (a personal view), so `/control/` tunes the TV output without fighting a laptop drag. Every control is a small-step slider (resolution step = one rung; feed width step 1; font/ticker step 0.05) — gradual by construction, never a radical jump.

Verified live: 1440p streams at 2560×1440 @ a steady 20fps; a single partial PUT set 1440p + a 45 % feed + 1.3× feed font + 1.6× ticker while the merge preserved the layout + channels; the ticker's content (cards, scores, league markers) scaled up *with* its height. Web + helper + renderer; no native change. A free-form custom W×H (with its sustainable fps derived from the §31 render-time fit) is a natural follow-on (BACKLOG); the closed 16:9 ladder keeps `--ux` an exact ratio and every rung's fps measured/modelled.

## 33. Weather radar — a selectable per-cell widget source (free public NWS) (2026-06-27)

A wall cell can be set to **Weather Radar** instead of a video feed: it renders an animated NWS radar loop for a region. The design choice that keeps it small: radar is a **pseudo-channel**, not a new config concept. A cell stores a `weather-radar-<region>` slug in the **existing** per-cell `channel` field (the region is encoded in the slug), so radar rides the existing picker, the per-cell `channel` validation, and the §30 partial-merge with **no new wall-config field** — the wall schema is untouched. The single source of truth for the region set is `helper/weather/regions.py`.

**Source — the free public NWS RIDGE loop.** `radar.weather.gov/ridge/standard/{SITE}_loop.gif` is a 10-frame GIF89a NWS assembles server-side and refreshes ~every 5 min; `CONUS` is national, a `KXXX` WSR-88D id is a local site (KILX = Lincoln IL / central Illinois). Keyless, DRM-free, no ToS gate — verified live (HTTP 200 + `image/gif`) before shipping. CONUS-LARGE (3400×1600, ~6.5 MB) is deliberately excluded as too heavy for a tile.

**Helper proxy + cache (`/api/weather/radar/{region}`).** The endpoint maps a whitelisted region → its NWS site, fetches the loop through the **SSRF-safe `fetcher`** (https-only, private-IP block, the app's active resolver — so phantom mode blocks egress), and serves the bytes same-origin so the wall `<img>` is CORS-clean. A per-region **`RadarFrameCache` (5-min TTL, matching the scan cadence)** means **NWS is hit at most once per region per TTL** regardless of how many tiles request it; the wall's `?t=` refresh cache-busts only the *browser* cache, not the region-keyed helper cache, so it never re-hits NWS per refresh. The site id is re-validated against a strict regex before URL interpolation (defense-in-depth; user input can't reach the fetched URL). **Honest fallback:** on an upstream failure the endpoint serves the **last good frame marked `X-Radar-Stale`** if it has one, else an honest 5xx; never a blank or a synthesized frame.

**Picker integration (web-only).** Radar regions are surfaced as synthetic `/api/channels` rows **only when the web client asks** (`?widgets=1`); the native TV picker fetches without the param and its channel list is **byte-identical to before** — radar must not offer a non-playable entry on the TV. The rows carry `category: "Weather Radar"` (a new picker section right after Weather, mirrored in the helper `CATEGORY_ORDER` and the web `CHANNEL_CATEGORY_ORDER`), `kind: "weather-radar"`, and `current_url` = the proxy path (the wall reads it as the image src — parity with a video channel's HLS url). The picker labels radar **"radar loop"** (not the video "live · plays in browser" claim): the row asserts *availability*, and the real freshness is the tile's runtime `<img>` state. Radar is an **explicit pick** — the `isRadarSlug` guard keeps it out of every default / auto-filled lineup (`newsLineup`, preset top-up), matching native, which never auto-fills it.

**Render.** `renderCell` branches on `ch.kind === "weather-radar"` *before* the video path → `renderRadarCell`: an `<img>` (Chromium animates the GIF natively; x11grab captures the composed frames — **no video decode**, so it doesn't tax the §31 software-render bottleneck), captioned `Weather Radar — <region>` via the same `.tile-label`, fit with the same `object-fit: contain` policy, refreshed on a ~5-min timer. It self-heals (a fast retry until the first frame, then the steady cadence) and is honest: a frame that never loads shows an "unavailable" state, while a later refresh failure **keeps the last good frame** on screen. The cell carries no audio/captions (its `streamHandle` is null, so every audio/caption path skips it).

**Scope.** Radar is a **web-wall** widget (the headless renderer captures `/app/`). A native client that reads a stored radar cell can't resolve the slug (it never lists radar) and **gracefully falls back to its default cycler** for that cell — the same honest degradation it already does for any unknown slug, not a crash; the native app is untouched. Helper + web only; PIA / other containers untouched. Per-region status tracking (so the picker could show a probed live/offline dot like video channels) and a current-conditions data panel are natural follow-ons (BACKLOG).

## 34. Unified wall-output system — capture-once fan-out + derived render resolution (2026-06-27)

One rendered wall now drives **multiple outputs**. The wall config grows a top-level **`outputs` map** — `hls` (the shipping VLC stream) + `mercury` (§36) — each with its own `enabled` / `resolution` (the §32 ladder) / `bitrate_kbps` (fine, clamped per-resolution) / `audio` / `restart_epoch`. The map is built to take more outputs with **no migration** (add a defaults entry + a validator — no `schema_version` bump). The single `render.resolution` (§29) is **superseded**: render resolution is now **DERIVED** = `max(resolution of the enabled outputs)`; a load-time migration seeds `outputs.hls.resolution` from an old stored `render.resolution`.

**Capture once, encode N (the compositing-bound rationale).** The smoothness pass (§31) established that on this GPU-less box **compositing**, not encoding, dominates — so the wall is composited **once** by Chromium at the derived resolution, and a single `x11grab` capture is fanned out to per-output encodes (`supervisor.build_capture_fanout_cmd`: `-filter_complex split` + per-branch `scale` to each output's size + its own bitrate). The wall is never rendered twice. A single output at the canvas keeps the original no-filter pipeline, so the `hls` output (at the STREAM_DIR root: `playlist.m3u8` + `seg_*.ts`) is **byte-for-byte the prior VLC stream** — a regression guard. The per-rung raw-frame **memory-bound grab queue** (§32) is reused so the split/scale buffers stay ≤1 GiB.

**Restart matrix (avoid thrashing the render).** `supervisor.plan_output_restart`: a change that **moves the derived canvas** restarts the Xvfb/Chromium stack (the heavy path, the same self-heal restart as an Xvfb death); a change to only an encoder's **bitrate/audio/resolution-not-moving-the-max** respawns just the fan-out ffmpeg, **render untouched**; a per-output **`restart_epoch`** (monotonic, in config) cycles one output's encoder/publisher. With one fan-out ffmpeg (the encoders) + one publisher (Mercury), "cycle one without touching the others" holds for the real `hls`+`mercury` config.

**Status plane.** The renderer writes a per-output **status file** (`outputs-status.json`) into the shared stream volume (renderer **rw**, helper **ro** — the SAME trust direction as the HLS stream, no new privileged channel). The helper relays it for **`GET /api/outputs/status`** (path-safe read: reject symlink + resolve within the dir, same defence as the stream router), merging it with the config's configured outputs; **`POST /api/outputs/{name}/{action}`** (start/stop/restart) edits the config through the SAME validated partial-merge path as `PUT /api/wall`. `/control/` grows an **Outputs section** — a fully-working HLS/VLC card + the Mercury shell (§36); the resolution chooser moves off the wall editor onto the cards, the derived canvas shown read-only ("compositing at X").

## 35. Per-tile audio — browser-side mixing + per-output mux (2026-06-27)

The single-audible-cell pointer (§26) is **superseded** by **per-cell `audio`** (default false). **Any combination** of cells may be unmuted. The "mix" happens in the **browser, not ffmpeg**: every `audio:true` cell plays unmuted in the render Chromium → their audio combines in the single **PulseAudio null sink** → ffmpeg captures that sink **once** (`§27`'s `.monitor`). Per output, `audio:true` muxes that sink (silent if nothing is unmuted — predictable), `audio:false` omits the audio track entirely. The web wall (`/app/`) reads the per-cell `audio` flags and unmutes accordingly (the render's `--autoplay-policy=no-user-gesture-required` lets them unmute with no gesture); `/control/` toggles them per cell (a "N cells audible — mixed" hint when >1). Subtitles stay per-cell and, because they're burned into the ONE composite, appear identically in every output.

## 36. Mercury output — the `MercuryPublisher` abstraction + the inert stub (2026-06-27)

A wall cell's audio/video can be published into a Mercury (LiveKit) voice channel as a future `screen_share` stream. This build defines the **seam** and ships an **inert stub** — no network.

**The seam.** The renderer's output manager routes the `mercury` output to a `MercuryPublisher` interface (`start`/`stop`/`restart`/`status`) **instead of an ffmpeg encoder** — so the real LiveKit publisher is a drop-in at the greppable **`# MERCURY-WIRE-UP`** boundary (spec in `docs/mercury-wireup-notes.md`). The non-secret per-output fields (`channel_guid`, `display_name`) live in the wall config; the LiveKit key/secret live **only** in the gitignored `.env` (`LIVEKIT_API_KEY/SECRET/HOST/BOT_IDENTITY`, placeholders in `.env.example`) — never in the config, never logged.

**The stub (the only impl here).** `StubMercuryPublisher` reports `disabled` / `needs_setup` / `ready_not_wired` with a setup checklist (LiveKit key present? tailnet reachable? channel set?), and on `start`/`stop`/`restart` **opens no socket and mints no token** — it logs intent only. The tailnet check is a real-but-non-LiveKit TCP probe that is **short-circuited until the key + channel are present**, so the default (no creds) performs **zero** egress. `/control/`'s Mercury card renders this honestly: the ✓/✗ checklist gates the action controls (enable/start/stop/restart disabled until satisfied), while the config fields are editable now so they can be staged ahead. The card never reports "connected/publishing" in this build. Mercury's resolution is capped ≤1080p (no point pushing a 4K wall down a chat pipe). Native unchanged; PIA / other containers untouched.

## 37. Discord Activity output — a viewer iframe + a public origin + a server-side token exchange (2026-06-28)

Discord is the **third output**, and architecturally it is *unlike the other two*: it neither pulls (like HLS/VLC) nor pushes a second encode (like Mercury) — it is a **viewer** of the one HLS render, reached through a small public surface. The three transports, side by side:

| Output | Transport | Direction | Second encode? | Setup authority | "Ready" means |
| --- | --- | --- | --- | --- | --- |
| **HLS / VLC** | HLS pull | client pulls `/api/stream` (LAN) | yes (the fan-out encode) | renderer (status file) | running / stopped |
| **Mercury** | LiveKit push | renderer pushes `screen_share` | yes (a second encode + publish) | renderer (holds LiveKit env) | `ready_not_wired` (stub) |
| **Discord** | Activity iframe | a user launches a viewer that **pulls the HLS** | **no** (reuses the HLS render) | **helper** (holds `DISCORD_*`) | an operator *can launch* (NOT a live session) |

**Why an Activity (not a bot).** Discord's only ToS-legitimate surface for shared in-call video is an **Activity** (the Embedded App SDK) — a web app in the voice-channel iframe. A bot cannot broadcast video unattended; a user-token self-bot violates ToS. So Discord is **launch-to-start by platform rule**, surfaced honestly (the card never claims a session that isn't there). See `docs/decisions/0002`.

**The Activity (`discord-activity/`).** A static app: `new DiscordSDK(clientId)` → `ready()` → OAuth (`authorize` → exchange `code` at our token endpoint → `authenticate`) → a full-bleed `<video>` + hls.js playing the HLS through Discord's **`/.proxy/`** path. It uses RELATIVE URLs (`api/stream/playlist.m3u8`) so the playlist + its relative segment URIs resolve against the iframe's `…/.proxy/` base automatically — Discord proxies them to the public origin. Third-party JS is vendored same-origin (the SDK esbuild-bundled to a self-contained ESM; hls.js the same pinned bundle the wall uses), `script-src 'self'`, no CDN. Testable core (`activity-core.mjs`) is browser-global-free (the OAuth wiring + the hls attach are unit-tested with fakes under `node --test`).

**The public origin (`create_public_app`).** Discord's iframe can only reach our content through a public HTTPS origin (its URL-mappings mechanism). We expose that via the operator's **existing Cloudflare tunnel** → a NEW *minimal* app that serves ONLY: the Activity static files, `/api/discord/config` + `/api/discord/token`, and the hardened `/api/stream` **passthrough** (the SAME symlink-safe stream router — one hardened serving path, no divergent copy). The full API, `PUT /api/wall`, `/control/` and `/app/` are **never** on it; the raw LAN stream is never exposed — only this relay. It mirrors `create_stream_app`'s minimal-surface discipline, runs on its own plain-HTTP listener (TLS terminated at the tunnel edge), and a `/.proxy/`-prefix-tolerant ASGI shim makes it robust whether or not Discord forwards the prefix.

**The token exchange.** `POST /api/discord/token` exchanges the OAuth `code` for an access token using `DISCORD_CLIENT_SECRET` **server-side** (httpx → `https://discord.com/api/oauth2/token`, a fixed trusted host — the only user input is the `code`, a form value, so no SSRF surface). It returns **only** the `access_token` — never the secret, refresh token, or upstream error body; missing creds / bad code / upstream failure all **fail closed** (503 / 400 / 502) with a generic message. The client id is PUBLIC, served at `/api/discord/config` so the static app needs no build-time templating. The secret lives only in the gitignored `.env`.

**Status plane (helper-computed, unlike Mercury).** Because the helper holds `DISCORD_*` and owns the public origin (and the renderer isn't involved — Discord reuses its HLS), `/api/outputs/status` computes the Discord card directly: `disabled | needs_setup | ready`, where **`ready` = client id + secret present AND the public origin is reachable** — it means "an operator *can* launch", NOT that a session is live. There is **no** always-on "publishing" state. The reachability check is real but **non-authenticating** (a short GET of our own `/api/discord/config` through the tunnel — never a Discord call, no token, no session), TTL-cached, **short-circuited until creds are present** and **skipped in phantom**, so a not-yet-configured Discord (and phantom) perform **zero** egress; it is `unknown` when it can't be checked safely. `outputs.discord = {enabled, transport: "activity", guild_id}` is a **special viewer shape** (no resolution/bitrate/audio/restart — it inherits HLS quality); it slots into the map-shaped schema with **no `schema_version` bump** (decision 0001.4), and `clamp_reload_monotonic` tolerates an output with no `restart_epoch`. "Restart" is rejected (`400`) — launch-to-start has no helper-owned session to cycle. Native unchanged; PIA / other containers untouched.
