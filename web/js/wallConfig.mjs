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

/** A fresh empty cell. */
function emptyCell() { return { channel: null, subtitles: false }; }

/** Resize a cells array to exactly `count`, preserving assignments BY INDEX
 *  (pad with empties, truncate the overflow) — native LineupStore behaviour on
 *  a grid-dim change. Returns a new array (never mutates the input). */
export function resizeCells(cells, count) {
  const src = Array.isArray(cells) ? cells : [];
  const out = [];
  for (let i = 0; i < count; i++) {
    const c = src[i];
    out.push(c ? { channel: c.channel ?? null, subtitles: c.subtitles === true } : emptyCell());
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
    cells,
  };
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
    cells.push({ channel: i < fill.length ? fill[i] : null, subtitles: false });
  }
  return {
    schema_version: WALL_SCHEMA_VERSION,
    layout: { rows, cols },
    preset: preset.id ?? null,
    audible_cell: null,
    cells,
  };
}
