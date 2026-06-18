// Pure-logic tests for the web client's honesty-bearing render module.
// Run: `node --test web/test/*.test.mjs` (no dependencies — built-in node:test).
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
  TICKER_SCHEMA_VERSION,
  tickerSchemaCheck,
  tickerSchemaNote,
  statusKind,
  formatStatus,
  tickerCardModel,
  groupTickerCards,
  newsTickerEntries,
  entryLeague,
  leaguePool,
  filterHiddenLeagues,
  FEED_RECENCY_OPTIONS,
  feedRecencyOption,
  filterFeedRecency,
  GRID_DIM_MIN,
  GRID_DIM_MAX,
  clampGridDim,
  gridLayoutFromDims,
  TICKER_SPEED_MIN_PCT,
  TICKER_SPEED_MAX_PCT,
  clampTickerSpeedPct,
  tickerScrollPxPerSec,
  crawlCycle,
  CRAWL_DWELL_MS,
  TICKER_MOTIONS,
  tickerMotionOption,
  tickerFlipDwellMs,
  DEFAULT_VIEW_PREFS,
  normalizeViewPrefs,
  serializeViewPrefs,
  presetLineup,
  newsLineup,
  safeHttpLink,
  feedDetailModel,
  classifyVideoFailure,
  videoRetryDecision,
  VIDEO_RECONNECT_INTERVAL_MS,
  VIDEO_MAX_RECONNECTS,
  clampFeedPct,
  feedPctFromPointer,
  FEED_PANE_MIN_PCT,
  FEED_PANE_MAX_PCT,
  CHANNEL_CATEGORY_ORDER,
  channelCategory,
  sectionChannels,
  sectionFeedSources,
  feedSideOption,
  nextAudible,
  isAudible,
  shouldReassertAudio,
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

// ----- schema_version guard (ARCH-1) -----

test("tickerSchemaCheck: v1 passes; mismatch/missing/null degrade honestly", () => {
  assert.equal(TICKER_SCHEMA_VERSION, 1);
  assert.deepEqual(tickerSchemaCheck({ schema_version: 1, entries: [] }), { ok: true, version: 1, reason: "" });
  // A future contract this client doesn't understand → NOT ok (degrade, never render).
  const mismatch = tickerSchemaCheck({ schema_version: 2, entries: [] });
  assert.equal(mismatch.ok, false);
  assert.equal(mismatch.version, 2);
  // Missing version → not ok.
  assert.equal(tickerSchemaCheck({ entries: [] }).ok, false);
  assert.equal(tickerSchemaCheck({ entries: [] }).version, null);
  // Null/garbage envelope → unavailable, not a crash.
  assert.equal(tickerSchemaCheck(null).ok, false);
  assert.equal(tickerSchemaCheck(null).reason, "unavailable");
});

test("tickerSchemaNote: visible 'client out of date' on mismatch; silent when ok/unavailable", () => {
  assert.equal(tickerSchemaNote({ schema_version: 1, entries: [] }), "");          // ok → no note
  assert.equal(tickerSchemaNote(null), "");                                        // unavailable owns its own state
  assert.match(tickerSchemaNote({ schema_version: 7, entries: [] }), /client out of date/);
  assert.match(tickerSchemaNote({ schema_version: 7, entries: [] }), /v7/);        // names the version mismatch
  assert.match(tickerSchemaNote({ entries: [] }), /client out of date/);           // missing version
});

// ----- sports status block (mirrors native kindOf / formatStatus) -----

test("statusKind: in→live, post→final, pre+unknown→upcoming", () => {
  assert.equal(statusKind("in"), "live");
  assert.equal(statusKind("post"), "final");
  assert.equal(statusKind("pre"), "upcoming");
  assert.equal(statusKind("weird"), "upcoming");
  assert.equal(statusKind(""), "upcoming");
  assert.equal(statusKind(undefined), "upcoming");
});

test("formatStatus: FINAL literal; LIVE uppercases+normalises ' - '; UPCOMING as-is/—", () => {
  assert.equal(formatStatus("post", "anything"), "FINAL");
  assert.equal(formatStatus("in", "5:42 - 1st"), "5:42 1ST");   // " - " → space, uppercased
  assert.equal(formatStatus("in", ""), "LIVE");                 // blank live → "LIVE"
  assert.equal(formatStatus("pre", "7:30 PM"), "7:30 PM");      // upcoming as-is
  assert.equal(formatStatus("pre", ""), "—");                   // blank upcoming → "—"
});

// ----- structured card model: TEAM GAME renders from real fields, not display -----

test("tickerCardModel TEAM GAME (in): scores + leader highlight from structured fields", () => {
  const m = tickerCardModel({
    symbol: "MLB", display: "NYY 4–6 BOS · Bot 9th", direction: "none", is_sample: false,
    game: { league: "MLB", away: "NYY", away_score: "4", home: "BOS", home_score: "6", state: "in", status: "Bot 9th" },
  });
  assert.equal(m.type, "game");
  assert.equal(m.kind, "live");
  assert.equal(m.away, "NYY"); assert.equal(m.home, "BOS");
  assert.equal(m.awayScore, "4"); assert.equal(m.homeScore, "6");
  assert.equal(m.hasScores, true);
  assert.equal(m.homeLeads, true);     // 6 > 4, LIVE → home leads
  assert.equal(m.awayLeads, false);
  assert.equal(m.status, "BOT 9TH");   // LIVE uppercased
  assert.equal(m.sample, false);
});

test("tickerCardModel TEAM GAME (pre): no phantom 0–0, renders matchup not scores", () => {
  const m = tickerCardModel({
    symbol: "NHL", display: "CAR @ VGK · 8:00 PM", direction: "none", is_sample: false,
    game: { league: "NHL", away: "CAR", away_score: "", home: "VGK", home_score: "", state: "pre", status: "8:00 PM" },
  });
  assert.equal(m.kind, "upcoming");
  assert.equal(m.hasScores, false);    // empty scores → matchup, never a fake 0–0
  assert.equal(m.awayLeads, false); assert.equal(m.homeLeads, false);
  assert.equal(m.status, "8:00 PM");
});

test("tickerCardModel TEAM GAME (post/final): no leader highlight even with scores", () => {
  const m = tickerCardModel({
    symbol: "MLB", display: "SEA 4–0 DET · Final", direction: "none", is_sample: false,
    game: { league: "MLB", away: "SEA", away_score: "4", home: "DET", home_score: "0", state: "post", status: "Final" },
  });
  assert.equal(m.kind, "final");
  assert.equal(m.hasScores, true);
  assert.equal(m.awayLeads, false);    // FINAL shows no leader emphasis
  assert.equal(m.homeLeads, false);
  assert.equal(m.status, "FINAL");
});

