// MyMTS Wall Control (/control/) — the headless-container version's picker.
//
// The wall's layout in a browser, but each cell is a FEED-PICKER + per-cell
// audio + subtitle controls — NO video decode (lightweight; runs on a phone).
// Every change writes the server-side wall config (PUT /api/wall); the rendered
// wall (/app/) reads that config, so a pick here drives playback there.
//
// Shares the bundled pure logic with /app/ via absolute imports (served by the
// /app static mount): the API client, the category grouping + status helpers,
// and the pure config transforms (wallConfig.mjs). DOM text is written via
// textContent only (no helper string becomes markup); this surface fetches only
// the helper's own JSON (A1 boundary unchanged).

import { api } from "/app/js/api.mjs";
import {
  sectionChannels, channelStatus, browserPlayability, clampGridDim,
} from "/app/js/render.mjs";
import {
  normalizeConfig, withCellChannel, withCellSubtitles, withAudibleCell,
  withLayout, withPreset, cellCount, withCellReload, withWallReload, withResolution,
  withFeedPct, withFeedFont, withTickerScale,
  RENDER_RESOLUTIONS, RESOLUTION_INFO, FEED_PCT, FEED_FONT, TICKER_SCALE,
} from "/app/js/wallConfig.mjs";

const CHANNELS_POLL_MS = 60_000;
const el = (id) => document.getElementById(id);

let config = null;              // the normalized server wall config (source of truth)
let stored = false;             // false = a server default not yet customised
let channels = [];
let channelsBySlug = new Map();
let validSlugs = new Set();
let presets = [];

function node(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;   // text only — never markup
  return n;
}

function setStatus(text, kind = "") {
  const s = el("status");
  if (!s) return;
  s.textContent = text;
  s.className = `status${kind ? " status--" + kind : ""}`;
}

// ----- load -----

async function loadAll() {
  try {
    const [wall, chSnap, presetSnap] = await Promise.all([
      api.wall(), api.channels(), api.presets().catch(() => null),
    ]);
    channels = chSnap.channels ?? [];
    channelsBySlug = new Map(channels.map((c) => [c.slug, c]));
    validSlugs = new Set(channels.map((c) => c.slug));
    presets = presetSnap && Array.isArray(presetSnap.presets) ? presetSnap.presets : [];
    stored = wall.stored === true;
    config = normalizeConfig(wall, validSlugs);
    populatePresets();
    render();
    setStatus(stored ? "loaded" : "showing default (unsaved)", stored ? "ok" : "muted");
  } catch (e) {
    setStatus("helper unreachable — retrying…", "err");
  }
}

/** Refresh just the channel list (status/new channels) WITHOUT clobbering an
 *  in-flight edit — the wall config is owned here, so we only re-pull channels. */
async function refreshChannels() {
  try {
    const snap = await api.channels();
    channels = snap.channels ?? [];
    channelsBySlug = new Map(channels.map((c) => [c.slug, c]));
    validSlugs = new Set(channels.map((c) => c.slug));
    if (config) render();
  } catch { /* keep last; next tick retries */ }
}

// ----- save (every edit persists; the rendered wall then reflects it) -----

/** Apply a pure config transform optimistically, then PUT. On rejection, show
 *  the helper's reason and reload the authoritative server config (revert).
 *  Concurrency is last-write-wins on the server config: if two surfaces edit at
 *  once, both PUTs apply in order and each side re-reads server truth on its
 *  next poll/save — eventual-consistency for a single-operator tool (no locking,
 *  no data loss; the latest save is authoritative). */
async function commit(nextConfig, what) {
  config = normalizeConfig(nextConfig, validSlugs);
  render();
  setStatus("saving…", "muted");
  try {
    const saved = await api.putWall(config);
    stored = saved.stored === true;
    config = normalizeConfig(saved, validSlugs);
    render();
    setStatus(`saved ${what}`, "ok");
  } catch (e) {
    setStatus(`couldn't save: ${e.message}`, "err");
    await loadAll();   // revert to the server's truth
  }
}

// ----- preset + layout controls -----

function populatePresets() {
  const sel = el("preset");
  if (!sel) return;
  sel.replaceChildren(node("option", null, "— apply a preset —"));
  sel.firstChild.value = "";
  for (const p of presets) {
    const o = node("option", null, p.name || p.id);
    o.value = p.id;
    sel.appendChild(o);
  }
  sel.value = "";
}

/** Annotation for a resolution rung: canvas + sustainable fps + smoothness zone. */
function resolutionNote(name) {
  const i = RESOLUTION_INFO[name] || RESOLUTION_INFO["1080p"];
  return `${name} · ${i.w}×${i.h} · ~${i.fps}fps · ${i.zone}`;
}

