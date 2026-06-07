# Finding 16 — Web rework round 2: ticker league markers, agnostic feed, cell-count grid, channel picker, and the mixed-content diagnosis

> **Status: BUILT + TESTED 2026-06-06.** Second pass on the LAN web client after hands-on feedback. Ticker now shows ESPN-BottomLine-style league section markers (both clients); the web feed is an agnostic chronological list with the source next to each headline; the video grid uses **cell-count** configuration (1/2/4/6/9) instead of a size slider/splitter; channel selection is click-to-pick; and the video path honestly plays what works and labels the rest. **A headline diagnosis result reshaped Part 5 (below).** Helper 181 tests, web 17, app 246 (+5), APK builds. Security posture unchanged (LAN-only · same-origin · credential-free · CSP-locked · frame-src 'none' · no proxy · A1 held).

## What the operator flagged (hands-on, round 2)

1. **Ticker** scrolls now (good) but repeats the league per game ("MLB … / MLB …") — wants ESPN-BottomLine-style **league section headers** (league once, games under it).
2. **Feed** should be an **agnostic chronological list** with the **source next to each headline** (the original Onn-box style), not per-source sections.
3. **Video grid** should use **cell-COUNT** configuration (how many tiles), not a freeform size slider/splitter.
4. **Channel selection** is unintuitive — make it obvious (mouse).
5. **Video tiles aren't playing** — hypothesised as a mixed-content block (HTTPS page can't load HTTP segments).

## The mixed-content diagnosis — and an honest course-correction

Part 5 asked to diagnose the HTTPS/HTTP split, play the HTTPS-clean channels, and honestly label the rest. We diagnosed all **10 currently-live** channels by fetching the full HLS chain (master → every bitrate variant → real segments + AES keys) and checking both the scheme and CORS, each classification then **adversarially re-verified** by an independent probe trying to refute it.

**Result: all 10 are HTTPS-clean AND CORS-allowed (ACAO present).** Not one mixed-content or CORS gap was found anywhere in any chain:

| Channel | Verdict | Channel | Verdict |
|---|---|---|---|
| bbc-news | browser-playable (ACAO `*`) | livenow-fox | browser-playable (ACAO `*`) |
| bloomberg-tv | browser-playable (ACAO `*`) | newsmax | browser-playable (ACAO `*`) |
| cbs-sports-hq | browser-playable (ACAO `*`) | redbull-tv | browser-playable (ACAO `*`) |
| cnn | browser-playable (ACAO `*`, incl. https AES keys) | sky-news | browser-playable (ACAO `*`) |
| dw-news-en | browser-playable (ACAO `*`) | france24-en | browser-playable (ACAO echoes Origin) |

This is because the helper's `registry.validate_hls_url` already **enforces https at the seed boundary**, and the prober follows only an https variant — so every channel that reaches `live` started from an https master, and (as the probe confirmed) these CDNs serve relative or https segments throughout, with permissive CORS.

**So mixed content is NOT why the tiles were blank for the current channel set.** Reporting the convenient hypothesis as the cause would have been dishonest (C3 applies to our own diagnosis, not just the wall). The real cause is client-side. The leading, concrete suspect under this strict CSP: hls.js with `enableWorker:true` spawns a **`blob:` Web Worker**, which `default-src 'self'` (no `worker-src`) **blocks**. Fix applied: run hls.js with **`enableWorker:false`** — main-thread demux, which the browser host (a laptop, not the constrained S905Y4) handles easily for a few news tiles — so we did **not** have to widen the CSP with a `worker-src blob:`. We also added a **click-to-play** fallback for browsers that block muted autoplay, and the honest error state below.

### What we built anyway (correct + future-proof)

Even though today's channels are all clean, the **honest play-what-works / label-the-rest** system is the right design and is built:

- **Helper `browser_playable` hint** (`channels/prober.py::classify_browser_playable`, migration 002, exposed on `/api/channels` only when live): scans the master + followed-variant bodies for a literal `http://` sub-resource (variant / segment / `#EXT-X-MAP` / `#EXT-X-KEY` URI). `https://` does not contain `http://`, and relative URLs resolve to the https playlist, so neither false-positives. `True` = HTTPS-clean, `False` = mixed content found, `NULL` = unclassified. The browser can't introspect a blocked stream, so this must be server-side. For the current set every live channel classifies **True** — matching the diagnosis.
- **Client tri-state hint** (`render.mjs::browserPlayability` → `yes`/`no`/`maybe`) drives the picker badges and whether a cell attempts playback.
- **Runtime load-failure is the ground truth** (`video.mjs` → `onState('error')`): if a stream genuinely can't load — for ANY reason including a future mixed-content/CORS/dead channel — the tile flips to the honest **"Not playable in browser — on the TV wall"** state, never a black box shown as live. This catches what the helper hint can't predict (CORS, geo, transient death).
- **No proxying.** The helper never enters the video data path — it stays the resolver/shield. The native app remains the full-fidelity client where every channel plays (ExoPlayer has no mixed-content or CORS restriction).

