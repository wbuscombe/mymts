# Claude Code Build Prompt — MyMTS

> **You are building MyMTS, a native Android TV application plus a minimal NAS-side helper service.** This prompt is your engineering brief. Before you write any code, you must read the foundation documents that define what this project is and what must be true of it. They are non-negotiable; this prompt operationalizes them.

---

## 0. First actions (do these before anything else)

1. **Unpack the foundation docs.** The project root is `<REPO_ROOT>/`. Inside it is `files (2).zip`. Unpack it. It contains:
   - `01-VISION.md` — what MyMTS is. The fixed point.
   - `02-TRUST-BAR.md` — security/privacy/stability principles. Every one is ranked and traceable.
   - `03-OPERATIONAL-BAR.md` — delivery/deployment/maintenance outcomes.
   - `04-TECHNICAL-APPROACH.md` — the architecture decision (native Android TV + minimal helper) and the mechanism map.
   Read all four, in full, before proceeding. They are the source of truth. If anything in this prompt appears to conflict with them, the foundation docs win and you flag the conflict.
2. **Move the docs into the repo** as a permanent `docs/foundation/` set so they live with the project and are referenced in the README. Do not let the project drift from them.
3. **Confirm your understanding** by writing `docs/foundation/00-READING.md`: a one-paragraph-per-doc restatement in your own words, plus the precedence order (Security > shared-friend safety > data durability > honest staleness > feed resilience), so there is proof you internalized them. This is the anti-drift anchor.

---

## 1. What you are building (the short version)

A **native Android TV app** for the Onn 4K box — an ambient, glanceable wall of live news video that the operator occasionally takes control of via the remote — paired with a **minimal NAS-side helper** that does the two jobs the TV must not do itself: aggregating news sources and resolving live-stream addresses.

- Stack lineage is **WyzeGrid's**: Kotlin, Jetpack Compose for TV, Media3/ExoPlayer. Reuse what's proven there.
- The TV renders news as **native text** and plays video in the **native player**. No browser engine in the critical path. This is a security requirement, not a preference (Trust Bar A0/A1).
- Develop and debug against the **existing Onn 4K box now**; architect for **re-homing to fresh/other hardware later** (Technical Approach §4). Build for today's target; avoid choices that trap you on it.

---

## 2. Non-negotiable principles you are implementing

You are not just building features; you are meeting a bar. Hold these throughout:

- **Breach is the worst outcome.** The trust boundary is the primary investment, ahead of features and polish. No decision may increase the risk that this app becomes a path into the home network. (Trust Bar A0.)
- **Built for untrusted hands from day one**, even though sharing isn't a near-term feature — because harming a shared friend ranks above the operator's own data loss. (Trust Bar A3.)
- **A dead tile is forgivable; a breach is not.** Do NOT pour heroic effort into per-stream resilience. Graceful degradation, not perfect uptime. Spend that effort on the boundary and the data instead. (Trust Bar C0/C2, Vision §6.)
- **Bulletproof core, one-click-easy content.** The operator's routine content changes (lineup, sources, presets) are instant on-device actions that NEVER require redeploying the helper or editing a file. Security lives *beneath* the operator's content, not around it. (Vision §6, Operational Bar B4.)
- **Staleness is never silent.** The wall must visibly distinguish "current and calm" from "frozen and pretending." (Trust Bar C3.)
- **Updates never brick the wall; there is always a way back.** (Operational Bar B1/B2.)

When two principles tension, use the precedence order from `00-READING.md`.

---

## 3. Architecture you are implementing

Per `04-TECHNICAL-APPROACH.md §2`:

### The TV app (the wall)
Owns everything the operator sees and touches: the video wall, the native-text feed pane, the ticker, all D-pad interaction (arranging the wall, lineup, presets, settings), native video playback, and on-device persistence of the operator's content. It does **not** fetch raw web content, execute remote code, or resolve stream addresses itself. It talks only to its helper and plays the video addresses it's handed.