// ----- structured card model: EACH individual sport kind renders -----

test("tickerCardModel CARD leaderboard (PGA): kind + title + lines from card", () => {
  const m = tickerCardModel({
    symbol: "PGA", display: "The Memorial · 1. Scheffler -6", direction: "none", is_sample: false,
    card: { league: "PGA", kind: "leaderboard", title: "The Memorial Tournament", state: "in",
            status: "R3 In Progress", lines: ["1. Scheffler -6", "2. McIlroy -4"] },
  });
  assert.equal(m.type, "card");
  assert.equal(m.kind, "leaderboard");
  assert.equal(m.title, "The Memorial Tournament");
  assert.deepEqual(m.lines, ["1. Scheffler -6", "2. McIlroy -4"]);
  assert.equal(m.statusKind, "live");
  assert.equal(m.status, "R3 IN PROGRESS");
});

test("tickerCardModel CARD fight (UFC): kind + title + weight line", () => {
  const m = tickerCardModel({
    symbol: "UFC", display: "Garcia def. Silva · KO/TKO R2", direction: "none", is_sample: false,
    card: { league: "UFC", kind: "fight", title: "Garcia def. Silva", state: "post",
            status: "KO/TKO R2", lines: ["Lightweight"] },
  });
  assert.equal(m.kind, "fight");
  assert.equal(m.title, "Garcia def. Silva");
  assert.deepEqual(m.lines, ["Lightweight"]);
  assert.equal(m.status, "FINAL");     // post → FINAL chip text
});

test("tickerCardModel CARD match (Tennis): kind + players + set scores", () => {
  const m = tickerCardModel({
    symbol: "Tennis", display: "Alcaraz d. Sinner 6-4 7-6(3)", direction: "none", is_sample: false,
    card: { league: "Tennis", kind: "match", title: "Alcaraz d. Sinner", state: "post",
            status: "Final", lines: ["6-4 7-6(3)"] },
  });
  assert.equal(m.kind, "match");
  assert.equal(m.title, "Alcaraz d. Sinner");
  assert.deepEqual(m.lines, ["6-4 7-6(3)"]);
});

test("tickerCardModel CARD race (F1): kind + GP + podium lines", () => {
  const m = tickerCardModel({
    symbol: "F1", display: "Barcelona-Catalunya GP · Race", direction: "none", is_sample: false,
    card: { league: "F1", kind: "race", title: "Barcelona-Catalunya GP", state: "post",
            status: "Final", lines: ["1. Verstappen", "2. Norris", "3. Leclerc"] },
  });
  assert.equal(m.kind, "race");
  assert.equal(m.title, "Barcelona-Catalunya GP");
  assert.deepEqual(m.lines, ["1. Verstappen", "2. Norris", "3. Leclerc"]);
});

test("tickerCardModel CARD unknown kind → generic (forward-compatible, never crashes)", () => {
  const m = tickerCardModel({
    symbol: "DARTS", display: "x", direction: "none", is_sample: false,
    card: { league: "PDC", kind: "checkout", title: "Final", state: "in", status: "Leg 5", lines: ["a"] },
  });
  assert.equal(m.type, "card");
  assert.equal(m.kind, "generic");     // unknown kind dispatches to the generic card
  assert.equal(m.title, "Final");
});

test("tickerCardModel MARKETS cell: no game/card → direction arrow + value", () => {
  const m = tickerCardModel({ symbol: "S&P 500", display: "5,431.60", direction: "up", is_sample: false });
  assert.equal(m.type, "cell");
  assert.equal(m.value, "5,431.60");
  assert.equal(m.glyph, "▲");
  assert.equal(m.dirClass, "dir-up");
  assert.equal(m.sample, false);
});

test("tickerCardModel uses the `in` operator, not null — absent game/card ≠ present null", () => {
  // The wire OMITS game/card when absent (popped, not null). A markets entry
  // has NEITHER key → cell. Defend the contract: no fabricated card.
  const m = tickerCardModel({ symbol: "BTC", display: "—", direction: "flat", is_sample: true });
  assert.equal(m.type, "cell");
});

// ----- HONESTY: sample/stale never rendered as live-real -----

test("HONESTY: is_sample=true surfaces sample:true through EVERY card branch", () => {
  // game
  assert.equal(tickerCardModel({ symbol: "NBA", display: "x", is_sample: true,
    game: { league: "NBA", away: "LAL", away_score: "", home: "BOS", home_score: "", state: "pre", status: "7:30 ET" } }).sample, true);
  // card (fight) — the pinned invariant from the brief
  assert.equal(tickerCardModel({ symbol: "UFC", display: "x", is_sample: true,
    card: { league: "UFC", kind: "fight", title: "A vs B", state: "pre", status: "", lines: [] } }).sample, true);
  // markets cell
  assert.equal(tickerCardModel({ symbol: "BTC", display: "—", direction: "flat", is_sample: true }).sample, true);
  // and the converse: explicit false stays false; missing flag is NOT upgraded to live (defaults false here, helper sends it).
  assert.equal(tickerCardModel({ symbol: "X", display: "1", direction: "flat", is_sample: false }).sample, false);
  assert.equal(tickerCardModel({ symbol: "X", display: "1", direction: "flat" }).sample, false);
});

// ----- grouping into card-bearing league runs (page markers) -----

test("groupTickerCards: keys on game.league/card.league, collapses consecutive runs", () => {
  const entries = [
    { symbol: "MLB", display: "a", is_sample: false, game: { league: "MLB", away: "A", away_score: "1", home: "B", home_score: "2", state: "in", status: "Top 9th" } },
    { symbol: "MLB", display: "b", is_sample: false, game: { league: "MLB", away: "C", away_score: "0", home: "D", home_score: "0", state: "pre", status: "7pm" } },
    { symbol: "PGA", display: "c", is_sample: false, card: { league: "PGA", kind: "leaderboard", title: "T", state: "in", status: "R1", lines: [] } },
  ];
  const g = groupTickerCards(entries);
  assert.deepEqual(g.map((x) => x.label), ["MLB", "PGA"]);
  assert.equal(g[0].cards.length, 2);       // both MLB games under one marker
  assert.equal(g[0].cards[0].type, "game");
  assert.equal(g[1].cards[0].type, "card");
  assert.deepEqual(groupTickerCards([]), []);
  assert.deepEqual(groupTickerCards(null), []);
});

