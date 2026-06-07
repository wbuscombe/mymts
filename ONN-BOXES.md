# Onn Box Identity — Source of Truth

> **Shared reference for the WyzeGrid and MyMTS projects.** Both projects deploy to the same **three** physical Onn 4K Streaming Boxes. This file is the authoritative mapping of IP → physical box → role. Drop a copy in both repos and keep them identical. Names are **location + default (kiosk) function** (there are now two boxes in the office, so location alone no longer disambiguates).

## The mapping (authoritative)

Confirmed by Will **in person** — he physically operated the MyMTS dashboard on the office box, so the box running MyMTS is definitively the upstairs office box.

Naming is **location + default (kiosk) function** — there are now two boxes in the office, so location alone no longer disambiguates them.

| Stable name | IP (ADB) | Physical location | Hardware | Roles (changeable) |
|---|---|---|---|---|
| **`Office ONN Box - WyzeGrid`** | `<LAN_IP>:5555` | Upstairs office | onn. 4K Streaming Box (Amlogic S905Y4, armeabi-v7a, Android 14 / API 34, ~1.97 GB RAM). Small attached panel (1280×720). | **WyzeGrid kiosk (cameras)** + lingering MyMTS dev install (kiosk OFF — inert) |
| **`Basement ONN Box - WyzeGrid`** | `<LAN_IP>:5555` | Basement | onn. 4K Streaming Box | **WyzeGrid kiosk (cameras)** |
| **`Office ONN Box - MyMTS`** | `<LAN_IP>:5555` | Upstairs office | onn. 4K Streaming Box (Amlogic S905Y4 / `s4` board, armeabi-v7a, Android 14 / API 34, build `URO4.260304.011.B1`, serial `GUSA2541026903`). MAC `<MAC>` (DHCP-reserved). 720p panel, **no EDID emulator** (the emulator was the garble cause — removed; the panel auto-negotiates 720p60 natively, `defaultModeId`=720p). | **MyMTS kiosk (sole kiosk, Model A — release-signed with the fresh key)** + WyzeGrid debug-dormant (installed, no kiosk flag, does not autostart) |

## ⚠️ Correction notice — older WyzeGrid records are BACKWARDS

Older WyzeGrid project chats/prompts repeatedly state **`.158` = office** and **`.182` = basement**. **That mapping is WRONG.** The correct, in-person-confirmed mapping is the table above: **`.182` = office**, **`.158` = basement**.

Practical consequence for WyzeGrid: any past "deploy to office" command that targeted `.158` was actually hitting the **basement** box, and vice versa. If anything in WyzeGrid's history looks like it landed on the wrong box, this is why. Going forward, use the names/IPs in the table above.

## Naming principle (so this never rots again)

- **Name boxes by physical location** (`onn-office`, `onn-basement`) — this is stable and never changes.
- **Treat function/role as a separate, changeable attribute** — listed in the "Roles" column, updated freely as what-runs-where changes.
- Do **not** name a box by its current job (e.g. "the MyMTS box" or "the cameras box"). The office box currently runs *both* WyzeGrid and MyMTS; naming it by one job breaks when that changes.

## Provisioning — DONE 2026-06-07

MyMTS moved to its **own dedicated hardware** (`Office ONN Box - MyMTS` = `<LAN_IP>`, MAC `<MAC>`) per `04-TECHNICAL-APPROACH.md §4` portability posture. Provisioning completed 2026-06-07: aggressive reversible debloat (12 pkgs), a **fresh MyMTS release key** generated (the prior `.182`-era key was abandoned), MyMTS installed release-signed + kiosked (sole kiosk, Model A) reaching LIVE on the 720p panel, WyzeGrid installed debug-dormant. See `docs/OPERATIONS.md §"Office ONN Box - MyMTS provisioning (DONE 2026-06-07)"`.

- The WyzeGrid office box (`.182`) is unchanged — still WyzeGrid's cameras kiosk; its lingering MyMTS dev install stays inert (kiosk OFF).
- The MyMTS box (`.92`) runs MyMTS kiosk as the **sole** kiosk (Model A); WyzeGrid is present-but-dormant (no kiosk flag).
- The emulator that caused the garble is removed; the bare panel auto-negotiates 720p natively.

## Maintenance

- This file lives in both `wyzegrid/` and `MyMTS/` repos. Keep the two copies identical.
- The same mapping is recorded in Claude's cross-project memory so it surfaces in future sessions of either project.
- If a box is re-IP'd, ensure the DHCP reservation is updated and correct the IP here; the stable name stays the same.
