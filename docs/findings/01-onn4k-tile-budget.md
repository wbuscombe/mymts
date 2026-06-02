# 01 — Onn 4K Tile Budget

> **Status:** Stage 1 GATE — **PRELIMINARY**. The method is validated and the apparatus is committed. The gate exit criterion ("sustained over multiple hours") was not met in-session because a multi-hour soak does not fit in a single session. The operator runs the long soak using the exact harness below; this doc gets a `## Final` section appended once the long-soak data is in.
>
> **What is committed today:** the method, the apparatus, a 12-minute method-validation run at 6 tiles, the configurable grid default plumbing (`BuildConfig.DEFAULT_MAX_TILES`, sourced from `gradle.properties` → `MYMTS_DEFAULT_MAX_TILES`), and an interim default of **6** pending the long-soak result.

---

## What we are measuring

The largest **sustained** N — number of simultaneously playing live HLS tiles — that the **Onn 4K Streaming Box** holds for multiple hours without:

- slow memory growth crossing into kernel OOM-killer territory,
- decoder failures (errors, fallback churn) past the noise floor we see at small N,
- thermal degradation or per-tile drop rate climbing above ~5%.

This is the **sustained** number, not the instantaneous startup maximum. The dominant failure mode for an ambient TV wall is the one that takes hours to appear (Trust Bar **C4** — runs unattended for a long time).

## Why this matters

The configurable grid default depends on this number. Per the Stage 1 prompt:

> The grid default is set as a configuration value derived from this finding — never a hard-coded constant.

`BuildConfig.DEFAULT_MAX_TILES` is already plumbed (`app/build.gradle.kts` reads `MYMTS_DEFAULT_MAX_TILES` from `gradle.properties`). Until the long-soak result is in, that property defaults to **6**. After the long soak, the property gets updated in a single commit; downstream stages consume `BuildConfig.DEFAULT_MAX_TILES`, never a literal.

## Device facts (Onn 4K Streaming Box, <LAN_IP> and <LAN_IP>)

| Field | Value |
|---|---|
| `ro.product.model` | `onn. 4K Streaming Box` |
| `ro.product.device` | `YOC` |
| `ro.product.cpu.abi` | `armeabi-v7a` (32-bit ARM userspace) |
| `ro.build.version.release` | Android 14 |
| `ro.build.version.sdk` | API 34 |
| `ro.soc.manufacturer` / `ro.soc.model` | `Amlogic` / `AMLS905Y4` |
| `MemTotal` | ~1.97 GB |
| Active hardware video decoder | `c2.amlogic.avc.decoder` (Codec2 AVC/H.264) |

`.158` and `.182` are identical hardware (both `onn_4k_gtv`, both `armeabi-v7a`, both ~1.97 GB).

### Panel-resolution caveat for the long-soak target (`.182`)

`.182` is attached to a small panel reporting `wm size: 1280x720` (`wm density: 213`). **The Onn box itself is a 4K-capable SoC**, but the attached panel is HD. For a leak hunt this is acceptable: decode load is driven by *source* resolution (the HLS variant ExoPlayer selects), not by the panel — the SurfaceView still hands the decoder full-rate frames and the only thing that changes is the final downscale to panel pixels. PSS, decoder selection, dropped-frames, and reconnect behavior are all panel-agnostic.

**What we should NOT conclude from this run, no matter how clean:** that 6 tiles holds at 4K-panel-attached. A separate, shorter confirmatory soak on a 4K panel (or routed through a 4K-capable display) is a future follow-up before the default ships to a box that drives a 4K TV in production. Logged as a Stage 1.x item in `docs/BACKLOG.md`.

## Method

1. Build the debug APK: `./gradlew :app:assembleDebug` (or `./scripts/deploy.sh <device-ip>` to also install and launch).
2. Sideload to the Onn box.
3. Launch in soak mode:

   ```bash
   adb shell am start -n com.mymts/.MainActivity \
       --es mode soak \
       --ei tiles <N> \
       --es pool <live|stable>
   ```

4. Or, in one shot, drive the whole run from the host:

   ```bash
   ./scripts/soak.sh --device <LAN_IP> --tiles <N> --pool live \
       --duration 14400 --meminfo-interval 60
   ```

