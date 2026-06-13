# Adversarial Re-Review — New Code Since Campaign 1 (2026-06-13)

**Scope:** the NEW attack surface only — (A) HALF 1 web-client parity (`web/`), (B) HALF 2 playlist/M3U + profiles (`helper/.../playlist/`), (C) the two emergency deploy-infra fixes `4933f87` (deploy cert-mount/.env path) and `8ce1242` (seeder reconcile-and-PRUNE). Campaign 1 already hardened the pollers/markets/deploy-invariant/honest-degradation core — not re-litigated here.

**Method:** READ-ONLY. 6 independent reviewers (2 on the destructive seeder) → every finding adversarially verified (proof-of-impact re-derived, default-to-dismiss) → synthesized against the author's own ground-truth reads. Adversarial-playbook discipline: EVIDENCE / INFERRED / SPECULATIVE tags; P0–P3 with the **solo/homelab + scraper/data-aggregator proportionality cap** (no multi-tenant, no PII, no inbound surface, no auth; feed items are re-fetchable public-RSS cache, not durable operator data).

> **No code was changed. This report is the only artifact — the architect decides remediation.**

---

## Triage summary

| Severity | Count | Items |
|---|---|---|
| **P0** | **0** | — |
| **P1** | **0** | — (the seeder data-loss P1 hypothesis was **refuted** — see below) |
| **P2** | **0** | — |
| **P3** | **3** | F1 (confirmed) + F2, F3 (hardening nits) |

**Verdict: the new code is as solid as the reports claimed.** Zero P0/P1/P2. The three P3s are *defensive-completeness* nits — each requires a self-inflicted, near-implausible trigger (no attacker/network path exists anywhere in the new surface), and the worst-case impact is re-fetchable RSS cache, not durable data. **The destructive seeder prune is SAFE** (definitive read below).

---

## The architect's #1 question — is the destructive seeder prune (`8ce1242`) safe? **YES.**

The red-team's job was to find an input that makes `seed_from_file` + `store.delete_sources_not_in` **delete more than intended**. The definitive, EVIDENCE-backed answer: there is **no data-loss path that meets the bar**. The safety rests on five independently-verified facts:

1. **`seed.json` is the SOLE source of truth — there is no other way to create a source.** `feeds/api.py` exposes only `GET /api/feed` and `GET /api/feed/sources` — **no POST/PUT/DELETE** mutation endpoint (EVIDENCE: `feeds/api.py:38,63`). The only writer of real sources is the seeder. So pruning the DB to the seed can only remove *prior-seed orphans* — never operator data created by some other mechanism, because none exists.
2. **The seed is immutable in the running image.** `app.py` resolves it solely via `importlib.resources.files("mymts_helper.feeds").joinpath("seed.json")` — the package resource baked in at build (EVIDENCE: `app.py:_resolve_feed_seed_path`). There is no env override; `docker-compose.nas.yml` bind-mounts only `/data` (sqlite) and a read-only `/app/web` — it never mounts over the packaged seed. A "partial/truncated seed at runtime" cannot occur.
3. **Malformed input raises BEFORE the prune.** A non-dict entry → `SeederError` mid-loop (`seeder.py:49-50`), malformed JSON / root-not-list → `SeederError` at parse — all *before* the prune block is reached. So a garbage seed never causes a partial/over-prune; it fails boot loudly (same as pre-existing behavior).
4. **The empty/all-invalid GUARD works, and the cascade is proven.** `if seed_urls:` (`seeder.py`) skips the prune when no valid URL was parsed — tested by `test_empty_or_broken_seed_does_not_wipe_existing_sources`. The item cascade (`ON DELETE CASCADE`, migration `001`; `PRAGMA foreign_keys=ON` in `db.connect`) is proven by `test_seed_prunes_repointed_source_and_its_items` (asserts `total_items == 0` after prune).
5. **Matching is same-run, same-source-of-truth robust.** The upsert set and the `keep_urls` set are both derived from the *same* `entries` in the *same* run, so a just-upserted source's exact URL string is guaranteed present in `keep_urls` — only genuine prior-seed orphans are pruned. And the only data that ever cascade-deletes is `feed_items` — explicitly *"normalized, sanitized news items"* / re-fetchable public RSS (migration `001` header: "the operator owns content; the helper just stores their registration + fetch state"), refilled within one poll cycle.

