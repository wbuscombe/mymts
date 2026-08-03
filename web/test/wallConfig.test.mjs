// Pure-logic tests for the server-side wall-config transforms (headless version).
// These pin the picker's edit semantics (the writes /control/ + /app/ make) and
// the normalisation /app/ uses to render FROM the config — the same per-cell audio
// + subtitle + outputs-fan-out model the helper validator enforces server-side.
// Run: `node --test web/test/*.test.mjs` (built-in node:test, no deps).

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  WALL_SCHEMA_VERSION,
  resizeCells, cellCount, normalizeConfig,
  withCellChannel, withCellSubtitles, withCellAudio, withLayout, withPreset,
  withCellReload, withWallReload,
  withOutputEnabled, withOutputResolution, withOutputBitrate, withOutputAudio,
  withOutputRestart, deriveRenderResolution, bitrateBounds,
  withScaleStep, withScaleSteps, stepValue, clampStep, nearestStep, scaleCssVars,
  normalizeScaleBlock, defaultScaleBlock,
  RENDER_RESOLUTIONS, RESOLUTION_INFO,
  SCALE_BLOCKS, SCALE_STEP_MIN, SCALE_STEP_MAX, SCALE_STEP_DEFAULT,
  FEED_WIDTH_STEPS, FEED_TEXT_STEPS, TICKER_HEIGHT_STEPS, TICKER_TEXT_STEPS,
} from "../js/wallConfig.mjs";

/** Every (block, key) view-tunable control, derived from the shared table so a fifth
 *  control is covered by these tests the moment it is added there. */
const SCALE_KEYS = Object.entries(SCALE_BLOCKS)
  .flatMap(([block, keys]) => Object.keys(keys).map((key) => [block, key]));

const VALID = ["bbc-news", "cnn", "cbs-sports-hq", "bloomberg-tv"];

function cfg2x2() {
  return {
    schema_version: 1,
    layout: { rows: 2, cols: 2 },
    preset: "news",
    cells: [
      { channel: "bbc-news", audio: true, subtitles: false },
      { channel: "cnn", audio: false, subtitles: true },
      { channel: null, audio: false, subtitles: false },
      { channel: "cbs-sports-hq", audio: false, subtitles: false },
    ],
  };
}

// ---- resizeCells ----

test("resizeCells preserves by index, pads with empties", () => {
  const out = resizeCells([{ channel: "a", audio: true, subtitles: true }], 3);
  assert.equal(out.length, 3);
  assert.deepEqual(out[0], { channel: "a", audio: true, subtitles: true, reload: 0 });
  assert.deepEqual(out[1], { channel: null, audio: false, subtitles: false, reload: 0 });
});

test("resizeCells carries reload + audio along by index", () => {
  const out = resizeCells([{ channel: "a", audio: true, reload: 5 }, { channel: "b" }], 1);
  assert.equal(out[0].reload, 5);
  assert.equal(out[0].audio, true);
});

test("resizeCells migrates a legacy audible index to audio", () => {
  const out = resizeCells([{ channel: "a" }, { channel: "b" }], 2, 1);
  assert.equal(out[1].audio, true);    // the legacy single-audible cell
  assert.equal(out[0].audio, false);
});

// ---- normalizeConfig ----

test("normalizeConfig clamps dims and resizes cells to match", () => {
  const out = normalizeConfig({ layout: { rows: 9, cols: 0 }, cells: [] });
  assert.equal(out.layout.rows, 3);
  assert.equal(out.layout.cols, 1);
  assert.equal(out.cells.length, 3);
  assert.equal(WALL_SCHEMA_VERSION, out.schema_version);
  assert.ok(out.outputs && out.outputs.hls);
  assert.equal(out.render, undefined);          // render is gone
  assert.equal(out.audible_cell, undefined);    // audible_cell is gone
});

test("normalizeConfig clears a cell whose channel vanished (validSlugs)", () => {
  const out = normalizeConfig(cfg2x2(), ["cnn", "cbs-sports-hq"]);  // bbc-news gone
  assert.equal(out.cells[0].channel, null);
  assert.equal(out.cells[0].audio, false);   // a cleared cell resets to empty
  assert.equal(out.cells[1].channel, "cnn");
});

