# OPERATIONS

> **Status (Stage 0):** Skeleton. The operational outcomes are locked in `docs/foundation/03-OPERATIONAL-BAR.md`; the mechanisms that achieve them are filled in Stage 1 (spike), Stage 6 (hardening), and Stage 7 (portability + final docs).

---

## Sections to populate (and which stage owns each)

| Section | Owned by | What lives here |
|---|---|---|
| Install (cold-start, fresh box → on-the-wall) | Stage 1 + Stage 6 | The documented, repeatable path. Reproducible. Single source of truth. |
| Update + rollback | Stage 6 | Signed-install update path. Always a way back. No silent fleet cascade. How to abort a rollout. |
| Backup + restore | Stage 6 | On-device data backup + restore procedure. Stored where, encrypted how, restored how. |
| Self-tending upkeep | Stage 6 | Cleanup, rotation, renewals, retries. Cron, scheduled tasks, etc. |
| Operator alerting | Stage 6 | The channel the operator actually sees (existing `claude-status-bot` integration pattern). What fires alerts and when. |
| Health-at-a-glance | Stage 3 + Stage 5 + Stage 6 | In-app diagnostic view + helper health endpoint contract. |
| Recovery procedures | Stage 6 + Stage 7 | "Helper is broken, what now." "App won't start, what now." "Backup restore." |
| NAS layout + Docker conventions | Stage 2 | Helper's location on the NAS, log paths, secret paths, follows operator's existing standard layout. |

---

## Standing operational rules (apply from the first commit)

- **unrelated host container is never touched.** Restart, modify, reconfigure — all forbidden unless the operator explicitly requests it.
- **Auto-deploy is `pull → rebuild → restart`.** A bare restart never picks up changes. The deploy script verifies the full cycle.
- **No secrets in any log line, error message, or alert payload.**
- **The operator is alerted through a channel they actually see** (Op Bar C2). The default is the existing notification bot pattern (`claude-status-bot`).
- **Health is legible without deep investigation** (Op Bar C3). `/health` returns truth; the in-app diagnostics surface it.

---

## Health endpoint contract (Stage 2 will pin this)

The helper exposes a health endpoint that:

- Returns a small JSON document with `schema_version` so consumers can detect drift.
- States freshness for each upstream (last successful fetch + per-source error count).
- States whether protections (egress allowlist, etc.) are in force.
- Includes build SHA + version + uptime.

A contract test in the operator's monitoring tooling and in MyMTS itself prevents silent schema drift. Schema details land in Stage 2.

---

## Toolchain (Stage 1 — pinned for reproducibility)

| Tool | Version | Install |
|---|---|---|
| Android Studio | 2024.x (Ladybug or later) | `brew install --cask android-studio` |
| Android SDK Platform | 35 | `sdkmanager 'platforms;android-35'` |
| Android Build Tools | 35.0.0 | `sdkmanager 'build-tools;35.0.0'` |
| Android Command-Line Tools | latest | `brew install --cask android-commandlinetools` |
| Android Platform Tools (adb) | r37 | `brew install android-platform-tools` (or via sdkmanager) |
| OpenJDK | 17 | `brew install openjdk@17` |
| Gradle | 8.10+ (via wrapper) | shipped via `gradlew` |
| AGP | 8.7.3 | declared in `gradle/libs.versions.toml` |
| Kotlin | 2.0.0 | declared in `gradle/libs.versions.toml` |
| Python | 3.13 | required for helper; via brew or uv |
| uv | latest | `brew install uv` |

Required env for command-line builds:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$PATH"
```

`local.properties` (gitignored) at the repo root must contain `sdk.dir=$HOME/Library/Android/sdk`.

## Single-command runners

```bash
# TV app
./gradlew :app:assembleDebug                    # build the APK
./gradlew :app:testDebugUnitTest                 # run unit tests
./scripts/deploy.sh <LAN_IP>               # build + install + launch on an Onn box

# Helper
cd helper && uv sync --extra dev                 # install deps into .venv
cd helper && uv run pytest                       # run all tests
cd helper && uv run python -m mymts_helper       # start on :8091 locally
```

## Soak harness (Stage 1)

The soak harness lives in `scripts/soak.sh`. It installs the debug APK, starts the in-app soak mode, then streams two telemetry channels into a per-run results directory:

- `events.log` — every `MYMTS_SOAK` logcat line (TILE_MOUNT, TILE_READY, DROPPED, ERROR, BEAT, DECODER).
- `meminfo.csv` — periodic `dumpsys meminfo` samples.

`scripts/parse-soak-log.py` reads a run directory and emits a markdown summary suitable for pasting into the finding doc.

```bash
# 4-hour gate-clearing soak (operator runs this; the harness was method-validated in-session):
./scripts/soak.sh --device <LAN_IP> --tiles 6 --pool live --duration 14400 --meminfo-interval 60

# Quick method-validation:
./scripts/soak.sh --device <LAN_IP> --tiles 4 --pool stable --duration 600 --meminfo-interval 30

