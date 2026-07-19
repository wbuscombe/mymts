# MyMTS — Technical Approach (The How)

> **Status**: Locked decision record. This is the first "how" document. Unlike the three foundation docs, this one names technology — but every choice here is justified by tracing it back to `01-VISION.md`, `02-TRUST-BAR.md`, or `03-OPERATIONAL-BAR.md`. If a choice can't be traced, it doesn't belong.
> **Relationship to the foundation docs**: Those define *what must be true*. This defines *how we make it true*. When this conflicts with them, this is wrong.
> **Authority**: Engineer (Claude). The operator signed off on the single load-bearing decision (native vs. web); the rest is engineering owned here.

---

## 1. The load-bearing decision: native Android TV app, not a web app

### Decision
MyMTS is a **native Android TV application** targeting the Onn 4K box, built in the same proven lineage as the a sibling TV app project: **Kotlin + Jetpack Compose for TV + Media3 (ExoPlayer)**. It is paired with a **minimal NAS-side helper service** that does only the things the TV should not do itself (aggregating news sources, resolving live-stream addresses). News is rendered as native text; video plays in the native player; **no browser engine is in the critical path**.

### Why (traced to the foundation)

**Trust Bar A0 (never a path into the home network) + worst-outcome #1 (breach).**
A browser kiosk is a hostile execution environment: it runs untrusted remote content, it shares a cookie jar across the operator's subdomains, and securing it requires fighting the platform. A native app:
- Has no shared cookie jar — a problem in MyMTS cannot become credentials for the operator's other systems (directly satisfies Trust Bar **A5**, the "session is not a skeleton key" principle, at the architecture level rather than via fragile mitigation).
- Renders feed content as text and plays video in a real media player — there is no engine executing hostile scripts delivered by a news source (satisfies **A1**, hostile-input assumption, structurally).
- Isolates naturally from the rest of the network (satisfies **A2/A4**, blast-radius containment and no-ambient-authority).

This is the crux: nearly every severe issue raised during the prior web-app review rounds existed *because* of the browser-kiosk-over-tunnel architecture. Native does not patch those issues; it removes the architecture that created them.

**Operational Bar B1 (updates must never brick the wall) + B2 (always a way back).**
Native updates are signed installs with the prior version recoverable — a far more trustworthy update story than browser service-workers that can cascade a bad bundle across screens. The proven a sibling TV app deploy pattern already embodies this.

**Worst-outcome #2 (harm to a shared friend) + the someday-share principle.**
A signed app a friend sideloads on *their own* hardware, pointed at *their own* helper and sources, is a cleaner and safer sharing model than granting a friend access through the operator's tunnel. It keeps instances isolated by construction (serves **A3**, "designed as if untrusted people will touch it").

**Vision (TV is the point; desktop is nice-to-have).**
Native weakens the browser-desktop path. The operator explicitly ranked the TV as the point and desktop as a nice-to-have, so this trades the least-valued capability to bulletproof the most-valued outcome. A desktop companion, if ever wanted, is a small separate effort — not a reason to make the core a browser kiosk.

**Evidence it works.**
a sibling TV app is the existence proof: same household, same Onn 4K (2GB RAM) hardware, same stack, already shipping stable at a small memory footprint with native D-pad support and multiple simultaneous video streams. MyMTS is not a speculative architecture; it is a second app in a validated pattern.

### What this decision costs (stated honestly)
- The "view it in a browser on my desktop" path is not free anymore. Accepted: it was the least-valued capability.
- There is a NAS-side helper to build and maintain. Accepted: it is minimal, it follows the operator's established Docker/NAS standards, and it exists specifically so the TV doesn't do the fragile, dangerous work (web fetching, stream resolution) itself — which serves the Trust Bar.

### Addendum (2026-06-06) — the sanctioned *separate* web client, and what stays forbidden
The decision above forbids a **browser mode that shares origin/session** with the operator's other `*.<DOMAIN>` services (the cross-service path). It does NOT forbid a **separate** web client that consumes the same helper API as a dumb client — that path was always sanctioned. A **LAN-only, credential-free, origin-isolated** web client now exists (`web/`, served by the helper at `/app` on its bare LAN address — off the <DOMAIN> domain, not tunneled, not behind Cloudflare Access). It holds no credentials and shares no cookie jar, so the browser's same-origin policy enforces the isolation; the A1 closed door (no in-browser article reading) still applies. This is a faithful *extension* of the decision, not a reversal. A **remote-accessible** web client (a genuinely separate public origin with its own threat-model + auth tradeoff) remains a deliberate, un-built future decision — see `docs/BACKLOG.md`.

---

## 2. The shape of the system

Two pieces, clear responsibilities, clean boundary.

### Piece 1 — The TV app (the wall)
The native Android TV application. Owns:
- The ambient video wall and everything the operator sees and touches.
- The news feed pane, rendered as native text.
- The ticker.
- All operator interaction: arranging the wall, the lineup, presets, settings — all via D-pad, TV-native.
- Playing live video in the native media player.
- Local persistence of the operator's content and configuration on-device.

It does **not** fetch arbitrary web content, execute remote code, or resolve live-stream addresses itself. It talks only to its own helper and to the video sources it's been given.

