// The FOUR view-tunable controls, defined ONCE for every web surface.
//
// WHY THIS MODULE EXISTS (PR-027): the ladders lived in wallConfig.mjs, but the
// *controls* — which id maps to which config key, what the row is called, how its
// value reads — were re-declared independently in /control/ and never added to /app/
// at all. So PR-024 updated one surface, PR-026 updated the native app, and /app/'s
// own WALL SETTINGS modal silently kept a 3-option dropdown and an 18–58 slider for
// months. Three implementations of one idea is what let them drift; this is one
// implementation all of them render from.
//
// Adding a fifth control means adding ONE entry here — both surfaces pick it up.

import { SCALE_BLOCKS, SCALE_STEP_MIN, SCALE_STEP_MAX, clampStep, stepValue } from "./wallConfig.mjs";

/** How each control's resolved value reads to a human. Percent for the pane width
 *  (it IS a percentage of the wall); a multiplier for the three scale controls. */
const PCT = (v) => `${v}%`;
const MULT = (v) => `${v.toFixed(2)}×`;

/**
 * The four controls, in the order both surfaces present them.
 *
 * `id` is the DOM id STEM: /control/ uses it verbatim, /app/'s modal prefixes its own
 * to avoid colliding with the pane-divider's markup. `block`/`key` address the config
 * exactly as the helper's partial-merge expects, so a write touches one key.
 */
export const SCALE_CONTROLS = [
  { id: "feed-width", block: "feed", key: "width_scale",
    label: "Feed width", what: "feed width", fmt: PCT },
  { id: "feed-text", block: "feed", key: "text_scale",
    label: "Feed text", what: "feed text", fmt: MULT },
  { id: "ticker-height", block: "ticker", key: "height_scale",
    label: "Ticker height", what: "ticker height", fmt: MULT },
  { id: "ticker-text", block: "ticker", key: "text_scale",
    label: "Ticker text", what: "ticker text", fmt: MULT },
];

/** Guard: the control table must address only keys the config actually has, or a
 *  write would silently do nothing. Cheap enough to assert at module load. */
for (const c of SCALE_CONTROLS) {
  if (!SCALE_BLOCKS[c.block]?.[c.key]) {
    throw new Error(`scaleControls: ${c.block}.${c.key} is not a config scale key`);
  }
}

/** The label for a step: the STEP is what the operator sets, the resolved value is the
 *  honest hint about what it means ("7 · 44%"). Identical on every surface — including
 *  the native app, which mirrors this format. Pure. */
export function scaleLabel(control, step) {
  return `${clampStep(step)} · ${control.fmt(stepValue(control.block, control.key, step))}`;
}

/** Read a control's current step out of a normalized config. Pure. */
export function stepOf(config, control) {
  return clampStep(config?.[control.block]?.[control.key]);
}

/** Apply the shared range-input bounds. One place, so a surface can't ship a stale
 *  `min`/`max` in its markup the way /app/'s 18–58 slider did. */
export function applyRangeBounds(input) {
  if (!input) return;
  input.min = String(SCALE_STEP_MIN);
  input.max = String(SCALE_STEP_MAX);
  input.step = "1";
}

/**
 * The debounced, per-control-coalescing commit both surfaces write through.
 *
 * Two layers of thrash guard, as established in PR-024: the caller re-labels on
 * `input` (no network at all while dragging) and calls [push] on `change`; this then
 * coalesces for [delayMs] so a held arrow key — or a touch drag emitting several
 * `change` events — becomes ONE write. Pending edits are keyed PER CONTROL, so nudging
 * two controls inside one window produces a single write carrying BOTH rather than
 * dropping the earlier one.
 *
 * `apply(config, edits)` folds the pending edits (withScaleSteps); `commit(next, what)`
 * performs the write. Injected rather than imported so this module stays DOM-free and
 * testable.
 */
export function createScaleCommitter({ getConfig, apply, commit, delayMs = 250, setTimer, clearTimer }) {
  const pending = new Map();
  const schedule = setTimer ?? ((fn, ms) => setTimeout(fn, ms));
  const cancel = clearTimer ?? ((t) => clearTimeout(t));
  let timer = null;

  const flush = () => {
    timer = null;
    if (pending.size === 0) return;
    const edits = [...pending.values()];
    pending.clear();
    commit(
      apply(getConfig(), edits),
      edits.length === 1 ? edits[0].what : `${edits.length} display controls`,
    );
  };

  return {
    /** Queue one control's new step. Later edits of the same control replace earlier. */
    push(control, step) {
      pending.set(`${control.block}.${control.key}`, {
        block: control.block, key: control.key, step: clampStep(step), what: control.what,
      });
      if (timer !== null) cancel(timer);
      timer = schedule(flush, delayMs);
    },
    /** Write immediately (e.g. a pointer-drag ending). */
    flushNow() {
      if (timer !== null) { cancel(timer); timer = null; }
      flush();
    },
    get pendingCount() { return pending.size; },
  };
}
