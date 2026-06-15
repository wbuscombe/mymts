# Changelog

All notable changes to MyMTS will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## feat(web): draggable feed↔video divider in the web client (persisted ratio) (2026-06-15)

Web-client-only. A draggable vertical divider now sits between the news-feed pane and the video grid, so the operator can resize the split directly on the wall instead of hunting in Settings. Reuses the existing `feedPct` / `--feed-pct` / prefs mechanism — no new storage key, no new layout model.

- **Pointer + touch + keyboard.** Drag the divider (mouse or touch — `setPointerCapture`, `touch-action:none`) to set the ratio; the live `feedPct` is computed from the pointer position over the wall (`feedPctFromPointer`). The divider is focusable (`role="separator"`, `tabindex="0"`); **←/→ nudge** ±2% when focused. The ratio is clamped so neither pane collapses (`clampFeedPct`, 18–58%) and **persists** on drag-end / keypress via the existing `savePrefs`.
- **Stays in lockstep with Settings.** The "Feed width" slider was re-ranged to match the divider's 18–58% clamp; dragging updates the slider and vice-versa (single source of truth: `prefs.feedPct`).
- **Untouched by design:** the bottom ticker, and the video grid's existing column behavior **including the 3-column cap** (native parity, documented-not-lifted).
- Pure helpers `clampFeedPct` (bounds + NaN→default) and `feedPctFromPointer` (ratio across wall sizes, edge-clamped) are unit-tested. No CSP / no-proxy / A1 / vendored-hls.js change; no native app, no PIA.

## fix(app): lower the ticker crawl speed range for a genuinely-slow option (2026-06-15)

The crawl ran too fast and the speed slider felt like it did little. **Finding:** the crawl was NOT unwired — the chain `WallScreen → TickerStrip → CrawlTicker → crawlPxPerSec(scrollPct)` is intact and the driver re-keys on `scrollPct`, so the setting *does* drive it live. The real problem was the **range**: a 40% floor × a 32 dp/s base made the whole band fast (≈26–128 px/s at 1080p), so the slider only spanned "fast → faster" and even the floor was too fast for 10 ft.

