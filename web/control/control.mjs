// MyMTS Wall Control (/control/) — the unified panel (wall editor + Outputs).
//
// The wall's layout in a browser, but each cell is a FEED-PICKER + per-cell AUDIO
// + SUBTITLE toggles (multi-audible: any combination unmuted, they mix) — NO video
// decode (lightweight; runs on a phone). The Outputs section drives the multi-output
// fan-out: an HLS/VLC card (live) + a Mercury card (an inert, pre-fillable shell in
// a "needs-setup" state). Every edit writes the server-side wall config (PUT
// /api/wall, partial-merge); the rendered wall + the renderer read that config.
//
// Shares the bundled pure logic with /app/ via absolute imports. DOM text is written
// via textContent only (no helper string becomes markup); fetches only the helper's
// own JSON (A1 boundary unchanged).

import { api } from "/app/js/api.mjs";
import {
  sectionChannels, channelStatus, browserPlayability, clampGridDim,
} from "/app/js/render.mjs";
import {
  normalizeConfig, withCellChannel, withCellSubtitles, withCellAudio,
  withLayout, withPreset, cellCount, withCellReload, withWallReload,
  withFeedPct, withFeedFont, withTickerScale,
  withOutputEnabled, withOutputResolution, withOutputBitrate, withOutputAudio,
  withOutputRestart, withMercuryFields, withDiscordFields,
  RENDER_RESOLUTIONS, RESOLUTION_INFO, MERCURY_MAX_RESOLUTION, bitrateBounds,
  deriveRenderResolution, FEED_PCT, FEED_FONT, TICKER_SCALE,
} from "/app/js/wallConfig.mjs";

const CHANNELS_POLL_MS = 60_000;
const OUTPUTS_POLL_MS = 5_000;
const el = (id) => document.getElementById(id);

let config = null;              // the normalized server wall config (source of truth)
let stored = false;             // false = a server default not yet customised
let channels = [];
let channelsBySlug = new Map();
let validSlugs = new Set();
let presets = [];
let outputsStatus = null;       // GET /api/outputs/status — runtime state + checklist

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
    refreshOutputsStatus();
    setStatus(stored ? "loaded" : "showing default (unsaved)", stored ? "ok" : "muted");
  } catch (e) {
    setStatus("helper unreachable — retrying…", "err");
  }
}

async function refreshChannels() {
  try {
    const snap = await api.channels();
    channels = snap.channels ?? [];
    channelsBySlug = new Map(channels.map((c) => [c.slug, c]));
    validSlugs = new Set(channels.map((c) => c.slug));
    if (config) render();
  } catch { /* keep last; next tick retries */ }
}

/** Poll the per-output runtime status (HLS running/stopped, Mercury state +
 *  setup checklist) WITHOUT disturbing an in-flight config edit — status is
 *  renderer-owned, the config is owned here. */
async function refreshOutputsStatus() {
  try {
    outputsStatus = await api.outputsStatus();
    if (config) renderOutputs();
  } catch { /* keep last; next tick retries */ }
}

