// The FOUR view-tunable controls, defined ONCE for every web surface.
//
// WHY THIS MODULE EXISTS (PR-027): the ladders lived in wallConfig.mjs, but the
// *controls* — which id maps to which config key, what the row is called, how its
// value reads — were re-declared independently in /control/ and never added to /app/
// at all. So PR-024 updated one surface, PR-026 updated the native app, and /app/'s
// own WALL SETTINGS modal silently kept a 3-option dropdown and an 18–58 slider for
// months. Three implementations of one idea is what let them drift; this is one
// implementation all of them render from.
//
// Adding a fifth control means adding ONE entry here — both surfaces pick it up.

import { SCALE_BLOCKS, SCALE_STEP_MIN, SCALE_STEP_MAX, clampStep, stepValue } from "./wallConfig.mjs";

/** How each control's resolved value reads to a human. Percent for the pane width
 *  (it IS a percentage of the wall); a multiplier for the three scale controls. */
const PCT = (v) => `${v}%`;
const MULT = (v) => `${v.toFixed(2)}×`;

/**
 * The four controls, in the order both surfaces present them.
 *
 * `id` is the DOM id STEM: /control/ uses it verbatim, /app/'s modal prefixes its own
 * to avoid colliding with the pane-divider's markup. `block`/`key` address the config
 * exactly as the helper's partial-merge expects, so a write touches one key.
 */
export const SCALE_CONTROLS = [
  { id: "feed-width", block: "feed", key: "width_scale",
    label: "Feed width", what: "feed width", fmt: PCT },
  { id: "feed-text", block: "feed", key: "text_scale",
    label: "Feed text", what: "feed text", fmt: MULT },
  { id: "ticker-height", block: "ticker", key: "height_scale",
    label: "Ticker height", what: "ticker height", fmt: MULT },
  { id: "ticker-text", block: "ticker", key: "text_scale",
    label: "Ticker text", what: "ticker text", fmt: MULT },
];

/** Guard: the control table must address only keys the config actually has, or a
 *  write would silently do nothing. Cheap enough to assert at module load. */
for (const c of SCALE_CONTROLS) {
  if (!SCALE_BLOCKS[c.block]?.[c.key]) {
    throw new Error(`scaleControls: ${c.block}.${c.key} is not a config scale key`);
  }
}

/** The label for a step: the STEP is what the operator sets, the resolved value is the
 *  honest hint about what it means ("7 · 44%"). Identical on every surface — including
 *  the native app, which mirrors this format. Pure. */
export function scaleLabel(control, step) {
  return `${clampStep(step)} · ${control.fmt(stepValue(control.block, control.key, step))}`;
}

/** Read a control's current step out of a normalized config. Pure. */
export function stepOf(config, control) {
  return clampStep(config?.[control.block]?.[control.key]);
}

/** Apply the shared range-input bounds. One place, so a surface can't ship a stale
 *  `min`/`max` in its markup the way /app/'s 18–58 slider did. */
export function applyRangeBounds(input) {
  if (!input) return;
  input.min = String(SCALE_STEP_MIN);
  input.max = String(SCALE_STEP_MAX);
  input.step = "1";
}

/**
 * The debounced, per-control-coalescing commit both surfaces write through.
 *
 * Two layers of thrash guard, as established in PR-024: the caller re-labels on
 * `input` (no network at all while dragging) and calls [push] on `change`; this then
 * coalesces for [delayMs] so a held arrow key — or a touch drag emitting several
 * `change` events — becomes ONE write. Pending edits are keyed PER CONTROL, so nudging
 * two controls inside one window produces a single write carrying BOTH rather than
 * dropping the earlier one.
 *
 * `apply(config, edits)` folds the pending edits (withScaleSteps); `commit(next, what)`
 * performs the write. Injected rather than imported so this module stays DOM-free and
 * testable.
 */
export function createScaleCommitter({ getConfig, apply, commit, delayMs = 250, setTimer, clearTimer }) {
  const pending = new Map();
  const schedule = setTimer ?? ((fn, ms) => setTimeout(fn, ms));
  const cancel = clearTimer ?? ((t) => clearTimeout(t));
  let timer = null;

  const flush = () => {
    timer = null;
    if (pending.size === 0) return;
    const edits = [...pending.values()];
    pending.clear();
    commit(
      apply(getConfig(), edits),
      edits.length === 1 ? edits[0].what : `${edits.length} display controls`,
    );
  };

  return {
    /** Queue one control's new step. Later edits of the same control replace earlier. */
    push(control, step) {
      pending.set(`${control.block}.${control.key}`, {
        block: control.block, key: control.key, step: clampStep(step), what: control.what,
      });
      if (timer !== null) cancel(timer);
      timer = schedule(flush, delayMs);
    },
    /** Write immediately (e.g. a pointer-drag ending). */
    flushNow() {
      if (timer !== null) { cancel(timer); timer = null; }
      flush();
    },
    get pendingCount() { return pending.size; },
  };
}

