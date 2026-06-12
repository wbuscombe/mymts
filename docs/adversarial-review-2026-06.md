# MyMTS — Adversarial Review (2026-06)

> **Read-only review.** The only change in this pass is this file. No code was modified, nothing was deployed, no secret/keystore/PIA/panel-fit/adb anything was touched. Findings here become separate remediation prompts (campaign 2–4).
>
> **This is MyMTS's FIRST adversarial review** — there is no prior triage state or earlier review to dedup against.
>
> - **Playbook:** Adversarial AI App Review Prompts **v6** (`workspace/adversarial md prompts/adversarial_ai_app_review_prompts_v6.md`). §4 format, §17.1/.2/.3/.5 templates, §6.4/6.8/6.10/6.12/6.18 seeds.
> - **Mode:** §1.6 Agentic Run Mode — evidence self-service ([EVIDENCE] is the default; the repo was read), severity capped by Reality Constraints, v5 environment-invariant check treated as an [EVIDENCE] obligation.
> - **Archetype:** Solo/Homelab/Self-Hosted base + Scraper/Data-Aggregator overlay.
> - **Dimensions (deep):** external-API/vendor failure · data-integrity/schema-drift · deployment/rollback · local-dev/onboarding · maintainability/architecture.
> - **Method:** 5 parallel deep readers over the actual code → every non-P3 finding adversarially re-verified by an independent skeptic (try-to-refute) → cross-cut synthesis. The verification down-graded API-1, API-2, DEPLOY-1, and ARCH-3 and confirmed the rest.
> - **Reviewed at:** commit `b346ac5` (HEAD of `main`).

---

## 1. Executive Summary

**The honest-degradation discipline is genuinely well-held end-to-end** — every failure mode examined degrades *honestly to the viewer* (SAMPLE/STALE pills, play-what-works, never frozen-fake-live). That is the protected invariant and it is real, threaded through helper → app → web. **No P0 exists.** Per the severity cap (P0 = wall-broken or a real secret/topology leak), nothing qualifies: the secret/topology surface is clean at the git-tracking level, and the wall never silently shows wrong data to the couch.

The residual sharp edges are **operator-facing, not external**: *"the operator flies blind on a silently-stale source"* and *"the operator can't cleanly recover a bad deploy."*

**The five dimensions collapse onto two structural roots:**

- **ROOT A — values are tested, procedures are not.** Parsers, DTOs, and pure functions (WallFocusModel @ 49 tests, retention compare, ticker paging) are exhaustively and *realistically* tested — which is why the **data-integrity dimension produced zero confirmed real defects above P3**. But everything that *wraps* those values is untested: the pollers' HTTP-status/stale/keep-snapshot orchestration (**API-3**), and the deploy/rollback *procedures* (**DEPLOY-1/2/3**). Fix these as one initiative: cover the orchestration layer.
- **ROOT B — contract by convention, backstopped by an imaginary CI.** The helper→app→web schema seam is hand-mirrored; the app hard-rejects a bad `schema_version`, the **web client checks it nowhere** (**ARCH-1**). Config knobs are hand-mirrored across 5+ files (**ARCH-3**). Docs assert facts the code contradicts (**ONB-1/2/3**). The one mechanism that would catch all of this — **CI — does not exist** (`.github/workflows/` is empty), yet docs/comments claim it runs on every push (**ONB-3/DEPLOY-4**).

**Top risks (capped):**
| Rank | ID | Priority | One-liner |
|---|---|---|---|
| 1 | DEPLOY-2 | **P1** | No deploy path uses the standing adb invariant (push + byte-size-verify + `pm install` + `lastUpdateTime`); every script streams `adb install` — the exact partial-push failure the rule exists to prevent — and the rollback path streams too (no rollback-of-rollback). |
| 2 | DEPLOY-1 | P2 | `deploy-app.sh` never runs `:app:clean`; reproduces the documented stale-APK trap and can mis-archive a stale build as the new known-good. |
| 3 | DEPLOY-3 | P2 | Helper deploy has **no rollback**: `rsync --delete` + in-place image rebuild overwrites last-good; a failed `/health` verify leaves the broken container running. |
| 4 | API-3 | P2 | Ticker/feed/prober **pollers have zero direct tests** — every degradation/status/stale decision is untested orchestration. |
| 5 | ARCH-1 | P2 | Three-component schema seam by convention; the **web client never checks `schema_version`** → silent-wrong-data on the collaborator's demo after a bump. |
| 6 | ONB-1/2/3 | P2 | Onboarding docs drift from code: "2 live channels" (code: 21), `helper/README` claims a pre-Stage-2 skeleton + a Docker path that can't write its DB, and docs claim a CI that doesn't exist. |

**Fastest wins (hours, doc/one-line):** ONB-1 (prose: "2 channels" → "~21 live"), ONB-3/DEPLOY-4 honesty (either add the minimal CI or stop claiming it), DEPLOY-1 (`:app:clean` one line), DEPLOY-6 (`soak.sh $INTERVAL` typo + Dockerfile healthcheck port), DATA-1 (`math.isfinite` guard), API-5 (add a `ticker` block to `/health`).

