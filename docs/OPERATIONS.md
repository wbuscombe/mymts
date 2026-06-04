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

Stage 1 deploys are dev-sideloads via `adb install`. The production signed-install update path is **Stage 6** — see "Update + rollback" below.

## Update + rollback (Stage 6)

The Stage 6 update flow replaces ad-hoc `adb install` with a health-gated, rollback-capable script that satisfies the Operational Bar:
- **B1 (never bricks):** the old build keeps running until the new build proves itself.
- **B2 (always a way back):** every install keeps the prior known-good APK in an archive; manual rollback is one command.
- **B5 (no silent bad-bundle cascade):** the new build is **not** promoted to known-good until a telemetry-based health gate passes.

### Where artifacts live

| Path | Contents |
|---|---|
| `$HOME/.mymts/release/archive/` | Every released APK, named `mymts-<version>+<sha>-<utc>.apk`. Older versions are retained — manual rollback to any prior version is one `--manual-rollback` invocation. |
| `$HOME/.mymts/release/known-good` | Single line with the **filename** (not absolute path) of the current known-good APK. Updated atomically (temp-write + rename). |
| `$HOME/.mymts/release/deploy.log` | Audit log of every build / install / health-gate / promotion / rollback. |

The archive directory is overridable via `--archive-dir` or `MYMTS_ARCHIVE_DIR` so production can target the NAS instead of `$HOME`.

### Signing keystore — secret, never committed

Release builds require a real keystore. The keystore + credentials are **secrets** — `.gitignore` excludes `*.jks`, `*.keystore`, and `keystore.properties`. The operator supplies them via either:

- `app/keystore.properties` (untracked file, modeled on `app/keystore.properties.example`), or
- Four environment variables of the same name: `MYMTS_RELEASE_STORE_FILE`, `MYMTS_RELEASE_STORE_PASSWORD`, `MYMTS_RELEASE_KEY_ALIAS`, `MYMTS_RELEASE_KEY_PASSWORD`.

The file path inside `keystore.properties` points at the operator's standard secrets directory (e.g. `~/.keystores/mymts-release.jks`) — **never** inside the repo. Loss of the keystore means losing the ability to push updates to existing installs (Android refuses upgrades signed with a different key); back it up off-device.

A first-time keystore is created with:
```bash
keytool -genkeypair -v \
    -keystore mymts-release.jks \
    -alias mymts -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=MyMTS,OU=Operator,O=MyMTS,C=US"
```

### Single-command deploy + health-gate + auto-rollback

```bash
# Build + sign + install + health-gate + promote-or-rollback
./scripts/deploy-app.sh

# Dry-run: build + archive only, NO device touch (the operator-away
# default — confirms the signed APK is producible without risking the
# physical box while no one's there to recover it).
./scripts/deploy-app.sh --dry-run

# Manual rollback to the last known-good APK
./scripts/deploy-app.sh --manual-rollback
```

Flags:
| Flag | Default | Notes |
|---|---|---|
| `--device <ip[:port]>` | `<LAN_IP>:5555` | Target adb device. |
| `--archive-dir <path>` | `$HOME/.mymts/release` | Archive + known-good pointer location. |
| `--dry-run` | off | Build + archive only; never touches the device. |
| `--manual-rollback` | off | Skip build; reinstall the current known-good. |
| `--minimum-ready N` | 2 | Health-gate floor: minimum `EV=TILE_READY` events. |
| `--expected-tiles N` | 4 | Wall's tile count — used to detect FAIL_ALL_DEAD. |
| `--deadline-seconds N` | 90 | Health-gate observation window. |

### The health gate (`scripts/health_check.py`)

After install + launch, the deploy script captures `MYMTS_SOAK` telemetry for `--deadline-seconds` and runs the gate's decision logic over it. The decision is **pure** (`scripts/test_health_check.py` exercises 15 cases) and returns one of:

| Outcome | Trigger | Result |
|---|---|---|
| `PASS` | ≥ `minimum_ready` `EV=TILE_READY` events, no `EV=DEAD` within window | promote: write known-good pointer |
| `FAIL_ALL_DEAD` | `EV=DEAD` count ≥ expected tile count | rollback to prior known-good |
| `FAIL_DECODER_THRASH` | `EV=DECODER` ≥ expected tile count but `EV=TILE_READY` == 0 | rollback (the `b19b013` regression shape) |
| `FAIL_NOT_READY` | `EV=TILE_READY` < minimum | rollback |
| `FAIL_TIMEOUT` | window elapsed without sufficient data | rollback |

The script's exit code mirrors the outcome (0 = PASS / promoted; 1 = FAIL / rolled back; 3+ = configuration error / blocked).

### Manual rollback — the always-a-way-back guarantee

If a deploy goes wrong outside the health-gate window (a regression that surfaces hours later), the operator runs:
```bash
./scripts/deploy-app.sh --manual-rollback
```
This reinstalls the **current known-good** (the APK named in `known-good` *before* the failed deploy promoted itself — note: a failed deploy does NOT update the pointer, so the pointer always names the last working build). Because APKs are kept in the archive, rolling further back to any prior version is `adb install -r -d <archive>/mymts-X.Y.Z+sha-…apk`.

