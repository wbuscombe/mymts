// MyMTS LAN web client — mirrors the Onn wall: a scrolling ticker with
// ESPN-BottomLine-style league markers, an agnostic chronological feed
// (source next to each headline), and a cell-count video grid with
// click-to-pick channel selection. A dumb, read-only, credential-free
// consumer of the helper API; all honesty/label/group logic is in
// render.mjs (pure, tested). DOM text is written via textContent only
// (no helper string becomes markup); video is hls.js playback of the
// helper-resolved public streams (A1: playback, not a web reader).

import { api } from "./api.mjs";
import {
  tickerStaleNote, feedChronological, sourceLabel, relativeTime, channelStatus,
  helperUnreachable, filterHiddenSources, browserPlayability,
  groupTickerCards, tickerSchemaNote, newsTickerEntries,
  gridLayoutFromDims, clampGridDim,
  leaguePool, filterHiddenLeagues,
  FEED_RECENCY_OPTIONS, feedRecencyOption, filterFeedRecency,
  clampTickerSpeedPct, tickerScrollPxPerSec, crawlCycle, CRAWL_DWELL_MS,
  normalizeViewPrefs, serializeViewPrefs,
  feedDetailModel,
  classifyVideoFailure, videoRetryDecision,
  tickerFlipDwellMs,
  feedPctFromPointer, clampFeedPct,
  sectionChannels, sectionFeedSources, nextAudible, isAudible, shouldReassertAudio, feedSideOption,
  presetLineup, newsLineup, PRESETS_SCHEMA_VERSION,
} from "./render.mjs";
import { attachStream } from "./video.mjs";
import { normalizeConfig } from "./wallConfig.mjs";

const FEED_POLL_MS = 60_000, CHANNELS_POLL_MS = 60_000, TICKER_POLL_MS = 60_000;
// Server-side wall config (headless-container version): poll it so a change made
// in the picker (/control/) reflects in the rendered wall within a few seconds.
// After a LOCAL edit we PUT the config + skip re-hydration for a short grace
// window so a poll mid-PUT can't momentarily revert the operator's own change.
const WALL_POLL_MS = 5_000;
const WALL_EDIT_GRACE_MS = 4_000;
const MODE_ROTATE_MS = 18_000;       // ticker mode rotation, calm like the wall
const TICKER_BASE_PX_PER_SEC = 60;   // marquee scroll speed at 100% (calm base)

const everOk = { feed: false, channels: false };
const el = (id) => document.getElementById(id);

// ----- view prefs (browser-local; NOT the TV's settings) -----
// v4 adds the native CONTENT/LAYOUT parity knobs the web lacked: the grid is
// now independent ROWS × COLS (native parity, each 1–3) instead of a single
// cell-count; plus a sports-league denylist, a feed recency window, and a
// ticker scroll-speed percent. All are non-sensitive VIEW prefs (the TV keeps
// its own settings on-device; the web client can't write them). The panel-fit
// levers (Fit scale / Vertical stretch / Overscan / Position / Display size /
// Calibration) are deliberately NOT ported — they correct a physical TV's
// overscan/anchoring and are meaningless in a browser window.
const PREFS_KEY = "mymts.web.prefs.v4";
const prefs = loadPrefs();
// Normalize/clamp/migrate is a PURE function in render.mjs (normalizeViewPrefs)
// so the persistence round-trip is unit-tested against the REAL code path. Here
// we only do the IO (read/parse) + wrap the JSON-array denylists in Sets.
function loadPrefs() {
  let raw = {};
  try { raw = JSON.parse(localStorage.getItem(PREFS_KEY) || "{}"); }
  catch { raw = {}; }   // localStorage/JSON unavailable → all defaults below
  const n = normalizeViewPrefs(raw);
  return { ...n, hidden: new Set(n.hidden), hiddenLeagues: new Set(n.hiddenLeagues) };
}
function savePrefs() {
  try {
    // serializeViewPrefs re-normalizes + flattens the Sets to arrays (pure).
    localStorage.setItem(PREFS_KEY, JSON.stringify(serializeViewPrefs(prefs)));
  } catch { /* localStorage unavailable — view prefs just won't persist */ }
}
/** The current grid layout from the operator's rows × cols (native parity). */
function gridConfig() { return gridLayoutFromDims(prefs.gridRows, prefs.gridCols); }
function applyPrefs() {
  const layout = gridConfig();
  document.documentElement.style.setProperty("--grid-cols", String(layout.cols));
  document.documentElement.style.setProperty("--grid-rows", String(layout.rows));
  document.documentElement.style.setProperty("--feed-pct", prefs.feedPct + "%");
  document.documentElement.style.setProperty("--feed-font", String(prefs.feedFont));
  // Feed side (native Feed side): the wall is a flex row; "right" visually swaps
  // the feed pane to the right edge (CSS order) without moving it in the DOM.
  const wall = el("wall");
  if (wall) wall.classList.toggle("feed-right", prefs.feedSide === "right");
  // The side menu docks on the feed's edge, like native (the menu slides in from
  // the same side as the feed it acts on).
  const menu = el("menu-modal");
  if (menu) menu.classList.toggle("dock-right", prefs.feedSide === "right");
}

function node(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;   // text only — never markup
  return n;
}

// ----- ticker (scrolling marquee, bespoke per-sport cards) -----
// Each mode is { mode, env, news }: markets/sports carry a fetched envelope
// (with the schema-guard verdict on env._schema); the NEWS mode carries the
// latest feed items (news=true) and is gated behind a view pref (default OFF,
// matching the native tickerNewsEnabled default).
let tickerModes = [];
let modeIdx = 0;
let latestFeedItems = [];   // shared with the NEWS ticker mode
let latestSportsEntries = [];   // for the Sports-leagues filter pool (Settings)

async function pollTicker() {
  const modes = [];
  for (const [mode, fetcher] of [["MARKETS", api.tickerMarkets], ["SPORTS", api.tickerSports]]) {
    try {
      const env = await fetcher();
      modes.push({ mode, env });
      // Remember the SPORTS entries (schema-OK only) so Settings can offer the
      // real league pool; the toggle list comes from what the helper serves.
      if (mode === "SPORTS" && env && (env._schema?.ok ?? true)) {
        latestSportsEntries = env.entries ?? [];
        rebuildLeagueToggles();
      }
    } catch { modes.push({ mode, env: null }); }
  }
  if (prefs.tickerNews) modes.push({ mode: "NEWS", news: true });
  tickerModes = modes;
  if (modeIdx >= tickerModes.length) modeIdx = 0;
  renderTicker();
}

function rotateTicker() {
  if (tickerModes.length === 0) return;
  modeIdx = (modeIdx + 1) % tickerModes.length;
  renderTicker();
}

// ----- status chip (shared color-rule engine, native StatusBlock) -----
function statusChip(statusKind, text) {
  if (!text) return null;   // only render when status is non-blank
  return node("span", `t-status t-status--${statusKind}`, text);
}

function sampleChip() { return node("span", "t-sample", "SAMPLE"); }

// ----- per-kind card builders (render from STRUCTURED fields, not display) -----

function buildGameCard(m) {
  // Team game: matchup (abbrs + scores or "@") + status block + sample chip.
  const card = node("div", `t-card t-card--game t-game--${m.kind}`);
  const matchup = node("div", "t-matchup");
  if (!m.hasScores) {
    // pre / blank score → "AWAY @ HOME", never a phantom 0–0.
    matchup.appendChild(node("span", "t-team", m.away));
    matchup.appendChild(node("span", "t-at", "@"));
    matchup.appendChild(node("span", "t-team", m.home));
  } else {
    const away = node("span", `t-side${m.kind === "final" ? " t-side--final" : ""}`);
    away.appendChild(node("span", "t-team", m.away));
    away.appendChild(node("span", `t-score${m.awayLeads ? " t-score--lead" : ""}`, m.awayScore));
    const home = node("span", `t-side${m.kind === "final" ? " t-side--final" : ""}`);
    home.appendChild(node("span", "t-team", m.home));
    home.appendChild(node("span", `t-score${m.homeLeads ? " t-score--lead" : ""}`, m.homeScore));
    matchup.append(away, home);
  }
  card.appendChild(matchup);
  const chip = statusChip(m.kind, m.status);
  if (chip) card.appendChild(chip);
  if (m.sample) card.appendChild(sampleChip());
  return card;
}

function buildSportCard(m) {
  // Individual sport (leaderboard/fight/match/race/generic): title + lines +
  // status block + sample chip. Monospace lines for leaderboard/match (score
  // alignment); proportional for fight/race — matching the native registers.
  const mono = m.kind === "leaderboard" || m.kind === "match";
  const card = node("div", `t-card t-card--sport t-card--${m.kind}`);
  if (m.title) card.appendChild(node("div", "t-title", m.title));
  for (const line of m.lines) {
    card.appendChild(node("div", `t-line${mono ? " t-line--mono" : ""}`, line));
  }
  const chip = statusChip(m.statusKind, m.status);
  if (chip) card.appendChild(chip);
  if (m.sample) card.appendChild(sampleChip());
  return card;
}