### The helper (back-of-house)
Minimal NAS-side service owning only the two dangerous jobs:
- **News aggregation** — pulls the operator's chosen sources on schedule, serves the TV a clean, already-vetted, ready-to-render feed. All hostile-input handling is concentrated here, sandboxed, never on the TV. (Trust Bar A1/A2.)
- **Stream-address resolution** — where a live source's playable address must be discovered or refreshed, the helper does it in isolation behind strict outbound limits, and hands the TV a ready-to-play address. (Trust Bar A4; Vision's best-effort-source reality.)
- Nothing else. It is a shield, not a general backend. Keep it small.

### The boundary
- TV trusts the helper; helper trusts nothing external. (A1.)
- Helper has minimal authority for its two jobs and no more; its compromise cannot reach the operator's other systems. (A2/A4.)
- Operator content lives on-device and changes instantly; the helper is *told* what sources to pull, it is not the thing the operator edits. (Operational Bar B4.)
- Follow all of the operator's standing NAS/Docker standards for the helper (see §7).

---

## 4. Feature scope for v1 (the ambient wall, operator-only, single box)

This is the complete v1 surface. Anything not here is deferred (see §9). All of it must meet the bar in §2.

**The wall**
- A grid of simultaneous live video tiles. Default grid sized to what the target hardware sustains — **establish this empirically as the very first engineering spike (see §6, Stage 1); do not assume a number.** The grid default must be a config value, not hard-coded.
- Tiles play live video in the native player. All tiles muted by default; the operator can unmute tiles individually (multiple allowed). Per-tile volume remembered on-device.
- A single failed tile degrades to a quiet, honest gap and recovers when it can — no crash, no cascade, no room-filling error. (C2.)
- The wall visibly indicates when a tile is stale/dead vs. live. (C3.)

