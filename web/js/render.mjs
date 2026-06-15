// Pure render/label/grouping logic for the MyMTS LAN web client.
//
// This module holds the *honesty-bearing* logic — the same C3 discipline
// the native app enforces — as pure functions with NO DOM and NO fetch,
// so it is unit-tested with `node --test` (no browser, no toolchain).
// The browser loads it as an ES module; `app.js` calls these to turn
// helper-API JSON into display strings/structures, and only ever writes
// them into the DOM as text.
//
// A1 (closed door): every function here returns plain strings/data the
// caller renders as text. Nothing here builds HTML, resolves a URL, or
// touches an article page. The web client is a dumb consumer of the
// helper's already-inert plain-text data, exactly like the native app.

// ----- schema_version guard (ARCH-1: never render a contract we don't grok) -----

/** The ticker envelope schema this web client understands. Pinned to the
 *  helper's TICKER_SCHEMA_VERSION. If the helper bumps it (a new wire
 *  contract), this client is out of date and must DEGRADE HONESTLY —
 *  surface a visible "client out of date" state, never silently render
 *  fields it may misread. Additive helper changes keep this number; a
 *  breaking shape change bumps it on both sides in lockstep. */
export const TICKER_SCHEMA_VERSION = 1;

/**
 * Validate a ticker envelope's schema_version against what this client
 * understands. Pure; returns { ok, version, reason }.
 *   ok=true  → version matches → safe to render the entries.
 *   ok=false → missing/mismatched version → caller must show the honest
 *              "client out of date" state and render NO cards (degrade,
 *              don't fabricate against an unknown contract).
 * A null/undefined envelope is treated as "no data" (ok:false, reason
 * "unavailable") so the caller never tries to read entries off nothing.
 */
export function tickerSchemaCheck(envelope) {
  if (!envelope || typeof envelope !== "object") {
    return { ok: false, version: null, reason: "unavailable" };
  }
  const version = envelope.schema_version;
  if (version === TICKER_SCHEMA_VERSION) {
    return { ok: true, version, reason: "" };
  }
  if (version == null) {
    return { ok: false, version: null, reason: "missing schema_version" };
  }
  return { ok: false, version, reason: "schema mismatch" };
}

/** Short, honest banner text for a failed schema check (empty when ok). */
export function tickerSchemaNote(envelope) {
  const { ok, version, reason } = tickerSchemaCheck(envelope);
  if (ok) return "";
  if (reason === "unavailable") return "";   // unavailability is its own state
  if (version == null) return "client out of date — update needed";
  return `client out of date — expects v${TICKER_SCHEMA_VERSION}, helper sent v${version}`;
}

// ----- direction glyphs (markets) / none (sports) -----

export function directionGlyph(direction) {
  switch (direction) {
    case "up": return "▲";    // ▲
    case "down": return "▼";  // ▼
    case "flat": return "■";  // ■
    case "none": return "";        // sports: no arrow
    default: return "";            // unknown → no glyph (forward-compatible)
  }
}

export function directionClass(direction) {
  if (direction === "up") return "dir-up";
  if (direction === "down") return "dir-down";
  return "dir-flat";
}

// ----- ticker entry → display model (honest sample/stale) -----

/**
 * Turn a ticker envelope + entry into a display row model.
 * `isSample` and the envelope `stale` flag travel through untouched —
 * the caller renders a SAMPLE pill / stale note, never upgrades sample
 * to live. Returns { symbol, value, glyph, dirClass, sample }.
 */
export function tickerRow(entry) {
  return {
    symbol: String(entry.symbol ?? ""),
    value: String(entry.display ?? ""),
    glyph: directionGlyph(entry.direction),
    dirClass: directionClass(entry.direction),
    sample: entry.is_sample === true,
  };
}

/**
 * Envelope-level staleness note for a ticker mode. Honest: an all-sample
 * pre-poll snapshot (as_of null) is NOT stale — sample is honest, not
 * stale. Returns "" when fresh/honest-sample, else a short note.
 */
export function tickerStaleNote(envelope) {
  if (!envelope) return "";
  if (envelope.stale === true) return "stale — not updating";
  return "";
}

// ----- sports status block (mirrors native kindOf / formatStatus) -----

/**
 * Lifecycle kind from the ESPN state token — the same 3-way the native
 * SportsTicker.kindOf draws: `in`→LIVE, `post`→FINAL, everything else
 * (`pre` + unknown) → UPCOMING. Pure. Drives the status-chip colour.
 */
export function statusKind(state) {
  switch (String(state ?? "").toLowerCase()) {
    case "in": return "live";
    case "post": return "final";
    default: return "upcoming";   // "pre" + any unknown token
  }
}

/**
 * Status chip text, mirroring native formatStatus exactly:
 *   FINAL    → literal "FINAL"
 *   LIVE     → "LIVE" if blank, else status with " - " normalised to a
 *              space, UPPERCASED ("5:42 - 1st" → "5:42 1ST")
 *   UPCOMING → status as-is, or "—" if blank
 * Pure.
 */
export function formatStatus(state, status) {
  const s = String(status ?? "").trim();
  switch (statusKind(state)) {
    case "final": return "FINAL";
    case "live": return s === "" ? "LIVE" : s.replace(/ - /g, " ").toUpperCase();
    default: return s === "" ? "—" : s;
  }
}

