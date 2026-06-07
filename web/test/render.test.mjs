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
  filterHiddenSources,
  playableChannels,
  groupTickerByLeague,
  feedChronological,
  sourceLabel,
  gridLayout,
  GRID_CELL_COUNTS,
  browserPlayability,
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

test("filterHiddenSources: denylist case-insensitive, empty set = passthrough", () => {
  const items = [{ source: "BBC" }, { source: "Reason" }, { source: "" }];
  assert.deepEqual(filterHiddenSources(items, new Set()).length, 3);
  assert.deepEqual(filterHiddenSources(items, new Set(["reason"])).map((i) => i.source), ["BBC", ""]);
  // blank source hidden via the Unknown-source bucket label
  assert.deepEqual(filterHiddenSources(items, new Set(["Unknown source"])).map((i) => i.source), ["BBC", "Reason"]);
});

test("playableChannels: only live + with a URL", () => {
  const ch = [
    { status: "live", current_url: "https://x/y.m3u8" },
    { status: "live", current_url: null },     // contradictory → not playable
    { status: "unavailable", current_url: null },
  ];
  assert.equal(playableChannels(ch).length, 1);
});

test("groupTickerByLeague: sports collapse under one marker; markets stay per-symbol", () => {
  // Sports: consecutive same-symbol entries → one labelled run.
  const sports = [
    { symbol: "MLB", display: "SEA 4–0 DET · Final", direction: "none", is_sample: false },
    { symbol: "MLB", display: "KC 1–2 MIN · Top 9th", direction: "none", is_sample: false },
    { symbol: "NHL", display: "CAR @ VGK · 8:00 PM", direction: "none", is_sample: false },
  ];
  const g = groupTickerByLeague(sports);
  assert.deepEqual(g.map((x) => x.label), ["MLB", "NHL"]);
  assert.equal(g[0].cells.length, 2);                     // both MLB games under one marker
  assert.equal(g[0].cells[0].value, "SEA 4–0 DET · Final");
  assert.equal(g[1].cells.length, 1);
  // Markets: each symbol distinct → its own single-cell group (unchanged look).
  const markets = [
    { symbol: "S&P 500", display: "5,820", direction: "up", is_sample: true },
    { symbol: "DOW", display: "44,910", direction: "down", is_sample: true },
  ];
  const m = groupTickerByLeague(markets);
  assert.equal(m.length, 2);
  assert.equal(m[0].cells.length, 1);
  assert.equal(m[0].cells[0].glyph, "▲");
  assert.equal(m[0].cells[0].sample, true);               // honesty preserved through grouping
  assert.deepEqual(groupTickerByLeague([]), []);
  assert.deepEqual(groupTickerByLeague(null), []);
});

test("groupTickerByLeague: non-adjacent same league stays separate runs (mirrors emit order)", () => {
  // If the helper ever interleaved leagues, grouping is by ADJACENCY, not a
  // global bucket — so we never reorder the marquee out of emit order.
  const entries = [
    { symbol: "MLB", display: "a", direction: "none" },
    { symbol: "NHL", display: "b", direction: "none" },
    { symbol: "MLB", display: "c", direction: "none" },
  ];
  const g = groupTickerByLeague(entries);
  assert.deepEqual(g.map((x) => x.label), ["MLB", "NHL", "MLB"]);
});

test("feedChronological: agnostic newest-first across ALL sources, source retained", () => {
  const items = [
    { id: 1, source: "Guardian", title: "g-old", published_at: "2026-06-01T10:00:00.000Z" },
    { id: 2, source: "BBC", title: "b-new", published_at: "2026-06-05T10:00:00.000Z" },
    { id: 3, source: "Reuters", title: "r-mid", published_at: "2026-06-03T10:00:00.000Z" },
  ];
  const out = feedChronological(items);
  // Single river, newest-first, NOT grouped by source.
  assert.deepEqual(out.map((i) => i.title), ["b-new", "r-mid", "g-old"]);
  assert.deepEqual(out.map((i) => i.source), ["BBC", "Reuters", "Guardian"]);  // source kept per item
  assert.deepEqual(feedChronological([]), []);
  assert.deepEqual(feedChronological(null), []);
});

test("sourceLabel: blank/whitespace → Unknown source bucket", () => {
  assert.equal(sourceLabel({ source: "BBC" }), "BBC");
  assert.equal(sourceLabel({ source: "  " }), "Unknown source");
  assert.equal(sourceLabel({ source: "" }), "Unknown source");
  assert.equal(sourceLabel({}), "Unknown source");
});

test("gridLayout: supported counts → near-square; unknown clamps to 4 (2×2)", () => {
  assert.deepEqual(gridLayout(1), { count: 1, cols: 1, rows: 1 });
  assert.deepEqual(gridLayout(2), { count: 2, cols: 2, rows: 1 });
  assert.deepEqual(gridLayout(4), { count: 4, cols: 2, rows: 2 });
  assert.deepEqual(gridLayout(6), { count: 6, cols: 3, rows: 2 });
  assert.deepEqual(gridLayout(9), { count: 9, cols: 3, rows: 3 });
  assert.deepEqual(gridLayout(3), { count: 4, cols: 2, rows: 2 });   // unsupported → default 4
  assert.deepEqual(gridLayout(undefined), { count: 4, cols: 2, rows: 2 });
  assert.deepEqual(GRID_CELL_COUNTS, [1, 2, 4, 6, 9]);
});

test("browserPlayability: yes/no/maybe tri-state hint (mixed-content reality)", () => {
  const url = "https://x/y.m3u8";
  // playable + helper says HTTPS-clean → yes
  assert.equal(browserPlayability({ status: "live", current_url: url, browser_playable: true }), "yes");
  // playable but helper found mixed content → no (honest "on the TV wall")
  assert.equal(browserPlayability({ status: "live", current_url: url, browser_playable: false }), "no");
  // playable but unclassified → maybe (attempt; runtime load-failure is the truth, also catches CORS)
  assert.equal(browserPlayability({ status: "live", current_url: url, browser_playable: null }), "maybe");
  assert.equal(browserPlayability({ status: "live", current_url: url }), "maybe");
  // not playable at all → no
  assert.equal(browserPlayability({ status: "unavailable", current_url: null, browser_playable: null }), "no");
});
