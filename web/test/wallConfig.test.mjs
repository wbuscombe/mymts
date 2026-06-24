// Pure-logic tests for the server-side wall-config transforms (headless version).
// These pin the picker's edit semantics (the writes /control/ + /app/ make) and
// the normalisation /app/ uses to render FROM the config — the same single-
// audible + per-cell-subtitle model the helper validator enforces server-side.
// Run: `node --test web/test/*.test.mjs` (built-in node:test, no deps).

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  WALL_SCHEMA_VERSION,
  resizeCells, cellCount, normalizeConfig,
  withCellChannel, withCellSubtitles, withAudibleCell, withLayout, withPreset,
} from "../js/wallConfig.mjs";

const VALID = ["bbc-news", "cnn", "cbs-sports-hq", "bloomberg-tv"];

function cfg2x2() {
  return {
    schema_version: 1,
    layout: { rows: 2, cols: 2 },
    preset: "news",
    audible_cell: 0,
    cells: [
      { channel: "bbc-news", subtitles: false },
      { channel: "cnn", subtitles: true },
      { channel: null, subtitles: false },
      { channel: "cbs-sports-hq", subtitles: false },
    ],
  };
}

// ---- resizeCells: preserve by index, pad/truncate ----

test("resizeCells preserves by index, pads with empties", () => {
  const out = resizeCells([{ channel: "a", subtitles: true }], 3);
  assert.equal(out.length, 3);
  assert.deepEqual(out[0], { channel: "a", subtitles: true });
  assert.deepEqual(out[1], { channel: null, subtitles: false });
});

test("resizeCells truncates the overflow", () => {
  const out = resizeCells([{ channel: "a" }, { channel: "b" }, { channel: "c" }], 2);
  assert.equal(out.length, 2);
  assert.deepEqual(out.map((c) => c.channel), ["a", "b"]);
});

// ---- normalizeConfig: defensive repair ----

test("normalizeConfig clamps dims and resizes cells to match", () => {
  const out = normalizeConfig({ layout: { rows: 9, cols: 0 }, cells: [] });
  assert.equal(out.layout.rows, 3);
  assert.equal(out.layout.cols, 1);
  assert.equal(out.cells.length, 3);
  assert.equal(WALL_SCHEMA_VERSION, out.schema_version);
});

test("normalizeConfig drops an audible pointer at an empty cell", () => {
  const out = normalizeConfig({ layout: { rows: 1, cols: 2 }, audible_cell: 1,
    cells: [{ channel: "bbc-news" }, { channel: null }] });
  assert.equal(out.audible_cell, null);
});

test("normalizeConfig clears a cell whose channel vanished (validSlugs)", () => {
  const out = normalizeConfig(cfg2x2(), ["cnn", "cbs-sports-hq"]);  // bbc-news gone
  assert.equal(out.cells[0].channel, null);
  assert.equal(out.audible_cell, null);   // cell 0 was audible → dropped
  assert.equal(out.cells[1].channel, "cnn");
});

test("cellCount reflects the layout", () => {
  assert.equal(cellCount({ layout: { rows: 3, cols: 2 } }), 6);
});

// ---- withCellChannel ----

test("withCellChannel assigns a cell", () => {
  const out = withCellChannel(cfg2x2(), 2, "bloomberg-tv");
  assert.equal(out.cells[2].channel, "bloomberg-tv");
});

test("withCellChannel clearing the audible cell drops audio", () => {
  const out = withCellChannel(cfg2x2(), 0, null);  // cell 0 is audible
  assert.equal(out.cells[0].channel, null);
  assert.equal(out.audible_cell, null);
});

test("withCellChannel clearing a NON-audible cell keeps audio", () => {
  const out = withCellChannel(cfg2x2(), 1, null);  // cell 1 not audible
  assert.equal(out.audible_cell, 0);
});

// ---- withCellSubtitles ----

test("withCellSubtitles toggles per cell", () => {
  const a = withCellSubtitles(cfg2x2(), 0);
  assert.equal(a.cells[0].subtitles, true);
  const b = withCellSubtitles(a, 0);
  assert.equal(b.cells[0].subtitles, false);
  assert.equal(b.cells[1].subtitles, true);   // others untouched
});

// ---- withAudibleCell: single-audible model ----

test("withAudibleCell moves audio to a populated cell", () => {
  const out = withAudibleCell(cfg2x2(), 1);
  assert.equal(out.audible_cell, 1);
});

test("withAudibleCell toggling the audible cell mutes the wall", () => {
  const out = withAudibleCell(cfg2x2(), 0);  // 0 is already audible
  assert.equal(out.audible_cell, null);
});

test("withAudibleCell on an empty cell is a no-op", () => {
  const out = withAudibleCell(cfg2x2(), 2);  // cell 2 empty
  assert.equal(out.audible_cell, 0);          // unchanged
});

// ---- withLayout: resize + audio reconciliation ----

test("withLayout resizes and preserves assignments by index", () => {
  const out = withLayout(cfg2x2(), 1, 2);  // 4 → 2 cells
  assert.equal(out.cells.length, 2);
  assert.deepEqual(out.cells.map((c) => c.channel), ["bbc-news", "cnn"]);
  assert.equal(out.audible_cell, 0);        // cell 0 survives + still populated
});

test("withLayout drops audio if the audible cell is truncated away", () => {
  const base = { ...cfg2x2(), audible_cell: 3 };   // cell 3 audible
  const out = withLayout(base, 1, 2);              // truncates to 2 cells
  assert.equal(out.audible_cell, null);
});

// ---- withPreset ----

test("withPreset fills from preset slugs, sets grid, resets audio+subs", () => {
  const preset = { id: "nature", grid: { rows: 1, cols: 2 }, slugs: ["cnn", "bbc-news", "x"] };
  const out = withPreset(cfg2x2(), preset, VALID);
  assert.deepEqual(out.layout, { rows: 1, cols: 2 });
  assert.deepEqual(out.cells.map((c) => c.channel), ["cnn", "bbc-news"]);
  assert.equal(out.preset, "nature");
  assert.equal(out.audible_cell, null);
  assert.ok(out.cells.every((c) => c.subtitles === false));
});

test("withPreset filters slugs that are not real channels", () => {
  const preset = { id: "x", grid: { rows: 1, cols: 3 }, slugs: ["ghost", "cnn"] };
  const out = withPreset(cfg2x2(), preset, VALID);
  // ghost dropped → cnn lands in cell 0, rest empty
  assert.deepEqual(out.cells.map((c) => c.channel), ["cnn", null, null]);
});
