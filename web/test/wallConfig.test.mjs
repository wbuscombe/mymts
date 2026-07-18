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
  withFeedPct, withFeedFont, withTickerScale,
  RENDER_RESOLUTIONS, RESOLUTION_INFO,
  FEED_PCT, FEED_FONT, TICKER_SCALE,
} from "../js/wallConfig.mjs";

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

// ---- fine-grained view tunables ----

test("normalizeConfig defaults + carries the view tunables", () => {
  const out = normalizeConfig({ layout: { rows: 1, cols: 1 }, cells: [{ channel: "bbc-news" }] });
  assert.equal(out.feed_pct, FEED_PCT.default);
  assert.equal(out.feed_font, FEED_FONT.default);
  assert.equal(out.ticker_scale, TICKER_SCALE.default);
});

test("with* view-tunable transforms set + clamp (fine-grained)", () => {
  assert.equal(withFeedPct(cfg2x2(), 41).feed_pct, 41);
  assert.equal(withFeedPct(cfg2x2(), 999).feed_pct, FEED_PCT.max);
  assert.equal(withFeedFont(cfg2x2(), 1.25).feed_font, 1.25);
  assert.equal(withTickerScale(cfg2x2(), 0).ticker_scale, TICKER_SCALE.min);
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
