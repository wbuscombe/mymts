// In-browser HLS video tiles for the LAN web client's cell-count grid.
//
// Mirrors the native wall's player: plays the SAME public HLS URLs the
// helper resolves via /api/channels, using the vendored hls.js (or the
// browser's native HLS where supported, e.g. Safari). This is inert
// stream PLAYBACK — the same thing the native ExoPlayer does — NOT
// article-web-reading; the A1 closed door is about a web *reader*, which
// this is not.
//
// hls.js is vendored at web/vendor/hls.min.js (pinned, UMD → window.Hls)
// and loaded as a classic script, so the page CSP stays `script-src
// 'self'` — no CDN. We run hls.js with **enableWorker:false** on purpose:
// a worker would be spawned from a blob: URL, which the locked CSP
// (`default-src 'self'`, no `worker-src`) blocks — so disabling it keeps
// the CSP as tight as the prior version (no worker-src blob: widening)
// at the cost of main-thread demux, which the browser host (a laptop, not
// the constrained S905Y4) handles easily for a few news tiles.
//
// Honest degradation (C3): onState(state, detail) reports the true state —
//   "live"        once frames are actually rendering (the `playing` event),
//   "needgesture" if the stream loaded but autoplay was blocked (the tile
//                 shows a click-to-play affordance; calling play() on the
//                 returned handle, from a user click, starts it),
//   "stall"       if it was playing and then froze (currentTime stopped
//                 advancing while not paused/ended) — a transient,
//                 retryable failure,
//   "error"       if the stream cannot load (dead/geo/CORS/mixed-content/
//                 DRM/codec) — detail carries { kind, details } so the
//                 caller can tell a transient failure (retry on a backoff)
//                 from a genuinely-unplayable one (mark honestly, no retry).
// A dead stream is NEVER shown as live, and a hopeless one is NEVER retried
// forever — the retry/give-up policy lives in render.mjs (pure, unit-tested);
// this module only surfaces the honest signal it acts on.

const Hls = () => window.Hls;

// MediaError.code → a stable string render.mjs's classifier can read. Only
// SRC_NOT_SUPPORTED is genuinely-unplayable; NETWORK/DECODE are transient.
const MEDIA_ERR = {
  1: "MEDIA_ERR_ABORTED",
  2: "MEDIA_ERR_NETWORK",
  3: "MEDIA_ERR_DECODE",
  4: "MEDIA_ERR_SRC_NOT_SUPPORTED",
};

// A stream that was playing and then froze this long (currentTime not
// advancing while not paused/ended) is treated as a stall and retried.
const STALL_MS = 7000;

/**
 * Attach a channel's HLS stream to a <video>. Returns { play, teardown }:
 *  - play()    — (re)attempt playback; call it from a user click to clear
 *                an autoplay block ("needgesture").
 *  - teardown()— destroy hls + release the <video> + stop the watchdog.
 */
export function attachStream(videoEl, url, onState) {
  let destroyed = false;
  let errored = false;
  let hls = null;

  // Stall watchdog state — only armed after the first "playing", so the
  // initial manifest-fetch/buffer window is never mis-read as a stall
  // (a failure to start surfaces as a network/media error instead).
  let started = false;
  let stalled = false;
  let lastTime = 0;
  let lastProgressAt = 0;
  let watchdog = null;

  const set = (s, d) => { if (!destroyed) onState(s, d); };

  const tryPlay = () => {
    const p = videoEl.play();
    if (p && typeof p.then === "function") {
      // Resolved → playing (the `playing` event also confirms). Rejected →
      // autoplay policy blocked it; surface a click-to-play affordance.
      p.then(() => {}).catch(() => set("needgesture"));
    }
  };

  videoEl.addEventListener("playing", () => {
    started = true;
    lastTime = videoEl.currentTime;
    lastProgressAt = Date.now();
    set("live");
  });
  videoEl.addEventListener("timeupdate", () => {
    if (videoEl.currentTime !== lastTime) { lastTime = videoEl.currentTime; lastProgressAt = Date.now(); }
  });

  watchdog = setInterval(() => {
    if (destroyed || stalled || !started) return;
    // Paused (incl. autoplay-blocked) or ended → not a stall; keep the clock fresh.
    if (videoEl.paused || videoEl.ended) { lastProgressAt = Date.now(); return; }
    if (Date.now() - lastProgressAt > STALL_MS) {
      stalled = true;
      set("stall", { kind: "stall" });
    }
  }, 2000);

  if (videoEl.canPlayType("application/vnd.apple.mpegurl")) {
    // Native HLS (Safari / some TVs).
    videoEl.src = url;
    videoEl.addEventListener("loadeddata", () => tryPlay(), { once: true });
    videoEl.addEventListener("error", () => {
      const code = videoEl.error ? videoEl.error.code : 0;
      set("error", { kind: "native", details: MEDIA_ERR[code] || "MEDIA_ERR_UNKNOWN" });
    }, { once: true });
  } else if (Hls() && Hls().isSupported()) {
    hls = new (Hls())({ lowLatencyMode: false, enableWorker: false, maxBufferLength: 12, backBufferLength: 12 });
    hls.on(Hls().Events.MANIFEST_PARSED, () => tryPlay());
    hls.on(Hls().Events.ERROR, (_e, data) => {
      // Only FATAL errors are honest "can't play"; hls.js auto-recovers
      // transient ones internally. Fatal = it gave up after its own retries.
      // Latch on the FIRST fatal: hls.js can emit several fatals for one dead
      // stream, and onState("error") must fire at most once (matching the
      // native path's { once: true }) so the tile doesn't stack state boxes.
      // We forward both type AND details so the classifier can separate a
      // transient networkError (retry) from a keySystem/codec error (no retry).
      if (data && data.fatal && !errored) {
        errored = true;
        set("error", { kind: data.type || "hls", details: data.details || "" });
      }
    });
    hls.loadSource(url);
    hls.attachMedia(videoEl);
  } else {
    set("error", { kind: "no-hls-support" });
  }

  return {
    play: tryPlay,
    teardown() {
      destroyed = true;
      if (watchdog) { clearInterval(watchdog); watchdog = null; }
      try { if (hls) hls.destroy(); } catch (_) { /* ignore */ }
      try { videoEl.removeAttribute("src"); videoEl.load(); } catch (_) { /* ignore */ }
    },
  };
}