/**
 * Structured display model for one ticker entry — the honest, DOM-free
 * branch the caller renders. Branches on the wire shape the helper emits
 * (NOT by parsing `display`):
 *   - `card` present  → individual sport, type "card", dispatch on card.kind
 *                       (leaderboard|fight|match|race|generic-for-unknown).
 *   - `game` present  → team game, type "game".
 *   - neither         → markets/news cell, type "cell" (direction arrow + value).
 *
 * `game`/`card` are ABSENT (popped, not null) when not applicable, so we
 * test with the `in` operator, exactly as the wire contract specifies.
 *
 * Honesty: `sample` (per-entry is_sample) rides through untouched on EVERY
 * branch — sample is NEVER upgraded to live-real. Only an explicit
 * is_sample===true is sample.
 *
 * `newsMode` forces the news cell shape (source label + headline, inert)
 * for entries pulled from the feed into the ticker's NEWS mode.
 */
export function tickerCardModel(entry, { newsMode = false } = {}) {
  const e = entry ?? {};
  const sample = e.is_sample === true;
  const symbol = String(e.symbol ?? "");
  const display = String(e.display ?? "");

  if (newsMode) {
    return { type: "news", source: symbol, headline: display, sample };
  }

  if (e.card && typeof e.card === "object") {
    const c = e.card;
    const knownKinds = new Set(["leaderboard", "fight", "match", "race"]);
    const rawKind = String(c.kind ?? "");
    const kind = knownKinds.has(rawKind) ? rawKind : "generic";
    return {
      type: "card",
      kind,
      league: String(c.league ?? symbol),
      title: String(c.title ?? ""),
      state: String(c.state ?? ""),
      statusKind: statusKind(c.state),
      status: formatStatus(c.state, c.status),
      lines: Array.isArray(c.lines) ? c.lines.map((l) => String(l)) : [],
      sample,
    };
  }

  if (e.game && typeof e.game === "object") {
    const g = e.game;
    const kind = statusKind(g.state);
    const away = String(g.away ?? "");
    const home = String(g.home ?? "");
    const awayScore = String(g.away_score ?? "");
    const homeScore = String(g.home_score ?? "");
    // Scores shown only when LIVE/FINAL and both sides have a score; a `pre`
    // matchup (empty scores) renders "AWAY @ HOME", never a phantom 0–0.
    const hasScores = kind !== "upcoming" && awayScore !== "" && homeScore !== "";
    // Leader highlight is LIVE-only and only when both scores parse — FINAL
    // shows no leader emphasis (matches native TeamScore.leading rule).
    let awayLeads = false, homeLeads = false;
    if (kind === "live" && hasScores) {
      const a = Number(awayScore), h = Number(homeScore);
      if (Number.isFinite(a) && Number.isFinite(h)) {
        awayLeads = a > h;
        homeLeads = h > a;
      }
    }
    return {
      type: "game",
      league: String(g.league ?? symbol),
      kind,
      away, home, awayScore, homeScore,
      hasScores, awayLeads, homeLeads,
      status: formatStatus(g.state, g.status),
      sample,
    };
  }

  // Neither game nor card → markets/news cell (direction arrow + value).
  return {
    type: "cell",
    symbol,
    value: display,
    glyph: directionGlyph(e.direction),
    dirClass: directionClass(e.direction),
    direction: String(e.direction ?? "none"),
    sample,
  };
}

/**
 * Group a list of ticker entries into labelled, card-bearing runs, keyed
 * by the page marker the native app uses: `game.league ?? card.league ??
 * symbol`. Consecutive entries sharing a label collapse under one marker
 * (ESPN-BottomLine: the league shows ONCE). Each run carries structured
 * card models (via tickerCardModel), NOT flat strings — so the caller
 * renders bespoke per-sport cards from real fields.
 *
 * Pure. Returns [{ label, cards: [model, ...] }]. `newsMode` routes every
 * entry through the news-cell shape under a "NEWS" marker.
 */
export function groupTickerCards(entries, { newsMode = false } = {}) {
  const groups = [];
  for (const entry of entries ?? []) {
    const model = tickerCardModel(entry, { newsMode });
    let label;
    if (newsMode) label = "NEWS";
    else if (model.type === "card" || model.type === "game") label = model.league;
    else label = model.symbol;
    const last = groups[groups.length - 1];
    if (last && last.label === label) last.cards.push(model);
    else groups.push({ label, cards: [model] });
  }
  return groups;
}

/**
 * Build the NEWS ticker entries from feed items: source-labelled, inert
 * plain text (A1 — no in-ticker reading). Newest-first, capped so the
 * marquee stays glanceable. Pure — reuses the same chronological+source
 * discipline as the feed pane. Each becomes a normal ticker entry shape
 * ({symbol, display, direction, is_sample}) consumed by groupTickerCards
 * in newsMode. Honest: a feed item carries no sample flag, so news is
 * is_sample=false (real headlines) — never a fabricated SAMPLE pill.
 */
export function newsTickerEntries(items, limit = 24) {
  // Drop empty-title items BEFORE capping so an untitled feed row never
  // consumes a marquee slot (cap counts only renderable headlines).
  return feedChronological(items)
    .map((it) => ({
      symbol: sourceLabel(it),
      display: String(it.title ?? "").trim(),
      direction: "none",
      is_sample: false,
    }))
    .filter((e) => e.display !== "")
    .slice(0, Math.max(0, limit));
}

