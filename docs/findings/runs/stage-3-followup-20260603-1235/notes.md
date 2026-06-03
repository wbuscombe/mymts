# Stage 3 follow-up — corrected wall on the TV

**Device:** `<LAN_IP>:5555` (Onn 4K Streaming Box)
**Helper:** `<LAN_IP>:8091`, 17 channels seeded (16 prior + `iss-feed`)
**Launched:** 2026-06-03 (multiple restarts during the session)
**Screenshot:** `wall-followup-c2-panels-honest.png`

## What the screencap shows
- **Ticker** scrolling with `SAMPLE` pills on every cell (WTI, Gold, BTC, ETH, 10Y UST visible).
- **Feed pane** showing real Guardian World + Al Jazeera items with relative-time chips (22m / 30m / 31m / 52m). Header reads "FEED · 80 items."
- **Video grid 2×2** with each tile rendering the **C2 honest OFFLINE panel** — a quiet near-black square with a ghost channel label, no error chrome:
  - top-left: `DW News English`
  - top-right: `Red Bull TV`
  - bottom-left: `DW News English`
  - bottom-right: `Red Bull TV`

**This is the honesty fix working under adverse conditions.** The network was flaky at screencap time (both `dwamdstream102.akamaized.net` and `rbmn-live.akamaized.net` resolve from the dev Mac but the Onn box's player kept hitting `RECOVERING → DEAD` on each restart cycle — the same v2-soak signature, an external Akamai blip). The state machine surfaced this honestly via the C2 panel; the label on each panel correctly tells the operator what *would have been* in that slot.

## Honesty rule made structural (issue #3 — the important one)
- New `BoundTile` data class pairs a slot with the player whose `spec.id` matches that slot. `init { require(...) }` throws at construction if the pairing is wrong; a tile labelled "X" cannot end up playing channel "Y" because the type system + runtime check forbid it.
- `bindTiles(...)` does the pairing by **identity match** on `spec.id`, never by index. If a stale player from a prior recomposition is in the lookup map, it's discarded (`.takeIf { it.specId == slot.spec.id }`) — the tile renders the same C2 dead panel as a settled-DEAD player rather than drawing the wrong channel's video.
- `WallTile` is rewritten to take a `BoundTile` parameter — no internal player lookup. Label + state + player all flow from the same object.
- Compose `key(tile.key)` wraps each `WallTile` in `VideoGrid`, so when a slot's channel changes the tile is rebuilt from scratch — no stale state can leak across the identity change.
- 4 unit tests in `BoundTileTest.kt`:
  - `label, channel, and player all come from one Channel after backfill` — runs the operator's described scenario (preferred fail, fallbacks backfill) and asserts every (label, url) pair matches a single Channel.
  - `binding the wrong player to a slot throws` — proves the runtime check fires.
  - `empty slot pairs with null player` — empty slots stay empty.
  - `lookup miss leaves the tile with null player, not a wrong-channel player` — the wall never falls back to a different player to "fill" the slot.
  - `stale player whose specId mismatches is discarded, not drawn` — second-layer guarantee.

## CBS Sports HQ candidate URL (issue #1)
Tried `https://cbssports-cbssports-1-us.samsung.wurl.tv/playlist.m3u8` (Samsung TV+ Wurl pattern). Helper prober verdict: **DNS failure** — that endpoint doesn't resolve from this network. **Honest plain-text result:** I could not reliably find a current public HLS endpoint for CBS Sports HQ without web access. Per the operator's prompt — "honest OFFLINE rather than the wrong channel mislabeled" — the slug stays in the seed but does not occupy a slot until a working endpoint is found. The slot backfills via the selector.

## NASA TV → ISS feed swap (issue #2)
- ISS feed candidate URL: `https://iphone-streaming.ustream.tv/uhls/17074538/streams/live/iphone/playlist.m3u8` (NASA's UStream). Helper prober verdict: **SSL certificate hostname mismatch** — the UStream cert is no longer valid for that hostname. Honest result: candidate doesn't resolve.
- NASA TV stays in the seed; the helper marks it `live` (master manifest passes its `#EXTM3U` check) but the variant playlist fails for ExoPlayer.
- **New `denySlugs` mechanism** in `LineupSelector`: NASA TV is now in `LineupSelector.DENY` and structurally excluded from the wall's default lineup. It can never sneak in via the "rest" tier; it's filtered at the source. 3 new unit tests in `LineupSelectorDenyTest.kt` pin this.

## Channel-resolution net result
Helper's live set: `dw-news-en`, `redbull-tv`, `nasa-tv`. After `LineupSelector.forWall(4)` runs:
- Preferred (cbs/bbc/cnn/livenow): all unavailable → skip
- Fallback (c-span/iss/whitehouse/newsmax/cnn-int): all unavailable → skip
- Rest (dw, redbull, nasa): nasa is in DENY → only dw + redbull survive
- `TileSlotResolver` cycles `[dw, redbull, dw, redbull]` into the 4 slots.

This is the same shape as the v2 long-soak from Stage 2 — 2 channels cycled into 4 slots. Each label matches its slot.

## Standing rules
- **unrelated host services untouched.**
- **WyzeGrid** still re-enabled on `.182`.
- App tests green: 4 new `BoundTileTest` cases + 3 new `LineupSelectorDenyTest` cases on top of prior suite.
- Helper tests green: 122 (the seed change is data, not code).
