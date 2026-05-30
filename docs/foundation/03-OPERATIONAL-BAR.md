# MyMTS — Delivery, Deployment & Maintenance (The Operational Bar)

> **Status**: Locked. These are required *outcomes* for how MyMTS comes to life, gets updated, and is kept alive over time. As with the Trust Bar, this document names no technology, no pipeline, no tool. It states what must be true operationally; the engineer chooses how.
> **Derivation**: Traces to the Vision (ambient, set-and-forget, TV-first, one-click-easy content, someday-shareable) and inherits the Trust Bar's precedence rules.
> **Precedence**: Security (Trust Bar Part A) outranks everything here. An operational convenience never justifies a security regression.

---

## Part A — Getting it running (Delivery)

### A1. From nothing to on-the-wall is simple and repeatable
Bringing MyMTS to life from scratch — on a fresh machine, on a new TV — is a short, documented, repeatable path. It does not require arcane knowledge, undocumented steps, or remembering how it was done last time. Anyone following the written path arrives at a working wall. The process is the same every time and produces the same result.

*(Traces to: Vision — the operator owns and controls this; it must be reproducible to be truly owned.)*

### A2. First-run is unattended-friendly
The journey from "powered on for the first time" to "calmly showing the wall" must not depend on the operator babysitting it through a fragile sequence. A new TV coming online finds its way to a working, authenticated, on state with minimal hand-holding. Cold-start is a designed experience, not an afterthought.

