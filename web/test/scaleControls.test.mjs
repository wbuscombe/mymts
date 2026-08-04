// The SHARED control table + debounced committer (PR-027) — the module that exists so
// /control/ and /app/ cannot drift apart again. Pure; no DOM, no fetch.

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  SCALE_CONTROLS, scaleLabel, stepOf, applyRangeBounds, createScaleCommitter,
} from "../js/scaleControls.mjs";
import {
  SCALE_BLOCKS, SCALE_STEP_MIN, SCALE_STEP_MAX, normalizeConfig, withScaleSteps,
} from "../js/wallConfig.mjs";

test("the table covers EVERY config scale key, exactly once", () => {
  // If a fifth control is added to the config but not here, one surface silently
  // lacks it — which is precisely the bug this module exists to prevent.
  const declared = SCALE_CONTROLS.map((c) => `${c.block}.${c.key}`).sort();
  const actual = Object.entries(SCALE_BLOCKS)
    .flatMap(([b, keys]) => Object.keys(keys).map((k) => `${b}.${k}`)).sort();
  assert.deepEqual(declared, actual);
  assert.equal(new Set(declared).size, declared.length, "a control is declared twice");
});

test("every control has the fields both surfaces render from", () => {
  for (const c of SCALE_CONTROLS) {
    assert.ok(c.id && typeof c.id === "string", `${c.key}: missing id`);
    assert.ok(c.label, `${c.id}: missing label`);
    assert.ok(c.what, `${c.id}: missing 'what' (the save-status wording)`);
    assert.equal(typeof c.fmt, "function", `${c.id}: missing formatter`);
  }
  assert.equal(new Set(SCALE_CONTROLS.map((c) => c.id)).size, SCALE_CONTROLS.length,
    "two controls share a DOM id");
});

test("labels read identically to /control/ and the native app", () => {
  const width = SCALE_CONTROLS.find((c) => c.key === "width_scale");
  assert.equal(scaleLabel(width, 5), "5 · 32%");
  assert.equal(scaleLabel(width, 1), "1 · 12%");
  const tickerText = SCALE_CONTROLS.find((c) => c.block === "ticker" && c.key === "text_scale");
  assert.equal(scaleLabel(tickerText, 5), "5 · 1.00×");
  assert.equal(scaleLabel(width, 99), "10 · 64%", "out-of-range clamps in the label too");
});

test("stepOf reads a control's step out of a config, clamped", () => {
  const cfg = normalizeConfig({ layout: { rows: 1, cols: 1 }, cells: [{ channel: null }] });
  for (const c of SCALE_CONTROLS) assert.equal(stepOf(cfg, c), 5);
  assert.equal(stepOf({ feed: { width_scale: 99 } }, SCALE_CONTROLS[0]), SCALE_STEP_MAX);
  assert.equal(stepOf(undefined, SCALE_CONTROLS[0]), 5, "no config → the default");
});

test("applyRangeBounds stamps 1..10 from the shared constants", () => {
  const fake = {};
  applyRangeBounds(fake);
  assert.equal(fake.min, String(SCALE_STEP_MIN));
  assert.equal(fake.max, String(SCALE_STEP_MAX));
  assert.equal(fake.step, "1");
  applyRangeBounds(null);   // must not throw
});

// ---- the debounced, per-control-coalescing committer ----

function harness({ delayMs = 250 } = {}) {
  let cfg = normalizeConfig({ layout: { rows: 1, cols: 1 }, cells: [{ channel: null }] });
  const writes = [];
  let pendingFn = null;
  const committer = createScaleCommitter({
    delayMs,
    getConfig: () => cfg,
    apply: (c, edits) => withScaleSteps(c, edits),
    commit: (next, what) => { cfg = next; writes.push({ next, what }); },
    setTimer: (fn) => { pendingFn = fn; return 1; },
    clearTimer: () => { pendingFn = null; },
  });
  return { committer, writes, fire: () => { const f = pendingFn; pendingFn = null; f?.(); },
           get config() { return cfg; }, get armed() { return pendingFn !== null; } };
}

test("a burst on ONE control produces ONE write, carrying the LAST value", () => {
  const h = harness();
  const c = SCALE_CONTROLS[0];
  h.committer.push(c, 2); h.committer.push(c, 7); h.committer.push(c, 9);
  assert.equal(h.writes.length, 0, "nothing written before the window elapses");
  h.fire();
  assert.equal(h.writes.length, 1);
  assert.equal(h.config.feed.width_scale, 9);
});

test("two controls in one window produce ONE write carrying BOTH", () => {
  // The regression that matters: a single-slot pending value would drop the first edit.
  const h = harness();
  h.committer.push(SCALE_CONTROLS[0], 2);
  h.committer.push(SCALE_CONTROLS[3], 8);
  h.fire();
  assert.equal(h.writes.length, 1);
  assert.equal(h.config.feed.width_scale, 2);
  assert.equal(h.config.ticker.text_scale, 8);
  assert.match(h.writes[0].what, /2 display controls/);
});

test("a single edit is described by its own name, not a count", () => {
  const h = harness();
  h.committer.push(SCALE_CONTROLS[1], 3);
  h.fire();
  assert.equal(h.writes[0].what, SCALE_CONTROLS[1].what);
});

test("flushNow writes immediately and disarms the timer", () => {
  const h = harness();
  h.committer.push(SCALE_CONTROLS[2], 10);
  h.committer.flushNow();
  assert.equal(h.writes.length, 1);
  assert.equal(h.config.ticker.height_scale, 10);
  assert.equal(h.armed, false);
  h.committer.flushNow();                       // idempotent — nothing pending
  assert.equal(h.writes.length, 1);
});

test("pushed steps are clamped before they reach the config", () => {
  const h = harness();
  h.committer.push(SCALE_CONTROLS[0], 0);
  h.committer.flushNow();
  assert.equal(h.config.feed.width_scale, SCALE_STEP_MIN);
  h.committer.push(SCALE_CONTROLS[0], 999);
  h.committer.flushNow();
  assert.equal(h.config.feed.width_scale, SCALE_STEP_MAX);
});

test("an empty window writes nothing at all", () => {
  const h = harness();
  h.committer.flushNow();
  assert.equal(h.writes.length, 0, "a flush with no pending edits must not PUT");
});
