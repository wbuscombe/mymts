// Tests for the MyMTS Discord Activity core (node --test, no browser).
//
// Covers the OAuth wiring (authorize → exchange code at our endpoint → authenticate),
// the staged error states, and the hls.js attach against the proxied playlist path
// (incl. native-HLS fallback + self-recovery). Everything is injected, so no SDK, no
// window, no network.

import { test } from "node:test";
import assert from "node:assert/strict";

import {
  authorizeAndAuthenticate, attachStream,
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

// ----- the endpoint paths are RELATIVE (so they ride Discord's /.proxy/ base) -----

test("the stream/token/config paths are relative (proxy-safe)", () => {
  for (const p of [STREAM_PLAYLIST, TOKEN_PATH, CONFIG_PATH]) {
    assert.ok(!p.startsWith("/"), `${p} must be relative so it resolves under /.proxy/`);
    assert.ok(!p.startsWith("http"), `${p} must not be an absolute URL`);
  }
  assert.equal(STREAM_PLAYLIST, "api/stream/playlist.m3u8");
});

// ----- hls.js attach -----

function fakeHls({ supported = true } = {}) {
  const Events = { MANIFEST_PARSED: "manifestParsed", ERROR: "hlsError" };
  const ErrorTypes = { NETWORK_ERROR: "networkError", MEDIA_ERROR: "mediaError", OTHER_ERROR: "otherError" };
  const instances = [];
  function Hls() {
    const handlers = {};
    const inst = {
      handlers, loaded: null, attached: null, destroyed: false,
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
