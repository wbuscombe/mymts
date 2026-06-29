// Tests for the Mercury publisher browser app (renderer/publisher/publisher.js),
// run with `node --test`. publisher.js is a classic <script> that uses browser
// globals (window.LivekitClient, window.Hls, document, fetch, …) and calls main()
// at load, so we run it in a `vm` sandbox with fakes and assert the WIRING:
//   - it fetches /config and connects livekit-client to the room/token,
//   - it plays the wall's HLS (render-once) and captureStream()s it,
//   - it publishes a `source: screen_share` track with simulcast FORCED ON + the
//     explicit layer ladder (note B) — and screen_share_audio when audio is on (D).

import { test } from "node:test";
import assert from "node:assert/strict";
import vm from "node:vm";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const HERE = dirname(fileURLToPath(import.meta.url));
const SRC = readFileSync(join(HERE, "..", "publisher", "publisher.js"), "utf8");

function makeEnv(cfg) {
  const calls = { publishTrack: [], status: [], loadSource: [] };
  const listeners = {};

  // fake hls.js — fires MANIFEST_PARSED on attachMedia; the video reaches "loadeddata".
  function Hls() {
    this.on = (evt, cb) => { (listeners[evt] = listeners[evt] || []).push(cb); return this; };
    this.loadSource = (u) => { calls.loadSource.push(u); };
    this.attachMedia = () => { setTimeout(() => (listeners[Hls.Events.MANIFEST_PARSED] || []).forEach((c) => c()), 0); };
    this.startLoad = () => {}; this.recoverMediaError = () => {};
  }
  Hls.isSupported = () => true;
  Hls.Events = { MANIFEST_PARSED: "hlsManifestParsed", ERROR: "hlsError" };
  Hls.ErrorTypes = { NETWORK_ERROR: "netErr", MEDIA_ERROR: "medErr" };

  const videoListeners = {};
  const video = {
    muted: true, playsInline: false, videoWidth: 1920, videoHeight: 1080,
    addEventListener: (e, cb) => { (videoListeners[e] = videoListeners[e] || []).push(cb); },
    canPlayType: () => "",
    play: () => { setTimeout(() => (videoListeners.loadeddata || []).forEach((c) => c()), 0); return Promise.resolve(); },
    captureStream: () => ({
      getVideoTracks: () => [{ kind: "video", sender: { getStats: async () => new Map() } }],
      getAudioTracks: () => (cfg.audio ? [{ kind: "audio" }] : []),
    }),
  };

  // fake livekit-client
  const RoomEvent = {
    Connected: "connected", Reconnecting: "reconnecting", Reconnected: "reconnected",
    Disconnected: "disconnected", ParticipantConnected: "pc", ParticipantDisconnected: "pd",
  };
  const Track = { Source: { ScreenShare: "screen_share", ScreenShareAudio: "screen_share_audio" } };
  class VideoPreset { constructor(w, h, b, f) { Object.assign(this, { w, h, b, f }); } }
  class LocalVideoTrack { constructor(t) { this.t = t; this.sender = (t && t.sender) || null; } }
  class LocalAudioTrack { constructor(t) { this.t = t; } }
  class Room {
    constructor(opts) { this.opts = opts; this.remoteParticipants = new Map(); this.localParticipant = {
      publishTrack: async (track, options) => { calls.publishTrack.push({ track, options }); return { track }; },
    }; }
    on() { return this; }
    connect = async (url, token) => { this.connected = { url, token }; };
  }
  const LivekitClient = { Room, RoomEvent, Track, LocalVideoTrack, LocalAudioTrack, VideoPreset };

  const fetchImpl = async (path, opts) => {
    if (path === "config") return { ok: true, json: async () => cfg };
    if (path === "status") { try { calls.status.push(JSON.parse(opts.body)); } catch { /* */ } return { ok: true }; }
    return { ok: false, status: 404 };
  };

  const sandbox = {
    window: { LivekitClient, Hls, location: { reload: () => {} } },
    document: { getElementById: (id) => (id === "wall" ? video : null) },
    navigator: {}, fetch: fetchImpl, console: { log: () => {}, error: () => {} },
    setTimeout, clearTimeout, setInterval: () => 0, clearInterval: () => {}, Date,
    // publisher.js uses the BARE globals `Hls`/`LivekitClient` (browser: window.* are
    // also globals) — expose them on the sandbox global too.
    Hls, LivekitClient,
  };
  return { sandbox, calls, video };
}

const CFG = {
  wsUrl: "ws://dev:7880", token: "jwt", room: "channel-g", identity: "mymts-wall-bot",
  hlsUrl: "http://mymts-helper:8082/api/stream/playlist.m3u8", audio: false, fps: 30,
  topBitrate: 8000000,
  layers: [{ w: 1920, h: 1080, bitrate: 8000000, fps: 30 },
           { w: 1280, h: 720, bitrate: 1500000, fps: 15 },
           { w: 640, h: 360, bitrate: 500000, fps: 15 }],
};

const tick = () => new Promise((r) => setTimeout(r, 30));

test("connects, plays the HLS render, publishes screen_share with FORCED simulcast", async () => {
  const { sandbox, calls } = makeEnv({ ...CFG });
  vm.createContext(sandbox);
  vm.runInContext(SRC, sandbox);
  await tick(); await tick();

  // render-once: it loaded the configured HLS (not a re-render / screen-capture).
  assert.deepEqual(calls.loadSource, [CFG.hlsUrl]);
  // exactly one video publish, with the screen_share source + forced simulcast + layers.
  const pubs = calls.publishTrack;
  assert.ok(pubs.length >= 1, "publishTrack was called");
  const { options } = pubs[0];
  assert.equal(options.source, "screen_share");                 // note D
  assert.equal(options.simulcast, true);                        // note B: forced ON
  assert.equal(options.screenShareSimulcastLayers.length, 3);   // 1080/720/360
  assert.deepEqual(
    options.screenShareSimulcastLayers.map((l) => [l.w, l.h]),
    [[1920, 1080], [1280, 720], [640, 360]],
  );
  assert.equal(options.videoEncoding.maxBitrate, 8000000);
  // it reported "publishing".
  assert.ok(calls.status.some((s) => s.state === "publishing"));
});

test("video-only by default — no screen_share_audio publish", async () => {
  const { sandbox, calls } = makeEnv({ ...CFG, audio: false });
  vm.createContext(sandbox);
  vm.runInContext(SRC, sandbox);
  await tick(); await tick();
  assert.ok(!calls.publishTrack.some((p) => p.options.source === "screen_share_audio"));
});

test("audio:true also publishes a screen_share_audio track", async () => {
  const { sandbox, calls } = makeEnv({ ...CFG, audio: true });
  vm.createContext(sandbox);
  vm.runInContext(SRC, sandbox);
  await tick(); await tick();
  assert.ok(calls.publishTrack.some((p) => p.options.source === "screen_share_audio"),
    "screen_share_audio published when audio on");
});
