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

## Production deployment — app-vs-app foreground conflict on the Onn box

**What:** WyzeGrid runs a persistent `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` watchdog (`SYSTEM_ALLOW_LISTED`) that reclaims the foreground from any other TV app within ~80 min. The first gate-clearing soak failed because of this — MyMTS lost the foreground, was backgrounded, and Android then evicted it under memory pressure on the 2 GB box.
**Why-not-now:** Stage 1 only needs to measure the box; for the soak window we side-step the conflict by `pm disable-user com.wyzegrid` on `.182` and re-enable after. That doesn't generalize to production.
**What this means for production:** if MyMTS is ever installed on a box that also runs WyzeGrid (or any other foreground-service kiosk app), they will contend. MyMTS will need its own foreground service + watchdog story for the long-uptime kiosk role (which Stage 6 was already going to land).
**Reconsider when:** Stage 6 lands the long-uptime watchdog + boot-receiver story. At that point, design for coexistence (or assert "one kiosk app per box, MyMTS is the kiosk").

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

## Browser / PWA client (SEPARATE CLIENT, not a mode of the native app) — read the caveat

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

## Ideas that surfaced during build (add as you find them)

```
# Use this format:
#
# ## <slug>
# **What:** one line
# **Why-not-now:** which Vision principle defers it
# **Reconsider when:** the condition that would make it worth doing
```
