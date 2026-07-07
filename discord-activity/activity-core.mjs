// Pure, browser-global-free core for the MyMTS Discord Activity.
//
// Imported by BOTH the browser entry (`activity.mjs`) and `node --test`
// (`test/activity.test.mjs`). It touches NO browser global (no `window`,
// `document`, `fetch`) and does NOT import the Discord SDK — every collaborator
// is INJECTED, so the OAuth wiring + the hls.js attach can be unit-tested with
// fakes. The browser entry supplies the real `DiscordSDK`, `window.Hls`, and a
// same-origin `fetch`-backed `exchangeCode`.
//
// Playback reuses the SINGLE HLS render the NAS renderer already produces (served
// by the helper at `/api/stream`); the Activity is a viewer, never a second encode.

// --- Path constants (a leading-slash path each; {@link proxied} maps them). ---
// The wall's HLS playlist, the OAuth token-exchange endpoint, and the public,
// non-secret config endpoint (the Discord application's client id, so the static
// app needs no build-time templating). Every request in the Activity is mapped to
// its real URL by {@link proxied} — NEVER used bare (see the note there for why).
export const STREAM_PLAYLIST = "/api/stream/playlist.m3u8";
export const TOKEN_PATH = "/api/discord/token";
export const CONFIG_PATH = "/api/discord/config";

// --- Discord's `/.proxy/` requirement — the single source of truth for the base. ---
//
// Inside a Discord Activity, the iframe is served at the ORIGIN ROOT
// (`https://<app-id>.discordsays.com/`), and Discord's CSP silently blocks EVERY
// network request that does not go through the `/.proxy/` path (which Discord maps
// to the operator's public origin). The earlier design assumed the iframe base was
// `…/.proxy/` so a bare relative URL would inherit it — it is NOT: a bare relative
// URL resolves to `…discordsays.com/api/...` (root, outside `/.proxy/`) and is
// dropped before it leaves the sandbox. That is why the OAuth `fetch`es appeared to
// work (Discord tolerates a simple root fetch under a `/` mapping) while the hls.js
// stream chain — manifest + segments, and any absolute/derived URL — never reached
// the helper. So we stop relying on the base entirely and prefix `/.proxy/`
// EXPLICITLY for every request (config, token, playlist, and every hls.js-derived
// segment/child-manifest URL). Standalone (opened at the public origin in a browser)
// the plain path is correct, so `proxied` degrades to it.

/** True iff this document is running inside Discord's Activity proxy — detected by
 *  the `*.discordsays.com` host Discord serves the iframe from. `location` is
 *  injected (the browser passes `window.location`) so this stays test-pure. */
export function isInsideDiscord(location) {
  const host = (location && location.hostname) || "";
  return /(^|\.)discordsays\.com$/i.test(host);
}

/** Map an app/API/stream path to the URL to actually request. The SINGLE source of
 *  truth for the proxy base: inside Discord, force it through `${origin}/.proxy<path>`
 *  (Discord's required, CSP-allowed path); standalone, the plain `${origin}<path>`.
 *  `path` may be given with or without a leading slash. Pure. */
export function proxied(path, { origin = "", inDiscord = false } = {}) {
  const p = path.startsWith("/") ? path : `/${path}`;
  return inDiscord ? `${origin}/.proxy${p}` : `${origin}${p}`;
}

/** hls.js belt: rewrite ANY URL hls.js is about to load onto Discord's `/.proxy/`
 *  path when inside Discord — closing the classic escape route where hls.js derives
 *  a segment/child-manifest/redirect URL (relative → resolved against the manifest,
 *  or an absolute URI the manifest smuggles in) that lands OUTSIDE `/.proxy/`.
 *  Idempotent: a URL already under `/.proxy/` passes through unchanged; a non-http
 *  URL (blob:/data: MSE buffers) is left alone. A no-op outside Discord. Pure. */