function buildMarketCard(m) {
  // Markets/news fallback cell: value + direction arrow; inline dimmed
  // "sample" tag (NOT the boxed pill) when sample, matching native MarketCard.
  const c = node("div", `t-card t-card--cell${m.sample ? " t-card--sample" : ""}`);
  c.appendChild(node("span", "t-val", m.value));
  if (m.glyph) c.appendChild(node("span", `t-arrow ${m.dirClass}`, m.glyph));
  if (m.sample) c.appendChild(node("span", "t-sample-inline", "sample"));
  return c;
}

function buildNewsCard(m) {
  // News in the ticker: SOURCE label + headline, inert plain text (A1 — no
  // in-ticker reading). is_sample is honest: feed news is real, no SAMPLE.
  const c = node("div", "t-card t-card--news");
  c.appendChild(node("span", "t-news-source", String(m.source).toUpperCase()));
  c.appendChild(node("span", "t-news-headline", m.headline));
  if (m.sample) c.appendChild(sampleChip());
  return c;
}

function buildCard(m) {
  if (m.type === "game") return buildGameCard(m);
  if (m.type === "card") return buildSportCard(m);
  if (m.type === "news") return buildNewsCard(m);
  return buildMarketCard(m);   // "cell"
}

function buildTickerGroup(group) {
  // The league/market/NEWS marker shows ONCE (left curtain), then its cards
  // follow — each card is a discrete bordered cell (native: the card IS the
  // divider unit).
  const g = node("span", "t-group");
  g.appendChild(node("span", "t-league", group.label));
  for (const m of group.cards) g.appendChild(buildCard(m));
  return g;
}

// FLIP motion: a JS-driven dwell timer steps through the groups. CRAWL motion
// uses a Web-Animations marquee (startCrawl/stopCrawl below). Both are cleared on
// every genuine re-render so a mode rotation / poll can't leave a stale driver.
let flipTimer = null;
function clearFlip() { if (flipTimer) { clearInterval(flipTimer); flipTimer = null; } }

// The running CRAWL marquee (Web Animations API) + the signature of the content +
// speed it was built for. The crawl is re-created ONLY when this signature changes
// (a genuine content / speed / motion change) — NOT on every poll, so an unchanged
// refresh never restarts the scroll mid-pass (native: re-key on speed/pause/
// overflow/content, never on a routine refresh).
let crawlAnim = null;
let lastCrawlSig = null;

/** Stop + clear the crawl marquee: cancel the WAAPI animation and reset the
 *  transform so the strip sits at its origin. Idempotent. */
function stopCrawl() {
  if (crawlAnim) { try { crawlAnim.cancel(); } catch {} crawlAnim = null; }
  const track = el("ticker-track");
  if (track) track.style.transform = "translateX(0)";
  lastCrawlSig = null;
}

/** A signature of what the crawl is currently showing — the mode + the group
 *  CONTENT (display models, not raw envelopes, so a poll with identical visible
 *  data hashes the same) + the levers that change the motion timing (scroll speed,
 *  motion mode). Identical signature ⇒ nothing genuine changed ⇒ don't restart. */
function crawlSignature(cur, plan, p) {
  return JSON.stringify({
    mode: cur ? cur.mode : null,
    news: !!(cur && cur.news),
    motion: p.tickerMotion,
    scroll: clampTickerSpeedPct(p.tickerScrollPct),
    body: plan.kind === "cards" ? plan.groups : (plan.text || ""),
  });
}

/**
 * Build the ticker's render PLAN for the current mode WITHOUT touching the DOM, so
 * the crawl can decide whether anything genuinely changed before it rebuilds +
 * restarts. Mirrors the honest empty/error states (no data, schema drift, all
 * leagues hidden) and otherwise returns the grouped card models + the stale note.
 */
function tickerRenderPlan(cur) {
  if (!cur) return { kind: "empty" };
  if (cur.news) {
    // NEWS mode — consumes the already-fetched feed (no extra request).
    const groups = groupTickerCards(newsTickerEntries(latestFeedItems), { newsMode: true });
    if (groups.length === 0) return { kind: "empty", text: "no headlines yet", cls: "t-stale" };
    return { kind: "cards", groups, staleNote: "", stale: false };
  }
  if (!cur.env) return { kind: "empty", text: `${cur.mode.toLowerCase()}: unavailable`, cls: "t-stale" };
  // ARCH-1 schema guard: a contract we don't understand → DEGRADE HONESTLY (show
  // "client out of date", render NO cards). Never fabricate against an unknown shape.
  const schema = cur.env._schema ?? { ok: true };
  if (!schema.ok) {
    return { kind: "empty", text: "client out of date — cards hidden", cls: "t-schema-warn", note: tickerSchemaNote(cur.env) };
  }
  const staleNote = tickerStaleNote(cur.env);
  const stale = cur.env.stale === true;
  // Sports-league filter (client-side, identical to native filterLeagues): a
  // denylist applied ONLY to the SPORTS mode — markets entries are never touched.
  let entries = cur.env.entries ?? [];
  if (cur.mode === "SPORTS" && prefs.hiddenLeagues.size > 0) {
    entries = filterHiddenLeagues(entries, prefs.hiddenLeagues);
    if (entries.length === 0) {
      // The operator hid every league currently on → honest empty state.
      return { kind: "empty", text: "all leagues hidden (check Settings)", cls: "t-stale", note: staleNote };
    }
  }
  return { kind: "cards", groups: groupTickerCards(entries), staleNote, stale };
}

function renderTicker() {
  const track = el("ticker-track");
  const cur = tickerModes[modeIdx];
  el("ticker-mode").textContent = cur ? cur.mode : "";

  // Compute the render PLAN first (no DOM mutation), so the crawl can tell whether
  // anything genuinely changed before it rebuilds + restarts the scroll.
  const plan = tickerRenderPlan(cur);
  const sig = crawlSignature(cur, plan, prefs);

  // CRAWL: a routine poll whose content + speed are unchanged must NOT rebuild or
  // restart the marquee mid-pass (the "jumps back on every refresh" bug). Leave the
  // running animation alone; just refresh the out-of-track stale note/chip (cheap,
  // no reflow). Speed/motion are in the signature, so a settings change still re-keys.
  if (prefs.tickerMotion === "crawl" && plan.kind === "cards" &&
      sig === lastCrawlSig && crawlAnim && crawlAnim.playState === "running") {
    el("ticker-note").textContent = plan.staleNote || "";
    setStale(!!plan.stale);
    return;
  }

  // Genuine change (content / speed / motion / mode) → rebuild from scratch.
  clearFlip();
  stopCrawl();
  track.classList.remove("flip");
  track.replaceChildren();
  el("ticker-note").textContent = "";
  setStale(false);

  if (plan.kind === "empty") {
    if (plan.text) track.appendChild(node("span", plan.cls || "t-stale", plan.text));
    if (plan.note) el("ticker-note").textContent = plan.note;
    return;
  }

  // The stale note lives in a FIXED slot OUTSIDE the scrolling track — riding inside
  // it would clone the note into both halves; keeping it out leaves the track as two
  // identical halves so the loop stays seamless.
  el("ticker-note").textContent = plan.staleNote || "";
  setStale(!!plan.stale);

  const built = plan.groups.map(buildTickerGroup);

  // FLIP motion (native-parity, opt-in): one group at a time on a calm dwell.
  if (prefs.tickerMotion === "flip") {
    renderTickerFlip(track, built);
    return;
  }

  // CRAWL motion (web default): lay ONE copy, then let startCrawl measure + (only on
  // overflow) add the second copy and run the WAAPI marquee (scroll one period + dwell).
  built.forEach((b) => track.appendChild(b));
  startCrawl(track, built, sig);
}

/**
 * CRAWL marquee via the Web Animations API — native TickerStrip parity. Lays a
 * second copy ONLY when one copy overflows the strip (else it holds static), then
 * scrolls exactly one PERIOD (one copy's width + the inter-copy gap, measured as the
 * first clone's offset → the wrap lands copy 2 on copy 1's origin: no seam pop) at
 * the operator's px/sec, LINEAR, then HOLDS at the loop point for CRAWL_DWELL_MS,
 * repeating. The duration IS distance/speed, so the velocity stays constant under
 * load (the compositor drops frames, never the rate) — sub-pixel, main-thread-
 * independent. Re-created only by renderTicker on a genuine change.
 */
