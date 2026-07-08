# 0005 — Discord Activity: force every request through `/.proxy/` + cache/failure-surface hardening

**Status:** accepted (2026-07) · discord-activity / helper · native unchanged

The Discord Activity output (decision 0002, ARCHITECTURE §37) did not work inside Discord. Two
fix passes made it play and hardened it so the next failure is diagnosable in-frame. This record
captures the corrected design; §37's original relative-URL routing is superseded (see §40).

## Context

Discord serves an Activity iframe at the **origin root** (`https://<app-id>.discordsays.com/`), and
its CSP silently drops any network request that is not under the `/.proxy/` path (which Discord maps
to the developer's public origin). The original Activity built every path as a bare relative URL and
relied on the iframe base being `/.proxy/` — an assumption that is false. So the hls.js stream chain
resolved to `…discordsays.com/api/...` (root, outside `/.proxy/`) and was blocked before leaving the
sandbox: the wall stayed black with zero stream traffic. Then, after that was fixed, a stale
Cloudflare edge cache served a pre-fix `.css` alongside a fresh `index.html`/`.mjs`, rendering a
white frame with no UI and not even the diagnostics badge.

## Decisions

1. **One proxy base, used everywhere — stop relying on the iframe base.** `proxied(path)`
   (`activity-core.mjs`) is the single source of truth: `${origin}/.proxy${path}` inside Discord
   (`isInsideDiscord()` detects the `*.discordsays.com` host), the plain path standalone. Config,
   token, and playlist all route through it. The app does **not** use the SDK's `patchUrlMappings`
   (which patches `fetch`/XHR but not reliably the hls.js chain) — explicit prefixing is transparent
   and testable.
2. **hls.js forced through the proxy with two belts.** (a) The playlist is loaded via its proxied
   URL, so relative `seg_N.ts` URIs resolve under `/.proxy/`. (b) A custom hls.js loader
   (`makeProxyLoader` → `rewriteHlsUrl`) rewrites EVERY derived request — segments, child manifests,
   redirects, absolute URIs — back under `/.proxy/`, idempotently; `blob:`/`data:` MSE buffers are
   left alone. The helper serves the playlist with **relative URIs only** (locked by a test) and
   tolerates a forwarded `/.proxy/` prefix (`_StripProxyPrefix`), so VLC/LAN play the same playlist.
3. **`no-store` the Activity's static assets.** A `_NoStoreStatic` ASGI middleware stamps
   `Cache-Control: no-store` on the HTML/CSS/JS (keyed on content-type) so no edge/proxy cache
   (Cloudflare, Discord) can pin a stale copy — the white-frame class. The HLS media keeps the stream
   router's own headers. One residual manual step: a one-time Cloudflare purge of the copy cached
   before `no-store` shipped; after that it cannot recur.
4. **A build-SHA version stamp in the page source.** `index.html` is served with the running helper's
   git SHA injected into a `__MYMTS_BUILD_SHA__` placeholder — "which build is Discord running?" is
   answerable by view-source, forever.
5. **A JS-free failure surface — a dead module is never a silent white frame again.** `index.html`
   carries an inline dark background FIRST, an inline `window.onerror`/`unhandledrejection` handler
   FIRST in `<head>` that writes the error on-screen (dependency-free, runs even when the module fails
   to load/parse), and a static "loading…" state + `<noscript>`. The CSP adds `'unsafe-inline'` for
   script/style so this bootstrap can render; `connect-src 'self'` (the egress-critical directive)
   stays strict. Beneath it, the existing tappable ⓘ diagnostics overlay names the failing
   URL/stage/status when JS does run.

## Consequences

- The Activity is robust by construction: requests can't escape `/.proxy/`, assets can't be pinned
  stale, and a failure shows readable on-screen text (the boot-error box or the ⓘ overlay), never a
  white void. The version stamp makes "is Discord running the current build?" a view-source check.
- `'unsafe-inline'` on script/style is a deliberate, bounded relaxation for a trusted static bootstrap
  with no user-controlled HTML; the security-critical `connect-src` stays `'self'`.
- Helper + discord-activity only. Native is untouched (no APK/tag). Mercury stays inert; the renderer
  is unchanged.