### Piece 2 — The helper (the quiet back-of-house)
A minimal NAS-side service. Owns only what the TV must not:
- **Aggregating news.** It pulls from the operator's chosen sources, on the operator's schedule, and serves the TV a clean, already-vetted feed. The TV never touches a raw news source directly. This concentrates all hostile-input handling in one controlled, sandboxed place (Trust Bar A1, A2) instead of on the appliance in the living room.
- **Resolving live-stream addresses.** Where a video source's playable address must be discovered or refreshed, the helper does it, in isolation, behind strict outbound limits (Trust Bar A4, and the Vision's reality that some sources are best-effort). The TV receives a ready-to-play address, never the machinery that produced it.
- Nothing else. The helper is deliberately small. It is not a general backend; it is a shield that does the two dangerous jobs the TV shouldn't.

### The boundary between them
- The TV trusts the helper; the helper trusts nothing from the outside (Trust Bar A1).
- The helper has the minimal authority to do its two jobs and no more (Trust Bar A4); a compromise of the helper cannot reach the operator's other systems (A2).
- The operator's *content* (lineup, sources, presets) lives where the operator changes it instantly and on-device, never requiring the helper to be redeployed (Operational Bar B4, Vision's one-click-easy content). The helper is told what sources to pull; it is not the thing the operator edits a file in.

---

## 3. How the foundation requirements are met (mechanism map)

This maps each load-bearing principle to the mechanism that satisfies it. It is the engineer's checklist; it is not exhaustive of implementation detail.

| Principle | Mechanism in this architecture |
|---|---|
| A0/A1 hostile input | No browser engine on the TV; feed content rendered as text; all source-fetching isolated in the helper |
| A2 blast-radius containment | Two-piece split; helper isolated on the NAS with minimal reach; TV app sandboxed by the OS |
| A3 built for untrusted hands | Sharing model is "your own app on your own hardware," isolated by construction |
| A4 no ambient authority | Helper limited to its two jobs with strict outbound limits; TV app requests only OS permissions it needs |
| A5 session ≠ skeleton key | No shared web session/cookie jar exists; the prior #1 web risk is architecturally absent |
| A6 authenticated, intentional actions | Operator actions originate on the device via the remote; no cross-site action surface exists |
| A7 secrets stay secret | Any credentials live with the helper on the operator's infra, never on-screen or in logs, never shipped in the app; established secret-handling standards apply |
| A8 observable, fail-safe boundary | Helper health is observable; when a source or resolution fails, the system denies/degrades, never silently permits |
| A9 recoverable compromise | App is reinstallable to known-good; helper is redeployable; operator data is restorable from backup |
| B1–B5 privacy | No third-party telemetry; nothing about the operator's viewing leaves their infra; shared instances are isolated, not surveilled |
| C1 data durable | Operator content persisted on-device and backed up; survives reinstalls and updates |
| C2 dead feed is a non-event | Native player handles per-stream failure as an isolated, quiet gap; deliberately not over-engineered |
| C3 staleness never silent | The wall visibly distinguishes current from frozen; the helper reports its own freshness |
| C4 long uptime | Native app built for sustained operation; the a sibling TV app pattern of keeping players alive and disciplined memory use applies |
| C5/C6 recovery & graceful failure | Self-heals where it can, surfaces what it can't, fails in soft pieces, never into an unsafe state |
| Op A1–A3 delivery | Reproducible build + install path; designed cold-start; clean inviting first-run |
| Op B1–B5 updates | Signed installs, prior version recoverable, visible/verifiable updates, content edits off the deploy path, no silent fleet cascade |
| Op C1–C7 maintenance | Self-tending; alerts the operator through a channel they see; health legible; automated safe upkeep; backups from day one; extensible by the operator |

---

## 4. Portability posture (for the operator's "other hardware eventually")

The operator will eventually re-home this onto dedicated and possibly non-Onn hardware. To honor that without paying for it now:
- The TV app targets the Onn 4K **now**, but its design avoids hard dependencies on anything specific to that one box, so re-homing to similar hardware later is a port, not a rewrite.
- The helper is standard NAS-side infrastructure following the operator's established patterns, so it moves with the operator's infra freely.
- The clean two-piece boundary means either piece can be re-homed independently.
This is a *design discipline*, not a feature: we build for today's target and simply avoid choices that would trap us on it.

---

## 5. What is explicitly deferred (and why that's safe now)

Everything not required to satisfy the foundation docs for a single-box, operator-only, ambient TV wall is deferred. In particular: multi-box fleet management, the desktop companion, non-Onn platforms, and any actual sharing-to-friends functionality. These are deferred because the Vision ranks them as someday-maybe and the foundation docs are fully satisfiable without them. Crucially, deferring them is *safe* because the architecture was chosen so that adding them later does not require revisiting the security model — the isolation that protects a shared friend is the same isolation already built for the operator.

---

## 6. The path from here

1. This document + the three foundation docs are the complete "what + why."
2. The next artifact is the **Claude Code build prompt**: the concrete, staged engineering plan that implements this architecture to the foundation's bar, following all of the operator's standing standards. That prompt is where specific versions, project structure, test strategy, and staged milestones live.
3. The operator is not pulled back in for engineering decisions. The operator is consulted only if a genuine *vision* ambiguity surfaces — never to adjudicate a "how."
