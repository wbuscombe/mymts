# MyMTS performance analysis — Android wall + web client (2026-06)

**Read-only measurement pass.** Nothing was changed, optimized, built, or deployed. This
document is the input to a follow-up research/optimization phase. Android numbers are live
read-only `adb` diagnostics (`dumpsys`/`getprop`/`/proc`/`/sys`/`top`/`logcat -d`) against the
**currently-installed** build on the `.92` Onn box under load; web numbers are code/architecture
analysis plus a DevTools checklist for the operator to fill in (real browser GPU-decode numbers
can't be gathered headlessly).

> **On resolution:** the per-tile variant is **1280×720 @ 30fps** and is catalogued below as
> evidence. Per the operator, resolution is presented as **ONE lever among many, not the
> headline**. The data points first at **how many streams decode at once** and the **per-tile
> overhead** (audio decode on muted tiles, decoder re-init churn, thread/GC/composition cost) —
> levers that don't touch picture quality.

Profiled state: app `com.mymts/.MainActivity` foreground, **4 active video tiles (slots 0–3)**,
the in-app soak harness running (it is the source of the `MYMTS_SOAK` telemetry below). Captured
2026-06-16, box uptime ~38 h.

---

## 1. Android app — the `.92` Onn box

### 1.1 Hardware / OS facts (platform constraints)

| Fact | Value |
|---|---|
| Device | onn 4K Streaming Box (Google TV), board `s4` |
| SoC | Amlogic **S905Y4** — quad-core **Cortex-A35**, max **2.004 GHz** (all cores) |
| GPU | Mali (Amlogic S4 class) |
| RAM | **1.97 GB** total (~720 MB available; see memory pressure below) |
| ABI | **`armeabi-v7a` (32-bit only)** — the GTV image carries no arm64; the app runs 32-bit |
| OS | Android **14** (API 34) |
| Panel output | **1920×1080 @ 60 Hz** (the "4K" box is outputting 1080p; 720p modes also available) |
| Networking | **Wi-Fi only** (Realtek SDIO), 802.11ac, 5 GHz, strong signal |

Platform takeaways (not "fixable" but they frame everything): a **quad A35 is a low-power
in-order-ish core** (~efficiency class, not performance); **2 GB RAM** is tight for a 24/7
multi-decode workload; **32-bit** means older NEON and no arm64 codegen; the panel runs **1080p**
so 720p tiles are upscaled by the display pipeline.

### 1.2 Decode path — **HW-decode confirmed, no SW-video fallback in effect**

- Every video tile decodes on the **hardware** Amlogic decoder: `c2.amlogic.avc.decoder`
  (H.264/AVC). **No tile is on a software video decoder** (`c2.android.*`) at capture time — good.
- Per-tile variant (evidence, not a directive): **1280×720, 30 fps, H.264**, one ABR switch
  observed (`resolution-change-count=1`).
- **Audio is software-decoded:** `c2.android.aac.decoder` runs inside `media.swcodec`.
- Codec input→output latency on the HW decoder ran **high** in `media.metrics`: avg ≈ **260 ms**,
  max ≈ **400 ms**, min ≈ 60–115 ms (per-frame budget at 30 fps is 33 ms) — consistent with a
  CPU-contended box where the decode service can't keep buffers flowing smoothly.
- **Risk flag (code):** `DefaultRenderersFactory.setEnableDecoderFallback(true)` is set
  (`StreamPlayer.kt:196`). Under HW-decoder exhaustion a tile could **silently** fall back to a
  software video decoder — catastrophic on this SoC. Not happening now, but the door is open as
  tile count climbs.

### 1.3 Rendering / composition path — **SurfaceView + HWC overlay (GPU offloaded)**

- Tiles are **SurfaceView** (BLAST) — confirmed in `dumpsys SurfaceFlinger` (dedicated
  `SurfaceView[com.mymts…](BLAST)` layers per tile) — the correct choice (each tile is its own
  SF layer, not drawn through the app's GL surface).
- Composition: **5 layers `composition=DEVICE (2)`** = **hardware composer (HWC) overlays**, not
  GPU/CLIENT. The video planes are composited in hardware, **offloading the GPU**.
- GPU render time is correspondingly **low**: `gfxinfo` GPU percentiles 50th **7 ms**, 90th 14 ms,
  95th 17 ms, 99th 24 ms.

### 1.4 Render jank — **UI-thread / draw-command bound, not GPU bound**

`dumpsys gfxinfo com.mymts` (cumulative over the ~38 h run):

| Metric | Value |
|---|---|
| Total frames | 1,305,829 |
| **Janky frames** | **428,509 (32.8%)** |
| Frame time 50th / 90th / 95th / 99th | **36 ms** / 73 ms / 85 ms / 121 ms |
| Missed Vsync | 164,006 |
| Slow UI thread | 220,210 |
| Slow issue draw commands | 422,040 |
| Slow bitmap uploads | 121,589 |
| GPU 50th / 99th | 7 ms / 24 ms |

The **median frame is 36 ms** (over the 16.7 ms/60 Hz budget; over even the 33 ms/30 Hz budget),
the histogram piles up at **32 ms and 48 ms** (2× and 3× the 60 Hz budget) — but **GPU work is
only ~7 ms**. So the cost is in **issuing draw commands / the UI (main) thread**, i.e. CPU and
the Compose pipeline contending with the decode/composite load — **not** the GPU.

### 1.5 Memory — app is modest; the **system** is under pressure

`dumpsys meminfo com.mymts`: **TOTAL PSS ≈ 150 MB** (RSS ≈ 276 MB).

| Component | PSS |
|---|---|
| Native heap (decode buffers, Media3 native) | **~51 MB** |
| Dalvik heap (Java/Compose) | ~41 MB |
| Graphics / GL mtrack | ~25 MB |
| Stack / Ashmem / Unknown | ~30 MB |

150 MB for a 4-tile wall is **not** the headline. But **system-wide** RAM is tight: **1.82 GB of
1.97 GB used, ~337 MB swapped** (zram), ~150 MB free. Scaling tiles raises native+graphics heap
and pushes the box deeper into swap.

**GC:** `com.mymts` runs background concurrent GCs **freeing ~20–25 MB every 1–2 s** (600k+
objects per cycle). Pauses are small (sub-10 ms, concurrent copying), so GC isn't *directly*
janking frames — but the **allocation churn keeps GC threads busy**, burning CPU on a box that has
none to spare. Likely sources: Compose recomposition of feed/ticker/tiles + per-event telemetry
string building.

### 1.6 CPU + thermal — **the headline: CPU-bound, ~3× oversubscribed**

`top` / `/proc/loadavg` / cpufreq:

- **Load average 12.6 / 11.9 / 11.7 on 4 cores** — the run queue sits at ~**3× core count**,
  sustained across 1/5/15 min.
- **All 4 cores pinned at max 2.004 GHz** (governor `schedutil` at the ceiling). `iow = 0%` — this
  is genuine **CPU** demand, not I/O wait. (Instantaneous idle was ~174%/400% at one sample, i.e.
  bursty — the box isn't pegged every millisecond, but the sustained queue is deep.)
- Per-process CPU under load:

| Process | %CPU (of one core) | Cumulative CPU (of ~38 h) | What it is |
|---|---|---|---|
| **com.mymts** | **~93%** | **~32 h** (~0.84 core 24/7) | the app: Compose UI + 4 players + tick |
| `mediacodec` (c2 HW service) | ~32% | ~22 h | HW-decode buffer orchestration |
| **`media.swcodec`** | **~16%** | ~13 h | **SW AAC audio decode (all 4 tiles)** |
| `surfaceflinger` | ~11% | ~8 h | composition |
| `composer` (HWC HAL) | ~6.5% | ~4 h | hardware composer |
| `irq/48-vdec-1` + `vdec-core` | ~9% | ~5 h | HW video decoder IRQ/core threads |
| Wi-Fi (`RTW_RECV_THREAD`, `ksdioirqd`) | ~5% | — | SDIO Wi-Fi RX |

- **Thermal:** `soc_thermal = 58.2 °C`, status **0 = no throttling**. First throttle threshold is
  **65 °C** (then 75/95/105/120). So **~7 °C of headroom** and **not throttling now** — but a 24/7
  box in a warm spot could cross 65 °C and begin frequency mitigation (cooling devices
  `cpufreq-cpu0`/`gpufreq` are at state 0 now). Thermal-over-hours is an open question (§5).
- **100 threads** in the app process — 4 players × several Media3 threads each + Compose + OkHttp
  + tick handlers — context-switch overhead on 4 cores.

### 1.7 Network — **plentiful; not the constraint**

- Link: **802.11ac, 866 Mbps Tx / 585 Mbps Rx, RSSI strong, 5 GHz.**
- Average RX over the run: ~**20 Mbps** (≈ 346 GB over ~38 h) — i.e. **~2.3% of link capacity**
  for 4×720p H.264 plus reconnect re-fetches. `rx_errors` negligible, `drop=0`.
- Cost is the **CPU of Wi-Fi RX** (~5%, SDIO), not bandwidth.

### 1.8 Stability / steady-state — **tile churn (a cost AND a reliability signal)**

The app's own `MYMTS_SOAK` telemetry (`logcat -d`) shows at least one tile **not stably playing**:

```
STATE from=RECOVERING to=LIVE          (decoder reinit, init_ms=254)
STATE from=LIVE       to=STALE         (~15 s later)
RECOVERY attempt=1 kind=prepare
STATE from=RECOVERING to=STALE
RECOVERY attempt=2 kind=reinit  →  DECODER c2.amlogic.avc.decoder init_ms=254  →  LIVE  →  …
```

- A tile cycles **LIVE → STALE (after ~15 s) → RECOVERING → full decoder re-init (~254 ms) → LIVE**
  and repeats. The 15 s matches the code's `staleThresholdMs = 15_000` (`StreamPlayer.kt:53`): if
  a frame-arrival signal doesn't land within 15 s, the tile goes STALE and the recovery ladder
  (`prepare → reinit → dead`) fires.
- `EV=DROPPED` events (`dropped=1…10` over ~1.6–1.8 s windows) and **77 `TILE_READY` events** in
  the buffer confirm frequent churn.
- Each `reinit` **releases and recreates an ExoPlayer + re-inits a HW decoder** ("Discard frames
  from previous generation" / stale-buffer churn in the codec logs) — a real, repeated CPU cost.
- **Hypothesis:** the churn is partly a **symptom of CPU saturation** — when 4 HW decodes + the UI
  thread + GC + Wi-Fi contend, a tile can miss its 15 s frame-arrival window and trip recovery,
  which costs *more* CPU, feeding back. Distinguishing "flaky upstream stream" from "starved on
  the box" needs per-tile correlation over time (§5).

### 1.9 ExoPlayer / Media3 config (code — `StreamPlayer.kt`)

- **LoadControl already tight** (real-time-first, post the live-edge work): MIN 1500 / MAX 3000 /
  forPlayback 500 / forRebuffer 1500 ms, `targetBufferBytes 4 MB`, `backBuffer 0`. Little to
  reclaim here without hurting currency.
- **LiveConfiguration:** target offset 4000 ms, micro-correction speed window 0.97–1.03, plus a
  2 s-tick **live-edge watchdog** that seeks to live past 8 s drift. Sound; not a hotspot.
- **Renderers:** `DefaultRenderersFactory`, extension renderers OFF, **decoder fallback ON** (the
  SW-video-fallback risk flagged in §1.2).
- **TEXT renderer disabled** by default (`setTrackTypeDisabled(TRACK_TYPE_TEXT, true)`) — captions
  aren't decoded unless toggled. Good.
- **AUDIO is NOT disabled on muted tiles.** `setAudible(false)` sets `exo.volume = 0f`
  (`StreamPlayer.kt:274,324`) — that **attenuates output but keeps the audio renderer/decoder
  running**. The wall is muted-by-default with at most one audible tile, so **3–4 AAC streams are
  decoded that nobody hears** — this *is* the `media.swcodec` ~16% CPU (§1.6). The cleanest
  non-resolution lever on the board.
- Per-player **2 s tick on the main thread** × 4 — cheap, but it is main-thread work that adds to
  the UI-thread budget (§1.4).

### 1.10 Ranked cost-drivers (Android) — with candidate **non-resolution** levers

1. **Simultaneous decode + the whole-pipeline CPU it pulls (dominant).** 4 HW decodes drag the
   c2 service (32%), vdec IRQ (~9%), SF/HWC (~17%), and the app's own render/marshalling — summing
   to a **~3× oversubscribed** 4×A35. Levers: **fewer concurrent tiles** (product/UX lever — the
   most direct, but the wall *is* the product); **per-tile overhead reduction** (items 2–5);
   **resolution/variant** (one lever, see §4).
2. **Audio decode on muted tiles (~16% CPU, code-confirmed).** Disable the AUDIO renderer on
   non-audible tiles; decode audio only for the single audible tile. No picture-quality impact.
3. **Decoder re-init churn / tile instability.** Repeated 254 ms HW-decoder re-inits from the
   LIVE↔STALE↔RECOVERING loop. Levers: confirm whether it's upstream-flaky vs box-starved; the
   recovery ladder's thresholds/backoff; reducing the saturation that trips it.
4. **UI-thread / draw-command cost + GC churn.** Median frame 36 ms with GPU only 7 ms → the cost
   is CPU-side draw issuing + ~20 MB/1–2 s allocation. Levers: Compose recomposition scope /
   stability audit, allocation reduction (telemetry string churn, per-frame allocs), fewer
   main-thread ticks.
5. **Thread count (100) + 32-bit + thermal headroom (~7 °C).** Context-switch overhead; no arm64
   codegen; thermal could begin mitigating over hours. Levers: thread-pool consolidation; long-run
   thermal sampling to see if throttling ever engages.
6. **Network:** not a cost-driver (2.3% of link); only the ~5% Wi-Fi-RX CPU is notable — a wired
   adapter would move that off SDIO (hardware lever, out of scope but worth noting).

**Resolution/variant (720p30) is catalogued as ONE lever (§4), de-emphasized per the operator.**

---

## 2. Web client (browser)

Real GPU-decode numbers need a real browser + real streams (headless can't represent them), so
this is architecture/config analysis + a DevTools checklist for the operator (§2.4).

### 2.1 hls.js architecture / config (code — `web/js/video.mjs`, `app.mjs`)

- **One hls.js instance per video tile** (`attachStream` per cell). Config:
  `lowLatencyMode:false, enableWorker:false, maxBufferLength:12, backBufferLength:12`.
- **`enableWorker:false`** — demux/transmux runs on the **main thread** (a deliberate CSP choice:
  a worker spawns from a `blob:` URL, which the locked CSP — `script-src 'self'`, no `worker-src`
  — blocks). So the web client's parsing cost lands on the main thread, the browser analog of the
  native UI-thread cost. **Worker offload is a real lever but trades against the CSP posture** —
  flagged for the research phase, not decided here.
- Buffers: `maxBufferLength 12 s` + `backBufferLength 12 s` — more generous than native's 3 s
  (browsers/laptops have more headroom), but 12 s of back-buffer is per-tile memory.
- **ABR:** hls.js default level selection, **no `capLevel`/ABR cap** configured → each tile pulls
  the best variant bandwidth allows (720p sources → 720p). Evidence, not a directive.

### 2.2 Instance lifecycle / leak posture — **clean**

- `teardown()` calls `hls.destroy()`, releases the `<video>` (`removeAttribute('src')` + `load()`),
  and clears the watchdog. No caption/textTrack listeners remain (removed in the recent menu
  rebuild).
- **Reconnect-timer cleanup holds:** `clearCellRetry` clears the per-cell reconnect `setTimeout`
  before any teardown/re-render (`teardownCells`, `renderGrid`, `reattachCell`), and the retune to
  a 15 s poll over a ~3-min window is bounded — **no zombie reconnect loops**.
- Grid rebuild tears down every cell's stream + timers before rebuilding. Lifecycle is sound; not
  a leak suspect.

### 2.3 DOM / decoder-ceiling / helper coupling

- **Grid cap: 3×3 = 9** (`GRID_DIM_MAX = 3`) → up to **9 `<video>` elements + 9 hls.js instances**.
  Browsers cap **concurrent hardware video decoders** (Chrome historically ~16, fewer on some
  platforms/GPUs); **9 simultaneous 720p decodes sits in the upper-comfort zone**, especially with
  main-thread demux. Where the wall sits vs the host browser's real ceiling is a DevTools item.
- **Ticker:** crawl is a CSS `transform: translateX` keyframe animation (compositor-friendly);
  flip is a JS `setInterval`. Low cost relative to video.
- **Helper coupling:** the web client polls `/api/feed`, `/api/channels`, `/api/ticker` every
  **60 s** (one `fetch` each), ticker mode rotates every 18 s. The **helper** itself polls feeds
  every **300 s** and probes channels every **1800 s** server-side (`config.py`), decoupled from
  clients — so the per-poll client cost is one cached-JSON fetch/min. Negligible.

### 2.4 Operator DevTools checklist (please fill in and paste back)

Run on the real browser/machine that drives the wall, with the representative grid playing. Record
the **browser + version + OS + machine** (web perf is client-dependent).

- [ ] **`chrome://media-internals`** — per `<video>`: **decoder name (HW vs SW)**, dropped frames,
  selected variant/resolution. *(Confirms whether the browser HW-decodes all tiles or falls to SW
  past the decoder ceiling.)*
- [ ] **DevTools → Performance** — record ~20 s under load: **main-thread CPU**, FPS, long tasks,
  scripting-vs-rendering split. *(Shows the `enableWorker:false` main-thread demux cost.)*
- [ ] **DevTools → Memory** — heap snapshots a few minutes apart (24/7-tab leak check).
- [ ] **Chrome Task Manager (Shift+Esc)** — per-tab **CPU / GPU / memory** for the wall tab + the
  GPU process.
- [ ] Note: browser, version, OS, CPU/GPU, and the grid size used.

### 2.5 Candidate cost-drivers (Web) — non-resolution levers

1. **Simultaneous decode + main-thread demux** (shared with native; amplified by
   `enableWorker:false`). Levers: worker offload (CSP tradeoff), tile count, per-tile config.
2. **Concurrent-decoder ceiling** at 9 tiles. Lever: know the host's real ceiling; the 3×3 cap is
   native-parity, not a measured browser limit.
3. **Back-buffer memory** (12 s × N). Lever: smaller `backBufferLength` if memory-bound.
4. **DOM/repaint** (ticker, feed re-render) — minor; confirm via Performance trace.
5. **Resolution/variant** — one lever (§4), de-emphasized.

---

## 3. Cross-build synthesis

- **Shared dominant cost-driver: simultaneous decode + the per-frame pipeline it drives.** On
  Android the bottleneck is the **CPU around** the HW decoders (c2 service, vdec IRQ, SF/HWC, the
  app's render/marshalling on a 4×A35); in the browser it's the **main-thread demux + concurrent
  decoder ceiling**. Both scale ~linearly with **tile count**.
- **Build-specific:**
  - *Android:* CPU-bound and ~3× oversubscribed at 4 tiles; muted-audio decode (~16%) and
    decoder-reinit churn are concrete, code-confirmed overheads; GPU/composition are NOT the
    bottleneck (HWC overlays, 7 ms GPU); memory is tight system-wide; thermal headroom thin (~7 °C).
  - *Web:* leak posture and timer cleanup are clean; the open question is the host browser's real
    HW-decode behavior at 9 tiles and the main-thread demux cost — needs the operator's DevTools
    numbers.
- **Measurable now vs needs operator data:** Android runtime is well-characterized from read-only
  `adb`. Web runtime (decoder HW/SW per tile, main-thread CPU, real FPS/memory) **needs the
  DevTools checklist** — the doc analyzes architecture but can't measure the browser headlessly.

---

## 4. The lever-set (neutral; resolution is ONE option, NOT the lead)

Presented for the research phase to prioritize, **non-resolution levers first** (per the operator):

| Lever | Build(s) | Evidence | Picture-quality impact |
|---|---|---|---|
| **Disable audio decode on muted tiles** | Android | ~16% CPU on inaudible AAC (§1.9) | **none** |
| **Reduce per-tile decode-reinit churn** | Android | LIVE↔STALE loop, 254 ms reinits (§1.8) | none |
| **Cut UI-thread / GC / alloc cost** | Android | frame 36 ms w/ GPU 7 ms; 20 MB/1–2 s GC (§1.4–1.5) | none |
| **Fewer concurrent tiles** | both | load ~12/4 cores; linear in tile count (§1.6, §3) | none (fewer tiles, not lower res) |
| **Thread-pool / 100-thread consolidation** | Android | 100 threads on 4 cores (§1.6) | none |
| **Worker offload for demux** | Web | `enableWorker:false` main-thread (§2.1) | none (trades vs CSP) |
| **Back-buffer / buffer trims** | both | backBuffer 12 s web; native already tight (§1.9, §2.1) | none |
| **Wired networking** | Android | ~5% CPU on SDIO Wi-Fi RX (§1.7) | none (hardware change) |
| **Thermal mitigation / placement** | Android | 58 °C, ~7 °C headroom (§1.6) | none |
| **Resolution / variant cap (720p→lower)** | both | 720p30 per tile (§1.2, §2.1) | **YES — de-emphasized per operator** |

The data does **not** point at resolution as the answer: the box is CPU-bound *around* an already
HW-offloaded 720p decode, and the largest clearly-recoverable CPU (muted-audio decode, reinit
churn, UI-thread/GC) is **independent of resolution**.

---

## 5. OPEN QUESTIONS / DATA STILL NEEDED (for the research phase)

1. **Web runtime numbers (operator DevTools, §2.4):** per-tile HW-vs-SW decode, main-thread CPU,
   real FPS, memory over time, and the host's concurrent-decoder ceiling at 9 tiles.
2. **Thermal over hours:** sample `soc_thermal` + cooling-device state over a long run to see if it
   ever crosses 65 °C and begins frequency mitigation (a single 58 °C snapshot can't settle this).
3. **Tile-churn root cause:** correlate the LIVE↔STALE↔RECOVERING loop per channel over time — is
   it an upstream-flaky stream, or CPU starvation tripping the 15 s stale threshold? (Capture
   `MYMTS_SOAK` over a longer window; check whether the same slot/channel always churns.)
4. **Grid confirmation:** profiled at **4 tiles**; confirm the operator's day-to-day layout (and
   whether they run more than 4, which would worsen everything linearly).
5. **Muted-audio lever sizing:** measure CPU with audio renderers disabled on muted tiles to
   confirm the ~16% recovery before committing the change.
6. **Decoder-fallback exposure:** confirm whether any tile *ever* falls to a SW video decoder under
   load (watch `EV=DECODER` / codec names over a long run) — the `setEnableDecoderFallback(true)`
   risk.
7. **Per-tile CPU attribution:** the app is ~93% of one core in aggregate; a method-level profile
   (e.g. Perfetto/`simpleperf`, a deeper read-only pass) would split render vs decode-marshalling
   vs GC vs telemetry.
8. **Soak-harness caveat:** the profiled state had the soak harness running; confirm numbers hold
   for the plain operator wall without the harness.

---

*Method note: Android data via read-only `adb` (`getprop`, `/proc`, `/sys`, `dumpsys`
SurfaceFlinger/gfxinfo/meminfo/thermalservice/wifi, `top`, `logcat -d`) on `.92` only, one
foreground op at a time — no install/push/force-stop/restart. Web data via static source analysis.
No app/web code changed, no build, no deploy. Thermal sysfs was permission-denied to the shell
user (read via `thermalservice` instead). Device/SSID/MAC/IP detail is intentionally omitted to
keep this doc topology-clean.*