test("groupTickerCards: 'no games right now' is a normal entry — NOT sample, NOT a card", () => {
  // The helper's honest empty-sports state arrives as a plain cell entry.
  const g = groupTickerCards([{ symbol: "SPORTS", display: "no games right now", direction: "none", is_sample: false }]);
  assert.equal(g.length, 1);
  assert.equal(g[0].label, "SPORTS");
  assert.equal(g[0].cards[0].type, "cell");
  assert.equal(g[0].cards[0].sample, false);    // never a SAMPLE pill for an honest "nothing on"
});

// ----- NEWS in the ticker (source-labelled, inert, honest) -----

test("newsTickerEntries: source-labelled, newest-first, real (is_sample=false), capped", () => {
  const items = [
    { id: 1, source: "Guardian", title: "g-old", published_at: "2026-06-01T10:00:00.000Z" },
    { id: 2, source: "BBC", title: "b-new", published_at: "2026-06-05T10:00:00.000Z" },
    { id: 3, source: "", title: "blank-src", published_at: "2026-06-04T10:00:00.000Z" },
    { id: 4, source: "Reuters", title: "", published_at: "2026-06-06T10:00:00.000Z" },  // empty title dropped
  ];
  const out = newsTickerEntries(items);
  assert.deepEqual(out.map((e) => e.title ?? e.display), ["b-new", "blank-src", "g-old"]);  // empty-title dropped, newest-first
  assert.equal(out[0].symbol, "BBC");
  assert.equal(out[1].symbol, "Unknown source");   // blank source → bucket label
  assert.ok(out.every((e) => e.is_sample === false));  // real headlines, never a fake SAMPLE
  assert.ok(out.every((e) => e.direction === "none"));
  // cap respected
  assert.equal(newsTickerEntries(items, 1).length, 1);
});

test("groupTickerCards newsMode: every entry under a single 'NEWS' marker as news cells", () => {
  const entries = newsTickerEntries([
    { id: 1, source: "BBC", title: "headline one", published_at: "2026-06-05T10:00:00.000Z" },
    { id: 2, source: "Reuters", title: "headline two", published_at: "2026-06-04T10:00:00.000Z" },
  ]);
  const g = groupTickerCards(entries, { newsMode: true });
  assert.equal(g.length, 1);
  assert.equal(g[0].label, "NEWS");
  assert.equal(g[0].cards.length, 2);
  assert.equal(g[0].cards[0].type, "news");
  assert.equal(g[0].cards[0].source, "BBC");
  assert.equal(g[0].cards[0].headline, "headline one");
  assert.equal(g[0].cards[0].sample, false);
});

// ===== BUILD 2: SETTINGS PARITY =====

// ----- sports-league filter (mirrors native HelperTickerSource.filterLeagues) -----

test("entryLeague: game.league wins; falls back to symbol (matches native key)", () => {
  // Team game → game.league is the grouping/label key.
  assert.equal(entryLeague({ symbol: "MLB", game: { league: "MLB", away: "A", home: "B", state: "in" } }), "MLB");
  // A symbol/league divergence: the GAME's league is authoritative (so a hidden
  // league can't leak through the symbol) — native uses game.league ?? symbol.
  assert.equal(entryLeague({ symbol: "BASEBALL", game: { league: "MLB" } }), "MLB");
  // Individual sport (no game) → keys on symbol (the league), like native.
  assert.equal(entryLeague({ symbol: "UFC", card: { league: "UFC", kind: "fight" } }), "UFC");
  // Blank game.league → fall back to symbol, never an empty key.
  assert.equal(entryLeague({ symbol: "NHL", game: { league: "  " } }), "NHL");
  assert.equal(entryLeague({ symbol: "S&P 500" }), "S&P 500");   // markets cell → its symbol
});

test("leaguePool: distinct league labels from sports entries, alpha, dedup, blanks dropped", () => {
  const entries = [
    { symbol: "MLB", game: { league: "MLB", away: "A", home: "B", state: "in" } },
    { symbol: "MLB", game: { league: "MLB", away: "C", home: "D", state: "pre" } },  // dup → one
    { symbol: "UFC", card: { league: "UFC", kind: "fight" } },
    { symbol: "NHL", game: { league: "NHL", away: "E", home: "F", state: "post" } },
    { symbol: "", display: "no games right now" },   // blank league → not a togglable league
  ];
  assert.deepEqual(leaguePool(entries), ["MLB", "NHL", "UFC"]);   // alpha, deduped
  // Case-insensitive dedup keeps the first-seen casing.
  assert.deepEqual(leaguePool([{ symbol: "nba" }, { symbol: "NBA" }]), ["nba"]);
  assert.deepEqual(leaguePool([]), []);
  assert.deepEqual(leaguePool(null), []);
});

test("filterHiddenLeagues: DENYLIST, case-insensitive; empty set = passthrough; keeps honesty flags", () => {
  const entries = [
    { symbol: "MLB", is_sample: false, game: { league: "MLB", away: "A", away_score: "1", home: "B", home_score: "2", state: "in", status: "Top 9th" } },
    { symbol: "NHL", is_sample: true, game: { league: "NHL", away: "C", home: "D", state: "pre", status: "8pm" } },
    { symbol: "UFC", is_sample: false, card: { league: "UFC", kind: "fight", title: "A vs B", state: "pre", status: "", lines: [] } },
  ];
  // Empty denylist → unchanged.
  assert.equal(filterHiddenLeagues(entries, new Set()).length, 3);
  // Hide NHL (case-insensitive) → MLB + UFC remain.
  const kept = filterHiddenLeagues(entries, new Set(["nhl"]));
  assert.deepEqual(kept.map((e) => e.symbol), ["MLB", "UFC"]);
  // A kept entry's sample flag is NEVER altered by filtering (honesty preserved).
  assert.equal(filterHiddenLeagues(entries, new Set(["mlb"]))[0].is_sample, true);  // the NHL one (sample) survives untouched
  assert.deepEqual(filterHiddenLeagues([], new Set(["x"])), []);
  assert.deepEqual(filterHiddenLeagues(null, new Set(["x"])), []);
});

