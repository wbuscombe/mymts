// MyMTS LAN web client — mirrors the Onn wall (scrolling ticker + feed
// pane + 2×2 video grid), mouse-driven settings. A dumb, read-only,
// credential-free consumer of the helper API; all honesty/label/group
// logic is in render.mjs (pure, tested). DOM text is written via
// textContent only (no helper string becomes markup); video is hls.js
// playback of the helper-resolved public streams (A1: playback, not a
// web reader).

import { api } from "./api.mjs";
import {
  tickerRow, tickerStaleNote, groupBySource, relativeTime, channelStatus,
  feedEmptyState, helperUnreachable, filterHiddenSources, playableChannels,
} from "./render.mjs";
import { attachStream } from "./video.mjs";

const FEED_POLL_MS = 60_000, CHANNELS_POLL_MS = 60_000, TICKER_POLL_MS = 60_000;
const MODE_ROTATE_MS = 18_000;       // ticker mode rotation, calm like the wall
const TICKER_PX_PER_SEC = 60;        // marquee scroll speed (calm)

const everOk = { feed: false, channels: false };
const el = (id) => document.getElementById(id);

// ----- view prefs (browser-local; NOT the TV's settings) -----
const PREFS_KEY = "mymts.web.prefs.v1";
const prefs = loadPrefs();
function loadPrefs() {
  try {
    const p = JSON.parse(localStorage.getItem(PREFS_KEY) || "{}");
    return { gridPct: p.gridPct ?? 60, feedFont: p.feedFont ?? 1, hidden: new Set(p.hidden || []) };
  } catch { return { gridPct: 60, feedFont: 1, hidden: new Set() }; }
}
function savePrefs() {
  try {
    localStorage.setItem(PREFS_KEY, JSON.stringify({
      gridPct: prefs.gridPct, feedFont: prefs.feedFont, hidden: [...prefs.hidden],
    }));
  } catch { /* localStorage unavailable — view prefs just won't persist */ }
}
function applyPrefs() {
  document.documentElement.style.setProperty("--grid-pct", prefs.gridPct + "%");
  document.documentElement.style.setProperty("--feed-font", String(prefs.feedFont));
}

function node(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;   // text only — never markup
  return n;
}

// ----- ticker (scrolling marquee, markets ↔ sports rotation) -----
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

function renderTicker() {
  const track = el("ticker-track");
  const cur = tickerModes[modeIdx];
  el("ticker-mode").textContent = cur ? cur.mode : "";
  track.replaceChildren();
  if (!cur) return;
  if (!cur.env) { track.appendChild(node("span", "t-stale", `${cur.mode.toLowerCase()}: unavailable`)); track.style.animation = "none"; return; }
  const note = tickerStaleNote(cur.env);
  const cells = [];
  if (note) cells.push(node("span", "t-stale", note));
  for (const entry of cur.env.entries ?? []) {
    const r = tickerRow(entry);
    const cell = node("span", "t-cell");
    cell.appendChild(node("span", "t-sym", r.symbol));
    cell.appendChild(node("span", "t-val", r.value));
    if (r.glyph) cell.appendChild(node("span", `t-arrow ${r.dirClass}`, r.glyph));
    if (r.sample) cell.appendChild(node("span", "t-pill", "SAMPLE"));
    cells.push(cell);
  }
  // Duplicate the cell set so the 0→-50% scroll loops seamlessly.
  const all = [...cells, ...cells.map((c) => c.cloneNode(true))];
  all.forEach((c) => track.appendChild(c));
  // Speed proportional to content width (calm, constant px/sec).
  requestAnimationFrame(() => {
    const half = track.scrollWidth / 2;
    const dur = Math.max(8, half / TICKER_PX_PER_SEC);
    track.style.animation = `ticker-scroll ${dur}s linear infinite`;
  });
}

// ----- feed -----
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
  const items = filterHiddenSources(rawItems, prefs.hidden);
  const root = el("feed"); root.replaceChildren();
  el("feed-status").textContent = `${items.length} items`;
  if (items.length === 0) {
    root.appendChild(node("p", "empty", rawItems.length ? "No items from the selected sources (check Settings)." : "Waiting for the feed…"));
    return;
  }
  for (const section of groupBySource(items)) {
    const h = node("div", "section-header");
    h.appendChild(node("span", "section-source", section.source.toUpperCase()));
    h.appendChild(node("span", "section-count", `${section.items.length}`));
    root.appendChild(h);
    for (const item of section.items) {
      const row = node("article", "feed-row");
      const t = relativeTime(item.published_at || item.fetched_at);
      if (t) row.appendChild(node("span", "feed-time", t));
      row.appendChild(node("h3", "feed-title", item.title || ""));
      if (item.summary && item.summary.trim()) row.appendChild(node("p", "feed-summary", item.summary));
      root.appendChild(row);
    }
  }
}

