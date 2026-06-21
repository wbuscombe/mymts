# Adversarial Analysis — Performance + Channel Selection (2026-06-21)

**Scope:** an enterprise adversarial-review pass with two goals — **(A) performance** (the CPU-bound
`.92` Onn box first, then web + helper) and **(B) channel-selection quality** (the 56-channel lineup,
its categories + presets). READ-ONLY: no source/config changed, no deploy. This report is the only artifact.

**Method:** the adversarial-review playbook — 7 independent lenses (5 perf: native-recompose, native-GC,
native-player, web, helper; 2 channel: liveness/balance, preset-coherence) fanned out READ-ONLY across the
code, then **every finding adversarially verified** (the cited code re-derived, default-to-dismiss) — 67
raw → **64 confirmed, 3 dismissed**. Grounded against the author's own ground-truth reads of the hottest
paths (`VideoGrid`/`StreamPlayerManager` churn, the `wallAlpha` recompose, the cnbc placeholder) and a
read-only production `/api/channels` liveness snapshot. Tags: **EVIDENCE** (proven from code) / **INFERRED**
(strong, not airtight) / **SPECULATIVE**. Impact: **High / Med / Low**. Proportionality: a solo/homelab
ambient-wall on one CPU-bound box — "impact" = frames/CPU/GC on `.92` and lineup UX, not throughput/scale.

> **No code was changed. Fixes are a SEPARATE pass driven by these findings.**

---

## Triage summary

| Impact | Perf (native) | Perf (web/helper) | Channels | Headline |
|---|---|---|---|---|
| **High** | P‑N1, P‑N2, P‑N3 | — | C‑1 | full‑wall recompose ×2 + full‑wall decoder churn; a permanently‑dead channel in the default lineup |
| **Med** | P‑N4, P‑N5, P‑N6, P‑N7 | P‑W1, P‑H1 | C‑2, C‑3, C‑4, C‑5 | wide recompose scopes + per‑poll allocs; web has no offscreen cull; helper re‑resolves dark channels; dark‑heavy categories/presets |
| **Low** | (folded) | P‑W2, P‑W3, P‑H2 | C‑6, C‑7 | hls.js/DOM/DB micro‑costs; seasonal/mixed presets (by design) |

**Verdict.** The architecture is sound and the honest-degradation discipline holds — **but the `.92`
box pays for three avoidable wall-wide invalidations** (menu-toggle recompose, settings-nudge recompose,
and a *whole-grid decoder rebuild* triggered by one tile's URL token rotating). Those three are the
highest-leverage CPU wins and should drive the first fix prompt. On channels, the lineup is healthy live
**except** one permanently-dead channel (`cnbc`) sitting in the default preset's preferred list, and two
categories/presets whose dark-tile density hurts browsing UX (US News, Space).

---

## A. PERFORMANCE

### The architect's #1 question — what is the single highest-leverage CPU win on `.92`? → **P‑N3 (player-manager stability).**

A YouTube tile's resolved URL is a `googlevideo` manifest carrying an `expire` token; the resolver
re-resolves it when its cache TTL lapses (bounded ~4 h), so the **served `current_url` rotates**. The wall
rebuilds **all** players when it sees that, not just the one tile that changed — see P‑N3. Because the
ambient presets (Nature / Ocean / Space / Eagles) are ~100 % YouTube, switching to or sitting on one of
those means a periodic full-grid decoder reinit on the box that is *already* UI-thread-saturated. Fixing the
manager to key on a **stable identity** and swap a single tile's `MediaItem` in place removes that churn —
and also removes the same full-rebuild on every status-flip / override / grid change.

### Native (the priority — `.92` is CPU- / UI-thread-draw-bound)

**P‑N1 — Wall-wide recompose on every menu open/close.** · EVIDENCE · **High**
`WallScreen.kt:508-509` — `val wallAlpha = if (menu.isOpen || menu.pendingSelection != null) 0.45f else 1f`
is read at the Column's composition scope and passed to `Column(Modifier…alpha(wallAlpha))`. Toggling the
menu flips an *observed* `mutableState`, so the Column **and all its children — `TickerStrip`, `FeedPane`,
`VideoGrid`** — recompose, *during* the crawl animation, on the UI-thread-bound box. This is the cheapest
big win: **defer the state read into a draw-phase lambda** — `Modifier.graphicsLayer { alpha = if (menu.isOpen
|| menu.pendingSelection != null) 0.45f else 1f }` — so the menu toggle re-runs only the draw phase, not
recomposition. Precedent already in this file at `:499` (the fit-scale `graphicsLayer`).

**P‑N2 — Wall-wide recompose on any WallSettings nudge.** · EVIDENCE · **High**
`WallScreen` reads `val wallSettings by lineupStore.wallSettings` at the **top function scope**;
`LineupStore.updateWallSettings()` replaces the *whole* `WallSettings` on any single-field change (e.g.
`nudgeTickerScroll`, `cycleFeedWidth`, `nudgeGridRows`). So adjusting ticker speed recomposes the entire
`WallScreen` (ticker + feed + grid). Compounded: `WallSettings` carries `Set<String>` fields, which Compose
infers as **unstable**, so children taking it can't skip. Fix: (1) **scope-isolate** — wrap `TickerStrip` /
`FeedPane` / `VideoGrid` in small composables that each read only their own settings fields, so a
ticker-speed nudge recomposes only the ticker wrapper; (2) annotate `WallSettings` `@Immutable` (its fields
are primitives/enums/immutable Sets) so leaf composables receiving it skip correctly. Same root pattern hits
several Med findings below (the high-scope reads are the cause).

**P‑N3 — Whole-grid player rebuild on `specs` change (decoder churn).** · EVIDENCE · **High**
`VideoGrid.kt:94-104`: `playingSlots = remember(slots){…}` → `specs = remember(playingSlots){ playingSlots.map { it.spec } }`
→ `manager = remember(specs){ StreamPlayerManager(context, specs) }` + `DisposableEffect(manager){…}`.
`StreamSpec` is a `data class` (value equality) **but `StreamSpec.url` is the channel `current_url`** — which
rotates for any YouTube/tokenized tile. When one tile's URL changes, `specs` is unequal → `remember(specs)`
**recreates the manager** → `onStop` releases **every** player (`StreamPlayerManager.kt:103`) and the new
manager builds **every** player (`:90`). It also fires on any `slots`-identity change (status flip, operator
override, grid resize). Highest decoder-churn lever on a CPU-bound box. Fix: key the manager on a **stable
identity** (the ordered set of slot ids / slugs, not URLs); when only a tile's URL changed, replace that one
player's `MediaItem` in place. (Frequency note: the dismissed "30-min probe re-resolves every cycle" claim
was refuted — the resolver's cache TTL means a URL rotates only ~every few hours per YouTube tile — so this
is *periodic* full-grid churn, not constant; still the top CPU item given the YouTube-heavy ambient presets.)

