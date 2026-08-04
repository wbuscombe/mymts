// Pure helpers for the SERVER-SIDE wall config (headless-container version).
//
// Shared by the picker control surface (/control/) which WRITES the config and
// the rendered wall (/app/) which READS it, so the two can never disagree on
// the shape or the edit semantics. Every function is a pure config → config
// transform (no DOM, no fetch) — unit-tested in web/test/wallConfig.test.mjs.
// Mirrors the server validator in helper/.../wall/store.py (same schema: per-cell
// audio + subtitles, and the outputs fan-out map).

import { clampGridDim } from "./render.mjs";

export const WALL_SCHEMA_VERSION = 1;

/** The render-resolution ladder (mirror of the helper's RENDER_RESOLUTIONS + the
 *  renderer's). Outputs pick a rung; the renderer composites at max(enabled) + each
 *  output downscales to its own rung. */
export const RENDER_RESOLUTIONS = [
  "720p", "900p", "1080p", "1260p", "1440p", "1620p", "1800p", "2160p",
];
export const DEFAULT_RESOLUTION = "1080p";

/** Per-resolution display metadata for the /control/ chooser: the canvas, the
 *  sustainable fps on this CPU-only renderer, and the honest smoothness zone (§31). */
export const RESOLUTION_INFO = {
  "720p":  { w: 1280, h: 720,  fps: 30, zone: "smooth" },
  "900p":  { w: 1600, h: 900,  fps: 30, zone: "smooth" },
  "1080p": { w: 1920, h: 1080, fps: 30, zone: "smooth" },
  "1260p": { w: 2240, h: 1260, fps: 24, zone: "marginal" },
  "1440p": { w: 2560, h: 1440, fps: 20, zone: "marginal" },
  "1620p": { w: 2880, h: 1620, fps: 16, zone: "heavy" },
  "1800p": { w: 3200, h: 1800, fps: 14, zone: "heavy" },
  "2160p": { w: 3840, h: 2160, fps: 10, zone: "heavy" },
};

// ----- outputs (the multi-output fan-out; mirror helper store.py) -----
export const OUTPUT_NAMES = ["hls"];
export const BITRATE_KBPS_MIN = 500;
export const BITRATE_KBPS_CEIL = 60000;
export const RES_BITRATE_KBPS = {
  "720p": 4000, "900p": 6000, "1080p": 8000, "1260p": 10000,
  "1440p": 12000, "1620p": 14000, "1800p": 16000, "2160p": 20000,
};

function ladderIndex(res) {
  const i = RENDER_RESOLUTIONS.indexOf(res);
  return i < 0 ? RENDER_RESOLUTIONS.indexOf(DEFAULT_RESOLUTION) : i;
}
function clampResolution(v, def) { return RENDER_RESOLUTIONS.includes(v) ? v : def; }

/** The bitrate slider bounds for a resolution: a floor that carries motion, a
 *  ceiling of ~3x the rung's recommended bitrate (mirror helper clamp). */
export function bitrateBounds(resolution) {
  const rec = RES_BITRATE_KBPS[resolution] ?? 8000;
  return { min: BITRATE_KBPS_MIN, max: Math.min(BITRATE_KBPS_CEIL, rec * 3), step: 250, default: rec };
}
function clampBitrate(v, resolution) {
  const b = bitrateBounds(resolution);
  const n = Math.round(Number(v));
  return Number.isFinite(n) ? Math.min(b.max, Math.max(b.min, n)) : b.default;
}

export function defaultOutputs() {
  return {
    hls: { enabled: true, resolution: "1080p", bitrate_kbps: 8000, audio: true, restart_epoch: 0 },
  };
}

