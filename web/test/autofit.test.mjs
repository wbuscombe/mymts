// AUTO-FIT solver + mode (MYMTS-001).
//
// The geometry model was derived by MEASURING the live render surface at 1920×1080.
// These tests pin it against the recon's own published figures, so a change to any
// geometry term (divider, padding, gap, tile border) fails here rather than quietly
// shifting every computed width.

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  AUTOFIT_GEOMETRY, DEFAULT_ASPECT, solveFeedWidthPx, targetAspect,
  autoFitFeedWidth, residualBar,
} from "../js/scaleControls.mjs";
import {
  normalizeConfig, normalizeAutoFit, clampAutoFitPct, scaleCssVars,
  withAutoFit, withAutoFitWidth, withScaleStep, withPreset,
  AUTOFIT_MIN_PCT, AUTOFIT_MAX_PCT,
} from "../js/wallConfig.mjs";

// The measured live wall: 1920×1080 render surface, ticker occupying 50px.
const WALL = { wallW: 1920, wallH: 1080, tickerH: 50 };
const A169 = 16 / 9;
const FLOOR_PX = 220;                     // .feed-pane { min-width: calc(220 * var(--u)) }
const pct = (px) => (px / 1920) * 100;

// ---- the geometry terms themselves ----

test("the geometry constants match what was measured on the live wall", () => {
  assert.equal(AUTOFIT_GEOMETRY.DIVIDER, 5, "the pane divider is a real 5px flex item");
  assert.equal(AUTOFIT_GEOMETRY.GRID_PAD, 8, "grid-pane padding, EACH side");
  assert.equal(AUTOFIT_GEOMETRY.GAP, 8, "column-gap == row-gap");
  assert.equal(AUTOFIT_GEOMETRY.TILE_BORDER, 1, "tile border, EACH side, BOTH axes");
});

// ---- the solver, against the recon's published figures ----

test("solver reproduces the recon's grids to 0.01 percentage points", () => {
  // These four numbers came out of measuring the real wall. If the model drifts,
  // this is where it shows.
  const cases = [
    [2, 1, 52.41], [3, 1, 68.18], [3, 2, 37.05], [2, 2, 5.50],
  ];
  for (const [R, C, expectPct] of cases) {
    const px = solveFeedWidthPx({ ...WALL, rows: R, cols: C, aspect: A169 });
    assert.ok(Math.abs(pct(px) - expectPct) < 0.01,
      `${R}x${C}: got ${pct(px).toFixed(2)}%, recon measured ${expectPct}%`);
  }
});

test("solver reproduces the live wall's PRESENT geometry to within a pixel", () => {
  // Inverse check: at the operator's actual feed width (518.39px = 27%), the model
  // must predict the cell and video boxes that were measured on the page.
  const r = residualBar({ ...WALL, rows: 2, cols: 2, aspect: A169, feedPx: 518.39 });
  assert.ok(Math.abs(r.cellW - 686.30) < 1, `cell width ${r.cellW} vs measured 686.30`);
  assert.ok(Math.abs(r.cellH - 503.00) < 1, `cell height ${r.cellH} vs measured 503.00`);
  assert.ok(Math.abs(r.videoBoxW - 684.30) < 1, `video box w ${r.videoBoxW} vs 684.30`);
  assert.ok(Math.abs(r.videoBoxH - 501.00) < 1, `video box h ${r.videoBoxH} vs 501.00`);
  // and the bar it predicts must match the 58.19/58.04px actually measured
  assert.equal(r.barAxis, "top/bottom");
  assert.ok(Math.abs(r.barPx - 58.1) < 0.5, `predicted bar ${r.barPx} vs measured ~58.1`);
});

test("a taller ticker shortens the cells and therefore NARROWS the required feed", () => {
  const a = solveFeedWidthPx({ ...WALL, tickerH: 50, rows: 2, cols: 2, aspect: A169 });
  const b = solveFeedWidthPx({ ...WALL, tickerH: 110, rows: 2, cols: 2, aspect: A169 });
  assert.ok(b > a, "a taller ticker should make MORE feed width affordable");
  assert.ok(Math.abs(pct(b) - 11.06) < 0.05, `ticker step 10 -> ${pct(b).toFixed(2)}%, recon says 11.06%`);
});