function startCrawl(track, built, sig) {
  // Measure after layout (rAF) so widths are real.
  requestAnimationFrame(() => {
    track.style.transform = "translateX(0)";
    const oneCopy = track.scrollWidth;
    const viewport = (track.parentElement && track.parentElement.clientWidth) || 0;
    // OVERFLOW gate (native): only crawl when one copy actually exceeds the strip;
    // otherwise the single copy just sits (no clone, no animation).
    if (!(oneCopy > viewport)) { lastCrawlSig = sig; return; }
    // The second copy makes the loop seamless; append it now that we know it scrolls.
    const clones = built.map((b) => b.cloneNode(true));
    clones.forEach((c) => track.appendChild(c));
    // period = first clone's offset = one copy's width + the inter-copy gap (the
    // seamless repeat period — wrapping here lands copy 2 exactly on copy 1's origin).
    const period = clones[0].offsetLeft;
    const pxPerSec = tickerScrollPxPerSec(TICKER_BASE_PX_PER_SEC, prefs.tickerScrollPct);
    const { totalMs, scrollFraction } = crawlCycle(period, pxPerSec, CRAWL_DWELL_MS);
    if (!(totalMs > 0)) { lastCrawlSig = sig; return; }
    if (crawlAnim) { try { crawlAnim.cancel(); } catch {} }
    crawlAnim = track.animate(
      [
        { transform: "translateX(0px)", offset: 0 },
        { transform: `translateX(${-period}px)`, offset: scrollFraction },
        { transform: `translateX(${-period}px)`, offset: 1 },   // dwell hold (slip time)
      ],
      { duration: totalMs, iterations: Infinity, easing: "linear" },
    );
    lastCrawlSig = sig;
  });
}

/** FLIP motion: render the groups one at a time, vertically flipping to the
 *  next on a calm dwell (mirrors the native paged flip). The dwell scales with
 *  the same ticker-speed pref as the crawl. A single group just holds. */
function renderTickerFlip(track, built) {
  track.style.animation = "none";
  track.classList.add("flip");
  if (built.length === 0) return;
  let i = 0;
  const show = (idx) => {
    const g = built[idx];
    track.replaceChildren(g);
    // Force the flip-in keyframe to restart on each swap (re-inserting an
    // element doesn't always replay a stylesheet animation across browsers).
    g.style.animation = "none";
    void g.offsetWidth;          // reflow
    g.style.animation = "";
  };
  show(0);
  if (built.length > 1) {
    flipTimer = setInterval(() => { i = (i + 1) % built.length; show(i); }, tickerFlipDwellMs(prefs.tickerFlipPct));
  }
}

/** Toggle the envelope-level STALE chip pinned to the strip's right edge. */
function setStale(on) {
  const chip = el("ticker-stale");
  if (chip) chip.classList.toggle("hidden", !on);
}

// ----- feed (agnostic chronological list, source per headline) -----
async function pollFeed() {
  try {
    const snap = await api.feed(120); everOk.feed = true;
    latestFeedItems = snap.items ?? [];   // feeds the NEWS ticker mode too
    renderFeed(latestFeedItems);
    rebuildSourceToggles(latestFeedItems);
    // If the NEWS ticker mode is showing, refresh it with the new headlines.
    if (tickerModes[modeIdx] && tickerModes[modeIdx].news) renderTicker();
  } catch {
    if (helperUnreachable(false, everOk.feed)) el("feed").replaceChildren(node("p", "empty", "Helper unreachable — feed paused."));
  }
}
function renderFeed(rawItems) {
  // Source denylist → sports-league denylist (a feed item whose source IS a
  // league is gated by the SAME pool as the ticker scores, so hiding a league
  // hides its news too, consistently with native) → recency window → newest-
  // first. All are honest filters: they only DROP items, never fabricate.
  const sourceFiltered = filterHiddenSources(rawItems, prefs.hidden);
  const leagueFiltered = filterHiddenSources(sourceFiltered, prefs.hiddenLeagues);
  const recency = feedRecencyOption(prefs.feedRecency);
  const recent = filterFeedRecency(leagueFiltered, recency.maxAgeMs);
  const items = feedChronological(recent);
  const root = el("feed"); root.replaceChildren();
  el("feed-status").textContent = `${items.length} items`;
  if (items.length === 0) {
    root.appendChild(node("p", "empty", rawItems.length ? "No items match the current filters (check Settings)." : "Waiting for the feed…"));
    return;
  }
  // One agnostic river, newest-first across all sources — the source sits
  // next to each headline (the original Onn-box style), not in a section.
  for (const item of items) {
    const row = node("article", "feed-row");
    // Selectable: focusable + click / Enter / Space → expand the story.
    row.tabIndex = 0;
    row.setAttribute("role", "button");
    row.setAttribute("aria-label", `Open story: ${item.title || "untitled"}`);
    const meta = node("div", "feed-meta");
    meta.appendChild(node("span", "feed-source", sourceLabel(item)));
    const t = relativeTime(item.published_at || item.fetched_at);
    if (t) meta.appendChild(node("span", "feed-time", t));
    row.appendChild(meta);
    row.appendChild(node("h3", "feed-title", item.title || ""));
    if (item.summary && item.summary.trim()) row.appendChild(node("p", "feed-summary", item.summary));
    row.addEventListener("click", () => openStory(item));
    row.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") { e.preventDefault(); openStory(item); }
    });
    root.appendChild(row);
  }
}

// ----- news-story detail (expand a focused headline) -----
let lastStoryTrigger = null;   // restore focus here on close (keyboard a11y)

/** Expand a feed story into the detail modal — the item's OWN fields only,
 *  written via textContent (inert; no innerHTML of feed text), with an
 *  http(s)-gated link-OUT to the source (never an in-app fetch — A1). */
function openStory(item) {
  const m = feedDetailModel(item);
  el("story-source").textContent = m.source;
  el("story-time").textContent = relativeTime(m.timestamp) || "";
  el("story-title").textContent = m.title || "(untitled)";
  el("story-summary").textContent = m.summary;
  const link = el("story-link");
  if (m.hasLink) {
    link.href = m.link;            // safeHttpLink-gated: only http(s)
    link.classList.remove("hidden");
  } else {
    link.removeAttribute("href");
    link.classList.add("hidden");
  }
  lastStoryTrigger = document.activeElement;
  el("story-modal").classList.remove("hidden");
  el("story-close").focus();
}

function closeStory() {
  closeModal("story-modal");
  if (lastStoryTrigger && typeof lastStoryTrigger.focus === "function") lastStoryTrigger.focus();
  lastStoryTrigger = null;
}

// ----- video grid (cell-count, click-to-pick channels) -----
let channelsBySlug = new Map();
let channelList = [];
let cells = [];          // per-index { key, teardown, setState }
let autoFilled = false;

// Server-authoritative wall presets (GET /api/presets). The helper defines them;
// the selector renders whatever is served (a new preset appears with no rebuild).
let presets = [];
const presetById = (id) => presets.find((p) => p.id === id);
const isPlayable = (c) => c && (browserPlayability(c) === "yes" || browserPlayability(c) === "maybe");
// Single-audible-tile model (native LineupStore.audibleSlot): at most ONE tile
// is unmuted. SESSION-ONLY — never persisted: the autoplay rule blocks
// autoplay-with-sound, so audio is an explicit per-session choice, not a saved
// default that would try (and fail) to unmute on load. -1 = the wall is muted.
// `audibleSlug` records WHICH channel the operator chose audio for, so a slot
// whose channel was replaced/cleared never inherits the prior audio selection
// (the audio is bound to slot+channel, not the slot index alone).
let audibleIndex = -1;
let audibleSlug = null;

// ----- server-side wall config (the rendered wall reads it; the picker writes
// it) -----. When a config is STORED (the operator has customised it via
// /control/ or here), it is AUTHORITATIVE for the grid: layout + per-cell
// channel + the single audible cell + per-cell subtitles. When it is the
// un-customised server default (stored=false), /app/ keeps its own standalone
// behaviour (autofill from the news lineup) so a fresh wall is unchanged.
let wallStored = false;
let cellSubtitles = {};          // { cellIndex: true } — per-cell subtitle state (native captionsOnSlots parity)
let lastWallEditAt = 0;          // timestamp of the last LOCAL edit (the re-hydration grace window)

/** Whether captions/subtitles are ON for a given cell. Per-cell when a config
 *  is stored (native per-slot model); else the wall-wide settings toggle. */
function cellCaptionsOn(index) {
  return wallStored ? (cellSubtitles[index] === true) : (prefs.captions === true);
}

/** Build the server wall config from /app/'s CURRENT state (assignments + grid +
 *  the audible cell + per-cell subtitles) — the shape PUT to /api/wall. */
function buildWallConfig() {
  const layout = gridConfig();
  const cells = [];
  for (let i = 0; i < layout.count; i++) {
    cells.push({ channel: prefs.assignments[i] || null, subtitles: cellSubtitles[i] === true });
  }
  const audible = (audibleIndex >= 0 && audibleIndex < layout.count && cells[audibleIndex].channel)
    ? audibleIndex : null;
  return {
    schema_version: 1,
    layout: { rows: layout.rows, cols: layout.cols },
    preset: prefs.activePreset || null,
    audible_cell: audible,
    cells,
  };
}

