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

## White-whale channels — research verdicts (2026-06-21)

The "white-whale" channels from the original vision, with their sourcing verdicts so the
research is captured. The distinction is **free + DRM-free + official (no ToS-gray ingestion)**
vs **free-but-ToS-gray** (free, no DRM, but obtained by bypassing the source's own app/ads) vs
**BLOCKED** (DRM or no public stream).

- **SOLVED-CLEAN — shipped (2026-06-21).** Free official explore.org YouTube lives, `is_live`-gated
  → honest-offline, server-authoritative (Ocean + Eagles presets, both clients no-rebuild):
  **Tropical Reef** + **Manatee Cam** (Ocean) and **Decorah Eagles** (seasonal). Verified live on
  the NAS vantage. Per-cam video IDs (explore's `/live` rotates); a dead ID honest-offlines.
- **OMITTED (no clean source).** **CBS News 24/7** — no stable free 24/7 YouTube live exists
  (`@CBSNews/live` is event-only; `/streams` are VODs); it lives on Paramount+ / their app. Not
  faked. Revisit if CBS restores a persistent free YouTube live.
- **FREE-BUT-ToS-GRAY — pending a SEPARATE operator decision (NOT actioned here).** Free, no DRM,
  but ingestion bypasses the source's own app/ads, so it's the operator's call, not an engineering
  default: **Chicago O&O local news** (the station's own free stream), **WCIA** (via Haystack /
  Roku channels), **CBS Sports HQ** (via Pluto TV). Buildable as wall feeds technically; left
  un-added pending the operator's explicit go.
- **BLOCKED — structural, won't-build.** **Marquee / CHSN / WGN-direct** = DRM (same closed door as
  the premium-DRM-sports entry below). **Parkland College / Heartland** = no public stream exists
  to source at all.

## Follow-ups from the 2026-06-22 perf + channel pass

- **~~Re-source candidates (persistently dead).~~ RESOLVED 2026-06-24 — re-sourced + pruned.**
  `c-span` (the branded cspan1 Akamai, `http_403`) and `cnn-international` (Rakuten/Wurl host
  `dns_failure`) were re-sourced this pass and, finding **no clean free source**, **pruned**:
  - **cnn-international** — host DNS-dead; CNN International is on no US FAST platform (Pluto's
    "CNN Headlines"/"CNN Originals" are different curated channels, not the international linear
    feed); iptv-org US has no entry. No clean free source.
  - **c-span** (branded cspan1) — `http_403`; the three linear C-SPAN networks online are
    MVPD-login-gated. The token-FREE C-SPAN path is the **government event streams** — already
    built as the `us-senate-floor` / `us-senate-committees` `cspan` resolvers (unaffected by
    this prune). A branded-cspan1 token-handshake sidecar stays deferred (out of "re-source a URL").
  (Earlier prune this lineage: `cnbc` placeholder, `al-jazeera-en`, `cgtn-en`, `trt-world`.)
- **P-N2 full scope-isolation (deferred).** `@Immutable WallSettings` landed; the deeper
  `WallScreen` refactor (read each settings group in a narrow wrapper so a ticker-speed nudge
  recomposes only the ticker subtree) was deferred — high-risk on a 700-line composable for an
  occasional operator-adjusts-a-setting event. Revisit if settings-nudge hitch is felt.
- **P-N3 live confirmation.** The in-place URL swap is unit-proven (no manager rebuild on a token
  rotation); a *live* token rotation takes hours to observe — the `StreamPlayer.updateUrl` logcat
  marker ("resolved url rotated — swapping media source in place") lets the operator confirm a real
  rotation no longer reinits the grid.
- **Web/helper Med perf (not done this pass).** Offscreen tile culling (no IntersectionObserver),
  hls.js worker/buffer tuning, per-poll DOM rebuilds — logged from the read-only analysis, low-risk
  but deferred to keep this pass focused on the `.92` CPU triad.

## ~~Headless container version — render → HLS → VLC engine~~ — DONE (2026-06-24)

**Done — the wall is on the TV.** Both halves of the headless NAS-container version
shipped: the server-side wall config (`/api/wall`) + picker (`/control/`) + `/app/`
rendering from it (`ARCHITECTURE.md §26`), and the **render → HLS → VLC engine**
(`§27`): a `mymts-renderer` container (Xvfb + Chromium `/app/?render=1` + a PulseAudio
null sink + ffmpeg `x11grab` → HLS), the helper serving it at `/api/stream/playlist.m3u8`,
config-driven-live (a `/control/` pick reflected in the stream with no restart). Verified
on the deployed NAS: valid advancing HLS (h264 1080p + AAC), live tiles + feed + ticker,
a live `/control/` change reflected. The **Apple TV / stream-out** goal is met — VLC opens
one URL. Composes with the M3U playlist / profiles foundation (§24): the playlist serves
the channel *list*, the renderer serves the composed *wall*. The profile question stays
resolved-by-necessity for the headless context (server-side state), without prejudging the
native device-local model.

**Future optimization (not now): GPU passthrough for the renderer.** v1 is **CPU-only**
software x264 + N tile decoders (the cell count drives the load), bounded by the compose
cpus/mem caps. A future optimization is GPU passthrough (VA-API / NVENC) into the renderer
container for hardware decode + encode — lower CPU, more tiles / higher fps. Deferred:
needs device passthrough config on the NAS (and must still never disturb the helper / other
containers / PIA). Revisit if the CPU-only renderer's cell count or fps proves limiting.

**Renderer hardening follow-ups (minor, from the pre-push review).** The symlink-escape
blocker was fixed + live-verified; these remain as optional v1.1 hardening: (a) a
**branded holding frame** until the helper answers, so a cold-boot race never shows a brief
connection-error page in the stream (today it self-heals within a poll); (b) **pin the
helper's self-signed cert** in the renderer instead of `--ignore-certificate-errors` (a LAN
footgun if the URL behavior changes); (c) **read-only-rootfs** for the renderer (Chromium
needs writable dirs — would need a careful overlay/tmpfs layout). None are correctness or
isolation issues today.

**Stream serving: HTTP for tvOS today; HTTPS-everywhere a future alternative (2026-06-25).**
The HLS is served over **plain HTTP on `8082`** because Apple TV VLC hangs on the helper's
**self-signed** HTTPS (it won't prompt to accept; the segments ride the same HTTPS, so they
stall too — see `ARCHITECTURE.md §27`). HTTP is fine for a LAN-bound public video stream
(the API + `/control/` + `/app/` stay HTTPS), but a future alternative is a **trusted LAN
cert** (e.g. an internal CA the Apple TV trusts, or a real cert for a LAN hostname) so the
stream could go back to HTTPS-everywhere. Deferred: needs cert provisioning/trust on the
player side; the HTTP-on-LAN path is the pragmatic, working fix and is not a security issue
for public video.

## ~~Headless wall — layout normalization + multi-resolution~~ — DONE (2026-06-25)

The presentation pass shipped (`ARCHITECTURE.md §29`): cell sizing/spacing normalized to one
`--gap` gutter rhythm with uniform `object-fit: contain` + a uniform per-tile caption; the feed
↔ grid read as one composition; the whole wall is **responsive** off a single `--u` scale unit
(`computeUx = min(w/1920, h/1080)` → `--ux`), so no hardcoded px breaks off-1080p; and
**configurable render resolution** (`render.resolution` 1080p | 2160p in the wall config, a
`/control/` selector) wired end-to-end (Xvfb + ffmpeg + the web auto-scale) with the renderer
restarting its stack on a change. Verified live: 1080p ↔ 4K switching, 4K text sharp.

