# BACKLOG

> Ideas surfaced during build that are **deliberately not** part of v1 — logged here per the build prompt's anti-drift rule (§9). Adding something to this file is the *correct* answer when a good idea surfaces that isn't in scope; folding it into v1 is the wrong one.

For each entry: **What** (one line), **Why-not-now** (which Vision principle defers it), **Reconsider when** (the condition that would make it worth doing).

---

## Deferred from v1 (per `BUILD-PROMPT.md §9` and `04-TECHNICAL-APPROACH.md §5`)

| What | Why-not-now | Reconsider when |
|---|---|---|
| Multi-box fleet management | Vision §6: single-box, operator-only is v1 scope | A second box is actually running MyMTS |
| Desktop companion app | Vision §6 ranks desktop as nice-to-have; trading it bulletproofs the TV path | Operator explicitly asks |
| Non-Onn platforms (other Android TV boxes, Fire TV, Apple TV) | Portability is a *design discipline*, not a feature in v1 | Operator is actually re-homing |
| Actual friend-sharing functionality | "Someday-maybe" in Vision §5; the security model is built for it but the feature is not | Operator decides to share |
| Map / GDELT geocoded event layer | Out of scope: Vision §3 — "not a general-purpose OSINT terminal" | Vision changes |
| Aircraft (ADS-B), ships (AIS), weather radar | Same as above | Vision changes |
| Prediction markets (Kalshi) | Same as above | Vision changes |
| LLM feed classification | v2 per `04-TECHNICAL-APPROACH.md`; v1 uses keyword rules | The keyword classifier proves insufficient |
| Accounts / multi-user | Vision §3 — "not a product with users" | Never, per current Vision |
| Richer in-app article reading | Build prompt §4 — safe excerpt is the v1 answer; cross-device handoff was a forbidden dead-end | A safe in-app reading mechanism is designed |
| Sports in the ticker | Markets-only in v1; ticker designed to accept additional modes later without rework | Markets ticker is shipping and stable |
| Sports ticker logos | Same as above | When sports ships |
| NCAA leagues | When sports ships, start with the 6 cleanest-data leagues | When sports ships |
| Full Prometheus metrics endpoint | v1 ships JSON metrics; Prometheus is v1.x | claude-status-bot needs it |

---

## Stage 1.x — confirmatory 4K-panel soak

**What:** Re-run the tile-budget soak with the Onn box routed to a 4K-capable display.
**Why-not-now:** The Stage 1 gate-clearing long soak runs against `.182` whose attached panel is 1280×720. Decode load is panel-agnostic so the leak number transfers, but final-stage downscale + Graphics surface composition at 4K is unverified.
**Reconsider when:** Either `.182` is moved to a 4K panel, or `.158` (or another Onn box) is connected to one for a short confirmatory run. Before shipping the default to a box driving a 4K production TV.

## ~~Stage 2 / production — `StreamPlayer.state` must be frame-age-aware~~

**Promoted to a required Stage 2 fix.** See `docs/STAGE-2-PLAN.md §B.1`. Stage 1 reproduced this surface-honesty failure in all three soaks (the v3 run had four of six tiles reporting `state=LIVE` for 10+ hours after their last frame); it is now the entry point of Stage 2 because it is a direct violation of locked Trust Bar **C3** and it is the proximate cause of why the 6-tile capacity number could not be measured in Stage 1.

## ~~Production deployment — app-vs-app foreground conflict on the Onn box~~ — RESOLVED BY DECISION (Model A, 2026-06-04)

**Resolved by the Model A decision: one kiosk app per box.** The coexistence problem (two `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` watchdogs — WyzeGrid + MyMTS — thrashing on one box) is no longer pursued, because MyMTS gets its own dedicated box and `.182` stays WyzeGrid's alone. The kiosk scaffolding deliberately builds **no** coexistence/foreground-reclaim logic; the opt-in gate (kiosk OFF by default) keeps the shared APK inert on `.182` so no contention can occur there. The original entry's own "Reconsider when" foresaw exactly this resolution ("assert 'one kiosk app per box, MyMTS is the kiosk'"). See the kiosk entry below + `ARCHITECTURE.md §17`.
**If Model A is ever revisited (shared box):** coexistence/reclaim would need fresh threat-modelling before any reclaim logic is added — out of scope while Model A holds.

## Stage 3 polish-pass — partial channel-resolution investigation

**What:** Of the operator's 4 preferred channels for the 2×2 default (CBS Sports HQ, BBC News, CNN, LiveNOW from FOX), only **CBS Sports HQ** resolves live on this network path. Of the 5 fallback channels (C-SPAN, NASA TV, White House TV, Newsmax, CNN International), only **NASA TV** resolves. The rest fail with a mix of DNS failure (DNS-blocked or candidate URL stale), `http_403` (geo-block likely — BBC News, C-SPAN), `http_404` (URL doesn't exist as guessed — White House TV), and `http_400` (manifest reject — France 24, Sky News). Full per-channel result recorded in `docs/findings/03-stage-3-wall.md`. The polish pass scoped this tightly — find current working endpoints for these specific channels, log the result — but the broader "why don't more channels resolve" investigation is the natural follow-on.
**Why-not-now:** Building the channel-resolution recovery story (DNS-over-HTTPS for blocked lookups, geo-egress strategies, manifest-fallback discovery, candidate-URL rotation) is its own scoped effort with its own threat-model implications (any "try a different network path" mechanism is also a "could exfiltrate to a different network path" mechanism). Stage 3 ships with what resolves on the helper's current network path; honest C2 OFFLINE tiles fill the rest. The polish pass result is *evidence to start the investigation with*, not a finished mapping.
**Reconsider when:** The operator wants more channels live and is willing to scope the investigation as its own effort. Start by re-checking the current URL set against a fresh public-IPTV catalog (e.g. iptv-org) — half of the failures here are likely just stale candidate URLs, not architectural barriers.