/** Persist /app/'s current wall state to the server config (so an edit here
 *  drives the wall + survives reload, and /control/ sees it). Fire-and-forget:
 *  a failure keeps the local state; the next poll reconciles. */
async function pushWallConfig() {
  lastWallEditAt = Date.now();
  try {
    const saved = await api.putWall(buildWallConfig());
    wallStored = saved.stored === true;
  } catch { /* helper blip — local state stands; next poll reconciles */ }
}

/** Apply the single-audible + per-cell-subtitle state to the LIVE tiles. Needed
 *  after a config hydrate because renderGrid only re-renders CHANGED cells, so an
 *  audio-only / subtitle-only change (same channel) wouldn't otherwise reach the
 *  running stream handle. */
function applyWallAudioCaptions() {
  for (const c of cells) {
    if (!c.isVideo || !c.streamHandle) continue;
    try { c.streamHandle.setAudible(isAudible(audibleIndex, c.index)); } catch { /* ignore */ }
    try { c.streamHandle.setCaptions(cellCaptionsOn(c.index)); } catch { /* no track */ }
    updateTileAudioBadge(c);
  }
}

/** Hydrate /app/'s render state FROM a stored wall config, then re-render. The
 *  config is authoritative: grid dims, per-cell channel, the audible cell, and
 *  per-cell subtitles all come from it. */
function hydrateFromWall(config) {
  const rows = config.layout.rows, cols = config.layout.cols;
  const dimsChanged = rows !== prefs.gridRows || cols !== prefs.gridCols;
  prefs.gridRows = rows; prefs.gridCols = cols;
  const assignments = {};
  cellSubtitles = {};
  config.cells.forEach((c, i) => {
    if (c.channel) assignments[i] = c.channel;
    if (c.subtitles) cellSubtitles[i] = true;
  });
  prefs.assignments = assignments;
  if (config.preset) prefs.activePreset = config.preset;
  audibleIndex = Number.isInteger(config.audible_cell) ? config.audible_cell : -1;
  audibleSlug = audibleIndex >= 0 ? (assignments[audibleIndex] || null) : null;
  autoFilled = true;   // a stored config supersedes the news autofill
  if (dimsChanged) buildGrid(); else renderGrid();
  // buildGrid() resets the audio pointer; re-apply from the hydrated config.
  if (dimsChanged) { audibleIndex = Number.isInteger(config.audible_cell) ? config.audible_cell : -1; audibleSlug = audibleIndex >= 0 ? (assignments[audibleIndex] || null) : null; }
  applyWallAudioCaptions();
}

/** Poll the server wall config. When STORED + outside the post-edit grace
 *  window, hydrate from it so a /control/ change appears here. When it's the
 *  un-customised default, leave /app/ standalone. */
async function pollWall() {
  try {
    const wall = await api.wall();
    wallStored = wall.stored === true;
    if (!wallStored) return;                                   // standalone default — keep local behaviour
    if (Date.now() - lastWallEditAt < WALL_EDIT_GRACE_MS) return;   // a local edit is settling
    hydrateFromWall(normalizeConfig(wall));
  } catch { /* helper unreachable — keep the last good wall */ }
}

async function pollChannels() {
  try {
    const snap = await api.channels(); everOk.channels = true;
    channelList = snap.channels ?? [];
    channelsBySlug = new Map(channelList.map((c) => [c.slug, c]));
    autoFillDefaults();
    renderGrid();
    if (!el("picker-modal").classList.contains("hidden")) renderPicker();
  } catch { /* keep last grid; next ok poll refreshes */ }
}

/** First time we learn the channel set, fill empty cells with the first
 *  browser-playable channels so the grid isn't blank on first load. */
function autoFillDefaults() {
  if (autoFilled || Object.keys(prefs.assignments).length > 0) { autoFilled = true; return; }
  // The active preset's set (only when it's a NON-default preset that's loaded);
  // otherwise the curated News default — UNCHANGED, so a fresh wall (or `news`) is
  // identical to today. (News mirrors native PREFERRED; the lineup expansion never
  // changes which channels a fresh wall opens with.)
  const preset = prefs.activePreset !== "news" ? presetById(prefs.activePreset) : null;
  // News (default) uses the SAME forWall-equivalent order as the native TV
  // (PREFERRED → FALLBACK → rest, deny-listed slugs excluded) — shared so the
  // fresh-wall autofill and the News-preset apply can't drift from each other or
  // from the TV. A non-default preset fills with its own slugs.
  const fill = preset ? presetLineup(preset, channelList, isPlayable)
                      : newsLineup(channelList, isPlayable);
  const layout = gridConfig();
  for (let i = 0; i < layout.count && i < fill.length; i++) prefs.assignments[i] = fill[i];
  autoFilled = true;
  savePrefs();
}

/** Apply a wall preset: replace the tiles with the preset's channels + its grid,
 *  persist, rebuild. `news` keeps today's behavior (topup default). */
function applyPreset(presetId) {
  const preset = presetById(presetId);
  if (!preset) return;
  prefs.activePreset = preset.id;
  if (preset.grid && typeof preset.grid.rows === "number" && typeof preset.grid.cols === "number") {
    prefs.gridRows = clampGridDim(preset.grid.rows);
    prefs.gridCols = clampGridDim(preset.grid.cols);
  }
  // news → the forWall-equivalent order (parity with native + the autofill path);
  // any other preset → its own slugs.
  const fill = preset.id === "news" ? newsLineup(channelList, isPlayable)
                                    : presetLineup(preset, channelList, isPlayable);
  prefs.assignments = {};
  cellSubtitles = {};   // a fresh preset resets per-cell subtitles (parity with /control/'s withPreset)
  const layout = gridConfig();
  for (let i = 0; i < layout.count && i < fill.length; i++) prefs.assignments[i] = fill[i];
  savePrefs();
  buildGrid();
  pushWallConfig();   // a preset application is a wall-config change — persist it
}

/** Populate the menu's preset <select> from the served presets + the active one. */
function populatePresetSelect() {
  const sel = el("menu-preset");
  if (!sel || presets.length === 0) return;
  sel.replaceChildren(...presets.map((p) => {
    const o = document.createElement("option");
    o.value = p.id; o.textContent = p.name;
    return o;
  }));
  sel.value = presetById(prefs.activePreset) ? prefs.activePreset : "news";
}

async function pollPresets() {
  try {
    const snap = await api.presets();
    // Schema guard (ARCH-1): a contract we don't grok → ignore it rather than
    // render misread fields. Selector stays on its last good set; wall unaffected.
    if (snap?.schema_version !== PRESETS_SCHEMA_VERSION) return;
    presets = Array.isArray(snap.presets) ? snap.presets : [];
    populatePresetSelect();
  } catch { /* presets unavailable → selector stays empty; the wall is unaffected */ }
}

/** Cancel a cell's pending auto-reconnect timer (idempotent). Called before
 *  any teardown/re-render so a backoff timer can never fire against a cell
 *  that's been replaced — otherwise a torn-down tile could resurrect itself. */
function clearCellRetry(cell) {
  if (cell && cell.retryTimer) { clearTimeout(cell.retryTimer); cell.retryTimer = null; }
}

function teardownCells() {
  for (const c of cells) { clearCellRetry(c); try { c.teardown && c.teardown(); } catch {} }
  cells = [];
}

/** Rebuild the grid layout (cell count changed) from scratch. */
function buildGrid() {
  teardownCells();
  audibleIndex = -1; audibleSlug = null;   // fresh cells → no stale audible pointer; the wall starts muted
  if (slotModalIndex != null) closeSlotControls();   // the grid may shrink past the open slot
  applyPrefs();
  const grid = el("grid"); grid.replaceChildren();
  const layout = gridConfig();
  for (let i = 0; i < layout.count; i++) {
    const tile = node("div", "tile");
    grid.appendChild(tile);
    cells.push({ index: i, el: tile, key: null, teardown: null });
  }
  renderGrid();
}

/** Reconcile each cell with its assigned channel WITHOUT tearing down a
 *  cell whose content is unchanged (so a playing video isn't interrupted
 *  on every poll). */
function renderGrid() {
  const layout = gridConfig();
  if (cells.length !== layout.count) { buildGrid(); return; }
  for (const cell of cells) {
    const slug = prefs.assignments[cell.index] || null;
    const ch = slug ? channelsBySlug.get(slug) : null;
    const bp = ch ? browserPlayability(ch) : "none";
    const key = `${slug || "∅"}|${bp}|${ch ? channelStatus(ch).label : "∅"}`;
    if (key === cell.key) continue;   // unchanged — leave the cell (and its video) alone
    clearCellRetry(cell);
    if (cell.teardown) { try { cell.teardown(); } catch {} cell.teardown = null; }
    cell.key = key;
    cell.videoAttempt = 0;            // new channel/state → fresh retry budget
    renderCell(cell, slug, ch, bp);
  }
}

