// Per-tile render instrumentation — the missing number in every smoothness
// question. Enabled ONLY with `?fpsmeter=1` (off by default, zero cost otherwise);
// the headless renderer is pointed at `/app/?render=1&fpsmeter=1` for a measurement
// window (see tools/render-bench/). It answers, per video tile:
//
//   - decodedFps   — how fast frames actually leave the decoder (Δ totalVideoFrames)
//   - presentedFps — how fast frames reach the compositor (rVFC presentedFrames)
//   - dropPct      — droppedVideoFrames / totalVideoFrames (the judder signal
//                    mpdecimate CANNOT see: the encoder emits a perfect 30 CFR by
//                    duplicating, so an HLS unique-frame count stays high while an
//                    individual tile silently decodes at 8fps)
//   - variant vs cell — which hls.js level (resolution) the tile LOADED vs the
//                    device-px size it PAINTS into. overdrawX>1 means the tile is
//                    software-decoding more pixels than it displays — the prime
//                    frame-killer on a GPU-less renderer (a 1080p variant in a
//                    ~640px cell ≈ 6× wasted decode).
//
// The page samples every second, draws a compact on-screen overlay (so the numbers
// are ALSO in the captured HLS), and POSTs a cumulative-counter snapshot to the
// helper's LAN-only `/api/render/telemetry`, where the bench harness scrapes it.
// Cumulative counters + an elapsed clock let the harness compute any window by
// differencing two reads — no dependence on page-side windowing.
//
// This module is PURE at its core (fps math, the variant-vs-cell decision, the
// summary/verdict) — unit-tested in web/test/fpsmeter.test.mjs — with a thin DOM/
// timer wiring layer that no-ops when there is no document (import-safe under node).

// A tile paints more pixels than it decodes only up to this factor before it's
// judged "wasteful overdraw" (the capLevelToPlayerSize target). 1.5× allows the
// normal case where the nearest-larger variant slightly exceeds the cell.
export const OVERDRAW_WASTE_X = 1.5;
// Acceptance thresholds (ARCHITECTURE envelope): a tile sustaining >= this many
// presented fps with <= this drop-% is "smooth". 28/5 mirror the task's bar.
export const SMOOTH_MIN_FPS = 28;
export const SMOOTH_MAX_DROP_PCT = 5;

/** Decoded fps + drop-% from two cumulative getVideoPlaybackQuality snapshots.
 *  `a`/`b` are { totalVideoFrames, droppedVideoFrames, tMs }. Monotonic counters,
 *  so this differences cleanly over ANY window. Guards a zero/negative interval
 *  (returns nulls — "no reading" beats a divide-by-zero fabrication). */
export function decodedStats(a, b) {
  const dt = (b.tMs - a.tMs) / 1000;
  const dTotal = b.totalVideoFrames - a.totalVideoFrames;
  const dDropped = b.droppedVideoFrames - a.droppedVideoFrames;
  if (!(dt > 0) || dTotal < 0) return { decodedFps: null, dropPct: null, dTotal, dDropped };
  return {
    decodedFps: dTotal / dt,
    // drop-% is over frames CREATED in the window; 0 created → 0% (not NaN).
    dropPct: dTotal > 0 ? (dDropped / dTotal) * 100 : 0,
    dTotal,
    dDropped,
  };
}

/** Presented fps from two cumulative rVFC counters. `a`/`b` are { presentedFrames,
 *  tMs }. presentedFrames is the compositor-submitted count (media-time based, so a
 *  stall shows as a flat counter → 0 fps, exactly right). */
export function presentedFps(a, b) {
  const dt = (b.tMs - a.tMs) / 1000;
  const d = b.presentedFrames - a.presentedFrames;
  if (!(dt > 0) || d < 0) return null;
  return d / dt;
}

/** The prime-suspect metric: is this tile decoding more pixels than it displays?
 *  variant = the loaded hls.js level {w,h}; cellCssW/H = the tile's CSS px;
 *  dpr = devicePixelRatio (the renderer's Chromium runs dsf=1, so dpr≈1, but a 4K
 *  canvas or a retina dev browser makes cell device-px larger — account for it).
 *  overdrawX = decoded pixels / displayed pixels. A null variant (single-rendition
 *  stream, or level not yet known) → unknown, never a false "wasteful". */
