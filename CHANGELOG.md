# Changelog

All notable changes to MyMTS will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

### Known limitations carried forward
- `SoakFixtures.LIVE` URLs are public broadcaster HLS endpoints and decay over time. The first long-soak run may need updated URLs before it produces useful data; the rule is to edit the fixture file in a single commit, never silently drop dead fixtures from a result.
- Helper Dockerfile pins by tag (`python:3.13.1-slim-bookworm`), not by digest. Digest pinning lands in Stage 6 hardening.