function changeChip(cell) {
  const chip = node("span", "tile-change", "click to change");
  cell.el.appendChild(chip);
}

// ----- per-tile audio indicator (the slot controls live in a modal — see
// openSlotControls, the web analog of native's SlotControlsOverlay) -----

/** Show/hide a small 🔊 badge on the tile reflecting its REALIZED audible state
 *  (ground truth from the element), so the operator can see at a glance which
 *  tile owns audio without opening its controls. */
function updateTileAudioBadge(cell) {
  if (!cell || !cell.el) return;
  let aud = false;
  try { aud = !!cell.streamHandle && cell.streamHandle.audible() === true; } catch { aud = false; }
  let badge = cell.audioBadge && cell.audioBadge.isConnected ? cell.audioBadge : null;
  if (aud) {
    if (!badge) {
      badge = node("span", "tile-audio", "🔊");
      badge.title = "Audio on (this tile) — others muted";
      cell.el.appendChild(badge);
      cell.audioBadge = badge;
    }
  } else if (badge) {
    badge.remove();
    cell.audioBadge = null;
  }
  // If this slot's controls modal is open, keep its Audio row in sync.
  if (slotModalIndex === cell.index) refreshSlotControls();
}

/** If this cell's slot-controls modal is open, re-render it — so a tile that goes
 *  dead / reconnecting / empty / TV-only while its modal is open drops the stale
 *  Audio row + header instead of letting a tap flip audio onto a silent slot. */
function syncSlotModal(cell) {
  if (cell && slotModalIndex === cell.index) refreshSlotControls();
}

/** Drop the audio pointer if this cell owns it but is becoming a non-playing
 *  tile (empty / TV-only / offline / terminally dead) — so a silent slot never
 *  claims to own audio. NOT called for a transient reconnect (audio returns when
 *  the SAME channel recovers); the live re-apply's slug guard is the backstop. */
function clearAudioIfOwner(cell) {
  if (audibleIndex === cell.index) { audibleIndex = -1; audibleSlug = null; }
}

/** Move audio to THIS tile (single-audible-tile model): unmute it, mute every
 *  other tile. Toggling the already-audible tile mutes the whole wall. The
 *  invoking click is the user gesture the autoplay rule requires to unmute. */
function setCellAudio(cell) {
  audibleIndex = nextAudible(audibleIndex, cell.index);
  // Bind the audio choice to the chosen slot's CURRENT channel (null when toggled
  // off) so a later channel change/clear can't auto-inherit it on reconnect.
  audibleSlug = audibleIndex === cell.index ? (prefs.assignments[cell.index] || null) : null;
  for (const c of cells) {
    if (!c.isVideo || !c.streamHandle) continue;
    try { c.streamHandle.setAudible(isAudible(audibleIndex, c.index)); } catch { /* ignore */ }
    updateTileAudioBadge(c);
  }
  pushWallConfig();   // the audible cell is wall-config state — persist it
}

function renderCell(cell, slug, ch, bp) {
  const tile = cell.el;
  tile.replaceChildren();
  cell.isVideo = false;                 // only the playback branch sets this true
  cell.streamHandle = null;             // per-tile stream handle (set in the playback branch)
  cell.audioBadge = null;
  // Clicking a populated tile opens its SLOT CONTROLS (the native SlotControls
  // analog); an empty cell opens the picker directly (one obvious action).
  tile.onclick = () => openSlotControls(cell.index);

  if (!slug || !ch) {
    // Empty cell — obvious affordance to pick a channel (straight to the picker).
    clearAudioIfOwner(cell);   // a cleared slot no longer owns audio
    tile.onclick = () => openPicker(cell.index);
    const s = node("div", "tile-state");
    s.appendChild(node("div", "big", "＋"));
    s.appendChild(node("div", "head", "Add channel"));
    s.appendChild(node("div", "sub", "Click to choose a channel for this cell"));
    tile.appendChild(s);
    syncSlotModal(cell);
    return;
  }

  const label = ch.label || ch.slug;
  if (bp === "no") {
    clearAudioIfOwner(cell);   // TV-only / offline → not a browser audio source
    const live = channelStatus(ch).playable;
    const s = node("div", `tile-state${live ? " tvonly" : ""}`);
    s.appendChild(node("div", "big", live ? "📺" : "○"));
    s.appendChild(node("div", "head", label));
    // Honest C3: live-but-not-browser-playable → on the TV wall; otherwise offline.
    s.appendChild(node("div", "sub", live
      ? "Not playable in browser — on the TV wall"
      : (channelStatus(ch).label === "unknown" ? "Status unknown — checking…" : "Offline right now")));
    tile.appendChild(s);
    const dot = node("span", `tile-dot ${live ? "dot-tvonly" : "dot-offline"}`);
    tile.appendChild(dot);
    changeChip(cell);
    syncSlotModal(cell);
    return;
  }

  // bp is "yes" or "maybe" → attempt playback. The runtime result is the
  // ground truth: if it can't load (dead/CORS/mixed), we either reconnect (a
  // transient blip) or flip to the honest "on the TV wall" state — never a
  // black box shown as live, and never an infinite retry on a hopeless stream.
  cell.isVideo = true;
  // Per-attach generation token: late events from a torn-down/superseded
  // handle (e.g. one that fires during the backoff window) are ignored, so a
  // stray "live" can't cancel a pending reconnect or mutate a detached tile.
  cell.gen = (cell.gen || 0) + 1;
  const myGen = cell.gen;

  const video = document.createElement("video");
  video.muted = true; video.playsInline = true; video.autoplay = true;
  // Yellow "connecting" during the manifest-fetch/buffer window — honest, not
  // grey "offline" (which is reserved for known-not-playable). Flips on event.
  const dot = node("span", "tile-dot dot-unknown");
  const lab = node("span", "tile-label", label);
  tile.append(video, dot, lab);
  // Clicking the tile opens its SLOT CONTROLS modal (Change · Audio · Reconnect) —
  // the web analog of native's SlotControlsOverlay. No persistent chrome over video.
  tile.onclick = () => openSlotControls(cell.index);

  let playOverlay = null;
  const clearOverlay = () => { if (playOverlay) { playOverlay.remove(); playOverlay = null; } };

  const handle = attachStream(video, ch.current_url, (state, detail) => {
    if (myGen !== cell.gen) return;   // stale handle from a prior attach — ignore
    if (state === "live") {
      cell.videoAttempt = 0;          // a clean (re)connect refills the retry budget
      clearCellRetry(cell);
      dot.className = "tile-dot dot-live"; video.style.visibility = ""; clearOverlay();
      // Audio re-asserts ONLY for the same slot+channel it was chosen for — so a
      // transient reconnect keeps audio, but a slot whose channel was replaced
      // never inherits it (no "audio teleport"). Clear a now-stale pointer.
      const keepAudio = shouldReassertAudio(audibleIndex, audibleSlug, cell.index, slug);
      if (!keepAudio && audibleIndex === cell.index) { audibleIndex = -1; audibleSlug = null; }
      try { handle.setAudible(keepAudio); } catch { /* ignore */ }
      try { handle.setCaptions(cellCaptionsOn(cell.index)); } catch { /* no track */ }
      updateTileAudioBadge(cell);
      tile.onclick = () => openSlotControls(cell.index);
    } else if (state === "needgesture") {
      // Autoplay blocked — show a click-to-play affordance; click plays.
      dot.className = "tile-dot dot-unknown";
      if (!playOverlay) {
        playOverlay = node("div", "tile-play");
        playOverlay.appendChild(node("span", "glyph", "▶"));
        tile.appendChild(playOverlay);
      }
      tile.onclick = (e) => { e.stopPropagation(); handle.play(); };
      syncSlotModal(cell);   // a tile awaiting a play-gesture has no audio to toggle yet
    } else { // "stall" or "error" — a runtime failure. Classify, then decide.
      // THE CRUX: classifyVideoFailure separates a transient failure (CDN
      // blip / decode hiccup / freeze → worth a fresh attempt) from a
      // genuinely-unplayable one (DRM / codec / no-HLS / unsupported source →
      // a browser can NEVER play it, so retrying is pointless). videoRetry
      // Decision then caps the transient retries so a flaky stream that never
      // recovers still gives up to an honest state instead of looping forever.
      const cls = classifyVideoFailure(detail && detail.kind, detail && detail.details);
      const decision = videoRetryDecision(cell.videoAttempt, cls);
      cell.gen++;                       // neutralize any further events from THIS (failed) handle
      if (decision.retry) {
        // Keep cell.teardown pointing at the now-neutralized handle so a grid
        // rebuild DURING the backoff still tears it down (no leak); otherwise
        // reattachCell tears it down when the timer fires, then re-renders.
        showReconnecting(cell, label, cell.videoAttempt);
        cell.retryTimer = setTimeout(() => {
          cell.retryTimer = null;
          cell.videoAttempt += 1;
          reattachCell(cell);
        }, decision.delayMs);
      } else {
        // Genuinely unplayable, OR transient retries exhausted → STOP. Tear the
        // dead handle down off the event path; the tile then rests (honest)
        // until a manual ↻ or a channel-status change — never an infinite retry.
        const dead = handle.teardown; cell.teardown = null; cell.streamHandle = null;
        setTimeout(() => { try { dead(); } catch {} }, 0);
        showDeadVideo(cell, label, decision.reason);
      }
    }
  });
  cell.teardown = handle.teardown;
  cell.streamHandle = handle;   // exposes setAudible/audible() to the slot controls + audio badge
}

