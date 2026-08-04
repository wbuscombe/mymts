// MyMTS Wall Control (/control/) — the unified panel (wall editor + Outputs).
//
// The wall's layout in a browser, but each cell is a FEED-PICKER + per-cell AUDIO
// + SUBTITLE toggles (multi-audible: any combination unmuted, they mix) — NO video
// decode (lightweight; runs on a phone). The Outputs section drives the multi-output
// fan-out: an HLS/VLC card (live). Every edit writes the server-side wall config (PUT
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
  withScaleSteps, withAutoFit,
  withOutputEnabled, withOutputResolution, withOutputBitrate, withOutputAudio,
  withOutputRestart,
  RENDER_RESOLUTIONS, RESOLUTION_INFO, bitrateBounds,
  deriveRenderResolution,
} from "/app/js/wallConfig.mjs";
// The four sizing controls are defined ONCE, shared with /app/'s WALL SETTINGS modal
// (PR-027). Three independent implementations of the same controls is what let this
// surface and /app/ drift apart for two PRs.
import { SCALE_CONTROLS, scaleLabel, stepOf, applyRangeBounds, createScaleCommitter,
         autoFitStatusLine }
  from "/app/js/scaleControls.mjs";

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

/** Poll the per-output runtime status (HLS running/stopped) WITHOUT disturbing an
 *  in-flight config edit — status is renderer-owned, the config is owned here. */
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
  for (const c of SCALE_CONTROLS) setSlider(c, stepOf(config, c));
  syncAutoFit();
}

/** Reflect the auto-fit mode. /control/ cannot MEASURE the wall (it has no wall DOM),
 *  so it only toggles the mode and reports the width the render surface solved. */
function syncAutoFit() {
  const box = el("autofit");
  if (box && document.activeElement !== box) box.checked = config.autofit.enabled === true;
  const st = el("autofit-status");
  if (st) {
    st.textContent = autoFitStatusLine(config, null);
    st.classList.toggle("is-on", config.autofit.enabled === true);
  }
  // While auto owns the width, the manual width slider is still live — moving it is
  // how you exit the mode — but say so rather than leaving it looking inert.
  const w = el("feed-width");
  if (w) w.title = config.autofit.enabled
    ? "Auto-fit is on — moving this returns to manual control"
    : "";
}

/** Reflect a step onto its slider + label. Skips the thumb of a range the operator
 *  is CURRENTLY holding: the 60 s channel refresh re-renders this panel, and writing
 *  `value` mid-drag would snap the thumb back to the last-committed step. The label
 *  still updates, so a value that genuinely changed server-side is never hidden. */
function setSlider(control, step) {
  const s = el(control.id);
  if (s && document.activeElement !== s) s.value = String(step);
  const lab = el(`${control.id}-val`);
  if (lab) lab.textContent = scaleLabel(control, step);
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

// ----- the Outputs section (HLS card) -----

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

function renderOutputs() {
  const root = el("outputs");
  if (!root || !config) return;
  const rt = (outputsStatus && outputsStatus.outputs) || {};
  root.replaceChildren();
  root.appendChild(hlsCard(config.outputs.hls, rt.hls || {}));
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
  // The four 1–10 display sliders, wired from the SHARED control table and the SHARED
  // debounced committer (PR-027) — /app/'s WALL SETTINGS modal uses the identical code,
  // so the two surfaces cannot drift apart again.
  //
  // THRASH GUARD, two layers (unchanged contract): `input` only re-labels (no network,
  // so dragging is free); `change` queues a commit that coalesces PER CONTROL for
  // 250 ms, so a held arrow key — or two sliders nudged in one window — becomes ONE
  // PUT carrying both rather than a write storm or a dropped edit.
  const committer = createScaleCommitter({
    getConfig: () => config,
    apply: (cfg, edits) => withScaleSteps(cfg, edits),
    commit: (next, what) => commit(next, what),
  });
  for (const control of SCALE_CONTROLS) {
    const s = el(control.id);
    if (!s) continue;
    applyRangeBounds(s);
    s.addEventListener("input", () => {
      const lab = el(`${control.id}-val`);
      if (lab) lab.textContent = scaleLabel(control, Number(s.value));
    });
    s.addEventListener("change", () => committer.push(control, Number(s.value)));
  }
  const af = el("autofit");
  if (af) af.addEventListener("change", () => commit(withAutoFit(config, af.checked), "auto-fit"));

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

/** Show which build this panel is running (PR-026). The panel's own assets are served
 *  `Cache-Control: no-store`, so a browser can no longer be holding a page older than
 *  the helper — which makes the helper's build SHA an honest answer for the page too.
 *  Best-effort: a failed /health leaves the placeholder rather than blanking the UI. */
async function showBuild() {
  const el_ = el("build-sha");
  if (!el_) return;
  try {
    const h = await api.health();
    el_.textContent = `${h.build_sha ?? "?"} · v${h.version ?? "?"}`;
  } catch { el_.textContent = "unavailable"; }
}

function main() {
  wire();
  loadAll();
  showBuild();
  setInterval(refreshChannels, CHANNELS_POLL_MS);
  setInterval(refreshOutputsStatus, OUTPUTS_POLL_MS);
}
document.addEventListener("DOMContentLoaded", main);
