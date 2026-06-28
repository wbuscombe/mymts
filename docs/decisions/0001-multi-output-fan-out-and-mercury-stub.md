# 0001 — Multi-output fan-out + Mercury stubbed behind a drop-in seam

**Status:** accepted (2026-06-27) · server / web / renderer · native unchanged

A decision-record for the unified wall-output system: one rendered wall driving N
outputs (HLS today, Mercury later), per-tile audio, and a Mercury publisher that is
an inert shell pending credentials.

## Context

The headless wall composited once and streamed a single HLS output. We want the same
wall to also publish into a Mercury voice channel (a future LiveKit `screen_share`
publisher), and to relax the single-audible-cell model to per-tile audio. The renderer
runs on a **GPU-less** box where the smoothness pass (ARCHITECTURE §31) established that
**compositing**, not encoding, is the bottleneck.

## Decisions

1. **Render once, fan out to N encodes.** Because compositing dominates, the wall is
   composited ONCE at the *derived* render resolution = `max(resolution of enabled
   outputs)`, and each output downscales from that single capture to its own
   resolution + bitrate. We never render the wall twice. (A single output at the
   canvas keeps the original no-filter pipeline, so the shipping VLC stream is
   byte-for-byte the prior behaviour.)

2. **Audio mixes in the browser, not ffmpeg.** Cells flagged `audio:true` play
   unmuted in the render Chromium and combine in its one PulseAudio sink — that sink
   IS the mix, captured once. Per output, `audio:true` muxes that sink (silent if
   nothing is unmuted), `audio:false` omits the track. No explicit ffmpeg mixer; the
   model is predictable either way and any combination of audible cells is allowed.

3. **Mercury is stubbed behind a `MercuryPublisher` seam.** This build ships ONLY a
   `StubMercuryPublisher`: it reports `disabled`/`needs_setup`/`ready_not_wired` with a
   setup checklist, and opens NO socket / mints NO token on start/stop/restart. The
   renderer's output manager routes the `mercury` output to this interface (not an
   ffmpeg encoder), so the real LiveKit publisher is a drop-in at the greppable
   `MERCURY-WIRE-UP` boundary (see `docs/mercury-wireup-notes.md`). The non-connecting
   tailnet probe is short-circuited until the key + channel are present, so the
   default (no creds) performs **zero** network egress.

4. **The schema is a `outputs` MAP, built for per-output views later with no
   migration.** Each output carries its own enabled/resolution/bitrate/audio/
   restart_epoch; adding a third output is a defaults entry + a validator, no
   `schema_version` bump. A load-time migration upgrades the old single
   `render.resolution` + `audible_cell` in place.

5. **v1 = the identical wall on both outputs.** Both outputs encode the *same*
   composited wall (subtitles are burned into the one composite, so they appear
   identically everywhere). Per-output *different* views (a different layout per
   output) are a deliberate future step the map-shaped schema already accommodates.

## Consequences

- The restart matrix avoids thrashing the render: a change that moves the derived
  canvas restarts Xvfb/Chromium; a bitrate/audio/sub-max change respawns only the
  encode; a per-output `restart_epoch` cycles one output. (With one fan-out ffmpeg +
  one publisher, "without touching the others" holds for the real hls+mercury config.)
- The renderer writes a per-output status file into the shared stream volume
  (renderer rw, helper ro — the same trust direction as the HLS stream); the helper
  relays it for `/api/outputs/status`. No new privileged channel.
- Native is untouched (server/web/renderer only) → no APK, kept in `[Unreleased]`.
