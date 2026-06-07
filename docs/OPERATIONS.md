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

## Helper redeploy — 2026-06-06 (main HEAD `611544d`: 13 sources + ticker + web client)

Redeployed the NAS helper from `main` via `scripts/deploy-helper.sh` (full rsync → `docker compose build --pull` → `up -d` → HTTPS health-verify; helper service only). This activates the feed-sources, ticker, and web-client chapters that were committed-but-not-deployed, and **satisfies the migration's helper-redeploy prerequisite**.

**Before → after** (`curl -k https://<LAN_IP>:8443/health`): `build_sha` `a21f37c` → **`611544d`**; `feeds.sources_count` **4 → 13**; `/api/ticker/{markets,sports}` **404 → live**; `/app` **404 → 200**.

**Verified live:**
- `/health` → `ready=true`, 13 sources, 1107 items, 10 live channels.
- `/api/feed` → items from all 12 distinct sources flowing as inert plain text; **5 rapid hits all 200, no `sqlite3.ProgrammingError`** (the cross-thread fix `069f783` is live and stable).
- `/api/ticker/sports` → real ESPN scoreboard data (live games).
- `/api/channels` → 10 live channels (no regression).
- Hardening intact: container non-root (uid 10001), `mymts-net` bridge only, `_secrets` owned 10001:10001 (TLS key readable). **unrelated host container (`pia`) unchanged — Up, on `downloads_default`, never referenced.**

**LAN web client — now enabled.** Set in the deploy `.env`: `WEB_CLIENT_DIR=/app/web`; the repo-root `web/` tree is rsynced to `/srv/docker/mymts-helper/_web/` and bind-mounted read-only at `/app/web`. Browse on the LAN to **`https://<LAN_IP>:8443/app/`**.
> **Self-signed-cert browser warning is EXPECTED.** The cert is pinned by the native app; a desktop/phone browser will show a "not private" warning — accept it for the LAN host once. This is normal for a self-signed LAN cert and is why the client is LAN-only (never exposed publicly). To disable the web client: set `WEB_CLIENT_DIR=` (empty) in the NAS `.env` and re-up.

**⚠️ Honest data-source note — markets ticker partially SAMPLE from the NAS.** On the NAS egress, **Stooq now serves a JavaScript anti-bot challenge** (HTTP 200 with a JS proof-of-work page, not CSV) instead of quotes — so indices/FX/gold parse to zero and fall back to **honest SAMPLE pills**, while **CoinGecko (BTC/ETH) returns real values**. This is the C3 honesty contract working exactly as designed (a real upstream failure shows as SAMPLE, never faked-live) — not a deploy fault. It differs from the ticker chapter's verification, which hit Stooq cleanly from the dev Mac; the NAS's IP is being bot-walled. Logged in BACKLOG ("Markets ticker — Stooq anti-bot challenge from the NAS egress"). The fix (a different keyless indices/FX source, or accept SAMPLE) is a data-source follow-on, not a redeploy concern.

## Office ONN Box - MyMTS provisioning (DONE 2026-06-07)

MyMTS now runs on its permanent dedicated hardware: **`Office ONN Box - MyMTS` = `<LAN_IP>:5555`**, MAC `<MAC>` (DHCP-reserved), Amlogic S905Y4 / `s4` / armeabi-v7a / Android 14 (API 34) / build `URO4.260304.011.B1` / serial `GUSA2541026903`, driving a 720p panel.