export function variantVsCell({ variantW, variantH, cellCssW, cellCssH, dpr = 1 }) {
  const cellDeviceW = Math.max(1, Math.round(cellCssW * dpr));
  const cellDeviceH = Math.max(1, Math.round(cellCssH * dpr));
  const cellPx = cellDeviceW * cellDeviceH;
  if (!variantW || !variantH || cellPx <= 0) {
    return { cellDeviceW, cellDeviceH, overdrawX: null, wasteful: false };
  }
  const overdrawX = (variantW * variantH) / cellPx;
  return { cellDeviceW, cellDeviceH, overdrawX, wasteful: overdrawX > OVERDRAW_WASTE_X };
}

/** Roll up per-tile readings into a wall verdict. `tiles` carry { presentedFps,
 *  decodedFps, dropPct, overdrawX }. Nulls (a tile with no reading yet / an offline
 *  cell) are excluded from the aggregates so one un-started tile can't mask a real
 *  regression or fake a passing one. */
export function summarizeTiles(tiles) {
  const num = (xs) => xs.filter((v) => typeof v === "number" && isFinite(v));
  const pres = num(tiles.map((t) => t.presentedFps));
  const drops = num(tiles.map((t) => t.dropPct));
  const over = num(tiles.map((t) => t.overdrawX));
  const min = (xs) => (xs.length ? Math.min(...xs) : null);
  const max = (xs) => (xs.length ? Math.max(...xs) : null);
  const mean = (xs) => (xs.length ? xs.reduce((s, v) => s + v, 0) / xs.length : null);
  return {
    tileCount: tiles.length,
    measuredCount: pres.length,
    minPresentedFps: min(pres),
    meanPresentedFps: mean(pres),
    maxDropPct: max(drops),
    worstOverdrawX: max(over),
    wastefulTiles: over.filter((v) => v > OVERDRAW_WASTE_X).length,
  };
}

/** PASS iff EVERY measured tile clears the bar (worst tile, not the average — a
 *  smooth wall needs every cell smooth). Reports why for the bench table. */
export function wallVerdict(summary, { minFps = SMOOTH_MIN_FPS, maxDrop = SMOOTH_MAX_DROP_PCT } = {}) {
  const reasons = [];
  if (summary.measuredCount === 0) return { pass: false, reasons: ["no tiles measured"] };
  if (summary.minPresentedFps !== null && summary.minPresentedFps < minFps) {
    reasons.push(`slowest tile ${summary.minPresentedFps.toFixed(1)}fps < ${minFps}`);
  }
  if (summary.maxDropPct !== null && summary.maxDropPct > maxDrop) {
    reasons.push(`worst drop ${summary.maxDropPct.toFixed(1)}% > ${maxDrop}%`);
  }
  return { pass: reasons.length === 0, reasons };
}

// ---------------------------------------------------------------------------
// DOM / timer wiring (no-ops without a document, so the pure core imports under
// node for the unit tests).
// ---------------------------------------------------------------------------

/** True iff the URL asked for the meter (`?fpsmeter=1`). */
export function meterRequested(search) {
  try {
    return new URLSearchParams(search || "").get("fpsmeter") === "1";
  } catch {
    return false;
  }
}

/**
 * The bench high-motion control hook. When the meter is armed, `?benchclock=<url>`
 * injects a KNOWN-motion source (the synthetic multi-variant clock) into specific
 * cells so a run separates content-limit from pipeline-limit by construction —
 * WITHOUT the channel registry (whose https/probe/SSRF rules rightly reject an
 * internal clock). `?benchcells=2,3` picks which cell indices to override (default
 * 2,3). Returns { url: string|null, cells: Set<number> }; url=null ⇒ inert, so this
 * is a no-op on every normal render (it only fires under ?fpsmeter=1&benchclock=…).
 */
export function benchClockConfig(search) {
  try {
    const p = new URLSearchParams(search || "");
    if (p.get("fpsmeter") !== "1") return { url: null, cells: new Set() };
    const url = p.get("benchclock");
    if (!url) return { url: null, cells: new Set() };
    const raw = p.get("benchcells");
    const cells = new Set(
      (raw ? raw.split(",") : ["2", "3"])
        .map((s) => parseInt(s.trim(), 10))
        .filter((n) => Number.isInteger(n) && n >= 0),
    );
    return { url, cells };
  } catch {
    return { url: null, cells: new Set() };
  }
}

/** Resolve a cell's effective stream URL: the bench clock when this cell is in the
 *  override set, else the channel's real URL. Pure — the caller passes the config. */
