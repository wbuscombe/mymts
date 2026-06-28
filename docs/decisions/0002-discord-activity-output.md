# 0002 — Discord as a unified output (an Activity that plays the wall)

**Status:** accepted (2026-06-28) · server / web / new `discord-activity/` · native unchanged

A decision-record for adding **Discord** as the third destination in the unified
Outputs model (alongside HLS/VLC live + Mercury stubbed), with the same
configure-once → wall-appears UX.

## Context

We want the wall to show up *inside a Discord voice channel*, like it already shows
up in VLC (HLS) and — eventually — in a Mercury call. Discord has exactly one
ToS-legitimate surface for shared in-call video: an **Activity** (the Embedded App
SDK), a small web app that runs in an iframe in the voice channel. The alternatives
are forbidden: a **bot cannot broadcast video** unattended (the API has no
"bot screen-share"), and a **user-token self-bot is against Discord ToS**. So the
only correct design is launch-to-start: a user opens the Activity and the wall plays.

## Decisions

1. **Activity, not a bot.** Ship a static `discord-activity/` web app using
   `@discord/embedded-app-sdk`. Lifecycle: `new DiscordSDK(clientId)` → `ready()` →
   OAuth (`authorize` → exchange `code` at our token endpoint → `authenticate`) →
   play. There is deliberately **no** bot and **no** self-bot. "Launch-to-start" is a
   Discord platform rule we surface honestly, not a limitation we chose.

2. **Reuse the single HLS render — no new encode.** The Activity is a *viewer*: it
   plays the SAME HLS the renderer already produces (helper `/api/stream`), via
   hls.js. So `outputs.discord` is a **special shape** — `{enabled, transport,
   guild_id}`, with **no** resolution/bitrate/audio/restart_epoch — it inherits HLS
   quality and adds zero render/encode cost. (Contrast Mercury, which is a real second
   encode/publish.)

3. **A dedicated, minimal PUBLIC origin — never the LAN API.** Discord's iframe can
   only reach our content through a public HTTPS origin (its `/.proxy/` URL-mappings
   mechanism). We expose that via the operator's **existing Cloudflare tunnel** (the
   same mechanism the other 3SL hostnames use) → a NEW minimal app (`create_public_app`)
   that serves ONLY three things: the Activity static files, `/api/discord/*` (config +
   token exchange), and the hardened `/api/stream` passthrough. The full API,
   `PUT /api/wall`, `/control/` and `/app/` are **never** on this surface; the raw LAN
   stream is never exposed — only this relay. It mirrors `create_stream_app`'s
   minimal-surface discipline (no second, divergent serving path to drift).

4. **The secret stays server-side; only the access token crosses.** The OAuth token
   exchange (`POST /api/discord/token`) uses `DISCORD_CLIENT_SECRET` server-side and
   returns ONLY the `access_token` — never the secret, refresh token, or upstream
   error body. The client id is public (served at `/api/discord/config` so the static
   app needs no build-time templating). The secret lives only in the gitignored `.env`.

5. **Discord state is HELPER-computed (unlike Mercury).** The helper holds `DISCORD_*`
   and owns the public origin; the renderer isn't involved (Discord reuses its HLS).
   So `/api/outputs/status` computes the Discord card's `disabled | needs_setup | ready`
   state + checklist directly. **`ready` = creds present + public origin reachable** —
   it means "an operator *can* launch", NOT that a session is live. There is **no**
   always-on "publishing" state, and the card never claims a session that isn't there.
   The reachability check is real but **non-authenticating** (a GET of our own
   `/api/discord/config` through the tunnel — never a Discord call), short-circuited
   until creds are present and skipped in phantom, so a not-yet-configured Discord (and
   phantom) perform **zero** egress; it's `unknown` when it can't be checked safely.

## Consequences

- `outputs.discord` slots into the existing map-shaped schema with **no
  `schema_version` bump** (the design point of decision 0001.4): a defaults entry + a
  validator branch + a `clamp_reload_monotonic` that tolerates an output with no
  `restart_epoch`. Partial-merge already protects the other outputs field-for-field.
- "Restart" is rejected for Discord (`400`) — launch-to-start has no helper-owned
  session/encoder to cycle.
- A one-time operator step remains in two consoles we can't automate: the **Discord
  developer portal** (register the app, set **URL Mappings** `/` → the public origin)
  and the **Cloudflare dashboard** (add the public hostname → the helper's public
  port). Both are documented (README "Discord", with a copy-paste mappings block).
- The Activity vendors its third-party JS same-origin (the SDK esbuild-bundled to a
  self-contained ESM file; hls.js the same pinned bundle the wall uses) — `script-src
  'self'`, no CDN, matching `/app/` + `/control/`.
- Native is untouched (server/web + a new static app) → no APK, kept in `[Unreleased]`.
  PIA / other NAS containers untouched; the existing VLC HLS + the Mercury stub are
  byte-for-byte unchanged.
