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

**T-T4. Cleartext HTTP to the helper on the LAN → tampering / sniffing by a LAN-local attacker.**
*Likelihood:* low (operator's LAN; no public-internet exposure).
*Impact:* a LAN-local attacker could inject feed items, channel URLs, or modified `/api/feed` content. Worst case: an injected channel URL pointing at attacker-controlled HLS → wall plays attacker content.
*Mitigation (partial):* the wall's `network_security_config.xml` allows cleartext **only** for `<LAN_IP>`; every other host is HTTPS-only by Android policy. The helper container is bound to the LAN bridge only — not reachable from the public internet. Operator's network is single-occupant residential.
*Residual risk:* a LAN-local attacker (e.g. a compromised IoT device on the same network) could MITM the helper response. Stage 6 hardens this with TLS to the helper + certificate pinning (operator-issued internal CA). The current state is a documented v0.1 compromise — narrower than app-wide `usesCleartextTraffic=true`, broader than TLS.
*Traces to:* **A2**, **A4**.

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

## Stage gates that touch this file

- **Stage 1:** review threats applicable to the spike's outbound surface.
- **Stage 2:** populate every helper-related threat with concrete mitigations and tests.
- **Stage 3:** populate the TV-side video playback threats. **Done — T-T1 through T-T5 above.**
- **Stage 5:** confirm the in-app menu introduces no new boundary issues. **Done — T-T6, T-T7 above.**
- **Stage 6 — update mechanism.** **Done — T-A2 above.** Signed installs prevent unauthorised builds; health-gated promotion + retained-archive rollback prevent silent bad-bundle cascades.
- **Stage 6:** populate update mechanism + backup mechanism threats.
- **Stage 7:** final review against every Trust Bar principle (A0–A9, B1–B5, C0–C6); each must have at least one mitigation line here, or be explicitly noted as "not applicable to this architecture" with reasoning.

If a stage closes without its threat-model rows populated, that stage is not done.
