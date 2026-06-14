# MAINTENANCE-CHARTER.md — the continuous-quality platform

This charter accretes the continuous-quality checks that our audits and reviews kept
having to find **after the fact**. Its job is to turn each caught-too-late problem into
a **can't-slip-in-again** check — so the same class of gap doesn't recur. It is built to
**grow**: every future audit that finds a new gap class adds a check here.

It is the **index + the continuous-checks layer**. The deep specs live elsewhere and are
referenced, not duplicated:
- [`AGENTS.md`](AGENTS.md) — the protected invariants, the never-without-approval list, the deploy invariant.
- `professionalize.md` (the workspace-level template) — the cross-project professionalization standards.
- `adversarial_ai_app_review_prompts.md` (workspace) — the adversarial-review discipline (EVIDENCE/INFERRED/SPECULATIVE, proportionality).

---

## The two layers

Quality checks fall into two kinds, and you want **both**:

- **`[ENFORCED]`** — mechanized in CI; **cannot be skipped or forgotten**. A grep, a test, a gate.
  The build fails if violated. Use this whenever a check can be made mechanical.
- **`[RITUAL]`** — a judgment check an agent/operator **actively runs on a trigger** (phase end),
  captured in [`docs/PHASE-END-CHECKLIST.md`](docs/PHASE-END-CHECKLIST.md). Use this when the check
  needs judgment a machine can't make yet.

**Why both — the background-adb lesson.** "Never background `adb`" was a rule written in a doc, and it
still got violated repeatedly (multi-hour device hangs) until the discipline was hardened. A rule a
human is *supposed to remember* is weaker than a check a machine *enforces*. So: **mechanize what you
can (`[ENFORCED]`); ritualize what you can't (`[RITUAL]`) — and promote RITUAL→ENFORCED whenever
someone figures out how to mechanize it.** That promotion path is what makes this a platform, not a
static list.

---

## Check registry

Every check, tagged, with the **real finding it traces to** (no hypothetical bloat — each check exists
because something actually slipped).

| Check | Layer | Traces to (the real gap) |
|---|---|---|
| **Docs leakage** — no topology (full IP/MAC/abs-home-path) or personal-config (location/instance/possessive) in public docs | `[ENFORCED]` — `tools/docs-hygiene/check.mjs`, CI blocking gate, with `allowlist.txt` + inline `docs-hygiene:allow` markers | the 2026-06-12 topology scrub + the personal-config audit ("the office Onn", "running ambient in the room") |
| **Audience-aware docs (write-time)** — new public copy describes the project generically, not the operator's deployment | `[RITUAL]` — checklist §3 (backstopped by the ENFORCED gate for mechanizable tokens; the ritual catches subtler framing) | same scrub/audit; the grep can't catch tone (a caption can read as a personal deployment with no single flagged token) |
| **Helper tests + phantom zero-egress** | `[ENFORCED]` — `ci.yml` (`uv run pytest`, phantom smoke) | the original test gate |
| **Cross-component `schema_version` consistency** | `[ENFORCED]` — `ci.yml` (`scripts/check_schema_consistency.py`) | ARCH-1 (web/native/helper wire-contract drift) |
| **adb deploy-invariant** | `[ENFORCED]` — `ci.yml` (`scripts/test_adb_invariant.sh`) + `[RITUAL]` checklist §6 | DEPLOY-2 (scripts not matching the push→byte-verify→pm-install invariant; wrong-box device; health-gate false-rollback) |
| **Web client tests** (render/cards/schema-guard) | `[ENFORCED]` — `ci.yml` (`node --test`) | HALF 1 parity correctness |
| **Screenshot/gallery freshness** | `[RITUAL]` — checklist §1 | stale gallery captions; the phase-end-screenshot workspace standard |
| **Docs-vs-reality** | `[RITUAL]` — checklist §2 | "2 channels"→21, imaginary-CI→planned→live, a crash-looping README quickstart, stale counts |
| **Destructive ops have explicit guards + tests** | `[RITUAL]` — checklist §4 | the seeder reconcile-and-PRUNE (safe only because the API happened to be GET-only — incidental, not guarded) |
| **Fail-safe contracts exhaustively test the bad-input class** | `[RITUAL]` — checklist §5 | `load_profiles` missing `RecursionError`; truthiness-vs-`.strip()` slipping a whitespace URL past a guard |
| **Lint (ruff)** | `[ENFORCED, advisory]` — `ci.yml`, non-blocking | known pre-existing debt; new code kept clean (NOT the same as the blocking docs-hygiene gate) |

---

## How to grow this charter (the point)

When an audit/review (or an incident) finds a **new class of gap**:

1. **Add a row** to the registry above. Name the check, and **trace it to the finding** that motivated
   it (so the registry stays grounded in real problems, not speculative bloat).
2. **Tag it `[ENFORCED]` or `[RITUAL]`:**
   - If it can be made mechanical now (a grep, a test, a script) → `[ENFORCED]`: build the check, wire
     it into `ci.yml` as a **blocking** gate (advisory only if it's clearing pre-existing debt like
     ruff), and give the check its own self-test so its logic is regression-guarded.
   - If it needs judgment → `[RITUAL]`: add a concrete, verifiable item to `docs/PHASE-END-CHECKLIST.md`
     (an action + how to confirm it — never a vague principle).
3. **For a `[RITUAL]` check, note the promotion path:** could it *later* be mechanized? (e.g. parts of
   "docs-vs-reality" — stale counts, dead links — could become a grep; "destructive ops have tests"
   could become a coverage assertion.) Write down what would make it enforceable. **RITUAL→ENFORCED is
   the goal** whenever a judgment check becomes mechanical.

### Building an `[ENFORCED]` check — the rules that keep it trustworthy
- **It MUST have a working exemption mechanism (allowlist).** A check with no way to say "this match is
  intentional" false-positives on the deliberate keeps and gets disabled — a crying-wolf check is
  worse than none. docs-hygiene has two: a global `allowlist.txt` (literal keeps, with a reason each)
  and an inline `docs-hygiene:allow` marker (one-off lines). Pre-populate it with the known keeps.
- **It MUST pass clean on the current tree** before it's wired in as blocking — tune patterns +
  allowlist to **zero false positives** first.
- **It MUST have its own test** (planted-violation fails / clean passes / keeps not flagged), so a
  later change to the check can't silently break it.
- **Blocking vs advisory:** a check that catches **new** problems should **block** (docs-hygiene). A
  check clearing **pre-existing debt** can be **advisory** until the debt is paid (ruff).

---

## Feedback loop

This charter is revised by what actually catches things vs. what's noise:
- A check that **false-positives** gets its pattern/allowlist tuned — or demoted — promptly (crying
  wolf erodes the whole platform).
- A gap that **recurs** despite a `[RITUAL]` check gets **promoted/hardened** toward `[ENFORCED]` (the
  background-adb lesson: if a rule keeps getting violated, mechanize it).
- New checks are **proportional** — homelab/solo blast radius caps severity; don't scaffold
  enterprise gates the project doesn't need.

> **Status:** MyMTS is the **reference implementation** of this platform. Once proven, the pattern is
> intended to promote to a workspace-level standard (in `professionalize.md`).