function normalizeOutput(name, raw, def) {
  const src = raw && typeof raw === "object" ? raw : {};
  const res = clampResolution(src.resolution ?? def.resolution, def.resolution);
  return {
    enabled: src.enabled === undefined ? def.enabled : src.enabled === true,
    resolution: res,
    bitrate_kbps: clampBitrate(src.bitrate_kbps ?? def.bitrate_kbps, res),
    audio: src.audio === undefined ? def.audio : src.audio === true,
    restart_epoch: reloadInt(src.restart_epoch),
  };
}

/** The render canvas /control/ shows read-only ("compositing at X") — the largest
 *  ENABLED output resolution (each output downscales from it). 1080p if none on.
 *  Mirrors helper store.derive_render_resolution + renderer supervisor. Pure. */
export function deriveRenderResolution(outputs) {
  const enabled = Object.values(outputs || {})
    .filter((o) => o && typeof o === "object" && o.enabled && RENDER_RESOLUTIONS.includes(o.resolution))
    .map((o) => o.resolution);
  if (!enabled.length) return DEFAULT_RESOLUTION;
  return enabled.reduce((a, b) => (ladderIndex(b) > ladderIndex(a) ? b : a));
}

/** Normalise/clamp the outputs map (each output to the ladder + sane bitrate).
 *  Always returns the known outputs. Pure. */
export function normalizeOutputs(raw) {
  const def = defaultOutputs();
  const src = raw && typeof raw === "object" ? raw : {};
  return {
    hls: normalizeOutput("hls", src.hls, def.hls),
  };
}

/** A non-negative integer reload/restart counter (else 0). Monotonic on the wire. */
function reloadInt(v) { return Number.isInteger(v) && v >= 0 ? v : 0; }

/** A fresh empty cell. Per-cell audio + subtitles default off; reload epoch 0. */
function emptyCell() { return { channel: null, audio: false, subtitles: false, reload: 0 }; }

/** Resize a cells array to exactly `count`, preserving assignments BY INDEX. When
 *  `legacyAudible` is a migrating single-audible index, an old cell with no `audio`
 *  field at that index becomes audio:true. Returns a new array. */
export function resizeCells(cells, count, legacyAudible = null) {
  const src = Array.isArray(cells) ? cells : [];
  const out = [];
  for (let i = 0; i < count; i++) {
    const c = src[i];
    if (c) {
      const audio = c.audio === undefined ? (legacyAudible === i) : c.audio === true;
      out.push({
        channel: c.channel ?? null, audio, subtitles: c.subtitles === true, reload: reloadInt(c.reload),
      });
    } else {
      out.push(emptyCell());
    }
  }
  return out;
}

/** The cell count for a config's layout. */
export function cellCount(config) {
  const r = clampGridDim(config?.layout?.rows);
  const c = clampGridDim(config?.layout?.cols);
  return r * c;
}

/** Normalise/repair a config read from the wire so the client always has a
 *  well-formed object to render+edit (lenient mirror of the server validator).
 *  MIGRATES an old-shape config in memory: an absent `outputs` seeds hls.resolution
 *  from the legacy `render.resolution`; a legacy `audible_cell` folds into that
 *  cell's audio. `validSlugs` clears a cell whose channel vanished. Pure. */
export function normalizeConfig(raw, validSlugs = null) {
  const cfg = raw && typeof raw === "object" ? raw : {};
  const rows = clampGridDim(cfg.layout?.rows ?? 2);
  const cols = clampGridDim(cfg.layout?.cols ?? 2);
  const count = rows * cols;
  const legacyAudible = Number.isInteger(cfg.audible_cell) ? cfg.audible_cell : null;
  let cells = resizeCells(cfg.cells, count, legacyAudible);
  if (validSlugs) {
    const ok = validSlugs instanceof Set ? validSlugs : new Set(validSlugs);
    cells = cells.map((c) => (c.channel && !ok.has(c.channel) ? emptyCell() : c));
  }
  let outputs;
  if (cfg.outputs && typeof cfg.outputs === "object") {
    outputs = normalizeOutputs(cfg.outputs);
  } else {
    const def = defaultOutputs();
    const legacyRes = cfg.render?.resolution;
    if (RENDER_RESOLUTIONS.includes(legacyRes)) def.hls.resolution = legacyRes;
    outputs = normalizeOutputs(def);
  }
  const legacy = legacyScaleInputs(cfg);
  return {
    schema_version: WALL_SCHEMA_VERSION,
    layout: { rows, cols },
    preset: typeof cfg.preset === "string" ? cfg.preset : null,
    reload_epoch: reloadInt(cfg.reload_epoch),
    feed: normalizeScaleBlock(cfg.feed, "feed", legacy),
    ticker: normalizeScaleBlock(cfg.ticker, "ticker", legacy),
    autofit: normalizeAutoFit(cfg.autofit),
    outputs,
    cells,
  };
}

