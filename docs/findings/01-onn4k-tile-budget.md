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

_(Empty pending the operator's long soak. Once the long soak completes, the parser output goes here, the interim default in `gradle.properties` is updated if needed, the CHANGELOG gets a "Stage 1 gate cleared" entry, and Stage 2 is unblocked.)_