**4K is opt-in + bounded** — a live OOM (the deep x11grab queue ballooning a 4K raw frame ~33MB
past `mem_limit:2g`) was fixed with a resolution-bound grab queue + 4K headroom on the renderer's
own caps (`cpus 4→6`, `mem 2g→4g`). Open 4K follow-ups (not blocking): **GPU passthrough** (the
existing item above) would make 4K cheap (hardware decode/encode vs ~4.3 software cores); and a
weak NAS may need `RENDER_FPS=24` (documented lever) to hold 30fps at 4K.

## Headless wall — re-resolve cadence on persistent tile failure (minor, 2026-06-25)

The indefinite auto-reload (`§28`) re-resolves a tile's URL by re-reading the latest
`/api/channels` snapshot on each reattach — which `/app/` polls every 60s. So a tile that
keeps failing picks up a freshly-rotated FAST URL within ~a minute (well inside the ~45s
backoff settle). A possible future refinement: on a tile that's failed several times,
proactively trigger a channel re-poll (or a targeted re-resolve) so a token-expired URL is
refreshed faster than the 60s cadence. Not needed today (the 60s poll + 45s backoff already
converge); revisit only if a specific CDN's URL TTL proves shorter than the poll interval.

## Web-feed-filter parity for News genre groups — conscious deferral (2026-06-23)

The native feed-source filter became a **two-level genre → source filter** in v0.3.0
(`FeedGenres` taxonomy + `NewsFilterOverlay`; see `ARCHITECTURE.md §22`). The **web**
client keeps its existing per-category feed-source grouping for now — porting the
two-level genre toggle to it is **deliberately deferred**, tied to the unresolved
**cross-device profile-sharing** decision (the cross-platform-profiles fork, item H):
whether feed-filter state is per-device (today's model — `hiddenGenres`/`hiddenSources`/
`hiddenLeagues` live in each client's local store) or a shared profile synced via
per-client helper state. Building web parity now would either duplicate the per-device
denylist (fine, but a second place to maintain the taxonomy) or pre-commit the profile
fork. Native ships first; revisit the web port when the profile-sharing fork is decided.
The taxonomy itself already matches the helper's `feeds/category.py`, so the web port is
mechanical once the state model is chosen.

## Premium DRM sports feeds — BLOCKED by DRM, not buildable as wall feeds (2026-06-17)

**Structural "won't-do," not a deferred feature.** The operator holds legitimate paid subscriptions (MLB.tv / MLB Network, Marquee, CHSN via Comcast, Hulu Live TV incl. NFL Network / ESPN, NBA League Pass). The blocker is **NOT authentication** — it's **DRM** (Widevine / FairPlay), the standard for premium sports. Ingesting these into the wall's generic players (hls.js / ExoPlayer) would require **circumventing the DRM** to obtain decryption keys — DMCA §1201 anti-circumvention + ToS violation — which is **out of scope and will not be built**. The subscriptions do not change this; it's a legal/structural barrier, not a missing feature.

**Legit-buildable adjacent ideas (if ever pursued):**
- **(a) Free YouTube shoulder content** from these networks — highlights / pressers / studio shows, **not** live games — via the existing yt-dlp resolver (`kind='youtube'`, gated on `is_live`).
- **(b) Schedule / score surfacing** in the ticker or feed for the operator's teams — sports *awareness* on the wall without DRM video. Candidate integration with the operator's **Rabbit Ears** sports tracker.

Recorded so the "why aren't my paid sports on the wall?" question has a standing, honest answer (DRM, not a TODO), and the no-DRM-circumvention line stays explicit.

## C-SPAN sourcing — the FREE/GATED boundary (conclusion, 2026-06-17)

A standing decision-record for "what about C-SPAN?", drawing the line once so it isn't
re-litigated. C-SPAN content splits cleanly into two buckets with opposite verdicts:

- **FREE, no-login government feeds → BUILDABLE (and built).** C-SPAN streams the
  House/Senate floor, hearings, and federal events with no login, no DRM, no token. We
  source these directly from the government's own players, not C-SPAN's:
  - **U.S. Senate floor** — `kind='cspan'` resolver (`channels/cspan_resolver.py`): reads
    senate.gov's `floor_schedule.json` → `convenedSessionStream` (a daily `stv`+MMDDYY
    filename) → the Akamai HLS master. No Akamai token, no auth handshake, no `EXT-X-KEY`.
    Honest-offline when not in session. **Shipped** (this entry's CHANGELOG, 2026-06-17).
  - **U.S. House floor** — the House Clerk's free YouTube live (`kind='youtube'`), also
    honest-offline. **Shipped** (the Sky/House entry, 2026-06-17).
- **The three entitlement-gated C-SPAN linear networks (C-SPAN / C-SPAN2 / C-SPAN3) →
  STRUCTURAL WON'T-DO.** Their free web player is MVPD-auth-gated (Adobe Pass / TV
  Everywhere) over tokenized Akamai; obtaining a playable manifest requires a TV-provider
  login the helper will never drive (it sends no credential, follows no auth handshake).
  This is the same closed door as the premium DRM sports entry above — a boundary, not a
  TODO. The existing `c-span` channel in the lineup is the **separate** open cspan1 akamai
  direct-HLS feed, unrelated to the gated networks.

**Corrects two earlier honest-but-now-superseded omissions:** the "CNN International /
C-SPAN — no public free linear HLS" entry below (which read C-SPAN's *session-token web
player* as the only path) and the prior pass's "U.S. Senate Floor — frozen VOD, no live
entry point" CHANGELOG note (a *not-in-session* false-positive). The live entry point is
senate.gov's `convenedSessionStream`, missed before — so the free floor feed IS buildable
when the chamber is in session, while the gated networks remain out of scope.

**Generalizes — ~~not built now~~ NOW BUILT (2026-06-17):** the `cspan_resolver` recipe
(read a .gov schedule/ISVP → build the open Akamai master, gated on live) was generalized to
**Senate committee hearings** (the `hearings.xml` schedule → the aggregate "U.S. Senate
Committee Hearings" tile) plus **House committee + federal-event** feeds via official YouTube
`/live`. See the CHANGELOG (gov-stream generalization, 2026-06-17) and the refinements entry
below. The resolver remains the reusable foundation for any further free .gov ISVP feeds.

## Free gov-stream feeds — refinements + caveats (2026-06-17)

Follow-ons logged from the gov-stream generalization (committee hearings + federal events).
None are blockers — the shipped feeds are honest + validated; these are quality refinements.

- **Dedicated "Government" picker section.** The gov/committee/federal tiles (c-span, the two
  floors, the committee aggregate, the House committees, White House / State / War / DHS / DOJ)
  are categorized **US News** today. A dedicated **Government** category would group them
  cleanly. **The native-parity blocker is now CLOSED (2026-06-18):** the native picker reads
  the server `category` (`ChannelCategory.sectionedByCategory`) and *shows* any unrecognized
  category (appended before General), so a new server-side section just works on the TV. What
  remains is the small, deliberate step of (a) adding the `Government` category server-side in
  `category.py` + remapping those slugs, and (b) giving the **web** picker the same render
  order. Reconsider when the operator wants the gov feeds visually split out of US News.
- **Committee tile — surface *which* committee is live.** The aggregate "U.S. Senate Committee
  Hearings" tile shows one live committee (the resolver already carries the live committee name
  in `CSpanResolution.detail`); the tile label stays static. A future enhancement: write the
  live committee name through to the tile label (the prober/registry would update it per cycle).
  Also: when multiple committees are live concurrently, it surfaces one (most-recently-scheduled
  first); per-committee tiles or a rotation are a larger product call.