export function benchUrlFor(cfg, cellIndex, realUrl) {
  return cfg && cfg.url && cfg.cells.has(cellIndex) ? cfg.url : realUrl;
}

const now = () => (typeof performance !== "undefined" ? performance.now() : Date.now());

/** Read a tile's hls.js loaded level {w,h} via the reference video.mjs stashes on
 *  the element (`__mymtsHls`). Native-HLS tiles (iOS) have none → null (unknown). */
function tileVariant(video) {
  try {
    const hls = video.__mymtsHls;
    if (hls && Array.isArray(hls.levels) && hls.currentLevel >= 0) {
      const lvl = hls.levels[hls.currentLevel];
      if (lvl && lvl.width && lvl.height) return { w: lvl.width, h: lvl.height };
    }
  } catch { /* no hls / older build */ }
  return null;
}

/**
 * Start the meter over the live grid. `opts`:
 *   - videos()   — returns the current array of tile <video> elements
 *   - labelOf(v) — a short human label for a tile (optional)
 *   - endpoint   — telemetry POST URL (default the LAN helper route)
 *   - intervalMs — sample cadence (default 1000)
 * Returns a stop() handle. Safe to call once from main(); it self-disables if
 * there is no document (node import) — the caller still gates on meterRequested().
 */
export function startFpsMeter(opts = {}) {
  if (typeof document === "undefined") return { stop() {} };
  const {
    videos = () => Array.from(document.querySelectorAll(".tile video")),
    labelOf = () => "",
    endpoint = "/api/render/telemetry",
    intervalMs = 1000,
  } = opts;

  const startedAt = now();
  // Per-video cumulative state, keyed by the element (a rebuilt grid gets fresh
  // entries; torn-down elements just stop being visited).
  const state = new WeakMap();
  // Page compositor fps via rAF (how often the PAGE itself paints — the ticker
  // crawl's smoothness ceiling, independent of any tile).
  let rafFrames = 0;
  let rafStop = false;
  const tick = () => { rafFrames++; if (!rafStop) requestAnimationFrame(tick); };
  if (typeof requestAnimationFrame === "function") requestAnimationFrame(tick);

  const overlay = makeOverlay();

  const armRvfc = (video, st) => {
    if (typeof video.requestVideoFrameCallback !== "function" || st.rvfcArmed) return;
    st.rvfcArmed = true;
    const cb = (_t, meta) => {
      // presentedFrames is a monotonic total from the element; snapshot the latest.
      st.presentedFrames = meta && typeof meta.presentedFrames === "number"
        ? meta.presentedFrames : st.presentedFrames + 1;
      if (!st.dead && typeof video.requestVideoFrameCallback === "function") {
        video.requestVideoFrameCallback(cb);
      }
    };
    video.requestVideoFrameCallback(cb);
  };

  const sample = () => {
    const t = now();
    const dpr = typeof devicePixelRatio === "number" ? devicePixelRatio : 1;
    const vids = videos();
    const tiles = [];
    vids.forEach((video, i) => {
      let st = state.get(video);
      if (!st) { st = { presentedFrames: 0, rvfcArmed: false, dead: false, prev: null }; state.set(video, st); }
      armRvfc(video, st);

      let q = null;
      try { q = typeof video.getVideoPlaybackQuality === "function" ? video.getVideoPlaybackQuality() : null; } catch { q = null; }
      const cumulative = q
        ? { totalVideoFrames: q.totalVideoFrames, droppedVideoFrames: q.droppedVideoFrames, tMs: t }
        : null;

      // instantaneous window = since the previous sample (~1s)
      const inst = cumulative && st.prev ? decodedStats(st.prev, cumulative) : { decodedFps: null, dropPct: null };
      const presInst = st.prevPresented != null
        ? presentedFps({ presentedFrames: st.prevPresented, tMs: st.prevPresentedT }, { presentedFrames: st.presentedFrames, tMs: t })
        : null;

      const rect = video.getBoundingClientRect ? video.getBoundingClientRect() : { width: 0, height: 0 };
      const variant = tileVariant(video);
      const vc = variantVsCell({
        variantW: variant && variant.w, variantH: variant && variant.h,
        cellCssW: rect.width, cellCssH: rect.height, dpr,
      });

      tiles.push({
        index: i,
        label: labelOf(video, i),
        // cumulative (harness differences these over its own window)
        totalVideoFrames: q ? q.totalVideoFrames : null,
        droppedVideoFrames: q ? q.droppedVideoFrames : null,
        presentedFramesCum: st.presentedFrames,
        currentTime: typeof video.currentTime === "number" ? Number(video.currentTime.toFixed(2)) : null,
        paused: !!video.paused,
        // instantaneous (~1s) for the live overlay
        decodedFps: inst.decodedFps,
        presentedFps: presInst,
        dropPct: inst.dropPct,
        variant,
        cell: { cssW: Math.round(rect.width), cssH: Math.round(rect.height), dpr, deviceW: vc.cellDeviceW, deviceH: vc.cellDeviceH },
        overdrawX: vc.overdrawX,
        wasteful: vc.wasteful,
      });

      st.prev = cumulative;
      st.prevPresented = st.presentedFrames;
      st.prevPresentedT = t;
    });

    const rafFps = rafFrames / ((t - (state.__rafT0 || startedAt)) / 1000 || 1);
    // rolling page rAF fps over the last interval
    const pageRafInst = state.__lastRaf != null ? (rafFrames - state.__lastRaf) / ((t - state.__lastRafT) / 1000 || 1) : rafFps;
    state.__lastRaf = rafFrames; state.__lastRafT = t;

    const summary = summarizeTiles(tiles);
    const snapshot = {
      tMs: Date.now(),
      elapsedS: Number(((t - startedAt) / 1000).toFixed(1)),
      page: { rafFps: Number(pageRafInst.toFixed(1)), rafFramesCum: rafFrames },
      canvas: { w: typeof innerWidth === "number" ? innerWidth : null, h: typeof innerHeight === "number" ? innerHeight : null, dpr },
      tiles,
      summary,
    };
    drawOverlay(overlay, snapshot);
    postTelemetry(endpoint, snapshot);
  };

  const timer = setInterval(sample, intervalMs);
  sample();
  return {
    stop() {
      rafStop = true;
      clearInterval(timer);
      if (overlay && overlay.remove) overlay.remove();
    },
  };
}