### Health-gate verification status (recorded honestly)

The decision-logic Python is fully unit-tested (`scripts/test_health_check.py`). The end-to-end install + rollback flow was **verified on `.182` on 2026-06-04** with the operator physically present:

1. **Baseline established.** `./scripts/deploy-app.sh` built + signed + archived `mymts-0.0.0+5576551-20260604T230502Z.apk`, installed, launched, health-gate PASS (`117 EV=TILE_READY events ≥ 2, 0 dead`), promoted to known-good.
2. **Deliberate failure pushed.** `gradle.properties` temporarily flipped to `MYMTS_HELPER_BASE_URL=https://192.168.99.99:9999` (unreachable). Force-clean rebuild + deploy.
3. **Auto-rollback fired.** Health gate returned `FAIL_NOT_READY` (`0 EV=TILE_READY events; need ≥ 2`) after the 90 s capture window. Script automatically reinstalled the known-good APK (`mymts-0.0.0+5576551-20260604T230502Z.apk`) and relaunched. Wall came back up with 4 distinct slots LIVE (`bloomberg-tv / cbs-sports-hq / bbc-news / cnn`). `known-good` pointer unchanged; failed APK retained in archive for diagnosis.
4. **Manual rollback exercised.** `./scripts/deploy-app.sh --manual-rollback` reinstalled the known-good and relaunched in ~6 seconds; wall up; `known-good` pointer unchanged.
5. **Gotcha recorded for the runbook.** During the rollback test, the deploy script's first re-run accidentally promoted an APK whose content was stale — gradle had decided `:app:assembleRelease` was up-to-date despite a `gradle.properties` change. The fix is to add `:app:clean` to the build step (or to make the gradle config explicitly depend on `gradle.properties` mtime). For now, **always force a clean rebuild** when changing the helper URL or other config-driven `buildConfigField` values, especially before the rollback test.

The Operational Bar B1/B2/B5 properties (never bricks / always a way back / no silent bad-bundle cascade) are now operationally verified on real hardware.

## New MyMTS box provisioning (pending — hardware in transit)

### Decision recorded (2026-06-04): one kiosk app per box (Model A)

Per the at-the-box finale prompt, the operator confirmed **Model A — one kiosk app per box**. `onn-office` (`.182`) is WyzeGrid's permanent home for the cameras kiosk; MyMTS gets its own dedicated Onn box (in transit, not yet arrived). The kiosk / foreground-coexistence work is therefore deferred to the new-box-provisioning session — it must NOT run on `.182`, because testing MyMTS's foreground watchdog on WyzeGrid's box would reintroduce the Stage 1 two-watchdog thrash on the camera box, pointlessly. (The Stage 2 finding documented this exact contention.)

### Checklist (run when the new box arrives)

