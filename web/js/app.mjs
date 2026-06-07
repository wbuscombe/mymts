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
  helperUnreachable, filterHiddenSources, groupTickerByLeague, gridLayout,
  GRID_CELL_COUNTS, browserPlayability,
} from "./render.mjs";
import { attachStream } from "./video.mjs";

const FEED_POLL_MS = 60_000, CHANNELS_POLL_MS = 60_000, TICKER_POLL_MS = 60_000;
const MODE_ROTATE_MS = 18_000;       // ticker mode rotation, calm like the wall
const TICKER_PX_PER_SEC = 60;        // marquee scroll speed (calm)

const everOk = { feed: false, channels: false };
const el = (id) => document.getElementById(id);

// ----- view prefs (browser-local; NOT the TV's settings) -----
const PREFS_KEY = "mymts.web.prefs.v2";
const prefs = loadPrefs();
function loadPrefs() {
  const d = { cellCount: 4, feedPct: 32, feedFont: 1, hidden: new Set(), assignments: {} };
  try {
    const p = JSON.parse(localStorage.getItem(PREFS_KEY) || "{}");
    return {
      cellCount: GRID_CELL_COUNTS.includes(p.cellCount) ? p.cellCount : d.cellCount,
      feedPct: typeof p.feedPct === "number" ? p.feedPct : d.feedPct,
      feedFont: typeof p.feedFont === "number" ? p.feedFont : d.feedFont,
      hidden: new Set(Array.isArray(p.hidden) ? p.hidden : []),
      assignments: (p.assignments && typeof p.assignments === "object") ? p.assignments : {},
    };
  } catch { return d; }
}
function savePrefs() {
  try {
    localStorage.setItem(PREFS_KEY, JSON.stringify({
      cellCount: prefs.cellCount, feedPct: prefs.feedPct, feedFont: prefs.feedFont,
      hidden: [...prefs.hidden], assignments: prefs.assignments,
    }));
  } catch { /* localStorage unavailable — view prefs just won't persist */ }
}
function applyPrefs() {
  const layout = gridLayout(prefs.cellCount);
  document.documentElement.style.setProperty("--grid-cols", String(layout.cols));
  document.documentElement.style.setProperty("--grid-rows", String(layout.rows));
  document.documentElement.style.setProperty("--feed-pct", prefs.feedPct + "%");
  document.documentElement.style.setProperty("--feed-font", String(prefs.feedFont));
}

function node(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;   // text only — never markup
  return n;
}

// ----- ticker (scrolling marquee, ESPN-BottomLine league markers) -----
let tickerModes = [];   // [{mode, env}]
let modeIdx = 0;

async function pollTicker() {
  const modes = [];
  for (const [mode, fetcher] of [["MARKETS", api.tickerMarkets], ["SPORTS", api.tickerSports]]) {
    try { modes.push({ mode, env: await fetcher() }); }
    catch { modes.push({ mode, env: null }); }
  }
  tickerModes = modes;
  if (modeIdx >= tickerModes.length) modeIdx = 0;
  renderTicker();
}

function rotateTicker() {
  if (tickerModes.length === 0) return;
  modeIdx = (modeIdx + 1) % tickerModes.length;
  renderTicker();
}

function buildTickerCell(cell) {
  const c = node("span", "t-cell");
  c.appendChild(node("span", "t-val", cell.value));
  if (cell.glyph) c.appendChild(node("span", `t-arrow ${cell.dirClass}`, cell.glyph));
  if (cell.sample) c.appendChild(node("span", "t-pill", "SAMPLE"));
  return c;
}

function buildTickerGroup(group) {
  // The league/market marker shows ONCE, then its cells follow — the
  // redundant per-item league prefix is gone (the operator's ask).
  const g = node("span", "t-group");
  g.appendChild(node("span", "t-league", group.label));
  for (const cell of group.cells) g.appendChild(buildTickerCell(cell));
  return g;
}

function renderTicker() {
  const track = el("ticker-track");
  const cur = tickerModes[modeIdx];
  el("ticker-mode").textContent = cur ? cur.mode : "";
  track.replaceChildren();
  el("ticker-note").textContent = "";
  if (!cur) return;
  if (!cur.env) { track.appendChild(node("span", "t-stale", `${cur.mode.toLowerCase()}: unavailable`)); track.style.animation = "none"; return; }
  // The stale note lives in a FIXED slot outside the scrolling track — if it
  // rode inside the track it would be cloned into both halves (and could show
  // twice at once when content is narrow). Keeping it out leaves the track as
  // two identical halves, so the 0→-50% loop stays seamless.
  el("ticker-note").textContent = tickerStaleNote(cur.env);
  const groups = [];
  for (const group of groupTickerByLeague(cur.env.entries ?? [])) groups.push(buildTickerGroup(group));
  // Duplicate ONLY the groups so the 0→-50% scroll loops seamlessly.
  const all = [...groups, ...groups.map((b) => b.cloneNode(true))];
  all.forEach((b) => track.appendChild(b));
  requestAnimationFrame(() => {
    const half = track.scrollWidth / 2;
    const dur = Math.max(8, half / TICKER_PX_PER_SEC);
    track.style.animation = `ticker-scroll ${dur}s linear infinite`;
  });
}