test("solver refuses degenerate input rather than returning nonsense", () => {
  assert.equal(solveFeedWidthPx({ ...WALL, rows: 2, cols: 2, aspect: 0 }), null);
  assert.equal(solveFeedWidthPx({ ...WALL, rows: 2, cols: 2, aspect: NaN }), null);
  assert.equal(solveFeedWidthPx({ wallW: 0, wallH: 1080, tickerH: 50, rows: 2, cols: 2, aspect: A169 }), null);
  // a ticker taller than the wall leaves no room for a cell
  assert.equal(solveFeedWidthPx({ ...WALL, tickerH: 5000, rows: 2, cols: 2, aspect: A169 }), null);
});

// ---- the widget-exclusion policy ----

const VID = (a) => ({ kind: "video", aspect: a });
const WIDGET = (a) => ({ kind: "img", aspect: a });

test("POLICY: only VIDEO cells contribute to the target aspect", () => {
  // The radar is 600x550 (~1.09:1). Solved alone it wants a ~41% feed against the
  // videos' ~5.5%, so no single width satisfies both — three videos outrank one widget.
  const t = targetAspect([VID(1.77778), WIDGET(1.09091), VID(1.77778), VID(1.77917)]);
  assert.equal(t.sampled, 3, "the widget must not be sampled");
  assert.ok(Math.abs(t.aspect - 1.77778) < 0.001, `mean aspect ${t.aspect}`);
});

test("a grid with NO video cells reports why, rather than inventing a number", () => {
  const t = targetAspect([WIDGET(1.09091), WIDGET(1.09091)]);
  assert.equal(t.aspect, null);
  assert.match(t.reason, /no video cells/);
  const r = autoFitFeedWidth({ ...WALL, rows: 1, cols: 2, media: [WIDGET(1.09)], minPx: FLOOR_PX });
  assert.equal(r.ok, false);
  assert.equal(r.appliedPct, null, "must not apply a width it could not compute");
});

test("mixed video aspects average, and the spread is reported", () => {
  const t = targetAspect([VID(1.77778), VID(1.33333)]);
  assert.ok(Math.abs(t.aspect - 1.555555) < 1e-5);
  assert.ok(Math.abs(t.spread - 0.44445) < 1e-4, "spread lets a surface warn about mixed sources");
});

// ---- clamp + honesty ----

test("2x2 is UNREACHABLE and says so, clamping to the floor", () => {
  const r = autoFitFeedWidth({ ...WALL, rows: 2, cols: 2,
    media: [VID(A169), VID(A169), VID(A169)], minPx: FLOOR_PX });
  assert.equal(r.ok, true);
  assert.ok(Math.abs(r.idealPct - 5.50) < 0.01, `ideal ${r.idealPct}%`);
  assert.equal(r.reachable, false, "2x2 at 1080p cannot fit — it must NOT claim it did");
  assert.equal(r.clampedTo, "min-width floor");
  assert.ok(Math.abs(r.appliedPx - FLOOR_PX) < 0.01, `applied ${r.appliedPx}px should be the floor`);
  // and it reports the bar the operator will actually still see
  assert.ok(r.barPx > 0 && r.barPx < 25, `residual bar ${r.barPx}px (was ~58 before)`);
  assert.equal(r.barAxis, "top/bottom");
});

test("3x2 IS reachable and reports a zero bar", () => {
  const r = autoFitFeedWidth({ ...WALL, rows: 3, cols: 2,
    media: [VID(A169), VID(A169)], minPx: FLOOR_PX });
  assert.equal(r.reachable, true);
  assert.equal(r.clampedTo, null);
  assert.ok(Math.abs(r.appliedPct - 37.05) < 0.01, `applied ${r.appliedPct}%`);
  assert.ok(r.barPx < 0.01, `bars must reach zero when the fit is reachable, got ${r.barPx}px`);
});

test("3x1's ideal EXCEEDS the ladder's own maximum — auto must still express it", () => {
  const r = autoFitFeedWidth({ ...WALL, rows: 3, cols: 1, media: [VID(A169)], minPx: FLOOR_PX });
  assert.ok(Math.abs(r.idealPct - 68.18) < 0.01);
  assert.ok(r.idealPct > 64, "the ladder tops out at 64% — this is why auto stores an exact value");
  assert.equal(r.reachable, true, "68% is legal as an exact width even though no rung equals it");
});

test("clamping at the TOP is reported as such", () => {
  const r = autoFitFeedWidth({ ...WALL, rows: 3, cols: 1, media: [VID(A169)],
    minPx: FLOOR_PX, maxPct: 50 });
  assert.equal(r.reachable, false);
  assert.equal(r.clampedTo, "max width");
  assert.ok(Math.abs(r.appliedPct - 50) < 0.01);
});