- **Unmapped committee comms.** `intlnarc` (International Narcotics Control caucus) and
  `agriculture` have no `streamInfo` row in the ISVP player, so those (rare) hearings
  honest-offline rather than resolve. Add their streamIDs if they ever matter.
- **Majority-branded House committee channels.** The House committee YouTube handles are the
  committee's CURRENT-majority channel (each linked from the committee's own `.house.gov` site
  today); a chamber flip can rename/move the canonical handle. A re-point is a one-line seed
  fix when it happens (the same kind-authoritative upsert used elsewhere). Not a stability risk
  now — flagged so the maintenance is expected, not a surprise.

## ~~Native helper URL was compile-time only (the #1 distribution blocker)~~ — CLOSED (2026-06-18)

**Closed.** The app resolved its helper address from the compile-time `BuildConfig.HELPER_BASE_URL`
(only overridable by an adb extra), so every user had to rebuild the APK with their own URL. Now
the URL is resolved at **runtime** (`HelperUrl.resolve`): a persisted user value (a first-run setup
screen + a Settings "Helper URL" field, reachability-tested against `/health`) **>** the adb extra
**>** the configured BuildConfig default. A stock APK points at any helper with no rebuild; the
operator's configured build resolves out-of-box (no regression). Verified on-device (`.92`). See
CHANGELOG (2026-06-18) + ARCHITECTURE "Helper-host boundary".

**Distribution make-or-break — ALL FOUR CLOSED (2026-06-18); the distribution arc is complete.**
(1) the compile-time-helper-URL blocker → runtime helper-URL config; (2) the clean-clone
`docker compose up` crash-loop → a writable `/data` volume; (3) signed-APK publishing → the
gated `release.yml` Android job; and (4) **per-deployment lineup** → the optional
`lineup.local.json` override (below). A downstream self-hoster can now clone → run → point a
stock APK at it → customize the lineup, with no rebuild and without editing the shipped seed.

**CLOSED 2026-06-18 — per-deployment lineup.** An OPTIONAL operator override file
(`lineup.local.json` in the writable data dir; gitignored, NOT the shipped seed) is reconciled
on top of the shipped lineup at boot — **add** new channels, **disable** shipped ones, **override**
their label/category/source_url/kind. No file → identical to today. Each effective entry is
validated + probed like any channel (bad entry skipped + logged, never crashes the lineup);
removing the override reverts cleanly (orphaned adds are pruned). Category became storable
(migration 005; API serves `category or category_of(slug)`). Ships `lineup.local.example.json`.
Verified end-to-end on a live deploy (no-override = the shipped 53; add/disable/recategorize
reflected; revert clean). See CHANGELOG + ARCHITECTURE (the override layer).

**ADDRESSED 2026-06-18 — signed-APK publishing pipeline.** `release.yml` now builds the Android
APK on every `v*` tag and, **gated on the Android keystore secrets** (present → sign + `apksigner
verify` + attach `mymts-<version>.apk`; absent → build for validation only, attach nothing), can
publish a downloadable, *runtime-configurable* stock APK alongside the desktop executables. The
pipeline is in place; whether CI signs is **the operator's signing-key decision** — either add the
four `ANDROID_*` repo secrets (CI-sign) or local-sign + manually attach (keystore stays local). See
SECURITY-PRACTICES (the two paths) + CHANGELOG. The only honest caveat left is *install reality*: a
sideloaded APK needs "unknown sources" + isn't a Play Store distribution (a deliberate posture for a
personal/small distribution, not a gap). `versionCode` is version-derived; v1+v2+v3 signing implemented.

**CLOSED 2026-06-18 — clean-clone compose crash-loop.** The generic `helper/docker-compose.yml` now
mounts a writable `/data` state volume (the read-only rootfs had nowhere to create the SQLite DB →
fixed), so `cp .env.example .env && docker compose up` brings up a working keyless helper; verified
with a local clean-clone bring-up (reproduce → fix → idempotent re-up). See CHANGELOG (2026-06-18).

## ~~Screenshot gallery + victory-lap README (Campaign 4 — the showcase)~~ — DONE (2026-06-13)

**Done.** Automated **Playwright** capture of the demo (phantom-mode) web wall → `docs/screenshots/web/` (the wall, markets/sports/news ticker, settings, channel picker), a manual `workflow_dispatch` CI job that uploads the gallery as an **artifact** (no auto-committed binaries), a `docs/screenshots/device/` dir with **labeled placeholders + a filename spec** for the operator's native-TV hero shots, and the README rewritten into an honest showcase (hero shot, feature highlights w/ inline screenshots, mermaid architecture, the engineering story, the 60-second demo quickstart). Topology-clean, claims true-to-shipped, secret-free (demo helper only). See CHANGELOG (2026-06-13, Campaign 4), `tools/screenshots/`, `docs/screenshots/`.
**Remaining = operator manual step (not blocking):** capture the **native-TV hero shots** (the real wall on a dedicated Android TV display — `wall-hero.png` / `cards-closeup.png` / `in-situ.png` per `docs/screenshots/device/README.md`) and drop them in to complete the gallery. The live-data wall is also where the bespoke per-sport cards (PGA/UFC/Tennis/F1) show — the demo serves team games + SAMPLE data only.

## Liven up the ticker league/sport marker (readability-first)

