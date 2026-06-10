# Finding 18 — Panel fit, round 2: top-left Fit scale + Vertical stretch, the LEFT/RIGHT settings bug, and the overscan-vs-SD-panel saga

> **Status: BUILT + TESTED + DEPLOYED 2026-06-10.** The wall on `.92`'s panel rendered *larger than the visible area from a top-left origin* (top-left seated, bottom-right overflowing) — an anchor the existing centred Overscan inset and ±64 dp Position offset can't fix. Added a **top-left-anchored Fit scale** (the correct lever) + a **Vertical stretch** to close a residual bottom band, fixed a real **settings LEFT/RIGHT** bug found along the way, and added a **Calibration border** diagnostic. Wall now fits at **Fit scale 80% + Vertical stretch 110%**. Deployed (push + `pm install -r`, verified). Same release key.

## The symptom and the wrong anchor

The operator reported the wall **anchored correctly at the top-left** but **overflowing the bottom and right** edges — i.e. the rendered wall is bigger than the panel's visible area, growing from a top-left origin. The panel physically overscans; the box output is perfect (full `1280×720`, `scale=1.0`, `offset=0`, no software overscan — confirmed via `dumpsys display` / `dumpsys window`).

The existing levers couldn't fix this anchor:
- **Overscan inset** shrinks toward **centre** (symmetric margins) — it would pull the *correct* top-left inward, fighting the operator.
- **Position offset** maxes at ±64 dp (~85 px) — far too small to re-pin the top-left of a meaningfully-shrunk wall.
- **Display size / UiScale** scales chrome via a `LocalDensity` override but the wall Column still fills the inset area — it does **not** shrink the footprint, so it never helped fit.

## The fix: top-left-anchored Fit scale

`WallSettings.fitScalePct` (50–100%, default 100). Applied in `WallScreen` as a `graphicsLayer` on the inset Box:

```kotlin
.graphicsLayer {
    scaleX = fitScale
    scaleY = fitScaleY            // fitScale * (fitStretchYPct / 100)
    transformOrigin = TransformOrigin(0f, 0f)   // top-left pivot
}
```

`transformOrigin (0,0)` pins the top-left corner; shrinking pulls the bottom-right inward, black fills the freed space. A pure render transform on top of the inset/offset/density layout, so it composes with them and leaves the focus model untouched (49 `WallFocusModelTest` cases unchanged — it's not a focus change). **Verified empirically via `screencap`:** at Fit scale 70% the non-black content box measured `(≈0,0)–(≈896,504)` — the top-left 70% of the frame, exactly as intended.

## Vertical stretch — closing the residual bottom band

After Fit scale seats the **sides** edge-to-edge (80% on this panel), a slight black band remained at the **bottom** (the freed vertical space). Growing Fit scale would push the sides back off, so that needs a **height-only** grow: `fitStretchYPct` (100–130%, default 100) multiplies `scaleY` only, still top-left anchored. At Fit scale 80% + stretch 110%, `screencap` measured content `(4,20,1020,632)` — right edge unchanged at ≈1020 (80%), bottom dropped from ≈576 to ≈632 (88%). Slight aspect distortion (content a touch taller); default-off so it never affects a panel that doesn't need it.

## The bug found along the way: settings LEFT/RIGHT never fired

While driving the menu via injected keycodes, **LEFT/RIGHT did nothing** on any settings row (SELECT worked). Root cause: in `SettingRow` / `AdjustRow`, `.onPreviewKeyEvent {}` was placed **after** `.clickable()/.focusable()` in the modifier chain. A Compose key-input modifier only receives events when the focus target is its **descendant**; below `.focusable()` it never fires. SELECT worked because `clickable` handles `DPAD_CENTER`, which masked the bug. **Fix:** move the key handler above `clickable`/`focusable`. This had silently broken *every* settings slider (feed width/font/side too) for any operator using a remote.

## The overscan-vs-SD-panel saga (don't be misled)

Mid-diagnosis the panel's EDID read `disp_cap` preferred mode `480p60hz*` (a 480p SD panel, mfr "ADA") — which looked like a catastrophic HD→SD downscale crop, and Android TV refuses to output sub-720p (no root / no Amlogic `free_scale`/`window_axis`/`display-axis` sysfs on this Google TV build / `wm overscan` removed). That sent us toward "wrong panel, needs an emulator." **It was a red herring.** With the emulator removed and the real panel attached, it's plain **top-left-anchored overscan**, fixed app-side by Fit scale. Lesson: confirm the *anchor* and magnitude (the **Calibration border** + `screencap` are the tools) before concluding the panel is fundamentally incompatible.

## Calibration border (the diagnostic that cracked it)

`calibrationBorder` (default off) draws a bright magenta boundary + cyan **TL/TR/BL/BR** corner brackets at the true wall edge (a sibling of the wall inside the inset Box, outside the density override, in real px). On a physically-overscanning panel you otherwise **cannot** see what's cropped; with this on, "fits" is unambiguous (all four corners visible). It's also visible in `screencap`, which is how geometry was verified headlessly (the panel had no screen during emulator testing).

## Verification

- **Empirical geometry:** `screencap` content-box measurements at Fit scale 70% and at 80%+stretch-110% (above) confirm the top-left anchor and the height-only stretch.
- **Tests:** `WallSettingsTest` + `LineupStoreWallSettingsResolveTest` — fit-scale + vertical-stretch defaults (100), clamp-on-read, per-key wiring (a key returning an over-range value clamps and doesn't bleed into the other Int setting), the Overscan ladder (8 rungs, strictly ascending). Focus model 49 unchanged.
- **Deploy:** `adb push` + `pm install -r` (streamed `adb install` deadlocks on this box; verify `lastUpdateTime` advanced). Health-gate: 4 `TILE_READY`, 0 dead, MyMTS foreground.

## Standing rules

- Same release key (not regenerated/reprinted). `.182`/`.158` untouched. unrelated host services never touched. No secrets/absolute-paths. Box ends clean known-good (720p, fitted, persisted).