export function rewriteHlsUrl(url, { origin = "", inDiscord = false } = {}) {
  if (!inDiscord || !url) return url;
  let u;
  try {
    u = new URL(url, origin || "http://invalid.invalid");
  } catch {
    return url;
  }
  if (u.protocol !== "http:" && u.protocol !== "https:") return url;  // blob:/data: — leave alone
  if (u.pathname === "/.proxy" || u.pathname.startsWith("/.proxy/")) return url;  // already proxied
  const base = origin || `${u.protocol}//${u.host}`;
  return `${base}/.proxy${u.pathname}${u.search}`;
}

/** Format an hls.js ERROR `data` into ONE concise diagnostic line — the type, the
 *  details, the failing URL, and the HTTP status when present — so an in-frame
 *  overlay can name exactly what broke without DevTools. Honest: reports only what
 *  the error carries; never invents a status. Pure. */
export function formatHlsError(data) {
  const d = data || {};
  const parts = [];
  if (d.type) parts.push(String(d.type));
  if (d.details) parts.push(String(d.details));
  if (d.url) parts.push(String(d.url));
  const status = d.response && (d.response.code ?? d.response.status);
  if (status != null && status !== "") parts.push(`HTTP ${status}`);
  if (d.fatal) parts.push("fatal");
  return parts.join(" · ") || "unknown hls error";
}

/** The SMALLEST OAuth scope that yields a usable Activity session. We want a
 *  session so Discord lets the iframe run in the call — NOT any user data. */
export const OAUTH_SCOPE = ["identify"];

/** A staged error so the UI (and tests) can tell WHERE the launch failed
 *  (config / authorize / token / authenticate / playback) without leaking
 *  internals. Never carries a token or secret. */
export class ActivityError extends Error {
  constructor(message, { stage = "unknown", cause = null } = {}) {
    super(message);
    this.name = "ActivityError";
    this.stage = stage;
    if (cause) this.cause = cause;
  }
}

/** The Discord Activity OAuth handshake, end to end:
 *    authorize (SDK/RPC) → exchange the `code` at OUR token endpoint → authenticate.
 *  Collaborators are injected for testability:
 *    - `sdk`          a DiscordSDK instance (uses `sdk.commands.authorize/authenticate`)
 *    - `exchangeCode` async (code) => ({ access_token })  — POSTs to our endpoint
 *    - `clientId`     the public Discord application id
 *  Resolves the authenticated session; throws a staged {@link ActivityError} on any
 *  failure (declined consent, token error, bad session) so the caller degrades
 *  honestly instead of showing a half-loaded wall. */
export async function authorizeAndAuthenticate({ sdk, exchangeCode, clientId, scope = OAUTH_SCOPE }) {
  if (!clientId) throw new ActivityError("missing Discord client id", { stage: "config" });

  let code;
  try {
    ({ code } = await sdk.commands.authorize({
      client_id: clientId,
      response_type: "code",
      state: "",
      prompt: "none",
      scope,
    }));
  } catch (e) {
    throw new ActivityError("Discord authorize was declined or failed", { stage: "authorize", cause: e });
  }
  if (!code) throw new ActivityError("Discord returned no authorization code", { stage: "authorize" });

  let access_token;
  try {
    ({ access_token } = await exchangeCode(code));
  } catch (e) {
    throw new ActivityError("token exchange failed", { stage: "token", cause: e });
  }
  if (!access_token) throw new ActivityError("token endpoint returned no access_token", { stage: "token" });

  let auth;
  try {
    auth = await sdk.commands.authenticate({ access_token });
  } catch (e) {
    throw new ActivityError("Discord authenticate failed", { stage: "authenticate", cause: e });
  }
  if (!auth || !auth.access_token) {
    throw new ActivityError("Discord authenticate returned no session", { stage: "authenticate" });
  }
  return auth;
}

function play(video) {
  try {
    const p = video.play && video.play();
    if (p && typeof p.catch === "function") p.catch(() => { /* autoplay policy — overlay prompts */ });
  } catch { /* element not ready / autoplay quirk */ }
}

/** A custom hls.js loader class that rewrites EVERY request URL onto Discord's
 *  `/.proxy/` path (via {@link rewriteHlsUrl}) before the default loader fetches it
 *  — belt (b) of the proxy enforcement, catching segment/child-manifest/redirect/
 *  absolute URLs that relative resolution alone would let escape. Built from the
 *  injected `HlsLib.DefaultConfig.loader` so it's testable with a fake. Returns
 *  `null` when the lib exposes no default loader (a bare test fake) — the caller
 *  then simply omits the override (belt (a), the proxied playlist url, still holds). */
