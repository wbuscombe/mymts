# BACKLOG

> Ideas surfaced during build that are **deliberately not** part of v1 — logged here per the build prompt's anti-drift rule (§9). Adding something to this file is the *correct* answer when a good idea surfaces that isn't in scope; folding it into v1 is the wrong one.

For each entry: **What** (one line), **Why-not-now** (which Vision principle defers it), **Reconsider when** (the condition that would make it worth doing).

---

## Deferred from v1 (per `BUILD-PROMPT.md §9` and `04-TECHNICAL-APPROACH.md §5`)

| What | Why-not-now | Reconsider when |
|---|---|---|
| Multi-box fleet management | Vision §6: single-box, operator-only is v1 scope | A second box is actually running MyMTS |
| Desktop companion app | Vision §6 ranks desktop as nice-to-have; trading it bulletproofs the TV path | Operator explicitly asks |
| Non-Onn platforms (other Android TV boxes, Fire TV, Apple TV) | Portability is a *design discipline*, not a feature in v1 | Operator is actually re-homing |
| Actual friend-sharing functionality | "Someday-maybe" in Vision §5; the security model is built for it but the feature is not | Operator decides to share |
| Map / GDELT geocoded event layer | Out of scope: Vision §3 — "not a general-purpose OSINT terminal" | Vision changes |
| Aircraft (ADS-B), ships (AIS), weather radar | Same as above | Vision changes |
| Prediction markets (Kalshi) | Same as above | Vision changes |
| LLM feed classification | v2 per `04-TECHNICAL-APPROACH.md`; v1 uses keyword rules | The keyword classifier proves insufficient |
| Accounts / multi-user | Vision §3 — "not a product with users" | Never, per current Vision |
| Richer in-app article reading | Build prompt §4 — safe excerpt is the v1 answer; cross-device handoff was a forbidden dead-end | A safe in-app reading mechanism is designed |
| Sports in the ticker | Markets-only in v1; ticker designed to accept additional modes later without rework | Markets ticker is shipping and stable |
| Sports ticker logos | Same as above | When sports ships |
| NCAA leagues | When sports ships, start with the 6 cleanest-data leagues | When sports ships |
| Full Prometheus metrics endpoint | v1 ships JSON metrics; Prometheus is v1.x | claude-status-bot needs it |

---

## Stage 1.x — confirmatory 4K-panel soak

**What:** Re-run the tile-budget soak with the Onn box routed to a 4K-capable display.
**Why-not-now:** The Stage 1 gate-clearing long soak runs against `.182` whose attached panel is 1280×720. Decode load is panel-agnostic so the leak number transfers, but final-stage downscale + Graphics surface composition at 4K is unverified.
**Reconsider when:** Either `.182` is moved to a 4K panel, or `.158` (or another Onn box) is connected to one for a short confirmatory run. Before shipping the default to a box driving a 4K production TV.

## Ideas that surfaced during build (add as you find them)

```
# Use this format:
#
# ## <slug>
# **What:** one line
# **Why-not-now:** which Vision principle defers it
# **Reconsider when:** the condition that would make it worth doing
```
