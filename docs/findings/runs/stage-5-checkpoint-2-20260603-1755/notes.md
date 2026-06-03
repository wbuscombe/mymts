# Stage 5 checkpoint 2 — channel picker + persistence verified

**Device:** `<LAN_IP>:5555` (Onn 4K)
**Helper:** `<LAN_IP>:8091`, 17 channels seeded; 3 live (`dw-news-en`, `nasa-tv`, `redbull-tv`)
**Method:** telemetry — the operator was away from the TV.

## What this checkpoint proves

### 1. Picker opens, navigates, assigns (visual + telemetry)
Driven via adb keyevents: `MENU → SELECT (open picker for slot 0) → RIGHT × 2 (cycle to redbull-tv) → SELECT (assign) → BACK (close menu)`.
- `picker-slot1-redbull-tv-live.png`: centered card showing "SLOT 1 / Red Bull TV / live" with green ‹ › cycle arrows and the gesture hint line "‹ cycle ›    SELECT to assign    BACK to cancel". The menu rows in the dimmed background already show real channel names: "DW News English · live", "Red Bull TV · live", … — the channel labels are wired through from helper state.

### 2. Reassignment fires `TILE_READY` for the NEW spec id

Before assign (baseline cycler `[dw, redbull, dw, redbull]`):
- slot 0 = `slot-0-dw-news-en` (TILE_READY at 17:55:29)

After assign (slot 0 pinned to `redbull-tv`):
- **`EV=DECODER|id=slot-0-redbull-tv|init_ms=495`** at 17:56:35
- **`EV=TILE_READY|id=slot-0-redbull-tv`** at 17:56:36

The new spec id means a new player was constructed, the surface attached, `prepare()` ran, and `onRenderedFirstFrame` actually fired. **The new stream started — not just a label swap.**

### 3. Persistence across force-stop and relaunch

Disk inspection between the assign and the relaunch:
```
$ adb shell run-as com.mymts cat /data/data/com.mymts/shared_prefs/mymts_lineup.xml
<map>
    <string name="lineup_overrides">[0,"redbull-tv"]</string>
</map>
```

After `am force-stop` + relaunch:
- `EV=TILE_READY|id=slot-0-redbull-tv` at 17:57:26 — restored from disk.
- `EV=TILE_READY|id=slot-1-dw-news-en` at 17:57:26.
- `EV=TILE_READY|id=slot-2-dw-news-en` at 17:57:33.
- `EV=TILE_READY|id=slot-3-dw-news-en` at 17:57:34.

Slot 0 came up as `redbull-tv` (the saved override), not as `dw-news-en` (the default cycler's first pick). **The operator's choice persisted.**

### 4. Offline assignment renders C2 honestly, no player ever starts

Manually pinned via direct prefs write: `slot 0 → cnn` (offline), `slot 1 → dw-news-en` (live). After relaunch:

Decoders started: `slot-1-dw-news-en`, `slot-2-redbull-tv`, `slot-3-redbull-tv`.
Decoders for `slot-0/offline-cnn` or `slot-0-cnn`: **none**.

Screenshot `wall-with-cnn-offline-slot0.png` shows the top-left tile as a dark C2 panel with "CNN" as the ghost label; the other three tiles play normally. `Slot.Offline(0, cnn)` has no `spec` field by construction — the type system rules out a mispaired player, the structural `require()` from `9d5b0ad` cannot misfire on an offline slot.

### 5. No capacity blowout

Total decoder inits across the test: ~10 across all slot lifetimes (4 baseline + 3 rebind + 3 post-restart). Zero recovery strikes. Zero DEAD events. The Stage 2 N=4 ceiling holds; the menu does not destabilise the wall.

## Standing rules
- **unrelated host services untouched.**
- **WyzeGrid** as-found on `.182`.
- App tests green; `TileSlotResolverOverridesTest` (7) + `LineupStoreCodecTest` (7) + Stage 5 carry-over (`MenuStateTest`, 8) all pass.
