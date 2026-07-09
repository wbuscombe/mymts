# render-bench — the wall smoothness instrument

Every "is the wall actually moving?" question used to be answered with `mpdecimate`
on the HLS output — a number that **cannot** tell content-stillness from pipeline
frame-loss, and **cannot** see per-tile judder (the encoder emits a perfect 30 CFR by
duplicating, so one tile decoding at 8fps stays invisible). This harness measures
**every stage at once** so frame loss is *attributed*, not guessed.

## What it measures (per 60s warm window)

| Stage | Tool | Answers |
|---|---|---|
| **per-tile decode/present fps + drop-%** | render page `?fpsmeter=1` → `getVideoPlaybackQuality` + `requestVideoFrameCallback` → telemetry | is an individual `<video>` dropping frames? (the number nothing else sees) |
| **per-tile variant vs cell (overdrawX)** | same telemetry | is the tile software-decoding a 1080p variant into a ~640px cell? (the prime frame-killer) |
| **page paint** | telemetry rAF **+** an independent `x11grab :99 → mpdecimate` | is the browser painting ~30fps to the framebuffer? |
| **encoder unique-frame rate** | `mpdecimate` on the HLS output | the legacy number — kept to *show* it's output-side, ticker-dominated, not a tile signal |
| **box load** | `docker stats` + per-process `ps` | chromium (decode/composite) vs ffmpeg (encode) CPU, vs the 6-core cap |

## Prereqs

- Run **on the NAS host** (needs `docker`; node ≥ 18 — the host has v22).
- The instrumentation build must be deployed (the helper carries `/api/render/telemetry`,
  the web tree carries `fpsmeter.mjs`). Off by default — nothing runs until you point
  the renderer at `?fpsmeter=1`.

## 1. Arm the meter (point the renderer at fpsmeter mode)

`?fpsmeter=1` is added to the render URL via the renderer's env, then the stack is
recreated so Chromium reloads the page:

```sh
# on the NAS host, in the helper deploy dir (docker-compose.nas.yml):
RENDER_HELPER_URL='https://mymts-helper:8443/app/?render=1&fpsmeter=1' \
  docker compose -f docker-compose.nas.yml up -d --no-deps renderer
# ...measure... then revert:
docker compose -f docker-compose.nas.yml up -d --no-deps renderer   # back to ?render=1
```

The overlay (bottom-left of the wall) confirms it's live; the numbers are also in the
captured HLS, so a VLC glance at `:8082` shows them.

## 2. Run the collector

```sh
BENCH_WINDOW_S=60 BENCH_LABEL=news-before node bench.mjs
```

Env: `BENCH_WINDOW_S` (60), `BENCH_RENDERER` (mymts-renderer), `BENCH_HELPER`
(mymts-helper), `BENCH_HELPER_ORIGIN` (https://127.0.0.1:8443), `BENCH_LABEL`.
It prints a per-tile table, a per-stage roll-up, and one `BENCH_JSON:{…}` line for capture.
**Read-only** — it never restarts anything.

## 3. The high-motion control (separate content-limit from pipeline-limit *by construction*)

The real news wall can legitimately read a low unique-fps because the *content* is
still. To prove the *pipeline* can sustain 30fps, feed it a source whose motion is
known: a synthetic **multi-variant** (1080p/720p/480p) 30fps moving-clock — every
frame provably unique, and multi-variant so `capLevelToPlayerSize` has renditions to
choose (a faithful analog of the real CDN streams).

The clock is injected via the **fpsmeter-gated bench hook** (`?benchclock=<url>`),
NOT the channel registry — the registry rightly rejects an internal clock (https-only
+ trusted-CA + must-probe-live, the SSRF/TLS shield). The hook swaps a cell's stream
URL client-side, so the real wall (grid, cell sizing, `video.mjs` capping, fpsmeter)
is exercised unchanged. The clock serves **HTTPS self-signed** (the render Chrome runs
`--ignore-certificate-errors`; the render page is HTTPS, so an HTTP source would be
mixed-content-blocked) with permissive CORS (hls.js fetches it cross-origin).

Run the clock as a **sidecar container** (its own cgroup, so it stands in for an
external CDN and doesn't steal the renderer's cores — and it survives renderer
recreates). Point cells 2 & 3 at it via the render URL:

```sh
# 1. sidecar clock on mymts-net (reachable by the render Chrome as bench-clock:8099):
docker create --name bench-clock --network mymts-net --entrypoint sleep mymts-renderer:<ver> infinity
docker start bench-clock && docker exec bench-clock mkdir -p /app/render-bench
docker cp make-clock-hls.sh bench-clock:/app/render-bench/make-clock-hls.sh
docker exec bench-clock sh /app/render-bench/make-clock-hls.sh start

# 2. point cells 2 & 3 at it (recreate the renderer with the bench hook):
RENDER_HELPER_URL='https://mymts-helper:8443/app/?render=1&fpsmeter=1&benchclock=https://bench-clock:8099/master.m3u8&benchcells=2,3' \
  docker compose -f docker-compose.nas.yml up -d --no-deps --no-build renderer

# 3. the CLEAN per-tile read — telemetry only, no ffmpeg probes (see below):
BENCH_NO_PROBES=1 BENCH_WINDOW_S=60 BENCH_LABEL=control node bench.mjs
```

**Teardown (revert everything):**

```sh
RENDER_HELPER_URL='https://mymts-helper:8443/app/?render=1' \
  docker compose -f docker-compose.nas.yml up -d --no-deps --no-build renderer   # back to prod
docker rm -f bench-clock
```

The bench hook is inert on every normal render — it fires only when `?fpsmeter=1`
AND `benchclock=…` are both present (regression-tested in `web/test/fpsmeter.test.mjs`).

### `BENCH_NO_PROBES=1` — the clean per-tile read

The two ffmpeg probes run INSIDE the renderer and, at 60fps, contend with Chromium's
compositing — which perturbs the very per-tile numbers they sit beside (a tile can
read 16% drop under the probe load and ~0% without it). For the authoritative per-tile
smoothness read, set `BENCH_NO_PROBES=1`: telemetry + CPU only, no contention. Use the
full probes when you want the page-paint / encoder-unique stages too.

**Reading the synthetic control:** a locally-generated `-re` HLS live stream has some
live-edge jitter that a real CDN doesn't, so the clock tiles' *presented-fps* can dip
below the source's 30 — but **drop-% stays ~0 during those dips** (the pipeline
presents every frame it receives; the source under-delivered), and a REAL stream in
the same run (LiveNOW) holds a full 30fps/0%. So drop-% is the pipeline signal;
a low presented-fps at ~0 drop is source-side, not the render path.

## Interpreting it

- **Slowest-tile present-fps ≥ 28 and worst drop < 5%** on the *control* → the pipeline
  is delivering; any judder on the news wall is then provably content/player-side.
- **overdrawX ≫ 1 on the news tiles** → tiles are decoding far more pixels than they
  paint → cap the variant (`capLevelToPlayerSize`).
- **page paint ~30 but encoder-unique low** → the low unique-fps is a mpdecimate
  artifact, not a render fault (the framebuffer really is moving).
