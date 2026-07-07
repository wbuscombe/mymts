// Tests for the MyMTS Discord Activity core (node --test, no browser).
//
// Covers the OAuth wiring (authorize → exchange code at our endpoint → authenticate),
// the staged error states, and the hls.js attach against the proxied playlist path
// (incl. native-HLS fallback + self-recovery). Everything is injected, so no SDK, no
// window, no network.

import { test } from "node:test";
import assert from "node:assert/strict";

import {
  authorizeAndAuthenticate, attachStream, makeProxyLoader,
  isInsideDiscord, proxied, rewriteHlsUrl, formatHlsError,
  STREAM_PLAYLIST, TOKEN_PATH, CONFIG_PATH, OAUTH_SCOPE, ActivityError,
} from "../activity-core.mjs";

// ----- a fake DiscordSDK (records calls, scriptable outcomes) -----

function fakeSdk({ authorize, authenticate } = {}) {
  const calls = { authorize: [], authenticate: [] };
  return {
    calls,
    commands: {
      authorize: async (args) => {
        calls.authorize.push(args);
        if (typeof authorize === "function") return authorize(args);
        return authorize ?? { code: "CODE_FROM_DISCORD" };
      },
      authenticate: async (args) => {
        calls.authenticate.push(args);
        if (typeof authenticate === "function") return authenticate(args);
        return authenticate ?? { access_token: "AUTHED" };
      },
    },
  };
}

// ----- OAuth wiring -----

test("authorize → exchange code → authenticate, in order with the right args", async () => {
  const sdk = fakeSdk();
  const exchanged = [];
  const exchangeCode = async (code) => { exchanged.push(code); return { access_token: "ACCESS_X" }; };

  const session = await authorizeAndAuthenticate({ sdk, exchangeCode, clientId: "CID" });

  // authorize got our client id + minimal scope + code response type
  assert.equal(sdk.calls.authorize.length, 1);
  const a = sdk.calls.authorize[0];
  assert.equal(a.client_id, "CID");
  assert.equal(a.response_type, "code");
  assert.deepEqual(a.scope, OAUTH_SCOPE);
  // the code from authorize was exchanged at our endpoint…
  assert.deepEqual(exchanged, ["CODE_FROM_DISCORD"]);
  // …and the resulting access_token was handed to authenticate
  assert.deepEqual(sdk.calls.authenticate[0], { access_token: "ACCESS_X" });
  assert.deepEqual(session, { access_token: "AUTHED" });
});

test("missing client id fails at the config stage (before any SDK call)", async () => {
  const sdk = fakeSdk();
  await assert.rejects(
    () => authorizeAndAuthenticate({ sdk, exchangeCode: async () => ({ access_token: "x" }), clientId: "" }),
    (e) => e instanceof ActivityError && e.stage === "config",
  );
  assert.equal(sdk.calls.authorize.length, 0);
});

test("declined authorize surfaces stage=authorize", async () => {
  const sdk = fakeSdk({ authorize: () => { throw new Error("user closed the modal"); } });
  await assert.rejects(
    () => authorizeAndAuthenticate({ sdk, exchangeCode: async () => ({ access_token: "x" }), clientId: "CID" }),
    (e) => e instanceof ActivityError && e.stage === "authorize",
  );
});

test("authorize returning no code surfaces stage=authorize", async () => {
  const sdk = fakeSdk({ authorize: { code: "" } });
  await assert.rejects(
    () => authorizeAndAuthenticate({ sdk, exchangeCode: async () => ({ access_token: "x" }), clientId: "CID" }),
    (e) => e instanceof ActivityError && e.stage === "authorize",
  );
});

test("token-exchange failure surfaces stage=token (no token leak in message)", async () => {
  const sdk = fakeSdk();
  const exchangeCode = async () => { throw new Error("HTTP 502"); };
  await assert.rejects(
    () => authorizeAndAuthenticate({ sdk, exchangeCode, clientId: "CID" }),
    (e) => e instanceof ActivityError && e.stage === "token",
  );
});

test("token endpoint returning no access_token surfaces stage=token", async () => {
  const sdk = fakeSdk();
  await assert.rejects(
    () => authorizeAndAuthenticate({ sdk, exchangeCode: async () => ({}), clientId: "CID" }),
    (e) => e instanceof ActivityError && e.stage === "token",
  );
});

