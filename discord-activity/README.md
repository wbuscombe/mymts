# MyMTS Discord Activity

A tiny static web app that shows the MyMTS news wall **inside a Discord voice
channel**, using the [Discord Embedded App SDK](https://discord.com/developers/docs/activities/overview).
It is a **viewer**: it plays the same HLS render the wall already produces (helper
`/api/stream`) — no second encode.

> **Launch-to-start, by platform rule.** Discord's only ToS-legitimate surface for
> shared in-call video is a user-launched Activity. A bot cannot broadcast video
> unattended, and a user-token self-bot is against Discord ToS — so there is no bot
> here, by design. Configure once in `/control/`; a user launches it from Discord.

## Files

| File | What |
| --- | --- |
| `index.html` | full-bleed black page: a `<video>` + a status overlay + a one-time unmute button. Own-origin CSP (`script-src 'self'`, no CDN). |
| `activity.mjs` | browser entry: SDK handshake → OAuth → start playback. Browser-coupled glue only. |
| `activity-core.mjs` | the **testable** core (no `window`/`document`/SDK import): the OAuth wiring (`authorizeAndAuthenticate`) + the hls.js attach (`attachStream`). Collaborators are injected. |
| `activity.css` | minimal styling — black, contain-fit, no chrome. |
| `vendor/` | same-origin third-party JS: the esbuild-bundled SDK + the pinned hls.js (see `vendor/README.md`). |
| `test/activity.test.mjs` | `node --test` — OAuth wiring, error states, hls attach (proxied path + native fallback + self-recovery). |

## Lifecycle

```
fetch /api/discord/config        → the PUBLIC client id (no build-time templating)
new DiscordSDK(clientId) → ready()
authorize  (scope: identify)     → code
POST /api/discord/token {code}   → access_token   (secret used SERVER-side only)
authenticate({access_token})     → session
hls.js → play  api/stream/playlist.m3u8   (RELATIVE → resolves under Discord's /.proxy/)
```

The playlist URL is **relative** so it resolves against the iframe's
`…/.proxy/` base inside Discord (and against `/` when served standalone); the
playlist's relative `seg_N.ts` URIs inherit the same proxy path automatically.

## Running the tests

```sh
node --test discord-activity/test/*.test.mjs
```

## Serving / deploying

The app is served by the helper's **dedicated public app** (`create_public_app`),
which exposes ONLY this Activity + `/api/discord/*` + the `/api/stream` passthrough,
behind the operator's Cloudflare tunnel. The full LAN API / `/control/` are never on
that surface. Deploy + the one-time Discord-portal + Cloudflare steps (register the
app, set **URL Mappings** `/` → your public origin) are documented in the repo
**README → "Discord — the wall in a voice channel"**. Nothing is posted to Discord
until an operator completes those steps and a user launches the Activity.
