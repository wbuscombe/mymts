# 0003 — Mercury output: the real LiveKit screen_share publisher

**Status:** accepted (2026-06-29) · renderer / helper / web · native unchanged

Replaces `renderer/mercury.py`'s `StubMercuryPublisher` with a real LiveKit publisher
at the `MERCURY-WIRE-UP` seam: the wall is published into a Mercury voice channel as a
simulcast `source: screen_share` track. Built + **verified end-to-end against a
throwaway local LiveKit dev SFU**; it goes live on Mercury as a pure config swap once
the prod credentials + tailnet membership land.

## Decisions

1. **Browser SDK (`livekit-client`), not a Go/Rust server SDK.** Mercury's
   `/livekit-proxy` is ~80 lines of hand-rolled middleware only ever exercised by the
   browser `livekit-client` (note M). A server SDK would traverse unproven paths. So a
   dedicated **publisher Chrome** runs inside the renderer container and runs
   `livekit-client` (vendored UMD, same-origin, no CDN). The token is minted
   **server-side** (`mercury.mint_livekit_token`, HS256, no PyJWT) — the API secret
   never reaches the browser; only the short-lived (24h, re-minted on reload) JWT does,
   via a localhost control server. The grant is exactly publish-only:
   `roomJoin + canPublish`, `canSubscribe=false` (else every participant's mic is
   pulled to the NAS — note E), `canPublishData=false`; identity `mymts-wall-bot`
   (DUPLICATE_IDENTITY cleanly evicts a stale connection — note K), name
   `MyMTS News Wall` (note L).

2. **Capture source = the single HLS render via `captureStream()`, NOT
   `getDisplayMedia`.** The intended mechanism was `getDisplayMedia` of the Xvfb
   framebuffer, but Chromium's X11 desktop capturer is **non-functional under the
   renderer's headless Xvfb**: `ScreenCapturerX11::SelectSource` returns false (the
   screen id maps to no XRandR monitor) and `WindowCapturerX11` fails too —
   `NotReadableError: Could not start video source` across every flag combination
   (no-pipewire, usermedia-screen-capturing, swiftshader, GPU-on, window-by-title).
   So the publisher instead **re-uses the one composite the renderer already produces**:
   it plays the wall's HLS (`http://mymts-helper:8082/api/stream`) in a hidden
   `<video>` via hls.js and publishes `video.captureStream()`. This is render-once
   (literally the single render output — no second compositing), works headlessly, and
   carries the wall's mixed audio. Trade-off: HLS adds ~8–12s of latency vs the live
   framebuffer — acceptable for an ambient news wall; the framebuffer path remains the
   lower-latency ideal if the renderer ever moves to a real X server.

3. **Off-screen publisher window + anti-backgrounding flags.** The publisher Chrome's
   window is parked off the WxH framebuffer (`--window-position={W},0`) so it never
   pollutes the wall's HLS `x11grab`. But Chrome **suspends media decode in occluded /
   off-screen windows** — the `<video>` stalls at `readyState 0` and captureStream
   produces no frames. `--disable-backgrounding-occluded-windows`,
   `--disable-renderer-backgrounding`, `--disable-background-timer-throttling`, and
   `--disable-features=CalculateNativeWinOcclusion` keep the off-screen renderer fully
   alive (verified: with them the off-screen window reaches `loadeddata` + frames;
   without them it stalls forever).

4. **FORCE screen-share simulcast.** Screen-share simulcast is OFF by default in the
   SDK, so the publisher sets `simulcast: true` AND supplies an explicit
   `screenShareSimulcastLayers` ladder (top = `mercury.resolution` ≤1080p, plus 720p +
   360p) — else every viewer pulls full res regardless of their ~320px mini-viewer and
   `adaptiveStream`/`dynacast` can't downscale (note B). Audio (opt-in) publishes as
   `source: screen_share_audio` — its own deafen-aware path (note D); video-only by
   default.

5. **Operational facts baked in.** DNS: Chrome's `--host-resolver-rules` maps
   `chat.mercurychat.net` → `LIVEKIT_NODE_IP` (the tailnet IP) so the cert validates
   without `/etc/hosts`/root (note H). Reconnect: `livekit-client` handles transport
   blips; a hard `Disconnected` triggers a page reload with exponential backoff (single
   instance — no join/leave chime flapping, note G), re-minting the token (note 18).
   `dynacast` pausing the upstream when the channel is empty is **normal** — reported as
   `dynacast_paused`, never torn down (note C). The CORS header `Access-Control-Allow-Origin: *`
   was added to the helper's `/api/stream` endpoint (public video; the publisher fetches
   the HLS cross-origin).

6. **No-creds fallback = the stub (zero egress).** `make_publisher()` returns the real
   publisher only when `LIVEKIT_API_KEY`+`SECRET` are present, else the inert
   `StubMercuryPublisher` (needs-setup, no socket, no token) — so an un-credentialed
   renderer performs ZERO egress to Mercury, exactly as before.

## Dev-SFU verification recipe (repeatable)

A throwaway local `livekit/livekit-server --dev` (keys `devkey/secret`, ws :7880) on
the NAS `mymts-net`, the publisher pointed at it via the harness
(`renderer/tools/mercury_devsfu_harness.py`, mounted into a one-off container from the
renderer image), playing the **live wall HLS**:

```sh
docker run -d --rm --name mymts-lk-dev --network mymts-net livekit/livekit-server --dev --bind 0.0.0.0
docker run -d --rm --network mymts-net --shm-size=512m \
  -v <repo>/renderer/tools/mercury_devsfu_harness.py:/app/harness.py:ro \
  -e LIVEKIT_API_KEY=devkey -e LIVEKIT_API_SECRET=secret -e LIVEKIT_HOST=ws://mymts-lk-dev:7880 \
  -e RENDER_HLS_URL=http://mymts-helper:8082/api/stream/playlist.m3u8 \
  --name mymts-mercury-harness mymts-renderer:<tag> python3 /app/harness.py
# verify with the livekit CLI, then tear both down:
docker run --rm --network mymts-net -e LIVEKIT_URL=ws://mymts-lk-dev:7880 \
  -e LIVEKIT_API_KEY=devkey -e LIVEKIT_API_SECRET=secret livekit/livekit-cli \
  room participants get --room channel-devtest --identity mymts-wall-bot
docker rm -f mymts-mercury-harness mymts-lk-dev
```

**Verified:** (a) a `source: SCREEN_SHARE` video track; (b) `simulcast: true` with 3
layers (360p/720p/1080p, rids q/h/f); (c) publish-only (`canPublish: true`;
`canSubscribe`/`canPublishData` absent = false); (d) `dynacast_paused` when idle (the
publisher stays up, NOT torn down); (e) a forced SFU stop → `reconnecting` (backoff) →
`publishing` on restart.

## Consequences

- The published codec is **VP8** (livekit-client re-encodes the captured frames for the
  screen-share track); in-call browsers decode VP8 universally — a simpler answer than
  the H.264 codec question in the wireup notes (note 5).
- The top simulcast layer settles at livekit's screen-share default (~2.5Mbps at 1080p)
  rather than the configured 8Mbps — fine for a chat pipe, tunable later.
- Native untouched (renderer/helper/web only) → no APK, kept in `[Unreleased]`. PIA /
  other containers untouched. The single remaining boundary: drop in Mercury's
  `APIKey`/`APISecret` (gitignored `.env`), set `outputs.mercury.channel_guid`, join the
  NAS to the tailnet → enable the card → live. See `docs/mercury-wireup-notes.md`.
