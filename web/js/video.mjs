// In-browser HLS video tiles for the LAN web client's 2×2 grid.
//
// Mirrors the native wall's player: plays the SAME public HLS URLs the
// helper resolves via /api/channels, using the vendored hls.js (or the
// browser's native HLS where supported, e.g. Safari). This is inert
// stream PLAYBACK — the same thing the native ExoPlayer does — NOT
// article-web-reading; the A1 closed door is about a web *reader*, which
// this is not. Honest degradation: a stream that won't load shows an
// honest "offline" tile, never a faked-live one.
//
// hls.js is vendored at web/vendor/hls.min.js (pinned) and loaded as a
// classic script (it attaches `window.Hls`), so the page CSP stays
// `script-src 'self'` — no CDN. The CSP's connect-src/media-src are
// widened to https: for the arbitrary stream CDNs (documented); the
// client holds no credentials, so there is nothing to exfiltrate.

const Hls = () => window.Hls;

/**
 * Attach a channel's HLS stream to a <video>. Returns a teardown fn.
 * onState(state) is called with "live" once playback starts, or
 * "offline" if the stream errors / can't load — so the tile can label
 * itself honestly (never shows a dead stream as live).
 */
export function attachStream(videoEl, url, onState) {
  let destroyed = false;
  let hls = null;

  const fail = (why) => {
    if (destroyed) return;
    onState("offline", why);
  };

  if (videoEl.canPlayType("application/vnd.apple.mpegurl")) {
    // Native HLS (Safari / some TVs).
    videoEl.src = url;
    videoEl.addEventListener("loadeddata", () => !destroyed && onState("live"), { once: true });
    videoEl.addEventListener("error", () => fail("native-error"), { once: true });
  } else if (Hls() && Hls().isSupported()) {
    hls = new (Hls())({ lowLatencyMode: false, enableWorker: true, maxBufferLength: 10 });
    hls.on(Hls().Events.MANIFEST_PARSED, () => { if (!destroyed) videoEl.play().catch(() => {}); });
    hls.on(Hls().Events.FRAG_BUFFERED, () => !destroyed && onState("live"));
    hls.on(Hls().Events.ERROR, (_e, data) => { if (data && data.fatal) fail("hls-fatal"); });
    hls.loadSource(url);
    hls.attachMedia(videoEl);
  } else {
    fail("no-hls-support");
  }

  return function teardown() {
    destroyed = true;
    try { if (hls) hls.destroy(); } catch (_) { /* ignore */ }
    try { videoEl.removeAttribute("src"); videoEl.load(); } catch (_) { /* ignore */ }
  };
}