// ----- ticker league grouping (ESPN-BottomLine style) -----

/**
 * Group consecutive ticker entries that share a symbol into one labelled
 * run — the ESPN-BottomLine pattern: the league/symbol marker appears
 * ONCE, then its rows follow without repeating the prefix. The helper
 * already emits a league's games contiguously (all MLB, then all NHL),
 * so consecutive-grouping reproduces "MLB ⟨game⟩ ⟨game⟩ … NHL ⟨game⟩".
 *
 * Markets symbols are each distinct, so every markets entry becomes its
 * own single-row group — visually unchanged from the per-symbol layout.
 * Sports entries collapse: eight "MLB | …" rows become one "MLB" marker
 * with eight rows, killing the redundant per-item league prefix.
 *
 * Pure. Returns [{ label, cells: [{ value, glyph, dirClass, sample }] }].
 * Each cell is a tickerRow() minus the symbol (which moved to the label).
 */
export function groupTickerByLeague(entries) {
  const groups = [];
  for (const entry of entries ?? []) {
    const row = tickerRow(entry);
    const last = groups[groups.length - 1];
    const cell = { value: row.value, glyph: row.glyph, dirClass: row.dirClass, sample: row.sample };
    if (last && last.label === row.symbol) {
      last.cells.push(cell);
    } else {
      groups.push({ label: row.symbol, cells: [cell] });
    }
  }
  return groups;
}

// ----- feed: agnostic chronological list (web client — Onn style) -----

/**
 * Flatten feed items into a single agnostic, newest-first list across ALL
 * sources — the original Onn-box style the operator prefers for the WEB
 * client: one chronological river with the SOURCE shown next to each
 * headline (not grouped into per-source sections). Pure; each item keeps
 * its `source` so the caller renders it inline.
 *
 * Honest staleness stays expressible per item via the time/age the caller
 * renders (relativeTime) — an item that hasn't refreshed simply shows its
 * true age. Source attribution uses the same "Unknown source" bucket label
 * as the grouped view, so a blank source is never silently dropped.
 *
 * NOTE: the NATIVE app intentionally keeps per-source SECTIONS (Stage 7,
 * FeedListBuilder) — this divergence is a per-client preference, not a
 * reversal of the native decision. Whether the native feed should also go
 * agnostic is flagged for the operator (BACKLOG), not changed here.
 */
export function feedChronological(items) {
  return (items ?? []).slice().sort(byNewestFirst);
}

/** The display source label for an item (blank → the Unknown bucket). */
export function sourceLabel(item) {
  return (item && item.source && item.source.trim()) ? item.source : "Unknown source";
}

// ----- feed story detail / expand (A1 honest, inert) -----

/**
 * Gate a feed item's `link` for use as a click-OUT to the source. ONLY
 * http/https are allowed — a `javascript:`, `data:`, or other scheme is
 * rejected (an href-injection / XSS vector). The link hands off to the
 * operator's OWN browser; MyMTS never fetches or renders the article itself
 * (the A1 closed door stays shut — this is a link-out, not an in-app reader).
 * Pure; returns the URL string, or "" if unsafe/absent.
 */
export function safeHttpLink(url) {
  const s = String(url ?? "").trim();
  if (s === "") return "";
  let u;
  try {
    u = new URL(s);
  } catch {
    return "";
  }
  return (u.protocol === "https:" || u.protocol === "http:") ? s : "";
}

/**
 * Build the detail-view model for a focused/expanded feed story — derived
 * ONLY from the item's OWN fields (no fetch, no scrape). `title`/`summary` are
 * the item's plain text, returned as strings the caller renders via
 * `textContent` (never innerHTML — a hostile RSS title can't script). `link`
 * is gated through `safeHttpLink`. Pure. Returns
 * { source, title, summary, timestamp, link, hasLink }.
 */
export function feedDetailModel(item) {
  const it = item ?? {};
  const link = safeHttpLink(it.link);
  return {
    source: sourceLabel(it),
    title: String(it.title ?? "").trim(),
    summary: String(it.summary ?? "").trim(),
    timestamp:
      (it.published_at && String(it.published_at).trim()) ||
      (it.fetched_at && String(it.fetched_at).trim()) ||
      "",
    link,
    hasLink: link !== "",
  };
}

// ----- video grid: cell-count layout (replaces freeform size drag) -----

/** The video-cell counts the web grid offers (TV is capped at 4; the
 *  laptop/browser isn't the constrained S905Y4, so it goes higher). */
export const GRID_CELL_COUNTS = [1, 2, 4, 6, 9];

/**
 * Lay out N video cells into a near-square grid: the operator picks a
 * COUNT (1/2/4/6/9), not a freeform size — intuitive and matching the
 * native wall's tile-count model. Pure: returns { count, cols, rows }.
 * An unknown count clamps to the nearest supported value (default 4).
 */
export function gridLayout(cellCount) {
  const count = GRID_CELL_COUNTS.includes(cellCount) ? cellCount : 4;
  const cols = { 1: 1, 2: 2, 4: 2, 6: 3, 9: 3 }[count];
  const rows = Math.ceil(count / cols);
  return { count, cols, rows };
}

// ----- grid rows × cols (native parity: independent dims, each 1–3) -----

