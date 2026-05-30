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

## 7. What this document deliberately does NOT specify yet

- Exact on-device persistence mechanism — chosen in Stage 5 (lineup/presets).
- API shape between TV and helper — pinned in Stage 2 with a contract test.
- Update mechanism details — chosen in Stage 6.
- Helper deployment to the NAS — Stage 2 once the helper does real upstream work; the Stage 1 helper is local-Docker-only by design.

Each will be filled in when its stage lands, with rationale traced back to a foundation doc.
