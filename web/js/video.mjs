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
// Honest degradation (C3): onState reports the true state —
//   "live"       once frames are actually rendering (the `playing` event),
//   "needgesture" if the stream loaded but autoplay was blocked (the tile
//                 shows a click-to-play affordance; calling play() on the
//                 returned handle, from a user click, starts it),
//   "error"      if the stream cannot load (dead/geo/CORS/mixed-content) —
//                 the tile then shows the honest "on the TV wall" state.
// A dead stream is NEVER shown as live.

const Hls = () => window.Hls;

/**
 * Attach a channel's HLS stream to a <video>. Returns { play, teardown }:
 *  - play()    — (re)attempt playback; call it from a user click to clear
 *                an autoplay block ("needgesture").
 *  - teardown()— destroy hls + release the <video>.
 */
export function attachStream(videoEl, url, onState) {
  let destroyed = false;
  let errored = false;
  let hls = null;
  const set = (s, d) => { if (!destroyed) onState(s, d); };

  const tryPlay = () => {
    const p = videoEl.play();
    if (p && typeof p.then === "function") {
      // Resolved → playing (the `playing` event also confirms). Rejected →
      // autoplay policy blocked it; surface a click-to-play affordance.
      p.then(() => {}).catch(() => set("needgesture"));
    }
  };

  videoEl.addEventListener("playing", () => set("live"));

  if (videoEl.canPlayType("application/vnd.apple.mpegurl")) {
    // Native HLS (Safari / some TVs).
    videoEl.src = url;
    videoEl.addEventListener("loadeddata", () => tryPlay(), { once: true });
    videoEl.addEventListener("error", () => set("error", "native"), { once: true });
  } else if (Hls() && Hls().isSupported()) {
    hls = new (Hls())({ lowLatencyMode: false, enableWorker: false, maxBufferLength: 12, backBufferLength: 12 });
    hls.on(Hls().Events.MANIFEST_PARSED, () => tryPlay());
    hls.on(Hls().Events.ERROR, (_e, data) => {
      // Only FATAL errors are honest "can't play"; hls.js auto-recovers
      // transient ones. Fatal = it gave up after its own retries. Latch on
      // the FIRST fatal: hls.js can emit several fatals for one dead stream,
      // and onState("error") must fire at most once (matching the native
      // path's { once: true }) so the tile doesn't stack repeated state boxes.
      if (data && data.fatal && !errored) { errored = true; set("error", (data.type || "hls")); }
    });
    hls.loadSource(url);
    hls.attachMedia(videoEl);
  } else {
    set("error", "no-hls-support");
  }

  return {
    play: tryPlay,
    teardown() {
      destroyed = true;
      try { if (hls) hls.destroy(); } catch (_) { /* ignore */ }
      try { videoEl.removeAttribute("src"); videoEl.load(); } catch (_) { /* ignore */ }
    },
  };
}
