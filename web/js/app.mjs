// MyMTS LAN web client — DOM wiring + polling.
//
// A dumb consumer of the helper API, mirroring the native app's surfaces
// (feed, ticker, channels, health) with the SAME honesty discipline. All
// honesty/label/group logic lives in render.mjs (pure, tested); this file
// only fetches on a timer and writes the results into the DOM AS TEXT
// (textContent, never innerHTML) — so no helper string can ever be
// interpreted as markup. A1 holds: no article fetch, no iframe, no HTML.

import { api } from "./api.mjs";
import {
  tickerRow, tickerStaleNote, groupBySource, relativeTime,
  channelStatus, feedEmptyState, helperUnreachable,
} from "./render.mjs";

const FEED_POLL_MS = 60_000;
const CHANNELS_POLL_MS = 30_000;
const TICKER_POLL_MS = 60_000;

// Track ever-succeeded per surface so we can distinguish "loading" from
// "helper down" honestly (helperUnreachable()).
const everOk = { feed: false, channels: false };

function el(id) { return document.getElementById(id); }

// Create an element with text content set safely (never innerHTML).
function node(tag, className, text) {
  const n = document.createElement(tag);
  if (className) n.className = className;
  if (text != null) n.textContent = text;   // text only — no markup path
  return n;
}

function setStatus(text, kind) {
  const s = el("status");
  s.textContent = text;
  s.className = "status " + (kind || "");
}

// ----- feed -----

async function refreshFeed() {
  try {
    const snap = await api.feed(120);
    everOk.feed = true;
    const items = snap.items ?? [];
    renderFeed(items);
  } catch (e) {
    renderFeedError();
  }
}

function renderFeed(items) {
  const root = el("feed");
  root.replaceChildren();
  const empty = feedEmptyState({ itemCount: items.length, stale: false, fetchOk: true });
  if (empty) { root.appendChild(node("p", "empty", empty)); return; }

  for (const section of groupBySource(items)) {
    const header = node("div", "section-header");
    header.appendChild(node("span", "section-source", section.source.toUpperCase()));
    header.appendChild(node("span", "section-count", `${section.items.length} ${section.items.length === 1 ? "item" : "items"}`));
    root.appendChild(header);

    for (const item of section.items) {
      const row = node("article", "feed-row");
      const meta = node("div", "feed-meta");
      const t = relativeTime(item.published_at || item.fetched_at);
      if (t) meta.appendChild(node("span", "feed-time", t));
      row.appendChild(meta);
      row.appendChild(node("h3", "feed-title", item.title || ""));
      if (item.summary && item.summary.trim()) {
        // Plain-text summary the helper already stripped of HTML. Rendered
        // as textContent — never as markup. No link is followed, no
        // article page is fetched (A1 closed door).
        row.appendChild(node("p", "feed-summary", item.summary));
      }
      root.appendChild(row);
    }
  }
}

function renderFeedError() {
  const root = el("feed");
  if (helperUnreachable(false, everOk.feed)) {
    root.replaceChildren(node("p", "empty", "Helper unreachable — feed paused."));
  } else if (root.childElementCount === 0) {
    root.replaceChildren(node("p", "empty", "Waiting for the helper…"));
  }
  // else: keep the last good render rather than blanking.
}

// ----- ticker (markets + sports, both shown stacked on the web view) -----

async function refreshTicker() {
  await renderTickerMode("markets", api.tickerMarkets, el("ticker-markets"));
  await renderTickerMode("sports", api.tickerSports, el("ticker-sports"));
}

async function renderTickerMode(mode, fetcher, root) {
  try {
    const env = await fetcher();
    root.replaceChildren();
    const note = tickerStaleNote(env);
    if (note) root.appendChild(node("span", "ticker-stale", note));
    for (const entry of env.entries ?? []) {
      const r = tickerRow(entry);
      const cell = node("span", "ticker-cell");
      cell.appendChild(node("span", "ticker-symbol", r.symbol));
      cell.appendChild(node("span", "ticker-value", r.value));
      if (r.glyph) cell.appendChild(node("span", `ticker-arrow ${r.dirClass}`, r.glyph));
      if (r.sample) cell.appendChild(node("span", "pill", "SAMPLE"));  // honesty pill
      root.appendChild(cell);
    }
  } catch (e) {
    root.replaceChildren(node("span", "ticker-stale", `${mode}: unavailable`));
  }
}

// ----- channels -----

async function refreshChannels() {
  try {
    const snap = await api.channels();
    everOk.channels = true;
    renderChannels(snap.channels ?? []);
  } catch (e) {
    if (helperUnreachable(false, everOk.channels)) {
      el("channels").replaceChildren(node("p", "empty", "Helper unreachable."));
    }
  }
}

function renderChannels(channels) {
  const root = el("channels");
  root.replaceChildren();
  for (const ch of channels) {
    const { label, playable } = channelStatus(ch);
    const row = node("div", "channel-row");
    row.appendChild(node("span", `dot ${playable ? "dot-live" : (label === "unknown" ? "dot-unknown" : "dot-offline")}`, ""));
    row.appendChild(node("span", "channel-label", ch.label || ch.slug || "—"));
    row.appendChild(node("span", `channel-status status-${label}`, label));
    root.appendChild(row);
  }
}

// ----- health banner -----

async function refreshHealth() {
  try {
    const h = await api.health();
    const feeds = h.feeds || {};
    const parts = [`helper ok`];
    if (feeds.sources_count != null) parts.push(`${feeds.sources_count} sources`);
    if (feeds.items_count != null) parts.push(`${feeds.items_count} items`);
    setStatus(parts.join(" · "), "ok");
  } catch (e) {
    setStatus("helper unreachable", "down");
  }
}

// ----- loop -----

function startLoop(fn, intervalMs) {
  fn();
  return setInterval(fn, intervalMs);
}

function main() {
  startLoop(refreshHealth, 30_000);
  startLoop(refreshFeed, FEED_POLL_MS);
  startLoop(refreshChannels, CHANNELS_POLL_MS);
  startLoop(refreshTicker, TICKER_POLL_MS);
}

document.addEventListener("DOMContentLoaded", main);
