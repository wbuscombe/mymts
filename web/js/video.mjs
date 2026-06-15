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
//   "captions"    when the set of SOFT caption tracks becomes known/changes —
//                 detail { available } — so the per-tile control can show the
//                 honest "CC on/off" vs "CC —" (no soft track) state.
// A dead stream is NEVER shown as live, and a hopeless one is NEVER retried
// forever — the retry/give-up policy lives in render.mjs (pure, unit-tested);
// this module only surfaces the honest signal it acts on.
//
// Captions (B): only SOFT (separate-track) captions can be toggled — disabling
// the text renderer. Captions BURNED INTO the video pixels (e.g. a network's
// scrolling chyron) are part of the image and CANNOT be removed; when a stream
// has no soft track we report `available:false` so the UI says so honestly
// rather than pretending a toggle did anything.
//
// Audio (C): each tile starts MUTED (the autoplay rule blocks autoplay WITH
// sound; tiles must autoplay muted). `setAudible(true)` simply unmutes the
// <video>; the caller invokes it from a user click (a valid gesture) and
// enforces the single-audible-tile model (mute all others) itself.

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

  // Caption state: whether the stream exposes a SOFT (separate-track) caption
  // track, and the operator's desired on/off. Default OFF (the renderer is
  // disabled until `setCaptions(true)`), matching the native captions-off default.
  let softCaptions = false;
  let desiredCaptions = false;

  const set = (s, d) => { if (!destroyed) onState(s, d); };

  // ----- soft caption tracks (hls.js subtitle API, or native <video> textTracks) -----
  const refreshSoftCaptions = () => {
    if (hls) {
      softCaptions = !!(hls.subtitleTracks && hls.subtitleTracks.length > 0);
    } else {
      const tt = videoEl.textTracks;
      softCaptions = !!tt && Array.from(tt).some(
        (t) => t.kind === "subtitles" || t.kind === "captions");
    }
  };
  const applyCaptions = () => {
    const on = desiredCaptions && softCaptions;
    if (hls) {
      // hls.js: master render switch + track selection (-1 = none).
      try { hls.subtitleDisplay = on; } catch (_) { /* older hls */ }
      try { hls.subtitleTrack = on ? 0 : -1; } catch (_) { /* none to select */ }
    } else {
      const tt = videoEl.textTracks;
      if (tt) {
        let firstCaption = -1;
        for (let i = 0; i < tt.length; i++) {
          if (tt[i].kind === "subtitles" || tt[i].kind === "captions") { firstCaption = i; break; }
        }
        for (let i = 0; i < tt.length; i++) {
          tt[i].mode = (on && i === firstCaption) ? "showing" : "disabled";
        }
      }
    }
  };
  // Track availability can arrive asynchronously (after the manifest/segments
  // load); re-apply the desired state and tell the caller so the control updates.
  const onTracksChanged = () => {
    refreshSoftCaptions();
    applyCaptions();
    set("captions", { available: softCaptions });
  };

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
    // Native: caption tracks surface on the <video>'s textTracks list as the
    // manifest loads. `addtrack` fires per track; re-evaluate availability then.
    if (videoEl.textTracks && typeof videoEl.textTracks.addEventListener === "function") {
      videoEl.textTracks.addEventListener("addtrack", onTracksChanged);
      videoEl.textTracks.addEventListener("removetrack", onTracksChanged);
    }
  } else if (Hls() && Hls().isSupported()) {
    hls = new (Hls())({ lowLatencyMode: false, enableWorker: false, maxBufferLength: 12, backBufferLength: 12 });
    hls.on(Hls().Events.MANIFEST_PARSED, () => tryPlay());
    // Subtitle tracks are parsed from the manifest; this fires once known and
    // on any change. We start with the renderer OFF (subtitleTrack = -1).
    hls.on(Hls().Events.SUBTITLE_TRACKS_UPDATED, onTracksChanged);
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
    /** Enable/disable SOFT captions on this tile. Returns whether a soft track
     *  exists (false → nothing to toggle; burned-in captions can't be removed).
     *  Mirrors native StreamPlayer.setCaptionsEnabled. */
    setCaptions(on) {
      desiredCaptions = on === true;
      refreshSoftCaptions();
      applyCaptions();
      return softCaptions;
    },
    /** Whether this stream exposes a soft caption track (the honest "CC available"). */
    hasCaptions() { return softCaptions; },
    /** Unmute (audible=true) / mute this tile. The caller invokes audible=true
     *  from a user click (the autoplay-required gesture) and mutes the others.
     *  Returns the REALIZED audible state (so the indicator can reflect ground
     *  truth — `muted` is a script-controlled property, set immediately). */
    setAudible(audible) {
      videoEl.muted = audible !== true;
      return !videoEl.muted;
    },
    /** The tile's realized audible state (true = unmuted). Drives the honest
     *  🔊/🔇 indicator from the element itself, not just the session pointer. */
    audible() { return !!videoEl && !videoEl.muted; },
    teardown() {
      destroyed = true;
      if (watchdog) { clearInterval(watchdog); watchdog = null; }
      try {
        if (videoEl.textTracks && typeof videoEl.textTracks.removeEventListener === "function") {
          videoEl.textTracks.removeEventListener("addtrack", onTracksChanged);
          videoEl.textTracks.removeEventListener("removetrack", onTracksChanged);
        }
      } catch (_) { /* ignore */ }
      try { if (hls) hls.destroy(); } catch (_) { /* ignore */ }
      try { videoEl.removeAttribute("src"); videoEl.load(); } catch (_) { /* ignore */ }
    },
  };
}
