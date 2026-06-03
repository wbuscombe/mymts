# Stage 3 fix-forward — video startup regression fixed

**Device:** `<LAN_IP>:5555` (Onn 4K Streaming Box)
**Helper:** `<LAN_IP>:8091`, 17 channels seeded; 3 currently live (`dw-news-en`, `redbull-tv`, `nasa-tv`)
**Launched:** 2026-06-03 16:58 CDT
**Screenshot:** `wall-fixforward-tiles-live.png`

## What was wrong (the `b19b013` regression — telemetry-verified)

`VideoGrid.kt` cached the player-by-spec-id binding in a `remember(manager, specs) { … manager.player(idx) … }` block. That `remember` evaluates **during composition**, before the `DisposableEffect` that registers the manager as a lifecycle observer has run. So:

1. Composition pass: `manager._players` is empty → `manager.player(idx)` returns `null` for every idx → the materialised map is `{specId → null}` for every entry → `bound` is a list of `BoundTile`s with `player == null`.
2. Composition completes. `DisposableEffect` registers the lifecycle observer. Activity's onStart fires → `StreamPlayerManager.onStart` runs → players are constructed + `prepare()`'d → ExoPlayer initializes its decoder (so `EV=DECODER` fires).
3. But `WallTile`'s `PlayingTile` early-returns `DeadTile(slot.channel.label)` when `player == null` → no `StreamSurface` ever mounts → ExoPlayer has no surface → `onRenderedFirstFrame` cannot fire → no `EV=TILE_READY`.
4. LivenessTracker sees no frame for 15 s → STALE → recovery ladder → SETTLE_DEAD. Honest in form, wrong in cause.

The C2 dead-panel UI made this **visually identical** to a genuine network failure.

## What the fix is

1. **`StreamPlayerManager`** now exposes `val readyVersion: State<Int>` — a Compose-observable signal backed by `mutableIntStateOf(0)`, incremented inside `onStart` *after* players are created (and again inside `onDestroy` so observers can collapse cleanly).
2. **`VideoGrid`** now reads `manager.readyVersion.value` (subscribing the composable to its changes) and includes it in the `remember(slots, manager, readyVersion) { bindTiles(…) }` key list. When `onStart` flips the version, Compose invalidates the cached binding, the lookup re-runs with the now-populated manager, and the bound tiles carry real players. `StreamSurface` mounts, surface attaches, video renders, `EV=TILE_READY` fires.
3. The structural honesty rule (`BoundTile.init { require(player.specId == slot.spec.id) }` + `bindTiles` identity-pairing) is **unchanged** — the regression was in *when/how* the binding was wired, not in the rule itself.

## Telemetry — the proof

| Signal | Before (`b19b013`) | After (this commit) |
|---|---|---|
| `EV=TILE_READY` over ~90 s | **0** | **4** (one per slot, within 60 s of launch) |
| `EV=DECODER` | 12 (3 strikes × 4 tiles) | 4 (one per slot, no recovery thrashing) |
| `EV=DEAD` | 4 (all tiles settled DEAD) | **0** |
| LIVE state transitions | 0 | 7 (4 initial + 3 bursty self-resolutions) |
| Visual outcome | 4 quiet C2 dead panels | 4 live video tiles (verified by screencap) |

Stream URLs and network were identical across both runs — both Akamai origins returned `HTTP 200` from the dev Mac and pinged from `.182` at ~12–36 ms RTT with 0% loss. The variable was the code.

## Regression tests added

- **`StreamPlayerManagerReadinessTest`** (4 cases) — direct probe of the readiness mechanism. Verifies `readyVersion == 0` and `player(idx) == null` before `onStart`; verifies `readyVersion` increments and players are populated by the factory after `onStart`; verifies `onStart` is idempotent; verifies `onDestroy` releases + bumps. **These tests would not compile against `b19b013`** because the `readyVersion` property does not exist there — the strongest form of "fails on broken / passes on fix."
- **`VideoGridBindingTest`** (3 cases) — documents the broken vs fixed Compose-`remember` patterns by simulating cache semantics in plain Kotlin. The "buggy wiring without readyVersion key" test demonstrates that the regression's pattern caches nulls forever; the "fixed wiring keyed on readyVersion" test demonstrates that the fix invalidates correctly when readiness flips.

These join the existing 4 `BoundTileTest` cases (which guarded the honesty rule but did not catch this wiring bug — they pass a fully-populated player map, exercising correctness *assuming* players exist).

## Meta-lesson — recorded so this trap can't catch a future stage

**Honest-degradation UI can mask a startup regression.** A `BoundTile` with `player == null` rendered the C2 quiet dead-panel — pixel-identical to a tile whose player legitimately settled DEAD after the recovery ladder ran. The C2 rule (graceful degradation, no error chrome) is correct; it just turns out to be the worst-case UI signal during a startup regression because it normalises "no video" as "honest about being offline."

The corollary: **after any change to the video-pipeline wiring, telemetry (`EV=TILE_READY`, `EV=DECODER`, state-machine transitions) is the verification standard — not visual inspection.** This stage's first verification was visual ("the wall looks right") and missed the regression entirely. The operator's directive to verify with telemetry (no display needed) is what surfaced it.

Recorded in `CHANGELOG.md` and `docs/findings/03-stage-3-wall.md` for permanent reference.

## Standing rules
- **unrelated host services untouched.**
- **WyzeGrid:** as-found on `.182`, foreground + `WatchdogService` healthy.
- App tests green: `StreamPlayerManagerReadinessTest` (4) + `VideoGridBindingTest` (3) added on top of prior suite (28+ cases total).
- Helper untouched in this commit (the bug was TV-only).