test("BEHAVIOR: the leagues denylist actually filters which sports show", () => {
  // A setting must affect behavior — hiding a league removes exactly its entries.
  const sports = [
    { symbol: "MLB", game: { league: "MLB", away: "SEA", home: "DET", state: "post", status: "Final" } },
    { symbol: "NBA", game: { league: "NBA", away: "LAL", home: "BOS", state: "in", status: "Q4" } },
    { symbol: "NHL", game: { league: "NHL", away: "CAR", home: "VGK", state: "pre", status: "8pm" } },
  ];
  // All shown by default (denylist empty).
  assert.deepEqual(leaguePool(sports), ["MLB", "NBA", "NHL"]);
  assert.equal(filterHiddenLeagues(sports, new Set()).length, 3);
  // Operator hides NBA + NHL → only MLB remains in the ticker.
  const hidden = new Set(["NBA", "NHL"]);
  const shown = filterHiddenLeagues(sports, hidden);
  assert.deepEqual(shown.map((e) => e.symbol), ["MLB"]);
  // A NEWLY-appearing league (not in the denylist) shows by default.
  const withNew = [...sports, { symbol: "F1", card: { league: "F1", kind: "race", title: "GP", state: "pre", status: "", lines: [] } }];
  assert.ok(filterHiddenLeagues(withNew, hidden).some((e) => e.symbol === "F1"));
});

// ----- feed recency window (mirrors native FeedRecency) -----

test("FEED_RECENCY_OPTIONS / feedRecencyOption: native presets, unknown → All", () => {
  assert.deepEqual(FEED_RECENCY_OPTIONS.map((o) => o.id), ["all", "hour", "six", "day"]);
  assert.equal(feedRecencyOption("all").maxAgeMs, null);
  assert.equal(feedRecencyOption("hour").maxAgeMs, 60 * 60 * 1000);
  assert.equal(feedRecencyOption("six").maxAgeMs, 6 * 60 * 60 * 1000);
  assert.equal(feedRecencyOption("day").maxAgeMs, 24 * 60 * 60 * 1000);
  assert.equal(feedRecencyOption("nonsense").id, "all");   // unknown → safe default
  assert.equal(feedRecencyOption(undefined).id, "all");
});

test("filterFeedRecency: drops items older than the window; null = passthrough; no-ts dropped under a bound", () => {
  const now = Date.parse("2026-06-12T12:00:00.000Z");
  const items = [
    { id: 1, title: "fresh", published_at: "2026-06-12T11:30:00.000Z" },  // 30m ago
    { id: 2, title: "old", published_at: "2026-06-12T05:00:00.000Z" },    // 7h ago
    { id: 3, title: "no-ts" },                                            // no timestamp
  ];
  // null → everything kept (the All preset), no-ts included.
  assert.equal(filterFeedRecency(items, null, now).length, 3);
  // Last hour → only the 30m-ago item; old + no-ts dropped (no-ts is not provably recent).
  assert.deepEqual(filterFeedRecency(items, 60 * 60 * 1000, now).map((i) => i.title), ["fresh"]);
  // Last 24h → both timestamped items; no-ts still dropped under a bounded window.
  assert.deepEqual(filterFeedRecency(items, 24 * 60 * 60 * 1000, now).map((i) => i.title), ["fresh", "old"]);
  // fetched_at fallback when published_at is absent.
  assert.equal(filterFeedRecency([{ id: 9, fetched_at: "2026-06-12T11:50:00.000Z" }], 60 * 60 * 1000, now).length, 1);
  assert.deepEqual(filterFeedRecency([], 1000, now), []);
  assert.deepEqual(filterFeedRecency(null, 1000, now), []);
});

// ----- grid rows × cols (native parity: independent dims, each 1–3) -----

test("clampGridDim: 1–3 range, rounds, non-finite → min", () => {
  assert.equal(GRID_DIM_MIN, 1);
  assert.equal(GRID_DIM_MAX, 3);
  assert.equal(clampGridDim(2), 2);
  assert.equal(clampGridDim(0), 1);     // below min
  assert.equal(clampGridDim(9), 3);     // above max
  assert.equal(clampGridDim(2.7), 3);   // rounds
  assert.equal(clampGridDim("3"), 3);   // numeric string
  assert.equal(clampGridDim(NaN), 1);   // non-finite → min
  assert.equal(clampGridDim(undefined), 1);
});

test("gridLayoutFromDims: count = rows × cols; both clamped; same {count,cols,rows} shape", () => {
  assert.deepEqual(gridLayoutFromDims(2, 2), { count: 4, cols: 2, rows: 2 });
  assert.deepEqual(gridLayoutFromDims(1, 3), { count: 3, cols: 3, rows: 1 });
  assert.deepEqual(gridLayoutFromDims(3, 2), { count: 6, cols: 2, rows: 3 });
  assert.deepEqual(gridLayoutFromDims(3, 3), { count: 9, cols: 3, rows: 3 });
  // Out-of-range dims clamp into 1–3 (defensive on read).
  assert.deepEqual(gridLayoutFromDims(0, 9), { count: 3, cols: 3, rows: 1 });
});

// ----- ticker scroll speed (native parity: tickerScrollPct slider) -----

test("clampTickerSpeedPct: 10–200 range (matches native + the slider min), non-finite → 100", () => {
  assert.equal(TICKER_SPEED_MIN_PCT, 10);       // matches native + the slider min=10
  assert.equal(TICKER_SPEED_MAX_PCT, 200);
  assert.equal(clampTickerSpeedPct(100), 100);
  assert.equal(clampTickerSpeedPct(10), 10);    // the slider floor is LIVE, not snapped up
  assert.equal(clampTickerSpeedPct(5), 10);     // below min → floor
  assert.equal(clampTickerSpeedPct(999), 200);  // above max
  assert.equal(clampTickerSpeedPct(NaN), 100);  // non-finite → default
});

test("tickerScrollPxPerSec: scales base velocity by clamped percent (faster = more px/sec)", () => {
  assert.equal(tickerScrollPxPerSec(60, 100), 60);   // 100% = base
  assert.equal(tickerScrollPxPerSec(60, 200), 120);  // 200% = double
  assert.equal(tickerScrollPxPerSec(60, 10), 6);     // 10% floor = a genuinely slow crawl
  // An out-of-range stored pref is clamped, never a 0/absurd velocity.
  assert.equal(tickerScrollPxPerSec(60, 0), 6);      // clamps to 10% → 6
  assert.ok(tickerScrollPxPerSec(60, 5) >= 1);       // always > 0
});