/** Honest "Reconnecting…" state while a transient failure backs off. Never
 *  shown as live; the dot stays the neutral "checking" colour. */
function showReconnecting(cell, label, attempt) {
  const tile = cell.el;
  tile.replaceChildren();
  cell.streamHandle = null; cell.audioBadge = null;
  const s = node("div", "tile-state reconnecting");
  s.appendChild(node("div", "big", "↻"));
  s.appendChild(node("div", "head", label));
  s.appendChild(node("div", "sub", `Reconnecting… (attempt ${attempt + 1})`));
  tile.appendChild(s);
  tile.appendChild(node("span", "tile-dot dot-unknown"));
  tile.onclick = () => openSlotControls(cell.index);
  syncSlotModal(cell);
}

/** Honest terminal state for a stream the browser couldn't play. Carries a
 *  per-tile ↻ so the operator can force a fresh attempt (the helper may have
 *  re-resolved the URL) without reloading the whole wall. */
function showDeadVideo(cell, label, reason) {
  const tile = cell.el;
  tile.replaceChildren();
  cell.streamHandle = null; cell.audioBadge = null;
  clearAudioIfOwner(cell);   // a tile that gave up is silent → it no longer owns audio
  const s = node("div", "tile-state");
  s.appendChild(node("div", "big", "○"));
  s.appendChild(node("div", "head", label));
  s.appendChild(node("div", "sub", deadReasonCopy(reason)));
  // A large, centered ↻ directly UNDER the status text — an obvious "tap to
  // retry" affordance. It lives INSIDE the centered .tile-state column (not the
  // old bottom-right corner), so the stack reads ○ → name → status → ↻. This
  // treatment is only on the no-video dead tiles, so it never covers playback.
  const refresh = node("button", "tile-refresh", "↻");
  refresh.title = "Reconnect this tile";
  refresh.setAttribute("aria-label", "Reconnect this tile");
  refresh.onclick = (e) => { e.stopPropagation(); refreshCell(cell); };
  s.appendChild(refresh);
  tile.appendChild(s);
  tile.appendChild(node("span", "tile-dot dot-offline"));
  tile.onclick = () => openSlotControls(cell.index);
  syncSlotModal(cell);
}

/** Honest, reason-specific copy. A browser-fundamental limitation (DRM / codec
 *  / no-HLS) is stated plainly as "on the TV wall" (the helper confirmed the
 *  stream HTTPS-clean for bp yes/maybe; ExoPlayer plays what the browser
 *  can't). A retries-exhausted transient hedges with "may be". */
function deadReasonCopy(reason) {
  switch (reason) {
    case "no-browser-hls":     return "Browser can't play HLS — on the TV wall";
    case "drm":                return "Protected stream (DRM) — on the TV wall";
    case "codec":              return "Codec not supported in browser — on the TV wall";
    case "remux":              return "Can't be repackaged for the browser — on the TV wall";
    case "unsupported-source": return "Browser can't play this source — on the TV wall";
    case "exhausted":          return "Couldn't reconnect — may be on the TV wall";
    default:                   return "Couldn't play in browser — may be on the TV wall";
  }
}

/** Manual per-tile reconnect: reset the backoff budget and re-attach now. */
function refreshCell(cell) {
  if (!cell) return;
  cell.videoAttempt = 0;
  reattachCell(cell);
}

/** Whole-wall reconnect (header ↻): every video tile gets a fresh attempt —
 *  recovers a silently-degraded tile too, not just the visibly-dead ones. */
function refreshAllVideo() {
  for (const cell of cells) { if (cell.isVideo) refreshCell(cell); }
}

/** Tear down a cell's current stream and re-render it from the live channel
 *  snapshot (the helper may have re-resolved a fresh URL since it failed). */
function reattachCell(cell) {
  clearCellRetry(cell);
  if (cell.teardown) { try { cell.teardown(); } catch {} cell.teardown = null; }
  const slug = prefs.assignments[cell.index] || null;
  const ch = slug ? channelsBySlug.get(slug) : null;
  const bp = ch ? browserPlayability(ch) : "none";
  renderCell(cell, slug, ch, bp);
}

// ----- channel picker (intuitive, mouse-driven) -----
let pickerCell = null;

function pickerMeta(ch) {
  const { label, playable } = channelStatus(ch);
  const bp = browserPlayability(ch);
  if (bp === "yes") return { dot: "dot-live", text: "live · plays in browser" };
  // "maybe" = helper-live but unclassified → yellow, distinct from confirmed "yes".
  if (bp === "maybe") return { dot: "dot-unknown", text: "live · will try in browser" };
  if (playable) return { dot: "dot-tvonly", text: "live · on the TV wall only" };
  if (label === "unknown") return { dot: "dot-unknown", text: "checking…" };
  return { dot: "dot-offline", text: "offline" };
}

function openPicker(cellIndex) {
  pickerCell = cellIndex;
  el("picker-title").textContent = `CHOOSE A CHANNEL · CELL ${cellIndex + 1}`;
  renderPicker();
  el("picker-modal").classList.remove("hidden");
}

function renderPicker() {
  const root = el("picker-list"); root.replaceChildren();
  const current = pickerCell != null ? prefs.assignments[pickerCell] : null;

  // "Clear this cell" first.
  const clear = node("div", "prow clearrow");
  clear.appendChild(node("span", "dot dot-offline"));
  clear.appendChild(node("span", "pname", "Clear this cell"));
  clear.onclick = () => assignCell(null);
  root.appendChild(clear);

  // Pre-sort browser-playable first, then TV-only, then offline (alpha within),
  // THEN group into category SECTIONS (Sports / US News / Global News / Business
  // / Weather / General) in the native order — mirroring native's ChannelPicker
  // Overlay (the caller pre-sorts; the grouping preserves order). Empty sections
  // are omitted (sectionChannels), so there are never empty headers.
  const order = (c) => {
    const bp = browserPlayability(c);
    if (bp === "yes" || bp === "maybe") return 0;
    if (channelStatus(c).playable) return 1;
    return 2;
  };
  const sorted = channelList.slice().sort((a, b) =>
    order(a) - order(b) || (a.label || a.slug).toLowerCase().localeCompare((b.label || b.slug).toLowerCase()));

  for (const section of sectionChannels(sorted)) {
    root.appendChild(node("div", "picker-section", section.category));
    for (const ch of section.channels) {
      const meta = pickerMeta(ch);
      const row = node("div", `prow${ch.slug === current ? " assigned" : ""}`);
      row.appendChild(node("span", `dot ${meta.dot}`));
      row.appendChild(node("span", "pname", ch.label || ch.slug));
      row.appendChild(node("span", "pstatus", meta.text));
      row.onclick = () => assignCell(ch.slug);
      root.appendChild(row);
    }
  }
}

function assignCell(slug) {
  if (pickerCell == null) return;
  if (slug) prefs.assignments[pickerCell] = slug;
  else delete prefs.assignments[pickerCell];
  // If the operator DELIBERATELY re-points the audible slot at a new channel
  // (the picker click is a user gesture), audio follows the slot to that channel
  // (native slot-based parity). Clearing the slot drops audio (handled when the
  // empty cell renders). A non-deliberate death/clear never carries audio over.
  if (pickerCell === audibleIndex) audibleSlug = slug || null;
  savePrefs();
  pushWallConfig();   // persist to the server wall config (drives the wall + /control/)
  el("picker-modal").classList.add("hidden");
  renderGrid();
}

// ----- slot controls modal (the web analog of native's SlotControlsOverlay) -----
// Reached by clicking a tile, or from the side menu's CHANNELS list. Rows mirror
// native: Channel (→ picker), Audio (single-source toggle), Reconnect, Close.
// There is deliberately NO Captions row (burned-in captions are unremovable —
// native's own caption row resolves to "not available" on these streams).
let slotModalIndex = null;

function openSlotControls(index) {
  slotModalIndex = index;
  refreshSlotControls();
  el("slot-modal").classList.remove("hidden");
}

function closeSlotControls() {
  slotModalIndex = null;
  closeModal("slot-modal");
}

