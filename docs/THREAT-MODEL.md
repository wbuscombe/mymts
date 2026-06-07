# THREAT-MODEL

> **Status (Stage 0):** Skeleton. The architecture has been chosen specifically to *remove* the threat classes that filled the prior web-app review rounds rather than to mitigate them. Threats below are framed against the native-TV-app + minimal-helper architecture and will be filled in as concrete mechanisms land.

Cross-references: `docs/foundation/02-TRUST-BAR.md` (every threat below maps to one or more A/B/C principles), `docs/foundation/04-TECHNICAL-APPROACH.md` (mechanism map).

---

## Trust boundaries

| Boundary | Inside | Outside | Trusted to |
|---|---|---|---|
| **TV app ↔ helper** | TV (operator's owned device) | Helper (NAS service) | Hand the TV ready-to-play addresses and clean structured feed items; nothing more. |
| **Helper ↔ upstream news sources** | Helper | Upstream RSS / web | The helper trusts *nothing* here — defensive parsing, structured output only. |
| **Helper ↔ upstream stream sources** | Helper | YouTube / HLS endpoints | The helper resolves addresses in isolation; bounded outbound; fail-closed. |
| **Helper ↔ NAS** | Helper container | Other NAS services | Least authority; cannot pivot. |
| **App ↔ Android OS** | App | OS | App requests only the permissions it needs. |

---

## Threat catalog (Stage 0 outline — to be populated stage-by-stage)

Each entry will have: **Threat**, **Affected boundary**, **Likelihood**, **Impact**, **Mitigation**, **Residual risk**, **Traces to** (Trust Bar principle).

### Threats that the architecture removes (no longer applicable)

- **Browser-kiosk session sharing across operator's subdomains.** Removed by having no browser. *(Was A5 risk in web architecture.)*
- **Service-worker bad-bundle fleet cascade.** Removed by signed-install update path with prior-version recoverable. *(Was Op B1/B5 risk.)*
- **XSS via untrusted RSS markup rendering in DOM.** Removed by rendering feed as native text in Compose, never as HTML. *(Was A1 risk.)*
- **Reader-pane cross-device auth handoff.** Removed by deferring article reading; v1 shows a safe on-screen excerpt only. *(Was B4 risk.)*
- **CSRF on backend cookie-authenticated endpoints.** Removed by not having a public web backend. *(Was A6 risk.)*

### Threats present in this architecture (Stage 2 helper detailed below; rest filled in as stages land)

#### Helper-side threats (Stage 2 — populated)

**T-H1. Hostile RSS feed content → helper parser / TV-side rendering.**
*Likelihood:* high (every upstream is treated as hostile by policy).
*Impact:* TV-side XSS or RCE if the helper passes raw markup to the TV; helper-side XXE / entity-bomb / DoS if the parser is naive.
*Mitigation:* `feedparser` auto-loads `defusedxml` (we declare it as a runtime dependency) — XXE, entity bombs, DOCTYPE attacks neutralised at parse time. Item titles + summaries pass through an HTML stripper that drops `script` / `style` / `iframe` / `object` / `embed` content entirely and decodes entities. The TV is told the truth: this is plain text and renders it as native text widgets — no HTML rendering path exists on the TV side. Per-source error isolation in the poller prevents one bad source from stalling others.
*Residual risk:* a brand-new feedparser CVE could let crafted markup escape sanitization. We pin feedparser and defusedxml to exact versions, and the upstream advisory feed is on the operator's bot.
*Traces to:* **A1** (assume hostile input), **A2** (blast radius — TV is plain-text-only), **C2** (per-source error isolation).

**T-H2. SSRF / DNS rebinding via channel URL or RSS source registration → pivot into private network.**
*Likelihood:* medium (any operator who pastes a URL is a vector; same with any future API that takes URLs).
*Impact:* helper turned into a probe of the operator's internal network; credentials to other internal services exfiltrated.
*Mitigation:* single SSRF-safe fetcher (`mymts_helper.fetcher`) used by both the RSS poller and the channel prober. It resolves DNS up-front, rejects RFC1918 / loopback / link-local / CGNAT / IPv6 ULA / IPv6 loopback before opening a socket, refuses non-https, refuses URLs with userinfo, bounds body size, bounds total time, re-validates each redirect hop. Channel URLs additionally validated by structured parsing on registration (scheme + IDNA host + port {None, 443} + path looks like m3u8).
*Residual risk:* time-of-check-time-of-use — a hostname can resolve to a public IP at validation time and a private IP at fetch time. Documented for Stage 6 hardening (egress proxy with allowlist).
*Traces to:* **A1**, **A2**, **A4**.

**T-H3. Compromised upstream news source (server) returns content shaped to attack the parser.**
*Likelihood:* low-to-medium.
*Impact:* same as T-H1.
*Mitigation:* defensive parsing + plain-text-only rendering (above). Bounded body (`8 MB`) per fetch prevents memory-pressure attacks.
*Residual risk:* zero-day in feedparser. Same mitigation as T-H1.
*Traces to:* **A1**, **C6**.

**T-H4. Helper compromise → blast radius on the NAS.**
*Likelihood:* low (small attack surface).
*Impact:* potential pivot to other NAS containers / NAS host.
*Mitigation:* container is non-root (uid 10001), `read_only: true` rootfs, all capabilities dropped, `no-new-privileges`, explicit `cpus`/`mem_limit`, tmpfs `/tmp`. Container is on its own bridge network (`mymts-net`); does NOT join the unrelated host container's network or any other container's network. No `docker.sock` mount. Secrets injected at runtime via `.env` (never baked into image). State is in a named Docker volume — losing the container doesn't lose the data, and the operator inspects/backs up via documented commands.
*Residual risk:* container escape via kernel/runtime CVE — addressed by Stage 6 monthly image digest refresh + kernel-patching cadence.
*Traces to:* **A2**, **A4**, **A7**, **A9**.

**T-H5. Stale channel URL shown to TV as "live".**
*Likelihood:* high (live HLS URLs decay constantly).
*Impact:* TV sees a "live" channel that won't play (Trust Bar C3 violation: silent staleness).
*Mitigation:* prober's `_record` only sets `status='live'` + `current_url` when the upstream actually returned a valid HLS manifest. On any failure (HTTP non-2xx, not-`#EXTM3U`-prefixed body, network/SSRF rejection), it sets `status='unavailable'`, `current_url=NULL`. The `/api/channels` response masks `current_url` to `null` whenever `status != 'live'` — this is asserted by a contract test in `helper/tests/test_api.py`.
*Residual risk:* a channel's URL passes the manifest check but fails when the TV plays it. The Stage 2 player fix (B.1, `StreamPlayer.STALE`) is the second-line defence: if the surface stops receiving frames, the player surfaces stale honestly. Two-layer protection.
*Traces to:* **C3** (staleness is never silent).

**T-H6. Phantom mode quietly making outbound calls.**
*Likelihood:* low (a contributor would have to actively break the contract).
*Impact:* phantom mode is the operator's onboarding + CI contract; a leak undermines the no-network guarantee.
*Mitigation:* `phantom.phantom_resolver` raises `PhantomNetworkBlocked` on every hostname lookup. A CI contract test (`test_phantom_app_boots_and_serves_synthetic_data`) asserts the full app + `/api/feed` + `/api/channels` flow completes without any outbound call. Any future code path that bypasses the fetcher and dials raw sockets would still need to resolve a hostname through the OS — `PhantomNetworkBlocked` won't catch a literal-IP raw-socket dial, but that's a clear code-review signal that something's wrong.
*Traces to:* **A4**, **A8** (boundary is observable + fails safe).

**T-H7. Secret leakage through logs.**
*Likelihood:* low (helper holds no operator credentials yet).
*Impact:* if and when the helper holds upstream API keys, log lines could leak them.
*Mitigation:* structured JSON logs with a paranoid redaction pass (`mymts_helper.log.redact`): strips Bearer/Authorization/X-API-Key entire-line content + every RFC1918 / loopback / link-local IPv4. Tested in `test_log_redaction.py`. Secrets are env-vars only, never written to any file the helper touches.
*Residual risk:* a future contributor logs a secret directly via a non-standard logger or print(). Pre-commit gitleaks + log redaction is the layered defence.
*Traces to:* **A7**.

#### TV-side threats (Stage 3 — populated)

**T-T1. Frozen tile labelled "LIVE" (silent staleness in the wall).**
**Closed in Stage 2 Part B** and confirmed in the Stage 3 wall UI. Mechanism: `com.mymts.player.LivenessTracker` derives liveness from actual frame arrival via `onRenderedFirstFrame` + `onDroppedVideoFrames`, transitions to `STALE` when `last_frame_age_ms > 15 s`, runs a bounded recovery ladder (PREPARE → REINIT × 2 with 2 s/8 s/30 s backoff), and settles into `DEAD` after 3 strikes. The Stage 3 wall `WallTile` composable renders this state honestly: `STALE`/`RECOVERING` dim the surface and label the badge transparently; `DEAD`/`OFFLINE` collapse to a quiet near-black panel with a ghost channel label and no error chrome (Trust Bar **C2**). The C3 contract is enforced at both ends — helper API masks `current_url → null` when not live (Part A); player + wall UI surface honest `STALE`/`DEAD` (Part B + Stage 3). On-device evidence: Stage 3 Checkpoint B Red Bull origin failure at 20:42–20:44, and Stage 3 polish-pass NASA TV master-OK/variant-FAIL — both settled `DEAD` after the full ladder and rendered the C2 panel correctly.
*Traces to:* **C2** (graceful degradation), **C3** (no silent staleness).

**T-T2. HTML smuggled into the feed pane via RSS title/summary → XSS-equivalent on the TV.**
*Likelihood:* high (any compromised or hostile RSS source is the vector).
*Impact:* if the wall rendered RSS markup as HTML, an attacker could inject DOM that exfiltrates state, navigates to an attacker page, or layers attacker UI over the wall — even on a TV. Trust Bar A1 violation.
*Mitigation:* **no HTML rendering path exists on the TV.** The helper strips HTML in `feeds/parser.py` and emits inert plain text. The wall's `FeedPane` renders every field as Compose `Text` (`com.mymts.ui.wall.FeedRow`); the source code contains no `WebView`, no `AndroidView { WebView(...) }`, no HTML-rendering library on the classpath. There is *no API in the wall* that could render an attacker string as markup. The boundary is enforced by construction, not by escaping discipline.
*Residual risk:* a future contributor pulls in a Markdown/HTML renderer for feed items. Code-review gate + this threat-model entry are the layered defences.
*Traces to:* **A1** (assume hostile input), **A2** (blast radius — TV can render text only).

**T-T3. Wall pointed at a non-helper stream URL → bypasses the helper's SSRF + manifest validation.**
*Likelihood:* low (would require code change in the wall; the wall has no UI to enter URLs).
*Impact:* if the wall accepted operator- or attacker-supplied stream URLs, it would lose the helper's SSRF + structured-URL + manifest checks; could be steered into private-network probing or hostile-stream playback.
*Mitigation:* by construction. The `VideoGrid` consumes `Channel` objects from `ChannelsRepository`, which reads only `/api/channels` from the helper at `BuildConfig.HELPER_BASE_URL`. The wall never holds, accepts, or constructs a stream URL from any source other than the helper's `current_url`. Compose `LineupSelector` picks which helper channels to surface; it cannot synthesize one. `TileSlotResolver.resolve()` `error()`s if a `Slot.Playing` would carry a null URL.
*Residual risk:* a future "add custom channel" feature would re-introduce this surface; threat-model it fresh at that point.
*Traces to:* **A1**, **A4** (least-privilege egress).

**T-T4. Tampering / sniffing on the TV ↔ helper LAN link.**
*Likelihood:* low (operator's LAN; no public-internet exposure).
*Impact:* a LAN-local attacker could inject feed items, channel URLs, or modified `/api/feed` content. Worst case: an injected channel URL pointing at attacker-controlled HLS → wall plays attacker content.
*Mitigation (Stage 6 TLS track, cutover complete 2026-06-04):* the helper serves **HTTPS only on port 8443**, using a self-signed RSA-4096 certificate with SAN `IP:<LAN_IP>, DNS:mymts-helper` (10-year validity). The TV app's `network_security_config.xml` carries a domain-config for `<LAN_IP>` that pins **only the helper's own self-signed cert** as a trust anchor — the Android system CA bundle is explicitly NOT a trust anchor for this host, so even a global-CA-signed MITM cert is refused. The cleartext exception for `<LAN_IP>` was **removed** in the at-the-box finale Step 1 (operator present at `.182`); `cleartextTrafficPermitted="false"` for the helper host now, matching the base-config. The helper's compose was also stripped of the `8091:8091` host port mapping and the `PORT=8091` env var; the container is now reachable only on the LAN at `https://<LAN_IP>:8443`. Telemetry-verified on `.182`: 4 tiles fire `EV=TILE_READY` over HTTPS with zero HelperClient trust errors, zero cleartext errors, `/health` returns the deployed SHA. The private key (`helper.key`) lives only on the NAS at `/srv/docker/mymts-helper/_secrets/`, mounted into the container read-only at `/etc/ssl/mymts/` with ownership UID 10001 and mode 600; the public certificate is committed at `app/src/main/res/raw/helper_cert.pem` (public material — committable; `.gitignore` excludes the matching private key + any other `*.key`/`*.pem` under `helper/`).
*Residual risk:*
- Self-signed cert rotation requires an APK rebuild (the public cert is embedded as a trust anchor). Loss of the private key means generating a new cert, replacing `helper_cert.pem`, and shipping a new APK build via the Stage 6 signed-update path. This is acceptable for a single-operator-on-private-LAN posture.
- A LAN-local attacker with a global-CA-signed cert for `<LAN_IP>` could not MITM because the system CA bundle is explicitly NOT a trust anchor for this host. The remaining risk is a stolen private key (kept on the NAS) — same posture as a Stage 6 signed-update keystore loss.
*Traces to:* **A2** (trust boundaries), **A4** (least authority), **A1** (assume hostile input).

**T-T5. Wall consumes an attacker-shaped `/api/channels` or `/api/feed` JSON.**
*Likelihood:* low-to-medium (requires either a compromised helper or T-T4 MITM).
*Impact:* malformed JSON could crash the wall, drop into an unsafe code path, or inject hostile field values.
*Mitigation:* `HelperClient` pins `schema_version == 1` and **refuses** any other version (`HelperException`). Fields are parsed structurally: `current_url` null/blank → channel marked unplayable; `title` null → feed item silently skipped; `status` enum normalized through a single `parseStatus`. Field parsing is unit-tested in `HelperClientParseTest` + `HelperClientFeedParseTest` against null-injection, missing-field, unknown-status, and bad-schema-version cases. Parse failures on the wire surface to the UI as the existing staleness signal (`feed not updating` / `helper unreachable`), not as an empty list pretending to be current state.
*Traces to:* **A1**, **C3**.

#### TV-side threats (Stage 5 — additions)

**T-T6. Stage 5 menu introduces a new local-state surface (`LineupStore`).**
*Likelihood:* low (operator-only, on-device).
*Impact:* if the persistence held credentials or routable URLs, a compromised app process or a sideloaded debugger with `run-as com.mymts` access could exfiltrate them.
*Mitigation:* by construction. `LineupStore` stores only short slug strings the operator chose (e.g. `cbs-sports-hq`, `dw-news-en`) — no PII, no credentials, no absolute paths, no URLs. The slug is an opaque key the helper resolves to a current URL via `/api/channels`; if a slug were re-targeted later, the lineup just rebinds to whatever the helper now associates with that slug. Format is `SharedPreferences("mymts_lineup") → key "lineup_overrides"` holding a JSON array `[slot, slug, slot, slug, …]`. The decoder tolerates corrupt blobs (resets to empty + logs once) rather than crashing. Stage 5 codec round-trip is pinned by `LineupStoreCodecTest`.
*Residual risk:* none beyond the Android sandbox boundary itself, which is the same boundary the rest of the app relies on.
*Traces to:* **A7** (least-secret persistence), **B5** (operator-only data discipline).

**T-T7. Menu UI could misrepresent a channel's live/offline status.**
*Likelihood:* medium (every interaction surface where helper truth is restated is a new opportunity for drift).
*Impact:* if the picker presented an offline channel as if it would play, the operator could assign a slot expecting video and get nothing — a sibling of T-T1 (silent staleness) at the menu layer.
*Mitigation:* the picker reads each channel's status from the same `Channel.isPlayable` (`status == LIVE && currentUrl != null`) the player consumes. The picker decorates every cycle entry with `live` or `offline` text + colour. The wall's tile renders match — `Slot.Offline` produces the C2 panel with the channel's label, never a fake live tile. Both surfaces consume the same `TileSlotResolver.Slot` list (single source of truth), so the menu and the wall cannot disagree. Honesty pinned by `TileSlotResolverOverridesTest` cases (override-to-offline → `Slot.Offline`; override-to-vanished-slug → graceful fallback to default cycler).
*Residual risk:* a helper response with a stale `status` field. Mitigated by the existing `T-H5` (helper prober only sets `live` after a fresh manifest check) and Stage 2 `T-T1` (player-side liveness derived from actual frame arrival, not reported state).
*Traces to:* **C3**.

#### Other stages (filled in as they land)

- **T-A1: Malicious or buggy app sideload on the Onn box.** Stage 6.

**T-A2. Update mechanism bricking the TV or being subverted.**
*Likelihood:* medium (every update is a chance for a build to bring something nasty, or for an installer to push a bad APK).
*Impact:* worst case — the box ends up running an APK the operator did not authorise (impersonation / supply chain), or the upgrade fails open and leaves no working build on the device.
*Mitigation:*
- **Signed installs (auth).** Release APKs are signed with a v1+v2+v3 signing config tied to a keystore the operator controls. `app/build.gradle.kts` sources the signing material from `app/keystore.properties` (gitignored; modelled on `app/keystore.properties.example`) or matching environment variables. The deploy script calls `apksigner verify --print-certs` and **refuses to push** an APK whose certificate subject is the Android Debug stub (`CN=Android Debug,O=Android,C=US`). A build with no keystore configured falls back to debug-signing so dev builds still work, but `IS_RELEASE_SIGNED` in `BuildConfig` is `false` and the deploy refuses to ship it. Android itself refuses upgrades signed with a different key, so a stolen APK signed by anyone else cannot upgrade the operator's installation — only the holder of the keystore can ship updates.
- **Never-bricks (B1).** The deploy script installs the new APK with `adb install -r` (retain data + previous app remains installable in the rollback step). The old APK is preserved in `$MYMTS_ARCHIVE_DIR/archive/`. If anything fails between install and promotion, the rollback path reinstalls the prior known-good — the wall returns to whatever last worked, never to an absent app.
- **Always-a-way-back (B2).** `$MYMTS_ARCHIVE_DIR/known-good` names the currently-known-good APK filename; the pointer is updated **only** after the health gate passes. A failed deploy never overwrites the pointer, so manual rollback (`./scripts/deploy-app.sh --manual-rollback`) always reinstalls the last-good build. Older APKs are retained in the archive — recovery to any prior version is one `adb install -r -d`.
- **No silent bad-bundle cascade (B5).** Promotion is gated on `health_check.decide()` — a pure-Python function (15 unit tests in `scripts/test_health_check.py`) classifies the launch as `PASS` / `FAIL_ALL_DEAD` / `FAIL_DECODER_THRASH` / `FAIL_NOT_READY` / `FAIL_TIMEOUT` based on `MYMTS_SOAK` telemetry. A build that fails to start enough tiles, or starts decoders without ever rendering a frame (the b19b013 regression shape, now permanently caught), is **never** declared the new known-good. The Stage 3 fix-forward lesson is wired into the update path.
- **Audit trail.** Every deploy writes to `$MYMTS_ARCHIVE_DIR/deploy.log` with timestamps for build / install / health-gate result / promote-or-rollback.
*Residual risk:*
- **Keystore loss / theft.** A leaked keystore allows the holder to sign builds Android will accept as upgrades for this `applicationId`. Mitigation: the operator's standard secret-handling discipline (`.gitignore` exclusion verified, off-device backup, password-protected store). A leaked keystore would require migrating to a new `applicationId` + clean install — the Android signing model has no in-place revocation. This is a known limit and acceptable for a single-operator personal-scale wall.
- **Window-bounded health gate.** A regression that takes minutes to surface (e.g. a 30-min memory leak) won't be caught in the 90-s health window. Mitigation: the manual rollback path stays operator-invocable any time; future stages may add a "soak hour" extended gate after promotion, but the v1 trade-off is launch-only.
*Traces to:* **B1** (never bricks), **B2** (always a way back), **B5** (no silent fleet cascade), **A7** (secrets — keystore handling).

- **T-O1: Backup mechanism becoming the source of the failures it's meant to prevent.** Stage 6.
- **T-S1: A friend's sideloaded instance compromised → ranked above operator data loss; isolation by construction is the defence.** Stage 7 portability pass.

Each will be filled in with **likelihood**, **impact**, **mitigation**, **residual risk**, and a **back-trace** to the Trust Bar principle when the relevant stage lands.

---

## Navigation chapter — feed-expand A1 confirmation (2026-06-04)

**Claim:** The whole-wall D-pad navigation chapter introduces a focused-feed-item SELECT action that expands the item's summary in place. The expansion path:
  - Shows more of the **same** pre-fetched plain-text summary already in the `FeedItem`, without re-fetching from the network.
  - Renders expansion via a Compose `Text` widget `maxLines` toggle (collapsed: 2 lines; expanded: `Int.MAX_VALUE`) — no `WebView`, no HTML render, no browser engine.
  - Never introduces a fetch surface, a network call, or any HTML-interpretation layer that could become a vector for hostile-markup injection.

The A1 boundary **holds**: hostile-feed input remains neutralized by the helper's prior HTML stripping, and the TV-side code enforces "inert text only" by construction.

**Load-bearing code paths checked (adversarially):**

- **Focus model SELECT for Feed:** `app/src/main/java/com/mymts/ui/nav/WallFocusModel.kt` — `WallZone.Feed -> NavResult.Focus(focus.copy(feedExpanded = !focus.feedExpanded))`. The toggle is a stateless Boolean flip — no side-effects, no network calls, no fetcher invocation.
- **Expansion rendering in `FeedPane`:** `app/src/main/java/com/mymts/ui/wall/FeedPane.kt` — the `expanded` Boolean only adjusts `Text` widget properties (`fontSize`, `lineHeight`, `maxLines`, `color`). Same `item.summary` plain-text source either way. No new composable, no new render path.
- **No HTML rendering capability in the wall layer:** a grep across `app/src/main/java/com/mymts/ui/wall/` for `WebView`, `HtmlCompat`, `Html.from`, `Markwon`, or `AndroidView` finds zero matches. The expansion path cannot reach a markup interpreter because none exists in the wall classpath.
- **No new fetch on expand:** `FeedRepository.start()` polls on a fixed timer; `expandedIndex` is not a `LaunchedEffect` key on the repository or `HelperClient.fetchFeed()`. State change does not trigger a request.
- **Helper-side HTML strip (upstream):** `helper/src/mymts_helper/feeds/parser.py` `_StripHTML` class drops markup at parse time before storage. The TV never sees raw markup; it only receives the safe plain-text product the helper validated.

**Connection to T-T2 and T-H1:** This entry **does not change** the existing T-T2 (HTML smuggled into the feed pane via RSS title/summary → XSS-equivalent on the TV) or T-H1 (hostile RSS feed content) mitigations. The feed-expand path is a *consumer* of the helper's already-stripped output; it neither extends nor weakens those mitigations. The boundary they pin (no HTML interpretation on the TV) is the same boundary feed-expand respects by construction.

**Connection to BUILD-PROMPT §4 closed door:**

BUILD-PROMPT line 81 states: *"Opening an item on the TV shows whatever the helper safely provides (e.g., a text excerpt). Do **not** build a flow that requires the TV to fetch arbitrary web pages or that depends on a cross-device auth handoff — that path is forbidden. A simple, safe on-screen excerpt is the v1 answer; richer reading is deferred."*

The feed-expand implementation honors this closed door exactly: safe on-screen excerpt (the summary field from the helper's `FeedItem`), no TV-side fetch (expansion only flips render properties), richer reading deferred (a future "open on phone via QR" is logged in BACKLOG as a closed-door-compatible alternative, not as in-app HTML reading).

**What could break this in future code review:**

Red flags that must be caught at PR time:
1. **WebView mount on feed items.** `AndroidView { WebView(...) }` to render the summary as clickable-HTML would breach A1. Reject; re-threat-model the change.
2. **Fetch on expand.** `LaunchedEffect(expandedIndex) { repository.fetchFullArticle(...) }` would breach the closed door. Reject; require a fresh threat-model and a foundation-level decision.
3. **HTML-interpretation library.** Markwon, JSoup, `HtmlCompat.fromHtml`, or any markup parser added to the wall's classpath for feed rendering is a signal that someone is attempting structured-content rendering. Flag and require justification against A1.
4. **Summary mutation on expand.** If `item.summary` is conditionally modified (links inlined, additional text loaded from cache or network), the "same inert text" assumption is broken.

Future feed-expand changes must continue to render the summary as Compose `Text` (or equivalent inert widget), never fetch on expand, never mount a WebView, never mutate summary content based on expansion state. If any of these is violated, re-open this entry and assess.

**Residual risk:**

None beyond the existing T-H1 / T-T2 mitigations. Expansion adds no new vector and does not weaken the existing boundary. The risk to the feed system remains the helper's parser (T-H1); expansion does not compound it.

---

## Feed restructure — A1 reverify (2026-06-04)

**Claim:** Stage 7's sectioned-feed restructure (grouping items by source, displaying per-source freshness chips, rendering section headers with item counts) introduces no new web-fetch or HTML-render surface. The A1 boundary holds: hostile-feed input remains neutralized by the helper's prior HTML stripping, and the TV-side code enforces "inert text only" by construction.

**Prior work connection:** This entry reverifies the closed door confirmed in "Navigation chapter — feed-expand A1 confirmation" (2026-06-04). The feed-expand path (SELECT toggles `Text` widget `maxLines`) remains unchanged; the restructure layers sectioning atop it without breaching the boundary.

**Load-bearing code paths checked (adversarially):**

- **Grouping logic (pure Kotlin, no fetch):** `app/src/main/java/com/mymts/ui/wall/feed/FeedListBuilder.kt` — the `build()` function transforms `List<FeedItem>` into `List<FeedListEntry>` via a case-insensitive alphabetical sort and nested newest-first ordering. No network calls, no fetcher invocation, no `LaunchedEffect` keys on `FeedRepository.start()` or `HelperClient.fetchFeed()`. State change does not trigger a request. `entriesIndexForFocus()` maps focus indices over headers; the focus model's integer index remains the same flat feed order.

- **Section header rendering (pure Compose Text):** `app/src/main/java/com/mymts/ui/wall/FeedPane.kt` — `SectionHeader` composable renders the source name (`entry.source.uppercase()`) and item count as `Text` widgets. `FreshnessChip()` renders the per-source staleness signal (freshness enum → plain-text labels: "now", "Xm", "Xh", "not updating", "no items") in `Text` with no HTML interpretation.

- **Item expansion (unchanged from navigation chapter):** `FeedPane.kt` — `FeedRow` composable. SELECT-on-focused-item still toggles the `expanded` Boolean, which adjusts the summary `Text` widget's `fontSize`, `lineHeight`, `maxLines` (`if (expanded) Int.MAX_VALUE else 2`), and `color`. Same `item.summary` plain-text source (already HTML-stripped by the helper's `feeds/parser.py`). No WebView, no re-fetch on expand, no summary mutation.

- **Channel-picker orientation chip (pure local math):** `app/src/main/java/com/mymts/ui/menu/ChannelPickerOverlay.kt` — `pickerGroupAt()` function classifies cursor position within the live/offline grouping via a single `channels.count { it.isPlayable }` scan. No new helper calls, no new endpoints. Renders as a `Text` label ("LIVE 3/8" / "OFFLINE 2/5") in a `Row`. Tests at `app/src/test/java/com/mymts/ui/menu/PickerGroupTest.kt` pin the math.

- **No HTML rendering capability in the wall layer:** `FeedPane.kt` and `FeedListBuilder.kt` contain no imports for `WebView`, `HtmlCompat`, `Html.from`, `Markwon`, `JSoup`, or `AndroidView`. The expansion path cannot reach a markup interpreter because none exists in the wall classpath.

- **No new fetch surface on grouping changes:** `FeedPane.kt` — the `entries` list is `remember(state.snapshot)`, memoized on the helper's snapshot identity. The snapshot is fetched by the existing fixed-timer poll in `FeedRepository.start()` (unchanged). No new keys, no side-effects on freshness thresholds or section transitions.

- **Flat-item invariant preserved:** `FeedListBuilderTest.kt` (19 tests) pins that the order of `FeedListEntry.Item` in the output list matches the focus navigation order. The focus model's `feedIndex` 0..N-1 still traverses items in visible order; `entriesIndexForFocus()` skips headers. No focus-model change.

**Connection to T-T2 and T-H1:** This entry **does not change** the existing T-T2 (HTML smuggled into the feed pane → XSS-equivalent) or T-H1 (hostile RSS feed content) mitigations. The restructure is a *consumer* of the helper's already-stripped output; it neither extends nor weakens those mitigations. The boundary they pin (no HTML interpretation on the TV) is the same boundary the sectioning respects by construction.

**Four red-flag patterns from the prior feed-expand entry (still apply):**

1. **WebView mount on feed items.** `AndroidView { WebView(...) }` to render summaries as clickable-HTML would breach A1. Not present; must be rejected at PR time if introduced.
2. **Fetch on section transition.** `LaunchedEffect(freshness) { repository.fetchFullArticle(...) }` would breach the closed door. Not present; grouping changes trigger no fetches.
3. **HTML-interpretation library.** Markwon, JSoup, `HtmlCompat.fromHtml`, or any markup parser added to the wall's classpath for feed rendering signals a breach. Not imported.
4. **Section-header or freshness-chip text mutation from network state.** If freshness chips fetch external data (e.g., a "last-check time" from the API, a remote status message), the "inert local math only" assumption breaks. The freshness classification is computed locally from the helper's `FeedItem.publishedAtIso` / `fetchedAtIso` timestamps (already in-memory) and `now` (wall-clock). No mutation, no fetch.

**Residual risk:**

None beyond the existing T-H1 / T-T2 mitigations. Restructuring adds no new vector and does not weaken the existing boundary. The risk to the feed system remains the helper's parser (T-H1); restructuring does not compound it.

---


## UX & Config — A1 + B1 reverify (2026-06-04)

**Claim:** The UX & Config chapter introduces three operator-facing wall-layout knobs (feed width, feed font scale, feed side) via discrete presets persisted in SharedPreferences. A1 boundary holds: no new web-fetch or HTML-render surface. B1 boundary holds: settings persistence contains no secrets, no PII, no absolute paths — integer ordinals only.

**Prior work connection:** This entry reverifies the closed door confirmed in "Navigation chapter — feed-expand A1 confirmation" and "Feed restructure — A1 reverify" (both 2026-06-04). The settings UI and persistence do not extend fetch or markup-interpretation surfaces. Feed-side swap is a layout-only change with a pure-Kotlin parameter on the focus model.

**Load-bearing code paths checked (adversarially):**

- **Settings data + defaults:** `app/src/main/java/com/mymts/data/settings/WallSettings.kt` (lines 26–96) — data class `WallSettings` holds three enums (`FeedWidth`/`FeedFontScale`/`FeedSide`) with discrete presets. Width: Narrow 0.22 / Default 0.28 / Wide 0.36 fractions. Font: Small 0.88 / Default 1.0 / Large 1.18 multipliers. Side: Left (default) / Right. Ordinal-mapping helpers (`feedWidthFromOrdinal` etc., lines 88–95) fall back to safe defaults for out-of-range, ensuring partial corruption doesn't discard other values.

- **Persistence in LineupStore:** `app/src/main/java/com/mymts/data/lineup/LineupStore.kt` (lines 92–206) — three new SharedPreferences integer keys: `KEY_FEED_WIDTH` / `KEY_FEED_FONT` / `KEY_FEED_SIDE`. `updateWallSettings()` persists ordinals via `putInt`; `readWallSettingsFromDisk()` decodes ordinals back to enums with per-component fallback. Same `"mymts_lineup"` container as existing lineup overrides — single on-device store, not parallel or network-sourced. No secrets, no PII, no paths.

- **Settings UI (pure Compose Text):** `app/src/main/java/com/mymts/ui/menu/SettingsOverlay.kt` (lines 64–225) — centered popup with three `SettingRow` composables. Each row renders two `Text` widgets (title + value label) and a `Box` focus indicator. UP/DOWN navigates via Compose's focus system; LEFT/RIGHT on a focused row calls `onCycle` and live-applies via `LineupStore`. No network, no fetcher invocation, no HTML render.

- **Focus model gains feedSide parameter:** `app/src/main/java/com/mymts/ui/nav/WallFocusModel.kt` (lines 52–73) — `apply()` signature extended with `feedSide: FeedSide = FeedSide.Left` parameter (line 58). Default preserves signature compatibility; existing 38 tests pass unchanged. Spatial rules in `applyTicker`/`applyFeed`/`applyGrid` use `feedSide` to derive `toGrid`/`toMenu` / `intoGrid` directions (lines 144–145, 192–195, 225–226). Pure Kotlin, no network, no state mutation — all paths are function-local.

- **Feed-side-aware layout swap:** `app/src/main/java/com/mymts/ui/wall/WallScreen.kt` (lines 302–308) — Row composition branches on `wallSettings.feedSide`: Left renders `Row { feedPane; divider; videoGrid }`, Right renders `Row { videoGrid; divider; feedPane }`. Children are defined once (lines 269–301) as composable lambdas so focus bindings + subscriptions are reused unchanged; only the order changes.

- **Menu slide direction mirrors feed side:** `app/src/main/java/com/mymts/ui/menu/MenuOverlay.kt` (lines 79–88) — `alignment` and `slideInHorizontally`/`slideOutHorizontally` branch on `isLeft` (line 79). Feed-Left panel slides from left; Feed-Right panel slides from right. Gesture that opens the menu (RIGHT-from-feed in feed-right) feels spatially correct.

- **FeedPane accepts fontScale:** `app/src/main/java/com/mymts/ui/wall/FeedPane.kt` (line 75) — `fontScale: Float = 1f` parameter. All text `sp` values are multiplied by `fontScale` (invoked at call site via `wallSettings.feedFontScale.multiplier`, WallScreen line 278). Legibility floor enforced by `FeedFontScale.Small = 0.88`, asserted by `WallSettingsTest` (line 80–88).

- **No fetch on side swap or width/font cycle:** The three cycle functions in `LineupStore` (`cycleFeedWidth()` / `cycleFeedFontScale()` / `cycleFeedSide()`, lines 101–114) call `updateWallSettings()` which writes to SharedPreferences and updates the local `_wallSettings` state. No `FeedRepository.start()` re-trigger, no `HelperClient.fetchFeed()` invocation — the focus model and layout adapt to the new setting without network calls.

- **Settings persistence: integer ordinals only, no secrets.** (T-T6 sibling) `LineupStore` stores only integer enum ordinals, same format as existing `KEY_AUDIBLE_SLOT` (-1 for muted, 0+ for slot index). No slugs, no URLs, no credentials. The "no secrets / no PII / no absolute paths" promise is preserved by construction — the only values persisted are ordinal indices of finite enums.

- **No new HTML rendering capability:** a grep across `app/src/main/java/com/mymts/ui/menu/` for `WebView`, `HtmlCompat`, `Html.from`, `Markwon`, `JSoup`, or `AndroidView` containing a web renderer finds zero new imports. The settings overlay is pure Compose `Text` + `Box` + focus management.

**Feed-Right orientation test coverage (UX & Config chapter §3 invariants):**

`app/src/test/java/com/mymts/nav/WallFocusModelTest.kt` (lines 395–517) — 11 new tests in the "Feed-Right orientation" section (lines 412–517):
- `feed-right LEFT enters grid` (mirrors feed-left RIGHT; line 412–419)
- `feed-right RIGHT opens menu` (mirrors feed-left LEFT; line 421–424)
- `feed-right grid cell-boundary rules` (4 spatial-rule tests; lines 426–458)
- `feed-right FEED→GRID→FEED round-trip preserves both indices` (line 470–485)
- `feed-right no-trap invariant — every zone still exitable` (line 487–505)
- `feed-right vs feed-left LEFT-RIGHT are mirror images` (line 507–517)

All 49 WallFocusModel tests pass (38 pre-existing + 11 new).

**Connection to T-T2 and T-H1:** This entry **does not change** the existing T-T2 (HTML smuggled into the feed pane → XSS-equivalent) or T-H1 (hostile RSS feed content) mitigations. Settings UI is a consumer of the helper's already-stripped output; it neither extends nor weakens those mitigations. The boundary they pin (no HTML interpretation on the TV) remains the same.

**Four red-flag patterns from prior feed-expand entry (still apply and still not breached):**

1. **WebView mount on settings or feed items.** Not added.
2. **Fetch on setting change.** Not added — cycle functions write to SharedPreferences and update local state only.
3. **HTML-interpretation library.** Not added to wall or menu classpath.
4. **Setting-value mutation from network state.** Settings are persisted integers; no remote source of truth.

**Residual risk:**

None beyond the existing T-H1 / T-T2 mitigations. The UX & Config chapter adds no new vector and does not weaken the existing boundary. The risk to the feed system remains the helper's parser (T-H1); UX & Config does not compound it. The risk to the menu remains the existing Trust Bar C2 honest-degradation + C3 staleness principles (T-T6, T-T7); settings persistence adds no credentials or exfiltration surface.

---

## Feed-sources expansion + SQLite cross-thread fix (2026-06-05)

**Claim:** The feed-sources expansion (4 → 13 reputable public RSS sources seeded in `feeds/seed.json`) introduces no new attack surface. The SQLite cross-thread robustness fix is a correctness improvement that does not weaken any security boundary.

**Feed-sources expansion — A1 boundary holds:**

The helper's feed-handling layer treats all upstream sources as hostile by policy (T-H1). The expansion adds 9 new sources to the original 4, but does *not* introduce a new code path:
- **Same SSRF-safe fetcher:** `mymts_helper.fetcher.fetch()` (https-only, DNS-pinned, RFC1918/loopback/CGNAT/ULA rejection, userinfo rejection, bounded body + time, redirects re-validated) remains the single egress mechanism. Every source, old and new, goes through the same defensive boundary.
- **Same defensive parser:** `mymts_helper.feeds.parser.parse()` (feedparser + defusedxml; `script`/`style`/`iframe`/`object`/`embed` dropped, entities decoded, plain text only) processes every item. Hostile RSS content is neutralised at parse time before storage.
- **Per-source error isolation:** one broken feed does not stall the others. Seeding 13 sources instead of 4 increases the number of items flowing through the SAME handling path.
- **All 13 sources pre-verified:** each was fetched through the real helper fetcher and parsed through the real parser before commit — only feeds returning valid plain-text items were seeded. No surprise feed format reaches production.

**Seeded set (13):** Original — BBC World, Al Jazeera, Guardian World, NPR World. Added — PBS NewsHour, Christian Science Monitor, CBS News, NBC News, Politico, Bloomberg Markets, The Dispatch, National Review, Reason.

**Honesty call (C3-adjacent):** AP was rejected because its official feed is broken and the only working feed is a third-party mirror — labeling a relay "AP" would misrepresent provenance. Reuters was rejected because its public RSS was discontinued (returns HTML). Neither was seeded with a misleading label.

**Connection to T-H1:** this expansion does *not* change the existing T-H1 mitigation. More sources = more items through the same defensive parser and the same SSRF-safe fetcher. No new code path, no parser variant, no relaxation of HTML stripping or entity decoding. The boundary is preserved by construction.

**SQLite cross-thread fix — robustness without boundary change:**

The logged intermittent `sqlite3.ProgrammingError: SQLite objects created in a thread can only be used in that same thread` → HTTP 500 on `/api/feed` was caused by a FastAPI `Depends()` yield-dependency (`_conn` in `feeds/api.py` and `channels/api.py`) whose setup (open) and teardown (`close()`) ran through two separate `run_in_threadpool` calls that could land on different anyio threadpool threads — closing a connection on a different thread than it was opened on raises the error.

**Fix + boundary verification:**
- Added `db.connection_scope(path)` `@contextmanager`; `feeds/api.py` and `channels/api.py` now open + use + close the connection **inside the sync route body** (one `run_in_threadpool` call = one thread). The connection never crosses a thread boundary.
- The fix keeps sqlite's thread guard ON — it does **not** use `check_same_thread=False`.
- **SSRF fetcher unchanged** (T-H2 egress boundary untouched). **Parser unchanged** (T-H1). **Response envelope identical** (`schema_version` + field structure unchanged; no new endpoint, no new exposed field). **No credential surface** — the connection is a local SQLite file handle (T-H7 logging discipline unchanged).

**Tests:** 2 concurrency regression tests in `tests/test_api.py` (8 client threads × 64 requests on `/api/feed` and `/api/channels`, all 200 + stable envelope); 2 shipped-seed guard tests in `tests/test_feeds_seeder.py` (all-https, unique URLs/labels, every entry seeds, originals retained). Full helper suite: 140 passed.

**Residual risk:** none beyond the existing boundaries. The fix improves correctness (eliminating the intermittent 500) without loosening the SSRF boundary, the parser boundary, or the response contract. The dual-uvicorn-instance tidy-up remains a separate, independent BACKLOG sub-item.

**Traces to:** **A1** (defensive parse, unchanged), **T-H1** (hostile RSS, unchanged mitigation), **T-H2** (SSRF egress, unchanged), **C3** (honest provenance — AP/Reuters rejection).

---

## Ticker real data — markets + sports external sources (2026-06-05)

**Claim:** The ticker previously showed SAMPLE markets data behind honest SAMPLE pills. This chapter makes markets real (Stooq CSV + CoinGecko JSON) and adds a sports mode (ESPN scoreboard JSON), rotated on the TV. All three sources are keyless. The A1 boundary holds: external responses are fetched through the same SSRF-safe fetcher as feeds/channels, parsed strictly (never eval/exec, malformed dropped), and honesty (C3) is preserved per-entry.

**New external data boundaries (all keyless):**
- **Markets — Stooq CSV + CoinGecko JSON:** Stooq indices (`^SPX`, `^DJI`, `^NDQ`, `^FTM`, `^DAX`, `^NKX`, `^HSI`), FX (`EURUSD`, `GBPUSD`, `USDJPY`), gold (`XAUUSD`); direction from open-vs-close. CoinGecko spot + 24h change for BTC/ETH. Stooq CSV parse is strict (float-guarded fields; N/D + malformed rows dropped). CoinGecko via `json.loads()` with type checks; missing/null fields omit the symbol, never raise. Stooq throttle on rapid repeats → that cycle falls back to honest sample (per-source isolation).
- **Sports — ESPN scoreboard JSON:** MLB/NFL/NBA/NHL scores from `site.api.espn.com/apis/site/v2/sports/SPORT/LEAGUE/scoreboard`. Public, undocumented, keyless. ToS-gray, accepted for personal non-commercial single-box use (operator nod). Strict parse: `competitions`/`status`/`score` fields `.get()`-guarded, malformed games dropped, games-per-league capped, scores sanitized to digits. Honest staleness covers disappearance.

**A1 boundary — same defensive shape as T-H1 (hostile RSS):**
- **Single SSRF-safe egress:** `mymts_helper.fetcher.fetch()` (https-only, DNS-pinned, RFC1918/loopback/CGNAT/ULA rejection, userinfo rejection, bounded body [8 MB] + bounded time, redirects re-validated) is the sole outbound path. Every source goes through the same boundary. The fetcher itself was **not modified** this chapter.
- **Strict parse, never eval/exec:** CSV split with float guards; JSON via `json.loads()` with `isinstance` checks; ESPN via `.get()`-guarded access. No `eval`/`exec`/`pickle`/`yaml.load`, no untrusted deserialization. Malformed payloads drop the entry rather than surface an error.
- **Fail-closed:** a source that throttles, 5xx's, or returns malformed produces an empty real set for that cycle; the snapshot falls back to honest sample (and the envelope marks `stale` once real data aged past 15 min). The endpoint never 500s on bad upstream data — verified by the blocked-egress test.
- **No new secret:** every source is keyless. The helper-holds-a-secret line was deliberately NOT crossed. A future keyed source would follow the `.env` secret discipline (documented as the path, not taken here).

**Honesty (C3):** `is_sample` per entry travels helper→TV untouched; the TV never upgrades a sample entry to live. Stale real data is surfaced via the envelope `stale` flag, never shown frozen as current. Unreachable → honest sample (markets) / "scores unavailable" (sports, `is_sample=false` true state), never fabricated.

**In-memory snapshot — no DB reintroduction:** both pollers hold an in-process latest snapshot read on the event loop; the ticker path does **not** reintroduce the cross-thread sqlite exposure the feed path had (fixed in the feed-sources chapter).

**Rate-limit politeness:** markets poll 120 s, sports poll 180 s (env-overridable) — a courtesy/anti-abuse cadence on keyless public endpoints.

**Residual risk:** a source could change shape, rate-limit harder, or (ESPN) disappear. Mitigation = strict parse drops bad data + per-source isolation falls back to honest sample; disappearance surfaces as honest staleness/"scores unavailable", never fabricated values.

**Traces to:** **A1** (external sources hostile; SSRF-safe fetcher; strict, fail-closed parse), **A2** (per-source isolation; in-memory, no DB), **C3** (real-vs-sample per entry; stale surfaced; unreachable → honest fallback).

---

## Kiosk / foreground service + boot receiver (2026-06-06)

**Claim:** The kiosk scaffolding adds two new surfaces — an exported `BootReceiver` (must be exported to receive system `BOOT_COMPLETED`) and a foreground `Service`. Kiosk mode is **opt-in, OFF by default**, so the same signed APK on a non-kiosk box (`.182`, dev installs) starts no foreground service and does not autostart on boot. Model A: one sole kiosk per box; no coexistence logic. The new surfaces are low-impact by construction.

**T-K1 — a malicious app or adb spoofs a boot broadcast → BootReceiver → starts the foreground service without operator intent.**
*Likelihood:* medium (any app can broadcast; adb can send intents). *Impact if triggered:* MyMTS starts *its own* foreground service + launches *its own* wall activity — visual only; no data access, no privilege escalation, no cross-app effect.
*Mitigation (two-layer gate):* (1) `KioskPolicy.BOOT_ACTIONS` allowlist — only the known boot actions act; spoofed/unexpected actions are ignored. (2) The opt-in gate — `shouldStartOnBoot(action, kioskEnabled)` is `false` whenever `kioskEnabled` is false, so even a correctly-named spoofed `BOOT_COMPLETED` is a no-op on any box that wasn't explicitly provisioned. On `.182` the flag is off → nothing starts no matter how many boot broadcasts arrive.
*Residual risk:* broadcast spam is possible but the receiver returns early and does no meaningful work. *Traces to:* A1, A4, B1.

**T-K2 — the foreground service is externally startable.**
*Mitigation:* `KioskService` is declared `exported="false"`; the OS refuses external/adb starts. No `onBind` IPC surface; `onStartCommand` accepts no commands/state from callers. *Residual risk:* none (manifest-enforced). *Traces to:* A4.

**T-K3 — Model-A sole-kiosk assumption broken on a shared box.**
The kiosk code assumes it owns its box: no reclaim loop, no `SYSTEM_ALERT_WINDOW`, no coexistence state machine. On the dedicated box (sole kiosk) this holds. The opt-in gate means a non-provisioned box (e.g. `.182`) never runs the kiosk at all, so it cannot contend with WyzeGrid. A future shared-box use case would require fresh threat-modelling before any reclaim logic is added. *Traces to:* B1, A4.

**T-K4 — the opt-in gate is the sole `.182`-safety control; accidental enable would orphan a shared box.**
*Mitigation:* `KioskPrefs.DEFAULT_ENABLED=false`; the *only* enable path is the explicit provisioning intent (`--ez kiosk true`), which needs physical/adb access — not something a remote app can do. Standing rule (`.182` + WyzeGrid untouched) is the operational enforcement; a code-review gate rejects any change flipping the default to true. Rollback is `--ez kiosk false`. *Traces to:* B1, A7 (the flag is a boolean, not a secret).

**No new secret:** the kiosk flag is a boolean in SharedPreferences (no PII, no credential). The release signing keystore remains the only secret and is never committed. **STAGED:** on-hardware validation is pending the migration session; this analysis stands on the code as written.

**Traces to:** **A1** (untrusted external triggers — allowlist + opt-in gate), **A4** (least authority — service not exported, no extra reach, no coexistence machinery), **B1** (recover on reboot — the boot receiver is the recover mechanism), **A7** (no new secret).

---

## LAN web client — separate origin-isolated client (2026-06-06)

**Claim:** The new LAN web client is a *separate* client of the same helper API (like the native app), built so it cannot become the cross-service path the founding native-over-web decision avoided. It is LAN-only, credential-free, same-origin with the helper (no CORS), and holds the A1 closed door (no in-browser article reading). The helper core is unchanged except an optional, default-off static mount.

**Why it does not reopen the web-client threat the native decision closed:**
- **Separate origin, LAN-only.** Served on the helper's bare LAN address (`https://<LAN_IP>:8443/app/`), NOT a `*.<DOMAIN>` subdomain, NOT tunneled, NOT behind Cloudflare Access. It shares no origin and no cookie jar with the operator's other services; the browser's same-origin policy enforces the isolation. The specific threat that drove native-over-web (a browser sharing CF-Access cookies across `*.<DOMAIN>`) cannot occur because this client is off that domain entirely.
- **Credential-free.** No login/cookies/session/token; `fetch` uses `credentials: "omit"`. The helper data is already inert public plain text — nothing to steal, no session to hijack, no A5/A6 session-as-skeleton-key surface.
- **Helper stays the only boundary (A4/A1).** The client does no hostile-input work, no article-page fetch, no stream resolution — GET-only against the helper's own JSON. A page CSP (`connect-src 'self'`, `frame-src 'none'`, `object-src 'none'`) is a belt-and-braces guard: the browser forbids reaching any third-party host or iframing an article even if a bug tried.

**A1 closed door (no in-browser web reading):** the client renders the helper's already-stripped plain-text summaries via `textContent` only — never `innerHTML`, never a WebView/iframe, never an article fetch. Same closed door as the native app. Confirmed by construction + the CSP.

**Helper change — minimal, default-off:** the only helper change is a config-gated static mount (`WEB_CLIENT_DIR`, default unset → not mounted; the running helper is unchanged until the operator opts in). Mounted at `/app`, GET-only static files, same-origin as the API → **no CORS opened to any origin**. Mounted last so it cannot shadow `/api/*` or `/health`. The SSRF-safe fetcher, parsers, and non-root/read-only/cap-drop posture are untouched. New surface: serving our own bundled static files (no hostile input).

**Deferred — remote web client (NOT built):** exposing this beyond the LAN would be a genuinely separate public origin requiring its own threat-model pass, an auth story (and the credential tradeoff that introduces — the helper or client would then hold a secret), and rate-limiting. Scoped in `docs/BACKLOG.md` as a conscious future chapter. The LAN-only version sidesteps all of it by being unreachable from outside the network.

**Traces to:** the native-over-web decision in `04-TECHNICAL-APPROACH.md §1` (this is its sanctioned *separate-client* extension, not a reversal); **A1** (inert plain text, closed door), **A4** (helper is the only boundary; client has no reach), **A5/A6** (credential-free → no session to extend/forge).

---

## Feed filtering — A1 reverify (2026-06-06)

**Claim:** feed filtering (source denylist + recency window) introduces no new fetch / web / HTML surface. It operates purely on the already-fetched inert plain-text items the helper serves.

- **Pure, local, no fetch:** `FeedListBuilder.applyFilters()` is pure Kotlin over the in-memory `FeedItem` list (drop hidden sources; drop items older than the recency window). No `FeedRepository` re-trigger, no `HelperClient` call, no `LaunchedEffect` keyed on the filter that fetches. The filter UIs (`SettingsOverlay` recency row, `SourceFilterOverlay` toggle list) only write an on-device denylist/ordinal to SharedPreferences via `LineupStore` — no network, no markup.
- **A1 unchanged:** rendering is still the navigation/restructure chapters' `Text`-only path; filtering changes *which* inert items show, never *how* they render. No WebView, no article fetch, no iframe.
- **No new secret / no PII:** the persisted filter state is source labels (already public) + an enum ordinal. Mirrors the existing WallSettings persistence.
- **Search deferred:** free-text search was not built (D-pad friction); if added later it would also operate on the already-fetched plain text (no web search) — flagged so a future implementation keeps that boundary.

**Traces to:** **A1** (operates on already-stripped plain text; no new input surface), **C3** (honest empty/stale states preserved under filtering).

---

## Curation & preferences — A1 reverify (2026-06-06)

**Claim:** sports league curation + ticker-news + feed-source toggles introduce no new fetch / web / HTML surface and no faked data. All operate on already-fetched inert plain text.

- **Sports league curation:** pure TV-side filter (`HelperTickerSource.filterLeagues`) over the helper's existing `/api/ticker/sports` entries — drops entries for hidden leagues. No helper change, no new endpoint, no per-device helper state (avoids the cross-platform-profiles fork). Honest: empties to "scores unavailable", never fakes a game.
- **Ticker news:** built from the **feed the wall already polls** (`HelperTickerSource.newsEntries` over the in-memory `FeedItem` list) — no new fetch, no new endpoint. Headlines are the helper's already-stripped plain-text titles, rendered as inert `Text` (`Direction.NONE`). **No urgency/breaking-news fabrication** — RSS can't honestly flag it, so it isn't claimed; true urgency detection is logged as a future item needing a real signal, not faked.
- **Feed-source toggles:** the Stage 12 denylist, reused — no new surface.
- **Persistence:** league denylist (JSON string-set) + a boolean in SharedPreferences via `LineupStore` — no secret, no PII.

**Traces to:** **A1** (operates on already-stripped plain text; no new input surface), **C3** (honest "no games"/"no headlines" states; sample/stale never shown as live; no fabricated urgency).

---

## Web client rework — CSP for in-browser HLS (2026-06-06)

**Claim:** adding in-browser video (hls.js) to the LAN web client does not reopen the A1 closed door and does not weaken the LAN-only / credential-free posture.

- **Video playback ≠ web reading.** hls.js plays the same public HLS streams the helper resolves (`/api/channels`) — inert stream playback, exactly what the native ExoPlayer does. The closed door forbids an in-app *article web reader*; this is not one. No article page is fetched, no HTML is rendered, no link is followed.
- **CSP — scoped widening, locks preserved.** `script-src 'self'` (hls.js is vendored at `web/vendor/`, pinned — **no CDN**); `frame-src 'none'` + `object-src 'none'` stay (no iframes / article embeds); `connect-src`/`media-src` widened to `https:` because hls.js fetches `.m3u8` + segments from arbitrary public stream CDNs that can't be enumerated. The broader `connect-src` is acceptable because the client is **credential-free** (nothing to exfiltrate) and the stream URLs originate from the helper, not from arbitrary input. Documented in `web/README.md`.
- **Unchanged:** LAN-only (helper bare IP, off `*.<DOMAIN>`, not tunneled/CF-Access), credential-free (`credentials:"omit"`), same-origin to the API (no CORS), helper core untouched. View prefs are browser-local (localStorage) — non-sensitive (grid size, source show/hide); no secret is stored.
- **Honest degradation:** a stream that won't load shows an offline tile, never a faked-live one.

**Traces to:** **A1** (playback not reading; no article fetch; markup-free), the native-over-web decision's *separate-client* extension (still LAN-only/credential-free), **C3** (offline tiles honest).

## Sports ticker — current-games filter (2026-06-06)

The ESPN scoreboard returns future fixtures off-season; showing them would be stale-as-current (a C3 violation). `ticker/sports.py::parse_scoreboard` filters to current games only (in-progress / recent-final / soon-scheduled; far-future + stale dropped; empty leagues omitted) — same SSRF-safe fetcher, same defensive strict parse (never raises), same keyless source. No new surface; the change is a correctness/honesty filter over already-fetched data. Pure + unit-tested. **Traces to:** **C3** (current, not stale-future), **A1** (defensive parse unchanged).

## Web client rework round 2 — mixed-content honesty, no proxy, CSP stays locked (2026-06-06)

**Claim:** round 2 (ticker league markers, agnostic feed, cell-count grid, channel picker, play-what-works video, helper `browser_playable` hint) adds **no new attack surface** and keeps every lock from the prior entry.

- **CSP is no less locked — and arguably tighter.** No directive was widened vs the prior rework: `frame-src 'none'`, `object-src 'none'`, `script-src 'self'`, `base-uri 'none'`, `form-action 'none'` all stay; `connect-src`/`media-src` remain `https:`/`blob:`. We did **not** add `worker-src` — hls.js now runs `enableWorker:false`, so no `blob:` Web Worker is spawned (which a strict `default-src 'self'` would block anyway). The only blob used is the `<video>` MediaSource, already covered by `media-src blob:`.
- **No video proxy — helper egress unchanged.** The helper does **not** enter the video data path. The new `browser_playable` classification reuses the manifest body the prober **already fetches** through the SSRF-safe fetcher (no extra request, no new host, no relay of bytes to the client). It stores a 0/1/NULL hint; `/api/channels` exposes it only when live. The SSRF guards, the https-only egress, and the resolver/shield boundary are untouched. The **native app stays the full-fidelity client**; the browser is an honest best-effort viewer.
- **Honest by construction (C3).** A stream that can't load — mixed-content, CORS, geo, or dead — is shown as **"Not playable in browser — on the TV wall"**, never a black box as live. The `browser_playable` hint is best-effort (scheme-based); the runtime `<video>` load result is the ground truth and catches what the hint can't predict. Diagnosis confirmed the current 10 live channels are all HTTPS-clean + CORS-OK (finding 16), so nothing is mislabeled today.
- **Diagnosis honesty.** The mixed-content hypothesis was tested against live streams and **refuted** for the current set; we reported that rather than asserting the convenient cause (C3 applied to our own engineering).
- **Unchanged:** LAN-only, credential-free (`credentials:"omit"`), same-origin (no CORS opened), DOM text via `textContent` only (no markup from helper strings), hls.js vendored+pinned, A1 closed door held (playback ≠ reading). View prefs browser-local (`mymts.web.prefs.v2`: cell count, per-cell channel assignment, feed width, source show/hide) — non-sensitive; no secret stored. Migration 002 is an additive `ALTER TABLE ADD COLUMN` (NULL for existing rows) — no data-exposure change.

**Traces to:** **A1** (playback not reading), **C3** (honest "on the TV wall", honest diagnosis), the SSRF/egress boundary (no proxy, no new fetch), the native-over-web *separate-client* extension (still LAN-only/credential-free/no-CORS).

## Stage gates that touch this file

- **Stage 1:** review threats applicable to the spike's outbound surface.
- **Stage 2:** populate every helper-related threat with concrete mitigations and tests.
- **Stage 3:** populate the TV-side video playback threats. **Done — T-T1 through T-T5 above.**
- **Stage 5:** confirm the in-app menu introduces no new boundary issues. **Done — T-T6, T-T7 above.**
- **Stage 6 — update mechanism.** **Done — T-A2 above.** Signed installs prevent unauthorised builds; health-gated promotion + retained-archive rollback prevent silent bad-bundle cascades.
- **Stage 6:** populate update mechanism + backup mechanism threats.
- **Stage 7:** final review against every Trust Bar principle (A0–A9, B1–B5, C0–C6); each must have at least one mitigation line here, or be explicitly noted as "not applicable to this architecture" with reasoning.

If a stage closes without its threat-model rows populated, that stage is not done.