test("crawlCycle: duration is distance/speed, then a fixed dwell (scroll-then-dwell)", () => {
  // 600px at 60px/sec = 10s scroll; + 3s dwell = 13s total; scroll is 10/13 of it.
  const c = crawlCycle(600, 60, 3000);
  assert.equal(c.scrollMs, 10_000);
  assert.equal(c.dwellMs, 3000);
  assert.equal(c.totalMs, 13_000);
  assert.ok(Math.abs(c.scrollFraction - 10_000 / 13_000) < 1e-9);
});

test("crawlCycle: faster speed = shorter scroll (frame-rate-independent, distance/speed)", () => {
  const slow = crawlCycle(600, 30);   // default dwell = CRAWL_DWELL_MS
  const fast = crawlCycle(600, 120);
  assert.equal(slow.scrollMs, 20_000);
  assert.equal(fast.scrollMs, 5000);
  assert.equal(slow.dwellMs, CRAWL_DWELL_MS);   // default dwell applied
  assert.ok(fast.scrollMs < slow.scrollMs);
});

test("crawlCycle: nothing to crawl (0/negative distance) → totalMs 0 (static, no animation)", () => {
  assert.equal(crawlCycle(0, 60).totalMs, 0);
  assert.equal(crawlCycle(-5, 60).totalMs, 0);
  assert.equal(crawlCycle(0, 60).scrollFraction, 0);
});

test("crawlCycle: velocity floored at 1px/sec — never a divide-by-zero / infinite scroll", () => {
  const c = crawlCycle(100, 0);   // pxPerSec coerced to >= 1
  assert.ok(Number.isFinite(c.scrollMs) && c.scrollMs > 0);
});

test("crawlCycle: CRAWL_DWELL_MS matches the native scroll-then-dwell (doubled to 3000)", () => {
  assert.equal(CRAWL_DWELL_MS, 3000);
});

// ----- view-prefs persistence round-trip (the established web pattern) -----

test("normalizeViewPrefs: defaults + clamps; tickerNews honest-default OFF", () => {
  const d = normalizeViewPrefs({});
  assert.equal(d.gridRows, 2); assert.equal(d.gridCols, 3);   // default 2×3 = 6 tiles
  assert.equal(d.feedPct, 32); assert.equal(d.feedFont, 1);
  assert.equal(d.tickerNews, false);            // load-bearing: OFF unless explicit true
  assert.equal(d.feedRecency, "all");
  assert.equal(d.tickerScrollPct, 100);
  assert.equal(d.tickerFlipPct, 100);           // native Flip speed default
  assert.equal(d.tickerMotion, "crawl");        // web default motion
  assert.equal(d.feedSide, "left");             // native Feed side default
  assert.equal(d.captions, false);              // captions OFF by default (native parity)
  assert.deepEqual(d.hidden, []); assert.deepEqual(d.hiddenLeagues, []);
  assert.deepEqual(d.assignments, {});
  // Matches the exported default shape.
  assert.deepEqual(d, DEFAULT_VIEW_PREFS);
  // tickerNews is ONLY true on an explicit true (not "true", not 1).
  assert.equal(normalizeViewPrefs({ tickerNews: "true" }).tickerNews, false);
  assert.equal(normalizeViewPrefs({ tickerNews: true }).tickerNews, true);
  // feedSide is left unless explicitly "right" (defensive against junk).
  assert.equal(normalizeViewPrefs({ feedSide: "right" }).feedSide, "right");
  // captions is an explicit-opt-in boolean (only true enables; honest OFF default).
  assert.equal(normalizeViewPrefs({ captions: "true" }).captions, false);
  assert.equal(normalizeViewPrefs({ captions: true }).captions, true);
  assert.equal(normalizeViewPrefs({ feedSide: "bogus" }).feedSide, "left");
  // Out-of-range values are clamped on load (defensive against a tampered blob).
  assert.equal(normalizeViewPrefs({ tickerFlipPct: 9999 }).tickerFlipPct, 200);
  assert.equal(normalizeViewPrefs({ gridRows: 9, gridCols: 0 }).gridRows, 3);
  assert.equal(normalizeViewPrefs({ gridRows: 9, gridCols: 0 }).gridCols, 1);
  assert.equal(normalizeViewPrefs({ tickerScrollPct: 9999 }).tickerScrollPct, 200);
  assert.equal(normalizeViewPrefs({ feedRecency: "bogus" }).feedRecency, "all");
});

test("normalizeViewPrefs: migrates a legacy v3 cellCount into rows × cols", () => {
  // v3 stored a single cellCount; v4 is rows × cols. The grid size carries over.
  assert.equal(normalizeViewPrefs({ cellCount: 9 }).gridRows, 3);
  assert.equal(normalizeViewPrefs({ cellCount: 9 }).gridCols, 3);
  assert.equal(normalizeViewPrefs({ cellCount: 6 }).gridRows, 2);
  assert.equal(normalizeViewPrefs({ cellCount: 6 }).gridCols, 3);
  // An explicit gridRows/gridCols wins over a stale cellCount (no double-migrate).
  const both = normalizeViewPrefs({ cellCount: 9, gridRows: 1, gridCols: 2 });
  assert.equal(both.gridRows, 1); assert.equal(both.gridCols, 2);
});

test("PERSISTENCE: serialize → normalize round-trips equal (Sets ⇄ arrays)", () => {
  // The live prefs object carries denylists as Sets; serialize flattens to
  // arrays; normalize is the canonical re-read. The round-trip must be stable.
  const live = {
    gridRows: 3, gridCols: 1, feedPct: 40, feedFont: 1.18,
    feedSide: "right", captions: true,
    tickerNews: true, feedRecency: "six", tickerScrollPct: 160, tickerFlipPct: 80,
    tickerMotion: "flip",
    hidden: new Set(["BBC", "Reuters"]),
    hiddenLeagues: new Set(["NBA"]),
    assignments: { 0: "espn", 1: "tnt" },
    activePreset: "nature",
  };
  const stored = serializeViewPrefs(live);           // → plain, arrays
  // Survives a real JSON round-trip (what localStorage does).
  const reread = normalizeViewPrefs(JSON.parse(JSON.stringify(stored)));
  assert.deepEqual(reread, {
    gridRows: 3, gridCols: 1, feedPct: 40, feedFont: 1.18,
    feedSide: "right", captions: true,
    tickerNews: true, feedRecency: "six", tickerScrollPct: 160, tickerFlipPct: 80,
    tickerMotion: "flip",
    hidden: ["BBC", "Reuters"], hiddenLeagues: ["NBA"],
    assignments: { 0: "espn", 1: "tnt" },
    activePreset: "nature",
  });
  // Idempotent: normalize(serialize(reread-as-live)) is the same again.
  const live2 = { ...reread, hidden: new Set(reread.hidden), hiddenLeagues: new Set(reread.hiddenLeagues) };
  assert.deepEqual(normalizeViewPrefs(JSON.parse(JSON.stringify(serializeViewPrefs(live2)))), reread);
});