## Stage 3 polish-pass — configurable feed-pane width

**What:** Today the feed pane is a fixed `fillMaxWidth(0.28f)` of the screen; the grid autofits whatever space remains beside it. The operator confirmed this direction for the polish pass (grid adjusts to feed, not the reverse), but a configurable feed width — and ultimately operator-controllable proportions across all three regions — is the natural next step. Subsumed by the larger configurable-panes backlog entry below.
**Why-not-now:** Layout configurability needs a settings surface to be operator-visible; that surface is Stage 5. Hard-coding a width is the right v0.1 alpha shape per the polish-pass prompt.
**Reconsider when:** Stage 5 (settings) lands — pane-width controls are the natural place to expose it.

## Configurable / scalable panes (feed · grid · ticker) as a first-class layout system

**What:** Let the operator resize/scale the three panes (news feed, video grid, ticker) — starting with configurable feed width (grid currently adapts to a fixed feed width), generalizing to a user-driven layout where pane proportions are adjustable.
**Why-not-now:** Vision §6 (bulletproof core, one-click-easy content) and current staging — Stage 3 ships a fixed layout; the in-app settings system that would expose layout controls is Stage 5. The near-term piece (grid adapts to a fixed feed width) is already done this pass; full configurability is the generalization.
**Reconsider when:** Stage 5 (settings/menu) lands — pane-layout controls are a natural fit for that surface. Note the linkage to the per-device-profile idea (different output setups may want different default proportions).

## In-app menu / settings section (WyzeGrid-style)

**What:** The in-app settings/menu surface — WyzeGrid-pattern side panel (focusable rows, popup pickers, D-pad nav) for lineup, presets, pane layout, display options, diagnostics.
**Why-not-now:** This is **already planned as Stage 5** — logging it here only so the roadmap is visible in one place. Not a deferred-indefinitely item; it's the next major stage after Stage 3.
**Reconsider when:** Stage 5 (it IS Stage 5). Cross-reference `04-TECHNICAL-APPROACH.md` and the foundation docs for the WyzeGrid-pattern intent.

## Browser / PWA client (SEPARATE CLIENT, not a mode of the native app) — LAN version BUILT 2026-06-06; REMOTE deferred