*(Traces to: Vision — ambient, TV-first; worst-outcome #5 — a broken first-boot in front of family.)*

### A3. The starting state is honest and inviting, not empty-and-broken
A freshly-installed MyMTS presents a clean, intentional starting point — not a confusing blank, not a wall of errors. The first thing the operator sees makes it obvious how to make it theirs.

*(Traces to: Vision — the operator's experience comes first.)*

---

## Part B — Changing it (Deployment & Updates)

### B1. Updating must never brick the wall
Pushing a new version is a routine, safe act. It must be impossible for a bad update to leave the wall dead with no way back. There is always a safe path to recover from a bad version — remotely, without physical intervention on the TVs. The update mechanism that the operator relies on must itself be the most trustworthy part of the system, because everything else rides on it.

*(Traces to: worst-outcomes #1, #3, #5; the Vision's trust requirement. This directly answers the prior failure where an auto-update mechanism could brick the fleet.)*

### B2. There is always a way back
Any change — an update, a configuration change, a content change gone wrong — has an undo. The system can always return to a recent known-good state. The operator is never one mistake away from an unrecoverable situation. This applies to the operator's data (Trust Bar C1) and to the running software alike.

*(Traces to: worst-outcomes #1, #3; Trust Bar A9, C1.)*

### B3. Updates are deliberate and observable
The operator knows what version is running, knows when an update happened, and can tell whether it succeeded. Updates do not happen invisibly in ways the operator can't see or verify. A failed update is a visible, recoverable condition — never a silent corruption.

*(Traces to: worst-outcome #4 — silent failure; Trust Bar A8, C3.)*

### B4. The operator's content changes do NOT ride the deployment path
Changing the lineup, adding a source, swapping a channel — the operator's routine content edits — are *not* deployments. They are instant, in-the-moment, one-click-easy actions that never require pushing a new version, editing a file, or any engineering friction. The deployment path is for the *software*; the operator's content lives above it and changes freely. This is the operational expression of the Vision's "bulletproof core, one-click-easy content."

*(Traces to: Vision — the core tradeoff; Trust Bar Part D's cross-cutting guarantee.)*

### B5. A bad update can be stopped before it spreads
If MyMTS ever runs on more than one screen, a bad version must not be able to take them all down at once before anyone notices. There is a way to halt or limit the spread of a change and to recover the whole set from a single point of control. No update can silently cascade into total fleet failure.

*(Traces to: worst-outcomes #1, #5; the someday-shareable principle.)*

---

## Part C — Keeping it alive (Maintenance)

### C1. It tends itself
The defining state is unattended. MyMTS must therefore keep itself healthy without routine operator intervention — recovering from the ordinary failures of long-running, internet-dependent software on its own. The operator's normal experience is that it just keeps working. Maintenance is something the system mostly does to itself.

*(Traces to: Vision — set-and-forget, ambient; Trust Bar C4, C5.)*

### C2. When it needs a human, it asks clearly
For the rare problems the system can't fix itself, it tells the operator plainly — what's wrong, and ideally what to do — rather than failing silently or cryptically. The operator is alerted to real problems through a channel they actually see, and is never left to discover failures by chance or by a guest pointing at the TV.

*(Traces to: worst-outcome #4; Trust Bar C3, C5.)*

### C3. Its health is legible at a glance
The operator can, at any time, get a truthful answer to "is it healthy?" without deep investigation. The system surfaces its own state — what's working, what isn't, whether it's current, whether its protections are in force. Trust requires visibility; an opaque system cannot be trusted to run unattended.

*(Traces to: Vision — trust; Trust Bar A8, C3, C5.)*

### C4. Routine upkeep is automated and safe
The recurring chores that keep it healthy over months — clearing out what's stale, backing up what matters, resetting what drifts, renewing what expires — happen automatically, on schedule, safely, and without ever putting the operator's data or the system's safety at risk. Upkeep never becomes a source of the very failures it's meant to prevent.

*(Traces to: worst-outcomes #1, #3; Trust Bar C1, C6.)*

### C5. The operator's accumulated work is protected against loss
Backing up the operator's content and configuration is not optional and not deferred — it is part of keeping the system alive from the start. There is always a recent, restorable copy of everything the operator built. (This is the maintenance-side commitment behind Trust Bar C1.)

*(Traces to: worst-outcome #3; Trust Bar C1.)*

### C6. Dependence on the outside world is acknowledged and bounded
MyMTS depends on things outside the operator's control — live streams that move and break, outside sources of news and data, the path to the internet itself. These dependencies are treated as inherently unreliable. The system is built knowing they will fail, behaves well when they do, respects their limits (never hammering them abusively), and degrades honestly rather than pretending. Where a dependency's failure is silent by nature, the system makes it visible.

*(Traces to: worst-outcomes #4, #5; Trust Bar C2, C3, C6; the Vision's YouTube-ToS-style reality that some sources are best-effort.)*

### C7. Maintainable by its operator, on purpose
Because this is the operator's owned instrument and possibly a long-lived one, it is built to be understood and extended by the operator over time — not an opaque black box that only its original author could touch. This serves the Vision's ownership principle without violating the "bulletproof core" tradeoff: the parts meant to be extended are approachable; the parts that must stay locked down stay locked down.

*(Traces to: Vision — ownership, the core tradeoff; Trust Bar Part D.)*

---

## Part D — How operational concerns resolve against each other

Precedence when these tension:

1. **Security (Trust Bar Part A)** — always wins. No deployment or maintenance convenience justifies a security regression. *(Worst-outcome #1.)*
2. **Recoverability (B1, B2, C5)** — the ability to get back to good state outranks the convenience of any given change. Never trade away the undo. *(Worst-outcomes #1, #3.)*
3. **Honest visibility (B3, C2, C3)** — knowing the true state outranks a smoother-but-opaquer experience. *(Worst-outcome #4.)*
4. **Self-tending automation (C1, C4)** — the system doing upkeep itself outranks operator convenience features, but never outranks safety or recoverability.
5. **Feed/dependency resilience (C6)** — lowest; degrade gracefully rather than engineer heroically. *(Worst-outcome #5.)*

Cross-cutting and inviolable: **the operator's content changes never ride the deployment path (B4).** No operational mechanism may push routine content edits into anything resembling a deploy, a file edit, or engineering friction.

---

## Part E — What this doc deliberately does NOT say

It names no pipeline, no update technology, no backup tool, no monitoring system, no hosting mechanism, no scheduling system. It does not say *how* recovery works, *how* updates are halted, *how* health is surfaced, or *how* upkeep is scheduled. Those are for the engineer to choose such that these outcomes are met. If a better mechanism achieves these outcomes, it may replace an older one without this document changing. The outcomes are permanent; the means are not.
