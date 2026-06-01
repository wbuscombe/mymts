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

## Stage 2 / production — `StreamPlayer.state` must be frame-age-aware

**What:** Make `StreamPlayer.state` reflect "the surface is actually rendering" rather than just ExoPlayer's `playWhenReady`/`playbackState`. Right now a player can report `state=LIVE` while `lastFrameAtMs` is many minutes old (observed in the first long soak: three Mux/Unified tiles all reported `LIVE` while `last_frame_age_ms ≈ 5,000,000`, i.e. ~84 min since the last frame).
**Why-not-now:** Stage 1 is the budget gate, not a code-quality stage. Recording it here so it doesn't get lost.
**Why it matters:** This is a direct Trust Bar **C3** violation ("staleness is never silent"). The wall would show a frozen tile with a "LIVE" label, which is exactly the surface-honesty failure C3 forbids.
**Fix sketch:** add a `STALE` state derived from `now() - lastFrameAtMs > THRESHOLD` regardless of ExoPlayer's reported state; surface it to the same heartbeat + UI badge as a dead tile. Pick a threshold that's tight enough to be honest (~10–30 s for live HLS) but loose enough to absorb ordinary buffering.
**Reconsider when:** Stage 2 (the helper does real upstream work, so the wall starts displaying actual content) or earlier if another soak's results get distorted by stale-but-LIVE tiles.

## Production deployment — app-vs-app foreground conflict on the Onn box

**What:** WyzeGrid runs a persistent `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` watchdog (`SYSTEM_ALLOW_LISTED`) that reclaims the foreground from any other TV app within ~80 min. The first gate-clearing soak failed because of this — MyMTS lost the foreground, was backgrounded, and Android then evicted it under memory pressure on the 2 GB box.
**Why-not-now:** Stage 1 only needs to measure the box; for the soak window we side-step the conflict by `pm disable-user com.wyzegrid` on `.182` and re-enable after. That doesn't generalize to production.
**What this means for production:** if MyMTS is ever installed on a box that also runs WyzeGrid (or any other foreground-service kiosk app), they will contend. MyMTS will need its own foreground service + watchdog story for the long-uptime kiosk role (which Stage 6 was already going to land).
**Reconsider when:** Stage 6 lands the long-uptime watchdog + boot-receiver story. At that point, design for coexistence (or assert "one kiosk app per box, MyMTS is the kiosk").

## Ideas that surfaced during build (add as you find them)

```
# Use this format:
#
# ## <slug>
# **What:** one line
# **Why-not-now:** which Vision principle defers it
# **Reconsider when:** the condition that would make it worth doing
```