Re-ranged so the operator can dial it in-app (no redeploy to chase a speed):
- **Floor 40% → 10%** (`TICKER_SPEED_MIN_PCT`) and **step 20% → 10%** — a genuinely slow, calm slow-end + finer control.
- **Crawl base 32 → 24 dp/s** via a new crawl-only `CRAWL_BASE_DP_PER_SEC` (the flip's per-page reveal keeps its 32 dp/s `BASE_SCROLL_VELOCITY` — untouched).
- **Scroll default 100% → 50%** (`TICKER_SPEED_DEFAULT_PCT`) — a calm mid-speed, not the old too-fast value. (Existing installs keep their saved value; this only sets the fresh-install default.)

Resulting range (≈1080p): floor ≈ 4.8 px/s (slow + readable) → default ≈ 24 px/s → max ≈ 96 px/s. Pure `crawlPxPerSec` mapping unit-tested (monotonic, slow floor, the setting changes the speed). `WallSettings` round-trip still passes. The flip dwell (`tickerFlipPct`), panel-fit, video, back-nav, and resync are untouched; the web client's range is unchanged (native-only pass).

## fix(app): smooth playback + ticker + back-nav, and a minimal resync (native) (2026-06-15)

Four related native fixes unified by one principle: **real-time fidelity over catch-up** (drop, don't accumulate-and-sprint).

- **(C) Ticker crawl made cheap — the prime suspect for the new video lag.** The just-shipped crawl drove motion with `horizontalScroll` + `animateScrollTo`, re-laying-out a wide two-copy Row **every frame** → starved video decode on the S905Y4 (video degraded right when the crawl went live). Now a single `graphicsLayer { translationX }` (compositor-only, no re-layout) driven by a `withFrameNanos` loop whose **per-frame delta is clamped** (`CRAWL_MAX_FRAME_DELTA_MS=33`) so a stall **drops** the missed motion instead of sprinting to catch up. Base speed unchanged (the cost was the per-frame layout, not the speed). Pure helpers (`crawlPxPerSec`/`crawlAdvancePx`) unit-tested.
- **(B) Live video held at the live edge.** Added `MediaItem.LiveConfiguration` (`TARGET_LIVE_OFFSET_MS=4000`, imperceptible `0.97–1.03` speed window — micro-correction only) + tighter `LoadControl` buffers (`max 3000ms`) so latency can't pile up, plus a **live-edge watchdog** on the existing 2s tick: when `currentLiveOffset` exceeds `MAX_LIVE_DRIFT_MS=8000`, `seekToDefaultPosition()` **jumps to live and drops the stale backlog** rather than playing through it. New pure predicate `shouldSeekToLive` unit-tested. Honest constraint: these prioritize currency, not the hardware ceiling — a too-busy grid still needs fewer tiles.
- **(A) Single coherent back-stack — BACK can never exit to the launcher from a menu.** The wall delegated overlay-BACK to each overlay's own (focus-dependent) key handler, with the only `BackHandler` gated on `menu.isOpen`; when a tile-opened overlay didn't hold focus, BACK fell through to the Activity → home. Replaced with ONE focus-independent `BackHandler` enabled whenever any overlay is open, driven by a pure `menuBackOutcome` pop (`picker→controls→dismiss→close→pass`), unit-tested. Flow-smoothing: picking a channel now completes straight to the **wall** (one less hop); BACK from the picker still steps up to controls.
- **(D) Quick resync-to-live (single + all), zero persistent wall chrome.** Reuses (B)'s `seekToLive` primitive (or reconnect if a tile is actually dead) via `StreamPlayerManager.resync`/`resyncAll`. **Single:** long-press SELECT on a focused tile (`LONG_PRESS_RESYNC_MS=500`); short press still opens its controls. **All:** a "Resync all feeds" row in the side menu. A brief self-dismissing "Resyncing…" flash is the only chrome.

App unit suite green (incl. the WallSettings round-trip); release APK builds clean. The B/C constants are exposed for at-the-box feel-test/tuning. Not yet deployed.

## fix(app): channel picker takes + holds D-pad focus on open (native) (2026-06-15)

**Bug:** opening the sectioned channel picker (the `ChannelCategory` list — Sports / US News / Global News / Business / Weather) lost the D-pad cursor entirely — focus was null, the D-pad did nothing, only Back escaped — so you couldn't change a feed on the fly. Both entry points (selecting a video tile on the wall, and the menu → slot controls → "change channel") broke identically.

**Root cause (cause #2 — request-focus on an uncomposed target):** the picker requested focus from a **per-row** `LaunchedEffect` inside the `LazyColumn` (`if (isInitial) requestFocus()`), targeting the **slot's current channel**. When that channel sits in an off-screen section (e.g. Weather, the last one), `LazyColumn` never composes its row → the `LaunchedEffect` never runs → `requestFocus()` never fires → null focus. Nothing scrolled the target into view first. (Both entry points funnel through the one `ChannelPickerOverlay`, so both broke regardless of caller.)

**Fix (reuses `SourceFilterOverlay`'s proven list-level pattern):** hoist a single `FocusRequester` to the list level; on open, **`scrollToItem` the target into view, wait until its row is actually laid out (`snapshotFlow` on `visibleItemsInfo`), then `requestFocus()`** — so the request can't no-op on an uncomposed row. The flat-index computation is a new pure function `initialFocusIndex(sections, focusSlug)` (counts each section header + its channels), unit-tested incl. the off-screen-last-section case that broke. Section headers stay non-focusable (D-pad flows across sections); the existing focus-following scroll + the caller's exit-restore are unchanged. No `WallSettings`/panel-fit touched.

Tests: 3 new pure cases (`ChannelPickerListTest`) pin the index logic + that `channelToFocus`'s target always resolves to a real row; full app unit suite green (incl. the WallSettings round-trip); release APK builds clean. The on-device focus *landing* is integration-level — the operator's at-the-box feel-test (both entry points). **Not yet deployed** — ships with the next `.92` build (which also lights up the committed ticker-motion crawl).

## docs: BACKLOG accuracy pass + recorded candidate charter checks (2026-06-14)

Docs-only hygiene, closing the two doc-vs-reality gaps the read-only backlog audit flagged. No code, no deploy.

- **BACKLOG pruning (verified-before-strike).** Marked shipped-but-still-open entries DONE against confirmed repo evidence: **A. Whole-wall D-pad navigation + menu overhaul** (`WallFocusModel.kt`/`WallFocus.kt`, 49 focus tests, `docs/findings/07`; menu overhaul shipped) — at-the-box remote feel-test noted as the operator's residual; **C. Layout/sizing configurability** (all sub-items in `WallSettings`: `feedSide`/measured-area grid/`UiScale`+panel-fit/`FeedWidth`+`FeedFontScale`); **In-app menu/settings (Stage 5)** (the `MenuOverlay`/`SettingsOverlay` surface). Two entries **split** rather than fully struck: **Configurable/scalable panes** (feed-width + global-scale presets shipped; free-form pane-proportion resize still open) and **F. Feed source quality** (expanded 4 → 13 reputable balanced sources; AP/Reuters wire services + the editorial-reference-site source-list research kept as the open remainder). Left genuinely-open: the sports-menu `/api/ticker/leagues` refinement, Item B, and all idea-tier/deferred/decided entries — untouched.
- **Charter candidate checks recorded.** Added a "Candidate future checks" section to `MAINTENANCE-CHARTER.md` enumerating three `[ENFORCED]`-eligible greps: **tilde-path** (prioritized — `~/…` author-machine paths the abs-path pattern misses), **dead-link checker** (promotes the loose "dead links" gesture), and **real-domain backstop**. The tilde-path and real-domain candidates had lived only in working discussion — writing them down is the charter's own feedback-loop discipline. Recording only; none wired into `check.mjs`/CI this pass. docs-hygiene gate green.

## style(web): enlarge + center the per-tile refresh button under the status on dead tiles (2026-06-14)

Operator polish after using the LAN web wall. On a non-playable tile (the honest "Browser can't play this source — on the TV wall" state), the per-tile **↻** reconnect button was a small bottom-right-corner control — easy to miss. It's now **large (46 px circular, 30 px glyph) and centered directly under the status text**, so the stack reads ○ → channel name → status → ↻ — an obvious "tap to retry" target legible at 10 ft. Purely position + size (`web/js/app.mjs::showDeadVideo` moves the button into the centered `.tile-state` column; `web/styles.css` `.tile-refresh` goes from corner-absolute to in-flow centered); the reconnect **behavior** and hover/focus affordance are unchanged. Scope-guarded: the per-tile ↻ renders **only** on the no-video dead tiles, so it never covers playing video (playing/reconnecting tiles have no per-tile button — the whole-wall ↻ stays in the header). The given-up tile's ring glyph (○) was already static (not a spinner), so the honest-state glyph was left as-is. Web suite 60/60; browser-verified (centered in cell, ≥44 px, under the status); gallery refreshed. Web-static only — CSP / no-proxy / A1 / vendored-hls.js posture untouched.

## feat(tools): bulletproof scrcpy demo-recording capability (`make record-demo`) (2026-06-14)

A standing repo capability to record a high-quality demo of the **live wall** — native framebuffer capture via [scrcpy](https://github.com/Genymobile/scrcpy) over the existing adb connection (free, no camera, no new hardware, no quality loss). scrcpy **READS the screen only** — no deploy, no app change.

- **`tools/capture/record-demo.sh`** — one command (`make record-demo`) records the `.92` wall to `tools/capture/output/mymts-demo-<ts>.mkv`. The SCRIPT is bulletproof (fail-fast preflight, actionable errors); the walkthrough is the operator's (drive with the remote or the scrcpy window). Preflight guards: **scrcpy installed** (→ exact per-platform install command), **version ≥ 2.0** (→ upgrade guidance, so it never passes 2.x flags to an old scrcpy), **adb + device connected** (one foreground device-specific reconnect, then actionable failure), and **hard-targets the serial** via scrcpy `-s` from the gitignored `MYMTS_DEPLOY_DEVICE` (the wrong-box guard refuses the `192.0.2.*` placeholder — never falls through to `.182`/`.158`). Conforms to AGENTS.md: **never `kill-server`**, never backgrounds adb. High-quality defaults (H.264/16M/60fps, MKV so a Ctrl-C stop can't corrupt the file; `--h265`/`--mp4`/`--native`/`--audio` toggles). After each run it prints the **VERIFY reminder** — scrcpy captures the framebuffer, so a DRM/protected tile *could* record black; the free news streams are likely fine but the operator must check, not assume.
- **`tools/capture/README.md`** — the shot list (a tight ~60–90s flow: wall → settings → channel picker → a showcase beat), drive-via-remote-or-computer, the Wi-Fi-vs-USB transport caveat (`RECORD_VIDEO_BUFFER` to smooth jitter; USB for rock-solid), and the verify-the-output step.
- **`tools/capture/test-preflight.sh`** (`make test-capture`) — stubs scrcpy/adb to prove every guard fires + the happy path reaches the verify reminder, and lints for forbidden adb patterns (no `kill-server`, no backgrounded adb, `-s` hard-target present). Both scripts are **shellcheck-clean**. Recordings are **gitignored** (`tools/capture/output/`) — no binaries committed.
- A thin root **`Makefile`** provides `make record-demo` / `make test-capture` (the "lazy one-command" entry points).

Repo tooling only — no deploy, no app/`.92` change (scrcpy reads the screen). PIA untouched.

## docs: collaborator-readiness audit — CONTRIBUTING currency + complete config scaffolding (2026-06-14)

A narrow, audit-first doc-currency pass scoped to **collaborator-readiness** (a new contributor is joining), not the full professionalization sweep. Audited four areas; fixed the two that were PARTIAL. No deploy, no code-behavior change.

- **Feature doc-currency — PASS (no fix needed).** The six recently-shipped features (news-story expand, web video auto-recovery + manual refresh, the cross-platform ticker-motion setting, the M3U/playlist endpoint + profiles, the per-sport web cards, the schema guard) are all documented accurately in README / ARCHITECTURE / CHANGELOG, including the **honest** "web-shipped / Android-ticker-rides-the-next-.92-deploy" caveat (verified against the actual code, not the docs' self-description).
- **Onboarding currency — fixed `CONTRIBUTING.md`.** It was stale: still labelled "Stage 0 — skeleton, build/test commands land when the toolchain does" (the toolchain landed long ago). Updated to **active**; added the real local test commands (`uv run pytest` · `./gradlew :app:testReleaseUnitTest` · `node --test web/test/*.test.mjs` — the same suites CI runs); added the **operational-guardrail pointers** a contributor must know (AGENTS.md invariants, the MAINTENANCE-CHARTER docs-hygiene gate, the phase-end checklist); added the **web client** (served at `/app`, how it's tested) and commit-scoping guidance. ONBOARDING.md + the `docs/onboarding/` prompts + the README quickstart were already accurate (confirmed).
- **Config scaffolding — completed the templates.** `helper/.env.example` was missing **9** env vars `config.py` actually reads (`DATA_DIR`, the feed/channel/markets/sports poll intervals, `FEED_RETENTION_DAYS`, and the Stage-6 TLS trio `HTTPS_PORT`/`SSL_KEYFILE`/`SSL_CERTFILE`) — all OPTIONAL with safe defaults (the helper still boots from a fresh clone with no `.env`), now documented so every knob is discoverable. Added **`local.properties.example`** (the optional `MYMTS_HELPER_BASE_URL` helper-host override; clarifies `sdk.dir` is auto-managed by Android Studio and signing lives in `keystore.properties`). Added `MYMTS_ARCHIVE_DIR` to `scripts/deploy.local.env.example`. And a **new guard test** (`test_config_env_example.py`) asserts `.env.example` stays complete vs `config.py` — turning this audit-time catch into an enforced check so it can't drift again.
- **Demo-boots-clean — PASS (verified live).** From a clean state the README quickstart (`cd helper && PHANTOM_MODE=1 PORT=8091 uv run python -m mymts_helper`) boots in ~1s with `phantom:true`, no secrets/NAS; `/health` + `/app/` respond; 21 channels; the vendored `hls.min.js` is served (offline-capable); the recent web features (news-expand, video-recovery refresh, ticker-motion) are live and introduced no fresh-clone dependency; `/api/playlist.m3u` returns valid M3U.

Audit ran as a parallel multi-agent fan-out (the four areas); each finding verified against the actual code before fixing. docs-hygiene gate green. No deploy this pass (the helper redeploy that lights up the committed web features live is the next, separate step).

## feat: Browser Client Refocus — web video auto-recovery · ticker-motion parity · grid-cap + native-vs-web rationale (2026-06-14)

A four-part pass making the LAN web client an honest, self-healing peer of the TV wall — and documenting *why* the two clients differ where they do.

- **Web video auto-recovery (the lead).** Dead browser tiles used to sit dead until a manual page reload — unacceptable for an always-on wall. **The crux:** `render.mjs::classifyVideoFailure` (pure, unit-tested) separates a **transient** failure (network blip / decode hiccup / a new stall-watchdog firing → worth a fresh attempt) from a **genuinely-unplayable** one (DRM / codec / no-browser-HLS / unsupported source → a browser can never play it). `videoRetryDecision` retries the transient class on an exponential backoff (2→4→8→16s, cap 30s) up to a bounded `VIDEO_MAX_RETRIES=4`, then falls to the honest terminal state with reason `exhausted` — **never an infinite retry on a hopeless stream**; an unplayable failure is marked immediately (reason-specific honest copy, e.g. "Protected stream (DRM) — on the TV wall"), never retried. `video.mjs` emits a richer signal (hls fatal `{type, details}`, native `MediaError.code`, a stall watchdog) and tears down leak-safe; `app.mjs` drives per-cell retry behind a **generation guard** (a late event from a superseded handle can't resurrect a torn-down tile or cancel a pending reconnect). **Manual refresh:** a per-tile ↻ on every dead tile + a whole-wall ↻ in the header (both keyboard-accessible). 5 new pure-logic tests pin the classification + the bounded backoff; browser-smoke verified (no JS faults; a tile correctly reaches the honest dead state with a working ↻).
- **Ticker motion — one cross-platform setting.** Both motions now exist on **both** clients: the web wall's continuous **crawl** and the native wall's paged **flip**. Only the per-platform **default** differs (web = crawl, native = flip), preserving each wall's established feel; the operator can switch either. **Web ships** (`tickerMotion` view pref through the normalize/serialize round-trip + a flip render path + a Settings select; round-trip + `tickerMotionOption`/`tickerFlipDwellMs` unit-tested; toggling verified live + persisted). **Native is built + unit-tested but NOT deployed** — a `TickerMotion` enum (default Flip) on `WallSettings`, persisted by `LineupStore`, a `cycleTickerMotion` + Settings row, and a `CrawlTicker` Compose path (seamless two-copy marquee, pure `crawlDurationMs`); it rides the next on-device (`.92`) release. `:app:testDebugUnitTest` green.
- **Grid 3-column cap — diagnosed + documented (not lifted).** The web grid's rows × cols, each 1–3 (up to 3×3 = 9), is **deliberate native parity** (`GRID_DIM_MAX`, the same clamp as native `WallSettings`), **not** a feed-width or CSS/technical limit (the CSS grid would render more fine). Raising it on web alone would break the "same wall on both screens" contract → documented as a constraint (code comment + a settings note), not widened.
- **ARCHITECTURE.md — "Native vs web: why the TV is the full-fidelity client".** ExoPlayer plays what a browser can't (mixed content / CORS / codec / DRM / autoplay); the dead web tiles **are** the evidence of that boundary, and the helper never proxies video to erase it. Ties the auto-recovery, the grid-cap parity, and the ticker-motion parity into one rationale.

Web stays same-origin / CSP-locked / no-proxy / no-CDN / hls.js-vendored-pinned. No deploy this pass (the native ticker change rides the next `.92`). PIA untouched; nothing on `.92`/`.182`/`.158`. <!-- docs-hygiene:allow — the .92/.182/.158 here are the standing "do not touch" constraint, not a topology disclosure -->

## feat(ci): Maintenance Charter — enforced docs-hygiene gate + phase-end ritual checklist (2026-06-14)

A continuous-quality **platform**, built so caught-after-the-fact problems become can't-slip-in-again checks. Every check traces to a real gap our audits caught. Three layers:
- **ENFORCED — `docs-hygiene` CI gate** (`tools/docs-hygiene/check.mjs`, **blocking** in `ci.yml`): greps the public docs (markdown + `.phantom.yml`, excluding gitignored ops-local) for topology leakage (full IPv4 / MAC / absolute home paths) and personal-config (location-qualified box instances, possessive-deployment framing, named-box instances, room-as-deployment) and **fails the build** on a non-allowlisted match. Critically, it has a working **allowlist** (without which it would crying-wolf on the deliberate keeps and get disabled): a global `allowlist.txt` (the `.182`/`.158`/`.92` last-octet box aliases, loopback/emulator IPs — each with a reason) plus an inline `docs-hygiene:allow` marker for one-off legit lines (e.g. a CHANGELOG entry that quotes a removed phrase to document its removal). Tuned to **zero false positives** on the current clean tree (59 docs scanned), and **regression-guarded by its own self-test** (`check.test.mjs`: planted leaks fail, clean/allowlisted/precise-keeps pass — "Onn 4K box" and "anyone in the room" are NOT flagged).
- **RITUAL — `docs/PHASE-END-CHECKLIST.md`**: the judgment checks a grep can't make, run when closing a phase — regenerate screenshots, docs-vs-reality, write-time audience-aware-docs, destructive-ops-have-explicit-guards+tests (traces to the seeder prune), fail-safe-contracts-exhaustively-tested (traces to `load_profiles`/the `.strip()` gap), deploy-conformance, and the bank step. Each item is a concrete action + how to verify it.
- **`MAINTENANCE-CHARTER.md`** (repo root): the living index — defines both layers, the **why-both** (the background-adb lesson: a rule a human must remember is weaker than a check a machine enforces), the **check registry** (each tagged `[ENFORCED]`/`[RITUAL]`, traced to its finding), and the **growth + RITUAL→ENFORCED promotion discipline** so it expands as future audits find new gap classes. Referenced from the README + AGENTS.md.

MyMTS is the reference implementation; the pattern is intended to promote to a workspace standard once proven. CI green with the new blocking gate.

## docs: generalize personal-config references in public docs (2026-06-13)

Audience-aware-docs pass for the **personal-config tier** — environmental/deployment specificity (room/location framing, the operator's specific box instance) that isn't sensitive (no IP/MAC/hostname/secret — those were already scrubbed to `<LAN_IP>`/`<MAC>` placeholders) but doesn't belong in enterprise-grade public docs. **Forward-fix only — NO history rewrite** (the operator rules on that separately, after the audit). Generalized, didn't gut — each reference now describes the project capability, not the operator's instance:
- **Screenshot gallery / device-shot spec** (README, `docs/screenshots/README.md` + `device/README.md`, the placeholder images + `make-placeholders.mjs`): "the office Onn" / "office Onn 4K driving a real TV" / "running ambient in the room" / "running on my wall" → "a dedicated Android TV display" / "running as an ambient wall display". Renamed `office-in-situ.png` → `in-situ.png` (the filename encoded a room) and updated every reference + the regenerated placeholder caption. <!-- docs-hygiene:allow — this entry quotes the removed phrases to document the cleanup -->
- **Historical records:** the named box instance "Office ONN Box - MyMTS" → "the dedicated MyMTS box" (CHANGELOG migration entry + BACKLOG decision note); the "upstairs session" feedback labels → "hands-on session" (BACKLOG + findings 07–10); "the WyzeGrid office box" → "the other (WyzeGrid) box" (findings 17). <!-- docs-hygiene:allow — quotes the removed phrases to document the cleanup -->
- **Kept (generic, correct):** "Onn 4K box" as the hardware *platform* the project targets; "anyone in the room" as the ambient-audience *use-case* (foundation docs); the WyzeGrid coexistence *facts* (operational context, not a location/instance) and soak run-directory artifact names (historical identifiers).

Audit + the git-history footprint + a history-rewrite recommendation were delivered to the operator for a separate decision (the personal-config footprint in history is small and non-sensitive — forward-fix is the recommended sufficient measure). The `.182`/`.158`/`.92` last-octet box aliases (AGENTS.md governance + CHANGELOG history) are flagged as sensitive-adjacent for the operator's awareness — left in place this pass (load-bearing in the governance rules; prior-scrub domain).

## feat(web): news-story expand + richer screenshot gallery (2026-06-13, Campaign 4.1)

- **News-story expand (new feature).** Selecting a feed headline (click, or Enter/Space on the focused row — keyboard/remote-accessible) now **expands** it into a detail modal showing the item's OWN fields — source, time, title, summary — as **inert plain text** (`textContent`, never `innerHTML`), plus a *Read at source ↗* **link-out**. A1 closed door held: MyMTS shows the feed's own summary and hands off to the browser for the article; it never fetches or renders the article HTML, and the link is gated to `http(s)` only (`safeHttpLink` rejects `javascript:`/`data:` — no href-XSS). Esc / ✕ / scrim close, with focus restored to the originating headline. CSP/same-origin/no-proxy posture unchanged (no new fetch, no CDN). Pure logic (`feedDetailModel`, `safeHttpLink`) is unit-tested (`node --test`, 52 green incl. the link-gating + escaped-field tests).
- **Richer capture gallery.** The Playwright pipeline now also captures the news-expand states — `feed-story-highlighted.png` (the selection highlight) and `feed-story-expanded.png` (the detail view) — alongside the refreshed wall / three ticker modes (markets / team-sports / news) / settings / channel-picker shots. Deterministic framing preserved; still demo/phantom-only (no secrets/NAS) and `workflow_dispatch` artifact (no auto-commit-on-push).
- **Refreshed device-shot spec.** `docs/screenshots/device/README.md` now spells out the **live-data** states the demo can't serve — the bespoke per-sport cards (PGA/UFC/Tennis/F1, live ESPN), real market quotes, real channels playing — as the operator's camera/screencap hero shots (with the per-sport breakout filename option).

Phase-end gallery refresh per the new workspace standard (professionalize.md §5/§6 — regenerate when user-visible UI changed). Helper/web/tooling only — rides the next helper redeploy. Operator's manual step: the live-data device hero shots per the refreshed spec.

## fix: remediate the 3 P3 findings from the adversarial re-review — F1 + F2 + F3 (2026-06-13)

The new-code re-review (`docs/adversarial-review-2026-06-new-code.md`) came back clean (0 P0/P1/P2) with three P3 defensive-completeness items; all three fixed, each TDD (failing test first):
- **F1 — profile loader fails closed on `RecursionError`.** `load_profiles` caught `(OSError, ValueError)` but not `RecursionError` (a `RuntimeError` subclass a pathologically-deep profiles file raises during parse) → boot crash, violating the loader's "always boots" contract. Broadened to `(OSError, ValueError, RecursionError)`.
- **F2 — seeder rejects whitespace-only URLs.** The URL check used bare truthiness, so `"   "` passed and could slip into the prune keep-set, weakening the empty/all-invalid guard. Now uses `url.strip()` (validation + keep-set) so a whitespace-only seed correctly triggers the no-wipe guard.
- **F3 — conservative URL-match normalization in the prune.** The reconcile matched DB↔seed sources by exact string, so a trailing-slash / scheme-or-host-case difference orphaned-then-pruned a still-wanted source. `delete_sources_not_in` now compares via `_match_key` (strip whitespace; lowercase scheme + host; collapse a single trailing path slash) — and ONLY those RFC-safe rules: path content/case, query, fragment, port, userinfo, and http-vs-https are preserved exactly, so genuinely-distinct URLs never merge (a missed match merely re-fetches a cache; a wrong match would drop a real source). The stored/served URL is never rewritten — normalization is for comparison only.

5 new tests (incl. the F3 guardrail test proving distinct URLs stay distinct); full helper suite green. Helper-only — rides the next helper redeploy. The re-review doc's triage is updated to FIXED.

## fix(feeds): seeder prunes sources no longer in the seed — completes the NBC English fix (2026-06-13)

Found while verifying the NBC fix on the live helper: NBC was *still* serving Spanish after the URL swap, because the feed seeder was **additive-only** (upsert-by-URL, never prunes). Changing NBC's seed URL (`/public/news` → `/public/us-news`) added the English source but left the **old Spanish-serving `/public/news` source orphaned in the persistent DB**, still polled — so the feed carried both. **Fix:** the seeder now **reconciles** — seed.json is the source of truth for the feed list (there is no add-source API), so any DB source whose URL isn't in the seed is pruned (its `feed_items` cascade away), with a hard guard against pruning when the seed yields no valid URLs (a broken/empty seed must never blank the wall). Regression-guarded by two tests (repointed-URL prune + cascade; empty-seed no-wipe). On redeploy the orphaned NBC source is pruned on boot and the feed is English-only.

## fix(deploy): write MYMTS_HELPER_REMOTE_PATH into the NAS .env (helper was crash-looping on a missing TLS cert) (2026-06-13)

Caught during a live helper redeploy. The compose bind-mounts the TLS certs and the web client via `${MYMTS_HELPER_REMOTE_PATH:-/srv/mymts-helper}/_secrets` (and `/_web`), but `deploy-helper.sh` wrote a `.env` that **omitted** `MYMTS_HELPER_REMOTE_PATH`, and that var isn't exported into the remote `docker compose` shell over ssh — so the mounts silently fell back to the `/srv/mymts-helper` **default**, docker auto-created an empty `_secrets` there, and uvicorn crash-looped on `load_cert_chain: FileNotFoundError`. (The deploy's auto-rollback hit the same empty cert dir, so it couldn't recover either.) **Fix:** the script now writes `MYMTS_HELPER_REMOTE_PATH` into the `.env` so the bind-mounts resolve to the real deploy dir. Diagnosed via `docker inspect` (mount source was the wrong `/srv/...` path) + the container logs; the helper was restored immediately by writing the var into `.env` and `up -d`. Deploy-script-only change; no helper code touched. PIA/VPN never touched.

## fix: NBC news source now uses the English feed (was serving Spanish) (2026-06-13)

The "NBC News" feed source was returning Spanish-language stories. **Diagnosed first:** the configured URL `https://feeds.nbcnews.com/nbcnews/public/news` (NBC's generic *top-stories* aggregator) serves a **mixed** feed — English NBC headlines interleaved with **Telemundo** Spanish-language content (World Cup "Vive el Mundial" items) — despite a misleading `<language>en-US</language>` tag (so the feed-level language signal is unreliable here). Not a parsing bug; a wrong/over-broad source URL.
- **Fix (source-level, not a fragile filter):** swapped the NBC source to the scoped English topic feed **`https://feeds.nbcnews.com/nbcnews/public/us-news`** ("NBC News U.S. News"), keeping NBC as the general US-broadcast-news slot (parallel to CBS News). Verified English **before** committing (direct fetch: 0 Spanish-marker titles) **and** end-to-end via a live non-phantom poll — `/api/feed` returned 25 NBC items, all English, 0 Spanish.
- **Whack-a-mole check:** content-scanned all other general-news sources for hidden non-English pollution — all clean English; no other mislabeled/wrong-language source.
- **Regression-guarded:** `test_feeds_seeder.py` now asserts the NBC source points at the English `us-news` topic feed and never the mixed `/public/news` top-stories endpoint (static config, no network — not flaky). Full helper suite green.

Helper-only change. Goes live on the operator's helper redeploy (`docker compose build --pull && up -d`, verify `/health`, then `/api/feed` shows NBC in English). No app/NAS-topology change.

## Screenshot gallery (automated web capture) + victory-lap README (2026-06-13, Campaign 4)

The showcase pass — a reproducible screenshot pipeline, a gallery, and a README rewrite:
- **Automated web-wall capture** (`tools/screenshots/`): a pinned **Playwright** (1.60.0, lockfile committed) script that boots the LAN web wall in **demo/phantom mode** (mock data, no NAS, no secrets — runs anywhere) and captures the feature surface — the wall, the markets/sports/news ticker, the settings modal, the honest channel picker — to `docs/screenshots/web/`. Deterministic framing (fixed viewport, ticker frozen at its left edge, wait-for-render). A second script regenerates the device-shot placeholders.
- **CI capture job** (`.github/workflows/screenshots.yml`, manual `workflow_dispatch`): boots phantom, captures, and uploads the gallery as a downloadable **artifact** — chosen over auto-committing binaries on every push (the gallery *can* be regenerated reproducibly; a bot doesn't spam commits). Secret-free, same phantom boot as the CI smoke test.
- **Gallery** (`docs/screenshots/`): the automated `web/` shots (committed, demo data) + a `device/` dir with **labeled placeholders + a filename spec** for the operator's manual native-TV hero shots (the real wall on a dedicated Android TV display — only the operator can capture those). The README references both, so it's complete-shaped now and gets richer when the hero shots land.
- **Victory-lap README**: the plain technical README rewritten into an honest showcase — hero shot, feature highlights with inline screenshots, a concise three-component architecture (+ mermaid diagram), the engineering story (adversarial review, the green CI gate, the hardware-proven adb invariant, honest-degradation, phantom mode), and the 60-second demo-mode quickstart. Claims kept true-to-shipped; topology-clean (demo/placeholder values only).

Honest demo note: phantom mode serves SAMPLE markets/sports + a fixture feed and does not exercise the four bespoke individual-sport cards (PGA/UFC/Tennis/F1 need live ESPN data) — the web shots show that honestly; the device hero shots are the live-data view. No app/NAS/real-data deploy; capture runs against the secret-free demo helper only.

## chore(ci): bump GitHub Actions to Node-24 runtime majors (2026-06-13)

GitHub forces JavaScript actions off the Node 20 runtime onto Node 24 by **2026-06-16**; the pinned actions declared `node20` and would start warning/breaking. Bumped each to its current major that runs on Node 24 (`ci.yml` version strings only — no job logic, matrix, or the advisory-ruff design changed):
- `actions/checkout` **v4 → v5** (pure Node 20→24 runtime bump; no input/behavior change).
- `astral-sh/setup-uv` **v3 → v7** (first major on Node 24; v7 still publishes a moving major tag, matching the repo's tag-pin style — v8 dropped them). New default: `enable-cache: auto` caches uv deps on hosted runners (keyed by the lockfile — reproducible, faster CI; opt out with `enable-cache: false` if ever needed).
- `actions/setup-java` **v4 → v5** (Node 24; `distribution: temurin` / `java-version: '17'` byte-identical — no `with:` change).
- `android-actions/setup-android` **v3 → v4** (Node 24; input names unchanged; default cmdline-tools advances to 20.0 — a forward SDK bump, pinnable via `cmdline-tools-version` if needed).

No `with:` migration was required. The push re-runs CI to confirm the blocking gates stay green and the Node-20 deprecation warnings are cleared.

## Playlist / M3U endpoint + profile foundation (2026-06-13, Campaign 3 HALF 2)

The helper now exposes the resolved channel lineup as a standard **M3U playlist** a generic player (VLC, incl. VLC-on-Apple-TV) can load directly — the groundwork for the cross-platform-profiles fork (BACKLOG item H), with the helper staying the resolver/shield (no video proxy):
- **`GET /api/playlist.m3u`** — the built-in `default` profile (every channel **live right now**), as `#EXTM3U` + `#EXTINF` entries (`tvg-id`=slug, `tvg-name`=label) pointing at each channel's **resolved upstream `current_url`**. The helper never proxies the bytes (the no-proxy decision); the M3U is a channel list, not a gateway.
- **`GET /api/playlist/{name}.m3u`** — a named **profile**: an ordered channel subset a given display should see (office wall vs. a bedroom Apple TV). Unknown profile → 404.
- **Profiles** = a built-in `default` (all live) plus optional operator-defined named profiles loaded from a JSON file (`PROFILES_FILE`, see `helper/profiles.example.json`) — read **once at startup**, operator data kept out of git, loader tolerant (a bad/again-bad file degrades to default-only so the wall boots). This is *not* the TV's mutable on-device lineup (that stays in `LineupStore`); per-client server-side prefs / identity / sync stay deferred per item H. The endpoint is stateless (returns what's live now).
- **Honest degradation (C3):** only `status==live` channels are listed (the same gate the TV + web clients use); a slug that isn't live — even one a profile names — is dropped, never faked. A fully-down lineup yields a valid, empty `#EXTM3U`, not a fabricated list. M3U output collapses CR/LF + strips quotes so a channel label can't inject a playlist line.
- **LAN-only, no new public surface, no new dependency, no new egress** (reads the snapshot the prober already maintains). Tests: **26 new** (`test_playlist_m3u` / `test_playlist_profiles` / `test_playlist_api` — incl. the live-vs-unavailable env gate + the no-proxy property); full helper suite **253 green**, new code ruff-clean.

Goes live when the helper is redeployed at the NAS (`docker compose build --pull && up -d`; `/health` `build_sha` verified). Operator validation: `curl https://<helper>:8443/api/playlist.m3u` → valid M3U, then load that URL in VLC (and VLC-on-Apple-TV → add network stream) and confirm a clean-resolving channel plays. See `docs/findings/22-playlist-profiles.md`, `ARCHITECTURE.md §23`.

## Web client parity — per-sport cards, news, settings, schema guard (2026-06-12, Campaign 3 HALF 1)

The LAN web client (served at `/app`) reaches feature parity with the native wall (`web/` only; consumes the existing helper endpoints):
- **Per-sport ticker cards** rendered from the **structured** ticker data (`game`/`card`, not display-string parsing): team game cards (abbr + score + the ESPN status block, color-coded live/final/upcoming) and the four individual-sport cards by `kind` (leaderboard / fight / match / race), matching the native design + palette.
- **News** in the web ticker (source-labelled, inert plain text — A1).
- **Honest-degradation pills** carry to web: per-entry SAMPLE (`is_sample`), envelope-level STALE — never faked (the real "no games" state shows no pill).
- **`schema_version` guard** in the web client (closes **ARCH-1** PARTIAL): a version mismatch surfaces a visible "client out of date" state — never silently renders an unknown contract.
- **Web settings parity** (gear modal): grid rows×cols, sports-leagues pool (built from the real served leagues), feed recency, ticker scroll / news toggles — localStorage-persisted; the TV-panel-only fit controls (Fit/Stretch/Overscan/Position) correctly skipped.
- **CI now runs the web tests** (`node --test`, 50 green). CSP stays locked, no CDN, hls.js vendored untouched, no-proxy preserved.

Goes live when the helper is redeployed at the NAS (rsyncs `web/`). Visual parity is the operator's in-browser feel-test. HALF 2 (playlist/M3U endpoint + the profile abstraction — the cross-platform-profiles fork) is the continuation.

## Ticker end-of-crawl dwell + in-flight deploy cleanup (2026-06-12)

- **Ticker legibility (app):** the sports ticker now **holds at the fully-revealed right edge** of a league crawl before flipping to the next category, so the rightmost content is readable. `PageRow`'s continuous marquee became a controlled single-pass reveal + a ~0.75s end-hold (capped via `revealDurationMs` so the hold always fits before the flip); the page-flip dwell, the pinned marker curtain, and the stale/paused behavior are unchanged. New `RevealDurationTest` pins the capped-duration logic. *Ships on the next `.92` deploy; visual confirmation on the panel is the operator's step.*
- **Banked the in-flight deploy hardening:** the health-gate now tolerates the flaky `.92` transport — it retries the logcat read (30s, was 10s), catches the read timeout instead of crashing, and **fails OPEN** (installed-but-unverified, no false-rollback) when the transport is unreadable; reserves rollback for positive crash evidence; adds `--skip-health-gate` and `--skip-build`. `deploy-app.sh` also refuses a placeholder/wrong-box device (the `.182` incident) and the gitignored `deploy.local.env` default was corrected `.182`→`.92`.
- **DEPLOY-2 confirmed on real hardware:** the operator hand-deployed current MyMTS to `.92` via the invariant (byte-verify `1918505 == 1918505`, `pm install` Success — **signing key intact**, `lastUpdateTime` advanced). The adb invariant is now proven end-to-end; `.92` runs current MyMTS.
- **Backlog:** logged a creative *"liven up the ticker league/sport marker"* item (per-league accent color / sport glyph / logo-caveat — readability-first).

## Remediation: adb deploy invariant + CI + poller/settings tests (2026-06-12)

Campaign 2b — the code + CI + tests remediation from the 2026-06 adversarial review, conforming to `AGENTS.md`.

- **DEPLOY-2 (P1):** all deploy scripts now implement the adb invariant via a shared `scripts/lib-adb.sh` — `adb push` → on-device byte-size verify == local APK (retry on mismatch) → `pm install -r` → verify `lastUpdateTime` advanced; never a streamed `adb install`; reconnect-not-`kill-server` on a wedge. The rollback uses the same verified path (the rollback-of-rollback) and retains the known-good APK. New `scripts/test_adb_invariant.sh` proves the gate (truncation rejected without installing, retry recovers, stale-install caught) — 8/8 green.
- **DEPLOY-1:** `deploy-app.sh` build runs `:app:clean` first (no more stale-APK trap).
- **DEPLOY-3:** `deploy-helper.sh` snapshots the running image as `last-good` before `up -d` and auto-reverts on a failed `/health` verify (scoped to `mymts-helper:*` only; the data volume is never touched; always prints manual recovery).
- **CI (DEPLOY-4 — highest-leverage):** `.github/workflows/ci.yml`, secret-free GitHub Actions running helper `pytest`, the new poller tests, the `schema_version` consistency check (ARCH-1), the adb gate test, a phantom demo-boot smoke, and the app JVM unit tests. First run green. The 2a "planned" CI notes were flipped to truthful.
- **API-3:** 15 direct poller orchestration tests (realistic upstream shapes + failure-injection, not mocked-green).
- **ARCH-3:** a WallSettings round-trip test (all 18 fields incl. the locked panel-fit) — protects the panel-fit from a silent reset on update.
- **DATA-1:** non-finite (NaN/Inf) market prices are rejected (no fake-live `nan` cell). **DEPLOY-6:** the `soak.sh $INTERVAL` typo is fixed.
- **Deferred honestly to backlog:** API-2 (bozo-success masking in `/health`), per-source 429 cooldown, channels `mapNotNull`, `events[0]`→iterate, and the ~51 pre-existing ruff violations (CI ruff is advisory until cleared).

The live `.92` deploy verification of the rewritten scripts is the operator's to run (no device access in this pass) — see the report.

## Governance layer: AGENTS.md + doc-honesty reconcile (2026-06-12)

Made the repo self-governing and reconciled docs to reality (campaign 2a — docs/governance only, no code behaviour change, no deploy). Follows the 2026-06 adversarial review.

- **`AGENTS.md` (new, repo root)** — the canonical in-repo standard any coding agent reads first, generated from the review's §17.5 Guardrails: protected invariants (locked panel-fit, manual-launch, honest-degradation, same release key, the wire schemas), the never-without-approval list (PIA, gitignored config/keystore, force-push/history-rewrite, background/kill-server adb, container-hardening), sensitive areas (.92 transport, focus model, untested pollers), and the **deploy adb invariant** codified (push + on-device byte-size verify + `pm install -r` + `lastUpdateTime` advanced; never streamed install; one foreground op; never kill-server). Referenced from the README.
- **Doc-honesty fixes** (review's "Today" items): corrected the "2 mock channels" claim to the real ~21 across `.phantom.yml`, `ONBOARDING.md`, `ONBOARD-01`, and the `phantom.py` docstring; made the **imaginary-CI** claims honest (no CI exists yet — marked "planned") in `.phantom.yml`, `ARCHITECTURE.md`, `docs/THREAT-MODEL.md`, `SECURITY-PRACTICES.md`, and the `phantom.py`/`test_phantom.py` docstrings (comment-only, no behaviour change); rewrote the stale `helper/README.md` (pre-Stage-2 skeleton wording + a `docker compose up` path that crash-loops) to built reality + the supported `uv run` path; reconciled the top-level README (Stage-0 table/Setup → built status; fixed the `ARCHITECTURE.md` link).
- Also updated the house `professionalize.md` (workspace-level, outside this repo): added the authorized-history-rewrite safety protocol to §0 and re-added the audience-aware-docs standard (both the §4 body and the quick-ref).

The code remediation (DEPLOY-2 adb invariant, CI, poller tests) is campaign 2b and will conform to `AGENTS.md`.

## Professionalization: portable docs + parameterized config + history scrub (2026-06-12)

Made the repository **clean-in-itself** — generic, portable, and free of internal
topology in both the working tree and git history.

- **Docs generalized.** All committed docs scrubbed of internal IPs, NAS paths,
  MACs, ssh host, tunnel domain, and absolute user paths (→ placeholders); the
  VPN/infra-coexistence guidance generalized to the portable security point (the
  helper stays isolated on its own network, never touching unrelated services on
  its host). The two pure author-deployment runbooks (`docs/OPERATIONS.md`,
  `ONN-BOXES.md`) moved to gitignored `docs/ops-local/` with committed signpost
  stubs; a new *audience-aware docs* standard added to the house guidelines.
- **Functional config parameterized** (behaviour-preserving): the app's
  `network_security_config.xml` TLS-pin host is now **generated at build time**
  from the same local-config helper URL (no IP committed; reproduces the prior
  pin exactly for the operator, demo-safe loopback default otherwise); deploy/
  probe scripts + the NAS compose now source operator values from gitignored
  local config with generic placeholder defaults. Operator's real values live
  only in gitignored `local.properties` + `scripts/deploy.local.env`.
- **Git history rewritten (one-time, authorized).** `git filter-repo`
  (`--replace-text` + `--replace-message`) purged the same specifics from all 94
  commits' contents **and** messages. Verified: 0 operator-specifics in history,
  current content byte-identical, no secret ever committed (the public helper
  cert is the only cert), 94 commits + DAG preserved. A full mirror backup was
  taken first. **Existing clones must re-clone** — see `ONBOARDING.md`.

## Video defaults + PGA scroll + ticker speed + manual reconnect + sectioned picker (2026-06-11)

Six operator changes from using the wall (built A–F; E checkpointed).
- **A — new 2×2 default lineup:** reordered `LineupSelector.PREFERRED` so the default 2×2 fills **TL LiveNOW from FOX, TR Fox Weather, BL BBC News, BR CBS Sports HQ**. Explicit per-slot overrides still win; everything stays in the picker. Verified on-panel.
- **B — PGA leaderboard top-10, scrolling:** the leaderboard card shows the **top 10** with position (`1. Theegala -6`) and scrolls horizontally via the existing page marquee when it overflows. Verified in the API (10 lines).
- **C — slower flip + speed sliders:** the page-flip default is calmer (base dwell **6500→9000ms**), and a new **Ticker** settings section has two D-pad sliders — **Scroll speed** and **Flip speed** (40–200%, default 100%, live-applied + persisted; `TickerStrip` scales velocity linearly and dwell inversely). Verified on-panel.
- **D — manual video reconnect:** `StreamPlayer.reconnect()` (fresh player + manifest, resets recovery state) exposed two ways — a **"Reconnect"** row in the per-tile controls, and a **"Refresh all video"** row in settings — driven from `WallScreen` via a nonce → `VideoGrid`/`StreamPlayerManager`. Honest play-what-works on the result. Verified on-panel (the rows render).
- **F — channel picker sectioned by category:** the channel list picker groups channels under **Sports / US News / Global News / Business / Weather / General** headers (`ChannelCategory` taxonomy), live-first within each, honest live/offline tags, opens on the slot's current channel, scroll-follows-focus. Verified on-panel (SPORTS/US NEWS/GLOBAL NEWS sections with the right channels).
- **E — news-feed genre/source two-level toggles: CHECKPOINTED** for a fresh session (the bigger taxonomy — a genre→source tree with check-box toggles, reconciled with the sports-leagues pool). Not started; the shared category naming from F is the foundation.
- Tests per part (lineup order, PGA top-10/position, ticker-speed clamp, `ChannelCategory`/`channelToFocus`). Full suites green. 0 fatals on `.92`.

## Menu focus fixes + Rows×Cols grid + channel list picker (2026-06-11)

Four issues from the operator using the new menu — two focus bugs (the core) + two UI enhancements.
- **Fix — focus-loss on menu exit (the Onn-box focus-escape, WyzeGrid pattern):** closing the menu lost D-pad control / leaked focus to the video "main panel." Three deterministic fixes: (1) the video `PlayerView` is now **non-focusable** (`isFocusable=false` + `FOCUS_BLOCK_DESCENDANTS`) so focus can never escape to a tile; (2) the side menu **explicitly reclaims focus** when a sub-overlay dismisses (keyed re-home, was a one-shot that never re-fired); (3) the wall root **explicitly re-homes focus** on full menu close via a frame-yielded `requestFocus` (replaced the racy `DisposableEffect`). **Verified on-box across 3 open/close cycles** — focus returns to the wall root (`[0,0][1280,720]`), never lost, never hijacked.
- **Fix — menu scroll doesn't follow the cursor:** navigating down, the focused row slid into the overscan-clipped bottom. The default `.focusable()` bring-into-view scrolled flush to the edge; now each row uses an explicit `BringIntoViewRequester` fired on focus, so the cursor stays visible. **Verified on-box** — scrolling to the last row keeps it on-screen (y within 0–720).
- **Feat — independent Rows × Columns grid:** the single "Video grid" preset (1/2/4/6/9) is replaced by two selectors — **Grid rows (1–3)** and **Grid columns (1–3)** — so 2×2, 2×3, 1×3, 3×2 … up to 3×3. `WallSettings.gridRows`/`gridCols` (default 2×2, clamped, persisted); `VideoGrid` takes explicit `columns`; the focus model's column nav matches. Cell count = rows × cols; per-slot channel overrides survive an R×C change. *(Migration: the old `gridSize` pref is dropped — the grid resets to 2×2; re-set R×C if you'd changed it.)*
- **Feat — channel selection is a scrollable LIST:** the 1-at-a-time left/right cycler is replaced by a `LazyColumn` picker — D-pad UP/DOWN through every channel, SELECT to assign, BACK to cancel. Opens focused on the slot's current channel, scrolls to follow the cursor (same fix as above), LIVE/OFFLINE section headers + per-row `live`/`offline` tags (honest status, C3).
- Tests: `WallSettings`/`LineupStore` grid-dims (default 2×2, clamp, persist), `ChannelPickerListTest` (initial index). Full suite green.

## Individual-sports ticker cards — UFC / PGA / Tennis / F1 (2026-06-11)

The four structurally-different sports now have bespoke ticker cards (they don't fit the team-vs-team game card). Research-first (`docs/findings/21`), then built on a shared per-sport payload — **all four shipped**.
- **Shared architecture (additive, schema v1 preserved):** a new `card` payload — `{league, kind, title, state, status, lines[]}` — rides each individual-sport entry alongside (not replacing) `game`, omitted on the wire when absent. The helper does the sport-specific formatting; `SportsTicker.blocks` groups card entries into their own league block (so each gets the pinned marker + curtain + flip); `TickerStrip` dispatches by `kind` to a bespoke composable.
- **PGA — `leaderboard`:** tournament + top-3 by rank with score-to-par (`S. Theegala -6`; "E" for even) + round status. Verified live on the panel (RBC Canadian Open).
- **UFC — `fight`:** one card per bout, headline first (main event reads last on ESPN, so parsed in reverse); `A vs B` / `A def. B` (winner first; a draw never invents one) + weight class + status. Verified on the panel (UFC Freedom 250 — Topuria vs Gaethje first).
- **Tennis — `match`:** matches read from `event.groupings[].competitions[]` (the top-level is empty — the findings/19 gap); `A d. B` + set scores with tiebreaks (`7-6(7) 6-4`), in-progress first then recent finals, capped. Verified on the panel (Boss Open finals).
- **F1 — `race`:** a run race's podium (`1. Verstappen …`) or an upcoming weekend's race start (`Barcelona-Catalunya GP · 6/14 9 AM`), clean GP name. Verified on the panel.
- All four moved from the picker's "Coming soon" note into the **active league toggles** (the staged list is now empty); each cycles in the ticker + is enable/disable-able + couples to the same pool as scores/news. Honest degradation throughout (off-season / no current event → league omitted, never faked).
- Helper-side validated on live ESPN data; tests: `test_ticker_individual` (20 cases across the 4 parsers) + app card-parse + blocks-with-card. Full suites green. 0 fatals on-box.

## Settings menu — grouped into sections + sports picker roadmap (2026-06-11)

The wall settings overlay grew organically across many chapters into one long flat list. Reorganized into labelled **sections** — **Display & Fit**, **Layout & Feed**, **Sports** — while keeping the exact single-level D-pad nav.
- **Sections, not a flat list:** non-focusable section headers group the rows; UP/DOWN focus traversal skips the headers, so every setting stays reachable with the same nav — no two-level menus, no focus traps. The card now **vertical-scrolls and follows focus** (height-capped to the panel), so the longer grouped list never clips. *(Especially relevant now that the grid can be 6/9 — the side menu has more channel slots above Settings.)*
- **Presentation only — values preserved.** Every setting keeps its current persisted value; the **locked panel-fit is untouched** (verified on-box: Fit 80% / Stretch 110% / Overscan None / Position 0,0 / Calibration Off, all intact after the regroup).
- **Sports picker:** the existing league filter (8 leagues: NFL/NCAAF/UFL/NBA/WNBA/NCAAB/MLB/NHL, all default-on, driving the SAME pool as ticker scores + sports-news) now lives in the **Sports** section and shows the structurally-different sports as **"Coming soon: UFC · PGA · Tennis · F1"** — honest roadmap, not offered as toggles whose cards aren't built yet (they join when Prompt-3 ships their bespoke cards).
- Verified on-box (.92) via uiautomator: all three section headers render, every row reachable, locked fit intact, the coming-soon note shows. 0 fatals.

## Live market data — Yahoo Finance replaces Stooq sample fallback (2026-06-11)

The markets ticker showed honest **SAMPLE** pills for indices/FX/gold because **Stooq bot-walls the NAS egress IP** (a JS challenge the prober can't pass). Swapped the helper's markets fetch to **Yahoo Finance's keyless v8 chart endpoint** (`query1.finance.yahoo.com/v8/finance/chart/<symbol>`), which **IS reachable from the NAS egress** — verified the WeatherNation way: probed from *inside the helper container* (the prober is the gate, not the dev Mac).
- **Everything is live now.** Yahoo covers all 14 quotes — the 7 indices, 3 FX pairs, gold — **and** the three that were previously sample-only (Brent, WTI, 10Y UST). Only crypto stays on CoinGecko (already worked). No more SAMPLE tags in the normal case.
- **Per-symbol isolation (C2):** one request per symbol, fetched concurrently (`asyncio.gather`) — a single symbol failing samples only that symbol, never the whole set; wall time bounded by the slowest fetch.
- **Honest SAMPLE preserved (C3):** a symbol whose fetch fails/parses empty still falls back to an honest SAMPLE placeholder — real-when-reachable, SAMPLE-only-on-genuine-failure. **Keyless** (no API key), works with the helper's own UA. DTO shape unchanged → the TV app needs no change, it just receives real prices.
- Tests: `parse_yahoo_chart` (price + direction, `previousClose` fallback, drops missing/non-numeric/bool, never raises on junk), snapshot real-vs-sample mixing, rate/index/FX formatting, URL symbol-quoting. Full helper suite green.
## Weather feeds — two national weather channels added (2026-06-11)

Research-first weather-feed pass (national + local). Added two **confirmed public keyless HLS** national weather channels to the helper channel seed: **Fox Weather** and **AccuWeather NOW** — each verified master → variant → media-segment (real `video/MP2T`) AND confirmed **`status=live` by the NAS prober** after deploy. They enter as the `rest` tier (available in the menu picker, not a default slot); the operator selects one into a cell.
- **WeatherNation was probed streamable from the dev Mac but DROPPED** — the NAS helper prober fails its TLS handshake (`SSLV3_ALERT_HANDSHAKE_FAILURE`), so it would sit permanently OFFLINE. Honest play-what-works: a tile that can't validate where it's deployed is worse than none.
- **No central-Illinois/Midwest LOCAL weather stream was added** — those stations are auth-gated or YouTube-page-only. The only US locals with open weather HLS (Baton Rouge LA, Manchester NH) are out-of-region, so not added as "local." Reported honestly rather than shipping a wrong-region or broken tile.
- **The Weather Channel proper** is TV-provider-login gated — not addable without stored credentials (posture). **NOAA/NWS** is radar/data, not video.
- Full landscape (streamable / gated / not-video, with probe evidence) in `docs/findings/20-weather-feed-research.md`. BACKLOG: WeatherSpy (also streamable, niche), WeatherNation TLS recovery, and a possible NWS radar *non-video* widget.
## Ticker: pinned per-page marker (BottomLine curtain) + video label bottom buffer (2026-06-11)

Two presentation refinements after the operator reviewed the wall on the panel.
- **Pinned marker + curtain-clip scroll (ticker):** each ticker page now has a marker **pinned at the left edge** — a full-height **opaque** green curtain labeled per page (`NBA`/`MLB`/… for leagues, `MARKETS`, `NEWS`). It **persists** through the page's horizontal marquee scroll instead of scrolling away with the games. As cards scroll left they **vanish cleanly AT the marker's right edge** (the ESPN BottomLine "curtain"): the marker is drawn on top of a `clipToBounds` scroll area, so a card is occluded at the marker rather than visibly sliding under a translucent block. A leading `Spacer(MARKER_WIDTH)` keeps a static (non-overflowing) page's first card to the right of the marker; on overflow that reserve scrolls away and the cards pass behind the curtain. The per-page marker label is pure + unit-tested (`TickerPaging.Page.markerLabel`). The previous per-league pill (`LeagueMarker`, which scrolled with the games) is gone. The STALE flag now pins to the **right** edge (overlay, near-opaque backing) so it never displaces the left curtain.
- **Video label bottom buffer:** the below-video title strip gained a few dp of bottom padding (strip 18→22 dp, `LABEL_BOTTOM_BUFFER` = 4 dp) so the title isn't flush against the cell's bottom border. Purely additive — no placement change (still below the video), the extra dp come out of the video `weight(1f)` so it stays inside the cell's overscan-safe band, uniform at every grid size.
- **Verified on-box (.92):** marker pinned per page (NHL/WNBA/MLB caught on a busy game day); a card slides fully behind the MLB curtain (only the date peeks, no see-through); titles read with breathing room below. 0 fatals. Adversarial review (14 agents) raised 12, confirmed 1 (the STALE-pill displacing the marker when stale) — fixed before commit.
- Tests: `TickerPagingTest` (markerLabel per page type + every-page-has-a-marker); `WallTileLabelStripTest` (buffer > 0 and strip fits title + buffer).
## Video section refactor — measured area → cells → [video + label] units + configurable grid (2026-06-11)

The proper architectural fix for video-tile labels, replacing the prior bolt-on heuristics (overlay-on-video, then the dimension-aware below/above/bubble picker). The video section now **lays out structurally**, so a uniform below-video label that never clips is a property of the layout, not a per-tile guess — at **any** grid size.
- **[video + label] cell unit (`WallTile`):** each cell is a `Column` — a `weight(1f)` video area above a fixed-height label strip. The video area letterboxes via Media3 `RESIZE_MODE_FIT` (real `videoAspect`, no manual math); the label sits in its own reserved strip **below** the picture. The bottom row is no longer a special clipping case — every cell reserves its own label space. `TileLabel.kt` (the heuristic placement) is **deleted** — superseded by structure.
- **Section safe area (`VideoGrid`):** the grid `Column` reserves a `SECTION_SAFE_BOTTOM` (28dp) band so the bottom row's label strip stays inside the panel's visible area — derived from the residual overscan clip, **reading** the locked panel-fit (Fit 80% / Stretch 110% / Overscan None / Position 0,0), never changing it.
- **Grid-agnostic dimensions:** `gridColumnsFor(count)` + new `gridRowsFor(count, columns)` give 1×1 / 2×1 / 2×2 / 3×2 / 3×3 for 1 / 2 / 4 / 6 / 9 cells. The cell unit is identical at every size.
- **Configurable grid count:** new **"Video grid"** setting (`WallSettings.GridSize`: 1 / 2 / 4 / 6 / 9, **default 4 · 2×2**) in the Settings menu, persisted via `LineupStore` (SharedPreferences, ordinal + fallback-to-Four). `WallScreen` drives the grid, slot resolver, and focus model off `gridSize.cells`. Channel choices reconcile across grid changes: explicit per-slot `overrides` are keyed by slot index and preserved; the default fill is a **stable prefix** (`LineupSelector` preferred→fallback→rest) so 4 → 6 → 4 returns the same lineup (only live-availability churn moves it, by design).
- **Verified on-box (.92):** at the default 2×2 all four labels sit **below** their videos, uniform, nothing clipped; cycled the menu setting to 6 (3×2 — six cells, every label below) and back to 4. 0 fatals.
- **BACKLOG:** hardware-aware optimal grid configs — offer only grids sensible for the panel's real dimensions/resolution (the measured-area infra here is the foundation).
- Tests: `VideoGridLayoutTest` (cols×rows for 1/2/4/6/9 + `gridRowsFor` guards); `WallSettingsTest` + `LineupStoreWallSettingsResolveTest` extended for `GridSize` (default Four, ordinal round-trip, all-absent resolve).

## Markets SAMPLE restyle + dimension-aware video tile labels (2026-06-11)

Two polish items after the operator used the new ticker/feed/video.
- **Markets card — consolidated SAMPLE (Part A):** the bulky separate boxed "SAMPLE" pill is gone. A not-live quote now renders as a **dimmed cell** (muted symbol + ghost value — the 10-ft "not a live price" cue) with a small lowercase italic "sample" tag in the cell; the value stays prominent. A LIVE quote (e.g. BTC/ETH from CoinGecko, which the NAS reaches) renders clean + bright, so live-vs-sample reads at a glance. **C3 intact** — the not-live state is still perceptible, just not noisy. (The stock indices/FX show sample because Stooq bot-walls the NAS egress — logged in BACKLOG.)
- **Video tile labels — below the video, dimension-aware (Part B):** the label moved off the over-video overlay back to the operator's original look — **below the rendered video**, on the black letterbox. `StreamPlayer` now exposes the real `videoAspect` (from ExoPlayer `onVideoSizeChanged`); `TileLabel.placement` (pure, tested) picks **below → above → tinted-overlay** from the cell + video dimensions. The **bottom row** (whose lower edge sits in the overscan-clipped band) falls back to **above** the video when below would clip; other rows get **below**. A pillarboxed/unknown tile gets a tinted bubble lifted into the safe area. The locked panel-fit config is untouched (this is per-tile internal layout). Verified on-box (top-row label below the picture, bottom-row above).
- **BACKLOG:** (1) a live market source the NAS egress can reach (Stooq is bot-walled — CoinGecko/ESPN already work); (2) a full sports-selection league-picker menu, bundled with a future menu-interface overhaul.
- Tests: `TileLabelTest` (below/above/overlay selection).

## Feed: sports news mixed into the agnostic river, gated by the Sports-leagues pool (2026-06-11)

Sports-news headlines now appear inline in the existing agnostic newest-first feed, sourced + interleaved by time, **filtered to the operator's enabled leagues** — the SAME Sports-leagues pool that drives the ticker scores, so enabling/disabling a league moves its scores AND its news together.
- **Helper:** added the 8 ESPN keyless **RSS** news feeds (NFL/NCAAF/UFL/NBA/WNBA/NCAAB/MLB/NHL — verified live) to `feeds/seed.json` with the league name as the source label. The existing RSS poller fetches/parses/stores them — no new fetch path; they flow into `/api/feed` interleaved.
- **App:** `FeedListBuilder.applyFilters` gained a `hiddenLeagues` arg — a feed item whose source is a league is dropped when that league is hidden in the Sports-leagues filter (tied to the same pool as the scores). **Blend, don't dominate:** sports-news is capped to the newest `MAX_SPORTS_NEWS` (14) so a busy day can't flood the river; general news is uncapped; `build` interleaves both chronologically. Source label rides each row (A1 — inert plain text, no web reading).
- C3: sports news is real ESPN RSS, source-labelled like every item; a stale/unavailable league source degrades like any feed source (honest, never faked). Keyless — no new secret.
- Tests: `FeedListBuilderTest` (league-pool drop, sports cap).
## Ticker: whole-ticker paged flip — market quotes carded, scroll-within-page (2026-06-11)

The operator, after the sports flip, asked for the WHOLE ticker to work that way. Now every mode is a flip **page** with one consistent motion: the market quotes are a carded page, each sports league is a page, news is a page — and the strip flips between them all (markets → league blocks → back) with the same hold-then-flip; markets flips IN exactly like a league block. Built + deployed to `.92`, verified on-box (markets cards + a live NBA card across the rotation).
- **feat(ticker): `TickerPaging`** (pure, tested) turns one mode's entries into flip pages: games → one `League` page per block; arrow-bearing quotes → one `Markets` page; NONE-direction headlines → one `News` page. Each page has a stable flip key so the flip triggers on a real page change, not incidental equality.
- **feat(ticker): market quotes are bordered cards** — the same cell language as the game cards (symbol + value + arrow + SAMPLE pill). News headlines carded too (source accent + headline). Consistent visual language across the strip.
- **feat(ticker): scroll-within-an-overflowing-page** — each page is a `basicMarquee` row that scrolls only when its cards exceed the panel width (a wide markets set / a busy league night); a page that fits stays static. Flip BETWEEN pages, scroll WITHIN one.
- **feat(ticker): consistent flip** — one `AnimatedContent` (keyed on the page key) drives every transition, including the markets↔sports mode rotation, so markets flips in like a league block.
- **C3 intact:** SAMPLE pills on sample market/game cards; the STALE pill (leading, not scrolled) on aged real data; an empty/honest mode falls back to its line. Tests: `TickerPagingTest` (pages per mode, keys, wrap); existing `SportsTicker`/parse/source suites unchanged.
## Video grid: fix clipped bottom-row tile labels on the fitted panel (2026-06-11)

On `.92`'s fitted panel (Fit scale 80% / Vertical stretch 110%) the top tiles showed their name labels but the **bottom row did not** — the label sat flush at the tile's bottom edge (`6.dp`), which for the bottom row is the fitted wall's bottom, where the panel's overscan crops a sliver. Reserved a **bottom safe-area** inside every tile (`TILE_LABEL_BOTTOM_SAFE = 28.dp`, applied to `ChannelLabel` + the dead-tile label) so the label lifts into the visible area — a fix to the **video panel's internal label layout**, NOT the global fit (which stays Fit scale 80% / Vertical stretch 110% / Overscan None / Position 0,0). Verified on-box via screencap: all four tiles' labels now render inside the visible region. Deployed to `.92` (push + `pm install -r`, `lastUpdateTime` 19:29).

## Ticker: sports overhaul — BottomLine game cards, ESPN status blocks, league-cycling flip (2026-06-10)

The sports portion of the ticker moved from a run-together `·`-stream to ESPN-BottomLine-style discrete game cards. Decisions confirmed with the operator: **flip = sports only** (markets + news keep scrolling); **pool = the in-menu "Sports leagues" filter** (enabled = the auto-cycling pool). Built + tested + deployed (helper to the NAS, app to `.92`); verified rendering on the panel (a live WNBA card `CON 55 TOR 44 · 1:03 2ND` next to an upcoming `LA @ SEA`).

- **feat(helper): structured `GameDTO`** on sports entries (additive — markets/news omit it, stay byte-identical to schema v1). The sports pool expanded to the **8 team leagues** ESPN's keyless scoreboard exposes in the standard shape (NFL, NCAAF, UFL, NBA, WNBA, NCAAB, MLB, NHL — verified live); **live-first** ordering within each league; empty leagues omitted. UFC/PGA/tennis/F1 are structurally different and **staged** (BACKLOG + finding 19).
- **feat(app): game cards + strong dividers (goal 1)** — each game a discrete bordered card, not a `·`-stream. Team abbrs + scores (no logos; ESPN dropped them for legibility); a pre-game shows `AWY @ HOM` + time, never a phantom 0–0.
- **feat(app): ESPN status block (goal 2)** — a weighted, **colour-coded** block clearly separated from the score: **LIVE = bright green**, **FINAL = muted grey**, **UPCOMING = neutral**, formatted `5:42 1ST` / `FINAL` / start time. Final-vs-live reads at a glance; only the actual leader's live score is brightened.
- **feat(app): league-cycling flip (goal 3 / motion)** — sports HOLDS a league's card-set (~6.5 s) then flips (vertical slide+fade) to the next league, cycling the pool; a league marker heads each block. The sports mode dwell lengthened to ~42 s so it cycles the pool a lap. Markets + news keep the scrolling marquee.
- **feat(app): pool config** — the existing in-menu "Sports leagues…" filter expanded to all 8 (enabled = pool, set-once).
- **C3 honesty:** sample game cards still wear the SAMPLE pill; **aged real data now shows a STALE pill** (the envelope `stale` flag was previously dropped — fixed for markets + sports); a sports mode with no games falls back to the honest scrolling line; the hidden-league filter keys on the game's league. No fabricated score/status/game; keyless ESPN endpoints only.
- Tests: helper `GameDTO`/live-first/additive-wire (188 green); app `SportsTickerTest` (blocking, status classify, ESPN format, flip wrap), `HelperClientTickerParseTest` (game parse, markets-omit, malformed→null), `HelperTickerSourceTest` (isStale, filter-by-game-league). Adversarially reviewed (2 high C3 findings fixed pre-deploy). Focus model unchanged.

## Panel-fit: top-left Fit scale + Vertical stretch + Calibration border; fixed settings LEFT/RIGHT handler (2026-06-10)

Hands-on follow-up dialing the wall into `.92`'s panel. The panel renders the wall **larger than its visible area from a top-left origin** (top-left seated correctly, bottom-right overflowing off-screen) — an anchor neither the centred Overscan inset nor the ±64 dp Position offset can fix. Added the geometrically-correct lever plus supporting work; the wall now fits at **Fit scale 80% + Vertical stretch 110%** (operator-dialled).

- **fix(settings): LEFT/RIGHT row adjust never fired.** In `SettingsOverlay`'s `SettingRow` / `AdjustRow`, `onPreviewKeyEvent` was placed **after** `.clickable()/.focusable()` in the chain — a key-input modifier only receives events when the focus target is its descendant, so LEFT/RIGHT silently no-op'd (SELECT, via `clickable`, masked the bug). Moved the handler above `clickable`/`focusable`. This is why every slider adjusts from the remote now. Verified on-device.
- **feat(wall): top-left-anchored Fit scale** (`WallSettings.fitScalePct`, 50–100%, default 100). A `graphicsLayer` uniform scale with `transformOrigin = (0,0)` on the inset Box — shrinks the whole wall toward the **pinned top-left corner** so an overflowing bottom-right pulls into view; black fills the freed bottom/right. The correct lever for top-left-anchored overscan (the centred inset shrinks the wrong way; a uniform grow would push the sides off).
- **feat(wall): Vertical stretch** (`fitStretchYPct`, 100–130%, default 100). An extra **height-only** factor on top of Fit scale (also top-left anchored) to close a residual **bottom band** without moving the sides. Slight aspect distortion; default-off so it stays panel-specific.
- **feat(wall): Calibration border** (`calibrationBorder`, default off) — a bright magenta boundary + cyan **TL/TR/BL/BR** corner brackets at the true wall edge, so the operator can SEE which edges a panel crops (otherwise invisible by definition). The tool that diagnosed this panel.
- **feat(settings): extended Overscan inset** to None/3/5/7/10/13/16/20% (was capped at 7%); `cycleOverscan` reaches the new presets.
- Tests: `WallSettingsTest` + `LineupStoreWallSettingsResolveTest` cover the new fields (defaults, clamp-on-read, per-key wiring, the Overscan ladder length). Focus model **49** unchanged — the fit scale is a render transform, not a focus change. Deployed to `.92` via `adb push` + `pm install -r` (streamed `adb install` deadlocks on this box's flaky Wi-Fi transport — push the APK, install the local file, verify `lastUpdateTime` advances).
- **Hardware finding:** the box output is geometrically perfect (full 1280×720, `scale=1.0`, no software overscan) — the crop is the **panel**. A 480p-EDID SD panel earlier read as the cause was a red herring; with it removed the real panel simply overscans the HD frame from a top-left origin, fixed by Fit scale. Android TV floors output at 720p (won't emit the panel's native 480p) and exposes no root/sysfs overscan lever on this build, so compensation stays **app-side**.

## Panel-fit: added Position-offset (X/Y) lever to recenter an off-center overscan panel (2026-06-09)

The shipped Display size + Overscan inset are symmetric/centered — they fix "too big" but can't recenter a **shifted** image. This panel overscans off-center and has no hardware menu, so added a **Position offset (X/Y)** lever to nudge the whole wall in real screen space, completing the set (scale + inset + offset) for full software compensation.

- `WallSettings.offsetXDp` / `offsetYDp` (Int dp, default 0,0; clamped ±64 dp, 8 dp step). Applied in `WallScreen` as `Modifier.offset` on the inset Box — **outside** the `LocalDensity` scale override, so it's a true physical nudge that shifts layout + hit-testing together (D-pad focus still lands on shifted content) and behaves predictably at any Display-size setting.
- D-pad-adjustable, **live**: new **"Position X"** / **"Position Y"** rows (an `AdjustRow` — LEFT decrements, RIGHT increments, SELECT nudges right), grouped with Display size + Overscan inset so the fit controls lead the Settings card. Persisted per keypress in `LineupStore` (clamped on nudge **and** on read, so a corrupt value can't shove the wall off-screen).
- Focus model unchanged (the offset is a layout translation, not a focus change — 49 focus tests pass). App **255** unit tests (+offset clamp/defaults/constants + resolver clamp-on-read). Deployed to `.92` (health-gate PASS, 125 `TILE_READY`, 0 dead).

## Panel fit (overscan inset + global UI scale) + native feed → agnostic with source-per-headline (2026-06-07)

Two hands-on issues from running the wall on the new box's 720p panel.

### Panel fit — overscan-safe inset + global UI scale
- The wall clipped at the panel edges on `.92` (the box outputs a clean 1280×720 @ density 213; the **panel physically overscans** — the WyzeGrid box's panel doesn't). Fix is app-side + panel-agnostic:
  - **Overscan inset** (`WallSettings.Overscan`: None / 3% / 5% / 7%, default **5%** = TV action-safe). Measured in real screen space (`BoxWithConstraints`, before the scale) so it's a true physical safe-area margin; the black background shows through it.
  - **Global UI scale** (`WallSettings.UiScale`: Compact 0.80 / Default 1.0 / Roomy 1.15) — a single `LocalDensity` override scaling the **whole wall** (ticker, feed, grid chrome, labels, overlays) together. **Compact** is the shrink-to-fit lever; the per-piece feed width/font still tune within it.
  - Both are D-pad-cyclable in Settings (the new **"Display size"** + **"Overscan inset"** rows lead the panel; the operator lands on them), persisted in `LineupStore`. This cashes in the deferred "global UI sizing" BACKLOG item.
  - Box output is correct (720p/tvdpi); `wm overscan` was removed in modern Android, so the app-side inset is the right fix. A panel-side "just scan / picture size" setting can help too (operator, box-side).

### Native feed → agnostic chronological with source-per-headline
- Per the operator's decision after living with the sectioned version: the native feed is now **one newest-first list across all sources**, with the **source label next to each headline** — matching the reworked web client (`feedChronological` + `sourceLabel`). `FeedListBuilder.build` returns a flat `List<FeedItem>`; `FeedPane` renders source (accent) + age + headline + summary per row. Per-source sections + freshness chips removed.
- **Focus model unchanged** — the feed is still a flat N-item focus zone; `feedIndex == list index` directly (no headers to skip). All 49 `WallFocusModelTest` cases pass unchanged.
- **C3 honesty without sections:** per-item age on each row + the pane header's honest "feed not updating" / "helper unreachable". **A1 held** — inert plain text, no WebView. Feed filtering (source denylist + recency) reconciled to narrow the single list.

### Tests / deploy
- App **245** unit tests (FeedListBuilderTest rewritten for the agnostic interleave; WallSettingsTest +panel-fit presets; focus model 49 unchanged). Deployed to `.92` (health-gate PASS, 127 `TILE_READY`, 0 dead). Operator confirms the visual fit + feed on the panel; tune Display size / Overscan inset to the panel if needed.

## MyMTS provisioned onto its permanent box + fresh release key + deploy-script fix (2026-06-07)

- **Migration:** MyMTS now runs on the **dedicated MyMTS Android TV box** (`<LAN_IP>`, MAC `<MAC>`, 720p panel, no EDID emulator). Aggressive reversible debloat (12 pkgs); MyMTS installed **release-signed + kiosked** (sole kiosk, Model A) reaching LIVE (119 `TILE_READY`, 0 dead); all 4 default tiles LIVE on the box's WiFi path (bloomberg-tv, cbs-sports-hq, bbc-news, cnn); WyzeGrid installed debug-**dormant**. See `docs/OPERATIONS.md` (dedicated-box provisioning) + the operator's local box inventory.
- **Fresh release key:** generated a new MyMTS release keystore (the prior `.182`-era key abandoned). `.jks` gitignored + backed up to `~/Dropbox/secrets/`; passwords in the operator's password manager. New signing identity going forward (cert SHA-256 `7acc6315…`).
- **`scripts/deploy-app.sh` fix:** the signing verification parsed apksigner's legacy `Subject:` label; build-tools 33+ prints `Signer #1 certificate DN:`. Now matches both, so a correctly-signed release no longer falsely fails the gate.
- **Reboot/boot finding (known gap):** on reboot the box auto-restores 720p + the `KioskService` foreground service (network ADB survives reboot), but the **wall activity doesn't auto-foreground over the Google TV launcher** (Android 14 background-activity-launch blocks the boot-time `startActivity`). The HOME-launcher fix was **attempted and reverted** — Google TV's system launchers (`launcherx`, `setupwraith`, `tv.settings`) outrank a third-party HOME app by `android:priority` and win the boot home-race even when MyMTS holds the HOME role; disabling them to force it destabilized the box (recovered + restored to clean known-good). The boot-auto-foreground gap therefore **remains**; the wall works when launched. A code-only **full-screen-intent** from `KioskService` was then attempted (2026-06-07) — it fired correctly (`canUseFullScreenIntent=true`, FSI posted) but **did NOT foreground the wall** on the Google TV form factor (TVs treat FSI as a notification, not an auto-launch); reverted to clean known-good. Remaining options: accept manual-launch after the rare reboot, or a future device-owner/lock-task provisioning project (NOT the launcher-disabling path, which destabilizes this build). See `docs/OPERATIONS.md`.
- No app code changed (provisioning + scripts/docs only); the `1b8d1b0` build is what shipped.

## Web rework round 2 — ticker league markers, agnostic feed, cell-count grid, channel picker, mixed-content honesty (2026-06-06)

Second hands-on pass on the LAN web client. Plus an honest diagnosis course-correction on the video.

### Ticker — ESPN-BottomLine league markers (both clients)
- The league/market marker now shows **once** as an accent pill, then its games/values follow — the redundant per-item "MLB … / MLB …" prefix is gone. `render.mjs::groupTickerByLeague` (web) + `ui/wall/TickerGrouping.kt` (native, 5 tests) group **consecutive** same-symbol entries identically, so both clients read the same. Markets symbols are distinct → each stays its own marker+value (unchanged). SAMPLE pills survive grouping.

### Feed — agnostic chronological with source-per-headline (WEB ONLY)
- The web feed is now a single newest-first **river across all sources**, with the **source next to each headline** (the original Onn style) — `render.mjs::feedChronological` + `sourceLabel`. Honest staleness stays per-item via the time/age.
- **The NATIVE feed is deliberately left as-is** (per-source sections, Stage 7). This is a per-client preference, not a reversal. **Open question flagged for the operator** (BACKLOG): revert the native feed to agnostic too? Not changed unilaterally.

### Video grid — cell-count config + click-to-pick channels
- Replaced the freeform size slider + draggable splitter with **cell-count** configuration (1 / 2 / 4 / 6 / 9) — `render.mjs::gridLayout` → `--grid-cols/--grid-rows`. Matches the native wall's tile-count model; the browser isn't the constrained S905Y4, so it goes past 4. **Feed width** is now a clean Settings control. Cell count + per-cell assignment + feed width persist (`mymts.web.prefs.v2`).
- **Intuitive channel selection:** click a cell → a picker listing every channel with an honest badge (green "plays in browser", amber "on the TV wall only", grey "offline") + a "Clear this cell" row, browser-playable first. The tile labels which channel it holds and shows a "click to change" chip; empty cells show "＋ Add channel".

### Video playback — diagnosis, honest course-correction
- **Diagnosed the HTTPS/mixed-content/CORS split** for all 10 live channels (full chain: master → every variant → real segments + AES keys), each classification **adversarially re-verified**. **Result: all 10 are HTTPS-clean AND CORS-allowed.** Mixed content is NOT why the tiles were blank for the current set — reported truthfully rather than asserting the convenient hypothesis (C3 applies to our own diagnosis).
- **Suspected real cause + fix:** hls.js `enableWorker:true` spawns a `blob:` worker the locked CSP blocks → switched to **`enableWorker:false`** (main-thread demux; no CSP widening). Added a **click-to-play** fallback for blocked autoplay.
- **Built the honest play-what-works system anyway** (correct + future-proof): helper `browser_playable` hint (`prober.py::classify_browser_playable` + migration 002 + `/api/channels`), client `browserPlayability` tri-state, and — the ground truth — a runtime load-failure that flips a tile to the honest **"Not playable in browser — on the TV wall"** state (catches CORS/geo/dead too). **No proxy** — the helper stays out of the video data path; the TV plays everything.

### Tests
- Helper **181** (+6: classifier + browser_playable round-trip). Web **17** (+6). App **246** (+5 TickerGrouping); debug APK builds.

### Security / standing rules
- CSP no less locked than before (no `worker-src`/CDN added; frame-src/object-src/script-src/base-uri/form-action all still locked). hls.js vendored+pinned. A1 held (video ≠ web reading). LAN-only / same-origin / credential-free / no-proxy.

### Operator action
- Helper redeploy required (migration 002 + classifier are helper-side): `scripts/deploy-helper.sh`; the web rework is static (same deploy rsyncs `web/`). Re-verify `/api/channels` exposes `browser_playable`.

## Web client reworked to mirror the wall + scrolling ticker + ESPN current-games sports (2026-06-06)

Hands-on feedback: the web client looked like a foreign dashboard, the ticker didn't scroll, the video grid wasn't sizable, and the sports ticker showed stale out-of-season fixtures (NFL preseason months away, in June). All fixed.

### Web client — mirrors the Onn wall
- Reworked layout to mirror `WallScreen`: scrolling ticker (top) + feed pane (left) + **2×2 video grid** (right), MyMTS dark theme. The dashboard channel-roster moved into Settings.
- **In-browser video:** vendored `hls.js@1.5.17` (`web/vendor/`, pinned, `script-src 'self'` — no CDN) plays the same public HLS the helper resolves via `/api/channels`; honest **offline** tiles when a stream won't load (`web/js/video.mjs`).
- **Sizable grid:** a Settings slider **and** a draggable splitter set the feed/grid split; persisted in localStorage.
- **Mouse settings (gear):** grid size, feed text size, feed source show/hide, channel roster — **browser-local view prefs** (the TV's settings live on-device; the web client has no write path — that's the deferred cross-platform-profiles fork).
- **CSP:** widened `connect-src`/`media-src` to `https:` for arbitrary stream CDNs (hls.js); `frame-src 'none'`/`object-src 'none'`/`script-src 'self'` stay locked. Video playback is NOT a web reader — **A1 closed door held**; client stays LAN-only / same-origin / credential-free.
- `render.mjs`: `filterHiddenSources` + `playableChannels` (pure, tested). Web tests: **11** (`node --test web/test/`).

### Ticker — real scroll (both clients)
- Web ticker is now a real horizontal **marquee** (CSS, markets↔sports rotation every ~18 s, hover-to-pause). Native `TickerStrip` already scrolled (`basicMarquee`) — confirmed, unchanged.

### Sports ticker — ESPN current-games only (the substantive fix)
- `ticker/sports.py::parse_scoreboard(now_ms=…)` now keeps only **current** games: in-progress always; a final within ~12 h back ("today"); scheduled within ~12 h forward ("later today"). **Far-future fixtures and stale results are dropped; a league with no current games is omitted entirely** — so NFL-in-June (only September fixtures) disappears while MLB-in-June (today's games) shows. Reproduced the bug against live ESPN, validated the fix against it. C3 honesty applied to sports.
- Per-status formatting: live → "AWY 4–6 HOM · Bot 9th"; final → "· Final"; scheduled-today → "AWY @ HOM · 7:30 PM ET". Defensive parse unchanged (never raises). 16 sports tests (`test_ticker_sports.py`). Helper suite **175**.

### Markets ticker
- Unchanged honest real/SAMPLE labeling (Stooq-from-NAS anti-bot block logged separately; CoinGecko BTC/ETH real).

### Operator action
- Helper redeploy required (the ESPN current-games filter is helper-side): `scripts/deploy-helper.sh`; re-verify `/api/ticker/sports` shows current-only (MLB now, no NFL preseason). The web rework is static — picked up by the same deploy (rsync of `web/`).

### Deferred (BACKLOG)
- Remote (non-LAN) web client; team-level sports curation; news-in-web-ticker; deeper web↔TV settings parity (needs per-client helper state / cross-platform-profiles fork). Stooq indices source still open.

### Standing rules
- A1 held (video ≠ reading); hls.js pinned + vendored.

## Curation & preferences — sports curation + ticker news + source toggles (2026-06-06)

The "tune what I see" controls in the settings menu. Some choices are FEEL-TEST items — the mechanism is built and configurable, flagged for the operator to confirm after using the wall on real hardware (see findings/14).

### Added
- **A. Sports curation (league-level, TV-side):** `WallSettings.hiddenLeagues` denylist; `HelperTickerSource.filterLeagues` drops hidden-league entries from the ticker's sports mode (helper still serves all leagues — no helper change, avoids the cross-platform-profiles fork). Offered set MLB/NFL/NBA/NHL (`CURATED_LEAGUES`). Empties to honest "scores unavailable".
- **B. Ticker news (third rotation mode, default OFF):** `WallSettings.tickerNewsEnabled`; `HelperTickerSource.nextMode` (pure 2-/3-cycle), NEWS mode + `newsEntries(feedItems, hiddenSources, cap)` builds newest-first headlines from non-hidden feed sources — drawn from the feed the wall already polls (no duplicate fetch), real (not sample), `Direction.NONE`. **No fabricated urgency/breaking** (RSS can't honestly flag it).
- UI: `SettingsOverlay` "Ticker news" on/off + "Sports leagues…" rows; `SourceFilterOverlay` parameterized (title/emptyText) and reused for leagues; `MenuState.SportsLeagueFilter` + `openLeagueFilter()`.
- `LineupStore` persists hiddenLeagues (JSON string-set) + tickerNewsEnabled; `toggleHiddenLeague`, `toggleTickerNews`. `CurationTest` (8 tests: league filter case-insensitive + status-line survival + all-hidden fallback, rotation 2-/3-cycle, news-entries newest-first/hidden-source/real/capped, news honest-empty).

### Changed
- `WallScreen` pushes curation into the running ticker via `HelperTickerSource.setCuration(...)` from an effect re-firing on settings/feed change.
- **C. Feed-source toggles** — reused from Stage 12 (the ticker-news source set is the same `hiddenSources`); not rebuilt.

### FEEL-TEST-CAVEAT items (build now, revisit after hands-on use)
- **Sports granularity:** league-level toggles ship; **team-level favorites** (operator's Chicago teams) deferred — confirm the right granularity after seeing scores flow.
- **News in the ticker at all / from which sources:** default OFF; confirm post-use. A dedicated ticker-news source subset (narrower than the feed) is a possible follow-on.

### Deferred (BACKLOG)
- Team-level sports curation; true breaking-news/urgency detection (needs a real signal, not faked); dedicated ticker-news source subset.

### Tests + standing rules
App suite **241** green (+8). A1 reverified (operates on already-fetched plain text; no new fetch/web/markup; no faked data). Focus model unchanged (modal overlays).

## Feed filtering — by source + recency (2026-06-06)

The feed gains operator-controlled filtering (the filtering half of feedback item B). Narrow it to chosen sources and/or a recency window; all over the plain-text the helper already serves — no new fetch, A1 holds. Free-text search was deliberately deferred (D-pad friction).

### Added
- `WallSettings.hiddenSources: Set<String>` (source **denylist** — new sources show by default) + `WallSettings.feedRecency` (`All` / `Last hour` / `Last 6h` / `Last 24h`).
- `FeedListBuilder.applyFilters(items, hiddenSources, recency, now)` — pure filter applied before grouping; `FeedListBuilder.distinctSources(items)` for the toggle list. `FeedFilterTest` (9 tests: denylist case-insensitive, recency windows + boundary, no-timestamp behaviour, source+recency compose, distinctSources ordering).
- `ui/menu/SourceFilterOverlay.kt` — D-pad toggle list of the feed's distinct sources (SELECT show/hide, BACK done). `MenuState.PendingSelection.SourceFilter` + `openSourceFilter()`.
- `SettingsOverlay` — new "Feed recency" cycle row + "Feed sources… (N hidden)" opener row.
- `LineupStore` — persists hiddenSources (JSON string-set) + recency ordinal; `cycleFeedRecency()`, `toggleHiddenSource()`; `encodeStringSet`/`decodeStringSet` codec.

### Changed
- `FeedPane` — accepts `hiddenSources` + `feedRecency`, applies `applyFilters` before `build`; reports the **filtered** item count to the focus model (so `feedIndex` can't overrun a filtered list); honest filter-aware empty state when filters hide everything.

### Honesty + focus
- Filter UIs are modal overlays — `WallFocusModel`'s zone graph is unchanged; the 49 navigation tests pass unmodified (no-trap invariants intact). Per-source freshness chips persist on remaining sections. Honest empty state distinguishes "filters hid everything" from "no data / stale".

### Deferred (BACKLOG)
- **Free-text search** — D-pad on-screen-keyboard friction for low ambient value; source + recency cover most of the "narrow the feed" value. Flagged for the operator to reverse.
- **Topic/keyword auto-classification** — foundation v2 idea; stays deferred (topic filtering = search, not auto-tagging).
- **Ticker-news reuse note** — the filter's "which items matter" notion is kept pure/parameterized so a future ticker-news mode can reuse it; that mode is a separate chapter.

### Tests
App suite **233** green (+9). `./gradlew :app:testDebugUnitTest`.

### Standing rules
- A1 held (filters operate on already-fetched plain text; no new surface).

## LAN web client — separate, credential-free, origin-isolated (2026-06-06)

A second client of the helper API for laptop/phone viewing on the home network — the *separate client* path the founding native-over-web decision sanctioned, NOT a reversal. LAN-only, credential-free, same-origin with the helper (no CORS), A1 closed door held. The remote version is scoped as a future chapter, not built.

### Added
- `web/` — dependency-free vanilla-JS SPA: `js/render.mjs` (pure label/group/honesty logic), `js/api.mjs` (same-origin, `credentials: "omit"` fetch wrappers), `js/app.mjs` (DOM wiring via `textContent` only), `index.html` (page CSP: `connect-src 'self'`, `frame-src 'none'`), `styles.css` (WyzeGrid-family dark), `README.md` (security posture + serving + cert note).
- Renders feed (sectioned by source, newest-first, plain-text summaries), ticker (markets + sports with SAMPLE pills + stale notes preserved), channel live/offline/unknown status, and health — same honesty discipline as the native app.
- `web/test/render.test.mjs` — 9 pure-logic tests via `node --test` (no toolchain): direction glyphs, sample/stale never-as-live, source grouping/ordering matching the native `FeedListBuilder`, relative time, honest channel status + empty states.
- Helper: config-gated static mount at `/app` (`WEB_CLIENT_DIR`, **off by default**) — serves the SPA same-origin so no CORS is opened; mounted last so it can't shadow `/api/*` or `/health`. `helper/tests/test_web_client_mount.py` (3 tests: off-by-default 404, served-when-configured, missing-dir soft no-op).

### Security posture (confirmed)
LAN-only (helper's bare LAN IP, off `*.<DOMAIN>`, not tunneled, not behind Cloudflare Access); credential-free (no login/cookies/session/tokens); same-origin → **no CORS**; A1 held (no article fetch/iframe — CSP-enforced + `textContent`-only rendering); helper core unchanged (SSRF fetcher, parsers, non-root/read-only posture untouched; only the default-off mount added).

### Single-command test runs
- Web: `node --test web/test/` (9 pass).
- Helper: `cd helper && .venv/bin/python -m pytest` (167 pass, +3).

### Deferred (BACKLOG)
- **Remote-accessible** web client — separate public origin, own threat-model, auth story (+ the credential tradeoff), rate-limiting. Not built; LAN-only sidesteps it.
- In-browser HLS video grid (`hls.js`) — LAN follow-on; the live grid is on the TV wall.

### Operator action
To serve the LAN client, set `WEB_CLIENT_DIR=/app/web` in the helper `.env` and redeploy (pull → rebuild → restart); browse to `https://<LAN_IP>:8443/app/` on the LAN (click through the self-signed-cert warning once). If left unset, the helper is unchanged. Standing rules: helper non-root / read_only / cap_drop ALL / dedicated bridge — **never any unrelated container on the host**.

## Pre-migration hygiene pass (2026-06-06)

Tidy-the-slate pass before the afternoon migration to the dedicated MyMTS box. No features, no behaviour changes — orphaned-process cleanup + doc reconciliation only.

### Cleaned
- **Killed a stale background shell** (PID 50718) that had run **3d 16h** in an `until adb … logcat … grep "EV=STATE…to=LIVE…slot-3"; do sleep 3; done` loop — a navigation Checkpoint-B screencap poller whose exit condition never matched. Confirmed terminated, no re-spawn over repeated checks, and no other orphaned pollers / streaming `logcat` handles / stray `caffeinate` left running against `.182`. (`.182` untouched — `logcat -d` is a local read-only dump.) Removed the `/tmp` screencap leftover.
- **Run-artifact hygiene:** working tree already clean; `.gitignore` confirmed comprehensive (`.gradle/`, `*.apk`/`*.aab`, `*.keystore`/`*.jks`, TLS private keys all excluded). `docs/findings/runs/` (53 tracked files) retained as durable soak evidence; `helper/uv.lock` correctly tracked.

### Debt
- **Dual-uvicorn tidy-up: considered, deliberately deferred** (logged in BACKLOG). The helper entry point's `lifespan="off" if cfg.port else "on"` logic is load-bearing (it stops the pollers — now including the ticker pollers — double-starting across listeners); refactoring it the same day as a migration whose prerequisite is a clean helper redeploy is the wrong risk/reward, and the dual-listener wiring isn't fully unit-exercisable. Functionally harmless today. No code change.
- No TODO/FIXME/dead code found in source.

### Docs reconciled
- Corrected a stale test-count claim: `KioskPolicyTest` is **7** cases, not 8 (CHANGELOG, ARCHITECTURE §17, findings/12); app suite total is **224**.
- **Live-vs-built helper state made explicit:** the running NAS helper is two chapters behind `main` — the 13 feed sources (`069f783`) and the `/api/ticker/*` endpoints (`81b06c9`) are committed but **not yet deployed**; the OPERATIONS migration-runbook prerequisite now states this plainly (live helper still serves 4 sources / no ticker until the redeploy).
- BACKLOG: the app-vs-app foreground-conflict entry marked **resolved by the Model A decision**; the kiosk story updated from "pending hardware" to **"scaffolding built; on-hardware validation STAGED."**
- Swept for hallucinated references (invented test names, a non-existent "previous reclaim chapter", invented adb actions) — none present.
- Section numbering verified: ARCHITECTURE §1–§18 and THREAT-MODEL headings have no duplicates or gaps.

### Verified
- App suite **224** green; helper suite **164** green. Signed-install path + kiosk scaffolding intact.

## Kiosk / foreground / boot scaffolding + provisioning readiness (2026-06-06)

The dedicated MyMTS Onn box arrives this afternoon. This chapter builds the kiosk/foreground/boot **code** + the provisioning runbook so the migration is execution, not build-from-scratch; **on-hardware validation is explicitly STAGED for that session** (nothing hardware-dependent is claimed working). Model A: one kiosk app per box — the new box runs MyMTS as the sole kiosk; `.182` stays WyzeGrid's and is untouched.

**Load-bearing safety property:** kiosk mode is **opt-in, OFF by default**. The same signed APK on a non-kiosk box (notably `.182`, where a MyMTS dev install may linger) must NOT start a foreground service or autostart on boot. Only the provisioning runbook flips it on for the dedicated box (`adb shell am start -n com.mymts/.MainActivity --ez kiosk true`).

### Added
- `kiosk/KioskPolicy.kt` (pure Kotlin, unit-tested): boot-action allowlist (`BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `QUICKBOOT_POWERON`, HTC quickboot); `shouldStartOnBoot(action, kioskEnabled)` both-conditions gate; `shouldRelaunchActivity()`; crash-loop backoff (`restartBackoffMs` 0/2/5/15/30/60 s cap + `isSameCrashStreak` + `STREAK_RESET_MS`=5 min).
- `kiosk/KioskPrefs.kt`: SharedPreferences flag, `DEFAULT_ENABLED=false`; static `isEnabled(context)`.
- `kiosk/KioskService.kt`: foreground service, `START_STICKY`, ongoing low-importance notification + channel, `onTaskRemoved` relaunch (gated on prefs), `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` guarded by API 34; `startIfEnabled`/`stop`/`launchWall` companions (no-op when kiosk off). No coexistence/foreground-reclaim logic (Model A).
- `kiosk/BootReceiver.kt`: gated reboot relaunch (`shouldStartOnBoot` AND `KioskPrefs.isEnabled`).
- `MainActivity.kt`: `--ez kiosk true|false` provisioning hook (normal launch never changes kiosk state); `startIfEnabled` on launch.
- `AndroidManifest.xml`: `RECEIVE_BOOT_COMPLETED` + `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` + `POST_NOTIFICATIONS`; `<service>` (`exported=false`, `specialUse` + justification) + `<receiver>` (the 4 boot actions). Holding the permissions starts nothing — the prefs gate does.
- 7 `KioskPolicyTest` cases (boot allowlist incl. spoofed/null rejected, the both-conditions gate, relaunch decision, backoff + streak window).

### Changed
- `ONN-BOXES.md` — pre-staged `onn-mymts` row (IP `TBD-at-provision`, role MyMTS sole kiosk); `onn-office` note updated (lingering MyMTS install is inert — kiosk OFF).
- `OPERATIONS.md` — ordered, runnable "Migration runbook" replacing the checklist stub: helper-redeploy prerequisite (13 feed sources + ticker endpoints verified), ADB connect, DHCP, ONN-BOXES fill-in, signed-install via `deploy-app.sh --device NEWIP` (release-signed gate, baked HTTPS helper URL `https://<LAN_IP>:8443` pinned cert), wall verification, opt-in kiosk-enable.

### STAGED for the migration session (explicitly NOT validated yet)
Foreground hold over hours on the new box; boot-receiver via a real reboot; low-memory survival; full runbook end-to-end; the accumulated nav + feed + config + ticker feel-test (now on the MyMTS box, not borrowed `.182`); helper redeploy (prerequisite). Unverified-until-migration.

### Safety
Opt-in gating keeps the same signed APK inert on `.182` (no foreground service, no boot autostart unless kiosk is provisioned-on) — adversarially verified (not refuted, 12 evidence citations). No coexistence/reclaim logic (Model A). No new secret (the kiosk flag is a boolean; the release keystore stays the only secret, never committed).

### Tests
App suite green (224 tests) incl. the 7 new kiosk policy tests; APK builds; manifest merges clean.

## Ticker — real markets data + sports mode + markets/sports rotation (2026-06-05)

The ticker now shows REAL keyless data and alternates between a markets mode and a sports mode; honest SAMPLE is retained where no free keyless source exists; no new secret was added. Every source was verified live through the helper's SSRF-safe fetcher before commit — only clean public endpoints were built.

### Added
- **Helper package `mymts_helper/ticker/`** with strict defensive parsers + honest fallback:
  - `__init__.py` — `TickerEntryDTO(symbol, display, direction, is_sample)`; `DIR_{UP,DOWN,FLAT,NONE}`; `TICKER_SCHEMA_VERSION=1`.
  - `markets.py` — `parse_stooq_csv()` + `parse_coingecko()` strict parsers; `build_snapshot()` mixes real + sample honestly per symbol and always emits the full canonical list; `all_sample_snapshot()` for pre-poll / phantom.
  - `sports.py` — `parse_scoreboard()` strict ESPN JSON parser (caps games/league, sanitizes scores); `SAMPLE_SPORTS` slate (`is_sample=true`); `no_games_entry()` (true state, `is_sample=false`).
  - `pollers.py` — `MarketsPoller` + `SportsPoller`: async loops, in-memory latest snapshot (ephemeral — no DB table/migration, no cross-thread sqlite exposure), per-source isolation, `real_as_of` tracking.
  - `api.py` — `GET /api/ticker/markets` + `/api/ticker/sports`, envelope `{schema_version, mode, as_of, stale, entries[]}`. `stale` true only when real data aged past 15 min; all-sample pre-poll is NOT stale.
  - `config.py` — `markets_poll_interval_seconds=120`, `sports_poll_interval_seconds=180` (env-overridable).
- **App** — `TickerSnapshot.kt`; `HelperClient.fetchMarketsTicker()/fetchSportsTicker()/parseTicker()` (schema_version pinned, missing `is_sample` defaults TRUE = fail-safe toward honesty, unknown direction → FLAT); `HelperTickerSource.kt` (polls both endpoints @60 s, rotates markets 22 s / sports 14 s, publishes via StateFlow, pure `entriesFor()` honest-fallback rules); `TickerEntry.Direction.NONE`.
- Tests: helper **164 passed** (+24: `test_ticker_markets`, `test_ticker_sports`, `test_ticker_api` incl. phantom + blocked-egress all-sample). App **217 passed** (+14: `HelperClientTickerParseTest`, `HelperTickerSourceTest`).

### Changed
- `WallScreen.kt` — swapped `SampleTickerSource` → `HelperTickerSource(client)` (SampleTickerSource retained as the honest fallback).
- `TickerStrip.kt` — `Direction.NONE` renders no arrow (sports scores are non-directional).

### Source investigation (verified live through the SSRF-safe fetcher before commit)
- **Markets:** Stooq keyless CSV (indices/FX/gold) + CoinGecko keyless (BTC/ETH). Brent/WTI/10Y-UST stay honest SAMPLE (no clean free keyless source). Yahoo Finance evaluated and rejected (unofficial/ToS-gray, blocks datacenter IPs).
- **Sports:** ESPN public scoreboard JSON (MLB/NFL/NBA/NHL), keyless. TheSportsDB evaluated; ESPN chosen (richer, keyless). ESPN endpoint is undocumented/public — ToS-gray, operator gave the nod for personal non-commercial use.
- **No API key for anything → the helper holds NO NEW SECRET.**

### Honesty (C3)
Real shown real (pill dropped); sample/unsupported kept sample (pill); stale real surfaced via the envelope `stale` flag; unreachable → honest sample (markets) / "scores unavailable" (sports), never frozen old numbers as current. `is_sample` travels per-entry helper→TV untouched.

### Adversarially verified
Two independent verifiers (not refuted): (1) the ticker never presents sample/stale data as live-real in any path; (2) the new sources go through the SSRF-safe fetcher unchanged, add no secret, and the parsers are strict/fail-closed. Full record: `docs/THREAT-MODEL.md §"Ticker real data — markets + sports external sources"`.

### Operator action
Helper redeploy required to serve the new endpoints: pull → rebuild → restart → verify `/health` 200 and `/api/ticker/markets` + `/api/ticker/sports` return valid envelopes. Deploy profile unchanged: **non-root, read_only, cap_drop ALL, dedicated bridge — never any unrelated container on the host**.

### Deferred
- Per-team/league curation UI (default leagues + mechanism shipped). Sample-only symbols (Brent/WTI/10Y). BACKLOG items D + G marked DONE.

### Standing rules
- A1: every external response treated as hostile — same SSRF-safe fetcher, strict parse, fail-closed, bounded.

## Feed sources — expanded to 13 reputable balanced RSS sources + SQLite cross-thread fix (2026-06-05)

The helper feed seed grows from 4 to 13 verified public RSS sources with a deliberate left/center/right balance, and the intermittent SQLite cross-thread 500 on `/api/feed` is fixed. Every new source was verified by fetching through the helper's real SSRF-safe fetcher and parsing through the real defensive parser before commit — only feeds returning valid plain-text items were seeded.

### Added
- 9 new seeded RSS sources in `feeds/seed.json`: PBS NewsHour, Christian Science Monitor, CBS News, NBC News, Politico, Bloomberg Markets, The Dispatch, National Review, Reason. (Original 4 retained: BBC World, Al Jazeera, Guardian World, NPR World.)
- `db.connection_scope(path)` — a context manager that opens, uses, and closes the SQLite connection inside the sync route body (one `run_in_threadpool` call = one thread), so the connection never crosses an anyio threadpool thread boundary.
- 2 shipped-seed guard tests in `tests/test_feeds_seeder.py` — all-https + unique URLs/labels validation; seeds every entry; originals retained.
- 2 SQLite concurrency regression tests in `tests/test_api.py` — `/api/feed` and `/api/channels` hammered by 8 client threads × 64 requests; all 200 + stable envelope asserted.

### Fixed
- **SQLite cross-thread bug** (resolves the logged BACKLOG entry). Root cause: the FastAPI `Depends()` yield-dependency (`_conn`) drove the connection's open and close through two separate `run_in_threadpool` calls that could land on different threadpool threads — closing a `sqlite3.Connection` on a different thread than it was opened on raised `ProgrammingError` → HTTP 500. `connection_scope()` keeps the whole lifecycle on one thread. The fix keeps sqlite's thread guard ON (no `check_same_thread=False`).

### Changed
- `feeds/api.py` and `channels/api.py` — removed the `_conn()` yield-dependency; both routes open the connection via `db.connection_scope(db_path)` inside the route body. No change to the SSRF fetcher, parser, store, or response envelope.

### Verification
Every seeded feed was fetched through the real SSRF-safe fetcher and parsed through the real defensive parser before commit. Two candidates were tested and deliberately left out:
- **Associated Press** (`apnews.com/index.rss`): official feed returns a SAXParseException; the only working feed is a third-party mirror (feedx.net), which would misrepresent provenance → rejected on honesty grounds.
- **Reuters** (`reutersagency.com/feed`): returns HTML; Reuters discontinued public RSS years ago → no clean public feed exists.
Verified-but-omitted (operator can swap in via `seed.json`): ABC News, CNBC, The Hill, Axios, The Atlantic, Vox, MarketWatch, Washington Examiner.

### Tests
Helper suite: **140 passed** (was 136). The new regression tests confirm `/api/feed` and `/api/channels` are stable under concurrent threaded load.

### Deferred
- Per-source bias tags / bias-labeling UI — future backlog idea; this chapter chooses a balanced set without labels.
- Dual-uvicorn-instance tidy-up — separate low-priority BACKLOG sub-item, independent of this fix.
- AP / Reuters — reconsider if either ships a clean public RSS feed.

### Operator action
Helper redeploy required to pick up the new sources: pull → rebuild → restart → verify `/health` returns 200 and `feeds.sources_count` reads **13**. Deploy profile unchanged: **non-root, read_only, cap_drop ALL, dedicated bridge — never any unrelated container on the host**.

### Standing rules
- Helper runs on its own bridge network.
- A1: every source treated as hostile, parsed defensively, served as inert plain text — same parser path, more sources.

## UX & Config — configurable feed width / font / side, settings overlay in menu (2026-06-04)

The operator can now tune the wall layout to their space and eyesight without code changes. Three new discrete-preset settings (feed width, feed font scale, feed side) are exposed in a Settings overlay accessed from the side menu. Each setting cycles through operator-chosen presets; LEFT/RIGHT on a focused setting advances to the next preset, applies it live, and persists it to the device's SharedPreferences. The focus model derives LEFT/RIGHT spatial rules from the configured feed side — when the feed is on the right, the grid's spatial-right direction spills to the feed (not off-wall), and the menu slides in from the right instead. Navigation stays spatially correct and trap-free in both orientations.

### Added
- `data/settings/WallSettings.kt` — data class holding three operator-tunable settings: `feedWidth` (enum `Narrow=0.22, Default=0.28, Wide=0.36` screen fraction), `feedFontScale` (enum `Small=0.88, Default=1.0, Large=1.18` multiplier), and `feedSide` (enum `Left` or `Right`). Internal helpers `feedWidthFromOrdinal()` / `feedFontScaleFromOrdinal()` / `feedSideFromOrdinal()` apply safe-fallback defaults for out-of-range or missing storage values.
- `data/settings/WallSettingsTest.kt` — 17 invariant tests pinning default values, enum ordering, legibility floor (Small `multiplier >= 0.85`), overflow ceilings (Wide `fraction <= 0.4`, Large `multiplier <= 1.25`), ordinal robustness (out-of-range fallback), copy semantics, and field-by-field equality.
- `ui/menu/SettingsOverlay.kt` — centered WyzeGrid-family modal with three focusable `SettingRow`s (Width, Font, Side). UP/DOWN navigate between rows via Compose focus traversal. LEFT/RIGHT on a focused row cycle the enum forward, apply the new value live to `FeedPane` / `WallScreen`, and persist to SharedPreferences. SELECT also cycles forward. BACK dismisses the overlay.
- `MenuState.PendingSelection.Settings` + `openSettings()` — menu state variant and dispatcher for opening the Settings overlay over the side menu.
- 11 new `WallFocusModelTest` cases for feed-right orientation — verify spatial-rule mirrors (LEFT/RIGHT semantics flip when feed is right), FEED↔GRID round-trip with preserved indices in the right-side layout, no-trap exitability invariant in the swapped layout, and ticker LEFT/RIGHT only the gesture toward the feed's outer edge opens the menu.

### Changed
- `data/lineup/LineupStore.kt` — `State<WallSettings>` added. New methods `updateWallSettings()`, `cycleFeedWidth()`, `cycleFeedFontScale()`, `cycleFeedSide()` read current values, compute the next enum ordinal, persist as integer keys (`KEY_FEED_WIDTH`, `KEY_FEED_FONT`, `KEY_FEED_SIDE`) to SharedPreferences, and emit the new state. `readWallSettingsFromDisk()` applies per-component default fallback if a key is missing or the stored ordinal is out of range.
- `ui/nav/WallFocusModel.kt` — `apply()` signature extended: `feedSide: FeedSide = FeedSide.Left` parameter (default preserves the existing 38 navigation tests unchanged). Feed zone inner/outer edge gestures now derive from `feedSide`. Grid zone LEFT/RIGHT branches use `toFeed` / `intoGrid` variables that flip based on `feedSide`. Ticker LEFT/RIGHT: only the gesture toward the feed's outer edge opens the menu.
- `ui/menu/MenuOverlay.kt` — accepts `feedSide` parameter. The side panel now slides in from the feed's outer edge (LEFT side of screen when `feedSide=Left`, RIGHT side when `feedSide=Right`). Added "Settings" row to the menu + "WALL" section title.
- `ui/wall/FeedPane.kt` — accepts `fontScale: Float` parameter (default `1.0`). All `sp` values in title, summary, time chip, and section headers are multiplied by `fontScale`. Legibility floor at 0.88× is asserted in `WallSettingsTest`.
- `ui/wall/WallScreen.kt` — reads `wallSettings` from `LineupStore.wallSettings`. Passes `feedSide` to `WallFocusModel.apply()` for spatial-direction derivation. Renders the Row as `Row{FeedPane, Divider, VideoGrid}` when `feedSide=Left`, or `Row{VideoGrid, Divider, FeedPane}` when `feedSide=Right`, with both children defined once so a feed-side swap does not recreate the `FeedRepository` or `StreamPlayerManager`. Renders `SettingsOverlay` when `PendingSelection.Settings`.

### Tests
28 new app tests — 17 in `WallSettingsTest` (defaults, ordering, legibility floor / overflow ceiling invariants, ordinal fallback on missing/out-of-range, copy) + 11 in `WallFocusModelTest` (feed-right orientation mirror checks, spatial-rule derivation, no-trap and round-trip preservation). Full app suite: **203 tests, all green**. Navigation invariants verified in both orientations (Left and Right feed side).

### Deferred (deliberately NOT in this chapter)
- **Overall UI sizing / global density scale** — operator was asked the vision question per chapter §5 and chose to defer to BACKLOG for revisit on the new MyMTS box; logged in `docs/BACKLOG.md §"Overall UI sizing"`.
- **Feed filtering / search UI** — separate chapter; logged in BACKLOG.
- **Section collapse / jump-by-source** — logged in BACKLOG as the operator-feedback-driven follow-on.
- **Ticker modes** — sample data only in v1; deferred to the ticker-modes chapter.
- **Kiosk story** — long-uptime foreground watchdog + boot receiver; deferred to the new MyMTS box.
- **In-app full-article web reading** — closed door, permanent.

### Adversarially verified — A1 + B1 boundaries unchanged
Settings persistence uses SharedPreferences integer ordinals only — **no secrets, no PII, no absolute paths**, no new endpoints, no fetch. `SettingsOverlay` is pure Compose `Text` + focusable `Row` widgets. LEFT/RIGHT on a focused setting calls `LineupStore.cycle*()` (local SharedPreferences write only). **No new web-fetch, no new WebView, no new HTML render.** The feed-expand path is unchanged. `WallFocusModel.apply()` is still pure Kotlin. Full entry: `docs/THREAT-MODEL.md §"UX & Config — A1 + B1 reverify (2026-06-04)"`.

### Operator-validated
- **Feel-test STAGED** for the operator's next at-the-box session — folded into the existing nav-feel-test checklist in `docs/OPERATIONS.md`. Adds Width/Font/Side cycling steps + D-pad navigation checks in the feed-right orientation.

### Standing rules
- Helper: non-root, read_only, cap_drop ALL, dedicated bridge — unchanged.

## Feed restructure — sectioned by source + per-source honest staleness (2026-06-04)

The continuous chronological feed scroll is replaced with a sectioned list grouped by news source. Each source (BBC, Guardian, Al Jazeera, NPR, …) gets its own labelled section with a per-source freshness chip applying Trust Bar **C3** at the section layer — the operator sees at a glance which sources are flowing and which have gone quiet. Items within each section stay newest-first (published time when present, fetched time as fallback). The flat-item invariant is preserved: `WallFocusModel`'s `feedIndex` still traverses 0..itemCount-1 in visible list order; headers are visual only, never focusable.

### Added
- `ui/wall/feed/FeedListBuilder.kt` — pure transform from `List<FeedItem>` to `List<FeedListEntry>` (sealed `Header | Item`). Groups by source (case-insensitive alphabetical), sorts within section newest-first by published time falling back to fetched time. Per-section freshness classified (`Fresh` < 2h, `Warm` < 12h, `NotUpdating`, `Unknown`).
- `ui/wall/feed/FeedListBuilderTest.kt` — 18 invariant tests pinning grouping, sorting, freshness boundaries, flat-item order = focus traversal order, `entriesIndexForFocus` mapping (entries-list index to focus index past headers).
- `SectionFreshness` enum — per-section staleness signal (`Fresh`, `Warm`, `NotUpdating`, `Unknown`) applied independently per source rather than once for the whole feed.
- `PickerGroupTest.kt` — 6 tests pinning live/offline position math for the picker's new orientation chip.
- `pickerGroupAt()` helper in `ChannelPickerOverlay` — computes 1-based position within the live/offline group for the cycler cursor, drives the slot header's "LIVE n/m" / "OFFLINE n/m" orientation chip.

### Changed
- `ui/wall/FeedPane.kt` — **rewritten**. Renders the sectioned `LazyColumn`: per-source `SectionHeader` (uppercase source name, item count, freshness chip with coloured age badge) + `FeedRow` with per-item time chip (source label removed from rows since it now lives in the header). Auto-scroll uses `entriesIndexForFocus` to map the focus model's `feedIndex` into the entries-list position even with intervening headers. Headers are visual only — never focusable; focus model unchanged.
- `ChannelPickerOverlay` slot header — now shows "SLOT n · LIVE i/j" or "SLOT n · OFFLINE i/j" — the orientation chip flips color and label text as the cursor crosses the live↔offline boundary so the operator always knows which group they're in.

### Tests
24 new app tests:
- **FeedListBuilderTest (18):** sections alphabetical + stable, within-section newest-first, freshness computed from newest item's age, boundaries sharp at thresholds, flat-item order matches focus traversal, `entriesIndexForFocus` maps past headers, out-of-range focus returns -1, flat item count equals input item count, `newestAgeMs` on header equals NOW minus newest item's timestamp.
- **PickerGroupTest (6):** cursor position on live/offline boundaries, all-live and all-offline lists, group-size and position-within-group correct at edges.
- **Navigation chapter (38 WallFocusModelTest cases):** re-run **green** without change. The focus model is unchanged; the flat-item invariant holds (test "flat-item order matches focus traversal order" pins this); no-trap and state-preservation properties survive the restructure.

Full app test suite: **all green** (175 tests including 18 new FeedListBuilder + 6 new PickerGroup + 38 navigation).

### Deferred (deliberately NOT in this chapter)
- **Feed filtering / search UI** — separate later chapter; logged in BACKLOG.
- **Section collapse / jump-by-source** — logged in BACKLOG as the operator-feedback-driven follow-on.
- **Configurable feed width, font, item-count-per-scroll** — typographic polish; "UX & config" push.
- **Ticker markets / sports modes** — sample data only in v1; deferred to the ticker-modes chapter.
- **Kiosk story** — long-uptime foreground watchdog + boot receiver; deferred to the new MyMTS box per Model A.

### Adversarially verified — A1 boundary unchanged
The feed-restructure introduces **no** new web-fetch, **no** new WebView, **no** new HTML render. The new code is pure Kotlin (grouping logic in `FeedListBuilder`) + Compose `Text` widgets (rendering headers and items). Section headers are inert-text labels. SELECT-on-focused-item expands the item's `summary` (already HTML-stripped by the helper's `feeds/parser.py`) by flipping the `Text` widget's `maxLines` — no fetch, no markup render. The feed-restructure adds **no new input surface**. Full entry: `docs/THREAT-MODEL.md §"Feed restructure — A1 reverify (2026-06-04)"`.

### Operator-validated
- **Feel-test STAGED** for the operator's next at-the-box session — folded into the existing nav-feel-test in `docs/OPERATIONS.md`. The sectioned layout and per-source freshness chips work; the skim-ability and muscle-memory delta on the real Onn remote is the operator's call at the box.

### Standing rules
- Helper: non-root, read_only, cap_drop ALL, dedicated bridge — unchanged.

## Whole-wall D-pad navigation — global focus model + per-zone actions (2026-06-04)

A single coherent D-pad navigation graph now covers every zone on the wall. The pure focus model (`WallFocusModel`) computes zone-to-zone transitions as a testable function, eliminating navigation traps and spatial inconsistencies that plague TVs where each layer re-invents focus independently. The per-zone action layer (cell SELECT → controls overlay, article SELECT → in-place summary expansion, ticker SELECT → pause/resume) layers orthogonally on top so navigation and action are decoupled; a future change to either layer doesn't require unpicking the other.

### Added
- `ui/nav/WallFocus.kt` — focus data class (`active`, `feedIndex`, `gridIndex`, `lastLowerZone`, `feedExpanded`, `tickerPaused`) + `WallZone` enum (Ticker, Feed, Grid).
- `ui/nav/WallFocusModel.kt` — pure function emitting `NavResult` sum type (`Focus`, `OpenMenu`, `OpenSlotControls`, `BackBubble`, `Stay`). No Compose, no Android; the entire navigation graph is testable from a single point.
- `nav/WallFocusModelTest.kt` — 38 invariant tests pinning no-traps (every zone reachable + exitable), spatial sense (UP from grid/feed reaches ticker; LEFT from grid col 0 lands on feed), state preservation (switching zones preserves the other zone's index), BACK collapsing feed-expand first then bubbling, and ticker SELECT toggling `tickerPaused`.
- Focus indicators on the wall: each focused zone (feed item, grid cell, ticker) is adorned with a WyzeGrid-family green-accent border so the operator sees D-pad movement from 10 feet. PAUSED chip in the ticker when paused.

### Changed
- `ui/wall/WallScreen.kt` — owns focus state (`WallFocus`), dispatches every D-pad event + SELECT + BACK through `WallFocusModel.apply()`, wires per-zone action results into the appropriate state holders (menu, slot controls overlay, ticker pause flag).
- `ui/wall/FeedPane.kt` — accepts `focusedIndex` (renders green-accent on that item) + `expandedIndex` (shows MORE of the helper's already-fetched plain-text `summary`; never any web fetch, WebView, or HTML render). Expanded rows handle BACK → collapse semantics. Expanded type ramps up (15 → 18 sp title, 12 → 14 sp summary) for 10-ft read.
- `ui/wall/VideoGrid.kt` — accepts `focusedCellIndex`, applies green-accent border to the focused tile. `gridColumnsFor(slotCount)` extracted as the single source of truth for column math (same logic the focus model uses for D-pad row/column navigation).
- `ui/wall/TickerStrip.kt` — accepts `focused` (green-accent border) + `paused` (marquee animation disabled, PAUSED chip surfaced). SELECT toggles `paused` via the model.
- Modal overlay gating — `SlotControlsOverlay` / `ChannelPickerOverlay` now render on `pending != null` rather than `menu.isOpen && pending`, so a SELECT on a focused grid cell opens the controls modal without dragging the side menu in.
- Root focus restoration — `DisposableEffect(menu.isOpen, menu.pendingSelection)` instead of just `menu.isOpen`, so the wall reclaims focus after a modal opened directly from a grid cell dismisses.

### Tests
Invariants pinned across the 38 navigation tests:
- **No traps.** Every zone is reachable from every other in ≤4 moves; every zone is exitable in at least one direction.
- **Spatial sense.** UP from feed/grid reaches ticker. LEFT from grid column 0 lands on feed at preserved `feedIndex`. DOWN from ticker returns to `lastLowerZone` (the zone the operator came up from), not always the feed.
- **State preservation.** Switching zones does NOT clobber the other zone's index. FEED → GRID → FEED round-trips return to the same feed item; GRID → TICKER → GRID preserves `lastLowerZone`.
- **BACK collapsing.** Feed with `feedExpanded=true` BACK collapses in place; feed with `feedExpanded=false` BACK bubbles.
- **Action layer non-trap.** Adding SELECT actions (grid → OpenSlotControls, feed → expand, ticker → pause) preserves the no-trap property; every zone still exits via a directional intent.
- **Ticker pause persistence.** `tickerPaused` survives zone transitions and round-trips; toggling SELECT on the ticker and leaving doesn't reset it.

Full app test suite: **all green** (151 tests including the 38 new navigation tests). Helper test suite: unchanged.

### Deferred (deliberately NOT in this chapter)
- **Kiosk story** — long-uptime foreground watchdog + boot receiver. Deferred to the new MyMTS box (in transit) per Model A.
- **Feed list/sections restructure** (BACKLOG item B). Operator's "the feed is a continuous individual-scroll, unintuitive" call needs structural decisions about grouping; this chapter delivers the navigation prerequisites.
- **Ticker markets/sports modes** (BACKLOG item D). Sample data only in v1.
- **QR-to-phone / send-to-phone full article read** — newly logged in BACKLOG as a closed-door-compatible alternative for richer reading; not built here.

### Adversarially verified — A1 boundary holds at feed-expand
The feed-expand path was independently verified to introduce **no** web-fetch surface and **no** HTML-render surface. The verifier checked the pure model SELECT semantics, the `FeedPane` render code, the `FeedRepository` / `HelperClient` call graph, and the helper-side parser. Result: feed-expand only flips a Boolean that changes `Text` widget `maxLines`; the summary text is the helper's already-stripped plain-text product. Full entry: `docs/THREAT-MODEL.md §"Navigation chapter — feed-expand A1 confirmation (2026-06-04)"`.

### Operator-validated
- **Feel-test STAGED** for the operator's next at-the-box session — see `docs/OPERATIONS.md §"Navigation chapter feel-test on the remote"`. The focus model logic works; the spatial feel on the real Onn remote is the operator's call at the box (WyzeGrid's 80-min foreground reclaim on `.182` defines the window).

### Standing rules
- Helper: non-root, read_only, cap_drop ALL, dedicated bridge — unchanged.

## At-the-box finale Step 4 — `.182` restored to WyzeGrid + kiosk deferred (2026-06-04)

The away-from-box + safe-on-`.182` roadmap is now complete. The kiosk / foreground-coexistence story waits for the new MyMTS box (hardware in transit).

### Decision recorded — Model A (one kiosk app per box)
`onn-office` (`.182`) is WyzeGrid's permanent home for the cameras kiosk; MyMTS gets its own dedicated Onn box. The kiosk / foreground-coexistence work is therefore deferred — running it on `.182` would reintroduce the Stage 1 / Stage 2 two-watchdog thrash on the camera box, pointlessly.

### `.182` restored
- Launched `com.wyzegrid/.MainActivity` to put WyzeGrid back in the foreground.
- `topResumedActivity = com.wyzegrid/.MainActivity` (cameras UI).
- `com.wyzegrid/.WatchdogService` `isForeground=true` (the persistent watchdog, alive).
- MyMTS install (release-signed, on the known-good APK from Step 3) remains on `.182` — harmless, will be evicted by WyzeGrid's WatchdogService over the next ~80 min per the Stage 1 finding, and that's fine because the wall has its own box coming.

### Added
- `docs/OPERATIONS.md` — new section "New MyMTS box provisioning (pending — hardware in transit)" with the explicit checklist (DHCP reservation, `ONN-BOXES.md` row, signed-install via `deploy-app.sh`, kiosk story validation: holds the foreground ≥ 80 min, survives reboot, survives low-memory). Also documents why the kiosk work is *not* attempted on `.182`.
- `docs/BACKLOG.md` — two new entries:
  1. **Kiosk / foreground / boot story — pending the new MyMTS box.** Model A confirmed; build + validate the kiosk story on the dedicated MyMTS box when it arrives.
  2. **Helper feeds API — SQLite cross-thread bug** (latent since the TLS dual-listener; surfaces as occasional HTTP 500 on `/api/feed`). Noted with the small-fix path: thread-local connection or aiosqlite; also revisit collapsing the dual-uvicorn-instance architecture now that HTTP is gone.

### Standing rules
- **WyzeGrid foreground + `WatchdogService` healthy on `.182`** at session end — verified by `dumpsys activity activities` + `dumpsys activity services`.
- App tests green; helper tests green: 136. No code changes in this commit (docs + BACKLOG only).
- **The at-the-box session is now complete.** Remaining work waits for the new MyMTS hardware.

## At-the-box finale Step 3 — rollback live-test verified on `.182` (2026-06-04)

The Stage 6 signed-update + auto-rollback path was operationally verified on real hardware. The dry-run had already exercised the decision logic; this session exercised the end-to-end install + health-gate + rollback flow with a deliberately-failing build.

### Sequence on `.182`

1. **Baseline established.** `./scripts/deploy-app.sh --archive-dir $HOME/.mymts/release`. Build → archive `mymts-0.0.0+5576551-20260604T230502Z.apk` → install → launch → health-gate **PASS** (`117 EV=TILE_READY events, 0 dead`) → promoted to known-good.
2. **Deliberately-failing build pushed.** `gradle.properties` temporarily flipped to `MYMTS_HELPER_BASE_URL=https://<LAN_IP>:9999` (unreachable). Force-clean rebuild (gradle's incremental build had been masking the URL change — see gotcha below) + deploy.
3. **Auto-rollback fired.** Health gate returned `FAIL_NOT_READY` (`0 EV=TILE_READY events; need ≥ 2`) after the 90 s capture window. Script automatically reinstalled the known-good APK and relaunched.
4. **Wall came back up.** 4 distinct slots LIVE within 60 s: `bloomberg-tv`, `cbs-sports-hq`, `bbc-news`, `cnn`. `known-good` pointer unchanged. Failed APK retained in `$MYMTS_ARCHIVE_DIR/archive/` for diagnosis.
5. **Manual rollback exercised.** `./scripts/deploy-app.sh --manual-rollback` reinstalled the known-good in ~6 seconds; wall up; `known-good` pointer unchanged.

### Operational Bar properties confirmed on hardware
- **B1 (never bricks).** The wall was always running — either on the just-installed build (briefly, while the gate ran) or on the known-good.
- **B2 (always a way back).** Both auto and manual rollback paths exercised; both restored the wall to a known-working state.
- **B5 (no silent bad-bundle cascade).** A build that produced zero `EV=TILE_READY` events in 90 s was **never** declared the new known-good. The promotion gate held.

### Fixes shipped this commit
- `scripts/deploy-app.sh` — `log()` now writes to stderr instead of stdout. The prior bug: `$(archive_release)` was capturing the log line as part of the filename, producing a corrupted install path. Found at first deploy attempt; fixed before any state was persisted.
- `docs/OPERATIONS.md` — "live device test STAGED" flipped to "**verified on `.182` on 2026-06-04**" with the exact sequence above + a gotcha about `:app:clean` being required before any `gradle.properties` change.

### Gotcha — gradle incremental builds can mask config changes
When `gradle.properties` is edited (e.g. to point at a different `MYMTS_HELPER_BASE_URL`), gradle may report `:app:assembleRelease` as up-to-date and reuse the prior APK. The generated `BuildConfig.java` correctly reflects the new value, but the assembled APK doesn't. Discovered during the rollback test: an APK that was supposed to be "the failing build pointed at <LAN_IP>" had quietly been re-archived as the prior good build. Always force `:app:clean` before changing config-driven `buildConfigField` values. Runbook addition recorded in `docs/OPERATIONS.md`.

## At-the-box finale Step 2 — TLS cutover complete (2026-06-04)

The TLS migration staged in the Stage 6 baseline commit (`b8240b7`) is now finished. With the operator physically at the `.182` box (the camera box, MyMTS borrowed for development), the transitional cleartext path was removed in two safe phases — app first, then helper — each independently telemetry-verifiable.

### Phase A — App: cleartext exception removed
`app/src/main/res/xml/network_security_config.xml` flipped `cleartextTrafficPermitted="true"` → `false` for `<LAN_IP>`. Trust pinning to `@raw/helper_cert` unchanged: only the helper's own self-signed cert is accepted for this host; the system CA bundle is explicitly NOT a trust anchor here, so a global-CA-signed MITM cert is refused.

Rebuilt + uninstalled + freshly installed on `.182`. Telemetry confirmed:
- 4 distinct slots reached `EV=TILE_READY` over HTTPS only.
- **Zero** `MyMTS.HelperClient` / `ChannelsRepo` / `FeedRepo` cleartext errors.
- Lineup default (clean prefs) — Bloomberg TV / CBS Sports HQ / BBC News / CNN — all live.

### Phase B — Helper: HTTPS-only listener
`helper/deploy/docker-compose.nas.yml` updated:
- Dropped `PORT: 8091` env var.
- Dropped `8091:8091` port mapping.
- Healthcheck now hits `https://127.0.0.1:8443/health` (with `-k` since the loopback fetch isn't a MITM concern).
- HTTPS port mapping (`8443:8443`) is the only exposed port.

Helper redeployed (full `docker compose up -d --build --force-recreate`):
- Container shows `0.0.0.0:8443->8443/tcp` only — HTTP 8091 is unreachable from the LAN (curl from dev Mac to `http://<LAN_IP>:8091/health` times out).
- `/health` over HTTPS returns deployed SHA `a21f37c` + `ready: true` + 10 live channels.
- App on `.182` after force-stop + relaunch: all 4 tiles reach `EV=TILE_READY` over HTTPS-only helper.

### Updated
- `app/src/main/res/xml/network_security_config.xml` — cleartext exception removed; comment updated to record the cutover date.
- `helper/deploy/docker-compose.nas.yml` — HTTP listener stripped; healthcheck moved to HTTPS.
- `docs/THREAT-MODEL.md` — T-T4 updated to reflect "HTTPS-only, cleartext refused" rather than "transitional cleartext fallback retained." Residuals trimmed accordingly.
- `docs/OPERATIONS.md` — the dual-port migration table flipped from "staged" to **COMPLETE 2026-06-04**.

### Standing rules
- **WyzeGrid** still as-found on `.182` — install was `am force-stop` + `install -r` + `am start`; `WatchdogService` foreground stayed alive through the whole cutover. WyzeGrid is the camera box's intended foreground owner and is restored to foreground at session-end Step 4.
- App tests green; helper tests green: 136. No behavior change in either test surface from this commit (compose + xml config + comment edits only).
- Helper redeployed via the standing standard — non-root, read_only, cap_drop ALL, dedicated bridge network — **never any unrelated container on the host**.

## Tile controls + captions OFF by default + lineup swap (2026-06-04)

The "big bite" — per-tile audio/volume controls, a captions toggle (default OFF), and a swap of the default lineup to put Bloomberg TV + CNBC at the top. Build + unit-test + telemetry-verify here; the real-remote feel pass on the controls overlay is **staged for the at-the-box finale**.

### Captions OFF by default

`StreamPlayer.createPlayer()` now sets `TrackSelectionParameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)` before `prepare()`. The wall never auto-selects a soft caption track at startup; the operator turns captions on per-tile from the new controls overlay.

Burned-in captions (pixels in the video) are NOT removable here — the per-channel caption table in `docs/findings/06-tile-controls-and-captions.md` documents which channels have a soft track (toggleable) vs. which have a burned-in transcription bar (operator picks a different channel or lives with it). Per-channel mechanisms were verified by actually fetching the master manifests and looking for `#EXT-X-MEDIA:TYPE=SUBTITLES` / `TYPE=CLOSED-CAPTIONS` lines:

| Channel | Mechanism | Toggle behaviour |
|---|---|---|
| CBS Sports HQ | Soft CEA-608 | Works |
| CNN (slate feed) | No captions declared | "not available" |
| LiveNOW from FOX | **Soft WebVTT** (not burned-in on this Akamai CDN) | Works |
| Newsmax | No captions on `index.m3u8` (master with captions doesn't resolve — variant 404) | "not available" |
| France 24 English | No captions declared | "not available" |
| Sky News | No captions declared (single-rendition variant) | "not available" |
| BBC News (worldwide shard) | No captions | "not available" |
| DW News English | Soft WebVTT (DEFAULT=NO) | Works |
| Bloomberg TV (new) | Soft track expected | Works |

### Per-tile controls overlay

SELECT on a slot row in the side menu now opens a small actions popup (the new `SlotControlsOverlay`) instead of jumping directly to the channel picker. WyzeGrid-family centered card, focusable rows:

- **Channel** → re-enters the existing channel picker; on assign or cancel, returns to the controls overlay so the operator can immediately toggle audio/captions on the chosen channel.
- **Audio** → toggles this tile audible. Single-audible-tile model — selecting "audible" here automatically mutes every other tile (`WallAudio` discipline enforced in `VideoGrid` via `setAudible(slot.index == audibleSlot)` for every player).
- **Captions** → toggles the soft caption track. Surface shows `not available on this channel` when the stream has no text track to toggle.
- **Close** → dismiss the controls overlay; the side menu stays open.

BACK dismisses just the controls overlay; the side menu stays open so the operator can navigate to another slot without reopening MENU. Consistent with the Stage 5 channel-picker's BACK semantics.

### Preferences persist via LineupStore

Existing `SharedPreferences("mymts_lineup")` gets two new keys:
- `audible_slot` → `Int` (`-1` = wall muted; default)
- `captions_on_slots` → JSON array of slot indices

5 new codec tests in `LineupStoreIntSetCodecTest` pin the round-trip, deterministic encoding, negative-index rejection, and non-int tolerance.

### Lineup priority swap — Bloomberg TV + CNBC at the top

`LineupSelector.PREFERRED` updated:
```
[bloomberg-tv, cnbc, cbs-sports-hq, bbc-news, cnn, livenow-fox]
```

DW News English stays seeded for manual assignment via the menu picker; it just drops out of the default cycler's preferred tier. 5 new tests in `LineupSelectorPreferredOrderTest` pin the order.

### Seed updates

- **Added** `bloomberg-tv` → `https://bloomberg.com/media-manifest/streams/phoenix-us.m3u8` (resolves live; Bloomberg TV+ Phoenix-us feed via the official direct origin).
- **Added** `cnbc` → `.invalid` placeholder URL. CNBC has no free public HLS endpoint as of 2026-06-04 (paywalled cable, no FAST-platform free stream). Seeded honestly so the prober reports `dns_failure` correctly; logged to BACKLOG as a carry-forward.
- **Reverted** `newsmax` from `master.m3u8` back to `index.m3u8` — the master exposes captions but its variants 404, taking the channel offline entirely. Honest trade-off: keep the channel playing without an optional toggle (captions default OFF anyway) rather than lose the channel for a feature off by default.

### Resolution map after this push

| status | count | channels |
|---|---|---|
| **live** | **10** | bbc-news, **bloomberg-tv** (new), cbs-sports-hq, cnn, dw-news-en, france24-en, livenow-fox, newsmax, redbull-tv, sky-news |
| unavailable | 9 | al-jazeera-en, c-span, cgtn-en, **cnbc** (new — placeholder URL, no public HLS exists), cnn-international, iss-feed, nasa-tv, trt-world, white-house-tv |

### Clean-slate default lineup on a 4-slot wall — telemetry-verified

After `pm clear com.mymts` + relaunch:
```
slot-0 = bloomberg-tv   (PREFERRED #1 — new)
slot-1 = cbs-sports-hq   (PREFERRED #3 — cnbc unavailable, skipped)
slot-2 = bbc-news        (PREFERRED #4)
slot-3 = cnn             (PREFERRED #5)
```
`EV=TILE_READY` fired for each within 60 s. LiveNOW from FOX sits behind CNN and would fill slot 5 at N=5; available for manual assignment.

### BACKLOG additions
- **Video crop to hide burned-in captions** — out of scope by engineering default. Three reasons all operator-decision rather than engineering defaults.
- **CNBC — no free public HLS endpoint exists** — placeholder URL recorded; resolves honestly as unavailable.

### What's staged for the at-the-box finale
- **Tile controls real-remote pass.** D-pad logic works in the away-from-box telemetry layer (Compose focus on each `ControlRow`, focusable popups, BACK semantics), but the **felt experience** on the actual Onn remote needs the operator there.

### Updated
- `app/src/main/java/com/mymts/player/StreamPlayer.kt` — captions disabled by default; `setAudible(Boolean)`, `setCaptionsEnabled(Boolean): Boolean`, `hasSoftCaptionTrack()`.
- `app/src/main/java/com/mymts/data/lineup/LineupStore.kt` — `audibleSlot`, `captionsOnSlots` state + `toggleAudible`, `toggleCaptions`, `muteAll`.
- `app/src/main/java/com/mymts/ui/menu/MenuState.kt` — new `PendingSelection.SlotControls`.
- `app/src/main/java/com/mymts/ui/menu/SlotControlsOverlay.kt` — new centered popup (Channel / Audio / Captions / Close).
- `app/src/main/java/com/mymts/ui/wall/WallScreen.kt` — controls overlay wired in; SELECT on slot row → controls (not directly to picker); picker on dismiss → returns to controls.
- `app/src/main/java/com/mymts/ui/wall/VideoGrid.kt` — `LaunchedEffect(bound, audibleSlot, captionsOnSlots)` applies audio + captions to each player.
- `app/src/main/java/com/mymts/ui/wall/LineupSelector.kt` — PREFERRED list updated.
- `helper/src/mymts_helper/channels/seed.json` — Bloomberg + CNBC added; Newsmax URL reverted.
- 5 new app tests in `LineupStoreIntSetCodecTest`, 5 new in `LineupSelectorPreferredOrderTest`.
- `docs/findings/06-tile-controls-and-captions.md` — new finding doc.
- `docs/BACKLOG.md` — video-crop + CNBC entries.

### Standing rules
- **WyzeGrid** as-found on `.182` — install was force-stop + install -r + am start; WyzeGrid foreground service stays alive.
- App tests green: ~80+ (now includes 10 new for this push). Helper tests green: 136.
- Helper redeployed per the standing standard (non-root, read_only, cap_drop ALL, dedicated bridge — never any unrelated container on the host).

## Stage 6 — TLS to the helper, baseline (2026-06-03)

The TV ↔ helper link is now encrypted with certificate pinning at the app layer. Operator-away-safe baseline: the helper serves HTTPS 8443 **alongside** HTTP 8091, and the app keeps a cleartext fallback for `<LAN_IP>` so a botched HTTPS path can't strand the box. The full cutover (cleartext exception removed + HTTP 8091 dropped from compose) is staged for the "at-the-box" finale Step 1, where the operator can physically recover the box if anything goes wrong.

### Trust mechanism — Option A (self-signed cert + pinned trust)

Per the prompt's recommendation. A single-operator LAN service doesn't need a CA — the simplest correct posture is "the app trusts that specific cert, and nothing else for that host."

- Self-signed RSA-4096 cert generated on the NAS, 10-year validity, SAN `IP:<LAN_IP>, DNS:mymts-helper`.
- The private key (`helper.key`) lives **only** on the NAS at `/srv/docker/mymts-helper/_secrets/`, mounted read-only into the container at `/etc/ssl/mymts/`, owned UID 10001 mode 600.
- The public cert (`helper.crt`) is committed at `app/src/main/res/raw/helper_cert.pem` (public material — committable). `.gitignore` excludes `*.key` and any other `*.crt`/`*.pem` under `helper/` with a narrow `!` re-include for the trust-anchor file.
- `network_security_config.xml` carries a `<domain-config>` for `<LAN_IP>` whose `<trust-anchors>` contains **only** `@raw/helper_cert`. The system CA bundle is **explicitly NOT** a trust anchor for this host — a global-CA-signed MITM cert is refused.

### Helper — dual-port serving from one app instance

`helper/src/mymts_helper/__main__.py` rewritten to build the FastAPI app once and run two `uvicorn.Server` instances concurrently sharing that one app:

| Listener | Port | Lifespan | Pollers |
|---|---|---|---|
| HTTP (transitional) | 8091 | `on` | yes — RSS poller + channel prober run once via the app's lifespan |
| HTTPS (target) | 8443 | `off` | no, but serves identical endpoints from the same app instance |

When the transitional HTTP is dropped at the finale, the HTTPS listener's lifespan flips to `on` and becomes the sole listener.

### App — pinned trust + HTTPS default

- `app/src/main/res/raw/helper_cert.pem` embedded (public cert, no key).
- `app/src/main/res/xml/network_security_config.xml` rewritten: base config refuses cleartext; the `<LAN_IP>` domain-config pins only `@raw/helper_cert`. Cleartext fallback retained transitionally.
- `gradle.properties` → `MYMTS_HELPER_BASE_URL=https://<LAN_IP>:8443`.

### Telemetry — proof (operator was away from the TV)

| Signal | Result |
|---|---|
| Helper `/health` over HTTPS 8443 from a US IP | `HTTP 200`, JSON with `build_sha`, 8 live channels reported |
| Helper `/health` over HTTP 8091 (transitional) | `HTTP 200`, same payload |
| App on `.182` after install + relaunch | 4 distinct `EV=TILE_READY` events: `slot-0-cnn`, `slot-1-dw-news-en`, `slot-2-livenow-fox`, `slot-3-cbs-sports-hq` |
| `MyMTS.HelperClient` errors in logcat | **zero** — no trust failures, no SSL exceptions, no fallback into HTTP |

The operator's preferred lineup (CBS Sports HQ + LiveNOW from FOX + the rest) is now actually playing over HTTPS — the channel-resolution + TLS tracks compound.

### Hiccup recorded for the runbook

First helper restart crash-looped with `PermissionError: [Errno 13]` on the key file — UID mismatch between the cert files (owned by host user `cargo`, UID 1000) and the container (UID 10001). Fixed without `sudo` using a short-lived root Alpine container (`cargo` has docker group membership). Documented in `OPERATIONS.md` so the next rotation doesn't trip the same way.

### What's NOT in this commit (staged for the at-the-box finale)

- Remove cleartext exception from `network_security_config.xml`.
- Remove `8091:8091` port mapping + `PORT=8091` env from helper compose.
- Flip HTTPS listener lifespan to `on`.
- Verify on `.182` with the operator at the box.

### Updated

- `helper/src/mymts_helper/__main__.py` — dual-server entry point.
- `helper/src/mymts_helper/config.py` — `https_port`, `ssl_keyfile`, `ssl_certfile` + `has_https()` predicate.
- `helper/tests/test_config_tls.py` — 4 new tests for the TLS config surface.
- `helper/deploy/docker-compose.nas.yml` — HTTPS port + cert mount + env wiring.
- `app/src/main/res/raw/helper_cert.pem` — embedded public cert (trust anchor).
- `app/src/main/res/xml/network_security_config.xml` — cert pinning + retained cleartext transitional.
- `gradle.properties` — default URL flipped to HTTPS.
- `.gitignore` — narrow private-key + helper-side cert/pem exclusion with `!` re-include for the app trust anchor.
- `docs/THREAT-MODEL.md` — T-T4 rewritten from "cleartext narrow exception" to "encrypted + pinned, transitional cleartext fallback documented, full cutover staged."
- `docs/OPERATIONS.md` — TLS section with cert generation runbook, ownership-fix gotcha, dual-port migration sequence.
- `ARCHITECTURE.md` §12 — TLS mechanism + trust model + cert-rotation contract.

### Standing rules
- Helper continues to run on its own bridge network (`mymts-net`), never the VPN container's.
- **WyzeGrid** as-found on `.182`, foreground + `WatchdogService` healthy.
- Helper tests green: **136** (132 prior + 4 new).
- App tests green (unchanged count — only resource + config changes on the app side).
- Helper redeployed to `<USER>@<HOST>` per the standing standard (non-root, read_only, cap_drop ALL, dedicated bridge network, **never any unrelated container on the host**).

## Channel-resolution investigation (helper-side) — 8 channels live, up from 2 (2026-06-03)

The deferred channel-resolution follow-on, now done. Three honest tracks:

### Prober deepened — master + variant validation

The prober previously validated only the master HLS manifest (`#EXTM3U` prefix check). NASA TV exposed the gap: its master fetches fine, but its variants return HTTP 404 — the helper would say `live` while the player couldn't actually play. The Stage 3 polish-pass `LineupSelector.DENY` workaround masked the truth at the menu layer; this commit fixes the cause at the helper layer.

After this commit (`helper/src/mymts_helper/channels/prober.py`):
1. Fetch master through the SSRF-safe `fetcher` (unchanged).
2. Validate `#EXTM3U` (unchanged).
3. If the master is a **media playlist** (`#EXTINF` present, no `#EXT-X-STREAM-INF`) → mark live with master URL.
4. Otherwise parse the master, find the first `#EXT-X-STREAM-INF` entry's URL line, absolutize against the master URL, and **fetch that variant** through the same SSRF-safe `fetcher`. Variant must respond `2xx + #EXTM3U`. Anything else → record precise error (`variant_http_<code>`, `variant_fetch:<reason>`, `variant_not_hls_manifest`, `variant_missing_in_master`).

All SSRF guards (https-only, DNS-pinned, RFC1918/loopback/CGNAT/ULA rejection, bounded body, bounded time, redirect re-validation) apply to the variant fetch verbatim. **No new egress surface.** 10 unit tests in `helper/tests/test_channels_prober.py` cover the master/media/variant cases including downgrade-rejection (http variant in master refused) and conservative None-on-ambiguity (intermediate tag-line skipped → return None rather than picking wrong rendition).

### Seed URL rotation — verified working candidates

Each new candidate was fetched and confirmed `HTTP 200 + #EXTM3U` from a US residential IP before being written into `helper/src/mymts_helper/channels/seed.json`:

- `cbs-sports-hq` → CBSi airspace CDN
- `bbc-news` → BBC worldwide shard (`-ww-live` instead of UK-only `-uk-live`)
- `cnn` → Turner/Warner slate feed (publicly-streamable, AES-128)
- `livenow-fox`, `newsmax` → Akamai direct
- `france24-en` → official `live.france24.com` origin (old `f24hls-i.akamaihd.net` decommissioned)
- `sky-news` → Sky CDN direct (in flight — works from dev + NAS curl, deploy-time probe returned 403; next 30-min cycle will retry)

### Net result on the helper

| status | count | channels |
|---|---|---|
| **live** | **8** | bbc-news, cbs-sports-hq, cnn, dw-news-en, france24-en, livenow-fox, newsmax, redbull-tv |
| unavailable | 9 | al-jazeera-en, c-span, cgtn-en, cnn-international, iss-feed, **nasa-tv** (variant-FAIL honestly reclassified — the deepened prober caught what the old prober missed), sky-news (in flight), trt-world, white-house-tv |

The operator's preferred lineup (CBS Sports HQ → BBC News → CNN → LiveNOW from FOX) now resolves **all four** to playable channels. The wall no longer cycles two channels into four slots.

### Carried forward to BACKLOG as honest "no public endpoint" cases

- **Geo-block circumvention** for region-locked broadcasters — explicit operator-decision item. **Not** added by default — would require re-doing the helper's egress threat-model and the operator's call on legal/TOS posture for specific broadcasters.
- **ISS Live standalone HLS** — UStream is dead and CloudFront origin DNS-fails; NASA's current standalone ISS feed is YouTube-only. NASA TV NTV1 is the honest substitute.
- **CNN International / C-SPAN** — no public free linear HLS exists; C-SPAN wraps its current web player in a session-token handshake; CNN International is no longer on any FAST platform.

### NASA TV deny-list no longer load-bearing

`LineupSelector.DENY = setOf("nasa-tv")` from the Stage 3 polish-pass was a workaround for the master-OK / variant-FAIL boundary gap. With the deepened prober, NASA TV is now honestly `unavailable` from the helper, so the deny list is documentation-of-the-shape rather than load-bearing logic. Left in place; if the variant becomes reachable again, the next probe cycle re-marks it live and the deny list can be removed in a future commit.

### Updated

- `helper/src/mymts_helper/channels/prober.py` — variant-fetch step added.
- `helper/src/mymts_helper/channels/seed.json` — 7 candidate URLs rotated.
- `helper/tests/test_channels_prober.py` — 10 new tests for the variant-validation logic.
- `docs/findings/05-channel-resolution.md` — full investigation record + resolution map.
- `docs/BACKLOG.md` — geo-block-circumvention (operator-decision), ISS-Live-standalone, CNN International / C-SPAN entries.
- `docs/THREAT-MODEL.md` — T-H5 unchanged in spirit; the prober's "live" definition is now tighter and matches the player.

### Standing rules
- **WyzeGrid** untouched (helper-side; no `.182` involvement).
- Helper tests: 132 green (122 prior + 10 new).
- Helper redeployed to `<USER>@<HOST>:/srv/docker/mymts-helper/`; non-root, read_only, cap_drop ALL, dedicated bridge network — **never any unrelated container on the host**.

## Stage 6 — signed-install update path + rollback (2026-06-03)

Per `03-OPERATIONAL-BAR.md` B1/B2/B5 — never bricks, always a way back, no silent bad-bundle cascade. App-side + release-infra track only (does NOT touch the helper, so safe in parallel with the channel-resolution work above).

### Release signing

`app/build.gradle.kts` gains a release signing config sourced from either `app/keystore.properties` (gitignored; modelled on `app/keystore.properties.example`) or four environment variables of the same name. The keystore + credentials are **secrets** — `.gitignore` excludes `*.jks`, `*.keystore`, `keystore.properties`. Loss of the keystore means losing the ability to push updates (Android refuses upgrades signed with a different key); backup-off-device responsibility documented.

A `BuildConfig.IS_RELEASE_SIGNED` boolean flips when a real keystore is wired up; the deploy script reads the actual signing certificate via `apksigner verify --print-certs` and refuses to ship a debug-signed APK (subject `CN=Android Debug,O=Android,C=US`).

### Update flow: build → install → health-gate → promote-or-rollback

`scripts/deploy-app.sh` orchestrates:
1. `:app:assembleRelease` (signed if keystore configured; falls back to debug-sign for dev, refused at install time).
2. Archive to `$MYMTS_ARCHIVE_DIR/archive/mymts-<v>+<sha>-<utc>.apk`. Older APKs are retained — manual rollback to any prior version is one command.
3. `adb install -r -t` on the target.
4. Launch.
5. Capture `MYMTS_SOAK` telemetry for `--deadline-seconds` (default 90).
6. Run `scripts/health_check.py` against the capture — pure-Python decision logic.
7. On **PASS**: atomic write of the new APK filename to `$MYMTS_ARCHIVE_DIR/known-good`.
8. On **FAIL**: reinstall the prior known-good APK; log the rollback.

The decision function returns one of `PASS / FAIL_ALL_DEAD / FAIL_DECODER_THRASH / FAIL_NOT_READY / FAIL_TIMEOUT`. The `FAIL_DECODER_THRASH` outcome catches exactly the b19b013 regression shape (decoders init repeatedly but `EV=TILE_READY` never fires) — the Stage 3 fix-forward lesson is now wired permanently into the update path. **15 unit tests** in `scripts/test_health_check.py` exercise the decision matrix.

### Operator-away safety — no destructive on-device install in this commit

The operator is currently away from `.182` and cannot physically recover a botched install. Per the prompt's `Section 3` guidance:
- **The decision logic is fully unit-tested.**
- **Dry-run validated end-to-end on this Mac**: `./scripts/deploy-app.sh --dry-run` built a signed-keystore-absent fallback APK and archived it as `mymts-0.0.0+6be42c4-20260603T232010Z.apk` without touching `.182`.
- A **live install + force-failed-health + automatic-rollback** test is **staged for when the operator is near the box**. Running it now while the operator is away carries device-recovery risk if the script misfires (e.g. if `adb` install fails to restore the prior APK). The staged test is documented in `OPERATIONS.md` so it can be picked up immediately when the recovery posture is acceptable.

### Manual rollback — the always-a-way-back guarantee

```bash
./scripts/deploy-app.sh --manual-rollback
```
Reads the `known-good` pointer (which never names a failed APK because promotion happens only on PASS), reinstalls that APK with `adb install -r -d` (`-d` allows downgrade for versionCode), restarts the activity. Manual recovery to any *older* archived APK is `adb install -r -d <archive>/mymts-X.Y.Z+sha-…apk`.

### Updated

- `app/build.gradle.kts` — signing config + `IS_RELEASE_SIGNED` BuildConfig field.
- `app/keystore.properties.example` — template; real file gitignored.
- `scripts/deploy-app.sh` — orchestrator.
- `scripts/health_check.py` — decision logic.
- `scripts/test_health_check.py` — 15 unit tests covering PASS / FAIL_ALL_DEAD / FAIL_DECODER_THRASH (b19b013 shape) / FAIL_NOT_READY / partial-dead-below-threshold / decoder-thrash-only-when-zero-TILE_READY / decision matrix.
- `docs/OPERATIONS.md` — Install + Update + rollback rows populated with the deploy flow, the artifact layout, the manual-rollback path, the dry-run mode, and the operator-away staged-pending status.
- `ARCHITECTURE.md` §11 — release/update/rollback flow + the four states an APK can be in.
- `docs/THREAT-MODEL.md` — T-A2 populated covering signed installs / never-bricks / always-a-way-back / no-silent-bad-bundle-cascade. Residual risks: keystore loss and the window-bounded health gate.

### Standing rules
- **WyzeGrid** as-found on `.182` (no device install in this commit).
- App tests: ~50+ prior cases still green. Decision logic tests: 15 new cases.
- Helper untouched in this track.

## Stage 5 checkpoint 2 — channel picker + persistence, lineup control complete (2026-06-03)

Stage 5 makes the wall operable from the couch. Checkpoint 1 (commit `e803fee`) shipped the menu shell — open/navigate/back. Checkpoint 2 (this commit) wires the actual feature: real per-slot channel labels, the centered TV-style channel picker with honest live/offline marking, on-device lineup persistence, and telemetry-verified reassignment.

### Added — TV-side menu (channel/lineup control only — settings/layout/diagnostics deferred to BACKLOG)

- **`ui/menu/ChannelPickerOverlay`** — centered TV-style popup (WyzeGrid signature pattern). Shows the focused channel as `< Channel Name (status) >`; D-pad LEFT/RIGHT cycles the helper's channels (wrapping at the ends); SELECT/CENTER assigns; BACK cancels. Each channel decorated with its real current status (`live` / `offline`); the operator cannot mistake an offline channel for one that will play (Trust Bar C3 at the menu layer).
- **`data/lineup/LineupStore`** — SharedPreferences-backed persistence. Format: `SharedPreferences("mymts_lineup") → key "lineup_overrides"` holding a JSON array `[slot, slug, slot, slug, …]`. Deterministic encode (sorted by key) so two writes of the same lineup produce byte-identical disk state. Tolerant of corrupt blobs (resets + logs once). **No secrets, no PII, no absolute paths** — only short slug strings the operator chose.
- **Mark-and-allow** policy for offline-pinned slots: the picker permits assigning an offline channel but marks it visibly; the wall renders the C2 honest panel for that slot with the assigned channel's label, distinct from an unassigned blank tile. (Rationale recorded in `docs/findings/04-stage-5-menu.md` — the wall already degrades honestly, and the operator may want a channel queued for when it returns.)

### Refactored — TileSlotResolver and the slot list as a single source of truth

- **`TileSlotResolver.Slot.Offline(index, channel)`** — new variant for an operator-pinned-but-not-currently-live channel. Renders the C2 panel with the channel's label. **Has no `spec` field by construction** — so the structural label/stream binding from `9d5b0ad` (`BoundTile.init { require(player.specId == slot.spec.id) }`) cannot misfire on an offline slot; the type system rules out a mispaired player.
- **`TileSlotResolver.resolve(tileCount, defaultChannels, allChannels, overrides)`** — new signature accepts the override map. Overrides take priority over the default cycler; the cycler's pool filters out operator-pinned slugs so a pinned channel never double-fills another slot. Saved slugs that no longer match any helper channel **fall through gracefully** to the default cycler — the operator never sees a label for a vanished channel.
- **`WallScreen` is now the owner** of the slot list. Computes it once per recomposition from `(allChannels, defaultOrder, overrides)` and passes the same `List<Slot>` to both `VideoGrid` and `MenuOverlay`. The menu and the wall **cannot disagree** about which channel is in which slot.

### Telemetry — the proof (operator was away from the TV)

The Stage 3 lesson held: a tile that fails to start is pixel-identical to an honest dead tile, so appearance proves nothing. Verified via telemetry only:

| Behavior | Evidence |
|---|---|
| **Reassign slot 0 to a different live channel** | After `MENU → SELECT → RIGHT × 2 → SELECT` to pin `redbull-tv` to slot 0: `EV=DECODER\|id=slot-0-redbull-tv` at 17:56:35 → `EV=TILE_READY\|id=slot-0-redbull-tv` at 17:56:36. New spec id = new player = real first frame, not just a label swap. |
| **Persistence across force-stop and relaunch** | `shared_prefs/mymts_lineup.xml` on disk contained `[0,"redbull-tv"]`. After `am force-stop` + relaunch: `EV=TILE_READY\|id=slot-0-redbull-tv` (restored from disk), not `slot-0-dw-news-en` (the default cycler's first pick). |
| **Offline assignment never starts a player** | After pinning `slot 0 → cnn` (offline) via direct prefs write: decoders started for slot-1-dw-news-en, slot-2-redbull-tv, slot-3-redbull-tv; **zero events** for `slot-0-cnn` or `slot-0/offline-cnn`. The C2 panel rendered with `CNN` as the ghost label (screencap evidence). |
| **No capacity blowout** | ~10 decoder inits across the full test (4 baseline + 3 rebind + 3 post-restart). Zero recovery strikes. Zero `EV=DEAD`. Stage 2 N=4 ceiling holds. |

Evidence: `docs/findings/runs/stage-5-checkpoint-2-20260603-1755/` (picker screencap + wall-with-cnn-offline screencap + notes.md).

### Tests added

- **`MenuStateTest`** (8 cases, checkpoint 1) — visibility + sub-overlay state.
- **`TileSlotResolverOverridesTest`** (7 cases) — override-to-live → Playing, override-to-offline → Offline with label, override-to-vanished-slug → graceful fallback, no double-fill, every-slot-pinned-and-no-spares → honest Empty tail, Offline carries no spec.
- **`LineupStoreCodecTest`** (7 cases) — round-trip preservation, corrupt-JSON safety, odd-length blob handling, invalid-pair skipping, deterministic encoding.

### Updated

- **`ARCHITECTURE.md`** §10 — new menu module map, slot-list-as-single-source-of-truth diagram, slot-variant table, persistence format, D-pad model, honesty rules carried through.
- **`docs/THREAT-MODEL.md`** — new `T-T6` (LineupStore persistence; no-secrets-by-construction) and `T-T7` (menu UI honesty; picker reads same `Channel.isPlayable` the player does). Stage 5 row in the gate table marked done.
- **`docs/findings/04-stage-5-menu.md`** — new finding doc covering the interaction model, the mark-and-allow decision, the telemetry proof, the persistence format.

### Standing rules held
- **WyzeGrid** as-found on `.182`, foreground + `WatchdogService` healthy. No disable.
- Helper untouched (UI-only commit).
- App test count ~50+ cases.

## Stage 3 fix-forward — video startup regression repaired (2026-06-03)

The Stage 3 follow-up commit `b19b013` (label/stream binding made structural) introduced a Compose-timing bug that broke video startup. The honesty rule itself was correct; the wiring was wrong. Telemetry — not visual inspection — surfaced it.

### What broke

`VideoGrid` cached the player-by-spec-id lookup in `remember(manager, specs) { … manager.player(idx) … }`. That `remember` block evaluates **during composition**, before the `DisposableEffect` that registers the manager as a lifecycle observer has run. So `manager.player(idx)` returned `null` for every idx, the materialised map permanently cached nulls, and — because `(manager, specs)` was stable across recompositions — nothing invalidated the cache once `manager.onStart` later populated real players. Each `BoundTile` permanently held `player == null`; `WallTile → PlayingTile` early-returned the C2 dead panel; `StreamSurface` was never mounted; no SurfaceView attached to any ExoPlayer; `onRenderedFirstFrame` never fired; `EV=TILE_READY` stayed at 0. The LivenessTracker honestly settled all 4 tiles into `DEAD` after the recovery ladder — correct mechanism, wrong cause.

The C2 dead-panel UI made the regression **visually identical** to a genuine network failure. The original commit message attributed it to an "Akamai blip" — that hypothesis was false. Both Akamai origins responded `HTTP 200` from the dev Mac and were pingable from `.182` at ~12–36 ms RTT with 0% loss.

### The fix

- **`StreamPlayerManager`** now exposes `val readyVersion: State<Int>` — a Compose-observable signal backed by `mutableIntStateOf(0)`, incremented inside `onStart` *after* players are created (and again inside `onDestroy` so observers can collapse cleanly).
- **`VideoGrid`** now reads `manager.readyVersion.value` (subscribing the composable to its changes) and includes it in `remember(slots, manager, readyVersion) { bindTiles(…) }`. When `onStart` flips the version, Compose invalidates the cached binding, the lookup re-runs with the now-populated manager, and the bound tiles carry real players. `StreamSurface` mounts, surface attaches, video renders, `EV=TILE_READY` fires.
- `StreamPlayerManager` also gains a `playerFactory` constructor parameter (defaults to `::StreamPlayer`) so the readiness mechanism is unit-testable without spinning up real ExoPlayer instances.
- **The structural honesty rule** (`BoundTile.init { require(player.specId == slot.spec.id) }` + `bindTiles` identity-pairing + Compose `key(tile.key)` per tile) is **unchanged**. The regression was in *when/how* the binding was wired, not in the rule.

### Telemetry — the proof

| Signal (~90 s after launch) | `b19b013` | After this commit |
|---|---|---|
| `EV=TILE_READY` | **0** | **4** (one per slot, within 60 s) |
| `EV=DECODER` | 12 (3 strikes × 4 tiles) | 4 (one per slot, no recovery thrashing) |
| `EV=DEAD` | 4 (all tiles settled DEAD) | **0** |
| LIVE transitions | 0 | 7 (4 initial + 3 bursty self-resolutions) |
| Wall appearance | 4 C2 dead panels | 4 live video tiles (verified by screencap) |

Stream URLs and network conditions were identical across both runs. The variable was the code. Screencap evidence: `docs/findings/runs/stage-3-fixforward-20260603-1658/`.

### Regression tests

- **`StreamPlayerManagerReadinessTest`** (4 cases) — direct probe of the readiness mechanism. Verifies `readyVersion == 0` and `player(idx) == null` before `onStart`; verifies `readyVersion` increments and players are populated by the factory after `onStart`; verifies `onStart` is idempotent; verifies `onDestroy` releases + bumps. **These tests would not compile against `b19b013`** because the `readyVersion` property does not exist there — the strongest form of "fails on broken / passes on fix."
- **`VideoGridBindingTest`** (3 cases) — documents the broken vs fixed Compose-`remember` patterns by simulating cache semantics in plain Kotlin. The "buggy wiring without readyVersion key" test demonstrates that the regression's pattern caches nulls forever; the "fixed wiring keyed on readyVersion" test demonstrates that the fix invalidates correctly when readiness flips.

The pre-existing `BoundTileTest` (4 cases) guards the honesty rule but did **not** catch this wiring bug — those tests pass a fully-populated player map, exercising correctness *assuming* players exist. That gap is closed by the two new test suites above.

### Meta-lesson — for the permanent record

**Honest-degradation UI can mask a startup regression.** A `BoundTile` with `player == null` rendered the C2 quiet dead-panel — pixel-identical to a tile whose player legitimately settled DEAD after the recovery ladder ran. The C2 rule (graceful degradation, no error chrome) is correct; it just turns out to be the worst-case UI signal during a startup regression because it normalises "no video" as "honest about being offline."

**Corollary: after any change to the video-pipeline wiring, telemetry (`EV=TILE_READY`, `EV=DECODER`, state-machine transitions) is the verification standard — not visual inspection.** This stage's first verification was visual ("the wall looks right") and the regression slipped past. The operator's directive to verify with telemetry (no display needed) is what surfaced it.

### Standing rules held
- **WyzeGrid** as-found on `.182`, foreground + `WatchdogService` healthy.
- App tests green: `StreamPlayerManagerReadinessTest` (4) + `VideoGridBindingTest` (3) added; total app unit-test count ~36+.
- Helper untouched (the bug was TV-only).

## Stage 3 follow-up — channel-identity honesty + lineup corrections (2026-06-03)

### Honesty fix — label and stream cannot drift (the important one)

Operator review of the polished wall identified a tile-label / stream desync — a tile labelled "X" could end up playing channel "Y" as the resolution/backfill order changed. **This is a labelling lie**, the channel-identity sibling of Trust Bar C3 (no silent staleness). Fixed structurally:

- **New `com.mymts.ui.wall.BoundTile`** pairs a slot with the player whose `spec.id` matches that slot. `init { require(player.specId == slot.spec.id) }` throws at construction if the pairing is wrong. A tile labelled "X" cannot end up playing channel "Y" because the type system + runtime check forbid it.
- **New `bindTiles(slots, findPlayer)`** pairs by identity match on `spec.id`, never by index. A stale player from a prior recomposition is discarded (`.takeIf { specId match }`); the tile renders the same C2 panel as a settled-DEAD tile rather than drawing the wrong channel's video.
- **`WallTile` rewritten** to take a `BoundTile` parameter; no internal player lookup. Label, state, and player all flow from the same already-paired object.
- **`Compose key(tile.key)`** wraps each `WallTile` in `VideoGrid` so when a slot's channel changes the tile is rebuilt from scratch — no stale label or surface state can leak across the identity change.
- **4 regression tests** in `app/src/test/java/com/mymts/wall/BoundTileTest.kt` exercise the operator's described scenario (preferred fail, fallbacks backfill, labels still match), the runtime-check fires for a deliberately-wrong pairing, the lookup-miss case renders null rather than a wrong-channel player, and the stale-specId player is discarded.

### Lineup corrections

- **CBS Sports HQ**: prior candidate was CBS *News*, not Sports HQ — replaced with a Samsung TV+ Wurl pattern guess (`cbssports-cbssports-1-us.samsung.wurl.tv/playlist.m3u8`). Helper prober verdict: **DNS failure** — that candidate doesn't resolve from this network. Honest plain-text result recorded: a working current public HLS endpoint for CBS Sports HQ could not be reliably found without web access. Per the operator's "honest OFFLINE rather than the wrong channel mislabeled" instruction, the slug stays in the seed but doesn't occupy a default slot until a working endpoint is found.
- **ISS feed**: added new `iss-feed` channel slug in `LineupSelector.FALLBACK` in NASA TV's place. ISS candidate URL: `iphone-streaming.ustream.tv/uhls/17074538/streams/live/iphone/playlist.m3u8`. Helper prober verdict: **SSL certificate hostname mismatch** — UStream's cert is no longer valid for that hostname. Honest result: candidate doesn't resolve.
- **NASA TV moved to a deny list**: new `LineupSelector.DENY = setOf("nasa-tv")`. NASA TV's master HLS manifest passes the prober but its variant playlist fails for ExoPlayer; the slug stays seeded (so the operator can re-enable it once the prober is deepened to variant-fetch — already in BACKLOG) but is structurally excluded from the default lineup, including from the "rest" backfill tier. 3 unit tests in `LineupSelectorDenyTest.kt` pin this.

### Net result on the wall

Helper currently reports `dw-news-en`, `redbull-tv`, and `nasa-tv` as live. `LineupSelector.forWall(4)`: preferred + fallback (10 slugs total) all unavailable, NASA TV denied in the "rest" tier → only `dw-news-en` and `redbull-tv` survive, `TileSlotResolver` cycles `[dw, redbull, dw, redbull]` into the 4 slots. Same shape as the Stage 2 v2 long-soak.

Evidence: `docs/findings/runs/stage-3-followup-20260603-1235/wall-followup-c2-panels-honest.png` — captured during an external Akamai network blip when all 4 tiles were settled in honest C2 OFFLINE panels. Each label correctly matches its assigned channel; NASA TV is denied even though the helper has it as live. The honesty rules hold even under adverse network conditions.

### Standing rules held

- **WyzeGrid** as-found on `.182`.
- App tests green: 4 `BoundTileTest` + 3 `LineupSelectorDenyTest` added on top of prior suite. Helper tests green (122).

### Carried forward to BACKLOG

The channel-resolution-investigation entry was extended to cover four sub-tracks: (1) find current working candidate URLs for CBS Sports HQ + ISS feed, (2) deepen the prober to variant-fetch (turns NASA TV and any other master-OK-variant-FAIL into real lineup options), (3) geo-blocks (BBC News, C-SPAN — needs an egress strategy with non-trivial threat-model implications), (4) DNS-blocked candidate-URL rotation (CNN, LiveNOW, Newsmax, CNN International).

## Stage 3 — the wall on the TV (CLOSED 2026-06-03)

### Added — TV-side wall UI

- `data/helper/HelperClient` + `Channel` / `ChannelsSnapshot` / `FeedItem` / `FeedSnapshot` — minimal `HttpURLConnection`-based reader for the helper's `/api/channels` + `/api/feed`. `schema_version == 1` pinned; unknown versions refused. Connect 4 s / read 6 s timeouts. Real `org.json` on the unit-test classpath; Android's bundled copy is a stub there.
- `data/helper/ChannelsRepository` (30 s poll) + `FeedRepository` (60 s poll). Neither ever silently empties the last good snapshot on error — both expose a freshness signal the UI reads to decide between "current and calm" and "honest stale" (Trust Bar C3).
- `ui/wall/WallScreen` — single-screen assembly: `TickerStrip` across the top, `Row { FeedPane(weight 0.28) | divider | VideoGrid(rest) }` beneath.
- `ui/wall/VideoGrid` + `WallTile` — the N=4 tile grid. Polish-pass: **autofit** by equal `weight(1f)` rows × columns; **fit-width-letterbox** scaling moves into `StreamSurface` via Media3 `PlayerView` + `RESIZE_MODE_FIT`. Each tile fills its cell width with clean black bars top/bottom where dimensions differ.
- `ui/wall/WallTile` state-aware rendering: LIVE shows no badge (the picture is the signal), CONNECTING / STALE / RECOVERING dim the surface and label transparently, DEAD / OFFLINE collapse to a quiet near-black panel with a ghost channel label (Trust Bar C2 — graceful degradation made visible).
- `ui/wall/TileSlotResolver` — pure cycler. Given K live channels and N slots, deterministically returns `[c0, c1, …, c0, c1, …]` (the v2-long-soak shape) with stable slot ids so Compose `key()`s reuse `ExoPlayer` instances across recompositions. 8 unit tests pin the 0/1/2/4/5 × N=4 cases + tileCount edge cases.
- `ui/wall/LineupSelector` — preferred → fallback → rest, capped at N. `forWall(N)` exposes the operator's preferred lineup (`cbs-sports-hq`, `bbc-news`, `cnn`, `livenow-fox`) + 5-slug fallback (`c-span`, `nasa-tv`, `white-house-tv`, `newsmax`, `cnn-international`). 10 unit tests pin priority order, partial-resolution backfill, dedup across lists, and the maxCount cap.
- `ui/wall/FeedPane` — 10-foot UI list of `FeedRow`s with `RelativeTime` chips (`now/Nm/Nh/Nd/date`, 7 unit tests). PaneHeader carries a calm staleness label. **Every field renders as Compose `Text` — no `WebView`, no HTML rendering path on the wall.** Trust Bar A1 boundary held at the UI layer.
- `data/ticker/TickerSource` + `SampleTickerSource` — pluggable interface for a future real markets feed; the Stage 3 implementation emits 16 static placeholder entries with `isSample = true` on every one. 4 unit tests pin the `isSample` contract + asset-class variety.
- `ui/wall/TickerStrip` — `basicMarquee` scroller. Per-cell `SYMBOL · value · ▲/▼/■` plus a visible `SAMPLE` pill so the operator can never mistake the values for live quotes (C3 applied to the ticker).
- `MainActivity` routes the default mode to the wall; `--es mode soak` still launches the Stage 1/2 soak harness; `--es mode placeholder` reaches the build-identity smoke screen. `MYMTS_HELPER_BASE_URL` in `gradle.properties` → `BuildConfig.HELPER_BASE_URL`.
- `app/src/main/res/xml/network_security_config.xml` — narrow cleartext allowance for `<LAN_IP>` only; the base config is `cleartextTrafficPermitted=false`. Stage 6 will replace this with TLS + internal-CA pinning.

### Added — helper

- `feeds/seed.json` + `feeds/seeder.py` — idempotent boot-time RSS-source upserter mirroring the channels-seed pattern. 4 known-good general-news sources seeded by default (BBC World, Al Jazeera, Guardian World, NPR World). 7 unit tests pin idempotency, malformed-input handling, and skip-invalid-row behavior.
- `channels/seed.json` expanded from 7 to 16 entries — operator's preferred 4 + fallback 5 added to the existing 7 candidates.

### Polish pass — fit-width-letterbox + autofit + preferred-lineup

- `StreamSurface` rewritten from raw `SurfaceView` to Media3 `PlayerView` with `useController=false`, `setShowBuffering(NEVER)`, `resizeMode = RESIZE_MODE_FIT`. The wall's tile UI is the single source of truth for state; player chrome is hidden.
- `VideoGrid` switched from `aspectRatio(16:9)`-per-tile to equal-weight rows × columns. Letterboxing now happens inside each tile, not by sizing the tile.
- `LineupSelector` wired into `VideoGrid` via the `lineupSelector` lambda; `WallScreen` passes `LineupSelector.forWall(maxCount = tileCount)::invoke`.

### Channel-resolution result (the start of the resolution-investigation record)

After the polish-pass redeploy seeded all 16 channels and the helper's prober ran one sweep:

- **4 channels live and playable**: `cbs-sports-hq` (operator's #1 preference), `dw-news-en`, `redbull-tv`, plus `nasa-tv` whose master manifest passes the prober but whose variant playlist fails for ExoPlayer with `ERROR_CODE_IO_BAD_HTTP_STATUS`. The wall renders the C2 OFFLINE panel for NASA TV — honest. Full per-channel result in `docs/findings/03-stage-3-wall.md §3`.
- **12 channels unavailable**: a mix of `http_403` (geo-block on BBC News + C-SPAN), DNS failure (CNN / LiveNOW from FOX / Newsmax / CNN International — candidate Rakuten / Samsung TV+ / Akamai endpoints didn't resolve), `http_400` (manifest reject — France 24, Sky News), `http_404` (White House TV URL guessed), and an SSL handshake failure (TRT World).
- **Stage 3 ships with what resolves;** the broader channel-resolution investigation (DNS-over-HTTPS, geo-egress, candidate-URL rotation, deepening the prober to variant-fetch) is **carried forward to BACKLOG** as its own scoped effort, not folded into Stage 3.

### Honest constraints recorded at Stage 3 close
1. **Channels: real but currently limited** (4/16 playable; cycler fills the wall from whatever resolves).
2. **Feed: real** (BBC World, Al Jazeera, Guardian World, NPR World — render as native text).
3. **Ticker: real styling, sample data** (`SAMPLE` pill on every cell; honest by construction).

### Updated

- `docs/THREAT-MODEL.md` — Stage 3 row populated with `T-T1` (re-confirmed Stage 2 mechanism is what the UI surfaces) through `T-T5`: HTML-injection-into-feed (no WebView), non-helper-URL ingestion (by construction), cleartext-on-LAN (narrow allowance, Stage 6 TLS), schema-version pinning + structural parsing of `/api/channels` + `/api/feed`.
- `ARCHITECTURE.md` — Stage 3 §9: UI module map, the autofit + fit-width-letterbox model, lineup-selection priority order, helper-host boundary.
- `docs/BACKLOG.md` — 5 new entries (partial channel-resolution investigation, configurable feed-pane width, configurable / scalable panes, in-app menu / settings (which IS Stage 5), browser / PWA client with the load-bearing security caveat from `04-TECHNICAL-APPROACH.md §1` preserved verbatim).

### On-device evidence

- Checkpoint A (2026-06-02 20:03): 4 tiles cycled `[dw, redbull, dw, redbull]` reached LIVE after one PREPARE strike; screencap at `docs/findings/runs/stage-3-checkpoint-a-20260602-2004/`.
- Checkpoint B (2026-06-02 20:18): full assembled wall on the TV; Red Bull origin went down ~24 min in and settled `DEAD` honestly via the full ladder — C2 panels rendered; screencap at `docs/findings/runs/stage-3-checkpoint-b-20260602-2018/`.
- Polish (2026-06-03 11:09): `[cbs-sports-hq, nasa-tv, dw-news-en, redbull-tv]` lineup; NASA TV's master-OK/variant-FAIL settled DEAD per the 3-strike ladder, C2 panel rendered; screencap at `docs/findings/runs/stage-3-polish-20260603-1109/`.

### Standing rules held
- **WyzeGrid:** re-enabled on `.182` at the end of every device run. No soak windows opened during Stage 3.
- App test suites green: `TileSlotResolverTest`, `HelperClientParseTest`, `HelperClientFeedParseTest`, `RelativeTimeTest`, `SampleTickerSourceTest`, `LineupSelectorTest` + Stage 1/2 carry-over.
- Helper test suites green: `test_feeds_seeder` (7) + prior 115 = 122.


### Stage 1 (apparatus + skeletons; GATE preliminary — long soak pending)

#### Added — TV app
- Compose-for-TV + Media3 (HLS only) Android TV app skeleton, target Onn 4K (Android 14 / API 34).
- `app/build.gradle.kts` pinned to WyzeGrid's validated versions (AGP 8.7.3, Kotlin 2.0.0, Compose BOM 2024.11.00, Media3 1.5.0).
- `MainActivity` with two modes via intent extras: default placeholder + soak harness.
- `StreamSpec` / `StreamPlayer` / `StreamPlayerManager` — ExoPlayer wrapper with lifecycle discipline; HLS-only by construction (RTSP rejected by `StreamSpec` constructor + unit test).
- Soak harness (`com.mymts.soak.*`) emitting structured pipe-delimited logcat lines on tag `MYMTS_SOAK` for host-side parsing.
- Public-live-HLS fixture pools (`SoakFixtures.LIVE` + `SoakFixtures.STABLE`).
- `BuildConfig.DEFAULT_MAX_TILES` config-driven from `gradle.properties:MYMTS_DEFAULT_MAX_TILES` so re-homing later is a property change, not a code change.
- 9 unit tests (StreamSpec scheme guard, SoakFixtures invariants).

#### Added — helper
- Python/FastAPI helper skeleton with pinned `/health` schema (`schema_version: 1`).
- Structured JSON logging with paranoid redaction pass (Bearer/Authorization/X-API-Key/internal-IPv4s).
- Local-dev Dockerfile + docker-compose (non-root, all caps dropped, read-only rootfs, tmpfs `/tmp`, `no-new-privileges`, cpus/mem caps).
- Phantom (demo / offline) mode hook surfaced via `/health.phantom`.
- 12 helper unit tests (health schema, log-redaction).

#### Added — apparatus
- `scripts/soak.sh` — host-side soak runner; installs APK, starts soak mode, captures `events.log` + `meminfo.csv` per run.
- `scripts/parse-soak-log.py` — turns a run directory into the markdown table that lives in the finding doc.
- `scripts/deploy.sh` — Stage 1 dev-sideload (Stage 6 will replace with signed-install update path).
- `docs/findings/01-onn4k-tile-budget.md` — method + interim default + the long-soak command.
- `docs/findings/runs/20260530-method-validation-6t-stable/` — first run, 12 min at 6 tiles, stable pool, PSS ~100 MB, no leak signal in that short window. Method-validation only.

#### Documented
- `ARCHITECTURE.md` — Stage 1 module map; WyzeGrid pattern reuse + hard boundary (no RTSP/camera/Wyze-bridge wiring).
- `docs/OPERATIONS.md` — toolchain versions, single-command runners, soak instructions.
- `docs/THREAT-MODEL.md` — unchanged; still skeleton.

### Gate status — Stage 1 closed 2026-06-01

- **Leak behavior: PASS.** v3 captured 681 post-warmup samples over 11.5 h; PSS slope −15.97 KB/min, median 124.0 MB (band 114.3–142.0 MB). No leak.
- **6-tile sustained capacity: NOT YET MEASURED, deferred to Stage 2.** Three soaks degenerated to ~1 active tile within 5–8 min because (a) the validated fixture pool was still mostly VOD-as-live test assets that fall off the live window and never re-enter, and (b) `StreamPlayer` does not recover stalled tiles and dishonestly continues to report `state=LIVE` over a frozen surface (a direct Trust Bar **C3** violation, observed in all three soaks). Both fixes are Stage 2 work; the capacity re-soak runs after both land. See `docs/STAGE-2-PLAN.md`.
- **Configurable default:** `MYMTS_DEFAULT_MAX_TILES=6` unchanged; annotated as a *target to be validated in Stage 2*, not a measured-safe number.
- **Stage 2 unblocked.** Entry point is `docs/STAGE-2-PLAN.md §B.1` (player robustness) → §A (the helper) → §C (the capacity re-soak).

### Closing housekeeping
- WyzeGrid re-enabled on `.182` and verified back to normal operation (foreground, watchdog service active).
- `docs/findings/runs/**/events.log` is gitignored going forward (raw captures are 30+ MB and regenerable; the small files + the parsed summary in the finding doc are the durable record).
- The v1 and v2 run directories were removed (superseded). The v3 directory is retained as the closing record.

## Stage 2 Part A — the helper (shield first)

Per the Stage 2 prompt's `A → B → C` sequencing override (recorded in `docs/STAGE-2-PLAN.md`).

### Added — helper internals
- `db.py` + numbered SQL migrations + `meta.schema_version` (initial migration creates `sources`, `feed_items`, `channels`, `meta`).
- SSRF-safe `fetcher` — single outbound primitive used by both the RSS poller and the channel prober. Enforces https-only, up-front DNS resolution, rejection of RFC1918 / loopback / link-local / CGNAT / IPv6 ULA / IPv6 loopback addresses, bounded body, bounded time, re-validated redirects. Injectable resolver for tests.
- `feeds/` — defensive RSS/Atom parser (feedparser + defusedxml auto-loaded, HTML stripped to plain text), sqlite store with dedup + retention sweep, 5-min poller with per-source error isolation, `/api/feed` + `/api/feed/sources`.
- `channels/` — structured URL validation (scheme + IDNA host + port + path), idempotent seed loader (operator-curated `seed.json` upserts on every boot), 30-min prober that validates upstream `#EXTM3U` before marking a channel `live`, `/api/channels` masking `current_url` to `null` whenever `status != "live"` (Trust Bar C3).
- `phantom.py` — phantom resolver that raises on every hostname lookup + a preload that seeds synthetic fixtures so phantom mode shows real state in `/health` / `/api/feed` / `/api/channels` with zero outbound traffic.

### Added — `/health` (still `schema_version: 1`)
- `feeds: {sources_count, items_count, stale_sources, last_poll_at}`.
- `channels: {channels_count, live_count, unavailable_count, last_probe_at}`.
- Adding fields is backward-compatible; bump only on remove/rename. Contract test pins the shape in `tests/test_health.py` + `tests/test_api.py`.

### Added — NAS deploy
- `helper/Dockerfile` pins the runtime base by digest (`python:3.13.1-slim-bookworm@sha256:031ebf3cde…`); the tag stays in `FROM` for human readability but the digest is source of truth.
- `helper/deploy/docker-compose.nas.yml` — non-root (uid 10001), `read_only: true`, `cap_drop: [ALL]`, `no-new-privileges`, tmpfs `/tmp`, cpu/mem caps, healthcheck wired to `/health`, named Docker volume for state, dedicated bridge network, host port `8091` bound for the TV.
- `scripts/deploy-helper.sh` — rsync source → render compose → render `.env` → `docker compose build --pull && up -d` → poll `/health` until the new SHA is reflected. **Deployed to `<USER>@<HOST>:/srv/docker/mymts-helper/`**; verified `/health` reports the deployed SHA, both seeded channels probed live from the NAS network.
- Deploy doc, backup/restore commands, image-digest-refresh recipe, and the claude-status-bot `/health` contract recorded in `docs/OPERATIONS.md`.

### Standards held
- All 115 helper tests green. ruff clean.
- Conventional commits, secrets only via `.env`.

### Stage 2 progress
- **Part A — helper: DONE** (this entry).
- **Part B — player robustness:** next session per the Stage 2 prompt's checkpoint instruction.
- **Part C — capacity probe:** after B.

## Stage 2 Part B — player robustness (Trust Bar C3 fix)

Full details + on-device evidence in `docs/findings/02-player-state-machine.md`.

### Added
- **`com.mymts.player.LivenessTracker`** — pure Kotlin state machine that derives liveness from actual frame arrival, not ExoPlayer's reported `playWhenReady`/`playbackState`. States: `CONNECTING / LIVE / STALE / RECOVERING / DEAD / OFFLINE`. Configurable thresholds + recovery-ladder shape + backoff schedule, injectable clock for tests.
- **17 unit tests** in `app/src/test/java/com/mymts/LivenessTrackerTest.kt` covering: threshold boundaries, the recovery ladder (PREPARE → REINIT × 2 → SETTLE_DEAD), anti-loop discipline (DEAD is absorbing; late frames don't silently revive), the never-connected path (CONNECTING → DEAD via the 30 s connecting timeout), configurable thresholds.
- **`StreamPlayer` rewrite** — drives the tracker via a 2 s handler tick. Feeds the tracker from both `onRenderedFirstFrame` and `onDroppedVideoFrames` (any decoder callback = liveness signal). Emits `EV=STATE`/`EV=RECOVERY`/`EV=DEAD` on transitions. Widens `state` over `{CONNECTING, LIVE, STALE, RECOVERING, DEAD, OFFLINE}`. SoakHarness tile UI updated with color mappings for the new states.
- **`scripts/parse-soak-log.py`** counts state transitions, recovery strikes (by kind), and DEAD settlements per tile.

### Chosen values (with reasoning in the finding doc)
- `staleThresholdMs = 15 s`
- `connectingThresholdMs = 30 s`
- `maxRecoveryAttempts = 3`
- `backoffMs = [2 s, 8 s, 30 s]`
- `tickIntervalMs = 2 s`

### On-device demonstration (`.182`, helper at `<LAN_IP>:8091`)
- **Healthy stream (helper-resolved `redbull-tv`, 75 s):** stayed `LIVE`; one transition `CONNECTING → LIVE`; no spurious `STALE`.
- **Unreachable URL (`httpbin.org/status/404`, 150 s):** full recovery lifecycle captured — `CONNECTING → STALE → PREPARE → STALE → REINIT → STALE → REINIT → DEAD` in ~120 s. After `DEAD`, no further events fire for the tile. This is the Stage 1 v3 silent-staleness failure shape, now caught and surfaced honestly within a bounded window.

### B.2 — dw-news-en investigation
- Stage 2 measurement (180 s solo, WyzeGrid disabled): **0.12 callbacks/s** (down ~70× from Stage 1's 8.2 / s under 6-tile contention).
- 21 STATE transitions over 180 s, all `LIVE ↔ STALE` oscillations with median recovery of ~75 ms — well before strike 1's 2 s backoff fires. The state machine handles bursty streams correctly.
- **Buffer-sizing change deferred to Part C** per the Part B prompt — the loosen-vs-keep decision interacts with memory-per-tile, which directly affects the capacity ceiling Part C measures.

### Trust Bar C3 now enforced at both ends
- Helper API masks `current_url → null` whenever `status != "live"` (Part A).
- TV player surfaces `STALE`/`DEAD` to the UI; the wall **cannot** show a `LIVE` badge over a frozen surface (Part B).
- T-T1 in `docs/THREAT-MODEL.md` closed with this evidence.

### Standards held
- All app unit tests green (17 LivenessTracker + 5 SoakFixtures + 4 StreamSpec).
- WyzeGrid was disabled on `.182` for the integration runs and **re-enabled afterward** per the documented recipe.

### Stage 2 progress
- **Part A — helper: DONE.**
- **Part B — player robustness: DONE** (this entry).
- **Part C — capacity probe: next**. Now unblocked: the helper resolves real streams (A) and the player is honest about staleness (B), so Part C can measure the true sustainable tile ceiling on this hardware.

## Stage 2 Part C — capacity probe bracket (historical entry; superseded by the closeout section below)

Full details in `docs/findings/01-onn4k-tile-budget.md §"Stage 2 Part C — Escalating-probe bracket"`.

### Bracketed
- **Sustainable ceiling on Onn 4K (Amlogic S905Y4) = N=4** based on an escalating 1→6 sweep at 8 min each on `.182` with WyzeGrid disabled, helper-resolved real live channels.
    - N=1..4 — all tiles LIVE, low drops, no recovery strikes (HEALTHY)
    - N=5 — dw-news-en tiles drop ~37% of frames; `dumpsys` itself starts timing out under system_server contention (DEGRADED)
    - N=6 — every redbull-tv tile fires 2–3 RECOVERY PREPARE strikes inside the window; dw-news-en tiles never produce a first frame (DEGRADED)

### Added
- `scripts/probe-tile-count.sh` — host-side escalating-probe runner. Pulls live channels from the helper's `/api/channels`, cycles them across N tiles, runs an 8 min sweep, captures `MYMTS_SOAK` events + `dumpsys meminfo`, emits a per-tile `summary.json`. The Part C procedure is itself the **per-device portability deliverable**: re-homing to fresh hardware is "run the probe, record that box's number, set the config."
- Multi-URL ad-hoc mode in `MainActivity` (`--es urls "U1,U2" --es labels "L1,L2" --es ids "I1,I2"`) so the soak harness can be driven against arbitrary URL lists without rebuilding.
- Expanded helper seed (`france24-en`, `al-jazeera-en`, `sky-news`, `cgtn-en`, `trt-world`) — same Stage 1 finding reproduced (those 5 are blocked from this network path). The 2 helper-verified-live channels (`dw-news-en`, `redbull-tv`) cycle across N tiles for the probe.
- Helper Dockerfile dependency-pinning fix (hatchling 1.30 rejects the duplicate-path `force-include` directive 1.27 tolerated).

### Changed
- `MYMTS_DEFAULT_MAX_TILES = 4` in `gradle.properties` with bracket-evidence rationale embedded as a comment. The number is no longer "validation-pending"; it's the measured-safe ceiling for this device profile.

### dw-news-en — answered
- Stage 1 v3's 8.2 / s `onRenderedFirstFrame` rate was a **6-tile contention effect**. Solo measurement at N=1 produced 0.13 / s; healthy N=4 produced the same ~0.13 / s per tile. The stream is fine as a fixture under healthy load.

### Buffer floor — decision
- Kept at Stage 1 values (min=1.5 s / max=4 s / playback=0.5 s / 4 MB target). Rationale in the finding doc — changing two variables at once (floor + tile count) would muddy the bracket signal, and the ceiling at N=4 holds cleanly. Re-visit if/when memory budgets tighten for unrelated reasons.

### Long soak — in flight
- Run id: `long-soak-4t-20260601-2100`. 4 tiles, 6 h, helper-resolved live channels (cycled `dw-news-en` × 2 + `redbull-tv` × 2). `caffeinate -i nohup`. Early-render check passed (all 4 tiles reached LIVE within 90 s of launch). ETA `2026-06-02 ~03:00 PDT`. Results in a follow-up commit; the closeout session re-enables WyzeGrid on `.182`.

### Standards held
- 17 LivenessTracker tests + 9 prior app tests still green.
- Helper 115 tests green.
- WyzeGrid is disabled on `.182` for the long soak window; re-enable command documented and queued for closeout.

### Stage 2 status (this section — interim; final state in the closeout section below)
- ✅ Part A — helper.
- ✅ Part B — player robustness.
- 🟡 Part C — bracket done; long soak unattended (at this point in the changelog timeline). _Final Part C state recorded in the next section._
- Stage 2 closes when the long soak ends and the closeout session re-enables WyzeGrid + commits the long-soak result. _(That closeout happened — see next section.)_

## Stage 2 Part C — harness telemetry fix + v2 long-soak + closeout

### Harness telemetry bug + fix
- The first long-soak attempt (`long-soak-4t-20260601-2100`) came back with a clean memory trace but **zero per-tile telemetry** (`events.log` empty, `summary.json.per_tile = {}`). Root cause: `scripts/probe-tile-count.sh` pulled logcat as a **one-shot `adb logcat -d -s MYMTS_SOAK` at the END of the 6 h sleep**. Over 6 hours the Android logcat ring buffer wrapped many times over; every `MYMTS_SOAK` event was overwritten before the dump fired.
- Secondary bug: the meminfo sampler subshell inherited the parent script's `set -euo pipefail` and exited on the first transient `dumpsys` reply that didn't match a field — sampling died ~114 min in (revealed when bash flushed `Terminated: 15` at end-of-run).
- Fix (commit `3c101c7`): continuous background `adb logcat -s MYMTS_SOAK` stream throughout the run (with a supervisor that re-launches if `adb` blips), `adb shell logcat -G 16M` to grow the on-device buffer, `set +e` inside both background subshells, `pkill -TERM -P` at end-of-run to actually tear down the supervisor's `adb` children.
- Proof-of-fix (18 min N=4 run, `probe-fixproof-20260602-0838-4t`): events.log grew monotonically (5.8 KB → 76 KB by +11 min), all 4 tiles LIVE, 270 STATE transitions captured, meminfo flowed at 1/min throughout.
- This failure mode is on permanent record so the same trap doesn't catch a future stage.

### Long-soak v2 (`long-soak-4t-v2-20260602-0857`) — 5.13 h healthy + synchronized external-event DEAD
- **5.13 h of clean N=4 LIVE evidence.** Per-tile lifecycle now fully verifiable (the harness fix held over the full 6 h): each tile ~1,140 LIVE↔STALE oscillations from the dw-news-en burst pattern characterized in Part B, every one self-resolving in ~75 ms — zero false LIVE labels over a frozen surface across 5+ hours.
- **PSS in mature steady state (minutes 120 → 305): slope −9.53 KB/min over 184 min — PASS** the ±50 KB/min leak threshold. The +74 KB/min aggregate across the full healthy window is dominated by a one-time settling step ~h1.5 (112 → 127 MB) as caches/connection pools reach equilibrium; per-hour median h2=129, h3=128, h4=127 — flat steady state, not a leak.
- At h5.13 all 4 tiles lost frames within a **12-second window**, hitting two distinct Akamai CDN origins (`rbmn-live.akamaized.net` + `dwamdstream102.akamaized.net`) simultaneously. The state machine ran the same 3-strike ladder on each tile (PREPARE → REINIT → REINIT → SETTLE_DEAD) and all 4 settled `DEAD` within a 13-second window. After `DEAD`: no thrashing, no CPU burn, PSS drops to 82.4 MB as `releaseInternal()` frees the 4 decoders. This is the C2 anti-loop discipline working exactly per design under a real-world adverse event.
- **What the v2 run proves:** the harness fix held; the state machine is honest under sustained real load; the recovery ladder + anti-loop behave correctly under a real network outage; no memory leak in the mature steady state.
- **What it does NOT prove:** 6 h of continuous 4-tile LIVE. The strict criterion was missed by a network event, not by capacity.

### Stage 2 closeout — **CLOSED 2026-06-02**

**Sustainable ceiling on the Onn 4K (Amlogic S905Y4) = N=4** — bracket-evidenced (1→6 escalating sweep) and **5.13h-LIVE-confirmed** under verified per-tile telemetry in the v2 long soak. The strict "6.0h continuous LIVE" criterion was missed by a synchronized external Akamai network event at h5.13, **not** by capacity or memory pressure — failure was synchronized across two unrelated CDN origins inside a 12-second window (capacity failures are staggered and load-correlated; this signature is unambiguously external). Closed on the evidence rather than chase a pristine run a random network blip can deny.

**Three permanent records carried forward in this stage:**
1. **N=4 ceiling with honest framing.** Stated as bracket-evidenced + 5.13h-LIVE-confirmed (not "6h confirmed"). Re-readers see the precise evidence and the network-event caveat together; the closing rationale lives in `docs/findings/01-onn4k-tile-budget.md §"Stage 2 closeout"`.
2. **Harness telemetry bug + fix as a failure-mode-on-record.** Documented above: one-shot end-of-run logcat dump → ring-buffer wrap → zero per-tile evidence over 6h. Fix is continuous background stream + 16 MB buffer growth + `set +e` in subshells + `pkill -TERM -P` cleanup. Recorded so this trap cannot catch a future stage silently.
3. **Graceful degradation as C2/C3 validation.** When the external network event hit at h5.13, the recovery ladder ran (PREPARE → REINIT → REINIT → SETTLE_DEAD), the anti-loop discipline held (DEAD is absorbing), all 4 tiles settled honestly within 13 s, and PSS dropped to 82.4 MB confirming the decoders were released. The state machine **succeeded** at its hardest job: surfacing real-world adverse failure honestly. C2 + C3 validated under real-world conditions, not just synthetic ones.

`MYMTS_DEFAULT_MAX_TILES = 4` in `gradle.properties` — the **measured-safe ceiling** for this device profile (no longer "validation-pending"). The `Awaiting long-soak confirmation` annotation has been dropped from the comment block.

**What closing Stage 2 means in total:** the security shield (helper), the honest self-recovering player, and the verified hardware capacity are all done. Stage 3 (the real wall UI) is unblocked.

### Standing rules held
- WyzeGrid re-enabled on `.182` at end of session (foreground, watchdog service running). Verified.
- App + helper test suites green.

### Run-artifact hygiene at closeout
- Dropped `docs/findings/runs/long-soak-4t-20260601-2100/` (telemetry-bug-invalidated v1 — the failure mode is permanently recorded above; the run dir itself is dead weight).
- Dropped `docs/findings/runs/probe-fixproof-20260602-0838-4t/` (proof-of-fix run, superseded by the 6h v2 which independently demonstrates the fix held).
- Kept `docs/findings/runs/long-soak-4t-v2-20260602-0857/` — the durable evidence record (5.13h healthy + DEAD evidence + memory record).
- Kept the `probe-20260601-2007-{1..6}t` bracket-sweep dirs.

### Stage 2 status (closed)
- ✅ Part A — helper.
- ✅ Part B — player robustness.
- ✅ Part C — capacity probe. **N=4 ceiling, bracket-evidenced + 5.13h-LIVE-confirmed, terminated by external network event.**

### Known limitations carried forward
- `SoakFixtures.LIVE` URLs are public broadcaster HLS endpoints and decay over time. The first long-soak run may need updated URLs before it produces useful data; the rule is to edit the fixture file in a single commit, never silently drop dead fixtures from a result.
- Helper Dockerfile pins by tag (`python:3.13.1-slim-bookworm`), not by digest. Digest pinning lands in Stage 6 hardening.
