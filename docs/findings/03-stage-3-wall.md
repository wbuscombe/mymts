# Finding 03 — Stage 3, the wall on the TV

> **Status:** Stage 3 CLOSED (2026-06-03). The full wall — ticker top, feed pane left, 4-tile video grid right — runs on the Onn 4K. Operator confirmed the assembled wall as a solid v0.1 alpha; this finding records the three honest constraints, the channel-resolution result from the polish pass, and what the operator should expect to see at next launch.

---

## 1. What's on the screen

```
┌──────────────────────────────────────────────────────────────────┐
│  ticker (scrolling marquee — SAMPLE pills on every cell)         │
├──────────────┬───────────────────────────────────────────────────┤
│              │                                                   │
│              │  ┌──────────────────┬──────────────────┐          │
│   FEED       │  │  CBS Sports HQ   │  NASA TV         │          │
│              │  │  (LIVE)          │  (OFFLINE)       │          │
│  · NYT       │  │                  │                  │          │
│  · BBC       │  ├──────────────────┼──────────────────┤          │
│  · Guardian  │  │  DW News English │  Red Bull TV     │          │
│  · NPR       │  │  (LIVE)          │  (LIVE)          │          │
│  · …         │  │                  │                  │          │
│              │  └──────────────────┴──────────────────┘          │
│  ~28% width  │   2×2 fills the remaining region edge-to-edge     │
└──────────────┴───────────────────────────────────────────────────┘
```

Operator-approved defaults from the polish pass:
- Each tile fills its cell width; black letterbox bars top/bottom where source and cell aspect ratios differ (Media3 `RESIZE_MODE_FIT`).
- The grid autofits the region beside the feed and beneath the ticker — no dead space.
- The lineup is helper-resolved (never hard-coded URLs in the app) and the slot order is the operator's preferred-then-fallback order.

## 2. The three honest constraints (Stage 3's "doneness" is truthful, not theatrical)

### 2.1 Channels: real but currently limited

The wall fills 4 tile slots from whatever channels the helper currently reports playable on this network. Today, that's **4 of 16 seeded channels**. The grid is built to adapt — to 0, 1, 2, 4, or more later — without code changes (`MYMTS_DEFAULT_MAX_TILES` is the cap; `TileSlotResolver` cycles whatever's left if there are fewer than N).

Polish-pass result (see §3 below for the full per-channel table):

| Result | Channels |
|---|---|
| **Live and playable** | `cbs-sports-hq`, `dw-news-en`, `redbull-tv` |
| **Live in helper, fails in player** (master-OK / variant-FAIL) | `nasa-tv` — renders the C2 OFFLINE panel |
| **Unavailable** | the other 12 — DNS failure / HTTP 4xx / SSL handshake / candidate URL stale |

Expanding channel resolution is its own scoped effort (helper / network / candidate-URL work) and is **explicitly carried forward to BACKLOG**, not folded into Stage 3.

### 2.2 Feed: real

The helper aggregates real RSS from the seeded set (BBC World, Al Jazeera, Guardian World, NPR World — added via the polish-pass-precursor `feeds/seed.json` + idempotent `feeds/seeder.py`). The feed pane reads `/api/feed?limit=80` and renders each item as native Compose `Text` — source caps, headline, 2-line summary, relative-time chip. **Never HTML.** There is no `WebView` on the wall. The header carries a calm staleness label (`"feed not updating"` / `"helper unreachable"` / `"N items"`) when the helper hasn't refreshed inside the window or is unreachable — Trust Bar C3 wired into the pane.

### 2.3 Ticker: real styling, sample data

The ticker scrolls in its real position and styling, but every entry comes from `SampleTickerSource` and carries `isSample = true` — rendered as a visible `SAMPLE` pill in each cell. Real markets data is **not** in scope here; the `TickerSource` interface lets a real source drop in later without rework. Honesty rule applied: the ticker doesn't get to pretend.

## 3. Channel-resolution result (the start of the resolution-investigation record)

After the polish pass seeded 16 channels and the helper's prober swept them once, the verdict:

| slug | result | error | notes |
|---|---|---|---|
| **cbs-sports-hq** (preferred 1) | ✅ live | — | Operator's #1 preference — works. Top-left slot. |
| bbc-news (preferred 2) | ❌ unavailable | `http_403` | BBC's HLS endpoint geo-blocks this network. |
| cnn (preferred 3) | ❌ unavailable | `fetch:dns_failure: cnn-cnninternational-1-eu.rakuten.wurl.tv` | Candidate Rakuten endpoint doesn't resolve. |
| livenow-fox (preferred 4) | ❌ unavailable | `fetch:dns_failure: livenowfromfox.akamaized.net` | Candidate endpoint doesn't resolve. |
| c-span (fallback 1) | ❌ unavailable | `http_403` | Geo-block. |
| **nasa-tv** (fallback 2) | ⚠️ live-but-DEAD | — | Helper says live; player gets `ERROR_CODE_IO_BAD_HTTP_STATUS` on variant fetch; settles `DEAD` after the 3-strike ladder. C2 OFFLINE panel renders. |
| white-house-tv (fallback 3) | ❌ unavailable | `http_404` | URL guessed, not real. |
| newsmax (fallback 4) | ❌ unavailable | `fetch:dns_failure: newsmax-newsmaxtv-1-us.samsung.wurl.tv` | Candidate endpoint doesn't resolve. |
| cnn-international (fallback 5) | ❌ unavailable | `fetch:dns_failure: cnn-cnninternational-1-fr.rakuten.wurl.tv` | Same Rakuten family. |
| **redbull-tv** (rest, from prior seed) | ✅ live | — | Bottom-right slot. |
| **dw-news-en** (rest, from prior seed) | ✅ live | — | Bottom-left slot. |
| france24-en | ❌ unavailable | `http_400` | Manifest rejected. |
| al-jazeera-en | ❌ unavailable | `fetch:dns_failure` | Known broken on this path. |
| sky-news | ❌ unavailable | `http_400` | Manifest rejected. |
| cgtn-en | ❌ unavailable | `fetch:dns_failure` | Known broken on this path. |
| trt-world | ❌ unavailable | `fetch:network_error: SSL handshake failure` | SSL incompatibility. |

**Selector outcome for the 4-slot wall**: `LineupSelector` picks the operator's preferred order, falls back, then tops up — final lineup is `[cbs-sports-hq, nasa-tv, dw-news-en, redbull-tv]`. The honest reality is 3 LIVE + 1 OFFLINE (C2 panel).

### 3.1 Edge documented for the investigation: master-OK / variant-FAIL

The helper's prober validates the *master* HLS manifest only (`#EXTM3U` prefix check). NASA TV's master passes; the variant playlist URL the master points to returns HTTP non-2xx for ExoPlayer. The helper truthfully labels the channel `status=live` per its definition; the player truthfully renders the tile DEAD per its definition. **The wall behaves correctly** (honest C2 panel) but there's a daylight gap in what "live" means at each boundary. Deepening the prober to also fetch a variant playlist is a candidate hardening for a future helper pass — logged implicitly to the channel-resolution-investigation BACKLOG entry.

## 4. What the operator should expect to see at next launch

- The wall comes up to the assembled layout — ticker, feed, grid. No setup screen, no login, no setup prompts (Vision §4 — ambient on room entry).
- The 2×2 grid renders the lineup above (or whatever the helper currently resolves — the lineup is dynamic, not baked into the APK).
- Tile states are honest end-to-end: a frozen surface is never labelled LIVE; a DEAD tile is a quiet near-black panel with a small `OFFLINE` badge, not an error card (C2 + C3 from Stage 2, surfaced in the Stage 3 UI).
- The feed shows the most recent ~80 items from the helper's RSS sources. Items render as native text; the pane header carries `"feed not updating"` if the helper hasn't refreshed inside 10 minutes.
- The ticker shows clearly-`SAMPLE`-tagged placeholder values, not real markets data.
- WyzeGrid: left re-enabled on `.182` at the end of every session.

## 5. Carried forward — BACKLOG entries created during Stage 3

- **Partial channel-resolution investigation** — the broader "why don't more channels resolve" sweep (DNS-over-HTTPS, geo-egress, manifest-fallback, candidate-URL rotation). Includes deepening the prober to variant-fetch.
- **Configurable feed-pane width** — operator-controllable proportions; needs a settings surface.
- **Configurable / scalable panes** — first-class layout system across feed/grid/ticker.
- **In-app menu / settings section (WyzeGrid-style)** — Stage 5.
- **Browser / PWA client (SEPARATE CLIENT — see security caveat in BACKLOG)** — a *separate* client that consumes the same helper API; not a mode of the native app. Preserved with the full Technical-Approach §1 native-vs-web rationale so future readers don't erase it.

See `docs/BACKLOG.md` for each, with `What` / `Why-not-now` / `Reconsider when` framing.

## 6. Standing rules at Stage 3 close

- **unrelated host services: never touched** (entire stage).
- **WyzeGrid:** re-enabled on `.182` at end of every device run; no soak windows were opened during Stage 3, so disable/re-enable wasn't needed for any extended period.
- **No secrets, no absolute paths** committed; helper host URL configured via `MYMTS_HELPER_BASE_URL` gradle property → `BuildConfig.HELPER_BASE_URL`.
- App + helper test suites green: app ~36 unit tests (cycler, channel-parse, feed-parse, time, ticker, lineup selector); helper 122 (115 prior + 7 seeder).