test("cellCount reflects the layout", () => {
  assert.equal(cellCount({ layout: { rows: 3, cols: 2 } }), 6);
});

for (const bad of [null, undefined, 42, "str", []]) {
  test(`normalizeConfig(${JSON.stringify(bad)}) falls back to a valid default`, () => {
    const out = normalizeConfig(bad);
    assert.deepEqual(out.layout, { rows: 2, cols: 2 });
    assert.equal(out.cells.length, 4);
    assert.ok(out.cells.every((c) => c.channel === null && c.audio === false && c.subtitles === false));
    assert.equal(out.outputs.hls.resolution, "1080p");
  });
}

test("normalizeConfig MIGRATES an old-shape payload (render + audible_cell)", () => {
  const wire = {
    schema_version: 1, stored: true,
    layout: { rows: 1, cols: 2 }, preset: "news", audible_cell: 0,
    render: { resolution: "1440p" },
    cells: [{ channel: "bbc-news", subtitles: true }, { channel: "cnn", subtitles: false }],
  };
  const out = normalizeConfig(wire, VALID);
  assert.equal(out.cells[0].audio, true);            // audible_cell → cell 0 audio
  assert.equal(out.outputs.hls.resolution, "1440p"); // render → hls
  assert.equal(out.stored, undefined);
  assert.equal(out.render, undefined);
});

// ---- withCellChannel / withCellSubtitles / withCellAudio ----

test("withCellChannel assigns a cell, audio rides along", () => {
  const out = withCellChannel(cfg2x2(), 2, "bloomberg-tv");
  assert.equal(out.cells[2].channel, "bloomberg-tv");
  assert.equal(out.cells[0].audio, true);   // cell 0's audio untouched
});

test("withCellSubtitles toggles per cell", () => {
  const a = withCellSubtitles(cfg2x2(), 0);
  assert.equal(a.cells[0].subtitles, true);
  assert.equal(withCellSubtitles(a, 0).cells[0].subtitles, false);
});

test("withCellAudio toggles per cell; MULTIPLE may be on", () => {
  let c = cfg2x2();                      // cell 0 audio on
  c = withCellAudio(c, 1);               // turn cell 1 on too
  assert.equal(c.cells[0].audio, true);
  assert.equal(c.cells[1].audio, true);  // multi-audible
  c = withCellAudio(c, 0);               // toggle cell 0 off
  assert.equal(c.cells[0].audio, false);
  assert.equal(c.cells[1].audio, true);  // independent
});

// ---- withLayout / withPreset ----

test("withLayout resizes and preserves assignments + audio by index", () => {
  const out = withLayout(cfg2x2(), 1, 2);
  assert.equal(out.cells.length, 2);
  assert.deepEqual(out.cells.map((c) => c.channel), ["bbc-news", "cnn"]);
  assert.equal(out.cells[0].audio, true);   // cell 0's audio survives by index
});

test("withPreset fills from preset slugs, sets grid, resets audio+subs", () => {
  const preset = { id: "nature", grid: { rows: 1, cols: 2 }, slugs: ["cnn", "bbc-news", "x"] };
  const out = withPreset(cfg2x2(), preset, VALID);
  assert.deepEqual(out.layout, { rows: 1, cols: 2 });
  assert.deepEqual(out.cells.map((c) => c.channel), ["cnn", "bbc-news"]);
  assert.equal(out.preset, "nature");
  assert.ok(out.cells.every((c) => c.audio === false && c.subtitles === false));
});

test("withPreset filters slugs that are not real channels", () => {
  const preset = { id: "x", grid: { rows: 1, cols: 3 }, slugs: ["ghost", "cnn"] };
  const out = withPreset(cfg2x2(), preset, VALID);
  assert.deepEqual(out.cells.map((c) => c.channel), ["cnn", null, null]);
});

// ---- force-reload epochs ----

test("normalizeConfig defaults the reload epochs (additive)", () => {
  const out = normalizeConfig({ layout: { rows: 1, cols: 2 }, cells: [{ channel: "bbc-news" }, { channel: null }] });
  assert.equal(out.reload_epoch, 0);
  assert.ok(out.cells.every((c) => c.reload === 0));
});