/** Grid rows/cols are each clamped to this range (1–3 → up to a 3×3 = 9
 *  grid), mirroring the native GRID_DIM_MIN/MAX. The web's earlier
 *  cell-COUNT model is a derived view of this (count = rows × cols).
 *
 *  The 3-cap is a DELIBERATE design constraint, not a bug or a feed-width/CSS
 *  limit (the CSS grid is `repeat(var(--grid-cols), 1fr)` — it would render 4+
 *  columns fine). It is native parity: the web client mirrors the TV wall, and
 *  the native wall caps each dim at 3 (legible/performant on a 1080p TV driven
 *  by the constrained S905Y4). Raising it on web alone would break the "same
 *  wall on both screens" contract. Do not widen without changing native too.
 *  See ARCHITECTURE.md (native-vs-web). */
export const GRID_DIM_MIN = 1;
export const GRID_DIM_MAX = 3;

/** Clamp a stored/edited grid dimension into [GRID_DIM_MIN]..[GRID_DIM_MAX]
 *  (mirrors native clampGridDim). A non-finite value clamps to the min. */
export function clampGridDim(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return GRID_DIM_MIN;
  return Math.min(GRID_DIM_MAX, Math.max(GRID_DIM_MIN, Math.round(n)));
}

/**
 * Lay out the video grid from independent ROWS × COLS (native parity — the
 * operator tunes rows and cols separately, each 1–3, so 2×2, 1×3, 3×2, …,
 * up to 3×3 = 9). Returns the SAME `{ count, cols, rows }` shape as
 * `gridLayout` so the rest of the client (CSS vars, cell reconcile) is
 * unchanged. `count = rows × cols` (native `gridCells`). Pure; both dims
 * are clamped defensively on read. The per-slot channel assignments are
 * keyed by slot index, so they survive a dims change for slots that still
 * exist (native LineupStore behaviour).
 */
export function gridLayoutFromDims(rows, cols) {
  const r = clampGridDim(rows);
  const c = clampGridDim(cols);
  return { count: r * c, cols: c, rows: r };
}

// ----- ticker scroll speed (native parity: tickerScrollPct slider) -----

/** Ticker-speed slider bounds + step, mirroring the native
 *  TICKER_SPEED_MIN/MAX/STEP_PCT. 100% = the calm default; the bounds keep
 *  the marquee from getting unreadably fast or painfully slow. */
export const TICKER_SPEED_MIN_PCT = 40;
export const TICKER_SPEED_MAX_PCT = 200;
export const TICKER_SPEED_STEP_PCT = 20;

/** Clamp a ticker-speed percent into the allowed range (defensive on read +
 *  nudge), mirroring native clampTickerSpeedPct. Non-finite → 100 (default). */
export function clampTickerSpeedPct(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return 100;
  return Math.min(TICKER_SPEED_MAX_PCT, Math.max(TICKER_SPEED_MIN_PCT, Math.round(n)));
}

/**
 * Scale a base marquee px/sec velocity by the operator's `tickerScrollPct`
 * (native `tickerScrollPct` scales BASE_SCROLL_VELOCITY). 100% = base;
 * higher = faster. The CSS animation duration is `trackHalfWidth / px-per-
 * sec`, so the caller divides by this scaled velocity. Pure; the percent
 * is clamped so an out-of-range stored pref can't produce a 0 or absurd
 * velocity. Returns px/sec (> 0).
 */
export function tickerScrollPxPerSec(basePxPerSec, pct) {
  const clamped = clampTickerSpeedPct(pct);
  return Math.max(1, basePxPerSec * (clamped / 100));
}

// ----- ticker motion mode (cross-platform: continuous crawl vs paged flip) ---

/** The two ticker MOTIONS, offered on BOTH platforms (a single shared setting):
 *   "crawl" — a continuous horizontal marquee of all the mode's cards (the
 *             web wall's historical motion);
 *   "flip"  — one card-group shown at a time, vertically flipping to the next
 *             on a dwell (the native TV wall's historical motion).
 *  The DEFAULT differs per platform (web = crawl, Android = flip) to preserve
 *  each wall's established feel; the operator can switch either to the other. */
export const TICKER_MOTIONS = ["crawl", "flip"];

/** Coerce a stored/edited motion into a valid mode; unknown → the platform
 *  default the caller passes (web passes "crawl"). Pure. */
export function tickerMotionOption(value, fallback = "crawl") {
  return TICKER_MOTIONS.includes(value) ? value : (TICKER_MOTIONS.includes(fallback) ? fallback : "crawl");
}

/** Base per-group dwell (ms) for the web FLIP motion at 100% speed — mirrors
 *  the native BASE_DWELL_MS feel. The ticker-speed percent scales it: a higher
 *  percent shortens the dwell (faster flips), clamped so a busy poll/extreme
 *  pref can't drive it to zero. Pure. */
export const TICKER_FLIP_BASE_DWELL_MS = 9000;
export function tickerFlipDwellMs(pct, base = TICKER_FLIP_BASE_DWELL_MS, min = 2500) {
  const clamped = clampTickerSpeedPct(pct);
  return Math.max(min, Math.round(base * 100 / clamped));
}

// ----- view-prefs normalize / serialize (browser-local, pure + testable) -----

