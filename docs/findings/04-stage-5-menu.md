# Finding 04 — Stage 5: in-app menu + channel/lineup control

> **Status: CLOSED 2026-06-03.** The wall now opens a WyzeGrid-style left side menu on the D-pad MENU key (or LEFT when closed); the operator picks a slot, a centered TV-style picker cycles through the helper's channels with honest live/offline marking, SELECT assigns, BACK cancels. The chosen lineup persists on-device and restores on relaunch. **Telemetry verified end-to-end** (operator was away from the TV during verification — the Stage 3 lesson holds: a tile that fails to start looks identical to an honest dead tile, so appearance proves nothing).

## What this stage does (and what it doesn't)

**Does:**
- Adds a `MenuState` + `MenuOverlay` + `ChannelPickerOverlay` UI surface for **channel/lineup control only**.
- Adds a `LineupStore` (SharedPreferences-backed) for on-device persistence of per-slot channel pins.
- Refactors `TileSlotResolver` to be the **single source of truth** for the slot list shared by `VideoGrid` and `MenuOverlay` — the menu and the wall now read the same `List<Slot>`.
- Adds a `Slot.Offline(index, channel)` variant so an operator-assigned-but-not-currently-live channel renders the C2 honest panel **with the channel's label**, distinct from an unassigned `Slot.Empty`.

**Does NOT (BACKLOG):**
- Settings rows (display options, refresh cadence, anything beyond channel pick).
- Layout / pane config (feed width, grid proportions, scalable panes — see existing BACKLOG entry).
- Per-tile audio mixing / unmute, feed filtering UI.
- Multiple named lineup presets — Stage 5 ships exactly one persisted lineup.
- First-run wizard, diagnostics screen.

## The interaction model

| Gesture | Menu closed | Menu open | Picker open |
|---|---|---|---|
| MENU | open menu | close menu | (picker handles) |
| LEFT | open menu | (panel focus) | cycle channel back |
| RIGHT | n/a | (panel focus) | cycle channel forward |
| UP/DOWN | n/a | move focused row | n/a |
| CENTER/OK | n/a | open picker for focused slot | **assign** the channel + dismiss picker |
| BACK | n/a | close menu | **cancel** — slot unchanged |

BACK is caught both via `BackHandler` and via the root `onPreviewKeyEvent`. The dual path is necessary because on TV `Modifier.focusable` consumes BACK to exit a focus group before the dispatcher sees it — `BackHandler` alone wasn't enough (recorded in checkpoint 1).

## The mark-and-allow decision (recorded for the record)

The operator may assign an **offline** channel to a slot. The picker marks each channel with its real current status (`live` / `offline`), so it's never presented as if it will play, but the assignment is allowed. The wall then renders the C2 honest OFFLINE panel for that slot with the assigned channel's label — the operator can see what *would have been* there.

**Why this default (mark-and-allow vs block):** the wall already degrades honestly via the Stage 3 C2 work; assigning an offline channel is the operator queuing it for when it comes back, which is a legitimate use. Blocking would force the operator to wait for a channel to return before they could even pin it. Mark-and-allow + the honest OFFLINE rendering is more flexible without sacrificing honesty. (If a future stage wants block-on-offline, the `LineupStore.assign(slot, slug)` API can be gated with a precondition check.)

## Telemetry — the proof

Operator was away from the TV during verification. The three behaviors that mattered:

### 1. Reassigning a tile to a different LIVE channel fires `TILE_READY` for the new spec id

Baseline launch (no overrides, default cycler):
```
17:55:24  EV=TILE_READY|id=slot-1-redbull-tv
17:55:26  EV=TILE_READY|id=slot-3-redbull-tv
17:55:29  EV=TILE_READY|id=slot-0-dw-news-en      ← slot 0 has DW News
17:55:31  EV=TILE_READY|id=slot-2-dw-news-en
```

After driving the picker via adb keyevents (`MENU → SELECT → RIGHT × 2 → SELECT` to assign `redbull-tv` to slot 0):
```
17:56:35  EV=DECODER|id=slot-0-redbull-tv|...|init_ms=495    ← NEW decoder for the reassigned slot
17:56:36  EV=TILE_READY|id=slot-0-redbull-tv                  ← real first frame, not just label swap
17:56:38  EV=DECODER|id=slot-3-dw-news-en|...                ← cycler rebalanced (redbull pinned out of pool)
17:56:38  EV=DECODER|id=slot-2-dw-news-en|...
17:56:41  EV=TILE_READY|id=slot-2-dw-news-en
```