test("withWallReload bumps ONLY the whole-wall epoch (+1)", () => {
  const out = withWallReload(cfg2x2());
  assert.equal(out.reload_epoch, 1);
  assert.ok(out.cells.every((c) => c.reload === 0));
  assert.equal(withWallReload(out).reload_epoch, 2);
});

test("withCellReload bumps ONLY that cell's epoch (+1)", () => {
  const out = withCellReload(cfg2x2(), 1);
  assert.equal(out.cells[1].reload, 1);
  assert.equal(out.cells[0].reload, 0);
  assert.equal(out.reload_epoch, 0);
  assert.equal(withCellReload(out, 1).cells[1].reload, 2);
});

// ---- outputs: the multi-output fan-out ----

test("normalizeConfig defaults the outputs block", () => {
  const out = normalizeConfig({ layout: { rows: 1, cols: 1 }, cells: [{ channel: "bbc-news" }] });
  assert.equal(out.outputs.hls.enabled, true);
  assert.equal(out.outputs.hls.resolution, "1080p");
});

test("withOutput* transforms set + clamp", () => {
  let c = cfg2x2();
  assert.equal(withOutputEnabled(c, "hls", false).outputs.hls.enabled, false);
  assert.equal(withOutputResolution(c, "hls", "2160p").outputs.hls.resolution, "2160p");
  assert.equal(withOutputAudio(c, "hls", false).outputs.hls.audio, false);
  // bitrate clamps into the per-resolution band
  assert.equal(withOutputBitrate(c, "hls", 9_999_999).outputs.hls.bitrate_kbps, bitrateBounds("1080p").max);
  assert.equal(withOutputBitrate(c, "hls", 1).outputs.hls.bitrate_kbps, bitrateBounds("1080p").min);
});

test("withOutputRestart bumps the per-output restart epoch (monotonic)", () => {
  const a = withOutputRestart(cfg2x2(), "hls");
  assert.equal(a.outputs.hls.restart_epoch, 1);
  assert.equal(withOutputRestart(a, "hls").outputs.hls.restart_epoch, 2);
});

test("deriveRenderResolution = max enabled output resolution", () => {
  // hls 1080p enabled → 1080p
  assert.equal(deriveRenderResolution(cfg2x2().outputs), "1080p");
  const c2 = withOutputResolution(cfg2x2(), "hls", "1440p");
  assert.equal(deriveRenderResolution(c2.outputs), "1440p");
  // nothing enabled → default
  const c3 = withOutputEnabled(cfg2x2(), "hls", false);
  assert.equal(deriveRenderResolution(c3.outputs), "1080p");
});

test("REGRESSION: a normalize round-trip never strips outputs (commit() safety)", () => {
  const c = withOutputResolution(withOutputBitrate(cfg2x2(), "hls", 6000), "hls", "1440p");
  const round = normalizeConfig(c, VALID);
  assert.equal(round.outputs.hls.resolution, "1440p");
  assert.equal(round.outputs.hls.bitrate_kbps, 6000);
});

test("outputs survive the other edit transforms (orthogonal)", () => {
  const base = withOutputResolution(cfg2x2(), "hls", "1440p");
  assert.equal(withCellChannel(base, 2, "bloomberg-tv").outputs.hls.resolution, "1440p");
  assert.equal(withLayout(base, 1, 2).outputs.hls.resolution, "1440p");
  assert.equal(withCellAudio(base, 1).outputs.hls.resolution, "1440p");
  const out = withPreset(base, { id: "x", grid: { rows: 1, cols: 1 }, slugs: ["cnn"] }, VALID);
  assert.equal(out.outputs.hls.resolution, "1440p");
});

// ---- the four view tunables: 1..10 integer steps ----

test("normalizeConfig defaults every view tunable to step 5", () => {
  const out = normalizeConfig({ layout: { rows: 1, cols: 1 }, cells: [{ channel: "bbc-news" }] });
  assert.deepEqual(out.feed, { width_scale: 5, text_scale: 5 });
  assert.deepEqual(out.ticker, { height_scale: 5, text_scale: 5 });
  assert.deepEqual(out.feed, defaultScaleBlock("feed"));
});

