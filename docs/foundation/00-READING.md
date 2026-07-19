# 00 — Reading (Anti-Drift Anchor)

> **Status**: Locked. Written by the engineer (Claude Code) as proof of having internalized the four foundation docs before any code was written.
> **Purpose**: Anti-drift. This project previously drifted by letting engineering decisions accumulate until the *what* was buried under the *how*. This document is the engineer's single-paragraph-per-doc restatement, in own words, plus the precedence order. Re-read it at every stage gate.
> **Authority**: The engineer owns this restatement; the operator owns the source docs. If the engineer's paraphrase conflicts with the source, the source wins.

---

## 01 — Vision (the What)

MyMTS is an **ambient news video wall** that lives on the TV — calm, glanceable, *on* by default, asking nothing of anyone in the room. It exists because monitor-the-situation.com can't be the operator's own — the operator wants their channels, the operator's rules, no third-party nags. It is a personal instrument, not a product; it might be shared with a friend one day but it has no users, no accounts, no growth motion. The five fixed priorities, ranked worst-first, are: **(1) breach — never become a path into the home network**; **(2) harm to a shared friend — even before sharing exists, the security model assumes untrusted hands will eventually touch it**; **(3) data loss — operator's lineup/presets/sources are durable**; **(4) silent staleness — the wall must distinguish "current and calm" from "frozen and pretending"**; **(5) a visible dead tile — the least bad and deliberately under-engineered**. The core tradeoff is resolved as **bulletproof core, one-click-easy content**: changing the operator's *own* channels and sources stays effortless on the couch; everything beneath that stays locked down.

## 02 — Trust Bar (security, privacy, stability — the principles)

The Trust Bar is the operationalization of Vision §6's worst-outcome ranking, expressed as nine security principles (A0–A9), five privacy principles (B1–B5), and seven stability principles (C0–C6) — all derived, never invented. The prime directive (A0) is that MyMTS **must never become a path into the home network**, and everything else exists in service of it: hostile-input assumption everywhere (A1), blast-radius containment so a compromise of one piece never reaches another (A2), built-for-untrusted-hands from day one (A3), no ambient authority — least power for every component (A4), the operator's session is never a skeleton key to anything beyond MyMTS (A5), all state-changing actions are authenticated and intentional (A6), secrets stay secret end-to-end including in logs and errors (A7), the boundary is observable and fails safe — never silently permissive (A8), and every compromise is recoverable remotely without physical heroics (A9). Privacy: the operator's behavior is theirs alone (B1), shared guests get the same privacy (B2), retention is minimal by default (B3), no convenience feature is allowed to become a leak (B4), all records live on operator infra (B5). Stability is **explicitly ranked, not absolute** (C0): durable data (C1), graceful dead-feed handling (C2 — under-invested on purpose), never-silent staleness (C3), long-uptime resilience (C4), self-healing where possible and visible where not (C5), graceful failure that never becomes a security failure (C6). The doc deliberately names **no technology** — those are downstream engineering choices that must satisfy these principles by any means.

## 03 — Operational Bar (delivery, deployment, maintenance — the outcomes)

The Operational Bar covers everything between "fresh box" and "still running healthily months later." Delivery (A1–A3): from nothing to on-the-wall is short, documented, repeatable; cold-start is a designed unattended-friendly experience; first-run shows a clean inviting starting state, never empty-and-broken. Deployment/updates (B1–B5): an update **must never brick the wall** (B1) — the update mechanism is the most-trustworthy thing in the system because everything rides on it; there is **always a way back** (B2) without physical intervention; updates are deliberate and observable (B3) — the operator can see what version is running and whether a deploy succeeded; the operator's **content changes never ride the deployment path** (B4) — content edits are instant on-device acts that never require redeploying or editing a file; a bad update **cannot silently cascade** (B5) across whatever screens exist. Maintenance (C1–C7): the system tends itself unattended (C1); when it needs a human it asks clearly through a channel the operator actually sees (C2); health is legible at a glance without deep investigation (C3); routine upkeep (cleaning, backing up, resetting, renewing) is automated and never becomes the source of the failures it's meant to prevent (C4); the operator's accumulated work has a recent restorable copy from day one (C5); external dependencies (live streams, news sources, the internet itself) are treated as unreliable and bounded — never hammered, never silently failed (C6); the system is maintainable by the operator over time, not an opaque black box (C7). Like the Trust Bar, this doc names **no specific pipeline, tool, or technology** — only required outcomes.

## 04 — Technical Approach (the How)

The single locked load-bearing decision: **MyMTS is a native Android TV application** (Kotlin + Jetpack Compose for TV + Media3/ExoPlayer, a sibling TV app's proven lineage on the same Onn 4K hardware), paired with a **minimal NAS-side helper** that does only the two jobs the TV must not do itself — aggregating news sources and resolving live-stream addresses. The crux: this architecture **does not patch** the issues that filled the prior web-app review rounds — it **removes the architecture that created them**. No browser engine on the TV means no shared cookie jar (A5 satisfied structurally), no engine executing hostile remote scripts (A1 satisfied structurally), no cross-subdomain blast radius (A2/A4 satisfied structurally). Native signed installs with the prior version recoverable replace the brittle service-worker update path that v1.0/v1.1 wrestled with (Op B1/B2). A friend's sideloaded app on their own hardware is a cleaner sharing model than tunnel-granted access (worst-outcome #2). The TV does the wall; the helper concentrates all hostile-input handling in one sandboxed back-of-house place; operator content lives on-device and changes instantly, never via the helper. Portability is a **design discipline, not a feature**: build for the Onn 4K today, avoid choices that trap us on it. Everything not required for "single-box, operator-only, ambient TV wall" is deferred — and deferring is safe because the security model is identical whether one person or many ever use it.

---

## Precedence order (memorize)

When any of these tension against each other, this is the order. Top wins.

1. **Security (Trust Bar Part A: A0–A9).** Nothing justifies increasing breach risk. *(Worst-outcome #1.)*
2. **Shared-friend safety.** Wins over the operator's own convenience and the operator's own data. *(Worst-outcome #2 ranked above #3 by the operator.)*
3. **Data durability (C1).** Wins over everything below. *(Worst-outcome #3.)*
4. **Honest staleness (C3).** Wins over visual polish and over per-feed reliability. *(Worst-outcome #4 above #5.)*
5. **Feed-level resilience (C2).** Lowest priority; explicitly under-invested. A dead tile is forgivable. *(Worst-outcome #5.)*

## Cross-cutting (inviolable)

**One-click-easy content.** None of the above protections may make changing the operator's own channels and sources require friction — no file edits, no redeploys. Security is built *beneath* the operator's content, never *around* it. If a security mechanism would gate routine content changes behind engineering friction, that mechanism must be redesigned to achieve the same safety some other way. **The requirement is to achieve the safety, not to relax it.**

## Anti-drift rules I am holding myself to

- No engineering decision expands scope beyond what these four docs require. "Could theoretically happen" is not justification.
- At every stage gate, I re-read the relevant foundation sections and verify I'm still serving them.
- I resolve "how" tradeoffs myself; I pull the operator in only on genuine **vision** ambiguity that the foundation docs cannot resolve.
- Conservative, incremental, recoverable, observable: when forking between two options, the more recoverable one wins by default.
- Effort is allocated to the worst-outcome ranking. Effort spent on dead-tile heroics is effort stolen from the trust boundary.

## What this document is NOT

It is not a substitute for re-reading the source docs. It is a check-in artifact — if the engineer's paraphrase and the source disagree, the source is right and this file gets corrected. Update it when the foundation docs are updated, not the other way around.