**What:** Each ticker league block has a green anchor/marker box (the pinned left-edge "curtain") with the league/sport name. Make it more visually interesting **without** sacrificing legibility — the text must stay prominent + readable, and it must stay subtle, not loud (it's an ambient glance-wall). Options to explore:
- **(a) League/sport LOGO** in or replacing the green box. *Caveat:* per-league logo assets add sourcing/licensing/legibility complexity, and ESPN actually **removed** logos in their BottomLine redesign for legibility — so logos may not be the answer.
- **(b) Per-league/sport ACCENT COLOR** for the marker — cheap, effective, legible; likely the best first move.
- **(c) A subtle sport GLYPH/icon** (⚾ 🏀 🏈 / a tasteful icon set) alongside the text.
- **(d)** Some other tasteful treatment.

**Constraint:** readability + prominence of the text is paramount; liven it up subtly, not over-the-top. Open-ended creative item — pick the livelier-but-still-clean approach when it's built. (Operator: *"get a little creative while not making it too loud and over the top, text still prominent and readable."*) Touches `PageMarker` in `TickerStrip.kt` (+ the per-sport `card` payload if a glyph/color is driven by sport kind). Do not regress the curtain's clip/pin behavior or the locked panel-fit.

## ~~Sports ticker — leagues staged for bespoke cards~~ — DONE (2026-06-11)

**All four shipped.** UFC (`fight`), PGA (`leaderboard`), Tennis (`match`), F1 (`race`) now have bespoke ticker cards on a shared per-sport `card` payload — built, deployed, and verified on the panel. See `docs/findings/21` (as-built shapes) + CHANGELOG (2026-06-11 individual-sports cards). They're active toggles in the picker now (no longer "Coming soon"). *(The original staging note is kept below for history.)*

### (history) Sports ticker — leagues staged for bespoke cards (2026-06-10)

The sports-ticker overhaul shipped the **8 team leagues** (NFL/NCAAF/UFL/NBA/WNBA/NCAAB/MLB/NHL) that ESPN's keyless scoreboard exposes in the standard 2-competitor score+clock+status shape. The operator's other four leagues are **structurally different** — they don't fit the score+clock game card and need their own card shapes. Probed live 2026-06-10 (see `docs/findings/19`):

| League | ESPN endpoint | Data shape (what a card needs) | Why-not-now |
|---|---|---|---|
| **UFC** | `mma/ufc` | 2 fighters per bout, fight-specific fields: weight class, round, method (KO/Sub/Dec) — a **fight card**, not score+clock | Needs a fight-result card design |
| **PGA** | `golf/pga` | a tournament = **~147 competitors** (a leaderboard: player, score-to-par, thru) — not a matchup | Needs a leaderboard snippet (top-N + cut line) |
| **Tennis** | `tennis/atp`, `tennis/wta` | **0 competitors** in the standard field — matches nest sets/games differently | Needs a match-sets parse + card |
| **F1** | `racing/f1` | a race = **~22 competitors** (drivers); session/standings, not a 2-team game | Needs a race/standings card |

**Re-probed live 2026-06-11 — all 4 are data-feasible (see `docs/findings/21`).** Updated shapes: UFC `competitions[]` = the fights (2 athletes + winner + status); PGA `competitions[0].competitors` = the 147-player leaderboard (score-to-par + `order`); **tennis matches DO exist** — nested under `event.groupings[].competitions[].linescores` (per-set scores), contrary to the 2026-06-10 read; F1 `competitions[]` = the 5 sessions (results populate once a session runs). **Recommended build order: PGA (live-verifiable now) → UFC → Tennis → F1.** The build is a cross-cutting change (a new per-sport `card` payload helper→app + classification into its own page + one composable per kind), deliberately **checkpointed for a session with adequate runway** rather than started under-verified (the prompt's own rule). Honest until then: **omitted / "Coming soon" in the picker**, never forced into the score+clock mold or faked.

## ~~Live market data source reachable from the NAS egress~~ — DONE (2026-06-11)

**Resolved.** Swapped the helper's markets fetch from Stooq (NAS-bot-walled) to **Yahoo Finance's keyless v8 chart endpoint**, which IS reachable from the NAS egress (validated from inside the helper container). All 14 quotes live — indices, FX, gold, **and** the former sample-only Brent/WTI/10Y UST; crypto stays on CoinGecko. Keyless, per-symbol isolation, honest SAMPLE fallback retained for genuine per-symbol failures. See `CHANGELOG` + ARCHITECTURE §16 markets-source note. *(Original ask: find a market source the NAS isn't walled from + swap the helper fetch — done.)*

## ~~Sports-selection menu — full league picker~~ + menu overhaul — DONE (2026-06-11)

**Done.** The settings menu was grouped into sections (Display & Fit / Layout & Feed / Sports) with focus-following scroll, and the league picker (the 8 leagues, all default-on, driving the same pool as scores + sports-news) now lives in the Sports section with the staged sports shown as "Coming soon: UFC · PGA · Tennis · F1". See CHANGELOG + ARCHITECTURE §settings-overlay.
**Remaining refinements (not blocking):** (1) drive the offered league list from the helper (a `/api/ticker/leagues`-style endpoint) instead of the hand-synced app-side `CURATED_LEAGUES` ↔ helper `DEFAULT_LEAGUES` pair — removes drift, "ideally ESPN-driven"; (2) reorder leagues in the picker. (3) The staged UFC/PGA/tennis/F1 move from "coming soon" into the toggle list when their bespoke cards ship.

## Hardware-aware optimal grid configs (2026-06-11)

**What:** the video grid count is now operator-configurable (1/2/4/6/9, default 2×2) and the layout is dynamic (measured-area → cells → [video + label]). Next: derive the SENSIBLE grid options from the panel's actual dimensions + resolution and **only offer those** — e.g. don't let a small 720p panel select a 9-cell grid where each video is unwatchably tiny (or the label strip can't fit); a large 4K display could allow denser grids. The system would compute viable grid counts from the real hardware (resolution, physical/usable area, min legible cell size) and present only those in the Video-grid menu.
**Why-not-now:** the operator's direction ("only allow optimal grid configs based on the actual hardware + resolution") is a refinement on top of the now-shipped configurable grid; v1 offers the fixed 1/2/4/6/9 set.
**Reconsider when:** the operator wants the menu to self-limit to viable grids. The **measured-area infrastructure from this chapter is the foundation** — the section already measures its safe area + cell sizes, so the viable-count computation (min cell size vs. measured area) is the natural next step. Pairs with the sports-selection menu under the broader menu-interface overhaul.

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

## Configurable / scalable panes (feed · grid · ticker) — PARTIAL (presets shipped; free-form pane resize still open)

**Shipped (2026-06-14):** the near-term pieces landed — configurable **feed width** (presets) + **feed font**, the grid **autofits** the space beside the feed and is operator-configurable (rows × cols, 1–3 each), grid **side** is switchable, and a **global UI scale** + panel-fit levers resize the whole wall. See entry **C** (DONE) above + the panel-fit DONE entry.
**Still open (the generalization):** a first-class, **user-driven layout where the operator freely adjusts the proportions of all three panes** (drag-style resize, beyond the feed-width presets + the fixed ticker height).
**Why-not-now:** the preset-level controls cover the immediate need; free-form pane-proportion editing is a larger layout-system effort, not a tweak.
**Reconsider when:** the operator wants drag-resizable panes beyond the current presets. Note the per-device-profile linkage (different output setups may want different default proportions).

## ~~In-app menu / settings section (WyzeGrid-style)~~ — DONE (Stage 5, struck 2026-06-14)

**Done.** The in-app settings/menu surface shipped (Stage 5 + the menu-overhaul chapter): a focusable overlay with D-pad nav for lineup/channel control (the channel picker), the sectioned settings (Display & Fit / Layout & Feed / Sports), display options + the panel-fit levers, the league picker, and the whole-wall controls. See `ui/menu/` (`MenuOverlay` / `SettingsOverlay`), the sports-selection-menu DONE entry above, `ARCHITECTURE.md`, CHANGELOG. *(In-menu "diagnostics" was not pursued as a discrete panel — the helper's `/health` + JSON metrics cover diagnostics out-of-band.)*

## Browser / PWA client (SEPARATE CLIENT, not a mode of the native app) — LAN version BUILT 2026-06-06; REMOTE deferred

**Update (2026-06-06):** the **LAN-only** version is now built (`web/`, served by the helper at `/app` on its bare LAN address — credential-free, separate-origin, same-origin-with-the-helper so no CORS, A1 closed door held). See `ARCHITECTURE.md §18`, `web/README.md`, and the THREAT-MODEL entry. The caveat below now applies specifically to the **remote-accessible** version, which remains **NOT built**:
- A **remote** web client must be a genuinely separate public origin (NOT sharing Cloudflare Access cookies with the operator's other `*.<DOMAIN>` services), with its **own fresh threat-model pass**, an **auth story** if exposed — and the tradeoff that introducing auth means the helper or client now **holds a credential** (it currently holds none) — plus rate-limiting and hostile-input-at-the-edge handling. The LAN version deliberately sidesteps every bit of this by being unreachable from outside the home network.
- **~~Also deferred (LAN follow-on): in-browser HLS video grid via `hls.js`~~ — BUILT (web-rework chapter, 2026-06-06).** The LAN client was reworked to **mirror the Onn wall** (scrolling ticker + feed pane + **`hls.js` video grid** + mouse settings) instead of a dashboard, then a round 2 (2026-06-06) gave it ESPN-BottomLine ticker league markers, an agnostic chronological feed, a **cell-count** video grid (1/2/4/6/9), click-to-pick channel selection, and honest play-what-works video. hls.js is **vendored + pinned** (`web/vendor/`, `script-src 'self'` — no CDN, `enableWorker:false` so no `worker-src` widening); `connect-src`/`media-src` scoped to `https:`/`blob:` (`frame-src`/`object-src`/`script-src` stay locked). Video playback ≠ web reading — A1 closed door held. See `docs/findings/15-web-rework-and-ticker.md` + `docs/findings/16-web-rework-r2-and-mixed-content.md`, `ARCHITECTURE.md §18`, the THREAT-MODEL entries. **LAN parity follow-ons — DONE (Campaign 3 HALF 1, 2026-06-12):** per-sport ticker **cards** (team game cards + the 4 individual kinds leaderboard/fight/match/race, rendered from the structured `game`/`card` data), **news** in the web ticker, web↔TV **settings parity** (leagues pool / grid R×C / feed recency / ticker controls, localStorage-persisted; TV-only panel-fit correctly skipped), and the **`schema_version` guard** (closes ARCH-1). See CHANGELOG (2026-06-12 web parity). **Still open:** the **cross-platform-profiles fork (item H)** — per-client helper state + a VLC/M3U **playlist endpoint** — is Campaign 3 HALF 2 (the real multi-profile groundwork); the **remote/public** web client stays deferred (separate security item).
**Reconsider when:** the operator explicitly wants remote/desktop access from outside the LAN AND there's appetite to take on the remote client's security work as its own scoped effort (threat-model it fresh against the Trust Bar before building).

### ~~Native app feed — agnostic-with-source vs per-source sections~~ — RESOLVED: AGNOSTIC, DONE 2026-06-07

**Resolved.** After living with the sectioned version on hardware, the operator decided the **native** feed should match the web client's agnostic style. Done in the panel-fit chapter: `FeedListBuilder.build` now returns a flat newest-first `List<FeedItem>` across all sources, `FeedPane` shows the source label per headline, the flat-index focus contract is preserved (feedIndex == list index; 49 focus tests pass unchanged), `FeedListBuilderTest` re-pinned for the agnostic interleave. **Both clients' feeds are now the same model** (the per-client divergence noted in finding 16 is closed). See `docs/findings/17-panel-fit-and-agnostic-feed.md`.

**Original (preserved):** The web client's feed was changed to an agnostic chronological list per the operator's hands-on preference; the native feed was left as per-source sections (Stage 7, commit a756184) pending the operator's call. That call is now made: agnostic.

### Stream proxying through the helper — DELIBERATELY NOT DONE (web-rework r2)

**What:** Route browser-blocked channels' HLS through the helper so the web client could play channels the browser refuses (mixed-content / CORS / geo).
**Why-not-now (operator decision + Trust Bar):** The operator chose **no proxy** — the helper stays the resolver/shield, **out of the video data path**. Proxying video would (a) put the helper in the bytestream (CPU/bandwidth + a new failure mode), (b) expand the SSRF/egress surface the fetcher was built to contain (a proxy is a "fetch arbitrary remote bytes and relay them" engine), and (c) blur the clean boundary where the **native app is the full-fidelity client** and the web client is an honest best-effort viewer. The honest answer for a browser-unplayable channel is the C3 "**on the TV wall**" label, not forcing it through. (For the current channel set this is moot anyway — all 10 live channels are HTTPS-clean + CORS-OK; see finding 16.)
**Reconsider when:** never, unless the LAN web client becomes a primary surface AND the operator accepts the helper entering the video path with a fresh threat-model pass on the proxy egress. Default answer is no.

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
**Update (migration 2026-06-07):** foreground-hold + boot-relaunch validated on `<LAN_IP>` — the `KioskService` foreground service starts on boot (`BootReceiver` → FGS), the box auto-restores 720p + screen-awake, and the kiosk holds the wall once it's foregrounded. The ONE thing that did NOT work unattended is bringing the wall to the **foreground over the launcher on boot** — see the dedicated entry directly below.

## Wall-on-boot (auto-foreground) — ACCEPTED manual-launch (2026-06-07); device-owner is the logged future path

**Decision (2026-06-07): accept manual-launch for now; device-owner/lock-task deferred to a deliberate future project.** Researched + empirical, not a loose end. On the dedicated MyMTS box, the `KioskService` + `BootReceiver` start the wall's foreground service on boot and hold it once the wall is up — but **getting the wall ACTIVITY to the foreground over the Google TV launcher at boot** is the gap. Two mechanisms were tried on this exact Google TV build and **empirically ruled out** — do **not** retry them:

- **HOME-launcher — RULED OUT.** Added `CATEGORY_HOME`+`DEFAULT` to `MainActivity` + `cmd package set-home-activity` (MyMTS held `android.app.role.HOME`). But Google TV's system home apps out-prioritise any third-party app at boot — `launcherx` (`android:priority=2`), the setup-wizard `setupwraith.RecoveryActivity` (priority 1), and `tv.settings` — and the boot home-launch follows **priority, not the HOME role**, so the wall lost the race (reboot came up on `launcherx`, 0 tiles). Disabling those system launchers to force a win **destabilised the box** (SystemUI/`system_server` restart, `pm` "Broken pipe", ADB offline → recovered by re-enabling them). That whole class of action (disabling system launchers / launcher surgery) is **off-limits on this build.**
- **Full-screen-intent — RULED OUT.** `USE_FULL_SCREEN_INTENT` + a HIGH-importance channel + `setFullScreenIntent(...)` posted from the `BootReceiver` path (the Android-sanctioned background→foreground mechanism; touches no launcher). Telemetry confirmed it **fired** (`canUseFullScreenIntent=true`, notification posted) — but on the **TV form factor** the system treats a full-screen-intent as a notification, **not** an auto-launch, so the wall didn't foreground. Reverted clean (code + app-op), redeployed the clean APK.

### Current accepted state
On power-on/reboot the box comes up on the Google TV launcher at 720p; **open MyMTS once → the wall runs LIVE and the kiosk holds it foregrounded.** This only matters on the **rare** reboot (a dedicated appliance on stable power), so the manual-launch cost is small. Good-enough for now.

### The future path — device-owner / lock-task (PLANNED, not reactive)
The robust "true kiosk home" mechanism: `adb shell dpm set-device-owner <pkg>/<DeviceAdminReceiver>` + `setLockTaskPackages` + lock-task — how commercial kiosks/signage do unattended boot-to-app.
**⚠️ Prerequisites + costs (why it's a planned project, not a quick try):**
- The device must be **UNPROVISIONED with NO Google account** for `set-device-owner` to be accepted → it needs a **factory reset**, then device-owner set BEFORE adding any account. That **wipes the debloat + both app installs + all config** → a full re-provision.
- Once device-owner, the app **cannot be uninstalled** — backing out requires **another factory reset** (rollback is expensive, not a `git revert`).
- `set-device-owner` is **documented to sometimes fail on TV hardware** even when done correctly — so a box could be reset, re-provisioned, attempt it, fail, and need re-provisioning again with nothing gained.
- Needs a `DeviceAdminReceiver` built into MyMTS first (code), then the reset → provision-as-device-owner → lock-task flow.

**When to do it:** a deliberate, budgeted effort — ideally on a **fresh/spare box**, or a planned reset day for `<LAN_IP>` where the re-provision time + risk-of-not-working are accepted up front. **NOT** a reactive "one more try."
**Reconsider when:** the operator wants true unattended boot-to-wall badly enough to budget a factory-reset + re-provision pass (and the device-admin code), accepting the expensive-rollback + may-fail-on-TV caveats.

## ~~Helper feeds API — SQLite cross-thread bug (latent since the TLS dual-listener)~~ — FIXED (feed-sources expansion, 2026-06-05)

**Fixed.** The `Depends()`-yielded sqlite connection in `feeds/api.py` and `channels/api.py` was opened and closed through two separate `run_in_threadpool` calls (FastAPI's handling of a sync `yield`-dependency), which could land on different anyio threadpool threads → `sqlite3.ProgrammingError: SQLite objects created in a thread can only be used in that same thread` → intermittent HTTP 500 on `/api/feed`. Replaced with `db.connection_scope(db_path)` opened **inside** the sync route body, so the whole connection lifecycle stays on the single threadpool thread that runs the route. Regression-guarded by two concurrency tests (`test_feed_endpoint_survives_concurrent_threaded_requests`, `test_channels_endpoint_survives_concurrent_threaded_requests`) that hammer the endpoints from 8 client threads. Fixed as part of the feed-sources expansion, since more sources stress the feed path.
**Remaining sub-item (separate, low priority):** revisit whether to collapse the dual-uvicorn-instance architecture in `__main__.py` (now that HTTP is gone, the second listener exists only as a leftover) to a single `uvicorn.run`. This is independent of the cross-thread bug — which is fixed regardless of listener count — and is pure tidy-up.
**Considered + deliberately deferred again (2026-06-06 pre-migration hygiene pass):** the entry point's `lifespan="off" if cfg.port else "on"` logic is load-bearing (it's what stops the pollers — now including the new ticker pollers — double-starting across the two listeners). Refactoring it the same day as a migration whose **prerequisite is a clean helper redeploy that must start those pollers** is the wrong risk/reward, and the dual-listener wiring isn't fully exercisable in the unit suite (it's asyncio.gather server wiring). The scaffolding is functionally harmless today (HTTPS serves; the second listener is internal-only since the compose dropped the HTTP port mapping). Tidy it in a calm `__main__.py` session, not under a migration deadline.
**Reconsider when:** the dual-listener tidy-up is convenient to fold into other `__main__.py` work; no functional driver.

# Usage feedback — 2026-06-04 (hands-on session)

Batch of feedback the operator surfaced after actually using the wall on `.182`. Logged here so nothing evaporates; **deliberately deferred from the current session** — these are next-roadmap items, not in-flight work. Caveats below preserved verbatim where flagged because they prevent re-litigating settled foundation decisions and set honest expectations on what's feasible vs. paywalled vs. a separate scoped effort.

## ~~A. Whole-wall D-pad navigation + menu overhaul~~ — DONE (2026-06-14)

**Done.** The whole-wall D-pad navigation shipped as a single pure focus model — `ui/nav/WallFocusModel.kt` + `WallFocus.kt`, **49 invariant tests** (`WallFocusModelTest`), zero runtime focus traps; focus moves across feed items, video cells, the ticker, and the menu (see `docs/findings/07-navigation-chapter.md`). The **menu overhaul** shipped alongside — the settings menu grouped into sections (Display & Fit / Layout & Feed / Sports) with focus-following scroll + the league picker (see the sports-selection-menu DONE entry above + CHANGELOG). News-expand made feed headlines **selectable** (focus → expand the safe plain-text summary, A1-respecting). The "next major chapter / clunky menu" framing is retired.
**Operator residual (not blocking):** the at-the-box remote D-pad *feel-test* is the operator's confirmation when next at the box (`docs/OPERATIONS.md` "navigation chapter feel-test") — the navigation graph itself is locked + tested.

## B. Feed UX — list view, live/offline sections, selectable items

**What:** Feed should be a **list, not a continuous individual-scroll** ("unintuitive and inefficient"). Add **sections** (e.g. by source or grouping). Make feed items selectable (ties into A — navigation).
**Why-not-now:** Current feed is a chronological river; restructuring + selection depends partly on the navigation overhaul (A).
**⚠️ CAVEAT (preserve — closed-door item):** "Selecting an article" must NOT mean opening/reading the full article *in-app*. In-app article reading was ruled out in `04-TECHNICAL-APPROACH.md §5` / foundation as a security+scope dead-end (the reader-pane cross-device-auth problem). "Select" can mean focus / expand the safe summary / mark — NOT a full in-app web reader. Revisiting that is a deliberate foundation-level decision, not a feature tweak.
**Reconsider when:** With the navigation chapter (A) — the list/sections part can also go in a "UX & config" push.

## ~~C. Layout / sizing configurability~~ — DONE (2026-06-14)

**Done.** Every named sub-item shipped, in `WallSettings` + the settings menu: grid **side** (`feedSide` Left/Right, with the focus model inverting LEFT/RIGHT to match), the grid **scales to its measured region** (the measured-area → cells refactor — it autofits the space beside the feed at any rows × cols), **global app sizing** (`UiScale` Compact/Default/Roomy + the panel-fit `Overscan` inset / Position offset / Fit-scale levers for the real TV), and **feed width + font** (`FeedWidth` / `FeedFontScale`). All D-pad-cyclable + persisted (`LineupStore`). See the panel-fit DONE entry below + `ARCHITECTURE.md`, CHANGELOG. *(The further generalization — free-form resize of all three panes — is the "Configurable / scalable panes" entry above, still open.)*

## ~~D. Ticker — alternate markets + curated sports scores/news~~ — DONE (ticker real-data chapter, 2026-06-05)

**Done.** The ticker now shows REAL data and alternates between a markets mode (Stooq indices/FX/gold + CoinGecko BTC/ETH, all keyless) and a sports mode (ESPN public scoreboard JSON for MLB/NFL/NBA/NHL, keyless), rotating on a calm timer (markets ~22 s, sports ~14 s) via `HelperTickerSource` behind the `TickerSource` interface. Honest labeling held: real entries drop the SAMPLE pill; symbols with no free keyless source (Brent, WTI, 10Y UST) stay sample; an unreachable helper falls back to honest sample (markets) / "scores unavailable" (sports), never frozen-live.
**Remaining (deferred sub-items):**
- **Per-team / per-league curation UI** — the chapter ships a sensible default league set (MLB/NFL/NBA/NHL) + the mechanism; a UI for the operator to pick leagues/teams is the follow-on. Until then, the league set is edited in `ticker/sports.py::DEFAULT_LEAGUES`.
- **Sample-only market symbols** — ~~Brent, WTI, 10Y UST remain honest SAMPLE~~ **RESOLVED 2026-06-11:** now live via Yahoo Finance (`BZ=F`/`CL=F`/`^TNX`) along with the rest of the markets set.

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

## F. Feed source quality — more reputable sources — SUBSTANTIALLY DONE (2026-06-14)

**Shipped (the expansion):** the feed grew from the original 4 to **13 reputable news sources** with a deliberately balanced spread — BBC World, Al Jazeera, Guardian World, NPR World, PBS NewsHour, Christian Science Monitor, CBS News, NBC News, Politico, Bloomberg Markets, The Dispatch, National Review, Reason (plus the 8 ESPN sports-news leagues). Seeded in `helper/.../feeds/seed.json`, parsed defensively as A1 plain text. The stale "current: 4 sources" line is retired.
**Still open (small remainder):** the specific **wire services** (AP / Reuters) aren't in the set yet, and the "research what `monitor-the-situation.com` uses as its source list" input task is unaddressed.
**Reconsider when:** any helper/seed work — add the wire services and reconcile against the MTS reference list if the operator wants closer editorial parity.

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

## ~~Markets ticker — Stooq anti-bot challenge from the NAS egress~~ — DONE (2026-06-11)

**Resolved** by swapping Stooq → Yahoo Finance v8 chart (NAS-reachable, keyless). The Stooq anti-bot challenge no longer matters — Stooq is no longer used. See the DONE item above + CHANGELOG (2026-06-11 live market data). Kept here for history: the NAS egress IP was bot-walled by Stooq's JS proof-of-work, which is why dev-Mac verification didn't catch it (the *NAS prober is the gate* lesson, also seen with WeatherNation).
**Reconsider when:** the operator wants real indices/FX/gold on the wall. Options: (a) a different **keyless** indices/FX/commodity source that tolerates the NAS IP (re-survey like the ticker chapter did, with ToS caveats); (b) a free-tier keyed source (crosses the helper-holds-a-secret line — handle per the .env discipline); (c) accept SAMPLE on those symbols. CoinGecko (crypto) is unaffected and stays real.

## Send-to-phone for richer article reading (QR pair) — closed-door-compatible

**What:** A future feature for the focused-feed-item SELECT path: alongside the current safe in-place expansion of the helper's plain-text summary, surface a small QR (and/or operator-pre-paired phone notification) that opens the article URL on the operator's phone. The full article is read on the phone's browser — a context where the operator's existing browser hygiene + the article's own platform already apply — not in MyMTS.
**Why this respects the closed door:** the in-app full-article web reading path is permanently closed (BUILD-PROMPT §4, lines 81/130/178). This entry is **not** that path: MyMTS never fetches the article HTML, never renders it, never proxies through the helper for the operator's session. The QR/notification is a *handoff to the phone*, the phone owns the read — same shape as "scan to open on phone" patterns in news apps. A1 / B4 hold because nothing about the article ever crosses into the TV or helper's render layer.
**Why-not-now:** Out of scope for the navigation chapter (chapter is whole-wall D-pad UX, not reading flow). Cross-device handoff also needs care: QR is the simple form (no auth, no pairing), notification-to-phone requires a one-time pairing flow which is a small but real surface. Decide which form (or both) at design time.
**Reconsider when:** Operator wants a richer reading flow than the in-place safe summary. Likely pairs with item B (feed UX list/sections) since "select to send to phone" is the natural next action verb once feed items are selectable.

## ~~Overall UI sizing — deferred (Stage 8 closeout)~~ — DONE 2026-06-07 (cashed in by the panel-fit chapter)

**DONE.** Cashed in exactly as predicted ("reconsider once on the real TV"): the panel-fit chapter added `WallSettings.UiScale` (Compact 0.80 / Default 1.0 / Roomy 1.15) — a single `LocalDensity` override in `WallScreen` scaling the WHOLE wall (ticker/feed/grid/labels/overlays) together — plus an `Overscan` safe-area inset (None/3/5/7%, default 5%) for panels that clip at the edges. D-pad-cyclable in Settings ("Display size" + "Overscan inset" rows), persisted in `LineupStore`. The per-piece feed-width/feed-font controls still tune within the scaled layout. See `docs/findings/17-panel-fit-and-agnostic-feed.md`, `ARCHITECTURE.md`, CHANGELOG.

### Original entry (preserved for history)

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

## H. Cross-platform profiles — ARCHITECTURE FORK, decide before building — M3U/PROFILE BACKEND FOUNDATION laid (Campaign 3 HALF 2, 2026-06-13)

**Update (Campaign 3 HALF 2, 2026-06-13): the backend FOUNDATION is laid — the prefs-sync architecture decision below is still open.** Shipped a helper **playlist/M3U** surface + a minimal **profile** abstraction: `GET /api/playlist.m3u` (built-in `default` = all live channels) and `GET /api/playlist/{name}.m3u` (an ordered channel subset), serving the resolved lineup to a VLC/Apple-TV client — the "multi-profile backend, VLC is one client" shape. Profiles = a built-in default + optional operator-defined named profiles from a JSON file (`PROFILES_FILE`, see `helper/profiles.example.json`), read once at startup (operator data, out of git). Honest (live-only), no-proxy (points at upstream `current_url`), LAN-only, stateless. See `ARCHITECTURE.md §23`, CHANGELOG (2026-06-13), `docs/findings/22-playlist-profiles.md`.
**Foundation vs. still-future:** *laid* = the channel-selection → playlist backend + per-client named lineups (different displays get different channel sets). **Still the open item-H decision (NOT presupposed):** per-client **server-side prefs state** (audio/caption/layout, not just channel choice), **identity**, and **cross-device sync** — i.e. device-local `LineupStore` vs. helper-hosted shared profile. The M3U endpoint is deliberately *stateless* (returns what's live now) so it doesn't prejudge that call. The **remote/public** web client stays a separate deferred-security item (don't conflate).

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

## Weather: gated nationals + central-IL local + NWS radar widget (2026-06-11)

**What:** three follow-ons from the weather-feed research (`docs/findings/20-weather-feed-research.md`):
1. **The Weather Channel (proper)** and **central-Illinois/Midwest local stations** (WMBD/WEEK/WHOI/WCIA/WAND…) are **login/auth-gated or YouTube-page-only** — no public keyless HLS. Out of scope under the **no-stored-credentials** posture; would need credentials the box must not hold.
2. **~~WeatherSpy + WeatherNation~~ — both ADDED 2026-06-24 (Weather category 2 → 4).** WeatherSpy added (free Rakuten/CloudFront FAST weather, US distribution, master→variant validated from the NAS). WeatherNation re-added after diagnosing its handshake failure: its Stirr CDN offers **only** a non-forward-secret RSA-kx AES-GCM cipher that Python's hardened default omits (curl/openssl complete it; httpx didn't) — so the `fetcher` now enables that cipher suite **without weakening verification** (CERT_REQUIRED + check_hostname stay on, SECLEVEL stays 2; FS is moot for public manifests). Both validated `live` from the NAS prober (the gate). The *NAS-prober-is-the-gate* lesson held — the fix was proven from the helper container, not the dev Mac.
3. **NWS / NOAA radar** is image-loops/data, **not a video stream** — could be a future *non-video* weather widget (radar tile / current-conditions panel), a different component from the HLS video tiles.

**Why-not-now:** (1) violates the keyless/no-credentials Trust Bar; (2) cosmetic — three nationals already cover it; (3) is a new widget type, not a channel — outside the video-tile model shipped today.
**Reconsider when:** (1) only if a station ever exposes a public keyless HLS (re-probe periodically); (2) on operator request; (3) when a non-video weather widget is scoped (pairs with the menu/widget overhaul) — the NWS public radar/forecast APIs are keyless and would fit a data widget, just not a video tile.

## News feed — genre groups + two-level source toggles (CHECKPOINTED 2026-06-11)

**What (Part E of the 6-change wall chapter):** categorize the feed's RSS sources into **genres** (US News / Global News / Business / Sports / Weather — the same naming as the channel-picker `ChannelCategory`) and let the operator enable/disable at **both** levels (the genre group AND each source within it) via check-box-style toggles in a settings "News" section. Two-level interaction: a genre off hides all its sources; an individual source toggles within an on-genre. Reconcile with the existing Sports-leagues pool (which already gates sports-news) so the two don't conflict. The feed stays the agnostic chronological river; toggles just filter contributors. Persist (LineupStore). Remote-navigable with the scroll-follows-focus + focus-restore discipline.
**Why-checkpointed:** A–D (quick wins) + F (sectioned channel picker) shipped + verified; E is the bigger taxonomy piece (a genre→source tree + a new overlay + the feed-filter wiring + sports-pool reconciliation) and was deliberately checkpointed rather than crammed under-verified at the end of a long session (the chapter's own rule — a clean subset beats six half-built changes).
**Reconsider when:** next session — the `ChannelCategory` taxonomy + the `SourceFilterOverlay` toggle-list pattern are the foundation; map the actual feed-source labels to genres, build the two-level overlay, wire `FeedListBuilder` to respect both levels.

## Persistent MyMTS stream in a Mercury (Discord-style) voice channel — unattended 24/7 wall, viewers pop in/out (NAS-hosted publisher) (2026-06-13)

**What:** Run a persistent, unattended MyMTS video stream that lives in a MercuryChat voice channel — a 24/7 "news wall" channel viewers can pop into and out of, hosted on the NAS. (MercuryChat = a friend's private Discord-inspired .NET 9 app; its media stack is a self-hosted LiveKit SFU. Its architecture doc — "MercuryChat Streaming Architecture — LiveKit, Voice & Screen Share," in Mercury's repo `Docs/`, last verified 2026-06-12 — is what makes the integration path below concrete.)

**Why it's viable (the integration path, per Mercury's architecture doc §10 Tier 3 + §6 + Appendix B):**
- Mercury treats LiveKit as a dumb media pipe. **Any separate process can publish into a room** by minting its own LiveKit JWT (shared API key/secret) with room name `channel-{channelGuid}` and a **unique identity**. Its `source: 'screen_share'` video track is **auto-rendered by every in-call client** (the `TrackSubscribed` handler doesn't care who published) — that's the persistent publisher.
- **The economics fit the pop-in/out pattern exactly:** Mercury's rooms run `adaptiveStream: true` + `dynacast: true`, so a stream **nobody is watching costs ~nothing** (the SFU pauses unsubscribed layers) and per-viewer quality auto-scales to the rendered element size. A usually-unwatched 24/7 wall is cheap and only spins up quality when someone actually joins.

**Likely build shape (when/if pursued):**
- A **headless publisher process on the NAS**: render the **MyMTS web client** (the LAN web wall — now at feature parity as of Campaign 3 HALF 1: per-sport cards, news, ticker, settings) in **headless Chromium (Playwright/Puppeteer)**, capture the rendered wall, and **publish it as a LiveKit screen-share track** into a Mercury room under a **dedicated "MyMTS" bot identity** + its own minted JWT.
- This **REUSES the existing web client as the render surface** (no new rendering engine) — the web-parity work already shipped is what makes it tractable: there is now a single browser-renderable URL (`/app/`) that shows the whole wall, which is exactly the capture source a headless publisher needs. The publisher is a thin capture-and-publish process, not a reimplementation of the wall.

**⚠️ Honest sharp edges (from Mercury's doc — recorded so they're not surprises):**
- **No LIVE badge / DB presence for a bot publisher** unless it ALSO creates a Mercury `VoiceSession` + heartbeats every <30 s (needs a Mercury user account + cookie auth, not just a LiveKit JWT). "Appears as a watchable stream" = free; "shows a LIVE badge in the sidebar like a user" = needs the heartbeat machinery.
- **A dedicated identity is mandatory.** Mercury identity == user GUID, and a duplicate identity **kicks** the prior connection (`DUPLICATE_IDENTITY`). The publisher MUST use its own unique bot identity, never a real user's, or it boots that user from voice.
- **All Mercury tokens grant full publish rights; there is no read-only token today; identity is hardcoded to the user GUID server-side.** A publisher (mint-your-own-JWT) is the easy direction; a passive viewer component is the awkward one (not what this needs).
- **Networking:** LiveKit media is UDP **50000–60000**, currently **tailnet-only** (`node_ip` pinned to the host's Tailscale address). A NAS-hosted publisher must reach the LiveKit host over the tailnet — the NAS would need to be a tailnet member / have a route.
- **Security context (Mercury's, not ours):** the LiveKit JWTs are signed with a static shared API key/secret (the doc notes the dev secret leaked into Mercury's git history; rotation is on *their* backlog), and the SFU is not E2E-encrypted (screen shares are visible to the SFU host by design). Our publisher would hold that shared secret — treat it as a real secret (gitignored, never logged, same `.env` discipline as the rest of the helper) and coordinate with Mercury's owner on a proper key (ideally a rotated/non-leaked one, possibly a dedicated key for the bot).
- **Audio:** the wall is largely visual — decide whether to publish `screen_share_audio` (the news-video audio) or run video-only. (Mercury's mic pipeline / noise-gate is irrelevant; this is a screen-share-source publish, not a mic.)

**Distinct from the M3U/VLC path — don't conflate:** Campaign 3 HALF 2's `/api/playlist.m3u` endpoint is a *channel-list playlist a player pulls* (VLC on Apple TV). THIS item is a *single composited wall video pushed into a live chat room*. Different outputs, both "MyMTS-as-a-stream" — keep them separate.

**Why-not-now:** It's an idea bank, not in-flight work — the operator is returning to other tracks. The enabling prerequisite (web-client parity) now exists (HALF 1), but this lands naturally AFTER the web client is solid (done) and ideally after Campaign 4 (so the wall looks polished on stream), and it requires coordination with Mercury's owner. It's its own focused campaign (headless-capture + LiveKit publish + a keep-alive/supervisor on the NAS so it's truly persistent + auto-restarts), not a side-quest.
**Reconsider when:** the operator prioritizes it as its own chapter AND Mercury's owner is in the loop for (a) a LiveKit API key for the bot — ideally a rotated/non-leaked or bot-dedicated one, (b) the target room / channel GUID, and (c) tailnet access for the NAS. Ideally sequenced after Campaign 4, once the wall is polished enough to look good on stream.
**Status:** IDEA / backlog. Not started. The pertinent prerequisite (web parity) now exists.