test("every ladder has ten rungs and is strictly increasing", () => {
  for (const [block, keys] of Object.entries(SCALE_BLOCKS)) {
    for (const [key, ladder] of Object.entries(keys)) {
      assert.equal(ladder.length, SCALE_STEP_MAX, `${block}.${key} rung count`);
      for (let i = 1; i < ladder.length; i++) {
        assert.ok(ladder[i] > ladder[i - 1], `${block}.${key} rung ${i + 1} not larger`);
      }
    }
  }
});

test("step 5 reproduces the pre-PR-024 defaults on every control", () => {
  // The no-visible-jump contract. If this changes, every stored wall shifts.
  assert.equal(FEED_WIDTH_STEPS[4], 32);
  assert.equal(FEED_TEXT_STEPS[4], 1.0);
  assert.equal(TICKER_HEIGHT_STEPS[4], 1.0);
  assert.equal(TICKER_TEXT_STEPS[4], 1.0);
});

test("the ladders MIRROR the helper's (bounds must move in lockstep)", () => {
  // Spelled out literally, not derived, so a one-sided edit fails here loudly.
  assert.deepEqual(FEED_WIDTH_STEPS, [12, 18, 23, 27, 32, 38, 44, 50, 57, 64]);
  assert.deepEqual(FEED_TEXT_STEPS, [0.60, 0.70, 0.80, 0.90, 1.00, 1.15, 1.32, 1.52, 1.75, 2.00]);
  assert.deepEqual(TICKER_HEIGHT_STEPS, [0.45, 0.58, 0.70, 0.85, 1.00, 1.25, 1.55, 1.90, 2.30, 2.75]);
  assert.deepEqual(TICKER_TEXT_STEPS, [0.55, 0.62, 0.72, 0.85, 1.00, 1.25, 1.55, 1.90, 2.30, 2.75]);
});

test("ticker height + text stay proportional from step 4 up", () => {
  assert.deepEqual(TICKER_HEIGHT_STEPS.slice(3), TICKER_TEXT_STEPS.slice(3));
  assert.ok(TICKER_TEXT_STEPS[0] > TICKER_HEIGHT_STEPS[0]);   // text floor is higher
});

test("feed width step 1 clears the .feed-pane min-width floor", () => {
  // min-width: calc(220 * var(--u)) = 220/1920 = 11.46 % of the wall. Below that the
  // slider's bottom rung would be a SILENT NO-OP.
  assert.ok(FEED_WIDTH_STEPS[0] > (220 / 1920) * 100);
});

test("clampStep clamps to 1..10 and coerces a numeric non-integer", () => {
  for (const [given, expected] of [[0, 1], [1, 1], [10, 10], [11, 10], [-99, 1], [999, 10],
                                   [5.0, 5], [5.4, 5], [5.6, 6], [7.5, 8]]) {
    assert.equal(clampStep(given), expected, `clampStep(${given})`);
  }
  // A range input's .value is a STRING — it must be accepted, not repaired away.
  assert.equal(clampStep("7"), 7);
  // The client REPAIRS garbage (the server is the surface that rejects it). null and
  // [] are the trap here: Number() turns both into 0, which would snap to step 1.
  for (const bad of ["wide", "", null, undefined, NaN, {}, [], true, false]) {
    assert.equal(clampStep(bad), SCALE_STEP_DEFAULT, `clampStep(${JSON.stringify(bad)})`);
  }
  assert.equal(SCALE_STEP_MIN, 1);
});

test("stepValue maps each step onto its ladder rung", () => {
  for (const [block, key] of SCALE_KEYS) {
    const ladder = SCALE_BLOCKS[block][key];
    for (let step = 1; step <= SCALE_STEP_MAX; step++) {
      assert.equal(stepValue(block, key, step), ladder[step - 1], `${block}.${key} step ${step}`);
    }
    assert.equal(stepValue(block, key, 99), ladder[9], "out-of-range clamps");
  }
  assert.equal(stepValue("nope", "nope", 5), null);
});

// ---- the CSS-variable contract (what the render page actually writes) ----

test("scaleCssVars emits exactly the four wall custom properties", () => {
  const vars = scaleCssVars(normalizeConfig(cfg2x2()));
  assert.deepEqual(Object.keys(vars).sort(),
    ["--feed-font", "--feed-pct", "--ticker-scale", "--ticker-text-scale"]);
});

