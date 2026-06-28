# Mercury output — wire-up notes (the spec for the real LiveKit publisher)

This is a **spec / decision-record** for the future step that replaces
`renderer/mercury.py`'s `StubMercuryPublisher` with a real LiveKit publisher. The
abstraction (`MercuryPublisher`) and the renderer's output manager already route the
`mercury` output to it; only the *implementation* is deferred. The swap point is the
greppable boundary comment:

```
# MERCURY-WIRE-UP: replace StubMercuryPublisher with the real LiveKit
# screen_share publisher
```

## What ships today (the inert shell)

- The wall config has an `outputs.mercury` entry (disabled by default), pre-fillable
  in `/control/` (`channel_guid`, `display_name`, resolution ≤1080p, bitrate, audio).
  **No secrets in the config.**
- The renderer composites the wall ONCE and could fan a Mercury encode branch off the
  same capture (an H.264 video at the Mercury resolution/bitrate) — but in this build
  the `mercury` output routes to the **stub**, which:
  - reports `disabled` / `needs_setup` / `ready_not_wired` (never "connected"),
  - computes a setup checklist (LiveKit key present? channel set? tailnet reachable?),
  - opens **no socket** and mints **no token** on `start`/`stop`/`restart`,
  - short-circuits the tailnet probe until the key + channel are present, so the
    default (no creds) performs **zero** network egress.

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
