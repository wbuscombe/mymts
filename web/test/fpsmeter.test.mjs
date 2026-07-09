// Pure-logic tests for the render fpsmeter's core: fps math from cumulative
// counters, the variant-vs-cell overdraw decision (the prime frame-killer), and
// the wall verdict (worst-tile, not average). No DOM — the module's wiring no-ops
// under node, so importing it here is safe.
//   Run: `node --test web/test/*.test.mjs`

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  decodedStats,
  presentedFps,
  variantVsCell,
  summarizeTiles,
  wallVerdict,
  meterRequested,
  OVERDRAW_WASTE_X,
} from "../js/fpsmeter.mjs";

test("decodedStats: decoded fps + drop-% from two cumulative snapshots", () => {
  // 30 frames decoded over 1s, 3 of them dropped → 30fps, 10% drop.
  const a = { totalVideoFrames: 100, droppedVideoFrames: 5, tMs: 1000 };
  const b = { totalVideoFrames: 130, droppedVideoFrames: 8, tMs: 2000 };
  const s = decodedStats(a, b);
  assert.equal(s.decodedFps, 30);
  assert.equal(s.dropPct, 10);
  assert.equal(s.dTotal, 30);
  assert.equal(s.dDropped, 3);
});

test("decodedStats: zero frames created in the window → 0% drop, not NaN", () => {
  const a = { totalVideoFrames: 100, droppedVideoFrames: 5, tMs: 1000 };
  const b = { totalVideoFrames: 100, droppedVideoFrames: 5, tMs: 2000 };
  const s = decodedStats(a, b);
  assert.equal(s.decodedFps, 0);
  assert.equal(s.dropPct, 0);
});

test("decodedStats: a non-advancing/negative interval returns nulls (no fabrication)", () => {
  const a = { totalVideoFrames: 100, droppedVideoFrames: 5, tMs: 2000 };
  const b = { totalVideoFrames: 130, droppedVideoFrames: 8, tMs: 2000 };  // dt=0
  const s = decodedStats(a, b);
  assert.equal(s.decodedFps, null);
  assert.equal(s.dropPct, null);
});

test("presentedFps: rVFC counter delta over the interval", () => {
  assert.equal(presentedFps({ presentedFrames: 300, tMs: 0 }, { presentedFrames: 330, tMs: 1000 }), 30);
  // a stall = flat counter → 0 fps
  assert.equal(presentedFps({ presentedFrames: 330, tMs: 1000 }, { presentedFrames: 330, tMs: 2000 }), 0);
  // dt<=0 → null
  assert.equal(presentedFps({ presentedFrames: 0, tMs: 1000 }, { presentedFrames: 30, tMs: 1000 }), null);
});

test("variantVsCell: a 1080p variant in a ~640px cell is wasteful overdraw", () => {
  // 1920x1080 decoded, painted into a 640x360 device-px cell → ~9x overdraw.
  const r = variantVsCell({ variantW: 1920, variantH: 1080, cellCssW: 640, cellCssH: 360, dpr: 1 });
  assert.ok(r.overdrawX > 8 && r.overdrawX < 10, `overdrawX=${r.overdrawX}`);
  assert.equal(r.wasteful, true);
});

test("variantVsCell: a right-sized variant is NOT wasteful", () => {
  // a 640x360 variant in a 640x360 cell → 1.0x, not wasteful.
  const r = variantVsCell({ variantW: 640, variantH: 360, cellCssW: 640, cellCssH: 360, dpr: 1 });
  assert.equal(Math.round(r.overdrawX * 10) / 10, 1.0);
  assert.equal(r.wasteful, false);
});

test("variantVsCell: dpr scales the cell's device pixels (a 4K canvas needs more)", () => {
  // same CSS cell but dpr=2 → 4x the device px → the 1280x720 variant is ~right.
  const r = variantVsCell({ variantW: 1280, variantH: 720, cellCssW: 640, cellCssH: 360, dpr: 2 });
  assert.equal(Math.round(r.overdrawX * 10) / 10, 1.0);
  assert.equal(r.wasteful, false);
});

test("variantVsCell: an unknown variant (single-rendition/native) is never a false 'wasteful'", () => {
  const r = variantVsCell({ variantW: null, variantH: null, cellCssW: 640, cellCssH: 360, dpr: 1 });
  assert.equal(r.overdrawX, null);
  assert.equal(r.wasteful, false);
});

test("summarizeTiles: aggregates worst-case, excluding un-measured tiles", () => {
  const tiles = [
    { presentedFps: 30, decodedFps: 30, dropPct: 1, overdrawX: 9 },
    { presentedFps: 8, decodedFps: 12, dropPct: 40, overdrawX: 6 },
    { presentedFps: null, decodedFps: null, dropPct: null, overdrawX: null }, // offline / not started
  ];
  const s = summarizeTiles(tiles);
  assert.equal(s.tileCount, 3);
  assert.equal(s.measuredCount, 2);
  assert.equal(s.minPresentedFps, 8);
  assert.equal(s.maxDropPct, 40);
  assert.equal(s.worstOverdrawX, 9);
  assert.equal(s.wastefulTiles, 2);  // both 9x and 6x exceed OVERDRAW_WASTE_X
});

test("wallVerdict: FAILs on the worst tile, not the average", () => {
  // one tile at 8fps drags the wall to FAIL even though the mean is ~19fps.
  const s = summarizeTiles([
    { presentedFps: 30, dropPct: 1, overdrawX: 1 },
    { presentedFps: 8, dropPct: 40, overdrawX: 1 },
  ]);
  const v = wallVerdict(s);
  assert.equal(v.pass, false);
  assert.ok(v.reasons.some((r) => r.includes("slowest tile")));
  assert.ok(v.reasons.some((r) => r.includes("worst drop")));
});

test("wallVerdict: PASS when every measured tile clears the bar", () => {
  const s = summarizeTiles([
    { presentedFps: 30, dropPct: 1, overdrawX: 1 },
    { presentedFps: 29, dropPct: 3, overdrawX: 1 },
  ]);
  assert.deepEqual(wallVerdict(s), { pass: true, reasons: [] });
});

test("wallVerdict: no tiles measured is an honest fail, not a vacuous pass", () => {
  const v = wallVerdict(summarizeTiles([{ presentedFps: null, dropPct: null, overdrawX: null }]));
  assert.equal(v.pass, false);
});

test("meterRequested: only ?fpsmeter=1 arms it", () => {
  assert.equal(meterRequested("?fpsmeter=1"), true);
  assert.equal(meterRequested("?render=1&fpsmeter=1"), true);
  assert.equal(meterRequested("?render=1"), false);
  assert.equal(meterRequested(""), false);
  assert.equal(meterRequested("?fpsmeter=0"), false);
});

test("OVERDRAW_WASTE_X is a sane threshold (>1, the nearest-larger-variant allowance)", () => {
  assert.ok(OVERDRAW_WASTE_X > 1 && OVERDRAW_WASTE_X <= 2);
});