**Update (2026-06-06):** the **LAN-only** version is now built (`web/`, served by the helper at `/app` on its bare LAN address — credential-free, separate-origin, same-origin-with-the-helper so no CORS, A1 closed door held). See `ARCHITECTURE.md §18`, `web/README.md`, and the THREAT-MODEL entry. The caveat below now applies specifically to the **remote-accessible** version, which remains **NOT built**:
- A **remote** web client must be a genuinely separate public origin (NOT sharing Cloudflare Access cookies with the operator's other `*.<DOMAIN>` services), with its **own fresh threat-model pass**, an **auth story** if exposed — and the tradeoff that introducing auth means the helper or client now **holds a credential** (it currently holds none) — plus rate-limiting and hostile-input-at-the-edge handling. The LAN version deliberately sidesteps every bit of this by being unreachable from outside the home network.
- **~~Also deferred (LAN follow-on): in-browser HLS video grid via `hls.js`~~ — BUILT (web-rework chapter, 2026-06-06).** The LAN client was reworked to **mirror the Onn wall** (scrolling ticker + feed pane + **2×2 `hls.js` video grid** + mouse settings gear) instead of a dashboard. hls.js is **vendored + pinned** (`web/vendor/`, `script-src 'self'` — no CDN); CSP widened only `connect-src`/`media-src` to `https:` for arbitrary stream CDNs (`frame-src`/`object-src`/`script-src` stay locked). Video playback ≠ web reading — A1 closed door held. See `docs/findings/15-web-rework-and-ticker.md`, `ARCHITECTURE.md §18`, the THREAT-MODEL entry. **Still LAN follow-ons:** news in the web ticker; deeper web↔TV settings parity (the web settings are browser-local view prefs — the cross-platform-profiles fork, item H, is the real fix).
**Reconsider when:** the operator explicitly wants remote/desktop access from outside the LAN AND there's appetite to take on the remote client's security work as its own scoped effort (threat-model it fresh against the Trust Bar before building).

### Original caveat (preserved — now scoped to the remote version)

**What:** A browser-based (possibly PWA) way to view MyMTS, for desktop/other displays, alongside the native TV app. Ties into "multiple output setups (browser / set-top / display)" — the layout adapting to where it runs.
**Why-not-now / CAVEAT (important — preserve this verbatim):** This is in genuine tension with the load-bearing architecture decision in `04-TECHNICAL-APPROACH.md §1`. MyMTS was deliberately built **native instead of web** *specifically because* a browser client is the worse security story for the operator's #1 ranked fear (worst-outcome #1, breach): a browser shares a cookie jar across the operator's `*.<DOMAIN>` subdomains, runs untrusted content, and reintroduces the CSP/cookie-scope/XSS class of problems that the native decision removed. Therefore a future browser client is **NOT a mode of the existing native app** — it is a **separate client** that consumes the same helper API (`/api/feed`, `/api/channels`). The architecture supports this cleanly because the helper is already client-agnostic, BUT the browser client would carry its own security burden (CSP, session/cookie scoping, hostile-input rendering) that the native client does not. Do not let a future "just add a web view" framing erase this — the whole point of going native was to avoid the browser threat surface.
**Reconsider when:** The operator explicitly wants desktop/browser access AND there's appetite to take on the browser client's security work as its own scoped effort (threat-model it fresh against the Trust Bar before building). The helper needs no change to support it; the new surface is the cost.

## Geo-block circumvention for region-locked broadcasters — **operator decision item**

**What:** Some broadcasters' public HLS feeds are deliberately geo-locked (e.g. BBC's `-uk-live` shards refuse non-UK clients; C-SPAN's public web player requires a session-token handshake that effectively gates remote scrapers). A future track could route the helper's outbound fetches through an egress strategy that presents a UK / US-cable-network IP, expanding the set of channels the operator can pin in the lineup.
**Why-not-now:** This is **out of scope by engineering default** and is explicitly an **operator decision**. Three reasons:
1. **Threat-model implications are non-trivial.** Any "try a different network path" mechanism is also a "could exfiltrate to a different network path" mechanism — the helper's existing SSRF guards (`fetcher`) are written against a single trusted egress; adding a configurable detour means re-doing the boundary analysis, the redirect re-validation rules, and the credentials-leak surface for whatever proxy / VPN provider is used.
2. **Operator-legal call.** Whether bypassing a broadcaster's geo-restriction is appropriate for the operator's use is the operator's decision, not an engineering default. The Stage 3 follow-on found that bbc-news works through the **worldwide** shard (`-ww-live` instead of `-uk-live`) without any circumvention — that's a better URL, not bypassing, and it's already in seed.json. The remaining geo-locked cases (e.g. real cable-network feeds) would need actual bypass.
3. **The wall already degrades honestly.** Channels that don't resolve render the Stage 5 C2 OFFLINE panel with the channel's label; the operator sees what's planned and what's offline. No need to force resolution for cosmetic reasons.
**Reconsider when:** The operator explicitly asks for a specific geo-blocked channel AND is willing to take on (a) the proxy/VPN choice + its trust posture, (b) a fresh threat-model pass on the helper's egress boundary with the detour wired in, and (c) the legal/TOS posture for the specific broadcaster. None of those are engineering defaults.

## ISS Live standalone HLS — no public endpoint currently exists

**What:** Replace `nasa-tv` slot with a dedicated International Space Station HD Earth-view feed when a public HLS endpoint becomes available.
**Why-not-now:** Both the historical UStream `iphone-streaming.ustream.tv/uhls/…/playlist.m3u8` (SSL cert hostname mismatch, then dead) and the CloudFront `d2ai41bknpka2u.cloudfront.net/live/iss.stream_source/chunklist.m3u8` (DNS-fails) are decommissioned. NASA's current standalone-ISS distribution is YouTube-only (`https://www.youtube.com/@liveiss/live`), which is not HLS and would need a separate sidecar resolver (out of scope for the helper's HLS-only contract). NASA TV's NTV1 master HLS (`https://ntv1.akamaized.net/hls/live/2014075/NASA-NTV1-HLS/master.m3u8`) carries ISS Earth-view footage during off-programming hours and is the honest substitute today.
**Reconsider when:** NASA publishes a stable public HLS endpoint for the standalone ISS HDEV feed, or the operator wants to wire a YouTube-Live-to-HLS sidecar (its own scoped effort with separate threat-model implications).

## CNN International / C-SPAN — no public free linear HLS currently exists

**What:** Both channels were on the operator's preferred / fallback list but resolve to honest "no working public endpoint" — CNN International's last Wurl endpoints (`*.rakuten.wurl.tv`, `*.samsung.wurl.com`) are DNS-dead and the channel is no longer exposed on any public FAST platform; C-SPAN wraps its current free web player in a session-token handshake, so no static `.m3u8` is reachable directly. CNN US itself only has a `cnn_slate` AES-128-encrypted promotional feed publicly — the actual broadcast is paywalled behind Max.
**Why-not-now:** Adding either would require either a session-token sidecar (C-SPAN), a slate-feed acceptance (CNN — and even then, the content is promotional slates, not the live broadcast), or a paid-tier integration (CNN US linear). None match the v1 single-source-of-truth-helper architecture.
**Reconsider when:** A free public HLS surfaces for either (occasionally happens with FAST platforms), or the operator decides slate-feed-as-CNN is acceptable + tolerable to the AES-128 handling in ExoPlayer.

## Video crop to hide burned-in captions — **out of scope by engineering default**

**What:** Some channels render transcription text **into the video pixels** (a permanent scrolling caption bar). A future track could crop the bottom N% of the tile to hide that band.
**Why-not-now:** Three reasons all of which are operator-decision rather than engineering defaults.
1. **Degrades the picture for everyone.** Cropping removes the bottom band whether it carries captions, lower-thirds, the broadcaster's logo, or actual content — the wall loses information honestly visible everywhere else.
2. **Channels rotate which content lives in which band.** A "caption strip" today is a lower-third tomorrow; the crop is wrong on most footage.
3. **The honest answer is "different channel."** The menu's channel picker lets the operator swap a burned-in channel for one without it. The captions-OFF default + per-channel caption table document exactly which channels burn captions in vs. which expose a toggleable soft track. If the operator finds a specific burned-in channel intolerable, the right answer is to swap it, not to crop a tile.
**Reconsider when:** A specific operator-named channel that the operator definitively wants is intolerably burned-in AND the operator chooses to accept the picture-degradation cost in exchange. Build per-tile only, never wall-wide.

