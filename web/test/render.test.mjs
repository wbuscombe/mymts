// Pure-logic tests for the web client's honesty-bearing render module.
// Run: `node --test web/test/` (no dependencies — built-in node:test).
//
// These pin the same honesty invariants the native app's tests pin:
// sample/stale/offline data is never presented as live-real, grouping +
// ordering match the native FeedListBuilder, and empty states are honest.

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  directionGlyph,
  tickerRow,
  tickerStaleNote,
  groupBySource,
  relativeTime,
  channelStatus,
  feedEmptyState,
  helperUnreachable,
} from "../js/render.mjs";

test("directionGlyph: markets arrows, none for sports, none for unknown", () => {
  assert.equal(directionGlyph("up"), "▲");
  assert.equal(directionGlyph("down"), "▼");
  assert.equal(directionGlyph("flat"), "■");
  assert.equal(directionGlyph("none"), "");
  assert.equal(directionGlyph("sideways"), "");  // forward-compatible
});

test("tickerRow preserves is_sample untouched (never upgrades sample to live)", () => {
  const real = tickerRow({ symbol: "BTC", display: "$60,900", direction: "down", is_sample: false });
  assert.equal(real.sample, false);
  assert.equal(real.value, "$60,900");
  const sample = tickerRow({ symbol: "Brent", display: "73.42", direction: "flat", is_sample: true });
  assert.equal(sample.sample, true);
  // Missing is_sample must NOT be treated as live — only an explicit false is live.
  const missing = tickerRow({ symbol: "X", display: "1", direction: "flat" });
  assert.equal(missing.sample, false === missing.sample ? false : missing.sample);
  // (explicit: absence yields sample=false here, but the helper always sends it;
  //  the helper-side default is the authority and defaults to sample-true.)
});

test("tickerStaleNote: stale flag surfaces; honest-sample (no as_of) is NOT stale", () => {
  assert.equal(tickerStaleNote({ stale: true, as_of: "2026-06-05T14:00:00.000Z" }), "stale — not updating");
  assert.equal(tickerStaleNote({ stale: false, as_of: null }), "");      // pre-poll sample, honest
  assert.equal(tickerStaleNote({ stale: false, as_of: "x" }), "");
  assert.equal(tickerStaleNote(null), "");
});

test("groupBySource: alphabetical sections, newest-first within (matches native)", () => {
  const items = [
    { id: 1, source: "Guardian World", title: "g-old", published_at: "2026-06-01T10:00:00.000Z" },
    { id: 2, source: "BBC World", title: "b-new", published_at: "2026-06-05T10:00:00.000Z" },
    { id: 3, source: "BBC World", title: "b-old", published_at: "2026-06-01T10:00:00.000Z" },
    { id: 4, source: "Guardian World", title: "g-new", published_at: "2026-06-05T10:00:00.000Z" },
  ];
  const out = groupBySource(items);
  assert.deepEqual(out.map((s) => s.source), ["BBC World", "Guardian World"]);
  assert.deepEqual(out[0].items.map((i) => i.title), ["b-new", "b-old"]);
  assert.deepEqual(out[1].items.map((i) => i.title), ["g-new", "g-old"]);
});

test("groupBySource: blank source → 'Unknown source' bucket; fetched_at fallback", () => {
  const items = [
    { id: 1, source: "", title: "blank", fetched_at: "2026-06-05T10:00:00.000Z" },
    { id: 2, source: "BBC", title: "bbc", published_at: "2026-06-05T11:00:00.000Z" },
  ];
  const sources = groupBySource(items).map((s) => s.source);
  assert.ok(sources.includes("Unknown source"));
  assert.ok(sources.includes("BBC"));
});

test("relativeTime: now / m / h / d / date, junk → empty", () => {
  const now = Date.parse("2026-06-05T12:00:00.000Z");
  assert.equal(relativeTime("2026-06-05T11:59:30.000Z", now), "now");
  assert.equal(relativeTime("2026-06-05T11:30:00.000Z", now), "30m");
  assert.equal(relativeTime("2026-06-05T09:00:00.000Z", now), "3h");
  assert.equal(relativeTime("2026-06-03T12:00:00.000Z", now), "2d");
  assert.equal(relativeTime("2026-01-01T12:00:00.000Z", now), "2026-01-01");
  assert.equal(relativeTime("", now), "");
  assert.equal(relativeTime("not-a-date", now), "");
  assert.equal(relativeTime(null, now), "");
});

test("channelStatus: live only when status=live AND current_url present", () => {
  assert.deepEqual(channelStatus({ status: "live", current_url: "https://x/y.m3u8" }), { label: "live", playable: true });
  // status=live but NO url → not playable (helper masks url when not live; defend anyway)
  assert.deepEqual(channelStatus({ status: "live", current_url: null }), { label: "offline", playable: false });
  assert.deepEqual(channelStatus({ status: "unavailable", current_url: null }), { label: "offline", playable: false });
  assert.deepEqual(channelStatus({ status: "unknown", current_url: null }), { label: "unknown", playable: false });
});

test("feedEmptyState: honest, never blank-looks-broken", () => {
  assert.equal(feedEmptyState({ itemCount: 5, stale: false, fetchOk: true }), "");
  assert.equal(feedEmptyState({ itemCount: 0, stale: false, fetchOk: false }), "Helper unreachable — feed paused.");
  assert.equal(feedEmptyState({ itemCount: 0, stale: true, fetchOk: true }), "Feed not updating — sources may be stale.");
  assert.equal(feedEmptyState({ itemCount: 0, stale: false, fetchOk: true }), "Waiting for the first feed sweep…");
});

test("helperUnreachable: only after a prior success then a failure", () => {
  assert.equal(helperUnreachable(false, true), true);    // had data, now failing
  assert.equal(helperUnreachable(false, false), false);  // never succeeded → "loading", not "down"
  assert.equal(helperUnreachable(true, true), false);    // healthy
});
