# Stage 2 — Plan

> **Status: COMPLETE (2026-06-02).** A, B, and C all done.
>
> - ✅ **Part A — Helper** (commit `2808c29`). RSS aggregation + stream-address resolution, hardened deploy on the NAS at `<LAN_IP>:8091`. Trust Bar A1/A2/A4 enforced at the boundary; C3 honored via `/api/channels` masking `current_url → null` when not live.
> - ✅ **Part B — Player robustness** (commit `aabd7e1`). Frame-age-aware `LivenessTracker` + recovery ladder + anti-loop. C3 violation Stage 1 demonstrated three times is now fixed at the player layer. Demonstrated against the helper's real resolved streams.
> - ✅ **Part C — Capacity probe** (commits `5e22886` / `3c101c7` / `93f89a2` / this closeout). **Sustainable ceiling: N=4** on the Onn 4K (Amlogic S905Y4) — bracket-evidenced + 5.13h-LIVE-confirmed under verified per-tile telemetry. Closed with the network-event caveat documented in `docs/findings/01-onn4k-tile-budget.md`; full evidence + rationale recorded there.
>
> Stage 3 (the real wall UI) is unblocked but a separate instruction.

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

## ✅ B (DONE 2026-06-01). Player robustness — DONE

Full details + on-device evidence in `docs/findings/02-player-state-machine.md`. Summary below.

## ~~B. Player robustness — REQUIRED, not deferred~~

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

### What B delivered (DONE 2026-06-01)

- **`LivenessTracker`** — pure Kotlin state machine with frame-age-aware liveness, recovery ladder (PREPARE → REINIT × 2), and DEAD anti-loop. 17 unit tests. Configurable thresholds; chosen values + reasoning in `docs/findings/02-player-state-machine.md`.
- **`StreamPlayer` rewrite** — wired to the tracker, handler-based 2 s tick, frame signals from both `onRenderedFirstFrame` and `onDroppedVideoFrames`. On-device demonstration (`.182`, helper at `<LAN_IP>:8091`):
    - Real helper-resolved stream (`redbull-tv`, 75 s) → stayed `LIVE`; one transition `CONNECTING → LIVE`; no spurious `STALE`.
    - Unreachable URL (`httpbin.org/status/404`, 150 s) → `CONNECTING → STALE → PREPARE → STALE → REINIT → STALE → REINIT → DEAD` in ~120 s. After `DEAD`, no further events ever fire for the tile. Anti-loop holds.
- **B.2 dw-news-en** — solo 180 s on `.182`: **0.12 callbacks/s** (down ~70× from Stage 1's 8.2/s under 6-tile contention). 21 STATE transitions, all `LIVE↔STALE` oscillations that self-resolve within ~75 ms (no real recovery strike fires). Real load test in Part C will decide whether the Stage 1 pathological rate was a contention effect; the suggested buffer-floor tuning is deferred to Part C per the Part B prompt.
- **Telemetry** — new `EV=STATE`/`EV=RECOVERY`/`EV=DEAD` event types; `scripts/parse-soak-log.py` updated to count and timeline these per-tile.
- **C3 contract now enforced at both ends.** Helper masks `current_url → null` when not live (Part A); player surfaces `STALE`/`DEAD` honestly (Part B). The wall cannot show a `LIVE` badge over a frozen surface.

---

## ✅ C — Capacity probe (CLOSED 2026-06-02, Path 2: bracket + 5.13h-LIVE + external Akamai event at h5.13)

Bracket result + method + dw-news-en answer + buffer decision are in `docs/findings/01-onn4k-tile-budget.md §"Stage 2 Part C"`.

Highlights:
- **Sustainable ceiling on this device (Onn 4K / Amlogic S905Y4) = N=4.** N=5 degrades dw-news-en tiles to ~37% drop rate; N=6 begins firing recovery PREPARE strikes.
- `MYMTS_DEFAULT_MAX_TILES = 4` in `gradle.properties`, with the bracket evidence embedded as a comment.
- dw-news-en's Stage 1 8.2/s callback rate WAS a contention effect (solo + healthy N≤4: 0.13/s).
- Buffer floor kept at Stage 1 values; rationale in the finding doc.
- The escalating-probe procedure is committed as `scripts/probe-tile-count.sh` + recipe in the finding doc — that's the portability deliverable.

### Long-soak status (2026-06-02) — **CLOSED Path 2** (close on 5.13h + network-event evidence)

- Attempt 1 (`long-soak-4t-20260601-2100`): INVALIDATED by a harness bug (one-shot end-of-run logcat dump lost everything to ring-buffer wrap). Fix landed in `3c101c7`.
- Attempt 2 (`long-soak-4t-v2-20260602-0857`): 5.13 h of clean N=4 LIVE evidence (state machine honest, PSS slope `−9.53 KB/min` in mature steady state), then a synchronized external network event hit both Akamai CDN origins simultaneously at h5.13. State machine + recovery ladder + anti-loop ran exactly per design; all 4 tiles settled into honest `DEAD` within 13 s of each other. Last ~47 min: tiles `DEAD` (no thrashing, no leak).
- **Decision (2026-06-02):** close on the 5.13h + network-event evidence. The failure mode at h5.13 is unambiguously external (synchronized across two unrelated CDN origins inside a 12-second window — capacity failures are staggered and load-correlated, not synchronized). The state machine succeeded at its hardest job (honest graceful degradation under real-world failure). The gate criterion's *intent* — "sustain 4 tiles without leaking or degrading from the box's own limits" — is met. Re-running risks the identical outcome (network blips are not schedulable). Full rationale + evidence in `docs/findings/01-onn4k-tile-budget.md §"Stage 2 closeout"`.

WyzeGrid was re-enabled on `.182` at the close.

## ~~C. Capacity re-soak (this is what completes the Stage 1 gate)~~

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