/** Default view prefs (the panel-fit levers are deliberately absent — TV-only). */
export const DEFAULT_VIEW_PREFS = {
  gridRows: 2, gridCols: 2, feedPct: 32, feedFont: 1,
  tickerNews: false, feedRecency: "all", tickerScrollPct: 100,
  tickerMotion: "crawl",   // web default; Android defaults to "flip"
  hidden: [], hiddenLeagues: [], assignments: {},
};

// ----- feed/video pane split (the draggable divider's clamps + math) -----

/** Bounds for the feed-pane width (percent of the wall body) — the divider can't
 *  starve the feed of headline room nor shrink the video pane below a watchable
 *  size. Video pane min = 100 − FEED_PANE_MAX_PCT. Named for tunability. */
export const FEED_PANE_MIN_PCT = 18;
export const FEED_PANE_MAX_PCT = 58;

/** Clamp a feed-pane width percent into [FEED_PANE_MIN_PCT..FEED_PANE_MAX_PCT];
 *  a non-finite value falls back to the default. Pure — unit-tested. */
export function clampFeedPct(value, min = FEED_PANE_MIN_PCT, max = FEED_PANE_MAX_PCT) {
  const n = Number(value);
  if (!Number.isFinite(n)) return DEFAULT_VIEW_PREFS.feedPct;
  return Math.min(max, Math.max(min, n));
}

/** The feed-pane width percent for a pointer at clientX [pointerX] over the wall
 *  body (its left edge [wallLeft], width [wallWidth] px), clamped so neither pane
 *  collapses. A ratio (not fixed px) so it stays sane across window sizes. Pure. */
export function feedPctFromPointer(pointerX, wallLeft, wallWidth) {
  if (!(Number(wallWidth) > 0)) return DEFAULT_VIEW_PREFS.feedPct;
  const raw = ((Number(pointerX) - Number(wallLeft)) / Number(wallWidth)) * 100;
  return clampFeedPct(raw);
}

/**
 * Normalize a raw (parsed-JSON or partial) prefs object into the canonical
 * view-prefs shape — clamping every value into its valid range, defaulting
 * the missing, and honestly defaulting `tickerNews` OFF (explicit opt-in
 * only, the load-bearing native default). Denylists come back as ARRAYS
 * (JSON-friendly; the caller wraps them in Sets). A legacy single
 * `cellCount` (the v3 grid model) migrates into rows × cols so the
 * operator's grid size carries forward. Pure — no DOM, no localStorage —
 * so the persistence round-trip is unit-tested against the REAL code path.
 */
export function normalizeViewPrefs(raw) {
  const p = (raw && typeof raw === "object") ? raw : {};
  let gridRows = p.gridRows, gridCols = p.gridCols;
  if (gridRows == null && gridCols == null && GRID_CELL_COUNTS.includes(p.cellCount)) {
    const legacy = gridLayout(p.cellCount);
    gridRows = legacy.rows; gridCols = legacy.cols;
  }
  return {
    gridRows: clampGridDim(gridRows ?? DEFAULT_VIEW_PREFS.gridRows),
    gridCols: clampGridDim(gridCols ?? DEFAULT_VIEW_PREFS.gridCols),
    feedPct: clampFeedPct(typeof p.feedPct === "number" ? p.feedPct : DEFAULT_VIEW_PREFS.feedPct),
    feedFont: typeof p.feedFont === "number" ? p.feedFont : DEFAULT_VIEW_PREFS.feedFont,
    tickerNews: p.tickerNews === true,   // explicit opt-in only (honest default OFF)
    feedRecency: feedRecencyOption(p.feedRecency).id,   // unknown id → "all"
    tickerScrollPct: clampTickerSpeedPct(p.tickerScrollPct ?? DEFAULT_VIEW_PREFS.tickerScrollPct),
    tickerMotion: tickerMotionOption(p.tickerMotion),   // unknown → web default "crawl"
    hidden: Array.isArray(p.hidden) ? p.hidden.map(String) : [],
    hiddenLeagues: Array.isArray(p.hiddenLeagues) ? p.hiddenLeagues.map(String) : [],
    assignments: (p.assignments && typeof p.assignments === "object") ? p.assignments : {},
  };
}

/**
 * Serialize the live prefs object (with denylists as Sets) into the plain,
 * JSON-storable, normalized shape (denylists as arrays). Round-trips with
 * `normalizeViewPrefs`: normalize(serialize(prefs)) === the canonical prefs.
 * Pure.
 */
export function serializeViewPrefs(prefs) {
  const p = prefs ?? {};
  return normalizeViewPrefs({
    gridRows: p.gridRows, gridCols: p.gridCols,
    feedPct: p.feedPct, feedFont: p.feedFont,
    tickerNews: p.tickerNews, feedRecency: p.feedRecency,
    tickerScrollPct: p.tickerScrollPct,
    tickerMotion: p.tickerMotion,
    hidden: p.hidden instanceof Set ? [...p.hidden] : p.hidden,
    hiddenLeagues: p.hiddenLeagues instanceof Set ? [...p.hiddenLeagues] : p.hiddenLeagues,
    assignments: p.assignments,
  });
}

// ----- browser playability hint (mixed-content / CORS reality) -----

