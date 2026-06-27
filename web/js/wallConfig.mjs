// Pure helpers for the SERVER-SIDE wall config (headless-container version).
//
// Shared by the picker control surface (/control/) which WRITES the config and
// the rendered wall (/app/) which READS it, so the two can never disagree on
// the shape or the edit semantics. Every function is a pure config → config
// transform (no DOM, no fetch) — unit-tested in web/test/wallConfig.test.mjs.
// Mirrors the server validator in helper/.../wall/store.py (same schema,
// same single-audible + per-cell-subtitle model).

import { clampGridDim } from "./render.mjs";

export const WALL_SCHEMA_VERSION = 1;

/** The render-resolution ladder the wall config understands (mirror of the helper's
 *  RENDER_RESOLUTIONS + the renderer's). The renderer maps each name → canvas + a
 *  SUSTAINABLE fps + bitrate; the web wall scales to any of them via --ux. */
export const RENDER_RESOLUTIONS = [
  "720p", "900p", "1080p", "1260p", "1440p", "1620p", "1800p", "2160p",
];
export const DEFAULT_RESOLUTION = "1080p";

/** Per-resolution display metadata for the /control/ chooser: the canvas, the
 *  sustainable fps on this CPU-only renderer, and the honest smoothness zone (from
 *  the smoothness envelope, ARCHITECTURE §31) so the operator's choice is informed —
 *  ≤1080p is the smooth zone; above it is marginal→heavy (GPU-territory). */
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

/** Coerce a render block to {resolution} with a valid ladder choice (else 1080p).
 *  Defensive on read so a bad/absent value never breaks the picker. Pure. */
function normalizeRender(render) {
  const res = render && typeof render === "object" ? render.resolution : null;
  return { resolution: RENDER_RESOLUTIONS.includes(res) ? res : DEFAULT_RESOLUTION };
}

/** Fine-grained, proportional view tunables — bounds mirror the helper (nothing
 *  collapses/overflows). Clamped on read; absent → default. Pure. */
export const FEED_PCT = { min: 18, max: 58, step: 1, default: 32 };
export const FEED_FONT = { min: 0.7, max: 1.6, step: 0.05, default: 1 };
export const TICKER_SCALE = { min: 0.6, max: 2.0, step: 0.05, default: 1 };
function clampScale(v, b) {
  const n = Number(v);
  return Number.isFinite(n) ? Math.min(b.max, Math.max(b.min, n)) : b.default;
}

/** A non-negative integer reload counter (else 0). Force-reload epochs are
 *  monotonic counters: a surface bumps one to signal "reload"; the rendered wall
 *  reattaches when it sees the value INCREASE. Defensive read for the wire. */
function reloadInt(v) { return Number.isInteger(v) && v >= 0 ? v : 0; }

/** A fresh empty cell. `reload` is the per-cell force-reload epoch (0 = never). */
function emptyCell() { return { channel: null, subtitles: false, reload: 0 }; }

/** Resize a cells array to exactly `count`, preserving assignments BY INDEX
 *  (pad with empties, truncate the overflow) — native LineupStore behaviour on
 *  a grid-dim change. The per-cell `reload` epoch rides along by index. Returns a
 *  new array (never mutates the input). */