## CNBC — no free public HLS endpoint exists (2026-06-04)

**What:** Replace the seeded placeholder URL for `cnbc` with a real working public HLS endpoint when one becomes available.
**Why-not-now:** CNBC is paywalled cable. There is no free 24/7 FAST stream on Pluto.tv, Samsung TV Plus, Roku Channel, or Plex (verified across `iptv-org/iptv` `us.m3u`, `us_samsung.m3u`, `us_pluto.m3u`, `us_roku.m3u`). The August 2024 Warner DMCA wave took down most unofficial m3u8 mirrors. The seed entry is recorded honestly with an `.invalid` placeholder URL — the prober marks `cnbc` as `dns_failure`, the menu shows it offline, the wall renders an honest OFFLINE panel for any operator-pinned slot. This is the same honesty discipline as the prior CNN International / C-SPAN entries.
**Reconsider when:** A free public CNBC HLS surfaces (occasionally happens with FAST platforms), or the operator wants to accept a different financial channel as a substitute. Bloomberg Originals, Yahoo Finance, and Cheddar News are catalog-available substitutes if the operator changes their mind on CNBC specifically.

## Kiosk / foreground / boot story — SCAFFOLDING BUILT (2026-06-06); on-hardware validation STAGED for the migration session

**What:** MyMTS's long-uptime kiosk behavior — a foreground service that holds the screen for the ambient-wall role + a boot-receiver so it relaunches on reboot (own-the-box, Model A).
**Status (2026-06-06 kiosk-scaffolding chapter):** the **code is built + unit-tested** (`KioskPolicy`/`KioskPrefs`/`KioskService`/`BootReceiver`, manifest, MainActivity `--ez kiosk` hook; 7 `KioskPolicyTest` cases; opt-in/off-by-default so the shared APK is inert on `.182`). See `ARCHITECTURE.md §17`, `docs/findings/12-kiosk-boot-scaffolding.md`, and the threat entry. **What remains is on-hardware validation only**, STAGED for this afternoon's migration session on the dedicated box: foreground hold over hours, boot-receiver via a real reboot, low-memory survival. The ordered steps are in `docs/OPERATIONS.md §"New MyMTS box provisioning" → "Migration runbook"` (steps marked [STAGED]).
**Reconsider when:** the migration session runs the [STAGED] validations on the dedicated box; once green there, this item closes entirely.

## ~~Helper feeds API — SQLite cross-thread bug (latent since the TLS dual-listener)~~ — FIXED (feed-sources expansion, 2026-06-05)

**Fixed.** The `Depends()`-yielded sqlite connection in `feeds/api.py` and `channels/api.py` was opened and closed through two separate `run_in_threadpool` calls (FastAPI's handling of a sync `yield`-dependency), which could land on different anyio threadpool threads → `sqlite3.ProgrammingError: SQLite objects created in a thread can only be used in that same thread` → intermittent HTTP 500 on `/api/feed`. Replaced with `db.connection_scope(db_path)` opened **inside** the sync route body, so the whole connection lifecycle stays on the single threadpool thread that runs the route. Regression-guarded by two concurrency tests (`test_feed_endpoint_survives_concurrent_threaded_requests`, `test_channels_endpoint_survives_concurrent_threaded_requests`) that hammer the endpoints from 8 client threads. Fixed as part of the feed-sources expansion, since more sources stress the feed path.
**Remaining sub-item (separate, low priority):** revisit whether to collapse the dual-uvicorn-instance architecture in `__main__.py` (now that HTTP is gone, the second listener exists only as a leftover) to a single `uvicorn.run`. This is independent of the cross-thread bug — which is fixed regardless of listener count — and is pure tidy-up.
**Considered + deliberately deferred again (2026-06-06 pre-migration hygiene pass):** the entry point's `lifespan="off" if cfg.port else "on"` logic is load-bearing (it's what stops the pollers — now including the new ticker pollers — double-starting across the two listeners). Refactoring it the same day as a migration whose **prerequisite is a clean helper redeploy that must start those pollers** is the wrong risk/reward, and the dual-listener wiring isn't fully exercisable in the unit suite (it's asyncio.gather server wiring). The scaffolding is functionally harmless today (HTTPS serves; the second listener is internal-only since the compose dropped the HTTP port mapping). Tidy it in a calm `__main__.py` session, not under a migration deadline.
**Reconsider when:** the dual-listener tidy-up is convenient to fold into other `__main__.py` work; no functional driver.

# Usage feedback — 2026-06-04 (upstairs session)

Batch of feedback the operator surfaced after actually using the wall on `.182`. Logged here so nothing evaporates; **deliberately deferred from the current session** — these are next-roadmap items, not in-flight work. Caveats below preserved verbatim where flagged because they prevent re-litigating settled foundation decisions and set honest expectations on what's feasible vs. paywalled vs. a separate scoped effort.

## A. Whole-wall D-pad navigation + menu overhaul (BIG — likely the next major chapter)

