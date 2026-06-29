// MyMTS Mercury publisher — the browser side (livekit-client).
//
// Publishes the wall into the Mercury room as a SIMULCAST `source: screen_share`
// track via livekit-client. The browser SDK is mandatory: Mercury's hand-rolled
// `/livekit-proxy` is only ever exercised by livekit-client (note M), so the browser
// client traverses the exact paths it's proven against.
//
// CAPTURE SOURCE — render-once preserved. The wall is composited ONCE by the wall
// Chrome → ffmpeg → HLS. This publisher re-uses THAT single render: it plays the HLS
// in a hidden <video> (hls.js) and publishes `video.captureStream()`. No second
// compositing, and no framebuffer screen-capture — getDisplayMedia screen/window
// capture is non-functional under the renderer's headless Xvfb (Chromium's X11
// desktop capturer fails `SelectSource` with no XRandR monitor); captureStream of the
// HLS is the robust, render-once equivalent (and carries the wall's mixed audio).
// See docs/decisions/0003 for the investigation. Config (incl. the short-lived JWT —
// never the API secret) is fetched from the localhost control server; status POSTed back.

/* global LivekitClient, Hls */
const LK = window.LivekitClient;
const { Room, RoomEvent, Track, LocalVideoTrack, LocalAudioTrack, VideoPreset } = LK;

const logEl = document.getElementById("log");
function logLine(m) {
  if (logEl) logEl.textContent = (`${m}\n${logEl.textContent}`).slice(0, 4000);
  console.log("[mercury-pub]", m);
}

// ---- status reporting to the localhost control server ----
let lastState = "connecting";
function report(state, extra = {}) {
  lastState = state;
  try {
    fetch("status", {
      method: "POST", keepalive: true,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ state, ts: Date.now() / 1000, ...extra }),
    }).catch(() => {});
  } catch { /* control server transient */ }
}

// ---- reconnect at the page level (re-mint the token), backed off (notes 18, G) ----
let reconnectAttempt = 0;
let reloadTimer = null;
function scheduleReload(why) {
  if (reloadTimer) return;
  const delay = Math.min(30000, 1000 * 2 ** reconnectAttempt);
  reconnectAttempt += 1;
  report("reconnecting", { detail: why, backoff_ms: delay });
  logLine(`reload #${reconnectAttempt} in ${delay}ms (${why})`);
  reloadTimer = setTimeout(() => { window.location.reload(); }, delay);
}

function countViewers(room) {
  try { return room.remoteParticipants ? room.remoteParticipants.size : 0; }
  catch { return 0; }
}

// ---- dynacast-aware idle detection (note C: pausing the upstream when the channel
//      is empty is NORMAL — report dynacast_paused, NEVER tear down) ----
function startStatsLoop(room, videoTrack) {
  let lastBytes = 0, lastAt = Date.now();
  setInterval(async () => {
    if (lastState === "reconnecting" || lastState === "error") return;
    const viewers = countViewers(room);
    let kbps = null;
    try {
      const sender = videoTrack && videoTrack.sender;
      if (sender && sender.getStats) {
        const stats = await sender.getStats();
        let bytes = 0;
        stats.forEach((r) => { if (r.type === "outbound-rtp" && !r.isRemote) bytes += (r.bytesSent || 0); });
        const now = Date.now(), dt = (now - lastAt) / 1000;
        if (dt > 0 && lastBytes > 0) kbps = Math.round(((bytes - lastBytes) * 8) / 1000 / dt);
        lastBytes = bytes; lastAt = now;
      }
    } catch { /* stats unavailable → report publishing */ }
    if (kbps !== null && kbps < 8) report("dynacast_paused", { viewers, kbps });
    else report("publishing", { viewers, kbps });
  }, 3000);
}

async function getConfig() {
  const r = await fetch("config", { cache: "no-store" });
  if (!r.ok) throw new Error(`config → HTTP ${r.status}`);
  return r.json();
}