/** (Re)render the slot-controls rows for the open slot, adapting to its state. */
function refreshSlotControls() {
  if (slotModalIndex == null) return;
  const idx = slotModalIndex;
  const cell = cells[idx];
  if (!cell) { closeSlotControls(); return; }   // grid shrank out from under the modal
  const slug = prefs.assignments[idx] || null;
  const ch = slug ? channelsBySlug.get(slug) : null;
  const label = ch ? (ch.label || ch.slug) : "(empty)";
  el("slot-title").textContent = `SLOT ${idx + 1}`;
  el("slot-channel").textContent = label;
  const body = el("slot-body"); body.replaceChildren();

  const row = (title, detail, detailCls, onActivate) => {
    const r = node("div", "slot-row");
    const main = node("div", "slot-row-main");
    main.appendChild(node("div", "slot-row-title", title));
    if (detail) main.appendChild(node("div", `slot-row-detail${detailCls ? " " + detailCls : ""}`, detail));
    r.appendChild(main);
    r.tabIndex = 0; r.setAttribute("role", "button");
    r.onclick = onActivate;
    r.onkeydown = (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onActivate(); } };
    body.appendChild(r);
  };

  // Channel — always available (opens the sectioned picker for this slot).
  row("Channel", "tap to choose", "muted", () => { closeSlotControls(); openPicker(idx); });

  // Audio — only when this tile is a PLAYING video (a stream to unmute). Single-
  // audible-tile model; toggling re-renders this row via updateTileAudioBadge.
  if (cell.isVideo && cell.streamHandle) {
    let aud = false;
    try { aud = cell.streamHandle.audible() === true; } catch { aud = false; }
    row("Audio", aud ? "audible — others muted" : "muted", aud ? "on" : "muted",
      () => setCellAudio(cell));
  }

  // Reconnect — only when a channel is assigned (something to reload).
  if (slug) row("Reconnect", "reload this stream", "muted", () => { closeSlotControls(); refreshCell(cell); });

  row("Close", "back to menu", "muted", () => closeSlotControls());
}

// ----- side menu (the web analog of native's MenuOverlay) — CHANNELS + WALL -----
function openMenu() {
  buildMenuChannels();
  populatePresetSelect();   // reflect the active preset + any newly-served presets
  el("menu-modal").classList.remove("hidden");
}
function closeMenu() { closeModal("menu-modal"); }

/** Build the CHANNELS list — one row per slot (native MenuOverlay CHANNELS). */
function buildMenuChannels() {
  const root = el("menu-channels"); if (!root) return;
  root.replaceChildren();
  for (const cell of cells) {
    const slug = prefs.assignments[cell.index] || null;
    const ch = slug ? channelsBySlug.get(slug) : null;
    const label = ch ? (ch.label || ch.slug) : "(empty)";
    const status = ch ? channelStatus(ch).label : "—";
    const r = node("div", "menu-row");
    r.tabIndex = 0; r.setAttribute("role", "button");
    const main = node("div", "menu-row-main");
    main.appendChild(node("div", "menu-row-title", `Slot ${cell.index + 1} · ${label}`));
    main.appendChild(node("div", `menu-row-detail status-${status}`, status));
    r.appendChild(main);
    const act = () => { closeMenu(); openSlotControls(cell.index); };
    r.onclick = act;
    r.onkeydown = (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); act(); } };
    root.appendChild(r);
  }
}

// ----- settings modal + source toggles -----
function rebuildSourceToggles(items) {
  const root = el("source-toggles"); if (!root) return;
  root.replaceChildren();
  const toggle = (s) => {
    const lab = node("label");
    const cb = document.createElement("input"); cb.type = "checkbox"; cb.checked = !prefs.hidden.has(s);
    cb.addEventListener("change", () => {
      if (cb.checked) prefs.hidden.delete(s); else prefs.hidden.add(s);
      savePrefs(); pollFeed();
    });
    lab.append(cb, node("span", null, s));
    return lab;
  };
  // Group the feed-source toggles by category (the helper's taxonomy, served as
  // source_category) — US News / Global News / Sports / Business / General;
  // empty categories are omitted. Uncheck-to-hide is unchanged.
  for (const section of sectionFeedSources(items)) {
    root.appendChild(node("div", "toggle-section", section.category));
    for (const s of section.sources) root.appendChild(toggle(s));
  }
}

/** Build the Sports-leagues toggle list from the leagues the helper is
 *  actually serving (the real pool) — DENYLIST: an unchecked league is
 *  hidden; a newly-appearing league shows by default. Mirrors the native
 *  "Sports leagues…" picker. The label shows the hidden count like native. */
function rebuildLeagueToggles() {
  const root = el("league-toggles"); if (!root) return;
  const pool = leaguePool(latestSportsEntries);
  const label = el("leagues-label");
  if (label) {
    const hidden = prefs.hiddenLeagues.size;
    label.textContent = `Sports leagues (uncheck to hide) · ${hidden === 0 ? "all shown" : hidden + " hidden"}`;
  }
  root.replaceChildren();
  if (pool.length === 0) {
    root.appendChild(node("p", "empty", "No sports leagues on right now."));
    return;
  }
  for (const lg of pool) {
    const lab = node("label");
    const cb = document.createElement("input");
    cb.type = "checkbox"; cb.checked = !prefs.hiddenLeagues.has(lg);
    cb.addEventListener("change", () => {
      if (cb.checked) prefs.hiddenLeagues.delete(lg); else prefs.hiddenLeagues.add(lg);
      savePrefs();
      rebuildLeagueToggles();   // refresh the hidden-count label
      renderTicker();           // league filter affects the sports ticker now
      renderFeed(latestFeedItems);   // and a league's news in the feed
    });
    lab.append(cb, node("span", null, lg));
    root.appendChild(lab);
  }
}

function closeModal(id) { el(id).classList.add("hidden"); }