// ----- the four view tunables (1..10 integer steps, default 5) -----
// MIRROR of helper store.py's ladders — the wire carries the STEP; the ladder maps
// it to the realised CSS value. Keep in lockstep with the helper (the same contract
// the resolution ladder has with the renderer). See ARCHITECTURE §44 for how each
// endpoint was derived from what actually renders.
export const SCALE_STEP_MIN = 1;
export const SCALE_STEP_MAX = 10;
export const SCALE_STEP_DEFAULT = 5;

export const FEED_WIDTH_STEPS = [12, 18, 23, 27, 32, 38, 44, 50, 57, 64];   // % of the wall
export const FEED_TEXT_STEPS = [0.60, 0.70, 0.80, 0.90, 1.00, 1.15, 1.32, 1.52, 1.75, 2.00];
// The ticker ladders are identical from step 4 up (matching steps ⇒ the old
// proportional feel); they diverge at 1-3 only, where the text has a legibility
// floor the box does not.
export const TICKER_HEIGHT_STEPS = [0.45, 0.58, 0.70, 0.85, 1.00, 1.25, 1.55, 1.90, 2.30, 2.75];
export const TICKER_TEXT_STEPS = [0.55, 0.62, 0.72, 0.85, 1.00, 1.25, 1.55, 1.90, 2.30, 2.75];

/** block → key → ladder. The single source of truth for what the four controls are. */
export const SCALE_BLOCKS = {
  feed: { width_scale: FEED_WIDTH_STEPS, text_scale: FEED_TEXT_STEPS },
  ticker: { height_scale: TICKER_HEIGHT_STEPS, text_scale: TICKER_TEXT_STEPS },
};

/** Clamp+coerce one step to an integer 1..10 (absent/garbage → 5). Mirrors the
 *  helper's _validate_step, except a non-number degrades to the default here rather
 *  than throwing: the client repairs, the server is the one that rejects. Pure. */
export function clampStep(v) {
  // Only a number, or the numeric STRING a range input's .value actually is. NOT a
  // blanket Number(): Number(null) and Number([]) are 0, which would silently snap a
  // null/garbage step to 1 (the narrowest setting) instead of the default.
  const n = typeof v === "number" ? v : (typeof v === "string" && v.trim() !== "" ? Number(v) : NaN);
  if (!Number.isFinite(n)) return SCALE_STEP_DEFAULT;
  return Math.min(SCALE_STEP_MAX, Math.max(SCALE_STEP_MIN, Math.round(n)));
}

/** The realised CSS value for a step on one control's ladder. Pure. */
export function stepValue(block, key, step) {
  const ladder = SCALE_BLOCKS[block]?.[key];
  if (!ladder) return null;
  return ladder[clampStep(step) - 1];
}

/** The nearest 1..10 step for a legacy continuous value (migration). `null` when the
 *  value isn't a finite number, so the caller falls through to the default. Ties go
 *  to the LOWER step, so migration is deterministic + idempotent. Pure. */