// ---- play the wall's HLS (the single render) into a hidden <video> ----
function playHls(video, url, audio) {
  return new Promise((resolve, reject) => {
    video.muted = !audio;          // muted → guaranteed autoplay; unmuted to capture audio
    video.playsInline = true;
    let settled = false;
    const onData = () => {
      if (settled) return;
      if (video.videoWidth > 0) { settled = true; resolve(); }
    };
    video.addEventListener("loadeddata", onData);
    video.addEventListener("playing", onData);
    if (window.Hls && Hls.isSupported()) {
      const hls = new Hls({ lowLatencyMode: false, backBufferLength: 15,
        manifestLoadingMaxRetry: 30, fragLoadingMaxRetry: 30 });
      hls.on(Hls.Events.MANIFEST_PARSED, () => { video.play().catch(() => {}); });
      hls.on(Hls.Events.ERROR, (_e, d) => {
        if (d && d.fatal) { if (d.type === Hls.ErrorTypes.NETWORK_ERROR) hls.startLoad();
          else if (d.type === Hls.ErrorTypes.MEDIA_ERROR) hls.recoverMediaError();
          else { settled || reject(new Error(`hls fatal ${d.details}`)); } }
      });
      hls.loadSource(url); hls.attachMedia(video);
    } else if (video.canPlayType("application/vnd.apple.mpegurl")) {
      video.src = url; video.play().catch(() => {});
    } else {
      reject(new Error("no HLS support")); return;
    }
    setTimeout(() => { settled || reject(new Error("HLS first frame timeout")); }, 25000);
  });
}

async function main() {
  report("connecting");
  const cfg = await getConfig();
  if (!cfg.wsUrl || !cfg.token || !cfg.hlsUrl) { report("error", { detail: "missing wsUrl/token/hlsUrl" }); return; }

  const room = new Room({ adaptiveStream: true, dynacast: true });
  room
    .on(RoomEvent.Connected, () => { reconnectAttempt = 0; report("connected", { viewers: countViewers(room) }); })
    .on(RoomEvent.Reconnecting, () => report("reconnecting", { detail: "transport blip" }))
    .on(RoomEvent.Reconnected, () => { reconnectAttempt = 0; report("connected", { viewers: countViewers(room) }); })
    .on(RoomEvent.Disconnected, (reason) => scheduleReload(`disconnected:${reason ?? "?"}`))
    .on(RoomEvent.ParticipantConnected, () => report(lastState, { viewers: countViewers(room) }))
    .on(RoomEvent.ParticipantDisconnected, () => report(lastState, { viewers: countViewers(room) }));

  await room.connect(cfg.wsUrl, cfg.token);
  logLine(`connected to room ${cfg.room} as ${cfg.identity}`);

  // Play the single HLS render, then capture it (render-once — no re-compositing).
  const video = document.getElementById("wall");
  await playHls(video, cfg.hlsUrl, !!cfg.audio);
  logLine(`HLS playing ${video.videoWidth}x${video.videoHeight} — capturing`);
  const stream = video.captureStream(cfg.fps || 30);
  const [vmst] = stream.getVideoTracks();
  if (!vmst) { report("error", { detail: "no captured video track" }); return; }

  const videoTrack = new LocalVideoTrack(vmst);
  // FORCE simulcast ON for the screen-share source (note B): default-off in many
  // builds, so set simulcast:true AND supply explicit layers (top = mercury.resolution
  // ≤1080p, plus mid + low) — else a viewer's ~320px mini-viewer pulls full res and
  // adaptiveStream/dynacast can't downscale.
  const layers = (cfg.layers || []).map((l) => new VideoPreset(l.w, l.h, l.bitrate, l.fps));
  const pub = await room.localParticipant.publishTrack(videoTrack, {
    source: Track.Source.ScreenShare,          // note D: viewers only render screen_share
    simulcast: true,
    screenShareSimulcastLayers: layers,
    videoEncoding: { maxBitrate: cfg.topBitrate, maxFramerate: cfg.fps || 30 },
    degradationPreference: "maintain-resolution",
  });
  logLine(`published screen_share (simulcast=${layers.length} layers, top=${cfg.topBitrate})`);

  // Audio (opt-in): the HLS carries the wall's mixed audio; publish it as
  // screen_share_audio — its own deafen-aware path (note D). Video-only by default.
  if (cfg.audio) {
    const [amst] = stream.getAudioTracks();
    if (amst) {
      try {
        await room.localParticipant.publishTrack(new LocalAudioTrack(amst),
          { source: Track.Source.ScreenShareAudio });
        logLine("published screen_share_audio");
      } catch (e) { logLine(`audio publish skipped: ${e?.message || e}`); }
    }
  }

  report("publishing", { viewers: countViewers(room), hasTrack: true });
  startStatsLoop(room, pub.track || videoTrack);
}

main().catch((e) => {
  logLine(`fatal: ${e?.message || e}`);
  report("error", { detail: String(e?.message || e) });
  scheduleReload("init-error");
});