**P‑N4 — Per-recomposition list allocation in the ticker build path.** · EVIDENCE · **Med**
`SportsTicker.kt:34-48` (the `blocks` grouping rebuilds per recompose, CRAWL + FLIP) and `TickerStrip.kt:105-117`
(`pages` computed at composable scope on every `entries` change). Because the ticker recomposes on the P‑N1/N2
triggers, these allocate per recompose → GC pressure on the box. Fix: `remember(entries)` the pages/blocks.
*(Note: the crawl itself does **not** recompose per frame — it's a `graphicsLayer { translationX }` driven by
an `Animatable`; the "recomposes every crawl frame" hypothesis was **dismissed**.)*

**P‑N5 — State collected too high → wide recompose scope.** · EVIDENCE · **Med**
`TickerStrip.kt:91-92` and `FeedPane.kt:75,84-87` `collectAsState()` + filter/sort at the top composable
scope; `WallTile.kt:91-106` reads `player.state.collectAsState()` and recomputes the tile alpha on every
player-state change. Fix: push collection/derivation into narrower child scopes; use `derivedStateOf` for the
alpha so it only recomposes when the *boolean* it depends on flips.

**P‑N6 — Unstable params widen recomposition.** · EVIDENCE · **Med**
`WallScreen.kt:512-565` passes the `focus` object (whole `WallFocus`) to `TickerStrip` / `FeedPane` /
`VideoGrid`, and creates the `feedPane`/`videoGrid`/`divider` composable lambdas inline (not memoized). Fix:
pass only the needed primitives (e.g. `focused: Boolean`, `feedIndex: Int`) and hoist/`remember` the lambdas.

**P‑N7 — Per-poll O(n log n) sorts + Set/Map allocs.** · EVIDENCE · **Med** (per-poll = 30-60 s, cumulative)
`FeedListBuilder.build` re-sorts on every feed order change (`:96-100`); `TileSlotResolver.resolve` allocates a
`byOverrideSlug` Set + `allBySlug` Map every resolution (`:88-91`); `HelperTickerSource.filterLeagues`
lowercases per poll (`:238-242`); `distinctSources`/`newsEntries` rebuild lists per filter change. Fix:
memoize on the inputs / make the resolver inputs reference-stable so `remember` actually skips.