test("scaleCssVars at step 5 reproduces the pre-PR-024 stylesheet defaults", () => {
  assert.deepEqual(scaleCssVars(normalizeConfig(cfg2x2())), {
    "--feed-pct": "32%",
    "--feed-font": "1",
    "--ticker-scale": "1",
    "--ticker-text-scale": "1",
  });
});

test("scaleCssVars tracks each control independently, per step", () => {
  let c = normalizeConfig(cfg2x2());
  c = withScaleStep(c, "feed", "width_scale", 1);
  c = withScaleStep(c, "ticker", "text_scale", 10);
  const vars = scaleCssVars(c);
  assert.equal(vars["--feed-pct"], "12%");                 // step 1 of the width ladder
  assert.equal(vars["--ticker-text-scale"], "2.75");       // step 10 of the text ladder
  assert.equal(vars["--feed-font"], "1");                  // untouched
  assert.equal(vars["--ticker-scale"], "1");               // ticker HEIGHT is independent
});

test("scaleCssVars covers every step of every control without a gap", () => {
  for (const [block, key] of SCALE_KEYS) {
    const seen = new Set();
    for (let step = 1; step <= SCALE_STEP_MAX; step++) {
      const vars = scaleCssVars(withScaleStep(normalizeConfig(cfg2x2()), block, key, step));
      const all = Object.values(vars).join("|");
      assert.ok(!seen.has(all), `${block}.${key} step ${step} duplicates another step`);
      seen.add(all);
    }
    assert.equal(seen.size, SCALE_STEP_MAX);
  }
});

// ---- migration off the retired continuous keys ----

test("normalizeConfig migrates the retired continuous scales to the nearest step", () => {
  const out = normalizeConfig({
    layout: { rows: 1, cols: 1 }, cells: [{ channel: "bbc-news" }],
    feed_pct: 32.0, feed_font: 1.0, ticker_scale: 1.0,
  });
  assert.deepEqual(out.feed, { width_scale: 5, text_scale: 5 });
  assert.deepEqual(out.ticker, { height_scale: 5, text_scale: 5 });
  // the legacy keys do not survive normalisation
  assert.ok(!("feed_pct" in out) && !("feed_font" in out) && !("ticker_scale" in out));
});

test("the retired ticker_scale seeds BOTH ticker keys", () => {
  const out = normalizeConfig({
    layout: { rows: 1, cols: 1 }, cells: [{ channel: "bbc-news" }], ticker_scale: 1.55,
  });
  assert.deepEqual(out.ticker, { height_scale: 7, text_scale: 7 });
});

test("an explicit step beats a legacy value (a stale /control/ tab is safe)", () => {
  const out = normalizeConfig({
    layout: { rows: 1, cols: 1 }, cells: [{ channel: "bbc-news" }],
    feed_pct: 58.0, feed: { width_scale: 3 },
  });
  assert.equal(out.feed.width_scale, 3);
});

test("migration is idempotent", () => {
  const base = {
    layout: { rows: 1, cols: 1 }, cells: [{ channel: "bbc-news" }],
    feed_pct: 41.0, feed_font: 1.25, ticker_scale: 1.5,
  };
  assert.deepEqual(normalizeConfig(normalizeConfig(base)), normalizeConfig(base));
});

test("nearestStep picks the nearest rung, ties to the lower step", () => {
  assert.equal(nearestStep(32, FEED_WIDTH_STEPS), 5);
  assert.equal(nearestStep(0, FEED_WIDTH_STEPS), 1);
  assert.equal(nearestStep(999, FEED_WIDTH_STEPS), 10);
  assert.equal(nearestStep(41, FEED_WIDTH_STEPS), 6);      // |38-41| == |44-41| → lower
  assert.equal(nearestStep("wide", FEED_WIDTH_STEPS), null);
  assert.equal(normalizeScaleBlock({}, "feed", {}).width_scale, SCALE_STEP_DEFAULT);
});

// ---- in-block no-clobber (the partner to the server's partial-merge invariant) ----