A new spec id (`slot-0-redbull-tv`) means a new player was constructed, the surface attached, `prepare()` ran, and `onRenderedFirstFrame` fired. **The new stream actually started.**

### 2. Persistence across force-stop and relaunch

Direct disk inspection:
```
$ adb shell run-as com.mymts cat /data/data/com.mymts/shared_prefs/mymts_lineup.xml
<map>
    <string name="lineup_overrides">[0,"redbull-tv"]</string>
</map>
```

After `force-stop` + relaunch:
```
17:57:26  EV=TILE_READY|id=slot-0-redbull-tv      ← restored from persisted state
17:57:26  EV=TILE_READY|id=slot-1-dw-news-en
17:57:33  EV=TILE_READY|id=slot-2-dw-news-en
17:57:34  EV=TILE_READY|id=slot-3-dw-news-en
```

Slot 0 came up as `redbull-tv` (the saved override), not as `dw-news-en` (the default cycler's first pick). **The operator's choice persisted.**

### 3. Offline assignment renders the C2 honest panel, never starts a player

Manually pinned slot 0 → `cnn` (an offline channel — `status=unavailable`, `current_url=null`) plus slot 1 → `dw-news-en` (live). After relaunch:

```
Decoders started: slot-1-dw-news-en, slot-2-redbull-tv, slot-3-redbull-tv
Decoders for slot-0-cnn or slot-0/offline-cnn: NONE
```

`Slot.Offline(0, cnn)` has no spec, no player, no surface — by construction. The wall renders the dead-panel background with `CNN` as the ghost label (visible in `wall-with-cnn-offline-slot0.png`). Crucially: **the structural label/stream binding from `9d5b0ad` (`BoundTile.init { require(player.specId == slot.spec.id) }`) cannot misfire** because `Slot.Offline` has no `spec` field — the type system rules out a wrong pairing.

### 4. No capacity blowout or decoder thrash on reassignment

Total decoder count after the reassignment + relaunch + offline-assignment test: ~10 decoder inits across all slot lifetimes (4 baseline + 3 rebind + 3 post-restart), no recovery strikes, no DEAD events. The Stage 2 N=4 ceiling holds; the menu does not destabilise the wall.

## Architecture — single source of truth

The slot list is built **once** in `WallScreen`, from:

```
ChannelsRepository  ─► allChannels  ─┐
                                     ├─► TileSlotResolver.resolve(N, default, all, overrides)
LineupStore         ─► overrides  ───┤   = List<Slot.Playing | Slot.Offline | Slot.Empty>
LineupSelector      ─► defaultOrder ─┘                              │
                                                                    ▼
                                                ┌───────────────────┴──────────────────┐
                                                ▼                                       ▼
                                          VideoGrid(slots)                       slots.map { it.toRow() }
                                                                                       ▼
                                                                                 MenuOverlay(slotRows)
```

The menu and the wall **cannot disagree** about a slot's channel because they read the same list. The picker writes to `LineupStore`, which flips the `overrides` state, which triggers `slots` to recompute, which re-renders both surfaces. The channel-identity honesty rule from `9d5b0ad` (label and player flow from the same `BoundTile`) is unchanged.

## Persistence format

`SharedPreferences("mymts_lineup")` key `lineup_overrides` holds a JSON array `[slot, slug, slot, slug, …]`:
```json
[0,"redbull-tv",1,"dw-news-en"]
```

Codec lives in `LineupStore.encode`/`decode` (both internal, both unit-tested in `LineupStoreCodecTest`). Tolerances: invalid pairs (negative index, blank slug) are silently skipped; odd-length blobs decode to empty (safer than half-restoring); corrupt JSON resets the blob and logs once. Encoding is deterministic on key order so two writes of the same lineup produce byte-identical disk state.

**No secrets / no PII / no absolute paths.** Values are short slug strings the operator chose; nothing identifying.

## Standing rules at Stage 5 close

- **unrelated host services: never touched.**
- **WyzeGrid** as-found on `.182`, foreground + `WatchdogService` healthy. No disable.
- App unit tests added in this stage:
  - `MenuStateTest` (8 cases) — visibility + sub-overlay state.
  - `TileSlotResolverOverridesTest` (7 cases) — override semantics, offline rendering, graceful fallback for vanished slugs, no double-fill.
  - `LineupStoreCodecTest` (7 cases) — round-trip, malformed-input safety, deterministic encoding.
- Helper untouched (UI-only commit).