export function nearestStep(value, ladder) {
  const n = Number(value);
  if (!Number.isFinite(n)) return null;
  let best = 0;
  for (let i = 1; i < ladder.length; i++) {
    if (Math.abs(ladder[i] - n) < Math.abs(ladder[best] - n)) best = i;
  }
  return best + 1;
}

/** A scale block at its defaults (every key on step 5 — the pre-PR-024 look). */
export function defaultScaleBlock(block) {
  const out = {};
  for (const key of Object.keys(SCALE_BLOCKS[block])) out[key] = SCALE_STEP_DEFAULT;
  return out;
}

/** Normalise ONE scale block, MIGRATING from the retired continuous keys when a key
 *  is absent (feed_pct → feed.width_scale, feed_font → feed.text_scale, ticker_scale
 *  → BOTH ticker keys). An explicitly-present new key always wins. Pure. */
export function normalizeScaleBlock(raw, block, legacy = {}) {
  const src = raw && typeof raw === "object" ? raw : {};
  const out = {};
  for (const [key, ladder] of Object.entries(SCALE_BLOCKS[block])) {
    if (src[key] !== undefined && src[key] !== null) { out[key] = clampStep(src[key]); continue; }
    const migrated = nearestStep(legacy[`${block}.${key}`], ladder);
    out[key] = migrated === null ? SCALE_STEP_DEFAULT : migrated;
  }
  return out;
}

/** The retired continuous keys, mapped onto the new keys they migrate into. Pure. */
export function legacyScaleInputs(cfg) {
  return {
    "feed.width_scale": cfg?.feed_pct,
    "feed.text_scale": cfg?.feed_font,
    "ticker.height_scale": cfg?.ticker_scale,
    "ticker.text_scale": cfg?.ticker_scale,
  };
}

// ---- auto-fit (MYMTS-001) — mirrors the helper's AUTOFIT_MIN/MAX_PCT ----
export const AUTOFIT_MIN_PCT = 1.0;
export const AUTOFIT_MAX_PCT = 95.0;

/** Clamp a solved width, or `null` for "not solved yet". Pure. */
export function clampAutoFitPct(pct) {
  const n = typeof pct === "number" ? pct
    : (typeof pct === "string" && pct.trim() !== "" ? Number(pct) : NaN);
  if (!Number.isFinite(n)) return null;
  return Math.min(AUTOFIT_MAX_PCT, Math.max(AUTOFIT_MIN_PCT, n));
}

/** Normalise the auto-fit block (lenient mirror of the server validator). Pure. */
export function normalizeAutoFit(raw) {
  const src = raw && typeof raw === "object" ? raw : {};
  return { enabled: src.enabled === true, feed_width_pct: clampAutoFitPct(src.feed_width_pct) };
}

/** THE render contract: a config → the exact CSS custom properties the wall needs.
 *  Pure (no DOM), so the per-step mapping is unit-tested against the real code path
 *  the render page uses — see applyWallViewTunables in app.mjs. */
export function scaleCssVars(config) {
  const feed = normalizeScaleBlock(config?.feed, "feed", legacyScaleInputs(config));
  const ticker = normalizeScaleBlock(config?.ticker, "ticker", legacyScaleInputs(config));
  const af = normalizeAutoFit(config?.autofit);
  // AUTO-FIT (MYMTS-001) overrides the feed WIDTH only, and only once it has actually
  // been solved. `enabled` with a null width means "on, but nothing has computed it
  // yet" (no renderer has run) — fall back to the operator's rung rather than
  // rendering something arbitrary. The other three controls are never touched by auto.
  const autoWidth = af.enabled && af.feed_width_pct !== null;
  return {
    "--feed-pct": autoWidth
      ? `${af.feed_width_pct}%`
      : `${stepValue("feed", "width_scale", feed.width_scale)}%`,
    "--feed-font": String(stepValue("feed", "text_scale", feed.text_scale)),
    "--ticker-scale": String(stepValue("ticker", "height_scale", ticker.height_scale)),
    "--ticker-text-scale": String(stepValue("ticker", "text_scale", ticker.text_scale)),
  };
}