export function makeProxyLoader(HlsLib, proxyCtx) {
  const Base = HlsLib && HlsLib.DefaultConfig && HlsLib.DefaultConfig.loader;
  if (typeof Base !== "function") return null;
  return class ProxyLoader extends Base {
    load(context, config, callbacks) {
      if (context && typeof context.url === "string") {
        context.url = rewriteHlsUrl(context.url, proxyCtx);
      }
      super.load(context, config, callbacks);
    }
  };
}

/** Attach the wall's HLS stream to a `<video>`, hls.js-first with a native-HLS
 *  fallback. Injected for testability:
 *    - `HlsLib`   the hls.js constructor (`window.Hls` in the browser; a fake in tests)
 *    - `video`    the target media element (a fake with canPlayType/play in tests)
 *    - `url`      the playlist url — the caller passes the {@link proxied} form
 *    - `proxyCtx` `{ origin, inDiscord }` — forces hls.js's derived URLs through the
 *                 `/.proxy/` loader (belt b) so segments/child manifests can't escape
 *    - `onState`  (state, detail) => void  — drives the overlay ("playing" |
 *                 "recovering" | "error")
 *    - `onEvent`  (kind, detail) => void  — feeds the in-frame diagnostics overlay:
 *                 the resolved playlist URL, hls.js lifecycle, and per-error type/
 *                 details/url/status (never a token/secret)
 *  Fatal hls.js errors self-recover (network → reload, media → recover) so a brief
 *  proxy/network blip doesn't black out the wall. Returns `{ destroy() }`. */
export function attachStream({
  HlsLib, video,
  url = STREAM_PLAYLIST,
  proxyCtx = { origin: "", inDiscord: false },
  onState = () => {},
  onEvent = () => {},
}) {
  onEvent("playlist", url);   // the playlist URL actually requested (readable in-frame)
  if (HlsLib && typeof HlsLib.isSupported === "function" && HlsLib.isSupported()) {
    const config = {
      enableWorker: true,
      lowLatencyMode: false,
      backBufferLength: 30,
      manifestLoadingMaxRetry: 8,
      manifestLoadingRetryDelay: 1000,
      fragLoadingMaxRetry: 8,
    };
    const ProxyLoader = makeProxyLoader(HlsLib, proxyCtx);
    if (ProxyLoader) config.loader = ProxyLoader;   // belt (b): rewrite every hls.js URL
    const hls = new HlsLib(config);
    if (HlsLib.Events.MANIFEST_LOADING) {
      hls.on(HlsLib.Events.MANIFEST_LOADING, () => onEvent("hls", "manifest loading"));
    }
    hls.on(HlsLib.Events.MANIFEST_PARSED, () => {
      onEvent("hls", "manifest parsed → playing");
      onState("playing", "");
      play(video);
    });
    hls.on(HlsLib.Events.ERROR, (_evt, data) => {
      if (!data) return;
      onEvent("error", formatHlsError(data));   // every error is named in-frame (fatal or not)
      if (!data.fatal) return;
      onState("recovering", (data && data.details) || "stream error");
      if (data.type === HlsLib.ErrorTypes.NETWORK_ERROR) hls.startLoad();
      else if (data.type === HlsLib.ErrorTypes.MEDIA_ERROR) hls.recoverMediaError();
      else hls.destroy();
    });
    hls.loadSource(url);
    hls.attachMedia(video);
    return { destroy: () => hls.destroy() };
  }
  // Native HLS (Safari / iOS webview): no MSE, the element plays the playlist directly.
  if (video.canPlayType && video.canPlayType("application/vnd.apple.mpegurl")) {
    video.src = url;
    onEvent("hls", "native HLS");
    onState("playing", "");
    play(video);
    return { destroy: () => { try { video.removeAttribute("src"); if (video.load) video.load(); } catch { /* noop */ } } };
  }
  onEvent("error", "no HLS support in this client");
  onState("error", "This client cannot play HLS");
  return { destroy: () => {} };
}