**Most dangerous unknowns (assumption ledger):** whether the real `adb install` over `.92` truncates in practice (low-confidence assumption it "works anyway", *against* the operator's own documented lore); whether Yahoo bot-walls as a **200-with-challenge-body** (the trigger that makes API-1/API-2 blind) vs a clean 429 (already caught); and whether the cert-pin host actually resolves/pins on the box (a pure runtime invariant).

**Working and worth preserving (do NOT regress):**
- Parsers are genuinely **fail-closed / never-raise** across every source, with `isinstance` field-checks and the `bool`-is-not-a-number guard — directly tested with binary/junk bodies.
- **Per-symbol / per-league / per-source isolation** is real; one vendor cannot take down the snapshot. `keep-prior-snapshot-on-total-failure` for sports is deliberate.
- **Honest-degradation** is structurally enforced and tested end-to-end (blocked egress → all-sample 200, never a 500 or frozen-fake-live).
- The app's **deploy-app.sh known-good + auto-rollback** flow (atomic pointer, archive retention, health-gate, refuses to push a debug-signed APK) is **above hobby-grade** — live-verified on hardware.
- **Config/secret scrub is clean**: only `.example` templates are tracked; real IPs/host/keystore/`.env` are gitignored with pre-commit gitleaks backstop. The build-time-generated `network_security_config.xml` commits no topology.
- The **.92-transport lore is captured**, not tribal (`docs/ops-local/OPERATIONS.md`), including the NEVER-kill-server warning.
- `feedparser` runs under **defusedxml** (XXE/entity-bomb neutralised); the ticker DTO is a real **tagged-union that scales** (12 sports via a parser registry, not 12 hand-rolled branches).

**Moot by construction (not flagged):** multi-tenant authz/RLS (no tenants, no accounts); PII/privacy-regulatory (no PII anywhere — all public feeds); enterprise observability/paging/SLO/multi-region/HA (solo hobby, one NAS, one TV); inbound-internet attack surface/DDoS/WAF (wall is LAN-only, helper is outbound-only). The one genuinely-P0-capable category — operator-secret/topology leak — is **in scope and currently clean** (gitignore + templates + gitleaks).

---

## 2. Deep findings by dimension

Priorities below are the **post-verification** values (the skeptic's `adjusted_priority`). Each finding notes its verdict.

### 2.1 External API / vendor failure (§6.12) — the marquee dimension

*Preserve:* fail-closed parsers; per-source isolation; HTTP-status-checked-before-parse on every path; the tennis nested-shape lesson correctly internalised; honest SAMPLE/keep-prior-snapshot fallbacks; defusedxml on RSS.

**API-1 — Markets `stale` flag goes blind when Yahoo is bot-walled but CoinGecko answers.** [EVIDENCE] · **P3** *(verified REAL; severity-adjusted P2→P3)*
The envelope `stale` flag is an OR across sources sharing one `real_as_of`; if crypto keeps answering, `stale:false` ships forever while 14/16 markets cells are silently SAMPLE. *Per-entry SAMPLE pills stay honest (good); only the envelope-level signal an operator would grep is blind.* Chain: `_fetch_yahoo`→`{}` (pollers.py:99-121); `if yahoo or coingecko: real_as_of=now` (pollers.py:92) refreshed by crypto; `_is_stale` sees fresh ts (api.py:37-45). **Env-invariant (v5):** the bot-wall is at the **NAS egress IP**, not the dev box — a regression test must simulate Yahoo-blocked + CoinGecko-OK at the *poller* level. **Fix:** add `entries_real/entries_sample` counts to the markets envelope (additive — TV pins `schema_version=1`), or per-source `as_of`. **Preserve-risk:** none — strengthens honest-degradation.

**API-2 — RSS poller records SUCCESS on bozo/zero-item bodies and 200 challenge pages.** [EVIDENCE] · **P3** *(verified REAL; severity-adjusted P2→P3)*
`_store()` calls `record_fetch_success` unconditionally (poller.py:130-147); `bozo`/insert-count only log. A 200-HTML bot-wall → `feedparser` `bozo=True`, 0 items → `last_success_at=now` → `/health` `stale_sources` never flags it (health.py:49-57). The dead feed looks healthy. **Env-invariant:** feed the parser a *real* 200-HTML challenge body and assert FAILURE. **Fix:** treat `bozo and not items` as a soft failure (`record_fetch_failure`); keep success when `bozo` but `items>0`. Optional: reject `text/html` content-type for an RSS source. **Preserve-risk:** none.

**API-3 — Ticker/feed/prober pollers have ZERO direct test coverage.** [EVIDENCE] · **P2** *(confirmed)*
All resilience *logic* (status≥400 handling, `real_as_of`/stale gating, partial-failure isolation, keep-prior-snapshot) lives in `pollers.py`/`poller.py` and is exercised only transitively. No `test_ticker_pollers.py`/`test_feeds_poller.py` exists; `test_channels_prober.py` tests only pure helpers. A future "simplify the `if yahoo or coingecko` gate" or "wipe sports on total failure" regression passes the whole suite. **Env-invariant:** the valuable tests are *failure-injection* (block a source, 429 a league, 200-HTML body) using the already-vendored `respx`, not always-green stubs. **Fix:** a focused poller test module driving `MarketsPoller.poll_once`/`SportsPoller.poll_once`/`FeedPoller._poll_one` with injected resolver + stubbed fetch. **Preserve-risk:** none (pure test addition).

**API-4 — No 429/Retry-After handling or jitter; fixed-interval polling invites escalating blocks.** [INFERRED] · **P3**
On a 429 the code treats it as a generic miss and retries at the same cadence (CoinGecko free + ESPN unofficial). Wall stays fine (SAMPLE/omit) but the source may stay blocked longer. **Fix:** per-source cooldown on a 429 (skip 1–2 cycles); ideally honor `Retry-After` + add interval jitter + a stable contact User-Agent (currently `mymts-helper/0.0`). Directly serves the "respect rate limits" constraint.

**API-5 — Ticker freshness absent from `/health`; the marquee data layer has no health signal.** [EVIDENCE] · **P3**
`/health` exposes feeds+channels but no `ticker` sub-object; `markets_poller.real_as_of`/`sports_poller.real_as_of` live only in-memory on `/api/ticker/*`. `claude-status-bot` (which consumes `/health`) is structurally blind to "markets went all-sample"/"sports poller died." **Fix:** add a `ticker:{markets:{as_of,stale,sample_count},sports:{as_of,stale}}` block built from the two pollers already in scope at `app.py:115-122` — *this is the proper home for API-1/API-2 alerts.* Additive; `/health` schema already allows additive fields. **Preserve-risk:** none (hobby-scaled).

**API-6 — PGA/UFC/F1 read only `events[0]`; concurrent/reordered events silently drop a league.** [EVIDENCE] · **P3**
Hard-indexing `events[0]` (individual.py:106) means a reorder or two concurrent tournaments yields `[]` — honest but lossy, and indistinguishable from "nothing on." **Env-invariant:** test with the live event at index 1+ and with two concurrent events. **Fix:** iterate events and pick the first passing `_is_current_event` (prefer `state=='in'`); keep an output cap for the Onn capacity invariant. **Preserve-risk:** respects the ~4-tile cap as long as event count stays capped.

### 2.2 Data integrity / schema drift (§6.4, §13.1)

*Preserve:* both sides pin `schema_version` and refuse unknown versions; additive-only wire (game/card omitted when null); idempotent dedup (`UNIQUE(source_id,guid) ON CONFLICT DO NOTHING`); crash-safe inserts; DB CHECK/FK/UNIQUE constraints tested; channel `current_url` masked to null unless live; retention sweep verified safe despite the timestamp-format mismatch. **This dimension produced no defect above P3 — the data plane is the strongest part of the system.**

**DATA-1 — NaN/Infinity `regularMarketPrice` passes the numeric guard → fake-LIVE `'nan'`/`'inf'` cell.** [EVIDENCE] · **P3**
`json.loads` accepts `NaN`/`Infinity`; `isinstance(price,(int,float)) and not bool` is True for `float('nan')`, so the symbol is a "real hit", `is_sample=False`, and the formatter emits the literal string `'nan'` — rendered live with no pill (violates honest-degradation for that cell). Repro-confirmed. **Fix:** `math.isfinite(price)` guard in `parse_yahoo_chart` (+ `parse_coingecko` `usd`); a non-finite value then falls to the existing honest sample placeholder. **Preserve-risk:** none — strengthens the invariant.

**DATA-2 — One malformed channels row aborts the ENTIRE channels snapshot (asymmetric vs feed/ticker).** [EVIDENCE] · **P3**
`parseChannels` uses `.map{} + requireString("slug")` (throws), unlike `parseFeed/parseTicker` which `mapNotNull`-skip bad rows — so one slug-less row (or a non-object array element, which `getJSONObject(i)` also throws on before the null-guard in *all three*) collapses the whole fetch to `Result.Err` and the wall shows no channels. Low probability today (helper emits typed rows) → defense-in-depth. **Fix:** a shared `mapArrayRows` helper using `optJSONObject(i)` + per-row try/catch; reserve whole-snapshot abort for envelope breaks (wrong `schema_version`/missing array). **Preserve-risk:** none — improves honest-degradation (show the good rows, drop the bad).

### 2.3 Deployment / rollback (§6.8)

*Preserve:* the app's known-good + auto-rollback flow (atomic pointer, retained archive, health-gate, debug-signed-refusal, dual `IS_RELEASE_SIGNED` signal); helper deploy correctly *rebuilds* (never bare-restarts) and verifies the running `build_sha`; NSC generated from helper URL with a registered task input; clean gitignore/template/gitleaks posture; named-volume strategy that never touches SQLite on redeploy; the two sharpest gotchas already in CHANGELOG.

**DEPLOY-2 — No deploy path uses the standing adb invariant; every script streams `adb install`.** [EVIDENCE] · **P1** *(confirmed)*
The reliable primitive (push + on-device byte-size == local + `pm install -r` + verify `lastUpdateTime` advanced) is implemented in **zero** scripts; `grep` for `adb push`/`pm install`/`lastUpdateTime`/byte-compare across `scripts/` returns nothing. `deploy-app.sh:190` install **and** `:229` rollback both stream `adb install -r` over the flaky `.92` link — so a truncated transfer can corrupt the rollback too (no rollback-of-rollback), and looks identical to a "bad build" to the health-gate. **Env-invariant (v5):** macOS host `stat -f%z` vs Onn toybox `stat -c %s`; `pm install` reads device-local paths only; a test must run against real adb transport, not same-fs tmp. **Fix:** replace the install/rollback with `adb push /data/local/tmp/...` → byte-size compare (abort on differ) → `pm shell pm install -r` → assert `dumpsys package com.mymts | grep lastUpdateTime` advanced; factor into one shared function. **Preserve-risk:** this RESTORES a protected invariant — must keep adb foreground/serial and NEVER `kill-server` (other Onn boxes).

**DEPLOY-1 — `deploy-app.sh` build never runs `:app:clean`; reproduces the stale-APK trap.** [EVIDENCE] · **P2** *(verified REAL; severity-adjusted P1→P2)*
`build_release()` runs only `:app:assembleRelease` (deploy-app.sh:159-162); `local.properties` is read at *configuration* time (build.gradle.kts:57-58) and is **not** a registered task input, so a config change leaves `assembleRelease` UP-TO-DATE and reuses a stale APK — which is then archived under a fresh versioned/sha filename and promoted to known-good (mislabeled lineage). Empirically hit (CHANGELOG.md:622). **Env-invariant:** a regression test must exercise real gradle up-to-date behavior (flip a `-P` property, no clean, assert the embedded value changed), not a mock. **Fix:** add `:app:clean` to the invocation (the operator's own documented remedy); ideally make config values proper task inputs or assert the installed `BUILD_SHA` == `git rev-parse --short HEAD` before promoting. **Preserve-risk:** none.

**DEPLOY-3 — Helper deploy has NO rollback.** [EVIDENCE] · **P2** *(confirmed)*
`rsync --delete` destroys prior `_src`; the image tag is `BUILD_VERSION` (git describe), so an unchanged HEAD rebuilds the same tag **in place**, overwriting last-good; `up -d` swaps the broken container live *before* the 30s `/health` verify; on failure the script `exit 1`s with no corrective action and `restart: unless-stopped` keeps looping the broken container. No `--manual-rollback` equivalent. **Env-invariant:** recovery runs on the NAS; the named volume `mymts-helper-data` must **never** be deleted; rollback must capture the prior image by **ID/digest** (the tag is reused). **Fix:** before `up -d`, `docker tag` the running image as `mymts-helper:last-good`; on verify-failure redeploy it (interim: print the exact `git checkout <sha> && redeploy` command instead of a bare `exit 1`). **Preserve-risk:** any prune/retag must be filtered to `mymts-helper:*` only — NEVER touch unrelated containers; helper stays off any other network.

**DEPLOY-4 — No CI and no PHANTOM boot gate.** [EVIDENCE] · **P3**
`.github/workflows/` is empty; pre-commit covers only secret-scanning + hygiene. A broken build / failing test / drifted demo reaches the manual deploy undetected (first caught at the box). **Env-invariant:** a CI demo gate must run `PHANTOM_MODE=1` (the only shape a public runner can boot — no egress, no secrets; a non-phantom run would make live keyless calls). **Fix:** one minimal Actions workflow — `:app:assembleDebug`, helper `pytest`+`ruff`, and a `PHANTOM_MODE=1` `/health` smoke. Needs no operator secret (release signing falls back to debug). *This is the single highest-leverage action — see §6.* **Preserve-risk:** none (runs on GitHub runners; must NOT add a release-signing job → would require the keystore in CI).

**DEPLOY-5 — Version identity broken: `versionCode=1` hardcoded; `versionName` now derives from the ugly `pre-professionalization-…` tag.** [EVIDENCE] · **P3**
`git describe --tags` resolves to the one-time history-rewrite marker, polluting `versionName` and every archive filename; all APKs share `versionCode=1` (rollback survives only because it leans on the archive/known-good pointer, not the code). **Fix:** add a clean semver tag (e.g. `v0.1.0`) so `git describe` is sane; ideally `versionCode = git rev-list --count HEAD` and derive the archive version from the APK's embedded `versionName` (closes part of DEPLOY-1). **Preserve-risk:** none (signing identity is independent of version).

**DEPLOY-6 — Two latent script defects.** [EVIDENCE] · **P3**
(1) `helper/Dockerfile:75-76` still bakes `HEALTHCHECK ... http://127.0.0.1:8091/health` (removed in the TLS cutover) — fine under the compose override, a footgun for any non-compose `docker run` (perpetually unhealthy). (2) `soak.sh:184` references undefined `$INTERVAL` under `set -u` (should be `$MEMINFO_INTERVAL`) — aborts a soak exactly on the meminfo-timeout-but-alive branch, i.e. under the device contention the fallback exists to survive. **Fix:** one-word `soak.sh` fix; update the baked Dockerfile HEALTHCHECK to `https://127.0.0.1:8443/health` + `EXPOSE 8443`; add a `bash -n`/shellcheck hook (SC2154 would have caught it). **Preserve-risk:** none.

### 2.4 Local dev / onboarding (§6.18)

*Preserve:* zero-secret phantom boot genuinely works (`_default_data_dir()` fallback, auto-discovered `web/` for `/app`); the `--es helper` override is real and load-bearing; secret-adjacent files correctly gitignored (only `.example` tracked); debug-build demo needs no signing; the phantom no-outbound contract is real and tested at the helper level. **Onboarding is mechanically sound — the findings are concentrated in documentation drift.**

**ONB-1 — Every onboarding artifact claims "2 live mock channels"; phantom marks all 21 seed channels live.** [EVIDENCE] · **P2** *(confirmed)*
`phantom.preload()` loops *all* 21 seeded channels and marks each `live` (no `[:2]`); `test_phantom.py` asserts `live_count == channels_count`; yet ONBOARDING.md:46, ONBOARD-01:31, and `.phantom.yml:27/42/49` (and `phantom.py`'s own docstring) say "2." A collaborator's first verify command contradicts the doc. **Fix:** prose — "the seeded channels, all marked live (~21)" — or, if a small deterministic demo is wanted, subset to 2 in code and update the test in lockstep. **Preserve-risk:** none.

**ONB-2 — `helper/README.md` is pre-Stage-2 stale and documents a Docker path that can't write its DB.** [EVIDENCE] · **P2** *(confirmed)*
It claims "skeleton with `/health` only / Stage 2" (false — feeds/channels/ticker are wired and phantom preloads fixtures) and advertises `docker compose up -d` as a quick-start; but `docker-compose.yml` is `read_only` with no `/data` volume, so `db.migrate()` → `mkdir`/SQLite write fails and the container crash-loops (`config.py` home fallback is also read-only at `/app`). **Env-invariant:** `read_only` rootfs + no `/data` volume → only writable path is the 8m `/tmp` tmpfs; a real `docker compose up` smoke is required (a tmp_path unit test proves nothing). **Fix:** rewrite the README to match built reality; either add a writable `/data` volume to the local compose **or** mark the Docker path not-for-demo and point at `uv run`. **Preserve-risk:** the correct fix is a narrow writable volume — do NOT relax `read_only`/`cap_drop` (a real hardening invariant).

**ONB-3 — Docs/comments claim "CI asserts the zero-outbound contract on every push"; no CI exists.** [EVIDENCE] · **P2** *(confirmed)*
`.phantom.yml:46/50`, `test_phantom.py:5-7`, `phantom.py:15`, `ARCHITECTURE.md:215`, `THREAT-MODEL.md:74-75`, `SECURITY-PRACTICES.md:15/21` all assert a CI gate; `.github/workflows/` is empty and no other CI runner exists. The only automated gate is opt-in pre-commit gitleaks (no pytest, no phantom contract). The collaborator's mental model of the safety net is false. **Fix:** make the docs honest ("run `uv run pytest`") **or** add the minimal CI that makes the claim true (it needs no secret). **Preserve-risk:** if CI is added, keep it test/lint-only — a release-signing job would require the keystore in CI (violates the gitignored-key invariant).

**ONB-4 — Top-level README "Build stages" table + "Setup" section are Stage-0 stale** and contradict the README's own "Built" status; the `docs/ARCHITECTURE.md` link is dead (file is at repo root). [EVIDENCE] · **P3** — *Fix:* reconcile to one present-tense state; fix the link.

**ONB-5 — "Zero network egress" framing is helper-scoped, but the full demo egresses to real CDNs.** [EVIDENCE] · **P3**
`phantom.preload()` sets `current_url` to the *real* external HLS URLs from `seed.json`, so the app/ExoPlayer egresses to bloomberg/akamai (and dead `*.invalid`) even in phantom. **Env-invariant:** the no-egress contract holds only inside the helper process, not the separate Android client. **Fix:** scope the wording ("the *helper* makes zero outbound calls; the app will still try the real public stream URLs"); ideally point phantom `current_url` at a bundled local sample for a truly egress-free demo. **Preserve-risk:** keep the dead-`.invalid` tiles rendering honestly offline.

**ONB-6 — ONBOARD-02 implies `/health` `ready` flips false→true as pollers warm; `ready` is hardcoded `True`.** [EVIDENCE] · **P3** — *Fix:* drop the "once pollers warm up" phrasing (point at `items_count`/`live_count` which *do* climb); verify `claude-status-bot` tolerates a dynamic `ready` before wiring one.

### 2.5 Maintainability / architecture (§12.1, §6.9)

*Preserve:* the additive ticker DTO (tagged-union that scales); the 12-sport parser-registry dispatch (not 12 branches); honest-degradation threaded across all three components; the **.92-transport lore captured** in `ops-local`; the pure-function discipline for hard logic (WallFocusModel @ 49 tests); **structurally realistic** parsing fixtures (mirror real upstream nesting).

**ARCH-1 — Three-component schema seam by convention; the web client enforces nothing.** [EVIDENCE] · **P2** *(confirmed)*
The wire contract is restated in 3 hand-maintained copies (helper DTOs, `HelperClient.kt`, `web/js/*.mjs`). The app hard-rejects `schema_version != 1`; the web client never reads `schema_version` (grep across `web/` → zero matches) — on a non-additive bump it renders `undefined`/blank/all-offline while the TV degrades honestly, and the operator (who validates on the TV) may not notice. **Fix:** a `schema_version` guard in `web/js/api.mjs` that surfaces an honest "web client out of date" banner; ideally a cross-component contract test loading one canonical fixture and asserting app + web produce the same display model. **Preserve-risk:** none — the web client currently *can* violate honest-degradation by rendering an unknown schema as valid.

**ARCH-2 — Sports-unavailable state mislabelled 'NEWS' by the ticker pager.** [EVIDENCE] · **P3**
On an ESPN outage the honest "scores unavailable" line gets the hardcoded `News.markerLabel = 'NEWS'` curtain (classification is by-absence: no game + no arrow ⇒ news). **Fix:** thread the known `mode` into `pagesFor` instead of re-deriving by absence. **Preserve-risk:** label-only; keep SAMPLE/STALE/`is_sample=false` semantics. *(Confirm in `TickerStrip.kt` whether the curtain is suppressed for single-line states — may not reach screen.)*

**ARCH-3 — WallSettings/LineupStore/SettingsOverlay config sprawl: every knob touches 5+ files.** [INFERRED] · **P3** *(verified REAL; severity-adjusted P2→P3, tag EVIDENCE→INFERRED for the consequence)*
A setting requires coordinated edits across the field+clamp, a `KEY_` + `put`/`get` + an 18-term "all-absent" guard conjunction (LineupStore.kt:394-403), a cycle/nudge mutator, a callback declared twice on `SettingsOverlay`, and the `WallScreen` wiring — all positional/string-based, not type-checked. A typo'd key or a forgotten guard term silently drops a setting with no compile error. **Fix (do this regardless):** a `WallSettings` full round-trip test (every field non-default → persist → resolve → equal) catches the three silent-desync modes at near-zero risk. Ideal: a declarative `field→key→codec→default` table. **Preserve-risk:** the **locked panel-fit persists through these same keys** — a desync could silently reset it on update; the round-trip test directly protects that invariant.

**ARCH-4 — Parsing tests use hand-built fixtures, not captured real responses.** [INFERRED] · **P3**
A green suite would not catch an upstream reshape; production falls to all-SAMPLE (fails *safe*) but invisibly to tests. **Env-invariant:** reachability is an egress-IP property — a CI test on captured bytes proves parsing, not reachability; a liveness probe must run from the **NAS egress**. **Fix:** commit one date-stamped captured body per upstream family + a parse test; ideally a weekly NAS-side liveness cron that alerts on sustained all-sample.

**ARCH-5 — `ARCHITECTURE.md §16` drifted from code** (Stooq table + "eight team sports" vs live Yahoo + 12 sports; omits `individual.py`). [EVIDENCE] · **P3** — *Fix:* reconcile the §16 source table/data-flow box to code; optionally a CI grep asserting "Stooq" isn't a live source and the doc's league labels match the code constants.

---

## 3. Risk Register (§17.1)

Priority = post-verification. Likelihood/Detectability are this review's estimate under the hobby context. "Det." = how detectable *today* (Low = silent).

| ID | Risk | Tag | Failure scenario | Impact (capped) | Likelihood | Det. | Priority | Evidence inspected | Mitigation | Proving artifact for closure |
|---|---|---|---|---|---|---|---|---|---|---|
| DEPLOY-2 | Streamed `adb install` everywhere; no push+byte-verify+`pm install`+`lastUpdateTime`; rollback streams too | EVIDENCE | Truncated `.92` transfer ships/rolls-back a partial APK, mis-read as a bad build | Operator can't reliably deploy/recover the wall | Med | Low | **P1** | deploy-app.sh:189-190,229; deploy.sh:22; soak.sh:103; probe-tile-count.sh:115; grep→none | Shared `push+size-verify+pm install+lastUpdateTime` fn; foreground/serial; never kill-server | A deploy-harness test that truncates the on-device copy and asserts abort-before-install, run over real adb transport |
| DEPLOY-1 | Build step never `:app:clean`s; `local.properties` not a task input | EVIDENCE | Config change → stale APK promoted as new known-good (mislabeled) | Wall runs old code; corrupted known-good lineage | Med | Low | P2 | deploy-app.sh:159-185; build.gradle.kts:55-62,181-189; CHANGELOG:622 | Add `:app:clean`; or assert installed `BUILD_SHA`==HEAD before promote | Two builds with a flipped `-P` value, no clean → assert embedded value changed (real gradle) |
| DEPLOY-3 | Helper deploy: `rsync --delete` + in-place image rebuild; no rollback; broken container left live | EVIDENCE | Bad helper deploy → wall backend down/stale, recovery fully manual | Wall data layer down for hours | Med | Med | P2 | deploy-helper.sh:91-145; nas.yml:25,34; deploy-app.sh:213-232 (contrast) | Tag running image `last-good`; redeploy on verify-fail; print recovery cmd | Deliberately-broken helper deploy → asserts auto-restore of prior container; volume never deleted |
| API-3 | Pollers/prober have zero direct tests | EVIDENCE | Degradation-logic regression ships green | Wall blanks on a vendor hiccup undetected | Med | Low | P2 | pollers.py:88-253; test_ticker_api.py; tests dir listing | Poller test module (respx + injected resolver), failure-injection | New `test_ticker_pollers.py`/`test_feeds_poller.py` covering status≥400/partial/keep-snapshot |
| ARCH-1 | 3-component schema seam by convention; web client checks no `schema_version` | EVIDENCE | Non-additive bump → web renders silent-wrong-data | Collaborator's demo silently wrong | Low | Low | P2 | web/js/api.mjs:13-30; render.mjs:41-49; HelperClient.kt:107-227 | `schema_version` guard + "out of date" banner in web; cross-component contract test | A contract test where a field rename fails app+web from one fixture |
| ONB-1 | Docs say "2 live channels"; code marks 21 live | EVIDENCE | First verify command contradicts the doc | Collaborator debugs a non-bug | High | High | P2 | phantom.py:77-85; seed.json; test_phantom.py:64-80; ONBOARDING:46 | Prose fix (or subset-to-2 in code+test) | Doc matches `/api/channels` count; a pin test |
| ONB-2 | `helper/README` pre-Stage-2 + broken Docker-compose DB path | EVIDENCE | Collaborator thinks helper is a skeleton / `docker compose up` crash-loops | Collaborator blocked/confused | Med | Med | P2 | helper/README:5,18-23,30; docker-compose.yml:19-41; config.py:14-22 | Rewrite README; add `/data` volume or mark Docker not-for-demo | `docker compose up` smoke returns `/health` 200, or doc points at `uv run` |
| ONB-3 | Docs claim CI asserts the contract; no CI exists | EVIDENCE | False safety net; unenforced phantom/schema contracts | Regression merges undetected | Med | Low | P2 | .github/workflows (empty); .phantom.yml:45-50; test_phantom.py:5-7 | Honest docs **or** add the minimal CI | Either docs corrected, or a green CI run on push |
| API-1 | Markets `stale` flag blind when one source survives | EVIDENCE | Yahoo bot-walled + crypto up → `stale:false` forever | Operator can't see a half-dead markets row | Med | Low | P3 | pollers.py:88-93; api.py:37-69; markets.py:186-217 | `entries_real/entries_sample` in envelope; per-source `as_of` | Poller test: Yahoo-blocked+CoinGecko-OK surfaces degradation |
| API-2 | RSS success recorded on bozo/200-HTML | EVIDENCE | Dead feed reports healthy in `/health` | Operator can't see a dead feed | Med | Low | P3 | poller.py:111-147; store.py:72-78; health.py:39-64 | `bozo and not items` → soft failure; content-type check | Poller test: 200-HTML → `record_fetch_failure` |
| API-4 | No 429/Retry-After/jitter | INFERRED | Fixed cadence prolongs a rate-limit block | Source stays SAMPLE longer | Med | Low | P3 | pollers.py:88-136; fetcher.py:170-181 | Per-source cooldown on 429; honor Retry-After | Poller test: 429 engages a cooldown |
| API-5 | Ticker freshness absent from `/health` | EVIDENCE | Heartbeat blind to markets/sports outage | Operator finds out via the TV | High | Low | P3 | health.py:88-117; app.py:160-170; api.py:48-69 | Add `ticker` block to `/health` | `/health` includes ticker freshness; backward-compat |
| API-6 | PGA/UFC/F1 hard-index `events[0]` | EVIDENCE | Reorder/concurrent events drop a league silently | A known-live league vanishes | Low | Low | P3 | individual.py:103-112,271-276,362-368 | Iterate events, pick first current; cap output | Test: live event at index 1+ still surfaced |
| DATA-1 | NaN/Inf price passes the numeric guard | EVIDENCE | Yahoo NaN → `'nan'` shown as live | One garbage markets cell shown live | Low | Low | P3 | markets.py:135-142,86-114,186-202; HelperClient.kt:142-158 | `math.isfinite` guard (price/prev/usd) | Test: NaN/Inf `regularMarketPrice` → `None`/sample |
| DATA-2 | One bad row aborts whole channels snapshot | EVIDENCE | Slug-less/non-object row → zero channels | Wall shows no channels on a partial payload | Low | Low | P3 | HelperClient.kt:232-254,61-68 | `mapNotNull` + per-row try/catch (shared helper) | Test: one bad row → N valid channels survive |
| DEPLOY-4 | No CI / no PHANTOM gate | EVIDENCE | Broken build/demo caught only at the box | Late discovery mid-deploy | High | Low | P3 | .github/workflows (empty); .pre-commit-config.yaml | Minimal Actions: assembleDebug + pytest/ruff + phantom smoke | A green CI run gating push/PR |
| DEPLOY-5 | `versionCode=1`; `versionName`=ugly tag | EVIDENCE | Archive identity confusing; `-d` flag dead | Harder manual rollback selection | Med | Med | P3 | build.gradle.kts:178,14-27,179; deploy-app.sh:177-181 | Clean semver tag; `versionCode`=commit count | A build → semver `versionName` + distinct codes |
| DEPLOY-6 | Stale Dockerfile HEALTHCHECK :8091; `soak.sh $INTERVAL` | EVIDENCE | Non-compose run perpetually unhealthy; soak aborts on contention | Latent footgun + lost soak telemetry | Low | Med | P3 | Dockerfile:73-76; nas.yml:104-112; soak.sh:2,62,184 | One-word soak fix; bake :8443 healthcheck; shellcheck hook | `docker run` standalone reaches healthy; `bash -n` clean |
| ONB-4 | README stage table/Setup Stage-0 stale; dead ARCHITECTURE link | EVIDENCE | README self-contradicts | Collaborator confusion | High | High | P3 | README:5,63,67-82 | Reconcile to present tense; fix link | One consistent README; link resolves |
| ONB-5 | "Zero egress" wording broader than enforced | EVIDENCE | App egresses to real CDNs in phantom | Surprise on a metered network | Med | Med | P3 | phantom.py:81-84; TileSlotResolver.kt:112-119; ONBOARDING:33-35 | Scope wording (or local sample stream) | Doc scopes egress to the helper |
| ONB-6 | `ready` hardcoded True vs doc warm-up implication | EVIDENCE | Misleading readiness field | Minor confusion | Low | High | P3 | ONBOARD-02:18; health.py:100-108 | Fix doc (or wire `ready` to poll state) | Doc matches behavior |
| ARCH-2 | 'NEWS' marker over 'scores unavailable' | EVIDENCE | Mislabelled honest state | Operator mis-reads the outage | Med | Med | P3 | TickerPaging.kt:45,58-64; HelperTickerSource.kt:205-298 | Thread `mode` into `pagesFor` | TickerPagingTest: unreachable SPORTS → marker not 'NEWS' |
| ARCH-3 | Config sprawl across 5+ files, hand-mirrored | INFERRED | Forgotten key/guard silently drops a setting (incl. locked fit) | Setting/locked-fit silently resets on update | Med | Low | P3 | WallSettings.kt; LineupStore.kt:106-128,388-431; SettingsOverlay.kt:85-106 | Full round-trip test; ideal: declarative table | A WallSettings all-non-default round-trip test |
| ARCH-4 | Hand-built (not captured) parsing fixtures | INFERRED | Upstream reshape → silent all-SAMPLE, suite stays green | Operator can't tell drift from quiet week | Med | Low | P3 | test_ticker_*.py; markets.py:216-217 | Committed captured fixtures + NAS liveness cron | A captured-body parse test per upstream family |
| ARCH-5 | `ARCHITECTURE.md §16` drift (Stooq/8-sport) | EVIDENCE | Canonical doc lags code | Maintainer makes a wrong change | Med | Med | P3 | ARCHITECTURE.md:792-823; markets.py:41-60; individual.py:399-404 | Reconcile §16 to code; optional CI grep | §16 table = code; "Stooq" not a live source |

---

## 4. Assumption Ledger (§17.2)

| ID | Assumption | Why it matters | Evidence for | Evidence against | Confidence | How to verify | Linked risk |
|---|---|---|---|---|---|---|---|
| A1 | Prod helper runs in the hardened container as uid 10001 writing the named volume at `/data` (not the dev `~/.local/share` fallback) | DB-writability/retention/permission findings assume `/data` is live | nas.yml named volume :71-77; config.py fallback only when `/data` unwritable; deploy-helper backup targets the volume | `config.py`'s **silent** fallback means a `/data` permission regression would relocate the DB and still boot green — repo can't prove which path is live | High | `docker exec` the running helper: where `mymts.db` actually lives + process uid; test the read-only-`/data` env invariant | ONB-2, A-set |
| A2 | Retention lexical timestamp compare ('T'/`.000Z` vs space-separated) behaves identically under the prod SQLite build | A wrong compare could delete fresh items or keep stale | Repro-confirmed 13d kept / 15d deleted; dominated by identical `YYYY-MM-DD` prefix | Correct by coincidence of the shared prefix; fragile to any format change; container SQLite may differ | Medium | Run `retention_sweep` against the container SQLite with boundary-straddling rows; better, normalize both sides + test with the prod shape | DATA (storage) |
| A3 | The cert-pinned LAN HTTPS path resolves/pins to the real helper host at runtime; generated NSC matches device resolution | The whole app↔helper security model | NSC generated from `helperUrl` (registered input); deploy verifies `/health` over loopback HTTPS | Pin-host resolution + SAN match + rotation behavior are pure on-device runtime — not provable from source | Medium | On the box: confirm HTTPS connects with the pinned cert; a deliberately-wrong cert is rejected | (security baseline) |
| A4 | Streamed `adb install` does NOT in practice trigger the truncating-push/0-byte/deadlock modes the invariant warns about | If false, DEPLOY-2 is live, not latent | deploy-app.sh live-verified 2026-06-04; lore captured even if not enforced | The operator's own documented invariant says streamed install is the failure to avoid; scripts use it exclusively with no byte/`lastUpdateTime` gate | **Low** | Reproduce a flaky-transport push on `.92`; observe partial/stale ship; the fix's test must assert byte-equality + `lastUpdateTime` on-device | DEPLOY-2 |
| A5 | When Yahoo is bot-walled it returns 200-with-challenge-body (parser fails closed) rather than a clean 403/429 | Determines whether API-1/API-2 are live blind spots or already-caught | Yahoo migration was *because* of bot-walling; status is checked before parse (so 403/429 IS caught) — blind spot only on 200-bad-body | The exact status under bot-walling is external runtime conduct; could be 429 (caught) | Medium | Capture real bot-walled responses (status+body) over a soak; 200-challenge ⇒ blind spots live; 429/403 ⇒ already covered | API-1, API-2 |
| A6 | `phantom_resolver` hard-fails ALL outbound at runtime, so the demo truly makes zero helper egress | The protected zero-outbound contract | phantom.py:37-41 raises on every host; pollers skipped; test_phantom boots e2e | Guards only the helper's own fetch path, not a dependency opening its own socket; asserted by ONE local test (no CI — ONB-3) | High | Run the phantom helper under an OS/firewall-level egress block and confirm boot+demo still work | ONB-3, A-set |

---

## 5. Engineering Backlog

Each item links a risk; effort and protected-invariant-risk noted. **This backlog seeds campaign prompts 2–4.**

### Today (≤ a couple hours, mostly doc/one-line; do regardless)
| Item | Risk | Why | Effort | Invariant risk | Verification |
|---|---|---|---|---|---|
| Fix "2 live channels" → "~21 live" in ONBOARDING/ONBOARD-01/.phantom.yml + phantom.py docstring | ONB-1 | First verify command currently contradicts the doc | XS | None | Doc matches `/api/channels` |
| Make the CI claim honest: change "CI asserts…" → "run `uv run pytest`" (or land the CI in §This Week) | ONB-3, DEPLOY-4 | False safety net | XS | None | No doc asserts a non-existent CI |
| `soak.sh:184` `$INTERVAL`→`$MEMINFO_INTERVAL`; bake Dockerfile HEALTHCHECK→`https://…:8443` + `EXPOSE 8443` | DEPLOY-6 | Soak aborts under contention; non-compose run unhealthy | XS | None | `bash -n` clean; `docker run` standalone healthy |
| Add `:app:clean` to `build_release()` | DEPLOY-1 | The operator's own documented remedy for the stale-APK trap | XS | None | Config-changed rebuild reflects on device |
| `math.isfinite` guard in `parse_yahoo_chart`/`parse_coingecko` | DATA-1 | A NaN cell shown as live violates honest-degradation | XS | None (strengthens it) | Test: NaN/Inf → sample |
| Reconcile README stage table/Setup to "Built"; fix `docs/ARCHITECTURE.md`→`ARCHITECTURE.md` link | ONB-4 | README self-contradicts | S | None | One consistent README; link resolves |
| Scope "zero egress" wording to the helper | ONB-5 | App still egresses to CDNs in phantom | XS | None (keep dead-tile honesty) | Doc scopes the claim |

### This Week (the two structural roots)
| Item | Risk | Why | Effort | Invariant risk | Verification |
|---|---|---|---|---|---|
| **Minimal GitHub Actions CI**: `:app:assembleDebug` + helper `pytest`+`ruff` + a `PHANTOM_MODE=1` `/health` smoke | DEPLOY-4, ONB-3, ARCH-1 venue | The single highest-leverage action — closes ONB-3, hosts the schema + orchestration tests | M | None (must stay test/lint-only; NO keystore in CI) | Green run gates push/PR |
| **Adopt the standing adb invariant** in `deploy-app.sh` install **and** rollback (and the shared helper for soak/probe) | DEPLOY-2 | Restores a protected invariant the scripts never wired in | M | Restores invariant — keep adb foreground/serial; never kill-server | Truncated-copy test aborts before install (real transport) |
| **Helper rollback**: tag running image `last-good` before `up -d`; redeploy on verify-fail (or at least print the recovery command) | DEPLOY-3 | No recovery path today | M | Scope retag/prune to `mymts-helper:*`; never delete the data volume; never touch other containers | Broken deploy auto-restores prior container |
| **Poller test module** (respx + injected resolver): status≥400 / partial / keep-snapshot / bozo-success / Yahoo-blocked+crypto-OK | API-3, API-1, API-2 | Covers the untested orchestration root; pins the degradation contract | M | None | New poller tests fail on a degradation regression |
| `WallSettings` **full round-trip test** (every field non-default → persist → resolve → equal) | ARCH-3 | Catches the three silent-desync modes incl. a locked-panel-fit reset | S | None (protects the locked-fit invariant) | Round-trip test green; a forgotten key fails it |

### This Month
| Item | Risk | Why | Effort | Invariant risk | Verification |
|---|---|---|---|---|---|
| Add a `ticker` block to `/health` (markets/sports `as_of`/`stale`/`sample_count`) | API-5, API-1, API-2 | Gives the heartbeat eyes on the marquee data layer; home for API-1/2 alerts | S | None (additive) | `/health` carries ticker freshness; bot alerts |
| `schema_version` guard + "out of date" banner in `web/js/api.mjs`; cross-component contract test from one fixture | ARCH-1 | Stops silent-wrong-data on the web demo | M | None | A field rename fails app+web tests |
| Align channels parser to `mapNotNull` + per-row skip (shared `mapArrayRows`) | DATA-2 | One bad row shouldn't drop all channels | S | None | Partial payload → valid subset survives |
| Per-source 429 cooldown (+ honor `Retry-After`, jitter, contact UA) | API-4 | Respect keyless rate limits | S–M | None | Poller test: 429 → cooldown |
| Iterate events (not `events[0]`) for PGA/UFC/F1 | API-6 | Reorder/concurrent robustness | S | Keep output cap (Onn capacity) | Test: live event at index 1+ |
| Clean semver tag (`v0.1.0`) so `versionName`/archives are sane; consider `versionCode`=commit count | DEPLOY-5 | Fixes the ugly-tag version identity | S | None (signing unchanged) | Build → semver version |
| Rewrite `helper/README.md` to built reality; fix/flag the Docker-compose DB path | ONB-2 | Helper README contradicts reality + a crash-looping path | S | Fix = narrow `/data` volume, NOT relaxing `read_only` | `docker compose up` smoke or doc points at `uv run` |
| Reconcile `ARCHITECTURE.md §16` (Yahoo, 12 sports, add `individual.py`) | ARCH-5 | Canonical doc lags code | S | None | §16 = code |
| Thread `mode` into ticker `pagesFor` (kill by-absence classification) | ARCH-2 | Fixes 'NEWS' over 'scores unavailable' | S | Label-only; keep pill semantics | TickerPagingTest marker assertion |

### Later (nice-to-have / depends on appetite)
| Item | Risk | Why | Effort | Invariant risk | Verification |
|---|---|---|---|---|---|
| Captured-real fixture per upstream family + weekly **NAS-egress** liveness cron (ntfy on sustained all-sample) | ARCH-4, A5 | Turns "silent all-sample" into a push; documents real shapes | M | Run from NAS, not CI/Mac; respect rate limits | Captured-body parse tests; cron alert fires on forced block |
| Declarative settings table (field→key→codec→default) replacing hand-mirrored put/get + single `onChange(WallSettings)` | ARCH-3 (ideal) | Removes the per-knob 5-file tax | M–L | Behavior-sensitive (ordinals/clamps/overscan migration) — land the round-trip test first | Round-trip test stays green through the refactor |
| `bash -n`/shellcheck pre-commit hook over `scripts/` | DEPLOY-6 | Would have caught `$INTERVAL` (SC2154) | XS | None | Hook flags SC2154 |
| Make `/health` `ready` reflect "≥1 successful poll OR phantom" | ONB-6 | Field would mean something | S | Verify `claude-status-bot` tolerates `ready:false` first | Test: `ready` false before first poll |

---

## 6. The single highest-leverage action

**Stand up one minimal CI.** It (1) runs the existing `pytest` suite, (2) hosts the new **poller tests** (API-3 / the ROOT-A orchestration coverage), (3) asserts **`schema_version` across helper + app + web** (ARCH-1 / ROOT-B), and (4) asserts the **phantom zero-outbound contract** the docs already claim it does. That one workflow simultaneously closes ONB-3/DEPLOY-4, hardens ARCH-1, and creates the venue for API-3 and DEPLOY-2 regression coverage — the highest-leverage single move across all five dimensions. It needs **no operator secret** (everything is keyless/phantom; release signing falls back to debug) and fits the hobby budget (free runner minutes). **Keep it test/lint-only — never add a release-signing job (that would require the keystore in CI, violating the gitignored-key invariant).**

**Recommended campaign order:** the planned order (2: professionalize+standards → 3: browser-parity → 4: screenshots/README) is sound. **One adjustment:** fold the **DEPLOY-2** adb-invariant fix and the **CI** into campaign 2 (professionalize/standards) rather than later — DEPLOY-2 is the lone P1 and is a deploy-safety/standards item, and the CI is the backbone that several other campaigns' guarantees lean on. Nothing here is a P0 that must pre-empt the queue.

---

## 7. Guardrails for Future AI Coding Work (§17.5)

> Source material for an `AGENTS.md` (campaign prompt 2 will generate it).

**Preserve (load-bearing — do not "fix"):**
- The **honest-degradation discipline** end-to-end: SAMPLE/STALE pills, play-what-works, `is_sample` defaults to *true*, channels mask `current_url` unless live, "scores unavailable"/"no games" are real states. Never hide not-live state.
- The **locked panel-fit** (Fit 80% / Vstretch 110% / Overscan None / Pos 0,0). It persists through `LineupStore` keys — a settings refactor must round-trip it intact.
- **Manual-launch-on-boot** (kiosk holds once launched). Do NOT re-pursue device-owner/lock-task or wall-on-boot.
- The **same release-signing key**, gitignored, never regenerated; `IS_RELEASE_SIGNED` gate.
- **Fail-closed parsers** + per-source isolation + keep-prior-snapshot-on-total-failure. defusedxml on RSS.
- The app's **known-good + auto-rollback** deploy flow and the helper's **rebuild-not-restart** discipline.

**Never do without explicit approval:**
- `git push --force` / history rewrite (the one authorized rewrite is done; the rule has resumed).
- Touch the **PIA VPN** container / any `service:vpn` config (helper is not behind it; other NAS services are).
- Background `adb`, or `adb kill-server` (would disrupt the other Onn boxes `.182`/`.158`). One foreground adb op at a time.
- Relax the helper container hardening (`read_only`, `cap_drop`, non-root uid 10001) to make something write — use a narrow volume instead.
- Add a release-signing job to CI / put the keystore anywhere shared.
- Change a wire schema non-additively without updating **all three** consumers (helper DTO, `HelperClient.kt`, `web/js`) + bumping `schema_version`.

**Sensitive areas (read the lore first):**
- The flaky `.92` adb transport — see `docs/ops-local/OPERATIONS.md` (truncating push, 0-byte, streamed-install deadlock, recovery).
- The Compose-for-TV **focus model** (`WallFocusModel` — pure, 49 tests; keep it pure).
- The TLS pin / generated `network_security_config.xml` (build-time from the helper URL; commits no topology).
- `LineupStore`/`WallSettings` persistence (the locked-fit lives here).
- Gitignored local config: `local.properties`, `scripts/deploy.local.env`, `docs/ops-local/`, `app/keystore.properties`, `*.jks`/`*.key`.

**Before code changes:** `git pull --ff-only`; confirm a clean tree; know whether you're in the dev checkout (you are — never operate on a deploy copy).
**After code changes:** run `uv run pytest` (helper) and `./gradlew :app:testReleaseUnitTest` (app); for a settings change, manually toggle→force-stop→relaunch and confirm persistence + the locked fit.
**Secrets:** never echo/commit a secret; only `.example` templates are tracked; pre-commit gitleaks is the backstop.
**Dependencies:** pinned + lockfile (`uv.lock`, gradle version catalog); security-only bumps isolated in a `security:` commit.
**Testing:** test *procedures and orchestration*, not just values — failure-injection over mocked-green; for any env-shape-dependent fix, the regression test must exercise the **environment invariant** (Docker/uid/read-only-`/data`, real adb transport, NAS egress), not a same-filesystem tmp or a mocked-green upstream.
**Scope & uncertainty:** this is a solo homelab display — no auth, no enterprise observability, no multi-region. Cap severity by the blast radius (P0 = wall-broken or a real secret/topology leak only). When the failure is runtime/external (vendor conduct, on-device TLS), tag it [INFERRED]/[SPECULATIVE] and say what would confirm it.

---

## 8. Method note & honest gaps

5 parallel deep readers (one per dimension) over the real code → every non-P3 finding adversarially re-verified by an independent skeptic instructed to refute → cross-cut synthesis. The verification **down-graded** API-1, API-2, DEPLOY-1, and ARCH-3 (and re-tagged ARCH-3's consequence INFERRED) and **confirmed** the rest — the register is deliberately not inflated.

**What this read-only pass did NOT cover (a hostile reviewer's list — candidates for a follow-up campaign):**
- The **web client** (`web/js/render.mjs/app.mjs/video.mjs`) was only spot-checked — its own honest-degradation behavior (does it show SAMPLE/STALE? crash on a null game/card?) is uncharacterized beyond ARCH-1.
- **Weather video sources** (Fox Weather / AccuWeather NOW), the **HLS prober**, and **ExoPlayer/Media3 runtime** failure handling (buffering/codec/dead-stream recovery on the box) are nearly absent — and video is a primary surface.
- **No dimension ran against the real environment** (container uid / read-only `/data` fallback, on-device cert-pin, `.92` truncation, OS-level egress block) — every such fix needs an environment-exercising test this review can only *specify*.
- **Concurrency/SQLite locking** under the concurrent poller sweep + retention overlap was not analysed (crash-safety is covered; lock contention is not).
- `health_check.py` (the promote-vs-rollback gate) + `test_health_check.py`, and the **runtime** redaction coverage of `log.py` for IPs/`truenas` in error paths, were not read — both underpin claims made elsewhere.
- `ARCHITECTURE.md`/`CHANGELOG.md` were grep-sampled, not read end-to-end; `PROFESSIONALIZATION-AUDIT.md`/`SECURITY-PRACTICES.md` were not opened — some findings may already be *known-and-accepted* there.
- **Keyless-API rate-limit/ban risk over a long soak** (cadence vs each vendor's tolerance) was not evaluated — the scenario API-1's blind stale-flag would hide.