/** Set ONE control's step (clamped 1..10). Partial by construction: the other three
 *  keys ride through untouched, so a slider can never clobber its neighbours. Pure. */
export function withScaleStep(config, block, key, step) {
  const cfg = normalizeConfig(config);
  if (!SCALE_BLOCKS[block]?.[key]) return cfg;
  const next = { ...cfg, [block]: { ...cfg[block], [key]: clampStep(step) } };
  // MOVING THE FEED-WIDTH CONTROL EXITS AUTO-FIT (MYMTS-001 decision 3). Auto is a
  // mode; a manual move is the operator taking the wheel back, and leaving auto armed
  // would have it silently overwrite them on the next recompute. The solved width is
  // KEPT (not cleared) so re-enabling is instant and the value stays auditable — the
  // `enabled` flag alone decides whether it is applied.
  if (block === "feed" && key === "width_scale" && next.autofit.enabled) {
    next.autofit = { ...next.autofit, enabled: false };
  }
  return next;
}

/** Turn the auto-fit MODE on or off. Does not clear a previously solved width. Pure. */
export function withAutoFit(config, enabled) {
  const cfg = normalizeConfig(config);
  return { ...cfg, autofit: { ...cfg.autofit, enabled: enabled === true } };
}

/** Record the SOLVED feed width (an exact percentage, clamped). Written by the render
 *  surface — the only one whose geometry defines the TV output. `null` clears it back
 *  to unsolved. Pure. */
export function withAutoFitWidth(config, pct) {
  const cfg = normalizeConfig(config);
  return { ...cfg, autofit: { ...cfg.autofit, feed_width_pct: clampAutoFitPct(pct) } };
}

/** Apply SEVERAL control edits at once — the pure core of /control/'s debounced
 *  commit. Nudging two sliders inside one debounce window must produce ONE write
 *  carrying BOTH, never a write that drops the earlier one; a later edit of the SAME
 *  control wins. Edits are `{block, key, step}`. Pure. */
export function withScaleSteps(config, edits) {
  let cfg = normalizeConfig(config);
  for (const e of edits || []) cfg = withScaleStep(cfg, e.block, e.key, e.step);
  return cfg;
}

/** Bump the WHOLE-WALL force-reload epoch (+1): the rendered wall reattaches
 *  EVERY video tile when it sees this increase. The "Reload all" action. Pure. */
export function withWallReload(config) {
  const cfg = normalizeConfig(config);
  return { ...cfg, reload_epoch: reloadInt(cfg.reload_epoch) + 1 };
}

/** Bump ONE cell's force-reload epoch (+1): the rendered wall reattaches just
 *  that tile when it sees the increase. The per-cell "Reload" action. Pure. */
export function withCellReload(config, index) {
  const cfg = normalizeConfig(config);
  if (index < 0 || index >= cfg.cells.length) return cfg;
  const cells = cfg.cells.map((c, i) =>
    i === index ? { ...c, reload: reloadInt(c.reload) + 1 } : c);
  return { ...cfg, cells };
}

/** Assign (or clear, slug=null) a cell's channel. Pure (audio/subtitles ride along). */
export function withCellChannel(config, index, slug) {
  const cfg = normalizeConfig(config);
  if (index < 0 || index >= cfg.cells.length) return cfg;
  const cells = cfg.cells.map((c, i) =>
    i === index ? { ...c, channel: slug || null } : c);
  return { ...cfg, cells };
}

/** Toggle a cell's per-tile AUDIO on/off. ANY combination may be on (multiple
 *  unmuted cells mix in the render's sink). Default off (muted). Pure. */
export function withCellAudio(config, index) {
  const cfg = normalizeConfig(config);
  if (index < 0 || index >= cfg.cells.length) return cfg;
  const cells = cfg.cells.map((c, i) => i === index ? { ...c, audio: !c.audio } : c);
  return { ...cfg, cells };
}