// ============================================================================
// AUTO-FIT (MYMTS-001) — solve the feed width that makes the video cells come
// out at the videos' native aspect ratio, so nothing letterboxes.
// ============================================================================
//
// WHY IT LIVES HERE: PR-027's lesson was that a control implemented per-surface
// drifts. The solver is shared for the same reason the control table is.
//
// THE GEOMETRY TERMS ARE MEASURED, NOT ASSUMED. They were derived by measuring the
// live render surface at 1920×1080 (the recon that preceded this change) and the
// resulting model reproduces the real wall to 0.01 px. Two of them are easy to get
// wrong and cost real time to rediscover:
//   - the 5 px pane DIVIDER between feed and grid is a real flex item;
//   - there is NO caption strip. The channel label and live dot are
//     `position: absolute` and consume zero layout. The only per-cell chrome is the
//     tile's 1 px border — and it applies to WIDTH as well as height.
// See ARCHITECTURE §49.

/** The auto-fit control, described once so both surfaces render the same thing —
 *  the same reason SCALE_CONTROLS exists. `id` is the DOM id stem. */
export const AUTOFIT_CONTROL = {
  id: "autofit",
  label: "Auto-fit feed width",
  what: "auto-fit",
  hint: "Sizes the feed so the video cells match the videos' shape — no black bars.",
};

/** The one-line status both surfaces show. Tells the truth about a near-miss rather
 *  than implying a fit that was not achieved (MYMTS-001 decision 5). Pure. */
export function autoFitStatusLine(cfg, result) {
  const af = cfg?.autofit ?? {};
  if (!af.enabled) return "off — the 1–10 control below is in charge";
  if (af.feed_width_pct === null || af.feed_width_pct === undefined) {
    return "on — waiting for the wall to measure itself";
  }
  const at = `on — feed at ${Number(af.feed_width_pct).toFixed(2)}%`;
  if (!result || !result.ok) return at;
  if (result.reachable) return `${at} · exact fit, no bars`;
  const bar = result.barPx == null ? null : Math.round(result.barPx);
  const why = result.clampedTo === "min-width floor"
    ? "this grid wants a narrower feed than the layout allows"
    : "this grid wants a wider feed than allowed";
  return `${at} · closest possible — ${why}` +
         (bar ? `, ~${bar}px bars remain (a taller ticker would close some of it)` : "");
}

/** Layout constants, in DESIGN px (i.e. at `--ux: 1`). Every one of these is
 *  `calc(N * var(--u))` in styles.css and therefore scales with the canvas — except
 *  TILE_BORDER, which is a hard `1px`. At 1080p `--ux` is 1 so they coincide; the
 *  solver takes the real measured values anyway, so this table is documentation of
 *  where each number comes from rather than a source of truth. */
export const AUTOFIT_GEOMETRY = {
  DIVIDER: 5,        // .pane-divider  { flex: 0 0 calc(5 * var(--u)) }
  GRID_PAD: 8,       // .grid-pane     { padding: var(--gap) }  — EACH side
  GAP: 8,            // .video-grid    { gap: var(--gap) }      — column == row
  TILE_BORDER: 1,    // .tile          { border: 1px }          — EACH side, BOTH axes
};

/** 16:9, the fallback aspect when no video has reported an intrinsic size yet. */
export const DEFAULT_ASPECT = 16 / 9;

/**
 * Solve the feed width (in px) that makes each cell's VIDEO BOX exactly aspect `A`.
 *
 * Pure. Every input is a measurement the caller takes from the live page — notably
 * `tickerH`, which must be the ticker's ACTUALLY OCCUPIED height: PR-024 made it a
 * `min-height`, so at a large text step the content can exceed the configured floor
 * and the two genuinely differ.
 *
 * Returns px; the caller converts to a percentage of `wallW`.
 */
export function solveFeedWidthPx({ wallW, wallH, tickerH, rows, cols, aspect, geom = AUTOFIT_GEOMETRY }) {
  const R = Math.max(1, Math.round(rows));
  const C = Math.max(1, Math.round(cols));
  const A = Number(aspect);
  if (!(wallW > 0) || !(wallH > 0) || !Number.isFinite(A) || A <= 0) return null;

  const gridH = (wallH - tickerH) - 2 * geom.GRID_PAD;
  const cellH = (gridH - (R - 1) * geom.GAP) / R;
  const videoH = cellH - 2 * geom.TILE_BORDER;
  if (!(videoH > 0)) return null;

  const videoW = A * videoH;
  const cellW = videoW + 2 * geom.TILE_BORDER;
  const gridW = C * cellW + (C - 1) * geom.GAP;
  return wallW - geom.DIVIDER - (gridW + 2 * geom.GRID_PAD);
}