test("authenticate failure / empty session surfaces stage=authenticate", async () => {
  const exchangeCode = async () => ({ access_token: "x" });
  await assert.rejects(
    () => authorizeAndAuthenticate({ sdk: fakeSdk({ authenticate: () => { throw new Error("nope"); } }), exchangeCode, clientId: "CID" }),
    (e) => e instanceof ActivityError && e.stage === "authenticate",
  );
  await assert.rejects(
    () => authorizeAndAuthenticate({ sdk: fakeSdk({ authenticate: {} }), exchangeCode, clientId: "CID" }),
    (e) => e instanceof ActivityError && e.stage === "authenticate",
  );
});

// ----- proxy enforcement: every request forced through Discord's /.proxy/ path -----

const DISCORD_LOC = { hostname: "1234567890.discordsays.com" };
const BROWSER_LOC = { hostname: "wall.3slstudios.example" };
const DISCORD_CTX = { origin: "https://1234567890.discordsays.com", inDiscord: true };
const DIRECT_CTX = { origin: "https://wall.3slstudios.example", inDiscord: false };

test("isInsideDiscord detects the *.discordsays.com iframe host, not a direct browser", () => {
  assert.equal(isInsideDiscord(DISCORD_LOC), true);
  assert.equal(isInsideDiscord({ hostname: "abc.discordsays.com" }), true);
  assert.equal(isInsideDiscord(BROWSER_LOC), false);
  assert.equal(isInsideDiscord({ hostname: "discordsays.com.evil.example" }), false); // suffix spoof
  assert.equal(isInsideDiscord({}), false);
  assert.equal(isInsideDiscord(null), false);
});

test("proxied() maps every path through /.proxy/ inside Discord, plain when direct", () => {
  // Inside Discord: absolute URL onto the proxy origin, under /.proxy/.
  assert.equal(
    proxied(STREAM_PLAYLIST, DISCORD_CTX),
    "https://1234567890.discordsays.com/.proxy/api/stream/playlist.m3u8",
  );
  assert.equal(proxied(TOKEN_PATH, DISCORD_CTX), "https://1234567890.discordsays.com/.proxy/api/discord/token");
  assert.equal(proxied(CONFIG_PATH, DISCORD_CTX), "https://1234567890.discordsays.com/.proxy/api/discord/config");
  // Direct browser: the plain path at the public origin (no /.proxy/).
  assert.equal(proxied(STREAM_PLAYLIST, DIRECT_CTX), "https://wall.3slstudios.example/api/stream/playlist.m3u8");
  // A path without a leading slash is normalized (the helper is the single source of truth).
  assert.equal(proxied("api/x", DISCORD_CTX), "https://1234567890.discordsays.com/.proxy/api/x");
  // The constants are leading-slash paths (mapped, never used bare).
  for (const p of [STREAM_PLAYLIST, TOKEN_PATH, CONFIG_PATH]) assert.ok(p.startsWith("/api/"));
});

test("rewriteHlsUrl forces any hls.js URL under /.proxy/, idempotently, inside Discord", () => {
  // An already-proxied URL (a relative segment resolved against the proxied playlist) → passthrough.
  const proxiedSeg = "https://1234567890.discordsays.com/.proxy/api/stream/seg_1.ts";
  assert.equal(rewriteHlsUrl(proxiedSeg, DISCORD_CTX), proxiedSeg);
  // An absolute URI the manifest could smuggle in (escaping /.proxy/) → re-homed onto the proxy path.
  assert.equal(
    rewriteHlsUrl("https://wall.3slstudios.example/api/stream/seg_9.ts", DISCORD_CTX),
    "https://1234567890.discordsays.com/.proxy/api/stream/seg_9.ts",
  );
  // A root path that escaped /.proxy/ → fixed.
  assert.equal(
    rewriteHlsUrl("https://1234567890.discordsays.com/api/stream/seg_2.ts?x=1", DISCORD_CTX),
    "https://1234567890.discordsays.com/.proxy/api/stream/seg_2.ts?x=1",
  );
  // blob:/data: (MSE source buffers) are never rewritten.
  assert.equal(rewriteHlsUrl("blob:https://x/abc", DISCORD_CTX), "blob:https://x/abc");
  // Outside Discord: a no-op.
  assert.equal(rewriteHlsUrl("https://wall.3slstudios.example/api/stream/seg_9.ts", DIRECT_CTX),
    "https://wall.3slstudios.example/api/stream/seg_9.ts");
});