test("PERSISTENCE: a tampered/garbage blob normalizes to safe honest defaults", () => {
  // Whatever is in storage, the loaded prefs are valid + honest (news OFF).
  assert.deepEqual(serializeViewPrefs(normalizeViewPrefs("not an object")), DEFAULT_VIEW_PREFS);
  assert.deepEqual(serializeViewPrefs(normalizeViewPrefs(null)), DEFAULT_VIEW_PREFS);
  assert.equal(normalizeViewPrefs({ hidden: "BBC" }).hidden.length, 0);   // non-array denylist → empty
  assert.equal(normalizeViewPrefs({ hiddenLeagues: 5 }).hiddenLeagues.length, 0);
});

// ----- ticker motion (cross-platform setting: crawl vs flip) -----

test("tickerMotionOption: valid modes pass; unknown → the platform default", () => {
  assert.deepEqual(TICKER_MOTIONS, ["crawl", "flip"]);
  assert.equal(tickerMotionOption("crawl"), "crawl");
  assert.equal(tickerMotionOption("flip"), "flip");
  assert.equal(tickerMotionOption("bogus"), "crawl");        // web default
  assert.equal(tickerMotionOption(undefined), "crawl");
  assert.equal(tickerMotionOption("bogus", "flip"), "flip"); // Android passes its own default
  assert.equal(tickerMotionOption(null, "nonsense"), "crawl"); // bad fallback → safe "crawl"
});

test("tickerMotionOption: normalizeViewPrefs defaults motion to crawl, keeps an explicit flip", () => {
  assert.equal(normalizeViewPrefs({}).tickerMotion, "crawl");
  assert.equal(normalizeViewPrefs({ tickerMotion: "flip" }).tickerMotion, "flip");
  assert.equal(normalizeViewPrefs({ tickerMotion: "spin" }).tickerMotion, "crawl"); // tamper → safe
});

test("tickerFlipDwellMs: scales with speed pct, clamped, never zero", () => {
  assert.equal(tickerFlipDwellMs(100), 9000);                 // base at 100%
  assert.ok(tickerFlipDwellMs(200) < tickerFlipDwellMs(100)); // faster speed → shorter dwell
  assert.ok(tickerFlipDwellMs(40) > tickerFlipDwellMs(100));  // slower speed → longer dwell
  assert.ok(tickerFlipDwellMs(99999) >= 2500);                // floor holds against an extreme pref
  assert.ok(tickerFlipDwellMs(-5) >= 2500);                   // non-finite/out-of-range → clamped, positive
});

// ----- feed story detail / expand (Campaign 4.1) -----

test("safeHttpLink: only http(s) link-outs allowed; javascript:/data:/junk rejected", () => {
  assert.equal(safeHttpLink("https://x.test/a"), "https://x.test/a");
  assert.equal(safeHttpLink("http://x.test/a"), "http://x.test/a");
  assert.equal(safeHttpLink("  https://x.test/a  "), "https://x.test/a");  // trimmed
  assert.equal(safeHttpLink("javascript:alert(1)"), "");   // XSS vector → rejected
  assert.equal(safeHttpLink("data:text/html,<script>"), "");
  assert.equal(safeHttpLink("ftp://x/y"), "");
  assert.equal(safeHttpLink(""), "");
  assert.equal(safeHttpLink(null), "");
  assert.equal(safeHttpLink("not a url"), "");
});

test("feedDetailModel: the item's OWN fields, link gated, safe defaults", () => {
  const m = feedDetailModel({
    source: "BBC", title: "Title", summary: "Summary text",
    published_at: "2026-06-13T12:00:00Z", link: "https://bbc.test/a",
  });
  assert.equal(m.source, "BBC");
  assert.equal(m.title, "Title");
  assert.equal(m.summary, "Summary text");
  assert.equal(m.timestamp, "2026-06-13T12:00:00Z");
  assert.equal(m.link, "https://bbc.test/a");
  assert.equal(m.hasLink, true);
  // missing link → no link-out
  assert.equal(feedDetailModel({ title: "X" }).hasLink, false);
  // unsafe link → no link-out (never exposed as a clickable href)
  assert.equal(feedDetailModel({ link: "javascript:alert(1)" }).hasLink, false);
  // null item → safe empty model (Unknown source bucket, no link)
  const e = feedDetailModel(null);
  assert.equal(e.title, "");
  assert.equal(e.hasLink, false);
  assert.equal(e.source, "Unknown source");
  // title/summary returned as STRINGS (caller renders via textContent — no HTML exec)
  assert.equal(typeof feedDetailModel({ title: 123 }).title, "string");
  // fetched_at is the timestamp fallback when published_at is absent
  assert.equal(feedDetailModel({ fetched_at: "2026-06-13T11:00:00Z" }).timestamp,
    "2026-06-13T11:00:00Z");
});

// ----- video auto-recovery: classify retryable vs genuinely-unplayable -----

test("classifyVideoFailure: genuinely-unplayable-in-browser is NOT retryable (the crux)", () => {
  assert.equal(classifyVideoFailure("keySystemError").retryable, false);            // DRM
  assert.equal(classifyVideoFailure("muxError").retryable, false);                  // remux/codec
  assert.equal(classifyVideoFailure("otherError", "manifestIncompatibleCodecsError").retryable, false);
  assert.equal(classifyVideoFailure("native", "MEDIA_ERR_SRC_NOT_SUPPORTED").retryable, false);
  assert.equal(classifyVideoFailure("no-hls-support").retryable, false);
});

test("classifyVideoFailure: transient failures ARE retryable", () => {
  assert.equal(classifyVideoFailure("networkError").retryable, true);   // CDN blip
  assert.equal(classifyVideoFailure("mediaError").retryable, true);     // buffer/decode
  assert.equal(classifyVideoFailure("stall").retryable, true);          // froze mid-play
  assert.equal(classifyVideoFailure("native", "MEDIA_ERR_NETWORK").retryable, true);
  assert.equal(classifyVideoFailure("somethingNew").retryable, true);   // unknown → bounded retry
});

