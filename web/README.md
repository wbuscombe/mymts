# MyMTS — LAN web client

A minimal, **separate, credential-free, LAN-only** web client that renders
the wall's content (feed, ticker, channel status, health) from the same
helper API the native TV app consumes. It is **not** a new backend and
**not** a mode of the native app — it is one more dumb consumer of the
single hardened helper, exactly the "separate client consuming the same
API" path the founding native-over-web decision explicitly sanctioned
(`docs/foundation/04-TECHNICAL-APPROACH.md`).

## Why this is safe (the load-bearing constraints)

- **Separate origin, off the `*.<DOMAIN>` domain.** Served by the
  helper on its bare LAN address over the existing HTTPS:8443 — NOT a
  <DOMAIN> subdomain, NOT tunneled, NOT behind Cloudflare Access. It
  shares no origin and no cookie jar with the operator's other services,
  so the browser's own same-origin policy keeps it from being the
  cross-service path the native decision avoided.
- **Credential-free.** No login, no cookies, no session, no stored
  tokens. `fetch` is called with `credentials: "omit"`. The helper data
  is already non-sensitive inert plain text; there is nothing to steal
  and no session to hijack.
- **Same-origin as the helper API → no CORS.** Because the helper serves
  this SPA at `/app`, the client calls `/api/...` same-origin. The helper
  does **not** open CORS to any other origin.
- **A1 closed door holds.** The client only GETs the helper's own JSON.
  It never fetches an article page, never iframes one (`frame-src 'none'`
  + `connect-src 'self'` in the page CSP), never interprets a helper
  string as markup (everything is written via `textContent`). Same closed
  door as the native app — no in-browser web reader.
- **Helper core untouched.** The only helper change is an optional,
  config-gated static mount (`WEB_CLIENT_DIR`, off by default). The SSRF
  -safe fetcher, parsers, and non-root/read-only/cap-drop posture are
  unchanged.

## How it's served

The helper mounts this directory at `/app` **only when** `WEB_CLIENT_DIR`
is set in its environment (off by default). With it set:

```
# helper .env (NAS)
WEB_CLIENT_DIR=/app/web      # path to this directory inside the container
```

Then browse (on the LAN) to **`https://<LAN_IP>:8443/app/`**.

> **Self-signed cert note:** the helper uses a self-signed cert (the
> native app pins it). A browser will show a one-time "not private"
> warning; click through / trust it for the LAN host. This is expected
> for a personal LAN tool and is why this client is LAN-only — it is
> never exposed to the public internet.

## What it renders — mirrors the Onn wall

The layout mirrors `WallScreen`: a **scrolling ticker** across the top,
an **agnostic feed pane** on the left, and a **cell-count video grid** on
the right, in the MyMTS dark theme. A **gear button** (top-right) opens
mouse-driven settings (there's no D-pad in a browser).

- **Ticker** — a real horizontal **marquee** that scrolls; rotates
  markets ↔ sports every ~18 s; hover to pause-and-read. Sports show
  **ESPN-BottomLine-style league markers** — the league shows once as an
  accent pill, then its games follow (no redundant per-game prefix).
  SAMPLE pills and stale notes preserved (never shows sample/stale as
  live).
- **Feed** — a single **agnostic chronological river** across all
  sources, newest-first, with the **source next to each headline** (the
  original Onn style — not per-source sections; the native app keeps its
  sections on purpose). Plain-text summaries, honest per-item age. Source
  show/hide + text size live in Settings.
- **Video grid** — **cell-count** layout (1 / 2 / 4 / 6 / 9), plays the
  same public HLS the helper resolves via `/api/channels` using the
  vendored **`hls.js@1.5.17`** (`web/vendor/`, pinned, `enableWorker:false` so
  the CSP needs no `worker-src`) or native HLS (Safari). **Click a cell**
  to pick its channel; the picker shows each channel's honest status
  (plays-in-browser / on-the-TV-wall-only / offline). A stream that won't
  load shows the honest **"on the TV wall"** tile, never a faked-live one.
- **Settings (gear)** — video cell count, feed width, feed text size, and
  feed source show/hide. **These are browser-local view prefs**
  (localStorage) — the wall's own settings live on the TV; the web client
  can't write them (no per-client helper state — that's the
  cross-platform-profiles fork, deferred).

## A1 / CSP — video playback is not web reading

In-browser HLS is **inert stream playback** (the same thing the native
ExoPlayer does), NOT article-web-reading — the closed door is about a web
*reader*, which this isn't. The page CSP keeps `frame-src 'none'` and
`object-src 'none'` (no iframes, no article embeds), `script-src 'self'`
(vendored hls.js, no CDN), and adds **no `worker-src`** — hls.js runs
`enableWorker:false`, so no `blob:` worker is spawned and the CSP stays as
locked as the pre-video version. `connect-src`/`media-src` are scoped to
`https:`/`blob:` because hls.js fetches `.m3u8` + segments from public
stream CDNs and feeds the `<video>` via a blob MediaSource. The client is
**credential-free**, so a broad `connect-src` has nothing to exfiltrate,
and the stream URLs come from the helper — not arbitrary input.

### Play-what-works, label the rest (no proxy)

The browser plays only HTTPS-clean, CORS-allowed streams; the native
ExoPlayer has no such limits, so the **TV is the full-fidelity client**.
The helper sends a best-effort `browser_playable` hint per channel (it
classifies the stream scheme server-side — the browser can't introspect a
blocked stream), and the picker shows it. The **ground truth** is the
runtime load result: a stream that can't load (mixed-content, CORS, geo,
dead) flips the tile to the honest **"Not playable in browser — on the TV
wall"** state. The helper **never proxies video** — it stays the
resolver/shield, out of the video data path.

## Tests

Pure label/grouping/honesty logic lives in `js/render.mjs` and is unit
-tested with Node's built-in runner (no dependencies, no toolchain):

```
node --test web/test/
```

These pin the same honesty invariants as the native app: sample/stale/
offline data is never presented as live-real, grouping/ordering match the
native `FeedListBuilder`, and empty states are honest.

## Remote access is a SEPARATE future chapter — NOT built

Exposing this beyond the LAN (a genuinely separate public origin, its own
threat-model pass, an auth story + the credential tradeoff that
introduces, rate-limiting) is a deliberate later decision, scoped in
`docs/BACKLOG.md`. The LAN-only version here sidesteps all of it by being
unreachable from outside the home network.
