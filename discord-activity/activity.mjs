// MyMTS Discord Activity — browser entry.
//
// Runs inside the voice-channel iframe (Discord's Embedded App SDK). Lifecycle:
//   new DiscordSDK(clientId) → ready() → OAuth (authorize → exchange code at our
//   token endpoint → authenticate) → play the wall's HLS through Discord's proxy.
//
// It is a VIEWER of the single NAS render (helper /api/stream) — no new encode.
// All third-party JS is vendored same-origin (script-src 'self', no CDN): the SDK
// as an ESM module, hls.js as the same pinned UMD bundle the wall uses (window.Hls).
//
// EVERY network request is routed through `proxied()` so it rides Discord's required
// `/.proxy/` path (Discord's CSP silently blocks anything else); see activity-core.
// Browser-coupled glue only; the testable wiring lives in activity-core.mjs.

import { DiscordSDK } from "./vendor/discord-embedded-app-sdk.mjs";
import {
  authorizeAndAuthenticate, attachStream, isInsideDiscord, proxied,
  CONFIG_PATH, TOKEN_PATH, STREAM_PLAYLIST, ActivityError,
} from "./activity-core.mjs";

const Hls = () => window.Hls;
const $ = (id) => document.getElementById(id);

// The proxy context — the ONE place `window.location` is read. Inside Discord every
// request is forced through `${origin}/.proxy/…`; standalone it stays the plain path.
const CTX = { origin: window.location.origin, inDiscord: isInsideDiscord(window.location) };

function setOverlay(text, kind = "") {
  const o = $("overlay");
  if (!o) return;
  o.textContent = text || "";
  o.dataset.kind = kind;
  o.style.display = text ? "flex" : "none";
}

// ----- in-frame diagnostics (the operator has no DevTools inside Discord) -----
// A dismissible overlay that records what actually happened — the environment, each
// OAuth stage, the resolved playlist URL, hls.js lifecycle, and on any hls.js error
// its type/details/failing URL/HTTP status. Toggle by tapping the corner badge;
// auto-shown on a fatal error. Honest: it reports events, never fakes progress, and
// never carries a token/secret (ActivityError + formatHlsError are both scrubbed).
const DIAG_MAX = 18;
const diagLines = [];
function diagRecord(kind, detail) {
  diagLines.push(`${kind}: ${detail}`);
  if (diagLines.length > DIAG_MAX) diagLines.shift();
  const el = $("diag");
  if (el) el.textContent = diagLines.join("\n");
}
function diagShow(show) {
  const el = $("diag");
  if (el) el.style.display = show ? "block" : "none";
}
function initDiag() {
  const badge = $("diagbadge");
  if (badge) {
    badge.style.display = "block";
    badge.addEventListener("click", () => {
      const el = $("diag");
      diagShow(!el || el.style.display === "none" || !el.style.display);
    });
  }
  diagShow(false);
}

/** Credential-free JSON fetch, routed through the proxy. This app only ever talks
 *  to OUR origin — the config + token endpoints — never an arbitrary URL. */
async function fetchJson(path, opts) {
  const url = proxied(path, CTX);
  const res = await fetch(url, {
    credentials: "omit",
    cache: "no-store",
    headers: { Accept: "application/json", ...(opts && opts.headers) },
    ...opts,
  });
  if (!res.ok) throw new Error(`${path} → HTTP ${res.status}`);
  return res.json();
}

const exchangeCode = (code) =>
  fetchJson(TOKEN_PATH, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code }),
  });

/** A one-time tap-to-unmute affordance. The wall starts MUTED so it always
 *  autoplays (browsers block autoplay-with-audio without a gesture); the user is
 *  already in the call and launched this, so a single tap arms the mixed audio. */
function offerUnmute(video) {
  if (!video || !video.muted) return;
  const btn = $("unmute");
  if (!btn) return;
  btn.style.display = "block";
  btn.addEventListener("click", () => {
    video.muted = false;
    const p = video.play && video.play();
    if (p && p.catch) p.catch(() => {});
    btn.style.display = "none";
  }, { once: true });
}

async function main() {
  initDiag();
  diagRecord("env", CTX.inDiscord ? `inside Discord — ${CTX.origin}/.proxy/` : `standalone — ${CTX.origin}`);
  try {
    setOverlay("Connecting to Discord…");
    // The public, non-secret client id (sourced from helper env; never the secret).
    const cfg = await fetchJson(CONFIG_PATH).catch((e) => {
      diagRecord("error", `config ${(e && e.message) || e}`);
      return {};
    });
    const clientId = cfg && cfg.client_id;
    if (!clientId) {
      diagRecord("oauth", "no client id — Activity not configured");
      setOverlay("This Activity isn't configured yet — ask the operator to set the Discord client id.");
      diagShow(true);
      return;
    }
    diagRecord("oauth", "config ok");

    const sdk = new DiscordSDK(clientId);
    await sdk.ready();
    diagRecord("oauth", "sdk ready");

    setOverlay("Authorizing…");
    await authorizeAndAuthenticate({ sdk, exchangeCode, clientId });
    diagRecord("oauth", "authenticated");

    setOverlay("Starting the wall…");
    const video = $("wall");
    attachStream({
      HlsLib: Hls(),
      video,
      url: proxied(STREAM_PLAYLIST, CTX),
      proxyCtx: CTX,
      onState: (state, detail) => {
        if (state === "playing") { setOverlay(""); offerUnmute(video); }
        else if (state === "recovering") setOverlay("Reconnecting to the wall…", "muted");
        else if (state === "error") { setOverlay(detail || "Couldn't play the wall stream."); diagShow(true); }
      },
      onEvent: (kind, detail) => diagRecord(kind, detail),
    });
  } catch (e) {
    const stage = e instanceof ActivityError ? e.stage : "unknown";
    diagRecord("error", `${stage}: ${(e && e.message) || e}`);
    diagShow(true);
    setOverlay(`Couldn't start the wall (${stage}). Try relaunching the Activity.`);
    // No token/secret is ever in an ActivityError; safe to log the stage + message.
    console.error("[mymts-activity]", stage, (e && e.message) || e);
  }
}

document.addEventListener("DOMContentLoaded", main);
