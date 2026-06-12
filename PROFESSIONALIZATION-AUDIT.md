# MyMTS — Professionalization Audit (collaborator-onboarding + security pass)

> Produced before any source change (professionalize.md §2). Governing process:
> the operator's universal `professionalize.md` + the MyMTS-specific security/
> onboarding layer. Rollback tag: `pre-professionalization-20260612T033648Z`.

## Ecosystems (multi-component — resolved per component)

| Component | Toolchain | Tests | Secrets store |
|---|---|---|---|
| **Android TV app** | Gradle (Kotlin/Compose) — `./gradlew` | `./gradlew :app:testReleaseUnitTest` | `app/keystore.properties` (gitignored) + `local.properties` |
| **NAS helper** | Python + `uv` — `cd helper && uv run …` | `cd helper && uv run pytest` | `helper/.env` (gitignored) |
| **LAN web client** | static (`web/` — vanilla `.mjs`, no build step; served by the helper) | n/a (covered by helper `test_web_client_mount`) | none |

---

## §1 — THE SECURITY GATE: git-history-clean verdict

### ✅ VERDICT: HISTORY IS CLEAN — safe to share (no secret in any of 86 commits)

**gitleaks** over full history (`gitleaks detect`, all commits) → 3 findings, **all false positives**:
1. `web/js/app.mjs` `PREFS_KEY = "mymts.web.prefs.v2"` — a browser-localStorage key name, not an API key (flagged by the generic-api-key heuristic).
2/3. `helper/tests/test_log_redaction.py` `redact("X-API-Key: sk_live_supersecret")` — a deliberately-**fake** fixture that *proves* the helper redacts secrets from logs (the test asserts the fake secret is absent from output).

**Targeted MyMTS hazard checks (every commit, ever):**
| Hazard | Result |
|---|---|
| Release keystore (`*.jks`, `mymts-release.jks`) | **never committed** (no `.jks` in any commit) |
| `keystore.properties` (real, with `MYMTS_RELEASE_*` passwords) | **never committed** — only `keystore.properties.example` (placeholders: `replace-with-real-password`) |
| Helper TLS **private** key | **never committed** — 0 `BEGIN … PRIVATE KEY` blocks across history |
| Helper `.env` (real) | **never committed** — only `helper/.env.example` (knobs + placeholders) |
| API keys | **none** — the markets/sports/weather sources are all keyless (Yahoo/ESPN/RSS/CoinGecko) |

**Public-by-design (correctly tracked):** `app/src/main/res/raw/helper_cert.pem` — the helper's **public** TLS certificate, pinned by the app (1 `BEGIN CERTIFICATE`, 0 private-key blocks). Public key material; safe.

**.gitignore** covers the hazards with correct, matching patterns: `*.keystore`, `*.jks`, `keystore.properties`, `*.key`, `helper/**/*.pem` (with `!…/helper_cert.pem` allowlisted), `.env`, `.env.*` (with `!.env.example`). The real `app/mymts-release.jks`, `app/keystore.properties`, `helper/.env` exist locally and are confirmed **gitignored + untracked**.