test("makeProxyLoader rewrites context.url through the proxy before the base loader", () => {
  const loads = [];
  // A fake hls.js exposing a DefaultConfig.loader base (the real lib has XhrLoader).
  const FakeHls = { DefaultConfig: { loader: class { load(ctx) { loads.push(ctx.url); } } } };
  const Loader = makeProxyLoader(FakeHls, DISCORD_CTX);
  const inst = new Loader();
  const ctx = { url: "https://1234567890.discordsays.com/api/stream/seg_5.ts" };
  inst.load(ctx, {}, {});
  assert.equal(ctx.url, "https://1234567890.discordsays.com/.proxy/api/stream/seg_5.ts"); // rewritten in place
  assert.deepEqual(loads, ["https://1234567890.discordsays.com/.proxy/api/stream/seg_5.ts"]); // base saw the proxied url
  // A lib with no default loader (a bare fake) → null (caller omits the override).
  assert.equal(makeProxyLoader({}, DISCORD_CTX), null);
});

test("formatHlsError names the type, details, failing URL, and HTTP status", () => {
  const line = formatHlsError({
    type: "networkError", details: "manifestLoadError",
    url: "https://x/.proxy/api/stream/playlist.m3u8",
    response: { code: 403 }, fatal: true,
  });
  assert.match(line, /networkError/);
  assert.match(line, /manifestLoadError/);
  assert.match(line, /playlist\.m3u8/);
  assert.match(line, /HTTP 403/);
  assert.match(line, /fatal/);
  assert.equal(formatHlsError(null), "unknown hls error");   // honest, never blank
  assert.equal(formatHlsError({}), "unknown hls error");
});

// ----- hls.js attach -----

function fakeHls({ supported = true } = {}) {
  const Events = { MANIFEST_LOADING: "manifestLoading", MANIFEST_PARSED: "manifestParsed", ERROR: "hlsError" };
  const ErrorTypes = { NETWORK_ERROR: "networkError", MEDIA_ERROR: "mediaError", OTHER_ERROR: "otherError" };
  const instances = [];
  function Hls(config) {
    const handlers = {};
    const inst = {
      handlers, config, loaded: null, attached: null, destroyed: false,
      startLoadCount: 0, recoverCount: 0,
      on(evt, cb) { (handlers[evt] = handlers[evt] || []).push(cb); },
      emit(evt, data) { (handlers[evt] || []).forEach((cb) => cb(evt, data)); },
      loadSource(url) { this.loaded = url; },
      attachMedia(v) { this.attached = v; },
      startLoad() { this.startLoadCount++; },
      recoverMediaError() { this.recoverCount++; },
      destroy() { this.destroyed = true; },
    };
    instances.push(inst);
    return inst;
  }
  Hls.isSupported = () => supported;
  Hls.Events = Events;
  Hls.ErrorTypes = ErrorTypes;
  // The real lib exposes a default loader class hls.js instantiates per request;
  // attachStream subclasses it (makeProxyLoader) to rewrite URLs through /.proxy/.
  Hls.DefaultConfig = { loader: class { load() {} } };
  Hls.instances = instances;
  return Hls;
}

function fakeVideo({ nativeHls = false } = {}) {
  return {
    src: null, played: 0, removed: false,
    canPlayType: (t) => (nativeHls && t.includes("mpegurl") ? "maybe" : ""),
    play() { this.played++; return Promise.resolve(); },
    removeAttribute() { this.removed = true; },
    load() {},
  };
}

test("attachStream wires hls.js to the proxied playlist + plays on manifest", () => {
  const Hls = fakeHls();
  const video = fakeVideo();
  const states = [];
  attachStream({ HlsLib: Hls, video, onState: (s) => states.push(s) });

  const inst = Hls.instances[0];
  assert.equal(inst.loaded, STREAM_PLAYLIST);     // loads the relative (proxy-safe) playlist
  assert.equal(inst.attached, video);
  inst.emit(Hls.Events.MANIFEST_PARSED, {});
  assert.ok(states.includes("playing"));
  assert.equal(video.played, 1);
});