**Conclusion:** Scenario "repointed URL prunes the old source" is the **intended design** (it's exactly the NBC Spanish→English fix). Scenario "a whitespace-only seed wipes the table" is mechanically real but requires hand-editing the immutable in-image seed to a lone `"   "` URL and rebuilding — self-inflicted, no runtime path, re-fetchable-cache impact. Not P1. The residual is a single P3 `.strip()` hardening nit (F2).

---

## Confirmed findings

### F1 — `load_profiles` does not catch `RecursionError` → boot crash on a pathologically-nested profiles file
- **Tag:** EVIDENCE · **Severity:** P3 (proportionality: operator-authored local config, no attacker/network path, near-zero realistic trigger)
- **Area:** HALF 2 — `helper/src/mymts_helper/playlist/profiles.py`
- **Detail / proof:** The loader catches `except (OSError, ValueError)` around `json.loads(p.read_text())` (`profiles.py:87-88`), and `app.py:100` calls `load_profiles` at startup, so an uncaught exception **crashes boot** — violating the loader's own documented contract ("the wall must boot even with a bad/again-bad config file… never crashes on a bad file", `profiles.py:74-77`). The verifier **corrected the original finding's rationale**: on Python 3.13 with the C-accelerated decoder, deeply-nested JSON (≈10,000 levels of `[[[…]]]`) raises **`RecursionError`** (a `RuntimeError` subclass), *not* `JSONDecodeError` — so it escapes the `(OSError, ValueError)` handler and propagates. This is the **same class** of "narrow `except` misses a sibling exception" as the `UnicodeDecodeError` gap caught during the HALF 2 review (fixed there by broadening to `ValueError`); the recursion sibling remains.
- **Why only P3:** the trigger is a ~10k-deep nested *operator-authored* `PROFILES_FILE` — not a plausible typo on a flat `{name, slugs:[]}` file, and there is no inbound/attacker path (single operator, LAN-only, no add-profile API). It breaks a stated invariant cheaply, but no operator would realistically hit it.
- **Suggested remediation (NOT applied):** broaden to `except Exception` (fail-closed to default-only, honoring the "always boots" contract) — or minimally `except (OSError, ValueError, RecursionError)`.

---

## Hardening nits (P3 — surfaced during the seeder red-team; below the action bar but noted)

### F2 — Seeder URL validation accepts whitespace-only URLs (truthiness, not `.strip()`)
- **Tag:** EVIDENCE · **Severity:** P3 (hardening)
- **Detail / proof:** `seeder.py:53` (`if not isinstance(url, str) or not url:`) and the `keep_urls` comprehension both use bare truthiness; `bool("   ")` is `True`, so a whitespace-only URL passes validation, gets upserted, and enters `keep_urls`. A seed consisting of *only* a whitespace entry would therefore pass the `if seed_urls:` guard and prune every real source. **No runtime path** (seed is image-baked; `/api/feed/sources` is GET-only); impact is re-fetchable cache; requires a self-inflicted in-image edit + rebuild.
- **Suggested remediation (NOT applied):** validate `url.strip()` (and store/compare the stripped value) — closes the guard gap and also de-duplicates trivial-whitespace variants.

### F3 — Seeder prune uses exact URL string matching (no normalization)
- **Tag:** EVIDENCE · **Severity:** P3 (hardening) — verifier confirmed mechanism, refuted severity
- **Detail / proof:** `upsert_source` keys on `ON CONFLICT(url)` (exact) and the prune compares `r["url"] not in keep_urls` (exact) — no trailing-slash/case/whitespace normalization. A repointed seed URL that differs only by a trailing slash inserts a new row and prunes the old one + its items. This is *functionally the intended reconcile* (a repoint *should* prune the orphan — the NBC fix); a trailing-slash typo is just a benign repoint with a one-poll-cycle, re-fetchable-cache cost.
- **Suggested remediation (NOT applied):** optional — normalize URLs (rstrip `/`, lower host) before compare, or leave as-is and rely on seed correctness (CI's `test_shipped_seed_is_wellformed_https_and_unique` already guards the shipped seed). Lowest priority of the three.

---

## Correctly dismissed (NOT findings — with reasoning)

- **Web `schema_version` guard "not enforced" (claimed P2) → REFUTED.** The original claim (app.mjs silently renders on bad schema) admitted it never read app.mjs. The caller **does** enforce it: `app.mjs renderTicker()` does `if (!schema.ok) { …banner "client out of date — cards hidden"; no cards; return }` (`app.mjs:225-230`), with the Settings league pool also gated on `env._schema?.ok`. Tested in `web/test/render.test.mjs`. The guard is real and honest-degrades on web exactly as on the TV. **ARCH-1 is genuinely closed.**
- **Seeder data-loss "DELETE MORE than intended" (claimed P1) → REFUTED to SAFE.** See the definitive read above. Intended-reconcile (Scenario 2) + no-runtime-path/re-fetchable-cache (Scenario 1) = no P1.
- **Deploy `$REMOTE_PATH` "topology leak" in the recovery message (claimed P2) → REFUTED.** `deploy-helper.sh:77` already echoes `remote-path=$REMOTE_PATH` on **every** deploy; the value is the operator's own gitignored config (committed default is the generic `/srv/mymts-helper`); and the recovery line is a copy-paste command that *needs* the real path. The "timestamped directory structure" in the proof was fabricated (no such logic exists). No untrusted audience.
- **Duplicate profile slugs allowed (claimed P3) → NOT-A-FINDING.** A profile listing a slug twice yields a duplicate (still-valid) M3U entry; self-inflicted edit of the gitignored `PROFILES_FILE`, well-formed output, no impact. Arguably honest pass-through of operator intent.
- **Deploy `MYMTS_HELPER_REMOTE_PATH` fix (claimed P1) → NOT-A-FINDING (already-closed fix).** The analysis is correct and the historical outage *was* P1-class, but it's the fix itself, verified correct at HEAD. The only forward item is a P3 test-coverage gap (a compose-mount-resolution test).

---

## Clean bills (reviewed, no finding — stated for completeness)

- **M3U injection (the flagged "real risk") — DEFENDED.** `m3u.py _one_line` collapses the *full* line-break set via `" ".join(value.splitlines())` (CR/LF/VT/FF/NEL/U+2028/U+2029 — hardened during the HALF 2 review), `_attr` strips quotes, and `render_m3u` skips any `current_url` containing CR/LF. A channel label/URL cannot inject a fake `#EXTINF`/directive line. (EVIDENCE + regression tests `test_label_*`/`test_url_with_control_char_is_skipped`.)
- **No-proxy — HELD.** `render_m3u` emits `c.current_url` verbatim as the stream line; `api.py` filters `status=="live"` and adds no helper-hosted path. Confirmed live: 0 helper-hosted lines.
- **Profile-name route traversal — SAFE.** `/api/playlist/{name}.m3u` uses `name` only as a dict key (`profiles.get(name)` → 404 on miss); it never touches a filesystem path, so `../`-style names simply 404.
- **Deploy ROLLBACK path — FIXED (the architect's specific question).** The rollback re-uses the *same* `.env` the forward path wrote (now containing `MYMTS_HELPER_REMOTE_PATH`), `sed`-ing **only** `BUILD_SHA`; so the rollback's `docker compose up -d` resolves the cert/web mounts to the real deploy dir — the original double-failure (both forward AND rollback hit the empty `/srv` dir) is genuinely closed, not just the forward path.
- **HALF 1 card dispatch — DEFENSIVE.** Unknown `card.kind` → `"generic"`, non-array `lines` → `[]`, every field `String(… ?? "")`, null entry → `{}`. Malformed structured data degrades, never crashes.
- **HALF 1 A1 / stored-XSS — HELD.** render.mjs returns only strings; app.mjs writes them via `node()`'s `textContent` (never `innerHTML`). A malicious RSS title/summary cannot script. localStorage prefs are all clamped/defaulted; `assignments` are map keys, never DOM-as-HTML.
- **New dependency (Playwright, Campaign 4) — DEV/CI-ONLY.** Pinned (`tools/screenshots/package.json` + committed `package-lock.json`); absent from `helper/pyproject.toml` and the helper Dockerfile — it is never in the runtime helper image. No new runtime egress/supply-chain surface.
- **Topology scrub — HELD.** No real LAN IPs/hostnames in the new committed code/docs/screenshots (web shots are demo data + public broadcast; the deploy script uses the `$REMOTE_PATH` var; `deploy.local.env` stays gitignored).

---

## For the architect — remediation priority (implement NONE this pass)

1. **F1 (P3):** broaden `load_profiles`'s `except` to honor its "always boots" contract (`except Exception` → default-only). Cheapest, closes a real-but-implausible boot-crash on a pathological profiles file.
2. **F2 (P3):** add `url.strip()` validation in the seeder — tightens the prune guard against a whitespace-only seed.
3. **F3 (P3, optional):** URL normalization before prune compare — lowest value; the shipped seed is CI-validated.

All three are *defensive completeness*, not active risks. The new surface introduced **no P0/P1/P2**, the destructive seeder prune is **safe**, and the emergency fixes (including the rollback path) are **correct**.