// ----- feed (agnostic chronological list, source per headline) -----
async function pollFeed() {
  try {
    const snap = await api.feed(120); everOk.feed = true;
    renderFeed(snap.items ?? []);
    rebuildSourceToggles(snap.items ?? []);
  } catch {
    if (helperUnreachable(false, everOk.feed)) el("feed").replaceChildren(node("p", "empty", "Helper unreachable — feed paused."));
  }
}
function renderFeed(rawItems) {
  const items = feedChronological(filterHiddenSources(rawItems, prefs.hidden));
  const root = el("feed"); root.replaceChildren();
  el("feed-status").textContent = `${items.length} items`;
  if (items.length === 0) {
    root.appendChild(node("p", "empty", rawItems.length ? "No items from the selected sources (check Settings)." : "Waiting for the feed…"));
    return;
  }
  // One agnostic river, newest-first across all sources — the source sits
  // next to each headline (the original Onn-box style), not in a section.
  for (const item of items) {
    const row = node("article", "feed-row");
    const meta = node("div", "feed-meta");
    meta.appendChild(node("span", "feed-source", sourceLabel(item)));
    const t = relativeTime(item.published_at || item.fetched_at);
    if (t) meta.appendChild(node("span", "feed-time", t));
    row.appendChild(meta);
    row.appendChild(node("h3", "feed-title", item.title || ""));
    if (item.summary && item.summary.trim()) row.appendChild(node("p", "feed-summary", item.summary));
    root.appendChild(row);
  }
}

// ----- video grid (cell-count, click-to-pick channels) -----
let channelsBySlug = new Map();
let channelList = [];
let cells = [];          // per-index { key, teardown, setState }
let autoFilled = false;

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
  const playableFirst = channelList
    .filter((c) => browserPlayability(c) === "yes" || browserPlayability(c) === "maybe")
    .map((c) => c.slug);
  const layout = gridLayout(prefs.cellCount);
  for (let i = 0; i < layout.count && i < playableFirst.length; i++) prefs.assignments[i] = playableFirst[i];
  autoFilled = true;
  savePrefs();
}

function teardownCells() {
  for (const c of cells) { try { c.teardown && c.teardown(); } catch {} }
  cells = [];
}

/** Rebuild the grid layout (cell count changed) from scratch. */
function buildGrid() {
  teardownCells();
  applyPrefs();
  const grid = el("grid"); grid.replaceChildren();
  const layout = gridLayout(prefs.cellCount);
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
  const layout = gridLayout(prefs.cellCount);
  if (cells.length !== layout.count) { buildGrid(); return; }
  for (const cell of cells) {
    const slug = prefs.assignments[cell.index] || null;
    const ch = slug ? channelsBySlug.get(slug) : null;
    const bp = ch ? browserPlayability(ch) : "none";
    const key = `${slug || "∅"}|${bp}|${ch ? channelStatus(ch).label : "∅"}`;
    if (key === cell.key) continue;   // unchanged — leave the cell (and its video) alone
    if (cell.teardown) { try { cell.teardown(); } catch {} cell.teardown = null; }
    cell.key = key;
    renderCell(cell, slug, ch, bp);
  }
}

function changeChip(cell) {
  const chip = node("span", "tile-change", "click to change");
  cell.el.appendChild(chip);
}