/** The aspect the fit targets: the mean native aspect of the VIDEO cells only.
 *
 *  POLICY (MYMTS-001, deliberate): non-video widgets are EXCLUDED. The weather-radar
 *  pseudo-channel is 600×550 (≈1.09:1, nearly square); solved on its own it wants a
 *  ~41 % feed where the videos want ~5.5 %, so no single width satisfies both. Three
 *  videos outrank one widget, and the widget is allowed to box. A grid with NO video
 *  cells has nothing to fit, so the caller is told so rather than being handed a
 *  meaningless number. Pure. */
export function targetAspect(mediaList) {
  const vids = (mediaList || []).filter(
    (m) => m && m.kind === "video" && Number(m.aspect) > 0,
  );
  if (vids.length === 0) return { aspect: null, sampled: 0, reason: "no video cells to fit" };
  const mean = vids.reduce((s, m) => s + Number(m.aspect), 0) / vids.length;
  const spread = Math.max(...vids.map((m) => m.aspect)) - Math.min(...vids.map((m) => m.aspect));
  return { aspect: mean, sampled: vids.length, spread: +spread.toFixed(5), reason: null };
}

/**
 * The full auto-fit answer: the ideal width, the width actually applied after
 * clamping, and enough detail for the UI to tell the truth about a near-miss.
 *
 * CLAMPING IS HONEST BY DESIGN (MYMTS-001 decision 5). The wall is height-constrained,
 * so plenty of grids want a feed narrower than `.feed-pane`'s CSS `min-width` floor —
 * a 2×2 at 1080p wants ~5.5 % against a floor of ~11.46 %. We clamp, and we report
 * `reachable: false` plus the residual bar thickness so the surface can say so rather
 * than pretending it fit.
 */
export function autoFitFeedWidth({
  wallW, wallH, tickerH, rows, cols, media,
  minPx, maxPct = 100, geom = AUTOFIT_GEOMETRY,
}) {
  const t = targetAspect(media);
  if (t.aspect === null) {
    return { ok: false, reason: t.reason, sampled: 0, idealPx: null, idealPct: null,
             appliedPx: null, appliedPct: null, reachable: false, residualBarPx: null };
  }
  const idealPx = solveFeedWidthPx({ wallW, wallH, tickerH, rows, cols, aspect: t.aspect, geom });
  if (idealPx === null) {
    return { ok: false, reason: "geometry not measurable yet", sampled: t.sampled,
             idealPx: null, idealPct: null, appliedPx: null, appliedPct: null,
             reachable: false, residualBarPx: null };
  }

  const floorPx = Number.isFinite(minPx) ? minPx : 0;
  const ceilPx = (maxPct / 100) * wallW;
  const appliedPx = Math.min(ceilPx, Math.max(floorPx, idealPx));
  const reachable = Math.abs(appliedPx - idealPx) < 0.5;

  // What the operator will actually still see, at the width we could apply.
  const residual = residualBar({ wallW, wallH, tickerH, rows, cols, aspect: t.aspect,
                                 feedPx: appliedPx, geom });

  return {
    ok: true, reason: null, sampled: t.sampled, spread: t.spread, aspect: +t.aspect.toFixed(5),
    idealPx: +idealPx.toFixed(2), idealPct: +((idealPx / wallW) * 100).toFixed(3),
    appliedPx: +appliedPx.toFixed(2), appliedPct: +((appliedPx / wallW) * 100).toFixed(3),
    reachable,
    clampedTo: reachable ? null : (appliedPx > idealPx ? "min-width floor" : "max width"),
    ...residual,
  };
}

/** The black bar that remains at a given feed width — the honest "what you'll still
 *  see" number. `barPx` is the thickness of ONE bar; `barAxis` says which pair. Pure. */
export function residualBar({ wallW, wallH, tickerH, rows, cols, aspect, feedPx, geom = AUTOFIT_GEOMETRY }) {
  const R = Math.max(1, Math.round(rows));
  const C = Math.max(1, Math.round(cols));
  const gridW = (wallW - feedPx - geom.DIVIDER) - 2 * geom.GRID_PAD;
  const gridH = (wallH - tickerH) - 2 * geom.GRID_PAD;
  const cellW = (gridW - (C - 1) * geom.GAP) / C;
  const cellH = (gridH - (R - 1) * geom.GAP) / R;
  const vw = cellW - 2 * geom.TILE_BORDER;
  const vh = cellH - 2 * geom.TILE_BORDER;
  if (!(vw > 0) || !(vh > 0)) return { barPx: null, barAxis: null, cellW: null, cellH: null };
  const boxA = vw / vh;
  let barPx, barAxis;
  if (Math.abs(boxA - aspect) < 1e-4) { barPx = 0; barAxis = "none"; }
  else if (aspect > boxA) { barPx = (vh - vw / aspect) / 2; barAxis = "top/bottom"; }
  else { barPx = (vw - vh * aspect) / 2; barAxis = "left/right"; }
  return {
    barPx: +barPx.toFixed(2), barAxis,
    cellW: +cellW.toFixed(2), cellH: +cellH.toFixed(2),
    videoBoxW: +vw.toFixed(2), videoBoxH: +vh.toFixed(2),
  };
}