- **Display:** the garble was the **EDID emulator** (removed). The bare panel auto-negotiates **720p60 natively** (`defaultModeId`=720p) — no pin needed, and it comes up 720p on every boot. NOTE: ADB `cmd display set/clear-user-preferred-display-mode` **cannot persist** on this build (shell uid hits `SecurityException: Package android does not belong to 2000` writing the global setting), so the ADB pin is in-memory only — moot here since native negotiation is 720p. Durable override, if ever needed (e.g. an emulator re-added): the Display-settings UI (writes it as system).
- **Debloat (reversible, `pm disable-user --user 0`):** 12 pkgs — `amazon.amazonvideo.livingroom, cbs.ott, disney.disneyplus, hulu.livingroomplus, instagram.airwave, tubitv` (streaming/social promos) + `youtube.tv, youtube.tvmusic, katniss, feedback, tv.feedbackconsent, partnersetup`. Verified after: boots clean, launcherx home works, ADB intact, WiFi intact (box pings the NAS). Restore any with `pm enable`.
- **Remote firmware:** apply the **BT remote-control** firmware update at the box UI only (Settings → Remotes & Accessories). **System/Android OS update SKIPPED** (deliberate — protects dev/ADB config). OS baseline to compare after: fingerprint `…/URO4.260304.011.B1/15051976…`, patch `2026-03-01`.
- **Fresh release key (the prior `.182`-era key was abandoned):** generated 2026-06-07, RSA-2048, validity ~27 yr, `CN=MyMTS, O=3SL Studios, C=US`, cert SHA-256 `7acc6315…c24ca8ca`. `.jks` at `app/mymts-release.jks` (**gitignored**) + backed up to `~/Dropbox/secrets/mymts-release.jks`; `app/keystore.properties` (gitignored) holds the four `MYMTS_RELEASE_*` values. PKCS12 → single password (store==key). **Passwords live in the operator's password manager** (reported once at generation; never written to the repo/docs).
- **Apps installed:** **MyMTS** release-signed via `scripts/deploy-app.sh --device <LAN_IP>:5555` — health-gate **PASS (119 `EV=TILE_READY`, 0 dead)**, promoted known-good `mymts-0.0.0+1b8d1b0`. **WyzeGrid** built debug + installed **dormant** (no kiosk flag, not launched; debug key `~/Dropbox/secrets/wyzegrid-debug.keystore`). One-kiosk-per-box (Model A) preserved.
  - **Fixed `deploy-app.sh`:** its signing check parsed apksigner's old `Subject:` label; build-tools 33+ prints `Signer #1 certificate DN:`. Now matches both (else a correctly-signed release falsely fails). Committed.
  - **BUILD_SHA note:** the first deploy embedded a stale `5576551` (gradle reused a cached BuildConfig); a `./gradlew clean` + redeploy fixed it (APP_START now `sha=1b8d1b0`).
- **§5 LIVE on permanent hardware:** all 4 default tiles reach LIVE on the box's residential-WiFi path — **bloomberg-tv, cbs-sports-hq, bbc-news, cnn** (119 TILE_READY, 0 dead). The earlier health-gate "fail" was purely the **panel asleep** during the window (no kiosk keep-awake yet) — a relaunch with the screen on showed all tiles LIVE.
- **§6 kiosk:** `am start -n com.mymts/.MainActivity --ez kiosk true` (via a force-stop→start so `onCreate` reads the extra) → `KioskService` foreground service up (`isForeground=true`, channel `mymts_kiosk`).
- **§6 reboot test — partial, one known gap:** `adb reboot` → **network ADB survives** (re-arms on boot despite empty `persist.adb.tcp.port`); box **auto-restores 720p + screen Awake**; `BootReceiver` fires → `KioskService` foreground starts on boot. **BUT the wall ACTIVITY does not auto-foreground over the Google TV launcher** — `KioskService.launchWall()`'s boot-time `startActivity` is blocked by Android 14 **background-activity-launch (BAL)** restrictions, so the launcher stays in front and 0 tiles render until the wall is opened. **The wall works perfectly when launched** (manually or by selecting it).
  - **HOME-launcher fix ATTEMPTED 2026-06-07 — does NOT work cleanly on this Google TV build; reverted.** Added `HOME`+`DEFAULT` to `MainActivity`, redeployed, and `cmd package set-home-activity com.mymts/.MainActivity` (MyMTS held `android.app.role.HOME`). **But MyMTS still lost the boot home-race:** Google TV stacks multiple system home filters at higher `android:priority` than a third-party app — `launcherx` (priority=2), the setup-wizard `setupwraith.RecoveryActivity` (priority=1), and `tv.settings` — and the boot home-launch follows priority, not the HOME role, so the reboot still came up on `launcherx` (0 tiles). Disabling those system launchers to force MyMTS to win **destabilized the box** (SystemUI/`system_server` restart, `pm` "Broken pipe", ADB flaked) — recovered by re-enabling `launcherx`+`setupwraith` and resetting the HOME role to `launcherx`. **Net: reverted to clean known-good** (manifest reverted, clean APK redeployed/promoted `66b387d`, launchers enabled, only the 12 debloat pkgs disabled). **The boot-auto-foreground gap remains; the wall works when launched.**
  - **Safer paths if the operator still wants wall-on-boot (do NOT just disable the system launchers — it destabilizes this build):** (a) **full-screen-intent** — `KioskService` posts a full-screen-intent notification to bring the wall up on boot, which is *allowed* from the background and **doesn't touch the launcher** (recommended, lowest risk); (b) **device-owner / lock-task** kiosk provisioning (most robust, but a bigger change — `dpm set-device-owner` on a fresh box); (c) **accept manual launch** after the (rare) reboot. Option (a) is the recommended next attempt — code-only, no launcher surgery.

