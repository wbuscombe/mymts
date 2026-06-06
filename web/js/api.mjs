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

export const api = {
  feed: (limit = 100) => getJson(`/api/feed?limit=${encodeURIComponent(limit)}`),
  channels: () => getJson(`/api/channels`),
  tickerMarkets: () => getJson(`/api/ticker/markets`),
  tickerSports: () => getJson(`/api/ticker/sports`),
  health: () => getJson(`/health`),
};