## Ticker — ESPN-BottomLine league markers (both clients)

- `render.mjs::groupTickerByLeague(entries)` groups **consecutive** same-symbol entries into `{ label, cells[] }` runs; `app.mjs` renders the league as a single accent pill, then its games. Markets symbols are distinct → each is its own one-cell run (unchanged look); sports collapse (one "MLB" + N games). SAMPLE pills survive per cell.
- Native: `ui/wall/TickerGrouping.kt` (pure, 5 tests) mirrors the web grouping exactly; `TickerStrip.kt` renders a `LeagueMarker` pill once + each value. The native ticker had the same per-item redundancy, so this makes both clients consistent.

## Feed — agnostic chronological (WEB ONLY) + the native question

`render.mjs::feedChronological` flattens to a single newest-first river across all sources; `app.mjs::renderFeed` shows **source + time next to each headline** (`sourceLabel`), inert plain text (A1). Honest staleness stays per-item via the time/age.

**Per-client divergence, on purpose.** The **native** app keeps its per-source **sections** (Stage 7, `FeedListBuilder`/`FeedPane`) — that was a deliberate prior chapter the operator approved then. Having used both, the operator prefers the agnostic style **for the web client**. This is a per-client preference, not a reversal of the native decision, so the native feed is **left untouched**. **Open question flagged for the operator:** should the native feed ALSO revert to agnostic-with-source-label? Not changed unilaterally — logged in `BACKLOG.md`. If yes, it's a focused follow-on.

## Grid — cell-count + click-to-pick channels

- `render.mjs::gridLayout(count)` maps 1/2/4/6/9 → near-square `{cols, rows}` (unknown clamps to 4); `app.mjs` sets `--grid-cols/--grid-rows`. The old size slider + draggable splitter are gone; **feed width** is now a clean Settings control (`--feed-pct`). Cell count + per-cell channel assignment + feed width persist in `localStorage` (`mymts.web.prefs.v2`).
- **Click a cell → channel picker** (`openPicker`): every channel with an honest badge — green "plays in browser", amber "on the TV wall only", grey "offline", plus a "Clear this cell" row. The picker orders browser-playable first. Which channel is in which cell is labelled on the tile; a "click to change" chip makes reassignment discoverable. Empty cells show a "＋ Add channel" affordance.

## Verification

- **Diagnosis:** 10/10 live channels HTTPS-clean + CORS, adversarially confirmed (multiple variants, real segments, AES keys).
- **Helper:** 181 tests (6 new: classifier https/http/key/variant cases + browser_playable round-trip incl. clear-to-NULL-on-unavailable). Migration 002 is additive (`ALTER TABLE ADD COLUMN`, NULL for existing rows) → safe on the production DB; the prober populates the hint on its first cycle after redeploy.
- **Web:** 17 pure-logic tests (groupTickerByLeague sports-collapse/markets-unchanged/adjacency, feedChronological agnostic-newest-first, gridLayout, browserPlayability tri-state, sourceLabel). All JS `node --check`-clean.
- **Native:** app compiles, 246 unit tests (+5 TickerGrouping), debug APK builds.
- **Operator's in-browser feel-test (can't be done headless):** do the league markers read well; does the agnostic feed read right; is cell-count + click-to-pick intuitive; and — the real test — **do the tiles now play** (the streams are confirmed clean, so if any tile still won't play, the browser devtools console will name the cause: a remaining CSP directive, an autoplay block surfaced as the click-to-play overlay, or a specific hls.js error).

## Security / standing rules

- CSP **no less locked** than before: `frame-src 'none'`, `object-src 'none'`, `script-src 'self'`, `base-uri 'none'`, `form-action 'none'`; `connect-src`/`media-src` scoped to `https:`/`blob:` as before; **no `worker-src` added** (enableWorker:false). hls.js stays vendored + pinned. No proxy; helper egress unchanged (it scans a manifest body it already fetches). A1 held — video playback ≠ web reading; no iframe, no article fetch, DOM text via `textContent` only. LAN-only / same-origin / credential-free intact. `.182`/WyzeGrid untouched. unrelated host services never touched. No secrets/absolute-paths.

## Deferred (BACKLOG)

- Native-feed agnostic revert (operator's call); team-level sports curation; news in the web ticker; remote web client; cross-platform profiles (the real web↔TV settings-parity fix); optional future stream-proxy (deliberately NOT done — helper stays out of the video path).
