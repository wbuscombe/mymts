# Phase-End Checklist (the RITUAL layer)

> The judgment checks a grep can't make. An agent (or the operator) **actively works
> through this** when closing a phase — a completed campaign/feature-batch, a
> professionalization pass, or a release. It is the counterpart to the ENFORCED
> [docs-hygiene](../tools/docs-hygiene/) CI gate: that catches the mechanizable leak
> classes on every push; this catches the rest, which need a human/agent's judgment.
> Each item traces to a real gap an audit caught (see [`../MAINTENANCE-CHARTER.md`](../MAINTENANCE-CHARTER.md)).

Run it top-to-bottom. For each item: do the action, confirm the evidence. Skip an
item only if it genuinely doesn't apply this phase (and say so).

### 1. Screenshots — regenerate if user-visible UI changed
*(traces to: stale operator-specific gallery captions; the phase-end-screenshot workspace standard)*
- [ ] Did user-visible UI change this phase (wall / menu / feed / ticker / layout / a component)?
  If **no** → skip. If **yes** → re-run the automated capture (`tools/screenshots/`) against demo
  mode to refresh `docs/screenshots/web/`.
- [ ] Flag any **manual/hero shots** now stale (the live-data device shots a tool can't take) with
  their filename/spec for the operator — don't silently leave stale hero shots.
- **Verify:** the committed gallery matches the current UI; the README's screenshot refs still resolve.

### 2. Docs-vs-reality — the docs describe what actually shipped
*(traces to: "2 channels"→21, imaginary-CI→planned→live, a crash-looping README quickstart path, stale counts)*
- [ ] Spot-check README / ARCHITECTURE / CHANGELOG / feature claims against what this phase actually
  shipped: no stale counts, no "planned" that's now real (or "shipped" that isn't), no dead links,
  no claims for unshipped features.
- [ ] If the quickstart/commands changed, **run them** — a documented path that crash-loops is worse
  than no path.
- **Verify:** every claim you'd stake the project's credibility on is true today.

### 3. Audience-aware docs (write-time)
*(traces to: the 2026-06-12 topology scrub + the personal-config audit)*
- [ ] Any NEW public-facing copy written this phase (captions, README, docs) describes the **project
  generically**, not the operator's deployment — no room/location/box-instance framing, no personal
  paths, no real topology.
- **Verify:** the ENFORCED docs-hygiene check passes (it backstops this) — but you still eyeball the
  subtler framing the grep can't catch (a caption can read as a personal deployment even with no
  single flagged token).

### 4. Destructive ops — explicit guards + tests, not incidental safety
*(traces to: the seeder reconcile-and-PRUNE — safe only because the API happened to be GET-only)*
- [ ] Did this phase add/change an op that **deletes / overwrites / prunes** data?
  If **no** → skip. If **yes**:
- [ ] Does it have an **explicit guard** (e.g. the empty-seed no-wipe guard), not safety-by-architectural-accident?
- [ ] Is there a test proving the **guard fires** on the dangerous input, AND a test proving it
  **does not over-delete** on normal input (the "distinct stays distinct" kind)?
- **Verify:** name the guard and the two tests. "Safe because nothing currently calls it that way"
  is not a guard.

### 5. Fail-safe contracts — exhaustively test the bad-input class
*(traces to: `load_profiles` missing RecursionError; truthiness-vs-`.strip()` slipping a whitespace URL past a guard)*
- [ ] Did this phase add/change a loader / parser / boot-path that **claims to degrade gracefully /
  fail closed**? If **no** → skip. If **yes**:
- [ ] Is the **whole realistic bad-input class** tested — malformed, empty, huge, deeply-nested,
  wrong-encoding, whitespace-only — not just the happy path and one obvious failure?
- **Verify:** the "always boots / always fails closed" contract has a test for each realistic way the
  input can be bad, including the sibling-exception traps (`RecursionError` is not a `ValueError`).

### 6. Deploy conformance — match the AGENTS.md invariant
*(traces to: DEPLOY-2 scripts not matching the invariant; wrong-box placeholder device; health-gate false-rollback)*
- [ ] Did this phase touch a **deploy script / path**? If **no** → skip. If **yes**:
- [ ] It conforms to the [`AGENTS.md`](../AGENTS.md) adb invariant: `adb push` → on-device byte-verify →
  `pm install -r` → confirm `lastUpdateTime` (never a streamed install), explicit device (never a
  placeholder/wrong box), **one foreground adb op, never `kill-server`**, helper deploy rebuilds the
  image + verifies `build_sha`.
- **Verify:** the changed path still satisfies every clause; a deploy-resolution gap (like the `.env`
  cert-mount bug) would crash-loop silently.

### 7. Bank the work — close the loop
- [ ] Working tree clean; `main == origin`.
- [ ] CHANGELOG updated for every feature/fix this phase.
- [ ] CI green (all blocking gates, including docs-hygiene).
- [ ] Any review/triage/audit doc updated to **FIXED** for what was fixed, with the proving artifact
  (test / commit) named — don't leave a finding's status stale.
- **Verify:** a reader of the repo at HEAD sees an accurate, self-consistent picture.
