// Helper API client for the LAN web client.
//
// Same-origin: the SPA is served by the helper (mounted at /app over the
// existing HTTPS:8443), so it calls the API with ABSOLUTE-PATH, relative
// URLs (`/api/...`) on the same origin — NO CORS, no hardcoded IP, no
// credentials. `fetch` defaults to `credentials: "same-origin"`; we pass
// `credentials: "omit"` explicitly to make the credential-free posture
// loud: this client never sends cookies/auth and the helper has none.
//
// A1: this only ever GETs the helper's own JSON endpoints — never an
// article page, never an arbitrary URL. The helper is the sole boundary.
//
// ARCH-1 (schema guard): the ticker fetchers run the parsed envelope
// through tickerSchemaCheck() — the single pure validator in render.mjs —
// and attach a `_schema` field { ok, version, reason }. If the helper
// bumps TICKER_SCHEMA_VERSION (a new wire contract this client doesn't
// understand), the app shows an honest "client out of date" state and
// renders NO cards, rather than silently misreading an unknown contract.

import { tickerSchemaCheck } from "./render.mjs";

async function getJson(path) {
  const res = await fetch(path, {
    method: "GET",
    credentials: "omit",        // credential-free, explicit
    cache: "no-store",
    headers: { "Accept": "application/json" },
  });
  if (!res.ok) throw new Error(`${path} -> HTTP ${res.status}`);
  return res.json();
}

/** PUT a JSON body, credential-free like the GETs. On a 4xx the helper's
 *  validation `detail` is surfaced so the control UI can show WHY a write was
 *  rejected (e.g. "audible_cell points at an empty cell") rather than a bare
 *  failure. */
async function putJson(path, body) {
  const res = await fetch(path, {
    method: "PUT",
    credentials: "omit",
    cache: "no-store",
    headers: { "Accept": "application/json", "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    let detail = `HTTP ${res.status}`;
    try { detail = (await res.json()).detail || detail; } catch { /* non-JSON error body */ }
    throw new Error(detail);
  }
  return res.json();
}

/** Fetch a ticker envelope and tag it with the schema-guard verdict. The
 *  verdict travels on `_schema`; the caller renders cards only when ok. */
async function getTicker(path) {
  const env = await getJson(path);
  return { ...env, _schema: tickerSchemaCheck(env) };
}

export const api = {
  feed: (limit = 100) => getJson(`/api/feed?limit=${encodeURIComponent(limit)}`),
  // Unified registry (2026-07): /api/channels serves the SAME lineup to every
  // surface — the video channels AND the non-video WIDGET sources (the NWS
  // weather-radar loops) in one list. The former `?widgets=1` opt-in is gone (the
  // native TV now lists + renders radar too), so this is a plain fetch. A channel's
  // `kind` tells this client HOW to render each tile (video vs radar <img>), never
  // whether to list it.
  channels: () => getJson(`/api/channels`),
  presets: () => getJson(`/api/presets`),
  tickerMarkets: () => getTicker(`/api/ticker/markets`),
  tickerSports: () => getTicker(`/api/ticker/sports`),
  health: () => getJson(`/health`),
  // Server-side wall config (headless-container version): the rendered wall
  // (/app/) reads it; the picker (/control/) writes it.
  wall: () => getJson(`/api/wall`),
  putWall: (config) => putJson(`/api/wall`, config),
  // Per-output runtime status (HLS running/stopped + Mercury state + setup
  // checklist) — read from the renderer's status file by the helper. The Outputs
  // panel polls this; all EDITS still go through putWall (partial-merge).
  outputsStatus: () => getJson(`/api/outputs/status`),
};