test("withScaleStep sets ONE control and never disturbs the other three", () => {
  for (const [block, key] of SCALE_KEYS) {
    let c = normalizeConfig(cfg2x2());
    c = withScaleStep(c, "feed", "width_scale", 2);
    c = withScaleStep(c, "feed", "text_scale", 8);
    c = withScaleStep(c, "ticker", "height_scale", 9);
    c = withScaleStep(c, "ticker", "text_scale", 3);
    const before = { feed: { ...c.feed }, ticker: { ...c.ticker } };
    const after = withScaleStep(c, block, key, 6);
    assert.equal(after[block][key], 6, `${block}.${key} set`);
    for (const [b, k] of SCALE_KEYS) {
      if (b === block && k === key) continue;
      assert.equal(after[b][k], before[b][k], `${block}.${key} clobbered ${b}.${k}`);
    }
  }
});

test("withScaleSteps coalesces a debounce window WITHOUT dropping an earlier edit", () => {
  // The guarantee /control/'s debounced commit relies on: two sliders nudged inside
  // one window produce ONE write carrying BOTH.
  const out = withScaleSteps(normalizeConfig(cfg2x2()), [
    { block: "feed", key: "width_scale", step: 2 },
    { block: "ticker", key: "text_scale", step: 8 },
  ]);
  assert.equal(out.feed.width_scale, 2);
  assert.equal(out.ticker.text_scale, 8);
  assert.equal(out.feed.text_scale, 5);          // untouched
  assert.equal(out.ticker.height_scale, 5);      // untouched
});

test("withScaleSteps lets the LAST edit of one control win", () => {
  const out = withScaleSteps(normalizeConfig(cfg2x2()), [
    { block: "feed", key: "width_scale", step: 3 },
    { block: "feed", key: "width_scale", step: 9 },
  ]);
  assert.equal(out.feed.width_scale, 9);
});

test("withScaleSteps with no pending edits is a no-op normalise", () => {
  const base = normalizeConfig(cfg2x2());
  assert.deepEqual(withScaleSteps(base, []), base);
  assert.deepEqual(withScaleSteps(base, null), base);
});

test("withScaleStep ignores an unknown control", () => {
  const c = normalizeConfig(cfg2x2());
  assert.deepEqual(withScaleStep(c, "feed", "nope", 3).feed, c.feed);
  assert.deepEqual(withScaleStep(c, "nope", "width_scale", 3), c);
});

test("applying a preset carries the view tunables through unchanged", () => {
  // REGRESSION: the preset builder is a SECOND literal key dict — an easy place to
  // silently reset the tunables to their defaults.
  let c = normalizeConfig(cfg2x2());
  c = withScaleStep(c, "feed", "width_scale", 9);
  c = withScaleStep(c, "ticker", "text_scale", 2);
  const out = withPreset(c, { id: "weather", grid: { rows: 1, cols: 2 }, slugs: ["cnn"] }, VALID);
  assert.equal(out.feed.width_scale, 9);
  assert.equal(out.ticker.text_scale, 2);
  assert.equal(out.feed.text_scale, 5);
});

test("the resolution ladder has 8 rungs, each with display info", () => {
  assert.equal(RENDER_RESOLUTIONS.length, 8);
  for (const name of RENDER_RESOLUTIONS) {
    const i = RESOLUTION_INFO[name];
    assert.ok(i && i.w && i.h && i.fps && i.zone, `${name} info`);
    assert.ok(Math.abs(i.w / i.h - 16 / 9) < 1e-6, `${name} 16:9`);
  }
  assert.equal(RESOLUTION_INFO["1080p"].fps, 30);
  assert.ok(RESOLUTION_INFO["2160p"].fps < RESOLUTION_INFO["1080p"].fps);
});

test("edit transforms preserve the whole-wall reload epoch (never rewind it)", () => {
  const base = withWallReload(cfg2x2());
  assert.equal(withCellChannel(base, 2, "bloomberg-tv").reload_epoch, 1);
  assert.equal(withCellAudio(base, 1).reload_epoch, 1);
  assert.equal(withLayout(base, 1, 2).reload_epoch, 1);
  const out = withPreset(base, { id: "x", grid: { rows: 1, cols: 1 }, slugs: ["cnn"] }, VALID);
  assert.equal(out.reload_epoch, 1);
});
