# Stage 2 — Plan

> **Status (this doc):** Stage 2 entry list. Operationalized when the Stage 2 prompt arrives — this is not the prompt, it's the punch list the prompt will draw from. Keep it short and exact.

For *why* Stage 2 exists and what it owns, see `docs/foundation/04-TECHNICAL-APPROACH.md §2 "Piece 2"`. Two non-negotiable items below are graduated out of `docs/BACKLOG.md` because Stage 1 proved they are blockers, not "nice to have"s.

---

## A. The helper — real news aggregation + real stream resolution

This is what the helper exists to do. Per `04-TECHNICAL-APPROACH.md` and `02-TRUST-BAR.md A1/A2/A4`:

- News aggregation that defensively parses upstream RSS/Atom/JSON and serves the TV a clean, pre-vetted, structured feed. Hostile-input handling is concentrated here, sandboxed.
- Live-stream-address resolution in isolation, behind strict egress, fail-closed.
- `/health` schema_version stays pinned; freshness fields per upstream added now.
- Helper-side persistence (sqlite WAL is fine) for resolved-URL cache + dedupe.
- Stage 2 deploy onto the NAS using the operator's standard layout (compose, non-root, pinned image by digest, no docker.sock, never the unrelated host container).

**Why this also unblocks the capacity measurement** (the Stage 1 gate): the soak's fixture pool is currently a hand-rolled list of test streams (mostly VOD-as-live) because there is no source of real, sustained live URLs. Once the helper resolves real channels, the soak runs against real load, not VOD that falls off the live window after 5 minutes.

---

## B. Player robustness — REQUIRED, not deferred

This was logged in `BACKLOG.md` as a "Stage 2 / production" item. Stage 1 promoted it to required because it appeared in all three soaks and is the direct reason the 6-tile capacity number could not be measured. It is also a direct violation of a *locked* foundation principle.

### B.1 — `StreamPlayer.state` must be frame-age-aware (Trust Bar C3)

> Trust Bar **C3**: "Staleness is never silent."

**The bug, demonstrated three times in Stage 1:** when ExoPlayer enters `STATE_READY` but the surface stops receiving frames (BEHIND_LIVE_WINDOW + non-recovery, network hiccup + non-recovery, decoder back-pressure, etc.), `StreamPlayer.state` continues to report `LIVE` because that field is derived from ExoPlayer's `playWhenReady`/`playbackState`, not from actual frame arrival. In v3 four of six tiles reported `state=LIVE` while `last_frame_age_ms` was 10+ hours.

**Required behavior:**

- **Detection** — liveness is derived from actual frame arrival, not solely ExoPlayer's reported playback state. Concretely: `state=LIVE` requires `last_frame_age_ms < THRESHOLD` (suggested initial threshold 10–30 s for live HLS, tuned during implementation). Any value above the threshold transitions to a new **`STALE`** state, regardless of what ExoPlayer reports.
- **Action** — on `STALE`, attempt genuine recovery: `prepare()` retry, then full re-init of the player on a second strike. If recovery fails after N attempts, surface an honest stale/dead indicator on the tile — **never** a `LIVE` badge over a frozen surface.
- **Heartbeat instrumentation** — `MYMTS_SOAK` beats already carry `playing`, `pos_ms`, and `last_frame_age_ms`. The `STALE` state lands on the same heartbeat so the harness sees it. Soak parser updated to count `STALE` separately from `LIVE`.
- **Tests** — unit test the state derivation: given (`state_ready`, `play_when_ready=true`, `last_frame_age_ms=60_000`), expected state is `STALE`. Integration test that a forced surface-pause produces `STALE` within the threshold window.

This is the single most impactful change downstream of Stage 1. It unblocks the capacity measurement *and* fixes the user-facing version of the same bug ("a frozen tile with a LIVE badge" is the C3 failure shape).

### B.2 — `dw-news-en` pathological-callback investigation