### Web

**P‑W1 — No offscreen tile culling; all ~9 tiles decode concurrently.** · EVIDENCE · **Med**
`app.mjs:699-905` (renderCell → attachStream) attaches a decoder per cell with no `IntersectionObserver` /
visibility gate. At/near the browser concurrent-decoder ceiling there's zero headroom; a large grid or a
scrolled-off tile still holds a decoder. Fix: cull/teardown offscreen or beyond-ceiling tiles.

**P‑W2 — hls.js config not tile-count-aware.** · EVIDENCE/INFERRED · **Low**
`video.mjs:157` — `enableWorker:false` (main-thread demux; it was disabled to avoid a `worker-src` CSP
widening — re-evaluate whether a scoped `worker-src 'self' blob:` is acceptable for the demux offload) and
`maxBufferLength:12` is fixed regardless of tile count. Fix: lower buffers at high tile counts; reconsider the
worker trade-off.

**P‑W3 — Per-poll DOM rebuilds (no diffing).** · EVIDENCE · **Low**
`app.mjs:310` ticker `replaceChildren` on a content-changed poll, `:433` feed full rebuild per poll, settings
toggles rebuilt per feed/sports poll. Fix: targeted updates. *(Confirmed clean: the WAAPI ticker's
signature-guard correctly **persists** the animation across polls — `app.mjs:299-303` — so the recent
ticker-parity change is **not** a perf regression.)*

### Helper

**P‑H1 — Resolvers don't cache *failed/offline* resolutions.** · EVIDENCE · **Med**
`youtube_resolver.py:209-249` and `cspan_resolver.py:359-380` only populate the cache on `ok=True`. So every
honest-offline channel is **fully re-resolved (a real yt-dlp / ISVP call) on every probe cycle** — and US News
alone has ~17 dark channels (gov feeds, court-tv, law-crime, pbs), most of them *persistently* dark
(off-session). Fix: cache negative results with a short TTL so dark channels back off (cheap CPU/egress win,
also lowers the cost of the dark-heavy categories from C‑2). Highest-value helper item.

**P‑H2 — Sequential / per-connection DB + fetch micro-costs.** · EVIDENCE · **Low**
Prober opens a connection per status update (`:342-363`) and per `_list_enabled` (`:215-220`); feeds + sports
pollers are sequential (markets is concurrent); DNS isn't cached (`fetcher.py:86-103`); the SSRF-safe fetch
runs twice per channel (master + variant). Mild on a non-request-bound box; batch/parallelize/cache if touched.

---

## B. CHANNEL SELECTION

Grounded against a read-only production `/api/channels` snapshot (live/total per category): Business 1/2,
Cameras 2/2, General 1/1, Global News 12/16, Nature 5/5, Space 1/2, Sports 1/2, **US News 7/24**, Weather 2/2.
A single point in time — gov feeds dark = *not in session* (by-design honest-offline), distinct from
*permanently dead* and *flaky*.

**C‑1 — `cnbc` is permanently dead AND in the default preset's preferred list.** · EVIDENCE · **High**
`seed.json:9-12` — `cnbc` `source_url = "https://no-public-cnbc-hls-exists.invalid/cnbc.m3u8"` (a deliberate
placeholder — no public CNBC HLS exists). It is `PREFERRED[5]` (`LineupSelector.kt:73-81`) and a `news`
preset slug (`presets.py`). At the 2×2 default it isn't reached (the first 4 preferred fill), but at **6+
tiles it occupies a slot as a permanent OFFLINE tile** — and it is re-resolved/probed every cycle for nothing
(feeds P‑H1). Fix: **prune `cnbc`** (or remove it from `PREFERRED` + the `news` preset so it can never take a
slot) until a real CNBC source exists. The lowest-effort channel win.

**C‑2 — US News is 24 channels / 7 live (29 %) — dark-heavy browsing.** · EVIDENCE · **Med**
17 dark, mostly **by-design** session/seasonal gov feeds (House/Senate floor + committees, State/War/DHS/DOJ,
court-tv, law-crime, pbs). Honest, but browsing the US News picker section is mostly dark tiles. Fix: the
already-logged **"Government" sub-category** — move the ~10 session-gov feeds out of US News so the section
reads live-dense. (Native renders an unknown category dynamically; the web folds unknown→General, so this one
*does* need a small web `CHANNEL_CATEGORY_ORDER` add — a deliberate client change, separate from server data.)

