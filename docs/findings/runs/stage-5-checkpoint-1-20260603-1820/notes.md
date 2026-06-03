# Stage 5 checkpoint 1 — menu interaction shell on the TV

**Device:** `<LAN_IP>:5555` (Onn 4K, foreground)
**Helper:** `<LAN_IP>:8091`
**Launched:** 2026-06-03 18:20
**Screencaps:** three frames spanning the open/navigate/close cycle.

## What this checkpoint proves
- The wall renders normally (4 live tiles cycled from helper-resolved channels).
- `KEYCODE_MENU` (D-pad MENU, code 82) opens the side panel. The wall dims to ~45% alpha behind a scrim; the panel slides in from the left.
- D-pad DOWN moves focus row-by-row, in the WyzeGrid pattern (green left-edge accent + green tint + bold label on the focused row).
- D-pad BACK closes the menu cleanly; the wall returns to full opacity. **Important caveat**: `BackHandler` alone wasn't enough on TV — `Modifier.focusable` consumes BACK to exit a focus group before the dispatcher sees it. Belt-and-braces: BACK is also caught in the root `onPreviewKeyEvent` when the menu is open.
- D-pad LEFT, when the menu is closed, also opens it — natural fallback gesture for remotes without a hardware MENU button.
- Throughout the open/navigate/close cycle, `EV=TILE_READY` continues to fire (2 → 4 over the test window from bursty-stream re-renders). The grid keeps playing — opening the menu never touches the `StreamPlayerManager`, so it cannot cascade into a video failure (Trust Bar C2).

## What's NOT here yet (checkpoint 2)
- Rows show `(channel picker — coming next)` instead of real per-slot channel names.
- SELECT/CENTER on a row is a no-op placeholder.
- No persistence (no `LineupStore` yet).

## D-pad model recorded
| Gesture | When menu closed | When menu open |
|---|---|---|
| MENU | open menu | close menu |
| LEFT | open menu | (let Compose focus handle — currently no-op inside panel) |
| UP / DOWN | (wall has nothing focusable) | move focus between rows |
| CENTER / SELECT | n/a | trigger focused row's action (no-op in checkpoint 1) |
| BACK | n/a | close menu |

## Standing rules
- **unrelated host services untouched.**
- **WyzeGrid** as-found on `.182`.
- App tests green; `MenuStateTest` (8 cases) added.
