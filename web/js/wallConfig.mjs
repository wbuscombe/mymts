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
export const OUTPUT_NAMES = ["hls", "mercury"];
export const MERCURY_MAX_RESOLUTION = "1080p";
export const BITRATE_KBPS_MIN = 500;
export const BITRATE_KBPS_CEIL = 60000;
export const RES_BITRATE_KBPS = {
  "720p": 4000, "900p": 6000, "1080p": 8000, "1260p": 10000,
  "1440p": 12000, "1620p": 14000, "1800p": 16000, "2160p": 20000,
};
export const DEFAULT_DISPLAY_NAME = "MyMTS News Wall";

function ladderIndex(res) {
  const i = RENDER_RESOLUTIONS.indexOf(res);
  return i < 0 ? RENDER_RESOLUTIONS.indexOf(DEFAULT_RESOLUTION) : i;
}
function clampResolution(v, def) { return RENDER_RESOLUTIONS.includes(v) ? v : def; }
function capMercury(res) {
  return ladderIndex(res) > ladderIndex(MERCURY_MAX_RESOLUTION) ? MERCURY_MAX_RESOLUTION : res;
}

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
    mercury: {
      enabled: false, resolution: "720p", bitrate_kbps: 3000, audio: true, restart_epoch: 0,
      channel_guid: "", display_name: DEFAULT_DISPLAY_NAME,
    },
  };
}

function normalizeOutput(name, raw, def) {
  const src = raw && typeof raw === "object" ? raw : {};
  let res = clampResolution(src.resolution ?? def.resolution, def.resolution);
  if (name === "mercury") res = capMercury(res);
  const out = {
    enabled: src.enabled === undefined ? def.enabled : src.enabled === true,
    resolution: res,
    bitrate_kbps: clampBitrate(src.bitrate_kbps ?? def.bitrate_kbps, res),
    audio: src.audio === undefined ? def.audio : src.audio === true,
    restart_epoch: reloadInt(src.restart_epoch),
  };
  if (name === "mercury") {
    out.channel_guid = typeof src.channel_guid === "string" ? src.channel_guid : def.channel_guid;
    out.display_name = typeof src.display_name === "string" ? src.display_name : def.display_name;
  }
  return out;
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

/** Normalise/clamp the outputs map (each output to the ladder + sane bitrate;
 *  Mercury ≤1080p). Always returns the two known outputs. Pure. */
export function normalizeOutputs(raw) {
  const def = defaultOutputs();
  const src = raw && typeof raw === "object" ? raw : {};
  return {
    hls: normalizeOutput("hls", src.hls, def.hls),
    mercury: normalizeOutput("mercury", src.mercury, def.mercury),
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
  return {
    schema_version: WALL_SCHEMA_VERSION,
    layout: { rows, cols },
    preset: typeof cfg.preset === "string" ? cfg.preset : null,
    reload_epoch: reloadInt(cfg.reload_epoch),
    feed_pct: clampScale(cfg.feed_pct, FEED_PCT),
    feed_font: clampScale(cfg.feed_font, FEED_FONT),
    ticker_scale: clampScale(cfg.ticker_scale, TICKER_SCALE),
    outputs,
    cells,
  };
}

/** Fine-grained, proportional view tunables — bounds mirror the helper. Clamped on
 *  read; absent → default. Pure. */
export const FEED_PCT = { min: 18, max: 58, step: 1, default: 32 };
export const FEED_FONT = { min: 0.7, max: 1.6, step: 0.05, default: 1 };
export const TICKER_SCALE = { min: 0.6, max: 2.0, step: 0.05, default: 1 };
function clampScale(v, b) {
  const n = Number(v);
  return Number.isFinite(n) ? Math.min(b.max, Math.max(b.min, n)) : b.default;
}

/** Set the feed-column width (% of the wall), clamped fine-grained. Pure. */
export function withFeedPct(config, pct) {
  return { ...normalizeConfig(config), feed_pct: clampScale(pct, FEED_PCT) };
}

/** Set the feed text scale (proportional, within --ux), clamped. Pure. */
export function withFeedFont(config, scale) {
  return { ...normalizeConfig(config), feed_font: clampScale(scale, FEED_FONT) };
}

/** Set the ticker height+content scale (proportional), clamped. Pure. */
export function withTickerScale(config, scale) {
  return { ...normalizeConfig(config), ticker_scale: clampScale(scale, TICKER_SCALE) };
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
/** Bump an output's restart_epoch (+1): cycle that one output's encoder/publisher. */
export function withOutputRestart(config, name) {
  const cfg = normalizeConfig(config);
  if (!OUTPUT_NAMES.includes(name)) return cfg;
  return withOutput(config, name, { restart_epoch: reloadInt(cfg.outputs[name].restart_epoch) + 1 });
}
/** Set the Mercury non-secret fields (channel_guid / display_name). NO secrets. */
export function withMercuryFields(config, fields) {
  const patch = {};
  if (typeof fields?.channel_guid === "string") patch.channel_guid = fields.channel_guid;
  if (typeof fields?.display_name === "string") patch.display_name = fields.display_name;
  return withOutput(config, "mercury", patch);
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
    feed_pct: clampScale(cfg.feed_pct, FEED_PCT),
    feed_font: clampScale(cfg.feed_font, FEED_FONT),
    ticker_scale: clampScale(cfg.ticker_scale, TICKER_SCALE),
    outputs: normalizeOutputs(cfg.outputs),
    cells,
  };
}
