# Stage 3 polish-pass — corrected wall on the TV

**Device:** `<LAN_IP>:5555` (Onn 4K Streaming Box, Amlogic S905Y4)
**Helper:** `<LAN_IP>:8091`, build `5223162`, 16 channels seeded after the polish-pass redeploy
**Launched:** 2026-06-03 11:09 PDT
**Screenshot:** `wall-polished-preferred-lineup.png`

## What was fixed
1. **Video scaling** — `StreamSurface` switched from raw `SurfaceView` to Media3 `PlayerView` with `RESIZE_MODE_FIT`. Each tile's video now fills the cell width with clean black letterbox bars top/bottom where source and cell aspect ratios differ (operator-approved default). No more native-size floating.
2. **Grid autofit** — the grid now divides its parent region evenly with `weight(1f)` rows × columns instead of fixed `aspectRatio(16:9)` tiles. The right-hand region (beneath the ticker, beside the feed) fills edge-to-edge horizontally; tile dimensions follow from the parent's size, letterboxing happens inside each tile.
3. **Preferred + fallback lineup** — `LineupSelector` (TV-side) walks the operator's 4 preferred slugs, then the 5-slug fallback list, then any remaining playable channels — capped at the wall's tile count.

## Channel-resolution result (the start of the resolution-investigation record)

After the polish-pass helper redeploy with all 16 channels seeded, the prober's verdict on each:

| slug | status | last_error | notes |
|---|---|---|---|
| **cbs-sports-hq** (preferred 1) | **live** | — | Operator's #1 — works. Top-left slot. |
| bbc-news (preferred 2) | unavailable | `http_403` | Likely geo-block on BBC's HLS endpoint. |
| cnn (preferred 3) | unavailable | `fetch:dns_failure: cnn-cnninternational-1-eu.rakuten.wurl.tv` | Candidate Rakuten endpoint doesn't resolve from this network. |
| livenow-fox (preferred 4) | unavailable | `fetch:dns_failure: livenowfromfox.akamaized.net` | Candidate Akamai endpoint doesn't resolve. |
| c-span (fallback 1) | unavailable | `http_403` | Likely geo-block. |
| **nasa-tv** (fallback 2) | **live** | — | Master manifest passes the prober; **but** the ExoPlayer variant fetch returns `ERROR_CODE_IO_BAD_HTTP_STATUS` and slot-1 settles `DEAD` after the full 3-strike ladder. Honest OFFLINE panel renders. *See "edge: master-OK / variant-FAIL" below.* |
| white-house-tv (fallback 3) | unavailable | `http_404` | URL guessed; not a real endpoint. |
| newsmax (fallback 4) | unavailable | `fetch:dns_failure: newsmax-newsmaxtv-1-us.samsung.wurl.tv` | Candidate Samsung TV+ endpoint doesn't resolve. |
| cnn-international (fallback 5) | unavailable | `fetch:dns_failure: cnn-cnninternational-1-fr.rakuten.wurl.tv` | Same family of candidate Rakuten endpoint. |
| **redbull-tv** (rest) | **live** | — | Original seed. Top-right slot in the cycler. |
| **dw-news-en** (rest) | **live** | — | Original seed. Bottom-left slot. |
| france24-en (rest) | unavailable | `http_400` | Manifest rejected. |
| al-jazeera-en (rest) | unavailable | `fetch:dns_failure` | Original — known broken on this path. |
| sky-news (rest) | unavailable | `http_400` | Manifest rejected. |
| cgtn-en (rest) | unavailable | `fetch:dns_failure` | Original — known broken on this path. |
| trt-world (rest) | unavailable | `fetch:network_error: SSL handshake failure` | SSL incompatibility. |

**Net of resolution:** 4 channels live (CBS Sports HQ + NASA TV + DW News + Red Bull TV); 12 unavailable. The selector picks `[cbs-sports-hq, nasa-tv, dw-news-en, redbull-tv]` for the 2×2.

**What's on the wall (the screencap):**
- top-left **CBS Sports HQ** — LIVE
- top-right **NASA TV** — quiet C2 OFFLINE panel (helper says live, ExoPlayer variant fetch failed → honest DEAD per the Stage 2 state machine)
- bottom-left **DW News English** — LIVE
- bottom-right **Red Bull TV** — LIVE

## Edge documented: master-OK / variant-FAIL (nasa-tv)
The helper's prober only fetches the master manifest and checks for `#EXTM3U` — that's its definition of "live." NASA TV's master passes that check. But ExoPlayer then resolves the variant playlist URL and gets `HTTP non-2xx`, fails to play, and the LivenessTracker settles `DEAD` after the full PREPARE→REINIT×2 ladder. **The wall behaves correctly** (C2 quiet honest gap on slot-1), but there's a daylight gap between the helper's `status=live` and the player's playability. A future helper hardening pass could deepen the prober to also fetch a variant — out of scope here; logged to BACKLOG implicitly via the channel-resolution-investigation entry.

## Honest constraints (carried forward from Checkpoint B, updated)
1. **Channels: real but currently limited.** 4 of 16 seeded channels resolve playable on this network. The grid fills 4 tiles from the resolved set; if fewer resolved, the cycler would cycle (and remaining slots would render the OFFLINE panel). This is the start of the channel-resolution-investigation record — see `docs/BACKLOG.md`.
2. **Feed: real.** Helper's RSS poller is pulling BBC World, Al Jazeera, Guardian World, NPR World. Items visible: NYT (Mangione hearing), BBC (Nigerian church attacks).
3. **Ticker: real styling, sample data.** `TickerStrip` running with `SampleTickerSource`. Every entry carries a visible `SAMPLE` pill. No pretense of live quotes.

## Standing rules during this run
- **unrelated host services untouched.**
- **WyzeGrid** as-found on `.182` — short visual confirmation; MyMTS held the foreground for the check.
- App + helper test suites green: app unit tests pass with `LineupSelectorTest` (10 cases) added; helper `test_feeds_seeder` (7 cases) added in Checkpoint B; all prior tests still green.