v3's dw-news-en tile emitted **341,634 `onRenderedFirstFrame` callbacks in 11.5 h** — sustained ~8.2/s. The tile was actively rendering (other signals confirmed it) but it was clearly rebuffering or variant-switching at very high frequency.

Plausible causes:

- Live HLS player tuned for low-buffer (Stage 1 `StreamPlayer` uses min=1.5 s / max=4 s / playback=0.5 s) being too tight for this upstream's variance.
- Adaptive-bitrate switching thrash on a CPU-bound box.
- An upstream peculiarity (DW packaging segment timings).

**Required:** characterize before declaring `dw-news-en` a stable fixture. Reproduce with verbose `AnalyticsListener` capture (load, dropped, decoder-counters); decide whether to (a) loosen the buffer floor, (b) keep the fixture and document the cost, or (c) replace it. Until characterized, treat its Stage 1 numbers as suspect when reasoning about the capacity re-soak's fixture pool.

---

## C. Capacity re-soak (this is what completes the Stage 1 gate)

Once A and B.1 are in place:

1. Helper resolves a set of real, sustained live news streams (operator-curated, but resolved by the helper, not hand-coded into `SoakFixtures.LIVE`).
2. App with the `STALE`-aware `StreamPlayer` recovers stalled tiles within the threshold window or surfaces them honestly.
3. Re-run `scripts/soak.sh` against `.182` at 6 tiles for 4–8 hours minimum.
4. Append a new section to `docs/findings/01-onn4k-tile-budget.md` with the result. The configurable default in `gradle.properties` either stays 6 (measured-safe) or moves to whatever holds.

The gate criteria stay as written in the existing finding doc; the difference now is the workload will be real.

---

## D. Production-deployment concerns (not blockers; record now so they're not lost)

- **App-vs-app foreground conflict.** WyzeGrid's persistent foreground-service watchdog reclaimed the foreground from MyMTS in Stage 1's first soak. Any Onn box that runs both apps will contend on a 2 GB device. MyMTS's own foreground/kiosk + watchdog story lands in **Stage 6** (operational hardening), per the build prompt; designed-for-coexistence-or-not is a decision then.
- **Confirmatory 4K-panel soak.** Stage 1 ran against `.182`'s 1280×720 panel. Per the finding doc, decode load is panel-agnostic so the slope transfers, but the Graphics-layer composition at 4K isn't independently measured. A short confirmatory soak on a 4K-attached display lands before the default ships to a production-grade TV.

Both already in `docs/BACKLOG.md`; mentioned here as cross-references.

---

## What is NOT in Stage 2

- The on-device lineup/preset/settings work (Stage 5).
- The signed-install update + rollback story (Stage 6).
- The first-run wizard (Stage 5).
- Anything in `docs/foundation/04-TECHNICAL-APPROACH.md §5` ("explicitly deferred").

## Sequencing — **A → B → C** (corrected by Stage 2 prompt)

This doc originally proposed sequencing the player fix (B.1) ahead of the helper (A) because B.1 is what unblocks the capacity re-soak. **The Stage 2 prompt overrode that ordering and the correct sequence is A → B → C.** Two reasons recorded here so the rationale lives with the plan:

1. **Helper-first is what the architecture says.** The helper is the project's security boundary — the shield that does the two dangerous jobs the TV must not (`04-TECHNICAL-APPROACH.md §2 "Piece 2"`). Sequencing the player fix ahead of it because the player fix unblocks one soak inverts what-serves-how: the *measurement* is downstream of the *shield*, not the other way around.
2. **B.1 should be tested against the real streams the helper resolves, not the VOD-as-live fixtures from Stage 1.** Recovery logic exercised against streams that were always going to die after a few minutes is a weak test — it confirms recovery in conditions the production wall will not actually see. Test B.1's STALE-detection and re-init behavior against the helper's real resolved-channel stream, which is what runs in production.

So Stage 2 is **A → B → C**. Items in D stay here as cross-references; they belong to Stages 6/7.
