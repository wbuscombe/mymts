# 02 — StreamPlayer state machine (Trust Bar C3 fix)

> **Status:** Final. Stage 2 Part B closeout. The C3 violation Stage 1
> demonstrated three times is now fixed at the player layer. The
> capacity re-soak in Part C is unblocked by this finding.

The Stage 1 player reported `state=LIVE` while the surface received zero
frames for 10+ hours on four of six tiles in v3 — a direct Trust Bar
**C3** violation (*staleness is never silent*). The fix has to derive
liveness from actual frame arrival, not from ExoPlayer's
`playWhenReady`/`playbackState` (ExoPlayer happily reports `STATE_READY`
while the decoder is starved).

## The state machine

```
                  onFrameRendered
                       ▲
                       │
           ┌───────────┴───────────┐
           │                       │
        CONNECTING ─── tick (age > 30s) ──▶ STALE
           │                                  │
           │                                  │ tick (backoff[n] elapsed)
           │                                  ▼
           │                              RECOVERING
           │                                  │
           │                                  │ tick (15s window)
           │                                  ▼
           │                          ┌─ STALE (n+1)
           │                          │
           │                          │
           │              n>=3 strikes│
           │                          ▼
           └────────── frame ────▶  LIVE
                                       │
                                       │ tick (age > 15s)
                                       ▼
                                     STALE → RECOVERING → … → DEAD
                                                              ▲
                                                              │
                              after 3 failed strikes ─────────┘
                              (release decoder, anti-loop)
```

### States

- **CONNECTING** — initial. The first frame has not been rendered yet.
- **LIVE** — frames have been arriving within the staleness threshold. Trust Bar C3 holds: the surface is honest.
- **STALE** — last frame is older than the threshold (or, in CONNECTING, no first frame ever). Tile is **not** labelled LIVE. Recovery ladder is armed.
- **RECOVERING** — a recovery strike is in flight; we wait one staleness window to see if the strike produced frames.
- **DEAD** — all recovery attempts exhausted. The tile shows an honest dead indicator and consumes no further CPU (Trust Bar C2: *a dead feed is a non-event*).
- **OFFLINE** — caller-only sentinel: the player was explicitly released (e.g. on Activity destroy). The state-machine itself never transitions into this.

### Frame-arrival signals (fed into the tracker)

Both fire on positive signals; either is enough.

- `Player.Listener.onRenderedFirstFrame` — initial render + variant switches.
- `AnalyticsListener.onDroppedVideoFrames` — any callback (even `droppedFrames=0`) means the decoder is alive.

### Chosen values + rationale

| Value | Chosen | Reasoning |
|---|---|---|
| **staleThresholdMs** | **15 s** | Covers a normal HLS buffer drain (Stage 1 buffer is min=1.5 s / max=4 s) plus a brief network hiccup. Tight enough to be honest within a noticeable window. 30 s would be conservative-safer; 15 s honors C3 more aggressively. (See "dw-news-en" below for the trade-off this surfaced.) |
| **connectingThresholdMs** | **30 s** | Longer than `staleThresholdMs` because a live HLS initial buffer + manifest fetch can take 10–15 s on the Onn box; we don't want to false-positive a slow start. Stage 1 v3 evidence: unreachable URLs hung in `RECONNECTING` for 10 hours. This timeout is what brings the recovery ladder online for the never-connected case. |
| **maxRecoveryAttempts** | **3** | Three strikes (PREPARE, then two REINITs). dw-news-en's v3 evidence suggested some streams recover after one reset cycle; three attempts gives a couple of cycles' headroom before declaring DEAD. More than three burns CPU + decoder slots on the constrained Onn box without adding signal. |
| **backoffMs** | **[2 000, 8 000, 30 000]** | Exponential with a soft cap. Strike 1 fires 2 s after STALE (give a brief blip a chance to self-resolve). Strike 3 only after 30 s of unsuccessful prior strikes. **Anti-loop discipline** (Trust Bar C2): a legitimately-offline stream settles into DEAD within ~115 s of going stale, not thrashing recovery on a 2 GB box. |
| **tickIntervalMs** | **2 000** | Fast enough to catch a 15 s threshold violation inside the threshold window, slow enough that 6 concurrent tickers cost essentially nothing. |

## Demonstrated against real workloads on `.182`

Two tests on the Onn box (2 GB Amlogic, Android 14), helper at `<LAN_IP>:8091` serving real channel URLs:

### Test 1 — helper-resolved redbull-tv (75 s solo)

```
id=redbull-tv | tile_ready=10 | dropped=0 | errors=0 | state=LIVE | playing=true
```

The state machine produced **one** transition: `CONNECTING → LIVE`. No spurious `STALE`. The healthy stream is labelled `LIVE` with frame ages well under threshold throughout.

### Test 2 — unreachable URL (`https://httpbin.org/status/404`, 150 s)

Full lifecycle captured in `MYMTS_SOAK` events:

```
+31 s   STATE CONNECTING → STALE     (connecting timeout exceeded)
+33 s   STATE STALE → RECOVERING     RECOVERY attempt=1 kind=prepare
+49 s   STATE RECOVERING → STALE     (strike failed)
+57 s   STATE STALE → RECOVERING     RECOVERY attempt=2 kind=reinit
+73 s   STATE RECOVERING → STALE
+103 s  STATE STALE → RECOVERING     RECOVERY attempt=3 kind=reinit
+119 s  STATE RECOVERING → DEAD      DEAD attempts=3
```

Final beat: `state=DEAD playing=false`. Anti-loop holds: after `DEAD`, no further events ever fire for this tile.

**This is the Stage 1 bug fixed.** The same nasa-public-0 / moctobpltc-eight-2 URLs that left tiles in `RECONNECTING` for 10 hours in v3 would now settle into honest `DEAD` within ~120 s.

## B.2 — dw-news-en pathological-callback investigation

Stage 1 v3 reported dw-news-en emitting ~341k `onRenderedFirstFrame` callbacks over 11.5 h — ~8.2 / s sustained, suggesting pathological rebuffer/variant-switch thrash.

### Stage 2 measurement (180 s solo on `.182`)

| metric | value |
|---|---|
| `TILE_READY` events | 22 |
| effective callback rate | **0.12 / s** |
| `RECOVERY` strikes | 0 |
| `DEAD` events | 0 |
| `ERROR` events | 0 |
| `DROPPED` frame events | 0 |

The callback rate **dropped ~70× from the Stage 1 measurement**. Possible explanations:

1. **Different load profile.** Stage 1 measured dw-news-en under 6-tile concurrent load with WyzeGrid contending for foreground. Stage 2 measured it solo with WyzeGrid disabled. The pathological rate may be a 6-tile-contention effect, not an inherent stream property. Part C will re-measure under real 6-tile load.
2. **Stream-side change.** Upstream packaging can shift between measurements. Without longitudinal data we can't separate (1) from (2).

### Secondary finding — the state machine surfaces this stream's burst pattern honestly

Of the 21 STATE transitions over 180 s, **all** are `LIVE↔STALE` oscillations: dw-news-en goes ~16 s between frame events, then produces a burst that returns `lastFrameAtMs` to fresh. Each `STALE` returns to `LIVE` within ~75 ms — well before strike 1's 2 s backoff fires.

The state machine handles this correctly:

- The `STALE` state is *transient* (median ~75 ms in dw-news-en's case), because the recovery ladder only commits to a strike *after the backoff elapses*. Bursty streams self-resolve without an artificial restart.
- The state report is *honest* — during the 16 s gap, the tile is **not** labelled LIVE, even though ExoPlayer reports STATE_READY. That's C3 holding.

### Buffer-sizing trade-off (deferred to Part C decision)

The current `StreamPlayer.LoadControl` is:

```
min=1500ms, max=4000ms, playback=500ms, playbackAfterRebuffer=1500ms
target=4MB, backBuffer=0
```

For a stream like dw-news-en that naturally has 10–15 s gaps between frame events, the 1.5 s minimum buffer is at the edge. Loosening it to 3–4 s might smooth the burst pattern. But that change interacts with **memory per tile** — a higher floor uses more RAM, which directly affects the **tile-count ceiling** Part C measures.

**Per the Part B prompt:** flag the linkage; **do not silently change buffer sizing before Part C.** Part C will measure under real 6-tile load and decide whether the LIVE↔STALE oscillation is a measurement artifact (then ignore) or a memory/CPU drain worth tuning (then loosen buffer and re-measure).

## Anti-loop verification

The unit-test suite (`app/src/test/java/com/mymts/LivenessTrackerTest.kt`, 17 tests) covers:

- `deadIsAbsorbing_noFurtherStrikes` — once DEAD, ticks return `NONE` forever.
- `deadIgnoresLateArrivingFrames` — a late frame does not silently revive a DEAD tile (the decoder has been released; a future explicit user re-init handles revival).
- `threeStrikesThenDead` — the exact ladder shape (PREPARE → REINIT → REINIT → SETTLE_DEAD) is asserted.
- `connectingToDeadIfStreamNeverProducesAFrame` — the never-connected case completes the same ladder.

Plus 13 more covering threshold boundaries, recovery resets, configurable thresholds, and the `STALE → LIVE` mid-recovery success path.

## What this finding does NOT decide

- **The buffer-floor change.** Deferred to Part C per the prompt.
- **`dw-news-en`'s fitness as a Part C fixture.** The 70× callback-rate drop is encouraging but isn't conclusive — Part C will re-measure under real load. Treat dw-news-en's Stage 1 numbers as suspect until Part C says otherwise.
- **Long-uptime behavior of `RECOVERING`/`DEAD`.** Tested up to 150 s. Part C's multi-hour soak validates that DEAD tiles stay DEAD and that `LIVE` tiles don't accumulate state-machine debt.