# Summarize a finished run:
./scripts/parse-soak-log.py docs/findings/runs/<run-id>/
```

## Install path (Stage 1 dev-sideload only)

Stage 1 deploys are dev-sideloads via `adb install`. The production signed-install update path (Op Bar B1/B2 — never bricks, always a way back) lands in Stage 6.

## WyzeGrid coexistence (note for the record; not currently active state)

During the Stage 1 gate-clearing soak window, WyzeGrid was disabled-user on `.182` because its persistent `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` watchdog (`SYSTEM_ALLOW_LISTED`) reclaimed the foreground from MyMTS around 80 minutes into the first attempt; backgrounded MyMTS was then evicted on the 2 GB box.

**Current state: WyzeGrid is re-enabled on `.182` and back to its normal operating state** (foreground, watchdog service running). The disable was a temporary, surgical step bound to the Stage 1 soak; it is not part of the steady-state plan.

If a future soak needs the same window, the recipe is:

```
# disable
adb -s <LAN_IP>:5555 shell am force-stop com.wyzegrid
adb -s <LAN_IP>:5555 shell pm disable-user --user 0 com.wyzegrid

# re-enable + relaunch
adb -s <LAN_IP>:5555 shell pm enable com.wyzegrid
adb -s <LAN_IP>:5555 shell am start -n com.wyzegrid/.MainActivity
```

The deeper finding — that two foreground TV apps cannot coexist on a 2 GB Onn box and MyMTS will need its own foreground-service kiosk story when it goes to production — is in `docs/STAGE-2-PLAN.md` (the production-deployment concerns section) and `docs/BACKLOG.md`.

## Helper on the NAS (Stage 2 deploy reality)

### Layout

```
/srv/docker/mymts-helper/
├── _src/              # rsynced helper/ tree (source for `docker compose build`)
├── compose.yml        # symlinked/copied from helper/deploy/docker-compose.nas.yml
└── .env               # BUILD_SHA, BUILD_VERSION, PHANTOM_MODE, LOG_LEVEL, intervals
```

Persistent state lives in the **Docker named volume** `mymts-helper-data`:

```
/var/lib/docker/volumes/mymts-helper-data/_data/
└── mymts-helper.db    # sqlite WAL (+ -wal + -shm files)
```

Logs: Docker `json-file` driver, 10 MB × 3 rotations per the compose. Accessible via `docker logs mymts-helper`.

### Single-command operations

```bash
# Deploy from the dev machine (rsync + build + up + /health verify)
./scripts/deploy-helper.sh                           # uses ssh alias `<HOST>`
./scripts/deploy-helper.sh --host cargo@<LAN_IP>

# On the NAS:
ssh <HOST> "cd /srv/docker/mymts-helper && docker compose ps"
ssh <HOST> "docker logs --tail 100 -f mymts-helper"
ssh <HOST> "curl -fsS http://127.0.0.1:8091/health | jq ."

# From a LAN host (Onn box, dev Mac):
curl -fsS http://<LAN_IP>:8091/health | jq .
curl -fsS http://<LAN_IP>:8091/api/channels | jq .
curl -fsS "http://<LAN_IP>:8091/api/feed?limit=5" | jq .
```

### Helper backup / restore

State is small (channels + sources + feed_items). Backup the volume to a tarball:

```bash
# Backup (on the NAS):
docker run --rm \
    -v mymts-helper-data:/data \
    -v "$PWD":/backup \
    alpine tar -czf /backup/mymts-helper-data-$(date +%Y%m%d).tgz -C / data

# Restore (on the NAS, with the helper stopped):
docker compose -f /srv/docker/mymts-helper/compose.yml down
docker run --rm \
    -v mymts-helper-data:/data \
    -v "$PWD":/backup \
    alpine sh -c 'rm -rf /data/* && tar -xzf /backup/mymts-helper-data-YYYYMMDD.tgz -C /'
docker compose -f /srv/docker/mymts-helper/compose.yml up -d
```

A nightly automated backup lands in Stage 6 (Op Bar C5 — backups are not optional).

### Image digest pinning policy

The runtime base image is pinned by digest in `helper/Dockerfile`:

```
ARG PYTHON_IMAGE=python:3.13.1-slim-bookworm@sha256:031ebf3cde…
```

To refresh (Stage 6 cadence target: monthly, on advisory):

```bash
TOKEN=$(curl -s 'https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/python:pull' | jq -r .token)
curl -sI -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.index.v1+json" \
    "https://registry-1.docker.io/v2/library/python/manifests/3.13.1-slim-bookworm" \
    | grep -i docker-content-digest
```

Update the `PYTHON_IMAGE` ARG in `Dockerfile`, commit, deploy, verify `/health` reports the new SHA.

### claude-status-bot contract

The bot consumes `GET /health` from the NAS LAN (`http://<LAN_IP>:8091/health`). Pinned shape:

```
{
  "schema_version": 1,        // bot alerts on unknown values
  "ok": true,
  "ready": true,
  "phantom": false,
  "build_sha": "<short-git-sha>",
  "version": "<semver>",
  "uptime_seconds": <float>,
  "feeds": {
    "sources_count": <int>,
    "items_count": <int>,
    "stale_sources": [<label>, ...],   // empty when healthy
    "last_poll_at": "<iso utc>" | null
  },
  "channels": {
    "channels_count": <int>,
    "live_count": <int>,
    "unavailable_count": <int>,
    "last_probe_at": "<iso utc>" | null
  }
}
```

Schema bumps require a coordinated update in the bot. Adding fields is backward-compatible.

## Backup + restore placeholder (Stage 6 will write the full automation)

The Stage 2 helper backup recipe lives above. Stage 6 will add nightly automation + retention + restore verification + the TV-side data backup (lineup/presets, once Stage 5 introduces them).