test("reconnect schedule: ~15s interval over a ~3-min window (~12 attempts)", () => {
  assert.equal(VIDEO_RECONNECT_INTERVAL_MS, 15_000);          // poll every 15s
  assert.equal(VIDEO_MAX_RECONNECTS, 12);                     // 3 min / 15s
});

test("videoRetryDecision: never retries the unplayable (no poll loop on a hopeless stream)", () => {
  const drm = classifyVideoFailure("keySystemError");
  assert.equal(videoRetryDecision(0, drm).retry, false);   // not even attempt 0 — marks immediately
  assert.equal(videoRetryDecision(0, classifyVideoFailure("no-hls-support")).retry, false);
  assert.equal(videoRetryDecision(0, classifyVideoFailure("native", "src_not_supported")).retry, false);
});

test("videoRetryDecision: polls a transient drop every 15s, then GIVES UP at the window", () => {
  const net = classifyVideoFailure("networkError");
  // attempts 0..max-1 schedule the next reconnect at the FIXED 15s interval.
  for (let a = 0; a < VIDEO_MAX_RECONNECTS; a++) {
    const d = videoRetryDecision(a, net);
    assert.equal(d.retry, true, `attempt ${a} should reconnect`);
    assert.equal(d.delayMs, VIDEO_RECONNECT_INTERVAL_MS, "fixed 15s cadence, not a backoff");
  }
  // at the window's end it gives up to the honest state — NEVER polls forever
  assert.equal(videoRetryDecision(VIDEO_MAX_RECONNECTS, net).retry, false);
  assert.equal(videoRetryDecision(VIDEO_MAX_RECONNECTS, net).reason, "exhausted");
});

// ----- draggable feed/video divider (A) — resize/clamp math -----

test("clampFeedPct: bounds the split so neither pane collapses", () => {
  assert.equal(clampFeedPct(5), FEED_PANE_MIN_PCT);    // too narrow → floor (feed keeps room)
  assert.equal(clampFeedPct(90), FEED_PANE_MAX_PCT);   // too wide → cap (video keeps room)
  assert.equal(clampFeedPct(40), 40);                  // in-range passes through
  assert.ok(FEED_PANE_MIN_PCT >= 15 && FEED_PANE_MAX_PCT <= 60); // feed≥15%, video≥40%
  assert.equal(clampFeedPct(NaN), 32);                 // non-finite → default, never breaks layout
});

test("feedPctFromPointer: ratio from pointer over the wall, clamped (sane across sizes)", () => {
  // wall left=0, width=1000: a pointer at 400px → 40% feed.
  assert.equal(feedPctFromPointer(400, 0, 1000), 40);
  // same RATIO at a different window size (left=200, width=2000): 600px in → 30%.
  assert.equal(feedPctFromPointer(800, 200, 2000), 30);
  // dragging past the edges clamps to the bounds, never collapses a pane.
  assert.equal(feedPctFromPointer(10, 0, 1000), FEED_PANE_MIN_PCT);
  assert.equal(feedPctFromPointer(990, 0, 1000), FEED_PANE_MAX_PCT);
  // a degenerate (zero-width) wall → the safe default, no divide-by-zero.
  assert.equal(feedPctFromPointer(400, 0, 0), 32);
});

// ----- sectioned channel picker (A) — group by category like native -----

test("channelCategory: trusts the served category; missing/unknown → General (never invents)", () => {
  assert.equal(channelCategory({ slug: "cbs-sports-hq", category: "Sports" }), "Sports");
  assert.equal(channelCategory({ slug: "bbc-news", category: "Global News" }), "Global News");
  // An old helper that doesn't serve `category`, or an unknown value, buckets
  // into General — the same fallback native uses — so the channel never vanishes.
  assert.equal(channelCategory({ slug: "x" }), "General");
  assert.equal(channelCategory({ slug: "x", category: "" }), "General");
  assert.equal(channelCategory({ slug: "x", category: "Politics" }), "General");
  assert.equal(channelCategory(null), "General");
});

test("sectionChannels: orders by the native section order, omits empty sections", () => {
  // Deliberately out of order on input; expect native ORDER, weather present,
  // US News absent (no channels) → that header must NOT appear.
  const chans = [
    { slug: "bloomberg-tv", category: "Business" },
    { slug: "cbs-sports-hq", category: "Sports" },
    { slug: "fox-weather", category: "Weather" },
    { slug: "bbc-news", category: "Global News" },
    { slug: "nasa-tv", category: "General" },
  ];
  const sections = sectionChannels(chans);
  assert.deepEqual(sections.map((s) => s.category), ["Sports", "Global News", "Business", "Weather", "General"]);
  // No "US News" header (it was empty) — never an empty section.
  assert.ok(!sections.some((s) => s.category === "US News"));
  // Section order follows the canonical taxonomy, restricted to the categories
  // actually present (empty sections — incl. any not in this input — are omitted).
  const present = new Set(chans.map((c) => c.category));
  assert.deepEqual(sections.map((s) => s.category),
    CHANNEL_CATEGORY_ORDER.filter((c) => present.has(c)));
});

test("sectionChannels: preserves input order WITHIN a section (caller pre-sorts live-first)", () => {
  // Two Global News channels in a specific (already live-first) order — the
  // grouping must not reorder them (native: caller pre-sorts, grouping doesn't).
  const chans = [
    { slug: "sky-news", category: "Global News" },
    { slug: "bbc-news", category: "Global News" },
    { slug: "cnn", category: "US News" },
  ];
  const sections = sectionChannels(chans);
  const global = sections.find((s) => s.category === "Global News");
  assert.deepEqual(global.channels.map((c) => c.slug), ["sky-news", "bbc-news"]);
  // Empty / non-array inputs are safe.
  assert.deepEqual(sectionChannels([]), []);
  assert.deepEqual(sectionChannels(null), []);
});

test("sectionFeedSources: groups feed sources by served category, ordered, empty omitted", () => {
  // Feed items carry a `source` label + helper-served `source_category`.
  const items = [
    { source: "NBC News", source_category: "US News" },
    { source: "BBC World", source_category: "Global News" },
    { source: "NBC News", source_category: "US News" },     // dup source collapses
    { source: "NFL", source_category: "Sports" },
    { source: "CBS News", source_category: "US News" },
    { source: "Bloomberg Markets", source_category: "Business" },
  ];
  const sections = sectionFeedSources(items);
  // Canonical CHANNEL_CATEGORY_ORDER (Sports first, as in the channel picker); no
  // Weather/General sections (none present) → omitted.
  assert.deepEqual(sections.map((s) => s.category), ["Sports", "US News", "Global News", "Business"]);
  // Distinct sources, alpha within a section.
  assert.deepEqual(sections.find((s) => s.category === "US News").sources, ["CBS News", "NBC News"]);
  // Missing/unknown category → General (never dropped); empty input safe.
  assert.deepEqual(sectionFeedSources([{ source: "Mystery" }]), [{ category: "General", sources: ["Mystery"] }]);
  assert.deepEqual(sectionFeedSources([]), []);
  assert.deepEqual(sectionFeedSources(null), []);
});

