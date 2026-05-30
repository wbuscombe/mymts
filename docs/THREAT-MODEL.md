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

### Threats present in this architecture (to be detailed Stage-2 onward)

- **Compromised upstream news source (hostile feed content) → helper.** Stage 2.
- **Compromised upstream stream resolution (hostile redirect / SSRF chain) → helper egress.** Stage 2.
- **Helper compromise → blast radius on NAS.** Stage 2 / Stage 6.
- **Malicious or buggy app sideload on the Onn box.** Stage 6.
- **Operator credentials exposed via misconfigured logs or error messages.** Stage 2 / Stage 6.
- **Update mechanism bricking the TV or being subverted.** Stage 6.
- **Backup mechanism becoming the failure (cf. Op C4 — upkeep never becomes the source of failures it's meant to prevent).** Stage 6.
- **Stale-but-pretending data shown to the operator (silent staleness).** Stage 3 / Stage 4.
- **A friend's sideloaded instance compromised → ranked above operator data loss; isolation by construction is the defense.** Stage 7 portability pass.

Each will be filled in with **likelihood**, **impact**, **mitigation**, **residual risk**, and a **back-trace** to the Trust Bar principle when the relevant stage lands.

---

## Stage gates that touch this file

- **Stage 1:** review threats applicable to the spike's outbound surface.
- **Stage 2:** populate every helper-related threat with concrete mitigations and tests.
- **Stage 3:** populate the TV-side video playback threats.
- **Stage 6:** populate update mechanism + backup mechanism threats.
- **Stage 7:** final review against every Trust Bar principle (A0–A9, B1–B5, C0–C6); each must have at least one mitigation line here, or be explicitly noted as "not applicable to this architecture" with reasoning.

If a stage closes without its threat-model rows populated, that stage is not done.