**What:** The wall isn't fully navigable from the couch. Operator can't D-pad into the feed to focus/select an article, can't focus a video cell to act on it; the menu "feels clunky" and "needs to be more robust / more human-friendly." Make the *whole wall* navigable: focus moves between feed items, video cells, ticker, and menu — everything reachable and actionable via D-pad.
**Why-not-now:** Stage 5 deliberately scoped the menu to channel/lineup control and deferred the full focus/navigation system. This feedback says that deferral has come due. It's a substantial chapter, not a tweak.
**Reconsider when:** Next major build after the current session's items land. This is the lead candidate for the next big push.

## B. Feed UX — list view, live/offline sections, selectable items

**What:** Feed should be a **list, not a continuous individual-scroll** ("unintuitive and inefficient"). Add **sections** (e.g. by source or grouping). Make feed items selectable (ties into A — navigation).
**Why-not-now:** Current feed is a chronological river; restructuring + selection depends partly on the navigation overhaul (A).
**⚠️ CAVEAT (preserve — closed-door item):** "Selecting an article" must NOT mean opening/reading the full article *in-app*. In-app article reading was ruled out in `04-TECHNICAL-APPROACH.md §5` / foundation as a security+scope dead-end (the reader-pane cross-device-auth problem). "Select" can mean focus / expand the safe summary / mark — NOT a full in-app web reader. Revisiting that is a deliberate foundation-level decision, not a feature tweak.
**Reconsider when:** With the navigation chapter (A) — the list/sections part can also go in a "UX & config" push.

## C. Layout / sizing configurability

**What:** Configurable side for the video grid (left/right of feed); video grid scales to display size + the space beside the feed; overall app resolution/sizing configurable; feed width AND font configurable.
**Why-not-now:** This is the existing "configurable panes" backlog item plus app-level sizing/resolution. Grid already autofits its region (Stage 3 polish pass) — clarify with operator what's missing vs. what exists. App-resolution-adaptiveness matters MORE once MyMTS is on its real box driving an actual TV (the in-transit hardware) vs. the small dev panel currently attached to `.182`.
**Reconsider when:** "UX & config" push; some (feed width/font) are low-effort and could come sooner.

## ~~D. Ticker — alternate markets + curated sports scores/news~~ — DONE (ticker real-data chapter, 2026-06-05)

**Done.** The ticker now shows REAL data and alternates between a markets mode (Stooq indices/FX/gold + CoinGecko BTC/ETH, all keyless) and a sports mode (ESPN public scoreboard JSON for MLB/NFL/NBA/NHL, keyless), rotating on a calm timer (markets ~22 s, sports ~14 s) via `HelperTickerSource` behind the `TickerSource` interface. Honest labeling held: real entries drop the SAMPLE pill; symbols with no free keyless source (Brent, WTI, 10Y UST) stay sample; an unreachable helper falls back to honest sample (markets) / "scores unavailable" (sports), never frozen-live.
**Remaining (deferred sub-items):**
- **Per-team / per-league curation UI** — the chapter ships a sensible default league set (MLB/NFL/NBA/NHL) + the mechanism; a UI for the operator to pick leagues/teams is the follow-on. Until then, the league set is edited in `ticker/sports.py::DEFAULT_LEAGUES`.
- **Sample-only market symbols** — Brent, WTI, 10Y UST remain honest SAMPLE (no clean free keyless source verified). Revisit if a keyless source surfaces (Stooq may cover oil/rates under symbols worth re-checking).

## E. More video channels (channel-supply — recurring thread)

Channel supply is the standing follow-on; these extend it with varying feasibility. Each carries its own honest caveat.

### E1. League networks — MLB / NFL / NBA / NHL Network
**What:** Add league-network feeds for the major US sports leagues.
**Why-not-now:** ⚠️ Almost certainly **paywalled, no free public HLS** (same as CNBC). Worth a look during the next channel-resolution pass to confirm, but temper expectations.
**Reconsider when:** If a free feed is found OR operator accepts a paid source (which would require a separate auth/source-of-truth design — these are not just-a-URL additions).

### E2. The Weather Channel
**What:** Add Weather Channel as a tile option.
**Why-not-now:** Likely a **free FAST feed** (Pluto / Samsung TV Plus etc.) — good candidate, findable.
**Reconsider when:** Next channel-resolution pass.

### E3. Chicago sports-talk video simulcasts (ESPN 1000 + 670 The Score) — LOWER PRIORITY

