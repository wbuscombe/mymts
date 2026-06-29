# Mercury output — wire-up notes

> **Status: IMPLEMENTED (2026-06-29); pending prod creds + tailnet.** The real LiveKit
> publisher now ships at the `MERCURY-WIRE-UP` seam, verified end-to-end against a local
> dev SFU. The mechanism + the full investigation (why the browser SDK, why
> `captureStream` of the HLS instead of `getDisplayMedia`, the dev-SFU recipe) live in
> **`docs/decisions/0003`**. This file is the original spec, kept for the note-by-note
> requirements (E/K/L/B/D/C/G/H/I/J/M/18) it captured — all of which the implementation
> honors.

The abstraction (`MercuryPublisher`) and the renderer's output manager route the
`mercury` output to the publisher. The swap point is the greppable boundary comment:

```
# MERCURY-WIRE-UP: the real LiveKit screen_share publisher (browser SDK on the
# shared Xvfb) — replaces the former StubMercuryPublisher.
```

## What ships now (the real publisher; stub = no-creds fallback)

- The wall config has an `outputs.mercury` entry (disabled by default), pre-fillable
  in `/control/` (`channel_guid`, `display_name`, resolution ≤1080p, bitrate, audio).
  **No secrets in the config.**
- The renderer composites the wall ONCE → HLS. The Mercury publisher (a publisher Chrome
  running `livekit-client`) RE-USES that single render: it plays the HLS and publishes
  `video.captureStream()` as a simulcast `source: screen_share` track (render-once; no
  second compositing). The token is minted server-side; the API secret never reaches the
  browser.
- With **no `LIVEKIT_API_KEY`/`SECRET`** the renderer falls back to the inert
  **`StubMercuryPublisher`**: `disabled`/`needs_setup`, opens no socket, mints no token,
  short-circuits the tailnet probe until key + channel are present → the default (no
  creds) performs **zero** egress to Mercury.

## The five things the operator (Ryan) must supply

1. A **dedicated** LiveKit API key/secret (so the leaked dev secret can finally be
   rotated, and the bot's traffic is attributable) → `LIVEKIT_API_KEY` /
   `LIVEKIT_API_SECRET` in the gitignored `.env`.
2. The production SFU's `node_ip` (the tailnet media IP) — confirm which config the
   live server runs (the production SFU, not the dev one).
3. The NAS joined to the **tailnet** (media is UDP 50000–60000 to the SFU host; the
   signaling port — typically `…:7095/livekit-proxy` — is `LIVEKIT_HOST`).
4. The target **channel GUID** (`outputs.mercury.channel_guid`, set in `/control/`).
5. **Codec** confirmation (the in-call browsers decode H.264 reliably).

## What the real `MercuryPublisher` impl must do

- **Mint a JWT** off `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` for room
  `channel-{channel_guid}`, with a **dedicated identity** (`LIVEKIT_BOT_IDENTITY`,
  default `mymts-wall-bot`) — NEVER a real user's GUID (that would duplicate-identity-
  kick the user). A publish-only grant (`canPublish=true`, `canSubscribe=false`) is the
  tidy choice for a one-way wall publisher.
- **Connect** to `LIVEKIT_HOST` (the signaling URL) and publish a video track tagged
  **`source: screen_share`** (LiveKit `Track.Source.ScreenShare`) — this is load-
  bearing: the in-call web clients auto-render any `screen_share` video track and
  would ignore a `camera` track. Optionally publish a `screen_share_audio` track when
  `outputs.mercury.audio` is true. The media comes from the **Mercury encode branch**
  of the renderer's single capture (an H.264 stream at the Mercury resolution/bitrate),
  fed to the LiveKit publisher (e.g. via a LiveKit ingress, the Go/Node SDK with a
  custom track source, or piping the encode branch into the publisher).
- **Self-reconnect** on a transport blip (no Mercury account/heartbeat applies to a
  pure publisher; the publisher owns its own reconnect). Honor the per-output
  `restart_epoch` (cycle the publisher) and `enabled` (start/stop).
- **Watchable, not a badge.** A pure publisher shows up as a watchable stream with **no
  Mercury LIVE badge** (that needs a Mercury account + cookie + `/join` +
  `screenshare/start` + a 10s heartbeat — out of scope; the wall is a watchable feed).
- **Status / honesty.** Replace the stub's `status()` with real connection state
  (`connecting` / `publishing` / `error`) while keeping the same `{state, checklist}`
  shape the status file + `/control/` expect. Never report "publishing" without an
  actual published track.

## Boundary discipline

- Secrets stay in the gitignored `.env`, referenced by var name; never echoed, catted,
  logged, or written into the wall config or the status file.
- The publisher is the renderer's only network egress to Mercury; it rides the same
  residential WAN / tailnet path as the rest of the renderer, **never the PIA VPN**.
