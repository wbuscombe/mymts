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

#### Other stages (filled in as they land)

- **T-A1: Malicious or buggy app sideload on the Onn box.** Stage 6.
- **T-A2: Update mechanism bricking the TV or being subverted.** Stage 6.
- **T-O1: Backup mechanism becoming the source of the failures it's meant to prevent.** Stage 6.
- **T-T1: TV-side surface honesty (frozen tile labelled "LIVE").** **Closed in Stage 2 Part B.** Mechanism: `com.mymts.player.LivenessTracker` derives liveness from actual frame arrival via `onRenderedFirstFrame` + `onDroppedVideoFrames`, transitions to `STALE` when `last_frame_age_ms > 15 s`, runs a bounded recovery ladder (PREPARE → REINIT × 2 with 2 s/8 s/30 s backoff), and settles into `DEAD` after 3 strikes. Demonstrated on `.182`: an unreachable URL (`httpbin.org/status/404`) completed the lifecycle to `DEAD` in ~120 s, the same Stage 1 v3 failure-shape that left nasa-public-0 in `RECONNECTING` for 10 h. The C3 contract is now enforced **at both ends** — helper API masks `current_url → null` when not live (Part A); player surfaces honest `STALE`/`DEAD` to the UI (Part B). Details + on-device evidence in `docs/findings/02-player-state-machine.md`.
- **T-S1: A friend's sideloaded instance compromised → ranked above operator data loss; isolation by construction is the defence.** Stage 7 portability pass.

Each will be filled in with **likelihood**, **impact**, **mitigation**, **residual risk**, and a **back-trace** to the Trust Bar principle when the relevant stage lands.

---

## Stage gates that touch this file

- **Stage 1:** review threats applicable to the spike's outbound surface.
- **Stage 2:** populate every helper-related threat with concrete mitigations and tests.
- **Stage 3:** populate the TV-side video playback threats.
- **Stage 6:** populate update mechanism + backup mechanism threats.
- **Stage 7:** final review against every Trust Bar principle (A0–A9, B1–B5, C0–C6); each must have at least one mitigation line here, or be explicitly noted as "not applicable to this architecture" with reasoning.

If a stage closes without its threat-model rows populated, that stage is not done.