function wireSettings() {
  // Whole-wall reconnect — a keyboard-accessible <button>, so Enter/Space work
  // for a remote/keyboard-driven wall, not just a mouse click.
  el("refresh-all").addEventListener("click", refreshAllVideo);
  // The gear opens the SIDE MENU (native MenuOverlay): CHANNELS list + WALL
  // section (Settings, Resync all feeds). Settings is reached FROM the menu.
  el("gear").addEventListener("click", openMenu);
  el("menu-close").addEventListener("click", closeMenu);
  el("menu-modal").addEventListener("click", (e) => { if (e.target === el("menu-modal")) closeMenu(); });
  // Click + keyboard (Enter/Space) for the static WALL rows (they're role=button).
  const wireRow = (id, fn) => {
    const r = el(id); if (!r) return;
    r.addEventListener("click", fn);
    r.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); fn(); } });
  };
  wireRow("menu-settings", () => { closeMenu(); el("settings-modal").classList.remove("hidden"); });
  wireRow("menu-resync", () => { refreshAllVideo(); closeMenu(); });
  // Wall preset selector (server-authoritative): apply on change, then close.
  const presetSel = el("menu-preset");
  if (presetSel) presetSel.addEventListener("change", () => { applyPreset(presetSel.value); closeMenu(); });
  el("slot-close").addEventListener("click", closeSlotControls);
  el("slot-modal").addEventListener("click", (e) => { if (e.target === el("slot-modal")) closeSlotControls(); });
  el("settings-close").addEventListener("click", () => closeModal("settings-modal"));
  el("settings-modal").addEventListener("click", (e) => { if (e.target === el("settings-modal")) closeModal("settings-modal"); });
  el("picker-close").addEventListener("click", () => closeModal("picker-modal"));
  el("picker-modal").addEventListener("click", (e) => { if (e.target === el("picker-modal")) closeModal("picker-modal"); });
  el("story-close").addEventListener("click", () => closeStory());
  el("story-modal").addEventListener("click", (e) => { if (e.target === el("story-modal")) closeStory(); });

  // Esc closes the topmost open overlay (innermost first), mirroring native BACK.
  document.addEventListener("keydown", (e) => {
    if (e.key !== "Escape") return;
    if (!el("story-modal").classList.contains("hidden")) closeStory();
    else if (!el("picker-modal").classList.contains("hidden")) closeModal("picker-modal");
    else if (!el("slot-modal").classList.contains("hidden")) closeSlotControls();
    else if (!el("settings-modal").classList.contains("hidden")) closeModal("settings-modal");
    else if (!el("menu-modal").classList.contains("hidden")) closeMenu();
  });

  // Grid rows × cols (native parity — independent dims, each 1–3). A dims
  // change rebuilds the grid; per-slot assignments survive for slots that
  // still exist (they're keyed by index). The note shows the resulting count.
  const gridRows = el("grid-rows"); gridRows.value = String(prefs.gridRows);
  const gridCols = el("grid-cols"); gridCols.value = String(prefs.gridCols);
  const updateGridNote = () => {
    const cfg = gridConfig();
    const note = el("grid-note");
    if (note) note.textContent = `${cfg.rows} × ${cfg.cols} = ${cfg.count} cell${cfg.count === 1 ? "" : "s"}`;
  };
  updateGridNote();
  const onGridDim = (sel, key) => sel.addEventListener("change", () => {
    prefs[key] = clampGridDim(Number(sel.value));
    sel.value = String(prefs[key]);   // reflect the clamp
    savePrefs(); buildGrid(); updateGridNote();
    pushWallConfig();   // grid layout is wall-config state — persist it
  });
  onGridDim(gridRows, "gridRows");
  onGridDim(gridCols, "gridCols");

  const feedWidth = el("feed-width"); feedWidth.value = String(prefs.feedPct);
  feedWidth.addEventListener("input", () => { prefs.feedPct = clampFeedPct(Number(feedWidth.value)); applyPrefs(); savePrefs(); });

  const feedFont = el("feed-font"); feedFont.value = String(prefs.feedFont);
  feedFont.addEventListener("change", () => { prefs.feedFont = Number(feedFont.value); applyPrefs(); savePrefs(); });

  // Feed side (native Feed side parity — feed on the left or right). Live-applied.
  const feedSide = el("feed-side"); feedSide.value = prefs.feedSide;
  feedSide.addEventListener("change", () => {
    prefs.feedSide = feedSideOption(feedSide.value);
    feedSide.value = prefs.feedSide;
    applyPrefs(); savePrefs();
  });

  // Feed recency window (native FeedRecency parity). Options are data-driven.
  const feedRecency = el("feed-recency");
  feedRecency.replaceChildren();
  for (const opt of FEED_RECENCY_OPTIONS) {
    const o = document.createElement("option");
    o.value = opt.id; o.textContent = opt.label;
    feedRecency.appendChild(o);
  }
  feedRecency.value = prefs.feedRecency;
  feedRecency.addEventListener("change", () => {
    prefs.feedRecency = feedRecencyOption(feedRecency.value).id;
    savePrefs(); renderFeed(latestFeedItems);
  });

  // Ticker motion (cross-platform setting; web default "crawl"). Live-applied —
  // switching re-renders the strip into the other motion immediately.
  const tickerMotion = el("ticker-motion"); tickerMotion.value = prefs.tickerMotion;
  tickerMotion.addEventListener("change", () => {
    prefs.tickerMotion = tickerMotion.value === "flip" ? "flip" : "crawl";
    savePrefs(); renderTicker();
  });

  // Ticker scroll speed — crawl velocity (native Scroll speed / tickerScrollPct).
  const tickerScroll = el("ticker-scroll"); tickerScroll.value = String(prefs.tickerScrollPct);
  const scrollVal = el("ticker-scroll-val");
  const showScrollVal = () => { if (scrollVal) scrollVal.textContent = `${prefs.tickerScrollPct}%`; };
  showScrollVal();
  tickerScroll.addEventListener("input", () => {
    prefs.tickerScrollPct = clampTickerSpeedPct(Number(tickerScroll.value));
    tickerScroll.value = String(prefs.tickerScrollPct);   // reflect the clamp (thumb can't desync)
    showScrollVal(); savePrefs(); renderTicker();
  });

  // Ticker flip speed — paged-flip dwell (native Flip speed / tickerFlipPct), a
  // SEPARATE lever from scroll speed (matches native; only affects flip motion).
  const tickerFlip = el("ticker-flip"); tickerFlip.value = String(prefs.tickerFlipPct);
  const flipVal = el("ticker-flip-val");
  const showFlipVal = () => { if (flipVal) flipVal.textContent = `${prefs.tickerFlipPct}%`; };
  showFlipVal();
  tickerFlip.addEventListener("input", () => {
    prefs.tickerFlipPct = clampTickerSpeedPct(Number(tickerFlip.value));
    tickerFlip.value = String(prefs.tickerFlipPct);   // reflect the clamp (thumb can't desync)
    showFlipVal(); savePrefs(); renderTicker();
  });

  // Ticker news toggle (native tickerNewsEnabled, default OFF — load-bearing
  // honesty/feel default). Adding/removing the NEWS mode re-polls the ticker.
  const tickerNews = el("ticker-news"); tickerNews.checked = prefs.tickerNews === true;
  tickerNews.addEventListener("change", () => {
    prefs.tickerNews = tickerNews.checked;
    savePrefs(); pollTicker();
  });

  // Captions (wall-wide, default OFF). Soft subtitle tracks only — burned-in
  // captions can't be removed. Applies live to every playing tile.
  const captions = el("captions-enabled");
  if (captions) {
    captions.checked = prefs.captions === true;
    captions.addEventListener("change", () => {
      prefs.captions = captions.checked;
      // A wall-wide toggle, stored PER-CELL so the server config + /control/'s
      // per-cell view agree (the per-cell control itself lives in /control/).
      const layout = gridConfig();
      cellSubtitles = {};
      if (captions.checked) for (let i = 0; i < layout.count; i++) cellSubtitles[i] = true;
      savePrefs();
      for (const c of cells) {
        if (c.isVideo && c.streamHandle) { try { c.streamHandle.setCaptions(cellCaptionsOn(c.index)); } catch { /* no track */ } }
      }
      pushWallConfig();
    });
  }

  rebuildLeagueToggles();
}

/** Draggable feed↔video divider — pointer (mouse + touch) drag resizes the split
 *  via the existing `feedPct` pref (→ `--feed-pct`); the ratio persists through
 *  savePrefs (the same localStorage prefs store, not a new key). The video grid's
 *  column logic (incl. the 3-column native-parity cap) is untouched — only the
 *  pane widths change. ←/→ when the divider is focused nudges it (keyboard a11y). */
function wireDivider() {
  const divider = el("pane-divider");
  const wall = el("wall");
  if (!divider || !wall) return;
  const setFeedPct = (pct) => {
    prefs.feedPct = pct;
    document.documentElement.style.setProperty("--feed-pct", pct + "%");
    const slider = el("feed-width");
    if (slider) slider.value = String(pct);   // keep the settings slider in sync
  };
  let dragging = false;
  divider.addEventListener("pointerdown", (e) => {
    dragging = true;
    divider.classList.add("dragging");
    try { divider.setPointerCapture(e.pointerId); } catch { /* not all targets capture */ }
    e.preventDefault();
  });
  divider.addEventListener("pointermove", (e) => {
    if (!dragging) return;
    const r = wall.getBoundingClientRect();
    // When the feed is on the RIGHT, its width grows toward the right edge, so the
    // pointer's distance is measured from the wall's RIGHT edge instead of the left.
    const x = prefs.feedSide === "right" ? (r.left + (r.right - e.clientX)) : e.clientX;
    setFeedPct(feedPctFromPointer(x, r.left, r.width));   // pure clamp in render.mjs
  });
  const endDrag = (e) => {
    if (!dragging) return;
    dragging = false;
    divider.classList.remove("dragging");
    try { divider.releasePointerCapture(e.pointerId); } catch { /* already released */ }
    savePrefs();   // persist the chosen ratio
  };
  divider.addEventListener("pointerup", endDrag);
  divider.addEventListener("pointercancel", endDrag);
  divider.addEventListener("keydown", (e) => {
    if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
    // Move the divider in the ARROW's direction on either side: when the feed is
    // on the right, growing it (ArrowLeft = divider left) means a LARGER feedPct,
    // so invert the sign to mirror the (already side-aware) pointer-drag math.
    const step = (e.key === "ArrowLeft" ? -2 : 2) * (prefs.feedSide === "right" ? -1 : 1);
    setFeedPct(clampFeedPct(prefs.feedPct + step));
    savePrefs();
    e.preventDefault();
  });
}

function startLoop(fn, ms) { fn(); return setInterval(fn, ms); }
/** Hover-to-read: pause the CRAWL marquee while the pointer is over the ticker,
 *  resume on leave. The old CSS `:hover { animation-play-state: paused }` only
 *  governed a CSS animation; the WAAPI marquee needs explicit pause/play. Bound
 *  once on the ticker bar (the animation handle is looked up live each time). */
function wireTickerHoverPause() {
  const bar = document.querySelector(".ticker-bar");
  if (!bar) return;
  bar.addEventListener("mouseenter", () => { if (crawlAnim) try { crawlAnim.pause(); } catch {} });
  bar.addEventListener("mouseleave", () => {
    if (crawlAnim && prefs.tickerMotion === "crawl") try { crawlAnim.play(); } catch {}
  });
}

function main() {
  applyPrefs();
  wireSettings();
  wireDivider();
  buildGrid();
  wireTickerHoverPause();
  startLoop(pollTicker, TICKER_POLL_MS);
  startLoop(pollFeed, FEED_POLL_MS);
  startLoop(pollChannels, CHANNELS_POLL_MS);
  pollPresets();   // server-authoritative presets (rarely change → fetch once)
  // Server-side wall config: render FROM it (headless-container version). Polled
  // so a /control/ picker change appears here within WALL_POLL_MS; a stored
  // config is authoritative for the grid (layout + per-cell channel/audio/subs).
  startLoop(pollWall, WALL_POLL_MS);
  setInterval(rotateTicker, MODE_ROTATE_MS);
}
document.addEventListener("DOMContentLoaded", main);