**C‑3 — Space preset is 50 % structurally dark.** · EVIDENCE · **Med**
`presets.py:43-45` — Space = `iss-feed` (live) + `nasa-tv`. `nasa-tv` is on the `DENY` list precisely because
its master-only HLS resolves but settles DEAD in ExoPlayer (`LineupSelector.kt:86-116`). So the Space preset
ships a structurally-dead second tile. Fix: drop `nasa-tv` from the **Space preset** (keep it in the picker for
when the prober is deepened to variant-fetch), or pair ISS with a working space feed (e.g. a second NASA/ISS
YouTube angle), so the preset is all-live.

**C‑4 — Global News 12/16 (4 dark).** · EVIDENCE/INFERRED · **Med**
`al-jazeera-en`, `cgtn-en`, `cnn-international`, `trt-world` dark in the snapshot. Likely geo/flaky vs the
NAS US vantage rather than session. Fix: confirm whether persistently dark (prune) or transient (keep).

**C‑5 — Sports is 2 channels, 1 live (`cbs-golazo` dark).** · EVIDENCE · **Med**
Thin category, and the live half is `cbs-sports-hq` only. Fix: confirm `cbs-golazo` (rotating handle?);
consider promoting a reliable free sports-news live if one verifies (the prior CBS-News-style ToS-gray sources
are an operator decision — see BACKLOG).

**C‑6 — Seasonal / mixed presets (by design).** · EVIDENCE/INFERRED · **Low**
Eagles (Decorah) is fully dark off-season (correct honest-offline; a year-round bird cam is already logged as
a future fill). Chill mixes nature/space + `bloomberg-tv`/`fox-weather` — an intentional "mixed" blend, but
the category mix is worth a sanity check. Ocean (Tropical Reef + Monterey + Manatee) is all-live — healthy.

**C‑7 — Phantom marks all seeded channels live.** · EVIDENCE · **Low**
`phantom.py:82-90` marks every seeded channel live (by design, for the zero-egress demo) — contradicts
production liveness but is correct for the demo. No action; noted for completeness.

**Redundancy / balance:** no true duplicate channels found (the prior `c-span` direct-HLS vs the YouTube /live
duplicate was already omitted). The imbalance is **breadth** (US News 24 vs Sports 2, Business 2), not
overlap; C‑2's sub-category split is the lever.

---

## Correctly dismissed (false positives caught — for credibility)

1. **"CrawlContent recomposes on every crawl frame"** (`TickerStrip.kt:343-361`) — **DISMISSED.** The crawl is
   a `graphicsLayer { translationX }` driven by an `Animatable`; the content composes once per `entries`
   change, not per frame. No per-frame recompose exists.
2. **"30-min probe forces YouTube re-resolution every cycle"** (`config.py:54` vs the resolver TTLs) —
   **DISMISSED.** The resolver caches successful resolutions up to `MAX_CACHE_TTL` with a safety margin, so a
   live URL is reused, not re-resolved every probe. (This refines P‑N3's churn frequency to *periodic*.)
3. **"News Wall topup can ghost CNBC and hide preset intent"** (`presets.py` + `LineupSelector`) —
   **DISMISSED** as framed; the real, narrower issue is C‑1 (cnbc permanently dead in `PREFERRED`), captured
   above.

---

## Prioritized "fix first" — the highest-leverage items to drive follow-up fix prompts

| # | Item | Why first | Effort |
|---|---|---|---|
| 1 | **P‑N3** — player-manager keyed on stable identity; swap a tile's `MediaItem` in place | kills the periodic **full-grid decoder reinit** — the biggest CPU churn on `.92`, and worst on the YouTube-heavy ambient presets | Med |
| 2 | **P‑N1** — defer `wallAlpha` into `graphicsLayer { alpha = … }` | removes a **full-wall recompose on every menu open/close**; near-trivial, precedent in-file | Low |
| 3 | **P‑N2** — scope-isolate ticker/feed/grid reads + `@Immutable WallSettings` | removes the **full-wall recompose on every settings nudge** | Med |
| 4 | **C‑1** — prune / de-PREFER `cnbc` | removes a permanently-dead tile from the default lineup **and** stops a pointless re-resolve every cycle | Low |
| 5 | **P‑H1** — cache negative (offline) resolutions | stops ~17 dark US-News channels (+ seasonal cams) re-resolving every probe | Low |
| 6 | **C‑3 / C‑2** — drop `nasa-tv` from the Space preset; "Government" sub-category | preset/category dark-density (UX) | Low/Med |

Items 1–3 are the CPU triad for `.92`; 4–5 are cheap helper/lineup wins; 6 is UX polish. P‑N4–N7 + the web
items fold in behind these (most share the P‑N1/N2 scope-isolation root). **Remediation is a separate pass —
none implemented here.**
