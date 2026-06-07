# Finding 15 — Web client rework + scrolling ticker + ESPN current-games sports

> **PARTLY SUPERSEDED by [finding 16](16-web-rework-r2-and-mixed-content.md) (round 2, same day):** the video-grid **size slider + draggable splitter** (`--grid-pct`) described below (lines ~32) were replaced by **cell-count** grid config (1/2/4/6/9) with feed width as `--feed-pct`; the per-source **sectioned** web feed became an **agnostic chronological** list; the bare "offline" tile became the honest "Not playable in browser — on the TV wall" / "Couldn't play in browser" states; and the sports ticker gained ESPN-BottomLine **league markers**. The sections below describe the round-1 UI.

> **Status: BUILT + TESTED 2026-06-06.** Web client reworked to mirror the Onn wall (scrolling ticker + feed + 2×2 hls.js video grid + mouse settings gear); the ticker now actually scrolls on both clients; and the sports ticker shows **only current games** (ESPN-BottomLine-style) — far-future fixtures and out-of-season leagues are suppressed. Helper suite 175, web tests 11, app suite unaffected. The ESPN current-games filter is helper-side → **requires a helper redeploy** to take effect.

## What the operator flagged (hands-on)

1. The web client read as a foreign **dashboard**, not the MyMTS wall.
2. The **ticker didn't scroll** — it was a static row.
3. The **video grid wasn't sizable** (and wasn't even playing video).
4. The **sports ticker showed stale out-of-season games** — NFL "9/9 8:20 PM" / "9/13 1:00 PM" preseason fixtures, surfaced in June when the NFL is off-season.

## The substantive fix — ESPN current-games / "ticker-worthy" rules

This is the testable core. The bug, confirmed against live ESPN: the NFL scoreboard in June returns 16 events, **all `state=pre` dated September** (months out), and even reports `season.type=2` (regular) — so season-type alone is *not* a reliable off-season signal. The robust discriminator is the **event date relative to now**.

**A game is "current / ticker-worthy" iff:**
| Status (`status.type.state`) | Shown when | Rationale |
|---|---|---|
| `in` (in-progress) | always | live is always current |
| `post` (final) | started within ~12 h back | "today's" finals, not yesterday's |
| `pre` (scheduled) | starts within ~12 h forward | "later today", not days/weeks away |
| anything else / no parseable date (and not live) | never | can't prove current |

A league whose events are **all** dropped contributes nothing and is **omitted from the ticker entirely**. If *every* league is empty, the ticker shows the honest "no games right now" line — never padded with stale future fixtures. This is C3 honesty applied to sports: reflect what's *current*, show nothing rather than something stale.

Validated against live data at build time: MLB (9 in-progress + today's finals/scheduled) all shown; NFL (all September `pre`) all dropped → NFL omitted. The windows (`FINAL_WINDOW_MS` / `UPCOMING_WINDOW_MS`, ~12 h each) are constants; `now_ms` is injected so the logic is deterministic in tests (`test_ticker_sports.py`, 16 tests: in-season-shown, off-season/future-omitted, stale-final-dropped, live/final/scheduled formatting, mixed-league keeps-only-current, defensive-parse-never-raises). Per-status display: live → `AWY 4–6 HOM · Bot 9th`; final → `· Final`; scheduled-today → `AWY @ HOM · 7:30 PM ET` (matchup + time, no fake 0–0 score). Composes with the curation chapter's league toggles (a league shows only if curated-on AND currently-in-season-with-games). Helper-side, keyless, SSRF-safe fetcher unchanged, defensive parse never raises.

## Web client rework

- **Layout mirrors `WallScreen`:** scrolling ticker top, feed pane left, 2×2 video grid right, dark theme. The old always-visible channel roster moved into Settings.
- **In-browser video (`web/js/video.mjs`):** vendored `hls.js@1.5.17` (`web/vendor/`, pinned) plays the helper-resolved public HLS in 2×2; native HLS path for Safari. A stream that errors → honest **offline** tile (dot + "offline" label), never faked-live.
- **Sizable grid:** a Settings slider AND a draggable splitter between feed and grid; the split (`--grid-pct`) persists in localStorage.
- **Settings gear (mouse, no D-pad):** grid size, feed text size, feed source show/hide (client-side `filterHiddenSources`), and the channel live/offline roster. These are **browser-local view prefs** — the wall's own settings live on the TV; the web client has no write path to them (per-client helper state would be the cross-platform-profiles fork, deferred). Documented so it isn't mistaken for controlling the TV.
- **Scrolling ticker:** CSS marquee (duplicated track, `translateX 0→-50%` seamless loop), markets↔sports rotation every ~18 s, hover-to-pause. SAMPLE pills / stale notes preserved.

## CSP change for HLS (the only security-surface change)

hls.js fetches `.m3u8` + segments from arbitrary public stream CDNs, so the page CSP widened `connect-src`/`media-src` to `https:`. **`frame-src 'none'`, `object-src 'none'`, `script-src 'self'` stay locked** (no iframes, no article embeds, no CDN scripts — hls.js is vendored). This does **not** reopen the A1 closed door: the closed door forbids an in-browser article *reader*; this is inert stream *playback* (what the native ExoPlayer already does). The broader `connect-src` is acceptable because the client is **credential-free** (nothing to exfiltrate) and stream URLs come from the helper, not arbitrary input. Recorded in `THREAT-MODEL.md`.

## Verification

- **Helper:** 175 tests green (16 sports incl. the current-games filter). Reproduced the NFL-preseason bug against live ESPN; the filter drops it.
- **Web:** 11 pure-logic tests (`node --test`); all JS `node --check`-clean. Visual render (layout, video playback, scrolling, gear/settings, splitter) is the **operator's in-browser check** after redeploy.
- **Both-client ticker scroll:** native `TickerStrip` already scrolled (`basicMarquee`, confirmed); web now scrolls via CSS marquee.

## Deferred (BACKLOG)

- Remote (non-LAN) web client; team-level sports curation; news in the web ticker; deeper web↔TV settings parity (needs per-client helper state); Stooq indices source (markets indices/FX on SAMPLE from the NAS egress).

## Standing rules

- A1 held (video playback ≠ web reading; no article fetch/iframe; markup-free). Web client stays LAN-only / same-origin / credential-free. `.182`/WyzeGrid untouched. unrelated host services never touched. hls.js pinned + vendored; no secrets/absolute-paths.
