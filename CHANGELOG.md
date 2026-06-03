# Changelog

All notable changes to MyMTS will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
