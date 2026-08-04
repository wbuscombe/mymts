// CROSS-LANGUAGE ladder-divergence gate (PR-027).
//
// The four 1–10 ladders exist in three places by necessity — the helper owns the
// schema (`wall/store.py`), the web resolves steps to CSS (`wallConfig.mjs`), and the
// native app resolves them to Compose units (`WallScaleSteps.kt`). A step is only
// meaningful if all three agree, and nothing structural stops them drifting: PR-024
// changed the helper + web, PR-026 changed native, and /app/ sat on the OLD numbers
// for two PRs without a single test going red.
//
// Native closed its side in PR-026 with a literals test. This closes the web side by
// reading the helper's Python source directly and comparing rung-for-rung, so editing
// ONE side fails the build instead of silently meaning two different things on two
// screens.
//
// Reading the .py as text (rather than executing it) keeps the web suite dependency-
// free — `node --test web/test/*.test.mjs`, no Python required.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

import {
  FEED_WIDTH_STEPS, FEED_TEXT_STEPS, TICKER_HEIGHT_STEPS, TICKER_TEXT_STEPS,
  SCALE_STEP_MIN, SCALE_STEP_MAX, SCALE_STEP_DEFAULT,
} from "../js/wallConfig.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const STORE_PY = path.resolve(HERE, "../../helper/src/mymts_helper/wall/store.py");

/** Pull a `NAME = (a, b, c, …)` tuple out of the helper's source as numbers. */
function ladderFromStorePy(source, name) {
  const m = source.match(new RegExp(`^${name}\\s*=\\s*\\(([^)]*)\\)`, "m"));
  assert.ok(m, `${name} not found in store.py — did it get renamed?`);
  return m[1]
    .split(",")
    .map((s) => s.replace(/#.*$/, "").trim())
    .filter((s) => s.length > 0)
    .map(Number);
}

function scalarFromStorePy(source, name) {
  const m = source.match(new RegExp(`^${name}\\s*=\\s*(-?[0-9.]+)`, "m"));
  assert.ok(m, `${name} not found in store.py`);
  return Number(m[1]);
}

const PY = readFileSync(STORE_PY, "utf8");

const LADDERS = [
  ["FEED_WIDTH_STEPS", FEED_WIDTH_STEPS],
  ["FEED_TEXT_STEPS", FEED_TEXT_STEPS],
  ["TICKER_HEIGHT_STEPS", TICKER_HEIGHT_STEPS],
  ["TICKER_TEXT_STEPS", TICKER_TEXT_STEPS],
];

for (const [name, js] of LADDERS) {
  test(`${name} matches the helper's store.py rung-for-rung`, () => {
    const py = ladderFromStorePy(PY, name);
    assert.equal(
      py.length, js.length,
      `${name}: helper has ${py.length} rungs, web has ${js.length}`,
    );
    for (let i = 0; i < py.length; i++) {
      assert.ok(
        Math.abs(py[i] - js[i]) < 1e-9,
        `${name} DIVERGED at step ${i + 1}: helper says ${py[i]}, web says ${js[i]}.\n` +
        `        A step must mean the same thing on every surface — fix both sides ` +
        `(and app/src/main/java/com/mymts/data/settings/WallScaleSteps.kt).`,
      );
    }
  });
}

test("the step range + default match the helper", () => {
  assert.equal(scalarFromStorePy(PY, "SCALE_STEP_MIN"), SCALE_STEP_MIN);
  assert.equal(scalarFromStorePy(PY, "SCALE_STEP_MAX"), SCALE_STEP_MAX);
  assert.equal(scalarFromStorePy(PY, "SCALE_STEP_DEFAULT"), SCALE_STEP_DEFAULT);
});

test("the parser is NOT a no-op — it really reads the helper's numbers", () => {
  // A divergence gate that silently parses nothing would pass forever. Prove the
  // extraction works by asserting a value the helper genuinely holds, and that a
  // deliberately-wrong comparison fails.
  const py = ladderFromStorePy(PY, "FEED_WIDTH_STEPS");
  assert.equal(py.length, 10, "parsed no rungs — the regex has stopped matching");
  assert.equal(py[4], 32, "step 5 of the feed-width ladder should be 32% in store.py");
  assert.notDeepEqual(py, py.map((v) => v + 1), "sanity: comparison can distinguish values");
});

test("the native ladder file exists and carries the same literals", () => {
  // Native has its own literals test (WallScaleStepsTest), but that only fails if
  // someone runs the ANDROID suite. This catches a web/helper edit that forgot native
  // from the suite a web contributor actually runs.
  const KT = path.resolve(HERE, "../../app/src/main/java/com/mymts/data/settings/WallScaleSteps.kt");
  const kt = readFileSync(KT, "utf8");
  const intArr = kt.match(/FEED_WIDTH_PCT\s*=\s*intArrayOf\(([^)]*)\)/);
  assert.ok(intArr, "FEED_WIDTH_PCT not found in WallScaleSteps.kt");
  assert.deepEqual(intArr[1].split(",").map((s) => Number(s.trim())), [...FEED_WIDTH_STEPS]);

  for (const [ktName, js] of [
    ["FEED_TEXT", FEED_TEXT_STEPS],
    ["TICKER_HEIGHT", TICKER_HEIGHT_STEPS],
    ["TICKER_TEXT", TICKER_TEXT_STEPS],
  ]) {
    const m = kt.match(new RegExp(`${ktName}\\s*=\\s*floatArrayOf\\(([^)]*)\\)`));
    assert.ok(m, `${ktName} not found in WallScaleSteps.kt`);
    const vals = m[1].split(",").map((s) => Number(s.trim().replace(/f$/, "")));
    assert.equal(vals.length, js.length, `${ktName}: native has ${vals.length} rungs`);
    for (let i = 0; i < vals.length; i++) {
      assert.ok(
        Math.abs(vals[i] - js[i]) < 1e-6,
        `${ktName} DIVERGED at step ${i + 1}: native ${vals[i]} vs web ${js[i]}`,
      );
    }
  }
});