5. The harness streams two channels of telemetry into `docs/findings/runs/<run-id>/`:
   - `events.log` — every `MYMTS_SOAK` logcat line (TILE_MOUNT, TILE_READY, DECODER, DROPPED, ERROR, BEAT). Pipe-delimited; format pinned by `SoakLog.kt` and asserted by unit test.
   - `meminfo.csv` — periodic `dumpsys meminfo` samples (TOTAL PSS, Java Heap, Native Heap, Graphics, Code, Stack, System).

6. After the run, `./scripts/parse-soak-log.py docs/findings/runs/<run-id>/` emits a markdown summary that goes into the `## Final` section here.

### Fixture pools

`SoakFixtures.kt` ships two pools:

- **`live`** — fixtures validated to actually play on `.182`. Composition is 5 stable + 1 reconnect exerciser (inverts the first-attempt pool's mostly-flaky ratio, which produced "1 active + 5 stale" load on the box and contaminated the slope).
- **`stable`** — Apple / Mux / test-streams.mux.dev test HLS. Useful for apparatus validation.

### Fixture validation (the lesson from the first attempt)

The first long soak burned 24 h on a pool where 3 of 6 URLs HEAD'd 200 from the Mac but **never produced a frame on `.182`**. A HEAD probe from a developer machine is not sufficient — geo, ISP path, CDN-tier policy, ExoPlayer's actual handshake, and the box's userspace can all reject what `curl -I` accepts.

For Stage 1 (and any future fixture change), use the validator on the box:

```
scripts/validate-fixture.sh --device <LAN_IP>:5555 \
    --id <id> --label "<label>" --url "<url>" --duration <s>
```

It launches a single-tile soak with that URL (via `--es url`/`label`/`id` intent extras the harness reads as an ad-hoc fixture override), sleeps `<s>`, parses `MYMTS_SOAK` telemetry, and prints one CSV row:

```
id,duration_s,tile_ready,decoder,dropped,errors,last_frame_age_ms,state,playing,pos_advance_ms
```

The real liveness signal is `playing=true AND (pos_advance_ms > 0 OR a live HLS at the live edge)`. `state=LIVE` alone is **not enough** — the underlying `StreamPlayer.state` reports ExoPlayer's `playWhenReady`/`playbackState`, which can be `LIVE` with no frames actually rendering (the C3 bug filed in `docs/BACKLOG.md`).

For each new candidate: 60–90 s smoke first, then a 5+ min sustained re-check on survivors. Anything that fails the smoke or decays in the sustained run does not go in the pool.

### Validation results (2026-05-31, `.182`)

`docs/findings/validation/candidates-20260531-1743.csv` + `sustained-20260531-2159.csv`.

**Passed both smoke (75 s) and sustained (5 min) on `.182`:**

| id | source | composition role |
|---|---|---|
| `redbull-tv` | https://rbmn-live.akamaized.net/.../master.m3u8 | live broadcast |
| `dw-news-en` | https://dwamdstream102.akamaized.net/.../index.m3u8 | live broadcast |
| `apple-bipbop-adv` | https://devstreaming-cdn.apple.com/.../master.m3u8 | multi-variant VOD (sustained) |
| `akamai-bbb` | https://test-streams.mux.dev/test_001/stream.m3u8 | single-variant VOD |
| `mux-x36xhzz` | https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8 | multi-variant VOD (drop counter climbed continuously in prior 87-min partial soak) |
| `unified-tears` | https://demo.unified-streaming.com/.../tears-of-steel.ism/.m3u8 | ~12 min VOD-as-live (reconnect exerciser) |

**Failed smoke on `.182` (`ERROR_CODE_IO_BAD_HTTP_STATUS`, do not silently re-add):**

`nasa-public` (`ntv1.akamaized.net`), `nasa-media` (`ntv2`), `moctobpltc-eight`, `france24-en` (two URLs), `nhk-world`, `al-jazeera-en`, `sky-news`, `tv5monde`, `abc-news-au`. Almost certainly geo-restricted to non-US networks or auth-tier on this CDN path.

### Pass criteria for the long soak (proposed)

The long soak passes for a given N if:

1. Duration ≥ 4 hours (8 hours preferred — slow leaks tend to surface in the 4–8h window).
2. Total PSS shows no monotonic growth beyond noise after the first 10 minutes. A linear regression of PSS over the post-stabilization samples has slope ≤ ~50 KB/min.
3. No `ERROR_CODE_DECODER_INIT_FAILED` or `OUT_OF_MEMORY`-class errors.
4. Per-tile dropped-frame rate < 5% over the whole run (`scripts/parse-soak-log.py` reports per-tile counts; divide by `30 fps × duration`).
5. At least one tile remained `state=LIVE` at the end.

If N passes, the long-soak default becomes N. If multiple N values pass, the highest passing N is the budget; the default ships at **N** for the TV-mode grid and the user-configurable ceiling becomes **N** as well (operators with similar hardware can raise it later via the gradle property; re-homing to higher-capacity hardware later does not require a code change).

If even N=2 fails, MyMTS does not run on the operator's current Onn 4K box and the soak finding documents that result instead.

## In-session method-validation run

Run id: `20260530-method-validation-6t-stable`
Device: `<LAN_IP>:5555` (Onn 4K)
Tiles: **6** · Pool: `stable` · Resolution hint: `auto`
Started: `2026-05-30T20:23:31Z` · Duration: **720 s (12 minutes)** · meminfo interval: 30 s

### Memory (19 samples)

| metric | first | last | min | max | median | delta first→last |
|---|---|---|---|---|---|---|
| Total PSS | 10.5 MB | 102.2 MB | 10.5 MB | 104.9 MB | 103.5 MB | +91.7 MB |
| Java Heap | 5.8 MB | 23.1 MB | 5.8 MB | 25.4 MB | 25.1 MB | +17.3 MB |
| Native Heap | 1.2 MB | 14.3 MB | 1.2 MB | 15.9 MB | 14.4 MB | +13.2 MB |
| Graphics | 0.0 MB | 4.6 MB | 0.0 MB | 4.6 MB | 4.6 MB | +4.6 MB |

The "first" row is the pre-mount cold-start sample taken before the first tile rendered. The legible **post-stabilization** picture is the **median vs max** comparison: PSS stays inside a tight ~2.7 MB band around 103.5 MB, with no monotonic upward drift visible in 12 minutes. Java + Native + Graphics together (~42 MB) explain less than half of PSS — the remainder is `Code`, `Stack`, shared libraries, and the per-tile graphic buffers held by SurfaceFlinger.

12 minutes is **far from the multi-hour binding test**. This run only proves the method works and that 6 tiles is not catastrophic on this hardware; it does not prove 6 tiles holds for 4+ hours.

### Events

- `START`: 1
- `TILE_MOUNT`: 6 (all six tiles mounted)
- `TILE_READY`: 313 (the stable pool emits frequent variant switches; each re-renegotiation produces a TILE_READY)
- `DECODER`: 6 (one per tile; all selected `c2.amlogic.avc.decoder`)
- `DROPPED`: 104 windows
- `ERROR`: 2 (`ERROR_CODE_BEHIND_LIVE_WINDOW` on both Mux fixtures — expected, since Mux's test stream is a finite VOD; the player correctly transitions to `RECONNECTING` rather than freezing on the last frame, which is exactly the Trust Bar **C3** behavior: never silently pretend stale is live)
- `BEAT`: 138 (heartbeat every 30 s × 6 tiles for ~12 min = 144 — matches within timing noise)

### Per-tile dropped frames (over 12 min)

| Tile | Dropped | Rough rate |
|---|---|---|
| `akamai-bbb-2` | 162 | ~0.75% (at ~30 fps × 720 s) |
| `akamai-bbb-5` | 165 | ~0.76% |
| `apple-bipbop-adv-0` | 322 | ~1.49% |
| `apple-bipbop-adv-3` | 361 | ~1.67% |
| `mux-test-1` | 36 | ~0.17% (lower because the VOD ended early; player went to RECONNECTING) |
| `mux-test-4` | 31 | ~0.14% |

All under the proposed 5% threshold. The apple-bipbop fixture is multi-variant adaptive bitrate — its higher drop rate is the cost of variant switching, not box capacity exhaustion.

### What the validation run tells us

- The apparatus works end-to-end: install → launch → telemetry → parse → summary.
- The Amlogic AVC hardware decoder is selected for every tile (a soft renderer would have shown up as `c2.android.avc.decoder` or worse, and would be a budget red flag).
- 6 tiles on stable pool, 12 minutes, was uneventful at PSS ~100 MB on a 1.97 GB box. Plenty of headroom for that duration; it tells us nothing about the next 3 hours and 48 minutes.
- The 12-minute meminfo trace shows no leak signal in that window. Slow leaks that the C4 long-uptime requirement cares about live in the 1–8h regime, so absence-of-leak here is method-validation, not gate-clearing.

## Interim default (until the long soak lands)

`gradle.properties: MYMTS_DEFAULT_MAX_TILES=6`

Rationale for 6 as interim:

- Matches the planned TV-mode grid shape (2×3 = 6 tiles, from the prior web-app v1.1 design that survived the architecture pivot).
- The 12-min validation suggests 6 is sustainable in the short term and the box has headroom.
- It is **a value the long soak can validate or revise downward, not upward**, so shipping at 6 today does not bake a number we will have to walk back.
- The default is a config value, never a constant. Re-homing later or raising the ceiling later is a one-line property change.

The user-configurable ceiling is **not** set today and will land with Stage 5 (settings). The Stage 1 commitment is the plumbing.

## The long soak — exact commands

The operator runs these. The harness is committed; the data goes into a new run directory under `docs/findings/runs/`; the parser drops a summary into the `## Final` section of this file.

```bash
# 1. Build a fresh debug APK so versionName tracks the latest tag.
#    (Run from the repo root.)
./gradlew :app:assembleDebug

# 2. Run the long soak. 4 hours minimum; 8 hours preferred.
#    --pool live uses the LIVE fixture pool. If those URLs decay, edit
#    SoakFixtures.LIVE and re-run; don't drop fixtures silently.
./scripts/soak.sh \
    --device <LAN_IP> \
    --tiles 6 \
    --pool live \
    --duration 14400 \
    --meminfo-interval 60 \
    --run-id "long-soak-6t-live-$(date +%Y%m%d)"

# 3. After it finishes, parse + paste the markdown into this file's
#    ## Final section.
./scripts/parse-soak-log.py docs/findings/runs/long-soak-6t-live-<date>/

# 4. If the run passed at N=6, no code change is needed beyond
#    appending the ## Final section and updating the CHANGELOG.
#    If the run failed, lower MYMTS_DEFAULT_MAX_TILES in
#    gradle.properties to a number the next soak passes at, and
#    keep going until a default holds.
```

## What this gate does not block

Stage 2 (the helper's real aggregation + resolution) can begin **only once** the `## Final` section below is filled in and committed. Until then, Stage 1 is the active stage.

## Final

**Closing position, decided 2026-06-01:** the leak question is answered (no leak). The capacity question is not yet answered, because three soaks have shown the 6-tile load can't be sustained from the current fixture pool + the current player code. The capacity re-soak is deferred into Stage 2 — running it again now would just produce a fourth degenerate run.

> **Updated 2026-06-01 (Stage 2 Part C bracket).** The capacity question IS now answered. See `## Stage 2 Part C — Escalating-probe bracket` below. The number this device sustains is **N=4**. The interim default has been moved to 4 (long-soak confirmation in progress).

### Leak behavior — **PASS**

Best evidence: v3 (`long-soak-6t-live-upstairs-v3-20260531-2229`), the longest clean capture.

- Duration: **11.64 h** (started `2026-05-31 22:29:10 PDT`, terminated cleanly `2026-06-01 10:07:36 PDT` by operator decision).
- Samples: **691 meminfo samples** at 60 s cadence (`-t 30` dumpsys timeout + 3× retry + `pidof` liveness fallback, per `scripts/soak.sh` after commit `a4619af`).
- Post-warmup samples (≥10 min in): **681** over 11.5 h.

| metric | first | last | min | max | median |
|---|---|---|---|---|---|
| PSS post-warmup | 123.7 MB | 123.8 MB | 114.3 MB | 142.0 MB | 124.0 MB |

**PSS slope post-warmup: −15.97 KB/min over 688 min**, well within the ±50 KB/min threshold. The slope is mildly *negative*, not positive — no upward creep over 11+ hours. Per-hour median PSS settles in the 120–124 MB band by hour 3 and stays there. No OOM kill, no decoder-init failure, no process restart (`pidof` returned 25273 for the entire run).

**Verdict on leak behavior: the app does not leak memory over long uptime at this workload.**

### 6-tile sustained capacity — **NOT YET MEASURED**

The leak number above is real, but it is measured against a **degenerate load**: five of six tiles fell over within 5–8 minutes of v3's launch and stayed dead for the remaining ~11.5 hours. The PSS budget being held by the surviving load is therefore much closer to "1 active tile + 4 frozen + 1 honestly-reconnecting" than the 6-active-tile workload the gate requires.

Per-tile evidence (v3, full run):

| Tile | First `TILE_READY` | Last `TILE_READY` | Time to silent death | Final state |
|---|---|---|---|---|
| `redbull-tv-0` | `22:29:20` | `22:34:15` | **~5 min** | `RECONNECTING` (honest — `ERROR_CODE_BEHIND_LIVE_WINDOW` × 1; never recovered) |
| `apple-bipbop-adv-2` | `22:29:22` | `22:34:55` | **~6 min** | `state=LIVE`, `playing=false`, no frames for 10.1 h (silent stall) |
| `akamai-bbb-3` | `22:29:20` | `22:35:21` | **~6 min** | `state=LIVE`, `playing=false`, no frames for 10.1 h (silent stall) |
| `mux-x36xhzz-4` | `22:29:19` | `22:35:21` | **~6 min** | `state=LIVE`, `playing=false`, no frames for 10.1 h (silent stall, dropped counter frozen at 7131) |
| `unified-tears-5` | `22:29:24` | `22:37:04` | **~8 min** | `state=LIVE`, `playing=false`, no frames for 10.1 h (silent stall) |
| `dw-news-en-1` | `22:29:46` | `06-01 10:08:01` | **still rendering at termination** | `state=LIVE`, `playing=true`, fresh frames; cumulative `TILE_READY` = 341,634 (~8.2/s sustained — pathological rebuffer/variant-switch churn worth understanding in Stage 2) |

Three soaks in a row showed this same shape (v1: 87 min until WyzeGrid-displaced kill — 1 active + 5 broken; v2: 1 min false-start from a host-side dumpsys timeout; v3: 11.5 h — 1 active + 4 silently stale + 1 honestly reconnecting). This is a pattern, not a streak of bad luck.

### Why the capacity number can't be obtained yet — two upstream blockers

1. **Fixtures.** The validated pool was still mostly VOD-as-live test assets (`apple-bipbop-adv`, `akamai-bbb`, `mux-x36xhzz`, `unified-tears`) that fall off the live window under concurrent load and never re-enter. Of the real news streams probed on `.182`, only Red Bull TV and DW News English play, and Red Bull went `BEHIND_LIVE_WINDOW` 5 min in. **The work of curating real, sustained streams is the helper's job in Stage 2** (`04-TECHNICAL-APPROACH.md §2 Piece 2`), not the TV app's. Until the helper exists, there is no honest fixture pool to soak against.

2. **Player non-recovery + Trust Bar C3 dishonesty.** When a tile stumbled into `BEHIND_LIVE_WINDOW`/dropout territory, `StreamPlayer` did not genuinely recover, and reported `state=LIVE` while no frames rendered for **10+ hours**. This is a direct violation of Trust Bar **C3** ("staleness is never silent") and is what allowed the four stalled tiles to "look fine" to the harness state machine. **The fix is required Stage 2 work** — see `docs/STAGE-2-PLAN.md §"Player robustness (required)"`.

Both blockers are Stage 2 work that is already named in the architecture and is now the entry point for that stage. The capacity re-soak runs once both are in place; it is item C in that plan.

### What was actually validated in Stage 1

- Toolchain pinned and reproducible; both skeletons build, install, and run.
- Soak harness (in-app telemetry + host-side runner + parser) works end-to-end across three runs.
- WyzeGrid coexistence is a real production-deployment concern, characterized, and recorded.
- Memory behavior of the app over long uptime: clean.
- The `state=LIVE` surface-honesty bug is reproducible and load-bearing on the capacity measurement.
- Fixture validation method (`scripts/validate-fixture.sh` + on-device beats with `playing` + `pos_ms`) catches what HEAD-probes miss.

### Run-artifact retention policy (decided here)

`docs/findings/runs/**/events.log` files are large (v3's was 33.8 MB) and **regenerable from the harness**. Going forward they are gitignored. The per-run record is kept by committing the small files: `meta.json`, `end.json`, `runner.log`, `meminfo.csv`, `device.txt`. The finding doc carries the parsed summary (decoder + per-tile counts + slope) — that's the durable evidence.

The v3 run directory is retained on this commit (small files only). The v1 (`long-soak-6t-live-upstairs-20260530/`) and v2 (`…-v2-…`) directories are discarded — they were superseded and adding them would add cruft without telling the story any better than the prose above already does.

---

## Stage 2 Part C — Escalating-probe bracket (2026-06-01)

> **Conditions changed since the closing position above:** the helper (Part A) now resolves real, manifest-verified live streams via `/api/channels`, and the player (Part B) is honest about staleness and recovers within a bounded window. The two contaminating factors from Stage 1 are gone — the bracket below measures genuine decoder load.

### Setup

- Device: `<LAN_IP>:5555` — Onn 4K Streaming Box, Amlogic AMLS905Y4, `armeabi-v7a`, Android 14, ~1.97 GB RAM. **WyzeGrid disabled** for the probe window (`pm disable-user com.wyzegrid`); to be re-enabled at Stage 2 closeout.
- Helper at `<LAN_IP>:8091`. Live channel pool: `dw-news-en`, `redbull-tv` (2 helper-verified-live; 5 other international news endpoints failed with the Stage 1 `IO_BAD_HTTP_STATUS` / DNS pattern from this network path). Tiles cycle these 2 URLs to fill N.
- Apparatus: `scripts/probe-tile-count.sh` — multi-URL ad-hoc mode (Stage 2 addition to `MainActivity` reads `--es urls`/`labels`/`ids`). Runs 8-min sweeps at each tile count. State-machine telemetry per tile in `summary.json`.

### Sweep results (8 min each, 60 s meminfo cadence)

| N | reached LIVE | dead | PSS median (MB) | Per-tile health |
|---|---|---|---|---|
| 1 | 1/1 | 0 | 88.9 | dw-news-en-0: 60 ready, 0 drops — **HEALTHY** |
| 2 | 2/2 | 0 | 98.2 | dw + redbull: 60 ready each, 0 drops — **HEALTHY** |
| 3 | 3/3 | 0 | 105.6 | all 3 tiles: 60 ready each, low drops — **HEALTHY** |
| 4 | 4/4 | 0 | 112.3 | all 4 tiles: ~60 ready each, low drops, no recovery strikes — **HEALTHY** |
| 5 | 5/5 | 0 | 134.5 | **dw-news-en tiles**: only 4 ready each, **~5,300 dropped frames each** (≈ 37% drop rate at 30 fps over 8 min). **redbull-tv tiles**: 26–30 ready, low drops. **DEGRADED** — well above the 5% drop-rate threshold. |
| 6 | 3/6 | 0 | 129.5 | **dw-news-en tiles**: 0 first-frame renders, ~2,300 drops each. **redbull-tv tiles**: 10–14 ready, 1–5 drops, **but every redbull tile fired 2–3 `RECOVERY PREPARE` strikes** during the window. **DEGRADED + recovery**. |

`dumpsys` itself was failing to complete at N=5 and N=6 within the 30 s timeout (only 3 of 8 and 2 of 8 expected meminfo samples landed), which is itself a strong signal that `system_server` is under heavy contention at those tile counts — independent evidence that the decoder budget is overrun.

### Bracket verdict

- **Sustainable ceiling = N=4.** Highest tile count where all tiles stay LIVE with healthy drop rate and no recovery strikes.
- N=5 is the first count where dw-news-en tiles drop ~37% of frames. The state machine still reports LIVE (because `onDroppedVideoFrames` callbacks count as "decoder is alive") but the surface is effectively dropping every other frame; the user would see a stutter, not a freeze.
- N=6 is the first count where the recovery ladder begins firing: every redbull-tv tile got 2–3 PREPARE strikes inside an 8-min window, meaning the decoder fell behind enough to cross the 15 s staleness threshold.

`MYMTS_DEFAULT_MAX_TILES` in `gradle.properties` is set to **4** with the rationale embedded as a comment, and a long-soak run is currently confirming the bracket.

### dw-news-en — answer to the Part B question

Stage 1 v3's reported 8.2 / s `onRenderedFirstFrame` rate was almost certainly a **6-tile contention effect**, not a property of the stream itself. Evidence:

- N=1 solo: 60 ready over 480 s = ~0.13 / s (matches Part B's 180 s solo measurement of 0.12 / s).
- N=4 (healthy): 60 ready per tile, same ~0.13 / s per-tile rate.
- N=5 (degraded): drops to 4 ready per dw tile in 480 s — variant-switching has nearly stopped because the decoder is too busy dropping frames to renegotiate.
- N=6: 0 ready per dw tile — the decoder never even completed first-frame render for the dw tiles.

The Stage 1 8.2 / s figure (~341k events / 11.5 h) was the *aggregate* of dw plus the cycle of failing tiles dragging the decoder through repeated rebuffers. As a fixture under healthy load, dw-news-en behaves consistently with redbull-tv.

### Buffer-floor decision (deferred from Part B)

`StreamPlayer` continues to use Stage 1's `LoadControl`:
- `bufferDurations(1500, 4000, 500, 1500)` and `targetBufferBytes(4 MB)`.

**Decision: keep the floor as-is for Stage 2.** The N=4 ceiling holds with these values and the dw-news-en LIVE↔STALE oscillations are transient (~75 ms median resolution) and add zero recovery strikes. Loosening the floor to 3–4 s would add ~1.5–2.5 MB per tile of decoder buffer, and at N=4 that doesn't matter — but the ceiling sensitivity to memory is unverified, and changing two variables at once (floor + tile count) would muddy the signal. If a future stage tightens the per-tile memory budget for any other reason, revisit. Recorded as a Stage 3+ option in `docs/BACKLOG.md`.

### The escalating probe as a repeatable procedure (the portability deliverable)

Per-device profile recipe for any future box:

```bash
# 1. Disable any competing kiosk app on the box (e.g. WyzeGrid).
adb -s <box>:5555 shell pm disable-user --user 0 com.wyzegrid

# 2. Ensure the helper has at least two helper-verified-live channels
#    available (the prober's status=live filter is the gate).
curl -fsS http://<LAN_IP>:8091/api/channels | jq '.channels[] | select(.status=="live") | .slug'

# 3. Install the current debug APK on the box.
./scripts/deploy.sh <box-ip>

# 4. Run the escalating sweep. The script self-stops at SETTLE_DEAD;
#    feed it counts in order and stop one short of any N where
#    `reached_live < N` or any tile drops >5% of frames.
TS=$(date +%Y%m%d-%H%M)
for N in 1 2 3 4 5 6; do
    ./scripts/probe-tile-count.sh --device <box-ip>:5555 \
        --tiles "$N" --duration 480 --run-id "probe-$TS-${N}t" \
        --no-install
done

# 5. Read each summary.json. The sustainable ceiling is the highest N
#    where all tiles reach + stay LIVE with healthy drop rates and zero
#    recovery strikes. Set MYMTS_DEFAULT_MAX_TILES in gradle.properties
#    to that number for this device's build profile.

# 6. Long-soak (4–8 h minimum) at the bracket. PSS slope ≤ ±50 KB/min
#    post-warmup, no decoder-init failures, drop rate < 5%, no DEAD
#    tiles, no sustained STALE state.

# 7. Re-enable WyzeGrid (or whatever was disabled in step 1).
adb -s <box>:5555 shell pm enable com.wyzegrid
```

The S905Y4 / Onn 4K Streaming Box device profile result: **N=4**. The Vision anticipates fresh dedicated hardware; the Onn stick's profile is one device profile, and the *procedure* is the durable artifact.

### Long-soak in flight

Run id: `long-soak-4t-20260601-2100`. 4 tiles, 6 h, helper-resolved live channels (cycled `dw-news-en` × 2 + `redbull-tv` × 2). Launched via `caffeinate -i nohup` so it survives this session. Early-render check confirmed all 4 tiles reached LIVE within 90 s of launch. ETA `2026-06-02 ~03:00 PDT`. Results land in a follow-up commit / next session.

WyzeGrid stays disabled until the long soak completes; the closeout session re-enables it.