function postTelemetry(endpoint, snapshot) {
  try {
    // keepalive so an in-flight POST survives a navigation; failures are ignored —
    // telemetry must NEVER perturb the wall it measures.
    fetch(endpoint, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(snapshot),
      keepalive: true,
    }).catch(() => {});
  } catch { /* no fetch / blocked — overlay still shows */ }
}

function makeOverlay() {
  try {
    const pre = document.createElement("pre");
    pre.id = "fpsmeter-overlay";
    pre.setAttribute("style", [
      "position:fixed", "left:6px", "bottom:6px", "z-index:2147483647",
      "margin:0", "padding:6px 8px", "font:11px/1.35 ui-monospace,Menlo,Consolas,monospace",
      "color:#0f0", "background:rgba(0,0,0,.72)", "border:1px solid #0f0",
      "white-space:pre", "pointer-events:none", "max-width:60vw", "border-radius:4px",
    ].join(";"));
    (document.body || document.documentElement).appendChild(pre);
    return pre;
  } catch { return null; }
}

function drawOverlay(pre, snap) {
  if (!pre) return;
  const s = snap.summary;
  const head = `FPSMETER  t=${snap.elapsedS}s  page-rAF=${snap.page.rafFps}fps  ` +
    `tiles=${s.measuredCount}/${s.tileCount}  minPres=${fmt(s.minPresentedFps)}  ` +
    `maxDrop=${s.maxDropPct == null ? "–" : s.maxDropPct.toFixed(1) + "%"}  ` +
    `worstOverdraw=${s.worstOverdrawX == null ? "–" : s.worstOverdrawX.toFixed(1) + "x"}`;
  const rows = snap.tiles.map((t) => {
    const v = t.variant ? `${t.variant.w}x${t.variant.h}` : "—";
    const cell = `${t.cell.deviceW}x${t.cell.deviceH}`;
    const od = t.overdrawX == null ? "—" : t.overdrawX.toFixed(1) + "x" + (t.wasteful ? "!" : "");
    return `#${t.index} ${(t.label || "").slice(0, 10).padEnd(10)} ` +
      `pres=${fmt(t.presentedFps)} dec=${fmt(t.decodedFps)} drop=${t.dropPct == null ? "–" : t.dropPct.toFixed(0) + "%"} ` +
      `var=${v} cell=${cell} od=${od}`;
  });
  pre.textContent = [head, ...rows].join("\n");
}

const fmt = (x) => (x == null || !isFinite(x) ? "–" : x.toFixed(1));