/**
 * Whether a channel can play IN THE BROWSER. The helper serves the web
 * client over HTTPS; browsers block an HTTPS page from loading http://
 * stream sub-resources ("mixed content"), and hls.js needs CORS-allowed
 * manifests. The native ExoPlayer has neither limit, so every resolvable
 * channel plays on the TV wall — only a subset plays in the browser.
 *
 * The helper classifies the scheme server-side and sends `browser_playable`
 * (true = HTTPS-clean chain, false = http:// sub-resource found, null =
 * not yet classified). This returns a tri-state HINT for the picker:
 *   "yes"   — playable + helper says HTTPS-clean → try to play
 *   "no"    — not playable, OR playable but helper found mixed content →
 *             show the honest "on the TV wall" state, don't attempt
 *   "maybe" — playable but unclassified (browser_playable null) → attempt;
 *             the runtime load-failure is the ultimate honest fallback
 *             (it also catches CORS, which the helper can't predict).
 * Pure. The hint informs the UI; the actual <video> load result is truth.
 */
export function browserPlayability(channel) {
  const { playable } = channelStatus(channel);
  if (!playable) return "no";
  if (channel.browser_playable === true) return "yes";
  if (channel.browser_playable === false) return "no";
  return "maybe";
}

// ----- sectioned channel picker (group by category, mirrors native) -----
//
// The native ChannelPickerOverlay groups channels under category sections in a
// fixed order; the helper now serves each channel's `category` on /api/channels
// (the SAME taxonomy, derived from the slug — see helper channels/category.py),
// so the web picker sections by it identically instead of showing a flat list.

/** Section render order — EXACTLY the native ChannelCategory.ORDER. A channel
 *  whose served category isn't one of these (missing field on an old helper, or
 *  an unknown value) buckets into "General" so it's never dropped from the picker. */
export const CHANNEL_CATEGORY_ORDER = ["Sports", "US News", "Global News", "Business", "Weather", "General"];

/** The section a channel belongs to. Trusts the helper's served `category`
 *  (authoritative, mirrors native) when it's a known section; otherwise falls
 *  back to "General" — the same fallback native uses for an unmapped slug, so we
 *  never invent a category nor lose a channel. Pure. */
export function channelCategory(channel) {
  const c = channel && typeof channel.category === "string" ? channel.category : "";
  return CHANNEL_CATEGORY_ORDER.includes(c) ? c : "General";
}

/**
 * Group channels into ordered sections by category, mirroring native's
 * ChannelCategory.sectioned: order by CHANNEL_CATEGORY_ORDER, and OMIT empty
 * sections (no empty headers). Within a section, input order is preserved — the
 * caller pre-sorts (live-first, then alpha) so each section reads live-first,
 * exactly as native does (the grouping never reorders). Pure — unit-tested.
 * Returns [{ category, channels: [...] }, ...].
 */
export function sectionChannels(channels) {
  const list = Array.isArray(channels) ? channels : [];
  const buckets = new Map();
  for (const ch of list) {
    const cat = channelCategory(ch);
    if (!buckets.has(cat)) buckets.set(cat, []);
    buckets.get(cat).push(ch);
  }
  return CHANNEL_CATEGORY_ORDER
    .filter((cat) => buckets.has(cat))
    .map((cat) => ({ category: cat, channels: buckets.get(cat) }));
}

// ----- in-browser video auto-recovery (retry transient, give up on hopeless) -----
//
// An always-on wall can't leave a tile dead until a manual reload. A RETRYABLE
// drop (transient network/stream hiccup, was-playing-then-dropped) reconnects on
// a steady ~15s cadence for up to a ~3-min window (~12 attempts), then HONESTLY
// gives up to the persistent state (the centered ↻ for manual retry). A
// genuinely-unplayable failure (DRM / unsupported codec / native-ExoPlayer-only)
// NEVER auto-retries — looping forever on a stream the browser fundamentally
// can't play is its own bad behaviour (wasted cycles, flicker, never succeeds).
// These are pure so the classification + the reconnect schedule are unit-tested
// against the real hls.js/native failure signals.

/** Fixed reconnect cadence + total window for a RETRYABLE drop. Polling (a steady
 *  interval) suits a transient outage that may clear at any time, better than an
 *  exponential backoff that would wait minutes between late attempts. Named for
 *  tunability. */
export const VIDEO_RECONNECT_INTERVAL_MS = 15_000;   // reconnect every ~15s
export const VIDEO_RECONNECT_WINDOW_MS = 180_000;    // for up to a ~3-min window
export const VIDEO_MAX_RECONNECTS = Math.round(VIDEO_RECONNECT_WINDOW_MS / VIDEO_RECONNECT_INTERVAL_MS); // ~12

/**
 * Classify a video failure signal into RETRYABLE (transient — worth a backoff
 * retry) vs genuinely UNPLAYABLE-in-browser (native-ExoPlayer territory — never
 * retry). `kind` is the hls.js ErrorType ("networkError"/"mediaError"/
 * "keySystemError"/"muxError"/"otherError"), or "native" (a <video> error code
 * in `details`), or "stall" (the watchdog), or "no-hls-support". `details` is the
 * hls.js ErrorDetails or the native error-code name. Returns { retryable, reason }.
 */
