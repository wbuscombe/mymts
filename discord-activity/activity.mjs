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
// Browser-coupled glue only; the testable wiring lives in activity-core.mjs.

import { DiscordSDK } from "./vendor/discord-embedded-app-sdk.mjs";
import {
  authorizeAndAuthenticate, attachStream,
  CONFIG_PATH, TOKEN_PATH, STREAM_PLAYLIST, ActivityError,
} from "./activity-core.mjs";

const Hls = () => window.Hls;
const $ = (id) => document.getElementById(id);

function setOverlay(text, kind = "") {
  const o = $("overlay");
  if (!o) return;
  o.textContent = text || "";
  o.dataset.kind = kind;
  o.style.display = text ? "flex" : "none";
}

/** Same-origin, credential-free JSON fetch (mirrors the web client's posture:
 *  this app only ever talks to OUR origin — the config + token endpoints and the
 *  HLS — never an arbitrary URL). */
async function fetchJson(path, opts) {
  const res = await fetch(path, {
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
  try {
    setOverlay("Connecting to Discord…");
    // The public, non-secret client id (sourced from helper env; never the secret).
    const cfg = await fetchJson(CONFIG_PATH).catch(() => ({}));
    const clientId = cfg && cfg.client_id;
    if (!clientId) {
      setOverlay("This Activity isn't configured yet — ask the operator to set the Discord client id.");
      return;
    }

    const sdk = new DiscordSDK(clientId);
    await sdk.ready();

    setOverlay("Authorizing…");
    await authorizeAndAuthenticate({ sdk, exchangeCode, clientId });

    setOverlay("Starting the wall…");
    const video = $("wall");
    attachStream({
      HlsLib: Hls(),
      video,
      url: STREAM_PLAYLIST,
      onState: (state, detail) => {
        if (state === "playing") { setOverlay(""); offerUnmute(video); }
        else if (state === "recovering") setOverlay("Reconnecting to the wall…", "muted");
        else if (state === "error") setOverlay(detail || "Couldn't play the wall stream.");
      },
    });
  } catch (e) {
    const stage = e instanceof ActivityError ? e.stage : "unknown";
    setOverlay(`Couldn't start the wall (${stage}). Try relaunching the Activity.`);
    // No token/secret is ever in an ActivityError; safe to log the stage + message.
    console.error("[mymts-activity]", stage, (e && e.message) || e);
  }
}

document.addEventListener("DOMContentLoaded", main);