function syncLayoutControls() {
  el("rows").value = String(clampGridDim(config.layout.rows));
  el("cols").value = String(clampGridDim(config.layout.cols));
  const count = cellCount(config);
  el("grid-note").textContent = `${config.layout.rows} × ${config.layout.cols} = ${count} cell${count === 1 ? "" : "s"}`;
  // Display sliders reflect the stored config (a slider's position IS the value).
  const resName = RENDER_RESOLUTIONS.includes(config.render?.resolution) ? config.render.resolution : "1080p";
  setSlider("resolution", RENDER_RESOLUTIONS.indexOf(resName), "resolution-note", resolutionNote(resName));
  setSlider("feed-width", config.feed_pct, "feed-width-val", `${Math.round(config.feed_pct)}%`);
  setSlider("feed-font", config.feed_font, "feed-font-val", `${Number(config.feed_font).toFixed(2)}×`);
  setSlider("ticker-height", config.ticker_scale, "ticker-height-val", `${Number(config.ticker_scale).toFixed(2)}×`);
}

/** Set a slider's value + its live label (no-op if the element is absent). */
function setSlider(id, value, labelId, labelText) {
  const s = el(id);
  if (s) s.value = String(value);
  const lab = el(labelId);
  if (lab) lab.textContent = labelText;
}

// ----- the picker cells (the heart of the surface) -----

function statusHint(ch) {
  if (!ch) return { dot: "dot-empty", text: "empty" };
  // A radar WIDGET is an available source, not a probed-live video — describe it
  // honestly (the real freshness is the rendered tile's runtime state).
  if (ch.kind === "weather-radar") return { dot: "dot-live", text: "radar loop" };
  const { label, playable } = channelStatus(ch);
  const bp = browserPlayability(ch);
  if (bp === "yes") return { dot: "dot-live", text: "live" };
  if (bp === "maybe") return { dot: "dot-unknown", text: "live" };
  if (playable) return { dot: "dot-tvonly", text: "live · TV wall" };
  if (label === "unknown") return { dot: "dot-unknown", text: "checking…" };
  return { dot: "dot-offline", text: "offline" };
}

/** A <select> of channels grouped by the server-authoritative categories (the
 *  same sectioning the /app/ picker + the native picker use), with an "(empty)"
 *  option first and the cell's current channel selected. */
function channelPicker(index) {
  const sel = node("select", "cell-picker");
  sel.setAttribute("aria-label", `Channel for cell ${index + 1}`);
  const current = config.cells[index].channel;
  const empty = node("option", null, "— empty —");
  empty.value = "";
  if (!current) empty.selected = true;
  sel.appendChild(empty);
  // Pre-sort live-first then alpha (parity with the /app/ picker), then group.
  const order = (c) => {
    const bp = browserPlayability(c);
    if (bp === "yes" || bp === "maybe") return 0;
    if (channelStatus(c).playable) return 1;
    return 2;
  };
  const sorted = channels.slice().sort((a, b) =>
    order(a) - order(b) || (a.label || a.slug).toLowerCase().localeCompare((b.label || b.slug).toLowerCase()));
  for (const section of sectionChannels(sorted)) {
    const group = document.createElement("optgroup");
    group.label = section.category;
    for (const ch of section.channels) {
      const o = node("option", null, ch.label || ch.slug);
      o.value = ch.slug;
      if (ch.slug === current) o.selected = true;
      group.appendChild(o);
    }
    sel.appendChild(group);
  }
  sel.addEventListener("change", () => commit(withCellChannel(config, index, sel.value || null), `cell ${index + 1}`));
  return sel;
}