**The feed pane (native text)**
- A toggleable pane showing a chronological list of news items the helper has aggregated, newest first.
- Rendered as native text. Each item: source, headline, time, and category/severity if the helper provides it.
- Filtering appropriate to a 10-foot D-pad UI (at minimum: by source, by recency). Keep it legible from the couch.
- Visibly indicates when the feed itself is stale (helper hasn't refreshed). (C3.)
- Opening an item on the TV shows whatever the helper safely provides (e.g., a text excerpt). Do **not** build a flow that requires the TV to fetch arbitrary web pages or that depends on a cross-device auth handoff — that path is forbidden (it was a prior dead-end; see Trust Bar B4 and the Technical Approach). A simple, safe on-screen excerpt is the v1 answer; richer reading is deferred.

**The ticker**
- A scrolling plain-text strip. v1 carries markets data; design it so an additional mode (e.g., sports) can be added later without rework. Plain text only.
- Visibly indicates stale/closed data rather than presenting stale as live. (C3.)

**The lineup, presets, and settings (all D-pad-native, all one-click-easy)**
- The operator picks which sources/channels fill the wall from a manageable on-screen picker. Adding a channel, swapping one, or adding a custom source is an **instant on-device action** — never a redeploy, never a file edit. (Vision §6, Operational Bar B4.)
- Presets: the operator can save the current arrangement as a named preset and switch between presets. Presets and all operator content persist on-device and are backed up. (C1, Operational Bar C5.)
- Settings follow the proven TV-native pattern (the WyzeGrid side-panel + focusable-rows + popup-picker approach). One coherent settings surface, D-pad-first.
- The operator can always tell, from within the app, whether things are healthy — feed freshness, tile health, helper reachability. (C3, Operational Bar C3.)

**First-run**
- A clean, inviting starting state — not a confusing blank, not errors. It makes obvious how to make the wall the operator's own. (Operational Bar A3.)
- Cold-start to a working, authenticated, on state is a designed experience, not an afterthought. (Operational Bar A2.)

---

## 5. The trust boundary — implement these concretely

This is the part that gets the most engineering care. Implement, and write tests for, at least:

- **No browser engine in the content path.** Feed content is text; video is the native player. Verify nothing renders untrusted remote markup in an executable context. (A1.)
- **Hostile-input handling concentrated in the helper.** The helper treats every fetched source as hostile: it parses defensively, never executes fetched content, strips anything dangerous before the TV ever sees it, and serves the TV only clean, structured, safe data. (A1/A2.)
- **Helper least-authority + strict egress.** The helper can reach only what its two jobs require, and nothing on the operator's internal network beyond what's strictly necessary. Outbound is bounded; it cannot be turned into a pivot. Stream-resolution runs isolated. Fail-closed: if the egress control can't do its job, deny, don't silently allow. (A2/A4/A8.)
- **Validate every externally-influenced input** the helper accepts (including any source/address the operator adds): structured validation, not loose pattern-matching; reject anything that resolves toward internal/loopback/private space; connect by resolved address with the integrity checks that prevent rebinding-style tricks. (A1/A4.)
- **Secrets stay secret.** No credentials shipped in the app. Any the helper needs live on the operator's infra per established secret-handling standards — never on-screen, never in logs, never in errors, never exposed to a component that doesn't need them, with a documented rotation path. (A7.)
- **Observable, fail-safe boundary.** The operator can see whether protections are in force; the app surfaces helper health and its own state truthfully. Protections that stop working are themselves a detectable, alertable condition. (A8.)
- **Recoverable.** The app is reinstallable to a known-good state; the helper is redeployable; operator data is restorable. No recovery requires physical heroics. (A9, Operational Bar B1/B2.)
- **No telemetry leaves the operator's infra.** Nothing about what the operator watches is reported anywhere outside their own systems. (Privacy B1.)

Document the full boundary, the threats it addresses, and residual risk in `docs/THREAT-MODEL.md`. Map each item back to the Trust Bar principle it satisfies.

---

## 6. Build in stages, with gates

Work incrementally with explicit rollback points (the operator's standing preference: conservative, incremental, explicit rollbacks). Each stage ends green — tests passing, committed, pushed — before the next begins.

**Stage 1 — Hardware-budget spike + skeleton (GATE).**
Before committing to any grid size or layout, empirically measure how many simultaneous live video tiles the target Onn 4K box sustains, and at what resolution, without OOM or degradation over a sustained run. This number gates the grid default and the picker layout. Produce a short written finding. Stand up the bare app skeleton and the bare helper skeleton with health-checks. **Do not proceed to Stage 2 until the budget is known and the grid default is set as a config value derived from it.** (Operational Bar §C; Technical Approach mechanism map C2/C4.)

**Stage 2 — The helper (the shield first).**
Build the minimal helper: news aggregation (defensive parsing, hostile-input handling, clean structured output) and stream-address resolution (isolated, egress-bounded, fail-closed). Health/freshness endpoints. This comes before the wall because the security boundary is the primary investment and the TV depends on the helper being trustworthy. Tests for the boundary behaviors in §5. Follow all NAS/Docker standards (§7).

**Stage 3 — The wall.**
The video grid with native playback, muted-by-default, individual unmute, per-tile volume, and graceful single-tile failure. Wire to the helper's resolved addresses. Prove the dead-tile-is-a-non-event behavior. Prove stale-vs-live is visible.

**Stage 4 — The feed pane + ticker.**
Native-text feed from the helper, D-pad-legible filtering, visible staleness. The plain-text ticker with markets, designed for later modes. Safe on-screen excerpt for items; no web-fetch/handoff path.

**Stage 5 — Lineup, presets, settings, first-run.**
The on-device picker (instant content changes), named presets, the TV-native settings surface, the health-at-a-glance view, and the designed first-run/cold-start experience. Prove content changes never touch the deploy path.

**Stage 6 — Operational hardening.**
Reproducible build + install path; signed-install update story with the prior version recoverable and no silent bricking; on-device data backup with restore; self-tending upkeep; operator alerting through a channel they actually see; legible health. Prove "always a way back."

**Stage 7 — Portability pass + docs.**
Audit for hard dependencies on the one specific box; document the re-homing path. Complete all documentation (§8). Final threat-model review against every Trust Bar principle.

At each stage gate, verify against the foundation docs, not just against this prompt.

---

## 7. Standing standards you must follow (the operator's established baseline)

Apply the operator's full project standards. In brief — read them as hard requirements:

- **Git/sessions:** Conventional commits (feat/fix/docs/chore/test/security). `git pull` before changes. Semver with tagged releases. Update README + CHANGELOG (Keep a Changelog format) + inline comments at session end; commit and push. No secrets, credentials, or absolute local paths beyond project root in history.
- **Security practices:** Secrets in gitignored config only; provide example templates. Never echo/print secrets to stdout — write directly to files, reference by variable name. `SECURITY-PRACTICES.md` required. Audit dependencies.
- **Docs:** README (setup, usage, config, architecture), `ARCHITECTURE.md`, `CONTRIBUTING.md`, `LICENSE` (MIT default), `.editorconfig`. Comments explain WHY not WHAT.
- **Testing:** TDD as default. Tests for critical paths and the trust-boundary behaviors at minimum. Single-command test run documented in README. No flaky or expected-to-fail tests. Required green before any release/tag.
- **DX:** Clone-to-running with clear instructions and config scaffolding from templates. Fail fast with actionable errors on missing config.
- **Helper (Docker/NAS) specifics:** Non-root container, pinned image versions (not `latest`), secrets injected at runtime never baked in, no secrets/tokens/absolute paths in any log or error output. Deploy script handles the full cycle (pull → rebuild → restart) — never assume a restart alone picks up changes.
- **Pre-commit:** Secret-scanning hooks where supported. Pinned dependencies with committed lock files.
- **CRITICAL — unrelated host services:** Never touch, modify, restart, or reconfigure any unrelated service or container sharing the helper's host unless explicitly told to. The helper stays isolated on its own network and must not disrupt anything else running on that host.
- **Standards for THIS prompt's deliverables:** Every Claude Code command sequence that needs project dependencies must activate the environment first. Run ALL tests before committing. Commit and push at session end. Update the CHANGELOG for any feature/fix. Verify the deploy pipeline handles the full cycle.
- **Onn/NAS environment facts to respect:** develop/debug against the known Onn box; the helper lives on the NAS following existing stack conventions (scripts, logs, docker stack locations as per the operator's standard layout); treat the operator's CIFS/mount and disk-health cautions as standing constraints for anything helper-side that touches the NAS filesystem.
- **Project integrations:** Wire operator-standard ops notifications (the operator's bot integration) and a pinned, contract-tested health endpoint the operator's monitoring can consume. Provide demo/offline mode per the operator's onboarding standard so the app and helper can run without live external dependencies for development and CI.

If any standard is unclear for a specific decision, prefer the more conservative, more recoverable, more secure option — that is always the right tiebreaker for this operator.

---

## 8. Documentation deliverables

Beyond the standing doc set, produce:
- `docs/foundation/` — the four foundation docs, plus your `00-READING.md`.
- `docs/THREAT-MODEL.md` — the trust boundary, threats, mitigations, residual risk, each mapped to a Trust Bar principle.
- `docs/ARCHITECTURE.md` — the two-piece system, the boundary, the data/control flows, the portability posture.
- `docs/OPERATIONS.md` — install/cold-start, the update-and-rollback story, backup/restore, upkeep, alerting, health-at-a-glance, recovery procedures.
- A README that orients a newcomer and links the above.

---

## 9. Explicitly out of scope for v1 (do not build; do not let scope creep toward them)

Multi-box fleet management; the desktop companion; non-Onn platforms; any actual friend-sharing functionality; maps/aircraft/ships/weather/prediction-markets; LLM feed classification; accounts/multi-user; richer in-app article reading beyond a safe excerpt; sports (the ticker is built to *accept* it later, but v1 ships markets only).

These are safe to defer because the foundation docs are fully satisfiable without them, and because the architecture was chosen so that adding them later does not require revisiting the security model. Log good ideas that surface into a `docs/BACKLOG.md` for a future version rather than folding them into v1.

---

## 10. How to work, and when to involve the operator

- You own the engineering. Resolve "how" questions yourself in service of the foundation docs.
- Do **not** pull the operator into engineering tradeoffs. The operator has been clear: their job was the vision and the risk posture (now locked in the foundation docs); your job is the how.
- Involve the operator **only** if a genuine *vision* ambiguity surfaces that the foundation docs cannot resolve — never to adjudicate a technical decision.
- Move conservatively and incrementally with explicit rollback points. Prefer the recoverable, secure option at every fork.
- At each stage gate, re-read the relevant foundation sections and verify you're still serving them. The prior failure of this project was drift; your discipline against drift is part of the deliverable.

Begin with Section 0.