export function classifyVideoFailure(kind, details = "") {
  const k = String(kind || "");
  const d = String(details || "").toLowerCase();
  // --- genuinely unplayable in a browser (do NOT retry) ---
  if (k === "no-hls-support") return { retryable: false, reason: "no-browser-hls" };
  if (k === "keySystemError" || d.includes("key")) return { retryable: false, reason: "drm" };
  if (d.includes("incompatiblecodecs") || d.includes("incompatible_codecs")) {
    return { retryable: false, reason: "codec" };
  }
  if (k === "muxError") return { retryable: false, reason: "remux" };
  if (k === "native" && d.includes("src_not_supported")) {
    return { retryable: false, reason: "unsupported-source" };
  }
  // --- transient (retry on a backoff) ---
  if (k === "networkError" || k === "mediaError" || k === "stall" || k === "native") {
    return { retryable: true, reason: k };
  }
  // Unknown failure → conservatively retryable, but still BOUNDED by the cap
  // (videoRetryDecision) so an unknown-but-hopeless stream can't loop forever.
  return { retryable: true, reason: k || "unknown" };
}

/**
 * Decide what to do after a failure: schedule the next reconnect (a fixed
 * [VIDEO_RECONNECT_INTERVAL_MS] poll) or give up to the honest persistent state.
 * NEVER retries a non-retryable failure (the crux); NEVER retries past
 * `maxRetries` (so a flaky stream can't poll forever — it gives up after the
 * window). Returns { retry, delayMs?, reason }.
 */
export function videoRetryDecision(attempt, classification, maxRetries = VIDEO_MAX_RECONNECTS) {
  const c = classification || { retryable: false, reason: "unknown" };
  if (!c.retryable) return { retry: false, reason: c.reason };
  if (Math.floor(Number(attempt) || 0) >= maxRetries) return { retry: false, reason: "exhausted" };
  return { retry: true, delayMs: VIDEO_RECONNECT_INTERVAL_MS, reason: c.reason };
}

// ----- feed source filter (browser-local view pref, mirrors the wall) -----

/**
 * Drop items whose source is in the hidden-set (case-insensitive). A
 * DENYLIST — new sources show by default, same semantics as the native
 * wall's hiddenSources. Pure; the web settings persist the set in
 * localStorage (a view pref, not the TV's setting — the web client can't
 * write the TV's on-device settings).
 */
export function filterHiddenSources(items, hiddenSet) {
  if (!hiddenSet || hiddenSet.size === 0) return items ?? [];
  const lower = new Set([...hiddenSet].map((s) => String(s).toLowerCase()));
  return (items ?? []).filter((it) => {
    const key = (it.source && it.source.trim()) ? it.source : "Unknown source";
    return !lower.has(key.toLowerCase());
  });
}

// ----- sports-league filter (browser-local view pref, mirrors the wall) -----

/**
 * The league label a sports ticker ENTRY belongs to — the same key the
 * native `HelperTickerSource.filterLeagues` matches on: the team game's
 * `game.league` when present (the value the card groups + labels by),
 * falling back to the entry `symbol`, so a hidden league can't leak
 * through a symbol/league divergence. Individual-sport entries (UFC/PGA/
 * etc.) have NO `game`, so they key on `symbol` (which the helper sets to
 * the league) — matching native, which also falls back to symbol there.
 * Pure; returns the raw (un-lowercased) label for display.
 */
export function entryLeague(entry) {
  const e = entry ?? {};
  if (e.game && typeof e.game === "object" && e.game.league != null && String(e.game.league).trim()) {
    return String(e.game.league);
  }
  return String(e.symbol ?? "");
}

/**
 * The distinct league labels present in a list of sports ticker entries,
 * alphabetical (case-insensitive) — the pool the Sports-leagues filter UI
 * offers as toggles. Markets/news "leagues" never appear here because this
 * is fed only the SPORTS envelope's entries. Blank labels are dropped (an
 * unlabelled honest "no games" cell is not a togglable league). Pure.
 *
 * Honest: the pool is derived from what the helper actually serves, so a
 * NEWLY-appearing league shows up here and — being absent from the
 * denylist — is shown by default (denylist semantics, matching native).
 */
export function leaguePool(entries) {
  const seen = new Map();   // lower → first-seen original-case label
  for (const entry of entries ?? []) {
    const label = entryLeague(entry).trim();
    if (label === "") continue;
    const lower = label.toLowerCase();
    if (!seen.has(lower)) seen.set(lower, label);
  }
  return [...seen.values()].sort((a, b) => a.toLowerCase().localeCompare(b.toLowerCase()));
}

/**
 * Drop sports ticker entries whose league is in the hidden-set
 * (case-insensitive) — a DENYLIST, exactly mirroring the native
 * `filterLeagues`: match on `game.league ?? symbol`, lowercased. New
 * leagues show by default. The league filter is TV-SIDE in native (the
 * helper still serves all leagues); the web filters client-side here,
 * identically, so the two clients agree on what's shown.
 *
 * Honest: filtering is the ONLY transform — it never fabricates, reorders,
 * or upgrades a sample/stale entry; a kept entry's flags ride through
 * untouched. Pure.
 */
export function filterHiddenLeagues(entries, hiddenSet) {
  if (!hiddenSet || hiddenSet.size === 0) return entries ?? [];
  const lower = new Set([...hiddenSet].map((s) => String(s).toLowerCase()));
  return (entries ?? []).filter((e) => !lower.has(entryLeague(e).toLowerCase()));
}

// ----- feed recency window (browser-local view pref, mirrors the wall) -----

/**
 * The feed recency presets the web offers, mirroring the native
 * `FeedRecency` enum: `All` (no bound) + three bounded windows. `maxAgeMs`
 * null means "no bound" (everything the helper retains). Pure data; the
 * settings <select> renders these and persists the chosen `id`.
 */