→ **No history rewrite required.** The keystore-password-in-a-report exposure noted in the prompt is transcript-only; the file never entered git. (Belt-and-braces recommendation in the deferred list: rotate that password at the operator's convenience — out of scope for a behavior-preserving repo pass and not a repo exposure.)

---

## §4 standard checklist (PASS / FAIL / N-A)

| Item | Status | Note |
|---|---|---|
| Secrets in gitignored stores | **PASS** | keystore/.env gitignored + untracked; verified clean from history (§1) |
| `.env.example` from referenced vars (placeholders) | **PASS** | `helper/.env.example` + `app/keystore.properties.example` exist, placeholders only |
| No absolute paths / internal topology in code | **PARTIAL → remediate (§3)** | app helper-URL **default** is the NAS IP; deploy/soak scripts + `docker-compose.nas.yml` carry box IPs + `/srv`; docs carry real IPs |
| Deps audited + lockfiles committed | **PASS** | `helper/uv.lock` + Gradle version catalog committed; deps pinned (FastAPI/uvicorn/etc. exact; no `latest`) |
| `SECURITY-PRACTICES.md` | **PASS** | present |
| Pre-commit secret scanning | **FAIL → add** | gitleaks available but not wired as a pre-commit/CI hook |
| README clone-to-running | **FAIL → fix** | README says *"Stage 0 — no code yet"* (badly stale); no quickstart/demo path |
| ARCHITECTURE.md | **PASS** | present + current (updated through the sports/menu chapters) |
| CONTRIBUTING.md / LICENSE / .editorconfig | **PASS** | all present (LICENSE = MIT) |
| CHANGELOG (Keep a Changelog) | **PASS** | present + maintained |
| Single documented test command per component | **PASS** | helper `uv run pytest`; app `./gradlew :app:testReleaseUnitTest` (in OPERATIONS) |
| Containers: non-root, pinned, runtime secrets, no secret/path in logs | **PASS (audit)** | helper Docker hardened in a prior chapter; log redaction tested (`test_log_redaction`) |
| Demo / Phantom mode | **PARTIAL → finish (§4)** | helper `PHANTOM_MODE` fully built (deterministic fixtures + hostname-blocking resolver); app falls back to `SampleTickerSource`; **missing**: `.phantom.yml`, `ONBOARDING.md`, the collaborator-feedable `ONBOARD-*.md` prompts, CI-runs-demo |
| ZMA (status/notification wiring) | **N-A** | no status-line/notification surface in this repo; the helper `/health` + soak telemetry already cover operational status |

---

## §3 — internal topology surface (to scrub, behavior-preserving)

| Location | What | Plan |
|---|---|---|
| `app/build.gradle.kts:53` | helper-URL **default** `http://<LAN_IP>:8091` | default → `http://localhost:8091`; operator's real URL via gitignored `local.properties` (`MYMTS_HELPER_BASE_URL`) — behavior-preserving once set; **also enables the localhost demo** |
| `scripts/deploy-*.sh`, `probe-tile-count.sh`, `soak.sh` | box/NAS IPs (`.92`/`.3`), `<HOST>` ssh alias | env-var-overridable with placeholder defaults + a gitignored `scripts/.deploy-env` for the operator's real values |
| `helper/deploy/docker-compose.nas.yml`, `deploy-helper.sh` | `/srv/...` NAS paths | parameterize via env with placeholder defaults |
| `helper/tests/test_fetcher.py`, `test_log_redaction.py` | `<LAN_IP>` as a **private-IP fixture** (testing SSRF block / log redaction) | low-priority: swap to a generic `<LAN_IP>` fixture (the IP is incidental to the test) |
| `ONN-BOXES.md`, `docs/OPERATIONS.md` (31), `ARCHITECTURE.md` (5), `CHANGELOG.md` (12), `docs/findings/runs/*/meta.json` | real IPs in operator runbooks/history | operator runbooks → placeholder the live values (real values to a gitignored local note); **deferred-heavy** — these are RFC1918 private IPs (not externally exploitable), so this is topology-hygiene, not a security exposure |

Note: `<LAN_IP>` are RFC1918 **private** addresses — useless to anyone outside the operator's LAN. Scrubbing them is the operator's topology-privacy preference, not a security gate. Prioritized below the secret gate + the demo/onboarding enablers.

---

## Remediation plan (ordered: security → onboarding → topology → cosmetic)

**This pass (must-hit = SAFE + spin-up-able):**
1. ✅ §1 security gate — verified clean (this doc).
2. App helper-URL default → `localhost`; operator real value via gitignored `local.properties` (§3 code scrub + demo enabler).
3. Onboarding deliverables: `.phantom.yml`, `ONBOARDING.md`, and the collaborator-feedable `docs/onboarding/ONBOARD-01-SETUP.md` + `ONBOARD-02-LOCAL-HELPER.md` (scoped to the collaborator's own machine, placeholders only, never any operator secret/infra).
4. README quickstart (clone → demo) replacing the stale "Stage 0" status.
5. Pre-commit gitleaks hook + CI demo-mode (no-secrets) boot check.
6. Clean-clone acceptance test (§7) — fresh clone, no operator config, helper `PHANTOM_MODE` boots clean.

**Deferred (cosmetic/topology-heavy — logged honestly; repo is safe + spin-up-able without it):**
- Exhaustive IP scrub of the operator runbooks (`OPERATIONS.md` ×31, `ONN-BOXES.md`, `CHANGELOG`, `docs/findings/runs/*`) — RFC1918 private IPs, topology-hygiene not security. Recommend a follow-up or gitignoring the operator-private runbook portions.
- Deploy/soak scripts full env-parameterization (operator-side tooling; the collaborator never runs them).
- Test-fixture IP swap (incidental).
- Keystore-password rotation (transcript-only exposure; operator's call; not a repo issue).

**Out-of-scope / manual:** history rewrite (not needed — clean); unrelated host services (untouchable); panel-fit values (locked); on-device wall deploy (this is a repo pass).
