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

/** The wall's HLS playlist, as a RELATIVE url. It resolves against the document
 *  base, which is what makes the Activity work in both worlds with no edit:
 *   - inside Discord the base is `https://<app-id>.discordsays.com/.proxy/`, so this
 *     becomes `…/.proxy/api/stream/playlist.m3u8` — Discord's required proxy path,
 *     which it forwards to the public origin (e.g. wall.3slstudios.com);
 *   - served standalone at the public origin root it resolves to
 *     `/api/stream/playlist.m3u8`.
 *  The playlist's segment URIs are themselves relative (`seg_N.ts`), so they
 *  inherit the same proxy path automatically — no rewrite needed. */
export const STREAM_PLAYLIST = "api/stream/playlist.m3u8";
/** Our server-side OAuth token-exchange endpoint (relative → proxied like above). */
export const TOKEN_PATH = "api/discord/token";
/** Our public, non-secret config endpoint: returns the Discord application's
 *  client id so the static app needs no build-time templating. */
export const CONFIG_PATH = "api/discord/config";

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

/** Attach the wall's HLS stream to a `<video>`, hls.js-first with a native-HLS
 *  fallback. Injected for testability:
 *    - `HlsLib`  the hls.js constructor (`window.Hls` in the browser; a fake in tests)
 *    - `video`   the target media element (a fake with canPlayType/play in tests)
 *    - `url`     the playlist url (defaults to the proxied {@link STREAM_PLAYLIST})
 *    - `onState` (state, detail) => void  — drives the overlay
 *      ("playing" | "recovering" | "error")
 *  Fatal hls.js errors self-recover (network → reload, media → recover) so a brief
 *  proxy/network blip doesn't black out the wall. Returns `{ destroy() }`. */
export function attachStream({ HlsLib, video, url = STREAM_PLAYLIST, onState = () => {} }) {
  if (HlsLib && typeof HlsLib.isSupported === "function" && HlsLib.isSupported()) {
    const hls = new HlsLib({
      enableWorker: true,
      lowLatencyMode: false,
      backBufferLength: 30,
      manifestLoadingMaxRetry: 8,
      manifestLoadingRetryDelay: 1000,
      fragLoadingMaxRetry: 8,
    });
    hls.on(HlsLib.Events.MANIFEST_PARSED, () => { onState("playing", ""); play(video); });
    hls.on(HlsLib.Events.ERROR, (_evt, data) => {
      if (!data || !data.fatal) return;
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
    onState("playing", "");
    play(video);
    return { destroy: () => { try { video.removeAttribute("src"); if (video.load) video.load(); } catch { /* noop */ } } };
  }
  onState("error", "This client cannot play HLS");
  return { destroy: () => {} };
}