export const FEED_RECENCY_OPTIONS = [
  { id: "all", label: "All", maxAgeMs: null },
  { id: "hour", label: "Last hour", maxAgeMs: 60 * 60 * 1000 },
  { id: "six", label: "Last 6h", maxAgeMs: 6 * 60 * 60 * 1000 },
  { id: "day", label: "Last 24h", maxAgeMs: 24 * 60 * 60 * 1000 },
];

/** Look up a recency option by id; unknown id → `All` (the safe default). */
export function feedRecencyOption(id) {
  return FEED_RECENCY_OPTIONS.find((o) => o.id === id) ?? FEED_RECENCY_OPTIONS[0];
}

/**
 * Drop feed items older than `maxAgeMs` (published time preferred, fetched
 * time fallback), mirroring the native `FeedListBuilder` recency window.
 * `maxAgeMs` null → passthrough (the `All` preset). An item with NO
 * parseable timestamp is KEPT under `All` and DROPPED under a bounded
 * window — we can't prove it's recent, exactly as native does (honest: we
 * never present an unproven-recent item inside a "last hour" claim). Pure.
 */
export function filterFeedRecency(items, maxAgeMs, now = Date.now()) {
  if (maxAgeMs == null) return items ?? [];
  return (items ?? []).filter((it) => {
    const ts = Date.parse(itemTimestamp(it));
    if (Number.isNaN(ts)) return false;   // no provable timestamp → not provably recent
    return (now - ts) <= maxAgeMs;
  });
}

/** Playable channels only (live + has a URL) — for the in-browser grid. */
export function playableChannels(channels) {
  return (channels ?? []).filter((c) => channelStatus(c).playable);
}

// ----- feed: group by source, newest-first (mirrors FeedListBuilder) -----

/**
 * Group feed items into source sections, alphabetical (case-insensitive)
 * by source, newest-first within each section. Pure — same ordering the
 * native FeedListBuilder produces, so the two clients read the same.
 * Returns [{ source, items: [...] }].
 */
export function groupBySource(items) {
  const bySource = new Map();
  for (const it of items ?? []) {
    const key = (it.source && it.source.trim()) ? it.source : "Unknown source";
    if (!bySource.has(key)) bySource.set(key, []);
    bySource.get(key).push(it);
  }
  const sources = [...bySource.keys()].sort((a, b) =>
    a.toLowerCase().localeCompare(b.toLowerCase()));
  return sources.map((source) => ({
    source,
    items: bySource.get(source).slice().sort(byNewestFirst),
  }));
}

function itemTimestamp(it) {
  // Prefer published, fall back to fetched; ISO-8601 sorts lexically as time.
  return (it.published_at && it.published_at.trim()) ||
         (it.fetched_at && it.fetched_at.trim()) || "";
}

function byNewestFirst(a, b) {
  const ta = itemTimestamp(a), tb = itemTimestamp(b);
  if (ta === tb) return (b.id ?? 0) - (a.id ?? 0);
  return ta < tb ? 1 : -1;  // descending (newest first)
}

// ----- relative time (10-ft readable, mirrors native RelativeTime) -----

export function relativeTime(iso, now = Date.now()) {
  if (!iso || !String(iso).trim()) return "";
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return "";
  const deltaSec = Math.floor((now - t) / 1000);
  if (deltaSec < 60) return "now";              // includes small clock skew
  if (deltaSec < 3600) return `${Math.floor(deltaSec / 60)}m`;
  if (deltaSec < 86400) return `${Math.floor(deltaSec / 3600)}h`;
  if (deltaSec < 30 * 86400) return `${Math.floor(deltaSec / 86400)}d`;
  return String(iso).slice(0, 10);              // YYYY-MM-DD
}

// ----- channel status (honest live/offline) -----

/**
 * Honest channel status label. A channel is "live" only if the helper
 * says status=live AND it carries a current_url; anything else is
 * offline/unknown — never shown as playable. Mirrors Channel.isPlayable.
 * Returns { label, playable }.
 */
export function channelStatus(channel) {
  const playable = channel.status === "live" && !!(channel.current_url && String(channel.current_url).trim());
  let label;
  if (playable) {
    label = "live";
  } else if (channel.status === "unknown" || channel.status == null || channel.status === "") {
    // Prober hasn't classified it yet — honestly "unknown", not "offline".
    label = "unknown";
  } else {
    // Known-not-playable: status=unavailable, OR the contradictory
    // status=live-without-url. Either way it won't play → "offline".
    label = "offline";
  }
  return { label, playable };
}

// ----- honest empty / unreachable states -----

/**
 * Feed empty-state text. Never a blank pane that looks broken.
 * fetchOk=false means the helper was unreachable on the last poll.
 */
export function feedEmptyState({ itemCount, stale, fetchOk }) {
  if (itemCount > 0) return "";
  if (!fetchOk) return "Helper unreachable — feed paused.";
  if (stale) return "Feed not updating — sources may be stale.";
  return "Waiting for the first feed sweep…";
}

/**
 * Whether the whole view should show a helper-unreachable banner.
 * Honest: distinguishes "no data yet" from "helper down".
 */
export function helperUnreachable(lastFetchOk, everSucceeded) {
  return everSucceeded === true && lastFetchOk === false;
}