---

## New MyMTS box provisioning (original runbook — superseded by the DONE record above)

### Decision recorded (2026-06-04): one kiosk app per box (Model A)

Per the at-the-box finale prompt, the operator confirmed **Model A — one kiosk app per box**. `onn-office` (`.182`) is WyzeGrid's permanent home for the cameras kiosk; MyMTS gets its own dedicated Onn box (in transit, not yet arrived). The kiosk / foreground-coexistence work is therefore deferred to the new-box-provisioning session — it must NOT run on `.182`, because testing MyMTS's foreground watchdog on WyzeGrid's box would reintroduce the Stage 1 two-watchdog thrash on the camera box, pointlessly. (The Stage 2 finding documented this exact contention.)

### Migration runbook (ordered — run top to bottom on the new box)

> The kiosk/boot **code** is built + unit-tested (commits in the kiosk chapter); this runbook is execution, not build-from-scratch. Steps marked **[STAGED]** are the on-hardware validations that genuinely need the box — they could not be tested before it arrived.

**Prerequisite — helper redeploy: DONE 2026-06-06 (`611544d`).** The helper was redeployed to `main` HEAD on the NAS via `scripts/deploy-helper.sh` (see "Helper redeploy 2026-06-06" below). `/health` now reports `build_sha=611544d`, `ready=true`, `feeds.sources_count=13`; `/api/ticker/{markets,sports}` are live; the LAN web client is served at `/app`. **So this afternoon's migration can skip the redeploy unless new helper changes land first** — just re-verify `/health` shows 13 sources + the ticker endpoints before provisioning. The original gap (recorded for history): the running helper had been behind `main` by the feed-sources (`069f783`) + ticker (`81b06c9`) chapters; if you ever need to re-run it from a behind state:
> ```
> cd ~/docker/mymts-helper && git pull && docker compose up -d --build
> curl -sk https://<LAN_IP>:8443/health | jq '.feeds.sources_count'      # expect 13 (was 4)
> curl -sk https://<LAN_IP>:8443/api/ticker/markets | jq '.mode'         # expect "markets" (was 404 before redeploy)
> curl -sk https://<LAN_IP>:8443/api/ticker/sports  | jq '.mode'         # expect "sports"
> ```
Standing rule: helper stays non-root / read_only / cap_drop ALL / dedicated bridge — **never the unrelated host container**.