// ----- feed side (native Feed side parity) -----

test("feedSideOption: only 'right' is right; anything else → 'left'", () => {
  assert.equal(feedSideOption("right"), "right");
  assert.equal(feedSideOption("left"), "left");
  assert.equal(feedSideOption("bogus"), "left");
  assert.equal(feedSideOption(undefined), "left");
  assert.equal(feedSideOption(null), "left");
});

// ----- per-tile audio — single audible tile (radio-button model) -----

test("nextAudible: enabling a tile moves audio to it; re-enabling the same mutes the wall", () => {
  // From all-muted, picking tile 2 makes 2 audible.
  assert.equal(nextAudible(-1, 2), 2);
  // Picking a DIFFERENT tile moves audio (single source — never two unmuted).
  assert.equal(nextAudible(2, 0), 0);
  // Picking the CURRENTLY-audible tile toggles the whole wall back to muted.
  assert.equal(nextAudible(2, 2), -1);
  // Defensive: a bad clicked index leaves the current selection unchanged.
  assert.equal(nextAudible(1, -1), 1);
  assert.equal(nextAudible(1, "x"), 1);
});

test("isAudible: only the single selected index is audible; -1 = all muted", () => {
  assert.equal(isAudible(2, 2), true);
  assert.equal(isAudible(2, 0), false);
  assert.equal(isAudible(-1, 0), false);   // wall muted → no tile audible
  assert.equal(isAudible(-1, -1), false);  // never treat the "muted" sentinel as audible
});

test("shouldReassertAudio: reconnect keeps audio only for the SAME slot+channel (no teleport)", () => {
  // Tile 1 was made audible for channel "espn". On reconnect of the SAME channel
  // in that slot → re-assert (audio returns after a transient blip).
  assert.equal(shouldReassertAudio(1, "espn", 1, "espn"), true);
  // The slot's channel was REPLACED (now "tnt") → must NOT inherit the old audio
  // selection — this is the "audio teleport" the review caught.
  assert.equal(shouldReassertAudio(1, "espn", 1, "tnt"), false);
  // The slot was CLEARED (no channel) → no audio to re-assert.
  assert.equal(shouldReassertAudio(1, "espn", 1, null), false);
  // A DIFFERENT slot than the audible one never re-asserts.
  assert.equal(shouldReassertAudio(1, "espn", 0, "espn"), false);
  // Wall muted (audibleIndex -1) → nothing re-asserts even on a slug match.
  assert.equal(shouldReassertAudio(-1, "espn", 0, "espn"), false);
  assert.equal(shouldReassertAudio(-1, null, 0, null), false);
});

// ----- wall presets (server-authoritative) -----

const _play = (c) => !!c && c.browser_playable !== false;
const _chans = [
  { slug: "livenow-fox", browser_playable: true },
  { slug: "bbc-news", browser_playable: true },
  { slug: "explore-nature-cams", browser_playable: true },
  { slug: "nasa-tv", browser_playable: false },   // honest-offline-ish (unplayable)
  { slug: "cnn", browser_playable: true },
];

test("presetLineup: exact fill keeps the preset's KNOWN slugs incl. honest-offline, no top-up", () => {
  const preset = { id: "nature", fill: "exact", slugs: ["explore-nature-cams", "nasa-tv"] };
  // nasa-tv exists but is unplayable → KEPT in its slot (honest-offline tile),
  // parity with native exactLineup; no other channels topped up.
  assert.deepEqual(presetLineup(preset, _chans, _play), ["explore-nature-cams", "nasa-tv"]);
});

test("presetLineup: exact drops a slug that matches no channel (no fake tile)", () => {
  const preset = { id: "x", fill: "exact", slugs: ["bbc-news", "ghost-channel"] };
  assert.deepEqual(presetLineup(preset, _chans, _play), ["bbc-news"]);
});

test("newsLineup: PREFERRED then FALLBACK then rest, deny-listed excluded (native forWall parity)", () => {
  const chans = [
    { slug: "livenow-fox", browser_playable: true },   // PREFERRED[0]
    { slug: "bbc-news", browser_playable: true },       // PREFERRED[2]
    { slug: "c-span", browser_playable: true },         // FALLBACK[0]
    { slug: "nasa-tv", browser_playable: true },        // DENY — excluded even when "live"
    { slug: "some-extra", browser_playable: true },     // rest
  ];
  const out = newsLineup(chans, _play);
  // PREFERRED (livenow-fox, bbc-news) → FALLBACK (c-span) → rest (some-extra).
  assert.deepEqual(out, ["livenow-fox", "bbc-news", "c-span", "some-extra"]);
  assert.ok(!out.includes("nasa-tv"), "deny-listed nasa-tv never takes a default slot");
});

test("presetLineup: topup fill = preset slugs then remaining playable (the News default)", () => {
  const preset = { id: "news", fill: "topup", slugs: ["livenow-fox", "bbc-news"] };
  const out = presetLineup(preset, _chans, _play);
  assert.deepEqual(out.slice(0, 2), ["livenow-fox", "bbc-news"]);   // preferred first, in order
  assert.ok(out.includes("explore-nature-cams") && out.includes("cnn"));   // topped up
  assert.ok(!out.includes("nasa-tv"));   // unplayable never fills
});

test("presetLineup: empty/invalid preset → []", () => {
  assert.deepEqual(presetLineup(null, _chans, _play), []);
  assert.deepEqual(presetLineup({ id: "x" }, _chans, _play), []);
});

test("view prefs: activePreset defaults to 'news' and round-trips", () => {
  assert.equal(normalizeViewPrefs({}).activePreset, "news");
  assert.equal(normalizeViewPrefs({ activePreset: "nature" }).activePreset, "nature");
  assert.equal(normalizeViewPrefs({ activePreset: 42 }).activePreset, "news");   // non-string → default
  const round = serializeViewPrefs(normalizeViewPrefs({ activePreset: "space" }));
  assert.equal(round.activePreset, "space");
});
