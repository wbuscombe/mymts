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
  withCellReload, withWallReload, withResolution,
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
  assert.deepEqual(out[0], { channel: "a", subtitles: true, reload: 0 });
  assert.deepEqual(out[1], { channel: null, subtitles: false, reload: 0 });
});

test("resizeCells carries the per-cell reload epoch along by index", () => {
  const out = resizeCells([{ channel: "a", subtitles: false, reload: 5 }, { channel: "b", reload: 2 }], 1);
  assert.equal(out[0].reload, 5);   // preserved through a truncating resize
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

// ---- normalizeConfig: null/garbage input never throws (the /app/ read path) ----

for (const bad of [null, undefined, 42, "str", []]) {
  test(`normalizeConfig(${JSON.stringify(bad)}) falls back to a valid default`, () => {
    const out = normalizeConfig(bad);
    assert.deepEqual(out.layout, { rows: 2, cols: 2 });
    assert.equal(out.cells.length, 4);
    assert.equal(out.audible_cell, null);
    assert.ok(out.cells.every((c) => c.channel === null && c.subtitles === false));
  });
}

test("normalizeConfig of a server-shaped wire payload yields render-ready state (/app/ hydrate)", () => {
  // The exact shape GET /api/wall returns (with the extra `stored` flag) → the
  // shape /app/'s hydrateFromWall consumes. normalizeConfig must keep the real
  // cells + audible and drop the wire-only `stored` field.
  const wire = {
    schema_version: 1, stored: true,
    layout: { rows: 1, cols: 2 }, preset: "news", audible_cell: 0,
    cells: [{ channel: "bbc-news", subtitles: true }, { channel: "cnn", subtitles: false }],
  };
  const out = normalizeConfig(wire, VALID);
  assert.equal(out.cells.length, 2);
  assert.deepEqual(out.cells.map((c) => c.channel), ["bbc-news", "cnn"]);
  assert.equal(out.cells[0].subtitles, true);
  assert.equal(out.audible_cell, 0);
  assert.equal(out.preset, "news");
  assert.equal(out.stored, undefined);   // the wire-only flag is not part of the config
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

// ---- force-reload epochs (the /control/ → /app/ reload signal) ----

test("normalizeConfig defaults the reload epochs (additive to schema v1)", () => {
  const out = normalizeConfig({ layout: { rows: 1, cols: 2 }, cells: [{ channel: "bbc-news" }, { channel: null }] });
  assert.equal(out.reload_epoch, 0);
  assert.ok(out.cells.every((c) => c.reload === 0));
});

test("normalizeConfig preserves bumped reload epochs from the wire", () => {
  const out = normalizeConfig({
    schema_version: 1, layout: { rows: 1, cols: 2 }, reload_epoch: 7,
    cells: [{ channel: "bbc-news", reload: 3 }, { channel: "cnn", reload: 0 }],
  }, VALID);
  assert.equal(out.reload_epoch, 7);
  assert.equal(out.cells[0].reload, 3);
});

test("normalizeConfig clamps a negative/garbage reload epoch to 0 (monotonic counter)", () => {
  const out = normalizeConfig({ layout: { rows: 1, cols: 1 }, reload_epoch: -4,
    cells: [{ channel: "bbc-news", reload: "x" }] }, VALID);
  assert.equal(out.reload_epoch, 0);
  assert.equal(out.cells[0].reload, 0);
});

test("withWallReload bumps ONLY the whole-wall epoch (+1), cells untouched", () => {
  const out = withWallReload(cfg2x2());
  assert.equal(out.reload_epoch, 1);                       // 0 → 1
  assert.ok(out.cells.every((c) => c.reload === 0));       // per-cell epochs unchanged
  assert.equal(withWallReload(out).reload_epoch, 2);       // monotonic on repeat
});

test("withCellReload bumps ONLY that cell's epoch (+1), others + wall untouched", () => {
  const out = withCellReload(cfg2x2(), 1);
  assert.equal(out.cells[1].reload, 1);                    // 0 → 1
  assert.equal(out.cells[0].reload, 0);                    // sibling unchanged
  assert.equal(out.reload_epoch, 0);                       // whole-wall epoch unchanged
  assert.equal(withCellReload(out, 1).cells[1].reload, 2); // monotonic on repeat
});

test("withCellReload on an out-of-range index is a no-op", () => {
  const out = withCellReload(cfg2x2(), 9);
  assert.deepEqual(out.cells.map((c) => c.reload), [0, 0, 0, 0]);
});

// ---- render resolution (the multi-resolution config field) ----

test("normalizeConfig defaults render to 1080p (additive to schema v1)", () => {
  const out = normalizeConfig({ layout: { rows: 1, cols: 1 }, cells: [{ channel: "bbc-news" }] });
  assert.deepEqual(out.render, { resolution: "1080p" });
});

test("normalizeConfig preserves a valid render and coerces a bad one", () => {
  assert.deepEqual(
    normalizeConfig({ layout: { rows: 1, cols: 1 }, render: { resolution: "2160p" }, cells: [{ channel: "bbc-news" }] }, VALID).render,
    { resolution: "2160p" },
  );
  // bad/unknown resolution → 1080p (defensive — a commit() must never persist junk)
  assert.deepEqual(
    normalizeConfig({ layout: { rows: 1, cols: 1 }, render: { resolution: "720p" }, cells: [{ channel: "bbc-news" }] }, VALID).render,
    { resolution: "1080p" },
  );
});

test("withResolution sets the resolution; unknown coerces to 1080p", () => {
  assert.deepEqual(withResolution(cfg2x2(), "2160p").render, { resolution: "2160p" });
  assert.deepEqual(withResolution(cfg2x2(), "8k").render, { resolution: "1080p" });
});

test("REGRESSION: a commit()-style normalize round-trip never strips render", () => {
  // The single highest-impact wiring bug: if normalizeConfig dropped render,
  // every /control/ save would revert 4K to 1080p. Prove it survives a round-trip.
  const c = withResolution(cfg2x2(), "2160p");
  assert.deepEqual(normalizeConfig(c, VALID).render, { resolution: "2160p" });
});

test("render survives the other edit transforms (orthogonal to channels/layout)", () => {
  const base = withResolution(cfg2x2(), "2160p");   // 4K wall
  assert.deepEqual(withCellChannel(base, 2, "bloomberg-tv").render, { resolution: "2160p" });
  assert.deepEqual(withLayout(base, 1, 2).render, { resolution: "2160p" });
  assert.deepEqual(withAudibleCell(base, 1).render, { resolution: "2160p" });
  // a preset reshapes channels/grid but must NOT revert the resolution
  assert.deepEqual(
    withPreset(base, { id: "x", grid: { rows: 1, cols: 1 }, slugs: ["cnn"] }, VALID).render,
    { resolution: "2160p" },
  );
});

test("edit transforms preserve the whole-wall reload epoch (never rewind it)", () => {
  const base = withWallReload(cfg2x2());   // reload_epoch = 1
  assert.equal(withCellChannel(base, 2, "bloomberg-tv").reload_epoch, 1);
  assert.equal(withCellSubtitles(base, 0).reload_epoch, 1);
  assert.equal(withAudibleCell(base, 1).reload_epoch, 1);
  assert.equal(withLayout(base, 1, 2).reload_epoch, 1);
  // a preset reshapes the grid (fresh cells, reload 0) but carries the wall epoch
  const out = withPreset(base, { id: "x", grid: { rows: 1, cols: 1 }, slugs: ["cnn"] }, VALID);
  assert.equal(out.reload_epoch, 1);
  assert.ok(out.cells.every((c) => c.reload === 0));
});
