# Stage 3 checkpoint A — video grid on the TV

**Device:** `<LAN_IP>:5555` (Onn 4K Streaming Box, Amlogic S905Y4)
**APK:** `app-debug.apk` built from working tree at `d513093` + Stage 3 grid edits
**Helper:** `http://<LAN_IP>:8091` — 2 channels live (`dw-news-en`, `redbull-tv`)
**Launched:** 2026-06-02 20:03:53 PDT
**Screenshot:** `wall-4tile-2x2-live.png` (taken at ~20:05)

## What's working
- 4 tile slots in a 2×2 grid, cycled from the 2 live helper channels: `[dw, redbull, dw, redbull]`.
- All 4 tiles reached `LIVE` by `20:04:28` — first connection took longer than the 15 s stale threshold, so each tile went `CONNECTING → STALE → RECOVERING (PREPARE) → LIVE` per the Stage 2 state machine. That's the recovery ladder honestly catching a slow first connect; subsequent steady state is stable.
- Channel labels visible bottom-left on a low-contrast black pill; no `LIVE` badge clutters the picture (the picture being there *is* the LIVE signal — per Trust Bar's "calm and glanceable" + the `tile_state_map` in the Stage 3 re-anchor brief).
- Dark background, tile gaps minimal, layout 16:9 per tile — matches the dense newsroom theme target.

## Honest constraints recorded
1. **Channels: real but limited.** Helper resolves 2 channels live on this network path right now (DW News English, Red Bull TV). 5 other seeded channels fail to resolve (DNS / network path). The grid fills its 4 tiles by cycling — this is the v2 long-soak shape — exactly as documented in `docs/findings/01-onn4k-tile-budget.md`. Expanding channel resolution is helper/network work, not a Stage 3 blocker.
2. **Feed: not yet on screen.** Checkpoint B will layer the feed pane in.
3. **Ticker: not yet on screen.** Checkpoint B will layer the ticker in (with clearly-labeled sample/placeholder market data, never faked as real).

## What's NOT on the screen yet (intentional — these are checkpoint B work)
- Feed pane (left side)
- Ticker bar (top)
- The full assembled wall layout

## WyzeGrid status during this checkpoint
- Not disabled (this was a short visual confirmation, not a sustained soak).
- WyzeGrid's `WatchdogService` was foreground when this build was deployed; MyMTS took the foreground via `am start` and held it for the duration of the checkpoint. No conflict observed for this brief window.