// ----- save (every edit persists; the rendered wall then reflects it) -----

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
    refreshOutputsStatus();   // a config change may flip an output's runtime state
  } catch (e) {
    setStatus(`couldn't save: ${e.message}`, "err");
    await loadAll();
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

function resolutionNote(name) {
  const i = RESOLUTION_INFO[name] || RESOLUTION_INFO["1080p"];
  return `${name} · ${i.w}×${i.h} · ~${i.fps}fps · ${i.zone}`;
}

function syncLayoutControls() {
  el("rows").value = String(clampGridDim(config.layout.rows));
  el("cols").value = String(clampGridDim(config.layout.cols));
  const count = cellCount(config);
  el("grid-note").textContent =
    `${config.layout.rows} × ${config.layout.cols} = ${count} cell${count === 1 ? "" : "s"}`;
  // The render canvas is DERIVED from the outputs (max enabled), shown read-only so
  // the relationship is legible ("compositing at 1080p"). The resolution chooser now
  // lives on the output cards.
  const renderRes = deriveRenderResolution(config.outputs);
  if (el("render-derived")) el("render-derived").textContent = `compositing at ${renderRes}`;
  // multi-audio hint
  const audioCount = config.cells.filter((c) => c.audio).length;
  if (el("audio-hint")) {
    el("audio-hint").textContent = audioCount > 1 ? `${audioCount} cells audible — mixed` : "";
  }
  setSlider("feed-width", config.feed_pct, "feed-width-val", `${Math.round(config.feed_pct)}%`);
  setSlider("feed-font", config.feed_font, "feed-font-val", `${Number(config.feed_font).toFixed(2)}×`);
  setSlider("ticker-height", config.ticker_scale, "ticker-height-val",
    `${Number(config.ticker_scale).toFixed(2)}×`);
}

function setSlider(id, value, labelId, labelText) {
  const s = el(id);
  if (s) s.value = String(value);
  const lab = el(labelId);
  if (lab) lab.textContent = labelText;
}

// ----- the picker cells (the wall editor) -----

function statusHint(ch) {
  if (!ch) return { dot: "dot-empty", text: "empty" };
  if (ch.kind === "weather-radar") return { dot: "dot-live", text: "radar loop" };
  const { label, playable } = channelStatus(ch);
  const bp = browserPlayability(ch);
  if (bp === "yes") return { dot: "dot-live", text: "live" };
  if (bp === "maybe") return { dot: "dot-unknown", text: "live" };
  if (playable) return { dot: "dot-tvonly", text: "live · TV wall" };
  if (label === "unknown") return { dot: "dot-unknown", text: "checking…" };
  return { dot: "dot-offline", text: "offline" };
}

function channelPicker(index) {
  const sel = node("select", "cell-picker");
  sel.setAttribute("aria-label", `Channel for cell ${index + 1}`);
  const current = config.cells[index].channel;
  const empty = node("option", null, "— empty —");
  empty.value = "";
  if (!current) empty.selected = true;
  sel.appendChild(empty);
  const order = (c) => {
    const bp = browserPlayability(c);
    if (bp === "yes" || bp === "maybe") return 0;
    if (channelStatus(c).playable) return 1;
    return 2;
  };
  const sorted = channels.slice().sort((a, b) =>
    order(a) - order(b)
    || (a.label || a.slug).toLowerCase().localeCompare((b.label || b.slug).toLowerCase()));
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
  sel.addEventListener("change",
    () => commit(withCellChannel(config, index, sel.value || null), `cell ${index + 1}`));
  return sel;
}

function renderCell(index) {
  const cell = config.cells[index];
  const ch = cell.channel ? channelsBySlug.get(cell.channel) : null;
  const hint = statusHint(ch);
  const hasChannel = !!cell.channel;

  const card = node("div", `cell${cell.audio ? " cell--audible" : ""}`);

  const head = node("div", "cell-head");
  head.appendChild(node("span", "cell-num", `Cell ${index + 1}`));
  const st = node("span", "cell-status");
  st.appendChild(node("span", `dot ${hint.dot}`));
  st.appendChild(node("span", "cell-status-text", hint.text));
  head.appendChild(st);
  card.appendChild(head);

  card.appendChild(channelPicker(index));

  // Per-cell AUDIO + SUBTITLE toggles (multi-audible: ANY combination of audio
  // cells may be on; they mix in the render's sink). Both are per-cell config
  // properties — enabled regardless of channel (pre-settable).
  const controls = node("div", "cell-controls");

  const audioBtn = node("button", `pill${cell.audio ? " pill--on" : ""}`,
    cell.audio ? "🔊 Audio on" : "🔇 Audio");
  audioBtn.type = "button";
  audioBtn.title = "Unmute this cell (multiple may be on → mixed)";
  audioBtn.addEventListener("click", () => commit(withCellAudio(config, index), `cell ${index + 1} audio`));
  controls.appendChild(audioBtn);

  const subBtn = node("button", `pill${cell.subtitles ? " pill--on" : ""}`,
    cell.subtitles ? "💬 Subtitles on" : "💬 Subtitles");
  subBtn.type = "button";
  subBtn.title = "Toggle subtitles for this cell";
  subBtn.addEventListener("click", () => commit(withCellSubtitles(config, index), "subtitles"));
  controls.appendChild(subBtn);

  const reloadBtn = node("button", "pill", "↻ Reload");
  reloadBtn.type = "button";
  reloadBtn.disabled = !hasChannel;
  reloadBtn.title = hasChannel ? "Reload just this tile on the wall" : "Assign a channel first";
  reloadBtn.addEventListener("click",
    () => commit(withCellReload(config, index), `reload cell ${index + 1}`));
  controls.appendChild(reloadBtn);

  card.appendChild(controls);
  return card;
}

// ----- the Outputs section (HLS card + Mercury shell) -----

function resOptions(sel, current, capName) {
  const cap = capName ? RENDER_RESOLUTIONS.indexOf(capName) : RENDER_RESOLUTIONS.length - 1;
  RENDER_RESOLUTIONS.forEach((r, i) => {
    if (i > cap) return;
    const o = node("option", null, resolutionNote(r));
    o.value = r;
    if (r === current) o.selected = true;
    sel.appendChild(o);
  });
}

/** A labelled control row. */
function row(labelText, controlEl) {
  const r = node("label", "output-row");
  r.appendChild(node("span", "output-row-label", labelText));
  r.appendChild(controlEl);
  return r;
}

function hlsCard(o, rt) {
  const card = node("div", "output-card");
  const head = node("div", "output-head");
  head.appendChild(node("span", "output-name", "HLS / VLC"));
  const state = rt.state || (o.enabled ? "stopped" : "disabled");
  head.appendChild(node("span", `output-state state-${state}`, state));
  card.appendChild(head);

  const enableBtn = node("button", `pill${o.enabled ? " pill--on" : ""}`,
    o.enabled ? "On" : "Off");
  enableBtn.type = "button";
  enableBtn.addEventListener("click", () => commit(withOutputEnabled(config, "hls", !o.enabled), "HLS on/off"));
  card.appendChild(row("Enabled", enableBtn));

  const resSel = node("select", "output-res");
  resOptions(resSel, o.resolution, null);
  resSel.addEventListener("change", () => commit(withOutputResolution(config, "hls", resSel.value), "HLS resolution"));
  card.appendChild(row("Resolution", resSel));

  const b = bitrateBounds(o.resolution);
  const brWrap = node("div", "output-bitrate");
  const br = node("input", "output-br-slider");
  br.type = "range"; br.min = String(b.min); br.max = String(b.max); br.step = String(b.step);
  br.value = String(o.bitrate_kbps);
  const brVal = node("span", "output-br-val", `${(o.bitrate_kbps / 1000).toFixed(1)} Mbps`);
  br.addEventListener("input", () => { brVal.textContent = `${(Number(br.value) / 1000).toFixed(1)} Mbps`; });
  br.addEventListener("change", () => commit(withOutputBitrate(config, "hls", Number(br.value)), "HLS bitrate"));
  brWrap.append(br, brVal);
  card.appendChild(row("Bitrate", brWrap));

  const audBtn = node("button", `pill${o.audio ? " pill--on" : ""}`, o.audio ? "🔊 On" : "🔇 Off");
  audBtn.type = "button";
  audBtn.addEventListener("click", () => commit(withOutputAudio(config, "hls", !o.audio), "HLS audio"));
  card.appendChild(row("Audio", audBtn));

  const actions = node("div", "output-actions");
  const startStop = node("button", "pill", o.enabled ? "Stop" : "Start");
  startStop.type = "button";
  startStop.addEventListener("click", () => commit(withOutputEnabled(config, "hls", !o.enabled), o.enabled ? "stop HLS" : "start HLS"));
  const restart = node("button", "pill", "↻ Restart");
  restart.type = "button";
  restart.addEventListener("click", () => commit(withOutputRestart(config, "hls"), "restart HLS"));
  actions.append(startStop, restart);
  card.appendChild(actions);

  const playlist = rt.playlist_path || "/api/stream/playlist.m3u8";
  const urlLine = node("div", "output-url");
  urlLine.appendChild(node("span", "output-url-label", "Playlist"));
  const code = node("code", "output-url-code", playlist);
  urlLine.appendChild(code);
  card.appendChild(urlLine);
  return card;
}

function mercuryCard(o, rt) {
  const card = node("div", "output-card output-card--mercury");
  const head = node("div", "output-head");
  head.appendChild(node("span", "output-name", "Mercury"));
  const state = rt.state || (o.enabled ? "needs_setup" : "disabled");
  head.appendChild(node("span", `output-state state-${state}`, state.replace(/_/g, " ")));
  card.appendChild(head);

  // Honest needs-setup checklist (renderer-computed). Actions stay disabled until
  // every item is satisfied; this build's stub NEVER connects.
  const cl = rt.checklist || {};
  const ready = cl.key_present === true && cl.channel_set === true && cl.tailnet_reachable === true;
  const checklist = node("div", "mercury-checklist");
  const item = (ok, label) => {
    const i = node("div", `check ${ok === true ? "ok" : ok === false ? "bad" : "unknown"}`);
    i.appendChild(node("span", "check-mark", ok === true ? "✓" : ok === false ? "✗" : "?"));
    i.appendChild(node("span", "check-label", label));
    return i;
  };
  checklist.appendChild(item(cl.key_present, "LiveKit key"));
  checklist.appendChild(item(cl.tailnet_reachable, "Tailnet reachable"));
  checklist.appendChild(item(cl.channel_set, "Channel set"));
  card.appendChild(checklist);
  card.appendChild(node("div", "mercury-detail", rt.detail || "Pending credentials (Ryan)"));

  // Pre-fillable config fields (no secrets) — editable NOW so Will can stage ahead.
  const guid = node("input", "mercury-input");
  guid.type = "text"; guid.value = o.channel_guid || ""; guid.placeholder = "channel GUID";
  guid.addEventListener("change", () => commit(withMercuryFields(config, { channel_guid: guid.value.trim() }), "Mercury channel"));
  card.appendChild(row("Channel GUID", guid));

  const dname = node("input", "mercury-input");
  dname.type = "text"; dname.value = o.display_name || ""; dname.placeholder = "display name";
  dname.addEventListener("change", () => commit(withMercuryFields(config, { display_name: dname.value }), "Mercury name"));
  card.appendChild(row("Display name", dname));

  const resSel = node("select", "output-res");
  resOptions(resSel, o.resolution, MERCURY_MAX_RESOLUTION);   // capped ≤1080p
  resSel.addEventListener("change", () => commit(withOutputResolution(config, "mercury", resSel.value), "Mercury resolution"));
  card.appendChild(row("Resolution", resSel));

  const b = bitrateBounds(o.resolution);
  const br = node("input", "output-br-slider");
  br.type = "range"; br.min = String(b.min); br.max = String(b.max); br.step = String(b.step);
  br.value = String(o.bitrate_kbps);
  const brVal = node("span", "output-br-val", `${(o.bitrate_kbps / 1000).toFixed(1)} Mbps`);
  br.addEventListener("input", () => { brVal.textContent = `${(Number(br.value) / 1000).toFixed(1)} Mbps`; });
  br.addEventListener("change", () => commit(withOutputBitrate(config, "mercury", Number(br.value)), "Mercury bitrate"));
  const brWrap = node("div", "output-bitrate"); brWrap.append(br, brVal);
  card.appendChild(row("Bitrate", brWrap));

  const audBtn = node("button", `pill${o.audio ? " pill--on" : ""}`, o.audio ? "🔊 On" : "🔇 Off");
  audBtn.type = "button";
  audBtn.addEventListener("click", () => commit(withOutputAudio(config, "mercury", !o.audio), "Mercury audio"));
  card.appendChild(row("Audio", audBtn));

  // Action controls — DISABLED until the checklist is satisfied; never triggers a
  // connection in this build (the publisher is stubbed).
  const actions = node("div", "output-actions");
  const enableBtn = node("button", "pill", o.enabled ? "Disable" : "Enable");
  const startStop = node("button", "pill", o.enabled ? "Stop" : "Start");
  const restart = node("button", "pill", "↻ Restart");
  for (const btn of [enableBtn, startStop, restart]) {
    btn.type = "button";
    btn.disabled = !ready;   // armed only when the checklist passes
    btn.title = ready ? "" : "Waiting on setup";
  }
  enableBtn.addEventListener("click", () => commit(withOutputEnabled(config, "mercury", !o.enabled), o.enabled ? "disable Mercury" : "enable Mercury"));
  startStop.addEventListener("click", () => commit(withOutputEnabled(config, "mercury", !o.enabled), o.enabled ? "stop Mercury" : "start Mercury"));
  restart.addEventListener("click", () => commit(withOutputRestart(config, "mercury"), "restart Mercury"));
  actions.append(enableBtn, startStop, restart);
  card.appendChild(actions);
  return card;
}

// ----- the Discord card (a launch-to-start Activity that VIEWS the HLS) -----

function discordCard(o, rt) {
  const card = node("div", "output-card output-card--discord");
  const head = node("div", "output-head");
  head.appendChild(node("span", "output-name", "Discord"));
  // State is disabled | needs_setup | ready — NEVER "live"/"publishing" (a bot
  // can't broadcast unattended; the card never claims a session that isn't there).
  const state = rt.state || (o.enabled ? "needs_setup" : "disabled");
  head.appendChild(node("span", `output-state state-${state}`, state.replace(/_/g, " ")));
  card.appendChild(head);

  card.appendChild(node("p", "discord-explain",
    "Discord plays the wall as an Activity a user launches in a voice channel — it views the SAME HLS render (no extra encode). A bot can't broadcast video unattended, so this is launch-to-start: configure once here, then start it from Discord."));

  const enableBtn = node("button", `pill${o.enabled ? " pill--on" : ""}`, o.enabled ? "On" : "Off");
  enableBtn.type = "button";
  enableBtn.addEventListener("click", () => commit(withOutputEnabled(config, "discord", !o.enabled), "Discord on/off"));
  card.appendChild(row("Enabled", enableBtn));

  // Helper-computed setup checklist (✓/✗/? — ? = couldn't check safely). The public
  // origin check is real but NON-authenticating (it never posts to Discord).
  const cl = rt.checklist || {};
  const checklist = node("div", "discord-checklist");
  const item = (ok, label) => {
    const i = node("div", `check ${ok === true ? "ok" : ok === false ? "bad" : "unknown"}`);
    i.appendChild(node("span", "check-mark", ok === true ? "✓" : ok === false ? "✗" : "?"));
    i.appendChild(node("span", "check-label", label));
    return i;
  };
  checklist.appendChild(item(cl.client_id_present, "Client ID present"));
  checklist.appendChild(item(cl.client_secret_present, "Client secret present"));
  checklist.appendChild(item(cl.public_origin_reachable, "Public origin reachable"));
  checklist.appendChild(item(cl.hls_enabled, "HLS output on (the Activity plays it)"));
  card.appendChild(checklist);
  card.appendChild(node("div", "mercury-detail", rt.detail || "Configure the Discord app to enable"));

  // URL Mappings is a one-time DEV-PORTAL step we can't verify from here — surface
  // it as an honest instruction, not a fake ✓.
  card.appendChild(node("p", "discord-note",
    "One-time in the Discord developer portal → your app → Activities → URL Mappings: map  /  →  the public origin below. The exact block is in the README."));

  const origin = rt.public_origin || "";
  const urlLine = node("div", "output-url");
  urlLine.appendChild(node("span", "output-url-label", "Public origin"));
  urlLine.appendChild(node("code", "output-url-code", origin || "(set DISCORD_ACTIVITY_PUBLIC_ORIGIN)"));
  if (origin && navigator.clipboard) {
    const copy = node("button", "pill pill--copy", "Copy");
    copy.type = "button";
    copy.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(origin);
        copy.textContent = "Copied"; setTimeout(() => { copy.textContent = "Copy"; }, 1500);
      } catch { /* clipboard blocked — the code is selectable anyway */ }
    });
    urlLine.appendChild(copy);
  }
  card.appendChild(urlLine);

  // Optional guild-id hint (non-secret). The client SECRET never appears in this UI.
  const guild = node("input", "mercury-input");
  guild.type = "text"; guild.value = o.guild_id || ""; guild.placeholder = "guild ID (optional)";
  guild.addEventListener("change", () => commit(withDiscordFields(config, { guild_id: guild.value.trim() }), "Discord guild"));
  card.appendChild(row("Guild ID", guild));

  card.appendChild(node("p", "discord-launch",
    "To start: in a Discord voice channel → Activities (the rocket) → launch “MyMTS News Wall”. The wall appears in the call; tap once for sound."));
  return card;
}

function renderOutputs() {
  const root = el("outputs");
  if (!root || !config) return;
  const rt = (outputsStatus && outputsStatus.outputs) || {};
  root.replaceChildren();
  root.appendChild(hlsCard(config.outputs.hls, rt.hls || {}));
  root.appendChild(mercuryCard(config.outputs.mercury, rt.mercury || {}));
  root.appendChild(discordCard(config.outputs.discord, rt.discord || {}));
}

function render() {
  if (!config) return;
  syncLayoutControls();
  const root = el("cells");
  root.replaceChildren();
  root.style.setProperty("--cols", String(clampGridDim(config.layout.cols)));
  for (let i = 0; i < cellCount(config); i++) root.appendChild(renderCell(i));
  renderOutputs();
}

// ----- wire -----

function wire() {
  el("rows").addEventListener("change", (e) =>
    commit(withLayout(config, Number(e.target.value), config.layout.cols), "layout"));
  el("cols").addEventListener("change", (e) =>
    commit(withLayout(config, config.layout.rows, Number(e.target.value)), "layout"));
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
    e.target.value = "";
  });
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
  setInterval(refreshOutputsStatus, OUTPUTS_POLL_MS);
}
document.addEventListener("DOMContentLoaded", main);