*(Corrects the earlier dismissive note that suggested these don't exist as reachable feeds. The operator was right; web check confirmed the feeds. The reason this is still not a seed-URL add is the integration shape, not the existence of the feeds — caveats below.)*

**What:** Both Chicago sports-talk radio stations video-simulcast their shows and are reachable:
- **ESPN 1000 (WMVP):** YouTube channel + Twitch — `twitch.tv/espn1000chicago` (confirmed live/active 2026-06-04).
- **670 The Score (WSCR, also 104.3 FM):** YouTube channel + Twitch — `twitch.tv/chicago670thescore` (confirmed; station's own YouTube directs viewers there).

**⚠️ Why this is NOT a seed-URL add (the real integration reality):**
- These are **Twitch/YouTube Live channels, not HLS**. The whole architecture is HLS-native (ExoPlayer plays HLS; the helper validates `#EXTM3U`). Twitch/YouTube don't expose a stable HLS URL — they sit behind their own player/auth/token systems.
- Playing them requires **stream extraction** (yt-dlp / streamlink class of tooling): resolve the page, negotiate tokens, pull the underlying media URL — which **rotates and expires**, so it's continuous re-extraction, not resolve-once.
- **Trust Bar implication:** running a stream-extractor against arbitrary remote sites expands the helper's attack surface — exactly the hostile-remote-content handling the SSRF-safe fetcher was built to contain. Needs a deliberate **threat-model pass**, not a casual add.
- **ToS implication (operator-decision, like geo-block circumvention):** programmatic extraction outside official players generally violates Twitch/YouTube terms. Operator's risk call, not an engineering default — flag it, don't bake it into defaults.
- **Cleanest likely architecture if pursued:** a **`streamlink` sidecar** that converts a Twitch/YouTube URL into a local HLS stream the helper points at — keeps extraction in a purpose-built tool rather than hand-rolled in the helper. Still a real new component with its own footprint + threat-model.

**Why-not-now / PRIORITY (operator-agreed):** **Lower priority.** The HLS FAST channels (Weather Channel, AP/Reuters feeds, etc.) and the whole-wall **navigation overhaul** come first — high-value, low-risk. This extraction feature is a deliberate, scoped chapter to tackle when it's the main focus, with its own threat-model + ToS decision, not a side-quest.

**Reconsider when:** Operator wants it as a focused build; budget a threat-model pass + the streamlink-sidecar design as part of that chapter.

### E4. WGN news/sports
**What:** Add WGN news/sports as a tile option.
**Why-not-now:** WGN America largely defunct as a national entity; local WGN Chicago may still have streams.
**Reconsider when:** Channel-resolution pass — check current availability.

## F. Feed source quality — more reputable sources

**What:** Add reputable feed sources — AP, Reuters, CNN, etc. Research what `monitor-the-situation.com` uses as a reference (the visual + editorial reference for this whole project).
**Why-not-now:** Clean, high-value, low-effort — just more RSS sources in the helper seed (current: BBC World, Al Jazeera, Guardian World, NPR World). Held only to keep the current session focused.
**Reconsider when:** Soon — pairs naturally with any helper/seed work. Research MTS's source list as input.

## Ground News as a feed source — NOT pursued (security + no API); use more public RSS instead

- **What was asked:** source MyMTS feed articles from the operator's Ground News subscription.
- **Finding (checked 2026-06-04):** Ground News has **no public API and no RSS export of a personalized/custom feed.** Custom Feeds (a Premium/Vantage feature) are **Ground Web-only** — a web-app feature, not a data feed. Notably the relationship is inverted: Ground News *ingests* RSS from sources and lets users *suggest* sources via RSS link; it does not *publish* your feed as RSS. So there is no tokened feed URL to drop into the helper seed.
- **⚠️ Why NOT to pursue it even via workaround (the important part):**
  - The only way to get the personalized feed would be **authenticating as the operator** (their subscription login) inside the helper and **scraping Ground Web's HTML**. That requires storing the operator's Ground News credentials/session on the NAS and having the helper act as them.
  - This **crosses the credential line the helper has deliberately NOT crossed** (A7 / Trust Bar): the helper does defensive, anonymous, public-RSS parsing, holds no operator secrets, and uses the SSRF-safe fetcher. Pulling an authenticated personal feed would mean real credential-handling pointed at a service that doesn't want programmatic access — the most security-fraught possible feed source for a project whose #1 value is "never a path into the home network."
  - It's the same class as the Twitch/YouTube extraction, but **worse** — it needs the operator's authenticated session, not just a public page; fragile (breaks on markup changes); against Ground's ToS.
  - **Low payoff anyway:** Ground's value is the *bias/source-comparison/blindspot/ownership* layer, which a scraped headline feed wouldn't carry. The underlying articles mostly come from public sources (Ground aggregates 60,000+) that have their own public RSS.
- **Sanctioned alternative (what actually delivers the intent):** add more reputable **public RSS sources** to the helper seed — AP, Reuters, the wire services, a politically-balanced spread (this is feedback item F, already flagged low-effort/soon). Delivers the source-diversity that makes Ground valuable, via the public-RSS path the helper is built for, zero credential/ToS risk.
- **Optional future feature in the same spirit:** MyMTS could maintain its *own* per-source bias/lean tags in the helper's source list (a home-grown bias layer, not Ground's) — log as a possible future feed enhancement if the operator wants the bias-awareness concept without Ground.
- **Reconsider when:** Only if Ground News ever ships a real public API / personal-feed RSS export. Until then, the public-RSS-expansion path (F) is the answer.

## Markets ticker — Stooq anti-bot challenge from the NAS egress (surfaced 2026-06-06 redeploy)

**What:** After the helper redeploy, the markets ticker shows **BTC/ETH real (CoinGecko) but indices/FX/gold on honest SAMPLE pills** — because, from the NAS's egress IP, **Stooq now returns a JavaScript proof-of-work anti-bot challenge** (HTTP 200, an HTML/JS page) instead of the CSV quote snapshot. The defensive parser finds zero rows and falls back to SAMPLE per the C3 honesty contract (correct, not faked-live). The ticker chapter verified Stooq cleanly from the dev Mac; the NAS IP is being bot-walled — a network-path/data-source issue, not a code or deploy bug.
**Why-not-now:** Not blocking — the markets ticker degrades honestly (real crypto, SAMPLE indices/FX/gold) and the rest of the helper is fully live. Fixing it is a data-source choice, not a redeploy concern.
**Reconsider when:** the operator wants real indices/FX/gold on the wall. Options: (a) a different **keyless** indices/FX/commodity source that tolerates the NAS IP (re-survey like the ticker chapter did, with ToS caveats); (b) a free-tier keyed source (crosses the helper-holds-a-secret line — handle per the .env discipline); (c) accept SAMPLE on those symbols. CoinGecko (crypto) is unaffected and stays real.

## Send-to-phone for richer article reading (QR pair) — closed-door-compatible

**What:** A future feature for the focused-feed-item SELECT path: alongside the current safe in-place expansion of the helper's plain-text summary, surface a small QR (and/or operator-pre-paired phone notification) that opens the article URL on the operator's phone. The full article is read on the phone's browser — a context where the operator's existing browser hygiene + the article's own platform already apply — not in MyMTS.
**Why this respects the closed door:** the in-app full-article web reading path is permanently closed (BUILD-PROMPT §4, lines 81/130/178). This entry is **not** that path: MyMTS never fetches the article HTML, never renders it, never proxies through the helper for the operator's session. The QR/notification is a *handoff to the phone*, the phone owns the read — same shape as "scan to open on phone" patterns in news apps. A1 / B4 hold because nothing about the article ever crosses into the TV or helper's render layer.
**Why-not-now:** Out of scope for the navigation chapter (chapter is whole-wall D-pad UX, not reading flow). Cross-device handoff also needs care: QR is the simple form (no auth, no pairing), notification-to-phone requires a one-time pairing flow which is a small but real surface. Decide which form (or both) at design time.
**Reconsider when:** Operator wants a richer reading flow than the in-place safe summary. Likely pairs with item B (feed UX list/sections) since "select to send to phone" is the natural next action verb once feed items are selectable.

## Overall UI sizing — deferred (Stage 8 closeout, operator-chosen)

**What:** A global UI scale/density setting (e.g. `Compact / Default / Roomy / Spacious`) that applies to ALL wall chrome at once — feed, grid chrome, ticker, menu — implemented as a `CompositionLocal` density override. Above the per-piece feed-width and feed-font settings already shipped.
**Why-not-now:** Surfaced as the vision question called for in the Stage 8 chapter prompt (§5). The operator chose to defer until the wall is on its real production hardware (the new MyMTS box, in transit) where it can be tuned against an actual 10-ft viewing distance. The per-piece controls (feed width + feed font + grid side) ship now and cover the immediate skim/legibility needs; revisit whether a global scale is still wanted once the operator's been at the real box for a session.
**Reconsider when:** New MyMTS box arrives, is driving a real TV at the wall's intended distance, and the operator's at-the-box feel-test surfaces "I want EVERYTHING bigger/smaller in lockstep, not just the feed." If so, implement as a single CompositionLocal density multiplier with the same 4-preset shape as the per-piece controls; persist alongside WallSettings.

## Feed filtering / search UI — FILTERING BUILT 2026-06-06; free-text SEARCH deferred

**Filtering BUILT (Stage 12, feed-filtering chapter):** source filter (denylist toggle — `WallSettings.hiddenSources`) + recency filter (All / 1h / 6h / 24h) in the settings menu, persisted; operates on already-fetched plain text (A1 held). See `ARCHITECTURE.md §19`, `docs/findings/13-feed-filtering.md`, the THREAT-MODEL reverify entry.
**Free-text SEARCH — still deferred (operator-reversible):** D-pad on-screen-keyboard free-text entry on a 10-ft ambient wall is high-friction for low value; source + recency filtering delivers most of the "narrow the feed" benefit without text entry. If the operator wants keyword search after using the wall, it would still operate on the already-fetched plain text (no web search) via an Android-TV on-screen keyboard / character picker.
**Topic/keyword AUTO-classification — stays deferred** (foundation v2 idea; topic filtering = keyword search, not auto-tagging).
**Reconsider when:** the operator finds source/recency filtering insufficient and specifically wants to find a story by keyword at the wall.

## Feed section collapse / jump-by-source — deferred (Stage 7 closeout)

**What:** Make section headers focusable / collapsible so the operator can collapse a section to hide its items, or press LEFT/RIGHT on a header to jump to the previous/next section.
**Why-not-now:** The current restructure keeps headers visual-only — the focus model didn't change, no-trap invariants stayed pinned without modification. Adding section-jump or collapse semantics expands the focus model (a new "header" focus position, or a new intent for section-jump). Worth doing if the operator finds DOWN-DOWN-DOWN inefficient through a 20+ item section, but the visible sectioning alone should already help orientation. Defer as a focused follow-on.
**Reconsider when:** Operator's at-the-box feel-test surfaces "I can see the sections but DOWN-by-one through them is still slow."

## Curation & preferences pass — BUILT 2026-06-06 (Stage 13); team-level + urgency deferred

**BUILT (Stage 13, curation chapter):** A = sports curation (league-level toggles, TV-side filter); B = ticker news (third rotation mode, default OFF, no faked urgency); C = feed-source toggles (reused from Stage 12). See `ARCHITECTURE.md §20`, `docs/findings/14-curation-pass.md`, the THREAT-MODEL reverify entry.

**Update (2026-06-06, web-rework chapter):** **out-of-season / far-future suppression is now done** — the sports ticker filters to *current games only* (in-progress always; finals within ~12 h back; scheduled within ~12 h forward), so an off-season league (NFL-in-June showing only September fixtures) is omitted entirely instead of surfacing stale games. Helper-side, both clients. See `docs/findings/15-web-rework-and-ticker.md`. This closes the "stale games in the ticker" gap; team-level curation below is still deferred.

**Deferred from this pass (FEEL-TEST + honest-engineering):**
- **Team-level sports curation** (favorite-teams pinning — operator's Chicago teams) — league-level shipped; team granularity is a FEEL-TEST follow-on (confirm after seeing scores flow on hardware).
- **True breaking-news / urgency detection** — NOT faked. RSS can't reliably flag urgency; building real urgency detection needs a genuine signal source (a dedicated breaking-news feed/API, or an LLM-classify pass — itself a foundation-v2 item). The honest source-subset + newest-first version shipped instead.
- **Dedicated ticker-news source subset** distinct from the feed denylist — if a 13-source ticker reads as noisy after use, give the ticker its own (narrower) source selection.

The original scoping (preserved below for the captured open-questions) —

The data plumbing is being built (real markets + sports ticker, 13 feed sources, channel lineup). The operator wants control over *what* flows through it. These curation controls belong together, likely as a settings-menu expansion (Stage 5 / UX-config lineage).

### A. Sports curation (refines the ticker sports mode)
- **What:** The ticker-data chapter ships a default sports set (MLB/NFL/NBA/NHL) + mechanism, with full curation deferred. The operator wants to narrow it to the sports/teams they care about.
- **Open question for the operator (capture, don't presuppose):** curation at the **league** level (toggle which leagues), the **team** level (pick favorite teams — note operator's Chicago context from other feeds: likely Blackhawks/Cubs/Bears/Bulls/etc.), or **both** (leagues for breadth + favorite teams pinned/prioritized). The answer shapes whether it's a simple league-toggle list or a richer team-picker.
- **Reconsider when:** After the ticker sports mode is live and the operator has seen scores flow; pairs with the settings-menu curation surface.

### B. Ticker news parameters (new — news in the ticker)
- **What:** The operator wants news in the ticker (currently markets, soon + sports). Could be a third rotation mode (markets → sports → news) and/or breaking-news interrupts.
- **Open design questions (capture for the discussion):**
  - Separate news *mode* the ticker rotates to, vs. breaking-news *interrupts* that push in regardless of current mode (or both)?
  - "Parameters" = which *sources* feed ticker news (a subset of the 13 RSS sources?) and/or *filtering* (only breaking/urgent, certain topics)?
- **Ties into:** the queued feed filtering/search chapter — "what counts as ticker-worthy news" overlaps with "how to filter the feed pane," so these may be designed together.
- **Reconsider when:** Alongside the feed-filtering chapter and/or the curation pass.

### C. (Cross-ref) Feed source enable/disable
- The 13 feed sources are all enabled by default (`enabled=1`). A natural companion to the above curation controls: let the operator toggle which sources appear in the feed (and potentially which feed the ticker). Cross-ref the existing settings-menu surface (Stage 8 UX-config) and the feed-filtering/search deferral above.

### Framing note
- These three (sports curation, ticker news params, feed-source toggles) cohere into a **"curation & preferences" chapter** — the "tune what I see" controls, living in the settings menu (Stage 5 / UX-config lineage). Logged together so they're scoped as one coherent future chapter rather than scattered tweaks.

## ~~G. Sports-data source (enabler for D + the original ticker sports mode)~~ — DONE (ticker real-data chapter, 2026-06-05)

**Done.** Identified + wired a free, keyless sports source: **ESPN's public scoreboard JSON** (`site.api.espn.com/apis/site/v2/sports/<sport>/<league>/scoreboard`) for MLB/NFL/NBA/NHL — no API key, parsed defensively through the SSRF-safe fetcher. ToS posture (undocumented-but-public endpoint, personal non-commercial use) confirmed with the operator. No new secret crosses the helper. Honest staleness covers the "endpoint vanished" case. Investigation also evaluated TheSportsDB (test-key works but ESPN is richer/keyless) — recorded in `docs/findings/11-ticker-real-data.md`.

## H. Cross-platform profiles — ARCHITECTURE FORK, decide before building

**What:** Should a user's profile (lineup + audio/caption/layout prefs) be **shared across platforms/devices** (this box, the in-transit MyMTS box, a future browser client), or stay **device/platform-specific**?
**⚠️ This is a genuine architecture decision, NOT a tweak — flagged by the operator for a REAL discussion when we reach that chapter (operator explicitly deferred deciding).** Lay out both paths when the time comes:
- *Device-local (current):* `LineupStore` on each box. Simple, private, no sync, no server-side user state.
- *Helper-hosted:* prefs live in the helper (server-side); any client reads the same profile. Enables sharing, but the helper now stores user state and you need a sync / identity model. Connects to the browser-client backlog item (a second client makes shared profiles more compelling).
**Why-not-now:** Operator wants a deliberate discussion of the tradeoff, not a snap call. Do not presuppose an answer.
**Reconsider when:** As its own decision point — likely alongside the browser-client question OR when the second (MyMTS) box is provisioned and the "same prefs on both boxes?" question becomes concrete.

## Ideas that surfaced during build (add as you find them)

```
# Use this format:
#
# ## <slug>
# **What:** one line
# **Why-not-now:** which Vision principle defers it
# **Reconsider when:** the condition that would make it worth doing
```