1. **Physical setup.** Power on, connect HDMI/display, Wi-Fi or wired Ethernet, configure DHCP reservation on the router. Record the IP.
2. **Update `ONN-BOXES.md`.** Add a new row for the new box with its stable location-based name (e.g. `mymts-display` or wherever it physically lives), IP, hardware (Amlogic S905Y4 / armeabi-v7a / Android 14), and role = MyMTS. Both repos' copies of `ONN-BOXES.md` get the update.
3. **Install signed MyMTS.** Use `scripts/deploy-app.sh --device <new-ip>:5555 --archive-dir $HOME/.mymts/release` to push the current known-good APK from the dev Mac. Confirm `/api/channels` + the wall come up over HTTPS (no cleartext exception is left in the app — the cutover already happened).
4. **Build + validate the kiosk / foreground / boot story THERE.** MyMTS gets its own long-uptime kiosk behavior on its own box — a foreground service + boot-receiver so it relaunches on reboot and holds the screen for its ambient-wall role (mirroring WyzeGrid's proven pattern — same lineage). Test:
   - **Hold the foreground.** Run for at least 80 minutes (the Stage 1 finding's WyzeGrid eviction window) to confirm no second watchdog reclaims the foreground.
   - **Survive a reboot.** Power-cycle the box; confirm MyMTS launches and reaches LIVE without manual intervention.
   - **Survive a low-memory event.** Force-stop other apps + start a memory hog; confirm MyMTS is not evicted.
5. **Confirm `.182` is unchanged.** WyzeGrid foreground + `WatchdogService` healthy; MyMTS dev install may remain on `.182` (harmless) but WyzeGrid is the intended foreground owner there.

### Why this is *not* attempted on `.182`

- `.182` is the camera box. A two-foreground-app contention there blinds the operator to the cameras while we test.
- MyMTS's kiosk story tested on a borrowed box doesn't tell us anything useful — different hardware budget, different background pressure (WyzeGrid's WatchdogService present), different physical attached panel. The new box is what it'll ship on.
- The away-from-box + safe-on-`.182` roadmap is complete after Step 4. The kiosk work waits for hardware.

## WyzeGrid coexistence (note for the record; not currently active state)

During the Stage 1 gate-clearing soak window, WyzeGrid was disabled-user on `.182` because its persistent `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` watchdog (`SYSTEM_ALLOW_LISTED`) reclaimed the foreground from MyMTS around 80 minutes into the first attempt; backgrounded MyMTS was then evicted on the 2 GB box.

**Current state: WyzeGrid is re-enabled on `.182` and back to its normal operating state** (foreground, watchdog service running). The disable has been a recurring, surgical step bound to each soak window (Stage 1 long soak; Stage 2 Part C bracket sweep; Stage 2 Part C long soaks v1 + v2). After each window closes, the recipe below is executed and verified.

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

## TLS for the TV ↔ helper link (Stage 6 — baseline)

### What this gives you

- Helper serves **HTTPS on 8443** alongside **HTTP on 8091** (the transitional sequence so an operator-away deploy can't strand the box).
- App's default `MYMTS_HELPER_BASE_URL` is `https://<LAN_IP>:8443`; the app trusts **only** the helper's own self-signed cert via `network_security_config.xml` (no system-CA fallback for this host, so even a global-CA MITM is refused).
- Cleartext fallback still permitted for `<LAN_IP>` as a recovery seatbelt — **removed in the "at-the-box" finale Step 1** (deferred until you can physically recover the box if anything goes wrong).

### Where the cert + key live

| Path | What |
|---|---|
| `/srv/docker/mymts-helper/_secrets/helper.key` | Private key — **on the NAS only**, mode 600, ownership UID 10001. **Never in the repo, never in logs.** |
| `/srv/docker/mymts-helper/_secrets/helper.crt` | Public cert — also on the NAS, mode 644. |
| `app/src/main/res/raw/helper_cert.pem` | Public cert embedded in the APK as a pinned trust anchor. Committed (public material). |

`.gitignore` excludes any `*.key`, plus `helper/**/*.crt` and `helper/**/*.pem`, with one `!app/src/main/res/raw/helper_cert.pem` re-include for the public cert that the app trusts.

### Generating / rotating the cert (one-shot, on the NAS)

```bash
ssh <HOST>
mkdir -p /srv/docker/mymts-helper/_secrets
chmod 700 /srv/docker/mymts-helper/_secrets
openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \
    -keyout /srv/docker/mymts-helper/_secrets/helper.key \
    -out   /srv/docker/mymts-helper/_secrets/helper.crt \
    -subj "/CN=MyMTS Helper" \
    -addext "subjectAltName=IP:<LAN_IP>,DNS:mymts-helper"
chmod 600 /srv/docker/mymts-helper/_secrets/helper.key
chmod 644 /srv/docker/mymts-helper/_secrets/helper.crt
```

**Gotcha (recorded):** the `cargo` host user is UID 1000 but the helper container runs as UID 10001. The cert files end up owned by `cargo`, unreadable by the container — the helper crash-loops with `PermissionError: [Errno 13]` on startup. Fix without `sudo` by using a short-lived root container (`cargo` has docker group membership):

```bash
ssh <HOST>
docker run --rm -v /srv/docker/mymts-helper:/parent alpine sh -c \
    "chown -R 10001:10001 /parent/_secrets && chmod 750 /parent/_secrets"
docker run --rm -v /srv/docker/mymts-helper/_secrets:/secrets alpine sh -c \
    "chmod 600 /secrets/helper.key && chmod 644 /secrets/helper.crt"
```

The directory ends up `drwxr-x---  10001:10001`, the key `-rw-------  10001:10001`, the cert `-rw-r--r--  10001:10001`. Container can now read them; host user `cargo` cannot list the directory directly any more (use docker to inspect — `docker exec mymts-helper ls /etc/ssl/mymts/`).

### Updating the APK with a new cert

After rotating the cert on the NAS, `scp` the new `helper.crt` back to the dev machine, replace `app/src/main/res/raw/helper_cert.pem`, rebuild + ship the APK via `scripts/deploy-app.sh`. The next time the app launches, it'll trust the new cert. Until the new APK is installed, the old cert is the only one the app trusts — so **rotate the cert + ship the APK in a coordinated pair**, not separately.

### The dual-port migration sequence — **COMPLETE 2026-06-04**

| Step | What | Where |
|---|---|---|
| 1 | Helper serves HTTPS 8443 + HTTP 8091 (both work) | ✅ Stage 6 baseline (`b8240b7`) |
| 2 | App defaults to HTTPS 8443, cleartext exception in place | ✅ Stage 6 baseline (`b8240b7`) |
| 3 | Telemetry-verify (TILE_READY over HTTPS, no trust errors) | ✅ Stage 6 baseline (`b8240b7`) |
| 4 | Remove cleartext exception, rebuild + deploy app | ✅ At-the-box finale Step 2A (this commit) |
| 5 | Remove HTTP 8091 from compose, redeploy helper | ✅ At-the-box finale Step 2B (this commit) |

The migration's safety properties held end-to-end: when the cleartext-only APK was installed on `.182`, the helper was still serving HTTP+HTTPS and the failure mode of "app can't reach the helper" was visible via telemetry before any helper-side change. When the helper was then narrowed to HTTPS-only, the app continued to reach it.

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