function renderCell(index) {
  const cell = config.cells[index];
  const ch = cell.channel ? channelsBySlug.get(cell.channel) : null;
  const hint = statusHint(ch);
  const isAudible = config.audible_cell === index;
  const hasChannel = !!cell.channel;

  const card = node("div", `cell${isAudible ? " cell--audible" : ""}`);

  const head = node("div", "cell-head");
  head.appendChild(node("span", "cell-num", `Cell ${index + 1}`));
  const st = node("span", "cell-status");
  st.appendChild(node("span", `dot ${hint.dot}`));
  st.appendChild(node("span", "cell-status-text", hint.text));
  head.appendChild(st);
  card.appendChild(head);

  card.appendChild(channelPicker(index));

  // Per-cell audio + subtitle controls — disabled (and the toggles inert) when
  // the cell is empty, matching the native model (no stream → nothing to voice
  // or caption). Audio is the single-audible-cell model: exactly one cell, or
  // none, carries audio across the whole wall.
  const controls = node("div", "cell-controls");

  const audioBtn = node("button", `pill${isAudible ? " pill--on" : ""}`, isAudible ? "🔊 Audio on" : "🔇 Audio");
  audioBtn.type = "button";
  audioBtn.disabled = !hasChannel;
  audioBtn.title = hasChannel ? "Make this cell the wall's audio (others muted)" : "Assign a channel first";
  audioBtn.addEventListener("click", () => commit(withAudibleCell(config, index), "audio"));
  controls.appendChild(audioBtn);

  const subBtn = node("button", `pill${cell.subtitles ? " pill--on" : ""}`, cell.subtitles ? "💬 Subtitles on" : "💬 Subtitles");
  subBtn.type = "button";
  subBtn.disabled = !hasChannel;
  subBtn.title = hasChannel ? "Toggle subtitles for this cell" : "Assign a channel first";
  subBtn.addEventListener("click", () => commit(withCellSubtitles(config, index), "subtitles"));
  controls.appendChild(subBtn);

  // Per-cell force-reload: bump this cell's reload epoch so the rendered wall
  // re-attaches just this tile's player (re-resolving a fresh stream URL) —
  // recovers a single wedged/dead tile without disturbing the rest of the wall.
  const reloadBtn = node("button", "pill", "↻ Reload");
  reloadBtn.type = "button";
  reloadBtn.disabled = !hasChannel;
  reloadBtn.title = hasChannel ? "Reload just this tile on the wall" : "Assign a channel first";
  reloadBtn.addEventListener("click", () => commit(withCellReload(config, index), `reload cell ${index + 1}`));
  controls.appendChild(reloadBtn);

  card.appendChild(controls);
  return card;
}

function render() {
  if (!config) return;
  syncLayoutControls();
  const root = el("cells");
  root.replaceChildren();
  root.style.setProperty("--cols", String(clampGridDim(config.layout.cols)));
  for (let i = 0; i < cellCount(config); i++) root.appendChild(renderCell(i));
}

// ----- wire -----

function wire() {
  el("rows").addEventListener("change", (e) =>
    commit(withLayout(config, Number(e.target.value), config.layout.cols), "layout"));
  el("cols").addEventListener("change", (e) =>
    commit(withLayout(config, config.layout.rows, Number(e.target.value)), "layout"));
  // ---- fine-grained DISPLAY sliders (all small-step, gradual) ----
  // Each: 'input' updates the live label only (no commit-per-step); 'change'
  // (on release) commits ONE config write. Resolution especially must commit only
  // on release — each commit restarts the renderer, so we never restart per step.
  const resSlider = el("resolution");
  if (resSlider) {
    resSlider.addEventListener("input", (e) => {
      const name = RENDER_RESOLUTIONS[Number(e.target.value)] || "1080p";
      const lab = el("resolution-note"); if (lab) lab.textContent = resolutionNote(name);
    });
    resSlider.addEventListener("change", (e) => {
      const name = RENDER_RESOLUTIONS[Number(e.target.value)] || "1080p";
      commit(withResolution(config, name), `resolution ${name}`);
    });
  }
  const liveLabel = (sliderId, labelId, fmt) => {
    const s = el(sliderId), lab = el(labelId);
    if (s && lab) s.addEventListener("input", () => { lab.textContent = fmt(Number(s.value)); });
  };
  liveLabel("feed-width", "feed-width-val", (v) => `${Math.round(v)}%`);
  liveLabel("feed-font", "feed-font-val", (v) => `${v.toFixed(2)}×`);
  liveLabel("ticker-height", "ticker-height-val", (v) => `${v.toFixed(2)}×`);
  const onRelease = (sliderId, transform, what) => {
    const s = el(sliderId);
    if (s) s.addEventListener("change", () => commit(transform(config, Number(s.value)), what));
  };
  onRelease("feed-width", withFeedPct, "feed width");
  onRelease("feed-font", withFeedFont, "feed font");
  onRelease("ticker-height", withTickerScale, "ticker height");
  el("preset").addEventListener("change", (e) => {
    const p = presets.find((x) => x.id === e.target.value);
    if (!p) return;
    commit(withPreset(config, p, validSlugs), `preset “${p.name || p.id}”`);
    e.target.value = "";   // it's an action, not a persistent selection
  });
  // Whole-wall force-reload: bump the wall reload epoch so the rendered wall
  // re-attaches EVERY video tile — recovers a wall that's silently degraded
  // (several wedged tiles) in one tap, the remote analog of /app/'s header ↻.
  const reloadAll = el("reload-all");
  if (reloadAll) reloadAll.addEventListener("click", () => {
    if (!config) return;
    commit(withWallReload(config), "reload all tiles");
  });
}

function main() {
  wire();
  loadAll();
  setInterval(refreshChannels, CHANNELS_POLL_MS);
}
document.addEventListener("DOMContentLoaded", main);