export function resizeCells(cells, count) {
  const src = Array.isArray(cells) ? cells : [];
  const out = [];
  for (let i = 0; i < count; i++) {
    const c = src[i];
    out.push(c ? { channel: c.channel ?? null, subtitles: c.subtitles === true, reload: reloadInt(c.reload) } : emptyCell());
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
 *  well-formed object to render+edit (lenient mirror of the server validator:
 *  clamp dims, resize cells to match, drop an audible pointer that no longer
 *  references a populated cell). `validSlugs` (optional) clears a cell whose
 *  channel is no longer a known channel. Pure. */
export function normalizeConfig(raw, validSlugs = null) {
  const cfg = raw && typeof raw === "object" ? raw : {};
  const rows = clampGridDim(cfg.layout?.rows ?? 2);
  const cols = clampGridDim(cfg.layout?.cols ?? 2);
  const count = rows * cols;
  let cells = resizeCells(cfg.cells, count);
  if (validSlugs) {
    const ok = validSlugs instanceof Set ? validSlugs : new Set(validSlugs);
    cells = cells.map((c) => (c.channel && !ok.has(c.channel) ? emptyCell() : c));
  }
  let audible = Number.isInteger(cfg.audible_cell) ? cfg.audible_cell : null;
  if (audible == null || audible < 0 || audible >= count || !cells[audible].channel) {
    audible = null;
  }
  return {
    schema_version: WALL_SCHEMA_VERSION,
    layout: { rows, cols },
    preset: typeof cfg.preset === "string" ? cfg.preset : null,
    audible_cell: audible,
    reload_epoch: reloadInt(cfg.reload_epoch),
    // CRITICAL: carry these through normalize, else every commit() round-trip
    // (which runs the config through normalizeConfig) would STRIP them and silently
    // revert the choice on the next save.
    render: normalizeRender(cfg.render),
    feed_pct: clampScale(cfg.feed_pct, FEED_PCT),
    feed_font: clampScale(cfg.feed_font, FEED_FONT),
    ticker_scale: clampScale(cfg.ticker_scale, TICKER_SCALE),
    cells,
  };
}

/** Set the render resolution (a ladder rung; unknown → 1080p). The renderer
 *  re-reads this and restarts its Xvfb/ffmpeg stack at the new canvas + fps. Pure. */
export function withResolution(config, resolution) {
  const cfg = normalizeConfig(config);
  const res = RENDER_RESOLUTIONS.includes(resolution) ? resolution : DEFAULT_RESOLUTION;
  return { ...cfg, render: { resolution: res } };
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

/** Assign (or clear, slug=null) a cell's channel. Clearing a cell that owns the
 *  audio drops the audio pointer (an empty cell can't be the audio source). */
export function withCellChannel(config, index, slug) {
  const cfg = normalizeConfig(config);
  if (index < 0 || index >= cfg.cells.length) return cfg;
  const cells = cfg.cells.map((c, i) =>
    i === index ? { ...c, channel: slug || null } : c);
  let audible = cfg.audible_cell;
  if (!slug && audible === index) audible = null;
  return { ...cfg, cells, audible_cell: audible };
}

/** Toggle a cell's subtitles on/off (per-cell, native captionsOnSlots parity). */
export function withCellSubtitles(config, index) {
  const cfg = normalizeConfig(config);
  if (index < 0 || index >= cfg.cells.length) return cfg;
  const cells = cfg.cells.map((c, i) =>
    i === index ? { ...c, subtitles: !c.subtitles } : c);
  return { ...cfg, cells };
}

/** Single-audible-cell toggle (native LineupStore.toggleAudible): set this cell
 *  as the audio source; toggling the already-audible cell mutes the wall; an
 *  EMPTY cell can never be made audible (no stream to unmute). */
export function withAudibleCell(config, index) {
  const cfg = normalizeConfig(config);
  if (index < 0 || index >= cfg.cells.length) return cfg;
  if (!cfg.cells[index].channel) return cfg;            // empty → no-op
  const audible = cfg.audible_cell === index ? null : index;
  return { ...cfg, audible_cell: audible };
}

/** Change the grid layout (rows × cols, each clamped 1..3), resizing the cells
 *  by index + dropping a now-invalid audio pointer. */
export function withLayout(config, rows, cols) {
  const cfg = normalizeConfig(config);
  const r = clampGridDim(rows);
  const c = clampGridDim(cols);
  const cells = resizeCells(cfg.cells, r * c);
  let audible = cfg.audible_cell;
  if (audible != null && (audible >= r * c || !cells[audible].channel)) audible = null;
  return { ...cfg, layout: { rows: r, cols: c }, cells, audible_cell: audible };
}

/** Apply a server preset: take its grid + fill cells from its slugs (filtered to
 *  channels that exist), reset audio + subtitles. `preset` is a /api/presets
 *  entry; `validSlugs` is the live channel set. Mirrors the wall's preset apply. */
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
    cells.push({ channel: i < fill.length ? fill[i] : null, subtitles: false, reload: 0 });
  }
  return {
    schema_version: WALL_SCHEMA_VERSION,
    layout: { rows, cols },
    preset: preset.id ?? null,
    audible_cell: null,
    // A preset reshapes the grid (fresh cells → per-cell reload resets to 0) but
    // the WHOLE-WALL reload epoch is monotonic, so carry it forward (resetting it
    // would make a later "reload all" look like it went backwards → missed).
    reload_epoch: reloadInt(cfg.reload_epoch),
    // Resolution + the view tunables are orthogonal to the channel preset — carry
    // them forward so applying a preset never resets the canvas / feed / ticker.
    render: normalizeRender(cfg.render),
    feed_pct: clampScale(cfg.feed_pct, FEED_PCT),
    feed_font: clampScale(cfg.feed_font, FEED_FONT),
    ticker_scale: clampScale(cfg.ticker_scale, TICKER_SCALE),
    cells,
  };
}