// ---- the config mode ----

const cfg = () => normalizeConfig({ layout: { rows: 2, cols: 2 },
  cells: [{ channel: "a" }, { channel: "b" }, { channel: "c" }, { channel: "d" }] });

test("autofit defaults to off + unsolved, and is additive to an old config", () => {
  assert.deepEqual(cfg().autofit, { enabled: false, feed_width_pct: null });
  // a config written before this feature has no autofit key at all
  const old = normalizeConfig({ layout: { rows: 1, cols: 1 }, cells: [{ channel: "a" }],
    feed: { width_scale: 9 } });
  assert.equal(old.autofit.enabled, false);
  assert.equal(old.feed.width_scale, 9, "the manual rung is untouched");
});

test("scaleCssVars uses the SOLVED width only when auto is on AND solved", () => {
  let c = withAutoFitWidth(withAutoFit(cfg(), true), 37.05);
  assert.equal(scaleCssVars(c)["--feed-pct"], "37.05%");
  // on but unsolved -> fall back to the rung (a wall with no renderer still renders)
  c = withAutoFitWidth(withAutoFit(cfg(), true), null);
  assert.equal(scaleCssVars(c)["--feed-pct"], "32%");
  // solved but off -> the rung wins
  c = withAutoFit(withAutoFitWidth(cfg(), 37.05), false);
  assert.equal(scaleCssVars(c)["--feed-pct"], "32%");
});

test("auto NEVER touches the other three controls", () => {
  const c = withAutoFitWidth(withAutoFit(cfg(), true), 12.5);
  const v = scaleCssVars(c);
  assert.equal(v["--feed-font"], "1");
  assert.equal(v["--ticker-scale"], "1");
  assert.equal(v["--ticker-text-scale"], "1");
});

test("moving the feed-width control EXITS auto, and keeps the solved value", () => {
  const on = withAutoFitWidth(withAutoFit(cfg(), true), 37.05);
  const after = withScaleStep(on, "feed", "width_scale", 8);
  assert.equal(after.autofit.enabled, false, "a manual move must take the wheel back");
  assert.equal(after.autofit.feed_width_pct, 37.05, "the solved value stays auditable");
  assert.equal(after.feed.width_scale, 8);
  assert.equal(scaleCssVars(after)["--feed-pct"], "50%", "the rung applies again immediately");
});

test("moving a DIFFERENT control does not exit auto", () => {
  const on = withAutoFitWidth(withAutoFit(cfg(), true), 37.05);
  for (const [b, k] of [["feed", "text_scale"], ["ticker", "height_scale"], ["ticker", "text_scale"]]) {
    assert.equal(withScaleStep(on, b, k, 8).autofit.enabled, true, `${b}.${k} should not exit auto`);
  }
});

test("clampAutoFitPct bounds the value and maps junk to unsolved", () => {
  assert.equal(clampAutoFitPct(37.05), 37.05);
  assert.equal(clampAutoFitPct(0), AUTOFIT_MIN_PCT);
  assert.equal(clampAutoFitPct(999), AUTOFIT_MAX_PCT);
  for (const bad of ["wide", "", null, undefined, NaN, {}, [], true]) {
    assert.equal(clampAutoFitPct(bad), null, `clampAutoFitPct(${JSON.stringify(bad)})`);
  }
});

test("normalizeAutoFit is a lenient mirror of the server validator", () => {
  assert.deepEqual(normalizeAutoFit(undefined), { enabled: false, feed_width_pct: null });
  assert.deepEqual(normalizeAutoFit({ enabled: "yes" }), { enabled: false, feed_width_pct: null });
  assert.deepEqual(normalizeAutoFit({ enabled: true, feed_width_pct: 41.35 }),
    { enabled: true, feed_width_pct: 41.35 });
});

test("applying a preset carries the auto-fit mode through", () => {
  // A preset can change the GRID, which changes the answer — the mode must survive so
  // the wall re-solves rather than silently reverting to a rung.
  const on = withAutoFitWidth(withAutoFit(cfg(), true), 37.05);
  const out = withPreset(on, { id: "nature", grid: { rows: 3, cols: 2 }, slugs: [] }, []);
  assert.equal(out.autofit.enabled, true);
  assert.equal(out.autofit.feed_width_pct, 37.05);
});

test("DEFAULT_ASPECT is 16:9 (the fallback before any video reports a size)", () => {
  assert.ok(Math.abs(DEFAULT_ASPECT - 1.77778) < 1e-5);
});