1. **Physical setup.** Power on, connect HDMI to the production TV, join the network (wired Ethernet preferred for a wall). In Android-TV settings enable Developer options → **USB/network debugging**.
2. **DHCP reservation.** On the router, reserve a fixed IP for the box's MAC. **Record it** — call it `NEWIP` below. (Suggested: keep it near the helper, e.g. `<LAN_IP>`.)
3. **ADB connect from the dev Mac.** `adb connect NEWIP:5555` → `adb devices` shows it `device`. (Accept the on-screen RSA prompt on the TV the first time.)
4. **Fill in `ONN-BOXES.md`.** The `onn-mymts` row is pre-staged with IP `TBD-at-provision` — replace it with `NEWIP`, confirm hardware (Amlogic S905Y4 / armeabi-v7a / Android 14 / API 34) and the attached TV's resolution. Copy the same edit into the WyzeGrid repo's `ONN-BOXES.md` (keep the two identical).
5. **Install signed MyMTS (health-gated).** From the repo on the dev Mac:
   `scripts/deploy-app.sh --device NEWIP:5555 --archive-dir $HOME/.mymts/release`
   The script builds a **release-signed** APK (refuses to push a debug-signed build — `apksigner` is the gate), installs, runs the health-gate, and promotes-or-rolls-back. The app's `HELPER_BASE_URL` is baked to `https://<LAN_IP>:8443` with the pinned cert (cleartext was removed at the TLS cutover), so no per-box URL edit is needed.
   - **Operator input needed at this step:** `NEWIP`; and the release **keystore** must be reachable (the signing secret — never committed; same keystore used for the `.182` release installs).
6. **Confirm the wall reaches the helper.** On the box, the wall launches automatically (LEANBACK launcher). Verify: feed pane populates (13 sources sectioned), at least one video tile reaches **LIVE**, the ticker shows real markets data (SAMPLE pills only on Brent/WTI/10Y). If the feed/tiles stay empty, re-check the helper prerequisite above and that the box can reach `<LAN_IP>:8443`.
7. **Enable kiosk mode (the own-the-box role).** Kiosk is **opt-in, off by default** — enable it explicitly on *this* box only:
   `adb -s NEWIP:5555 shell am start -n com.mymts/.MainActivity --ez kiosk true`
   This persists the kiosk flag and starts the foreground service. (To undo: `--ez kiosk false`.) Confirm the service is up: `adb -s NEWIP:5555 shell dumpsys activity services com.mymts | grep -i KioskService`.
8. **[STAGED] Validate kiosk uptime on hardware.** These need the running box and cannot be pre-tested:
   - **Hold the foreground ≥ 80 min.** Leave the wall running; confirm it stays foregrounded (no eviction). Model A means nothing else should contend, so this should be uneventful — but it's the first real long-uptime run on this hardware budget.
   - **Survive a reboot.** `adb -s NEWIP:5555 reboot`; confirm MyMTS relaunches on its own (BootReceiver → KioskService → wall) and reaches LIVE with no manual touch.
   - **Survive low memory.** Start a couple of other apps / a memory hog; confirm MyMTS is not evicted (foreground service should protect it).
9. **[STAGED] Run the accumulated at-the-box feel-test.** The navigation + feed-restructure + UX-config + ticker feel-tests (the sections below) are now runnable on the *MyMTS* box — its permanent home — rather than borrowed `.182`.
10. **Confirm `.182` is unchanged.** WyzeGrid foreground + `WatchdogService` healthy. A lingering MyMTS dev install on `.182` is harmless: kiosk mode there is OFF (default), so MyMTS starts no foreground service and does not autostart on boot. If desired, `adb -s <LAN_IP>:5555 uninstall com.mymts` to remove it entirely — but do NOT touch WyzeGrid.

### Why this is *not* attempted on `.182`