/** Toggle a cell's subtitles on/off (per-cell, native captionsOnSlots parity). */
export function withCellSubtitles(config, index) {
  const cfg = normalizeConfig(config);
  if (index < 0 || index >= cfg.cells.length) return cfg;
  const cells = cfg.cells.map((c, i) =>
    i === index ? { ...c, subtitles: !c.subtitles } : c);
  return { ...cfg, cells };
}

// ----- outputs transforms (the /control/ Outputs section) -----

/** Patch ONE output's fields (clamped via normalizeOutputs). Unknown name → no-op. */
export function withOutput(config, name, patch) {
  const cfg = normalizeConfig(config);
  if (!OUTPUT_NAMES.includes(name)) return cfg;
  const outputs = normalizeOutputs({ ...cfg.outputs, [name]: { ...cfg.outputs[name], ...patch } });
  return { ...cfg, outputs };
}
export function withOutputEnabled(config, name, on) {
  return withOutput(config, name, { enabled: on === true });
}
export function withOutputResolution(config, name, resolution) {
  return withOutput(config, name, { resolution });
}
export function withOutputBitrate(config, name, kbps) {
  return withOutput(config, name, { bitrate_kbps: kbps });
}
export function withOutputAudio(config, name, on) {
  return withOutput(config, name, { audio: on === true });
}
/** Bump an output's restart_epoch (+1): cycle that one output's encoder. */
export function withOutputRestart(config, name) {
  const cfg = normalizeConfig(config);
  if (!OUTPUT_NAMES.includes(name)) return cfg;
  return withOutput(config, name, { restart_epoch: reloadInt(cfg.outputs[name].restart_epoch) + 1 });
}

/** Change the grid layout (rows × cols, each clamped 1..3), resizing the cells
 *  by index. Per-cell audio/subtitles ride along by index. Pure. */
export function withLayout(config, rows, cols) {
  const cfg = normalizeConfig(config);
  const r = clampGridDim(rows);
  const c = clampGridDim(cols);
  const cells = resizeCells(cfg.cells, r * c);
  return { ...cfg, layout: { rows: r, cols: c }, cells };
}

/** Apply a server preset: take its grid + fill cells from its slugs (filtered to
 *  channels that exist), reset audio + subtitles. Carries reload_epoch + outputs +
 *  the view tunables forward (orthogonal to the channel preset). */
export function withPreset(config, preset, validSlugs) {
  const cfg = normalizeConfig(config);
  if (!preset) return cfg;
  const ok = validSlugs instanceof Set ? validSlugs : new Set(validSlugs || []);
  const rows = clampGridDim(preset.grid?.rows ?? cfg.layout.rows);
  const cols = clampGridDim(preset.grid?.cols ?? cfg.layout.cols);
  const count = rows * cols;
  const fill = (Array.isArray(preset.slugs) ? preset.slugs : []).filter((s) => ok.has(s));
  const cells = [];
  for (let i = 0; i < count; i++) {
    cells.push({ channel: i < fill.length ? fill[i] : null, audio: false, subtitles: false, reload: 0 });
  }
  return {
    schema_version: WALL_SCHEMA_VERSION,
    layout: { rows, cols },
    preset: preset.id ?? null,
    reload_epoch: reloadInt(cfg.reload_epoch),
    // The view tunables are ORTHOGONAL to a channel preset — they ride through
    // unchanged (cfg is already normalized, so these are the live steps, not defaults).
    feed: { ...cfg.feed },
    ticker: { ...cfg.ticker },
    // Auto-fit is orthogonal to a CHANNEL preset — but a preset can change the grid,
    // which changes the answer, so the mode rides through and the wall re-solves.
    autofit: { ...cfg.autofit },
    outputs: normalizeOutputs(cfg.outputs),
    cells,
  };
}
