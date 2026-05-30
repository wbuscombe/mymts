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

### Gate status
- **Preliminary.** Method validated, apparatus committed, interim default = 6.
- **Pending:** the operator's multi-hour (≥ 4 h) LIVE-pool soak per `docs/findings/01-onn4k-tile-budget.md`. Until that run is recorded in the `## Final` section of that file, **Stage 2 (helper aggregation + resolution) is blocked.**

### Known limitations carried forward
- `SoakFixtures.LIVE` URLs are public broadcaster HLS endpoints and decay over time. The first long-soak run may need updated URLs before it produces useful data; the rule is to edit the fixture file in a single commit, never silently drop dead fixtures from a result.
- Helper Dockerfile pins by tag (`python:3.13.1-slim-bookworm`), not by digest. Digest pinning lands in Stage 6 hardening.