- `.182` is the camera box. A two-foreground-app contention there blinds the operator to the cameras while we test.
- MyMTS's kiosk story tested on a borrowed box doesn't tell us anything useful — different hardware budget, different background pressure (WyzeGrid's WatchdogService present), different physical attached panel. The new box is what it'll ship on.
- The away-from-box + safe-on-`.182` roadmap is complete after Step 4. The kiosk work waits for hardware.

## Navigation chapter feel-test on the remote (staged — operator runs at the box)

### Pre-flight

- **Current state.** MyMTS APK installed on `.182` (`onn-office`); operator at the couch with the Onn remote.
- **Timing constraint.** WyzeGrid reclaims the foreground after ~80 min on `.182` (per the Stage 1 finding; see "WyzeGrid coexistence" below). Plan the session to complete inside that window, OR disable WyzeGrid beforehand per the recipe in the next section — restore at the end either way.
- **Standing rule — unrelated host services.** Never touched. The unrelated host container is never modified, restarted, or reconfigured by this session.

### Checkpoint-1 skeleton — zone movement + no-trap + BACK semantics

For each, focus should be visible as a WyzeGrid-green accent on the active zone (3 dp left-edge bar in feed; 3 dp border on a grid cell; 2 dp border on the ticker).

1. **Feed UP at row 0** → focus to ticker (green border lights the top strip).
2. **Feed DOWN** → next feed row (left accent moves down). Expanded items collapse first.
3. **Feed RIGHT** → focus enters the grid (top-left tile gets a green border).
4. **Feed LEFT** → side menu opens.
5. **Grid UP at top row** → focus to ticker; `lastLowerZone` should be Grid.
6. **Grid DOWN at last row** → stays (no wrap).
7. **Grid LEFT at column 0** → focus spills back to feed at the preserved feed row.
8. **Grid LEFT in interior** → previous cell in the same row.
9. **Grid RIGHT at row's last cell** → stays (no wrap to next row).
10. **Grid RIGHT in interior** → next cell in same row.
11. **Ticker DOWN** → returns to whichever zone the operator came up from (feed if from feed; grid if from grid). Round-trip ticker ↔ grid a few times — it should keep returning to the grid, not drift to the feed.
12. **Ticker UP** → stays.
13. **Ticker LEFT** → side menu opens.
14. **BACK with feed item collapsed** → app handles back (closes / exits). With expanded → collapses first.

### Checkpoint #124 actions — cell → controls, article → expand, ticker pause, menu robustness

1. **Grid cell SELECT.** Focus a grid cell, press OK → `SlotControlsOverlay` opens **without** the side menu sliding in. Channel/Audio/Captions/Close rows. BACK dismisses straight back to the wall (focus returns to that same grid cell).
2. **Feed article SELECT.** Focus a feed item, press OK → row expands in place: title steps up (15 → 18 sp), full summary text appears (no clipping), "OK to collapse · BACK to collapse" hint at the bottom. OK again → collapses; BACK → also collapses.
3. **Ticker SELECT.** Focus the ticker, press OK → marquee stops; a small green "PAUSED" chip appears at the leading edge. OK again → marquee resumes, chip disappears. Navigate away (DOWN to feed/grid), come back UP — the pause state should be **remembered** (preserved across the zone round-trip).
4. **Menu polish.** Open menu (LEFT from any non-modal zone, or KEY_MENU if the remote has it). Pick a slot row → controls open over the menu. Pick "Channel" → picker opens. BACK → returns to controls. BACK → returns to side menu. BACK → menu closes; wall focus is restored to whichever zone was last active. No focus loss.

### Feed restructure

The feed now groups items by source (case-insensitive alphabetical). Each source gets a section header with the source name (uppercase), item count, and a freshness chip. Verify the following within the same at-the-box session as the navigation chapter's feel-test:

1. **Section headers visible.** Feed pane displays a header before each source's items: uppercase source name (e.g., "BBC NEWS", "BLOOMBERG TV"), item count (e.g., "3 items"), and a small freshness chip to the right.
2. **Freshness chip color.** Chip color reflects the source's newest item age: green for items updated in the last 2 hours; amber for 2–12 hours; "not updating" text (warm-colored) when the source is past 12 hours.
3. **Section order is alphabetical and stable.** Sources appear in case-insensitive alphabetical order. Navigate away from the feed and back (e.g., to the grid and back to the feed) — the order should not reshuffle.
4. **Within-section sort is newest-first.** Items under each source appear newest-first (by published time, or fetched time as fallback). Scroll through a populated source section to confirm.
5. **Focus traversal skips headers.** Press DOWN through the feed: focus moves item-to-item, skipping over section headers (headers are visual only, never focusable). Focus should jump from the last item in one section directly to the first item of the next.
6. **Auto-scroll across sections.** With a focused item near the bottom of a section, press DOWN to move to the first item of the next section — the view should scroll to keep the focused row visible, even across section boundaries.
7. **SELECT on focused item.** Focus a feed item and press OK — the row should expand in place (title steps up, full summary appears, "OK to collapse · BACK to collapse" hint shown). Behavior is unchanged from the navigation chapter's feel-test.
8. **BACK collapses.** With an expanded item, press BACK — the item collapses back to summary form (preserved from the navigation chapter).
9. **Channel picker shows group chip.** Open the channel picker overlay (navigate to the grid, press SELECT on a cell for controls, press OK on "Channel"). The overlay header shows "SLOT n · LIVE i/j" (i = current live-channel group position, j = total live channels) or "SLOT n · OFFLINE i/k" with appropriate chip color. Cycling LEFT/RIGHT past the boundary flips the chip (e.g., from "LIVE 4/4" past the last live channel to "OFFLINE 1/k").
10. **Channel picker cycling is robust.** Cycle LEFT/RIGHT repeatedly to wrap between live and offline groups — no crashes, focus stays on the active entry, chip updates correctly each cycle.

### UX & Config (settings overlay)

Verify the live-configurable feed width, font scale, and side-anchor behaviors. Run these checks within the same at-the-box session as the navigation chapter's feel-test:

1. **Settings row visible in menu.** Open the side menu (LEFT from feed in Feed-Left layout). Below the channel slots, a WALL section appears with a "Settings" row.
2. **Settings overlay opens.** Press OK on Settings — a centered popup opens with three rows: Feed width / Feed font / Feed side, each showing the current value.
3. **Feed width cycles.** Focus Feed width and press RIGHT: the feed pane width cycles to the next preset (Default → Wide → Narrow → Default); the grid resizes to fill the rest. LEFT also cycles. SELECT also cycles forward.
4. **Feed font legible at distance.** Focus Feed font and cycle through Small / Default / Large — the feed's title and summary type sizes update live. Smallest preset (0.88×) is still legible from 10 ft; largest (1.18×) fits within the pane without overflow.
5. **Feed side swaps layout.** Focus Feed side and cycle to Feed Right — the feed pane swaps to the right side of the wall; the video grid swaps to the left. The menu side panel re-anchors to the right (slides from the right edge) so it remains on the feed's outer edge.
6. **Navigation mirrors with Feed Right.** With Feed Right active: from feed press LEFT → enters grid (mirror of original RIGHT behavior). From feed press RIGHT → opens the menu. From grid's rightmost column press RIGHT → spills back into the feed. UP from any zone still reaches the ticker; DOWN from the ticker still returns to the lastLowerZone.
7. **Settings overlay dismissal.** Press BACK in the settings overlay → overlay dismisses, side menu remains open. Press BACK again → menu closes; focus returns to the wall in the new orientation.
8. **Persistence across relaunch.** Force-stop the app (or reboot the box) and relaunch → Width/Font/Side values persist from the operator's last session; the wall comes up in the saved orientation.
9. **Focus accent position consistent.** With Feed Right active, verify the visual focus accent on a focused feed item still appears on the row's LEFT edge (the 3 dp WyzeGrid-green bar) — the accent position is the row's inner edge regardless of which side the feed occupies.

### Restore WyzeGrid to camera-box state

`.182` is WyzeGrid's box per Model A. Whether or not you disabled WyzeGrid for the session, end the session by relaunching it:

```
adb -s <LAN_IP>:5555 shell am start -n com.wyzegrid/.MainActivity
adb -s <LAN_IP>:5555 shell dumpsys activity activities | grep -E 'topResumedActivity|com.wyzegrid'
```

Expected: `topResumedActivity = com.wyzegrid/.MainActivity` and the WatchdogService alive. The MyMTS install can remain on the box (harmless; will be evicted by WyzeGrid's watchdog within ~80 min if the operator leaves it).

---

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