// ----- 2×2 video grid -----
let gridSlugs = "";          // current tile set (to avoid needless rebuilds)
let teardowns = [];
async function pollChannels() {
  try {
    const snap = await api.channels(); everOk.channels = true;
    const playable = playableChannels(snap.channels ?? []).slice(0, 4);
    const slugs = playable.map((c) => c.slug).join(",");
    if (slugs !== gridSlugs) { gridSlugs = slugs; buildGrid(playable); }
    renderChannelList(snap.channels ?? []);
  } catch { /* keep last grid; channel list will show stale on next ok */ }
}
function buildGrid(channels) {
  teardowns.forEach((fn) => { try { fn(); } catch {} }); teardowns = [];
  const grid = el("grid"); grid.replaceChildren();
  const slots = channels.slice(0, 4);
  // Always render 4 cells (pad empties) to mirror the wall's 2×2.
  for (let i = 0; i < 4; i++) {
    const ch = slots[i];
    const tile = node("div", "tile");
    if (!ch) { tile.appendChild(node("span", "tile-offline", "—")); grid.appendChild(tile); continue; }
    const dot = node("span", "tile-dot dot-offline");
    const label = node("span", "tile-label", ch.label || ch.slug);
    const video = document.createElement("video");
    video.muted = true; video.playsInline = true; video.autoplay = true;
    const offline = node("span", "tile-offline", "offline");
    offline.style.display = "none";
    tile.append(video, dot, label, offline);
    grid.appendChild(tile);
    const td = attachStream(video, ch.current_url, (state) => {
      if (state === "live") { dot.className = "tile-dot dot-live"; offline.style.display = "none"; }
      else { dot.className = "tile-dot dot-offline"; offline.style.display = ""; video.style.visibility = "hidden"; }
    });
    teardowns.push(td);
  }
}
function renderChannelList(channels) {
  const root = el("channel-list"); if (!root) return; root.replaceChildren();
  for (const ch of channels) {
    const { label, playable } = channelStatus(ch);
    const row = node("div", "crow");
    row.appendChild(node("span", `dot ${playable ? "dot-live" : (label === "unknown" ? "dot-unknown" : "dot-offline")}`));
    row.appendChild(node("span", null, ch.label || ch.slug));
    row.appendChild(node("span", `cstatus status-${label}`, label));
    root.appendChild(row);
  }
}

// ----- settings modal + source toggles + splitter -----
function rebuildSourceToggles(items) {
  const root = el("source-toggles"); if (!root) return;
  const sources = [...new Set(items.map((i) => (i.source && i.source.trim()) ? i.source : "Unknown source"))]
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

function wireSettings() {
  el("gear").addEventListener("click", () => el("settings-modal").classList.remove("hidden"));
  el("settings-close").addEventListener("click", () => el("settings-modal").classList.add("hidden"));
  el("settings-modal").addEventListener("click", (e) => { if (e.target === el("settings-modal")) el("settings-modal").classList.add("hidden"); });

  const gridSize = el("grid-size"); gridSize.value = String(prefs.gridPct);
  gridSize.addEventListener("input", () => { prefs.gridPct = Number(gridSize.value); applyPrefs(); savePrefs(); });

  const feedFont = el("feed-font"); feedFont.value = String(prefs.feedFont);
  feedFont.addEventListener("change", () => { prefs.feedFont = Number(feedFont.value); applyPrefs(); savePrefs(); });

  // Splitter drag → resize the video grid (and persist).
  const splitter = el("splitter"), wall = el("wall");
  let dragging = false;
  splitter.addEventListener("mousedown", () => { dragging = true; document.body.style.userSelect = "none"; });
  window.addEventListener("mouseup", () => { if (dragging) { dragging = false; document.body.style.userSelect = ""; savePrefs(); } });
  window.addEventListener("mousemove", (e) => {
    if (!dragging) return;
    const rect = wall.getBoundingClientRect();
    const gridPct = Math.round(((rect.right - e.clientX) / rect.width) * 100);
    prefs.gridPct = Math.max(30, Math.min(80, gridPct));
    el("grid-size").value = String(prefs.gridPct);
    applyPrefs();
  });
}

function startLoop(fn, ms) { fn(); return setInterval(fn, ms); }
function main() {
  applyPrefs();
  wireSettings();
  startLoop(pollTicker, TICKER_POLL_MS);
  startLoop(pollFeed, FEED_POLL_MS);
  startLoop(pollChannels, CHANNELS_POLL_MS);
  setInterval(rotateTicker, MODE_ROTATE_MS);
}
document.addEventListener("DOMContentLoaded", main);