test("attachStream self-recovers fatal network/media errors", () => {
  const Hls = fakeHls();
  const video = fakeVideo();
  attachStream({ HlsLib: Hls, video, onState: () => {} });
  const inst = Hls.instances[0];

  inst.emit(Hls.Events.ERROR, { fatal: true, type: Hls.ErrorTypes.NETWORK_ERROR });
  assert.equal(inst.startLoadCount, 1);           // network → reload
  inst.emit(Hls.Events.ERROR, { fatal: true, type: Hls.ErrorTypes.MEDIA_ERROR });
  assert.equal(inst.recoverCount, 1);             // media → recover
  // a non-fatal error is ignored (no churn)
  inst.emit(Hls.Events.ERROR, { fatal: false, type: Hls.ErrorTypes.NETWORK_ERROR });
  assert.equal(inst.startLoadCount, 1);
});

test("attachStream falls back to native HLS when hls.js isn't supported", () => {
  const video = fakeVideo({ nativeHls: true });
  const states = [];
  attachStream({ HlsLib: fakeHls({ supported: false }), video, onState: (s) => states.push(s) });
  assert.equal(video.src, STREAM_PLAYLIST);       // native element plays the playlist directly
  assert.ok(states.includes("playing"));
});

test("attachStream reports an error when no HLS path is available", () => {
  const video = fakeVideo({ nativeHls: false });
  const states = [];
  attachStream({ HlsLib: undefined, video, onState: (s) => states.push(s) });
  assert.ok(states.includes("error"));
});

test("attachStream loads the caller's proxied url + wires the /.proxy/ loader", () => {
  const Hls = fakeHls();
  const video = fakeVideo();
  const url = "https://1234567890.discordsays.com/.proxy/api/stream/playlist.m3u8";
  attachStream({ HlsLib: Hls, video, url, proxyCtx: DISCORD_CTX, onState: () => {} });
  const inst = Hls.instances[0];
  assert.equal(inst.loaded, url);                              // belt (a): the proxied playlist url
  assert.equal(typeof inst.config.loader, "function");        // belt (b): the URL-rewriting loader is wired
  // and that wired loader rewrites through /.proxy/ (same behavior as makeProxyLoader)
  const l = new inst.config.loader();
  const ctx = { url: "https://1234567890.discordsays.com/api/stream/seg_7.ts" };
  l.load(ctx, {}, {});
  assert.equal(ctx.url, "https://1234567890.discordsays.com/.proxy/api/stream/seg_7.ts");
});

test("attachStream feeds the diagnostics overlay: playlist url, lifecycle, and errors", () => {
  const Hls = fakeHls();
  const video = fakeVideo();
  const events = [];
  const url = "https://x.discordsays.com/.proxy/api/stream/playlist.m3u8";
  attachStream({ HlsLib: Hls, video, url, proxyCtx: DISCORD_CTX, onEvent: (k, d) => events.push([k, d]) });
  const inst = Hls.instances[0];
  // the resolved playlist url is reported up-front (readable in-frame)
  assert.deepEqual(events[0], ["playlist", url]);
  inst.emit(Hls.Events.MANIFEST_LOADING, {});
  inst.emit(Hls.Events.MANIFEST_PARSED, {});
  assert.ok(events.some(([k, d]) => k === "hls" && /manifest loading/.test(d)));
  assert.ok(events.some(([k, d]) => k === "hls" && /manifest parsed/.test(d)));
  // a NON-fatal error is still named in-frame (helps the operator), but doesn't churn
  inst.emit(Hls.Events.ERROR, { fatal: false, type: "networkError", details: "levelLoadError",
    url: "https://x/.proxy/api/stream/level.m3u8", response: { code: 404 } });
  assert.equal(inst.startLoadCount, 0);
  const errLine = events.find(([k]) => k === "error");
  assert.ok(errLine, "an error event was recorded");
  assert.match(errLine[1], /networkError · levelLoadError · .*level\.m3u8 · HTTP 404/);
});
