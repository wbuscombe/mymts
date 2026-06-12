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

/** Fetch a ticker envelope and tag it with the schema-guard verdict. The
 *  verdict travels on `_schema`; the caller renders cards only when ok. */
async function getTicker(path) {
  const env = await getJson(path);
  return { ...env, _schema: tickerSchemaCheck(env) };
}

export const api = {
  feed: (limit = 100) => getJson(`/api/feed?limit=${encodeURIComponent(limit)}`),
  channels: () => getJson(`/api/channels`),
  tickerMarkets: () => getTicker(`/api/ticker/markets`),
  tickerSports: () => getTicker(`/api/ticker/sports`),
  health: () => getJson(`/health`),
};