function renderCell(cell, slug, ch, bp) {
  const tile = cell.el;
  tile.replaceChildren();
  tile.onclick = () => openPicker(cell.index);

  if (!slug || !ch) {
    // Empty cell — obvious affordance to pick a channel.
    const s = node("div", "tile-state");
    s.appendChild(node("div", "big", "＋"));
    s.appendChild(node("div", "head", "Add channel"));
    s.appendChild(node("div", "sub", "Click to choose a channel for this cell"));
    tile.appendChild(s);
    return;
  }

  const label = ch.label || ch.slug;
  if (bp === "no") {
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
    return;
  }

  // bp is "yes" or "maybe" → attempt playback. The runtime result is the
  // ground truth: if it can't load (dead/CORS/mixed), we flip to the honest
  // "on the TV wall" state — never a black box shown as live.
  const video = document.createElement("video");
  video.muted = true; video.playsInline = true; video.autoplay = true;
  // Yellow "connecting" during the manifest-fetch/buffer window — honest, not
  // grey "offline" (which is reserved for known-not-playable). Flips on event.
  const dot = node("span", "tile-dot dot-unknown");
  const lab = node("span", "tile-label", label);
  tile.append(video, dot, lab);
  changeChip(cell);

  let playOverlay = null;
  const clearOverlay = () => { if (playOverlay) { playOverlay.remove(); playOverlay = null; } };

  const handle = attachStream(video, ch.current_url, (state) => {
    if (state === "live") {
      dot.className = "tile-dot dot-live"; video.style.visibility = ""; clearOverlay();
      tile.onclick = () => openPicker(cell.index);
    } else if (state === "needgesture") {
      // Autoplay blocked — show a click-to-play affordance; click plays.
      dot.className = "tile-dot dot-unknown";
      if (!playOverlay) {
        playOverlay = node("div", "tile-play");
        playOverlay.appendChild(node("span", "glyph", "▶"));
        tile.appendChild(playOverlay);
      }
      tile.onclick = (e) => { e.stopPropagation(); handle.play(); };
    } else { // "error" — honest, never faked-live
      clearOverlay();
      lab.remove();                                   // avoid showing the name twice
      tile.querySelector(".tile-change")?.remove();
      video.style.display = "none";
      // The helper believed this stream was HTTPS-clean (bp yes/maybe) yet it
      // failed in-browser — likely CORS / geo / transient / dead, NOT a
      // confirmed mixed-content block. Don't over-claim it's live on the TV;
      // the confident "on the TV wall" copy is reserved for the bp==="no"
      // case where the helper actually confirmed live + browser-incompatible.
      dot.className = "tile-dot dot-offline";
      const s = node("div", "tile-state");
      s.appendChild(node("div", "big", "○"));
      s.appendChild(node("div", "head", label));
      s.appendChild(node("div", "sub", "Couldn't play in browser — may be on the TV wall"));
      tile.appendChild(s);
      tile.onclick = () => openPicker(cell.index);
    }
  });
  cell.teardown = handle.teardown;
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

  // Channels: browser-playable first, then TV-only, then offline.
  const order = (c) => {
    const bp = browserPlayability(c);
    if (bp === "yes" || bp === "maybe") return 0;
    if (channelStatus(c).playable) return 1;
    return 2;
  };
  const sorted = channelList.slice().sort((a, b) =>
    order(a) - order(b) || (a.label || a.slug).toLowerCase().localeCompare((b.label || b.slug).toLowerCase()));

  for (const ch of sorted) {
    const meta = pickerMeta(ch);
    const row = node("div", `prow${ch.slug === current ? " assigned" : ""}`);
    row.appendChild(node("span", `dot ${meta.dot}`));
    row.appendChild(node("span", "pname", ch.label || ch.slug));
    row.appendChild(node("span", "pstatus", meta.text));
    row.onclick = () => assignCell(ch.slug);
    root.appendChild(row);
  }
}

function assignCell(slug) {
  if (pickerCell == null) return;
  if (slug) prefs.assignments[pickerCell] = slug;
  else delete prefs.assignments[pickerCell];
  savePrefs();
  el("picker-modal").classList.add("hidden");
  renderGrid();
}

// ----- settings modal + source toggles -----
function rebuildSourceToggles(items) {
  const root = el("source-toggles"); if (!root) return;
  const sources = [...new Set(items.map((i) => sourceLabel(i)))]
    .sort((a, b) => a.toLowerCase().localeCompare(b.toLowerCase()));
  root.replaceChildren();
  for (const s of sources) {
    const lab = node("label");
    const cb = document.createElement("input"); cb.type = "checkbox"; cb.checked = !prefs.hidden.has(s);
    cb.addEventListener("change", () => {
      if (cb.checked) prefs.hidden.delete(s); else prefs.hidden.add(s);
      savePrefs(); pollFeed();
    });
    lab.append(cb, node("span", null, s));
    root.appendChild(lab);
  }
}

function closeModal(id) { el(id).classList.add("hidden"); }

function wireSettings() {
  el("gear").addEventListener("click", () => el("settings-modal").classList.remove("hidden"));
  el("settings-close").addEventListener("click", () => closeModal("settings-modal"));
  el("settings-modal").addEventListener("click", (e) => { if (e.target === el("settings-modal")) closeModal("settings-modal"); });
  el("picker-close").addEventListener("click", () => closeModal("picker-modal"));
  el("picker-modal").addEventListener("click", (e) => { if (e.target === el("picker-modal")) closeModal("picker-modal"); });

  const cellCount = el("cell-count"); cellCount.value = String(prefs.cellCount);
  cellCount.addEventListener("change", () => {
    prefs.cellCount = GRID_CELL_COUNTS.includes(Number(cellCount.value)) ? Number(cellCount.value) : 4;
    savePrefs(); buildGrid();
  });

  const feedWidth = el("feed-width"); feedWidth.value = String(prefs.feedPct);
  feedWidth.addEventListener("input", () => { prefs.feedPct = Number(feedWidth.value); applyPrefs(); savePrefs(); });

  const feedFont = el("feed-font"); feedFont.value = String(prefs.feedFont);
  feedFont.addEventListener("change", () => { prefs.feedFont = Number(feedFont.value); applyPrefs(); savePrefs(); });
}

function startLoop(fn, ms) { fn(); return setInterval(fn, ms); }
function main() {
  applyPrefs();
  wireSettings();
  buildGrid();
  startLoop(pollTicker, TICKER_POLL_MS);
  startLoop(pollFeed, FEED_POLL_MS);
  startLoop(pollChannels, CHANNELS_POLL_MS);
  setInterval(rotateTicker, MODE_ROTATE_MS);
}
document.addEventListener("DOMContentLoaded", main);
