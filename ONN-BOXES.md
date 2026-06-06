# Onn Box Identity — Source of Truth

> **Shared reference for the WyzeGrid and MyMTS projects.** Both projects deploy to the same two physical Onn 4K Streaming Boxes. This file is the authoritative mapping of IP → physical box → role. Drop a copy in both repos and keep them identical. When a box's *role* changes, update the role line — never the stable name.

## The mapping (authoritative)

Confirmed by Will **in person** — he physically operated the MyMTS dashboard on the office box, so the box running MyMTS is definitively the upstairs office box.

| Stable name | IP (ADB) | Physical location | Hardware | Roles (changeable) |
|---|---|---|---|---|
| **`onn-office`** | `<LAN_IP>:5555` | Upstairs office | onn. 4K Streaming Box (Amlogic S905Y4, armeabi-v7a, Android 14 / API 34, ~1.97 GB RAM). Has a small attached panel (1280×720). | WyzeGrid (cameras) **+ lingering MyMTS dev install (kiosk OFF — inert)** |
| **`onn-basement`** | `<LAN_IP>:5555` | Basement | onn. 4K Streaming Box | WyzeGrid (cameras) |
| **`onn-mymts`** *(pre-staged — fill in at provisioning, 2026-06-06)* | `TBD-at-provision:5555` | TBD (where the production TV lives) | onn. 4K Streaming Box (confirm Amlogic S905Y4 / armeabi-v7a / Android 14 / API 34 at provision) | **MyMTS kiosk (sole kiosk on this box — Model A)** |

## ⚠️ Correction notice — older WyzeGrid records are BACKWARDS

Older WyzeGrid project chats/prompts repeatedly state **`.158` = office** and **`.182` = basement**. **That mapping is WRONG.** The correct, in-person-confirmed mapping is the table above: **`.182` = office**, **`.158` = basement**.

Practical consequence for WyzeGrid: any past "deploy to office" command that targeted `.158` was actually hitting the **basement** box, and vice versa. If anything in WyzeGrid's history looks like it landed on the wrong box, this is why. Going forward, use the names/IPs in the table above.

## Naming principle (so this never rots again)

- **Name boxes by physical location** (`onn-office`, `onn-basement`) — this is stable and never changes.
- **Treat function/role as a separate, changeable attribute** — listed in the "Roles" column, updated freely as what-runs-where changes.
- Do **not** name a box by its current job (e.g. "the MyMTS box" or "the cameras box"). The office box currently runs *both* WyzeGrid and MyMTS; naming it by one job breaks when that changes.

## Known upcoming change — IN PROGRESS (box arriving 2026-06-06)

MyMTS moves to its **own dedicated hardware** (per `MyMTS/docs/foundation/04-TECHNICAL-APPROACH.md §4` portability posture). The `onn-mymts` row above is **pre-staged** for it — at provisioning, fill in the reserved IP (replacing `TBD-at-provision`), confirm the hardware/TV resolution, and rename `onn-mymts` if a location-based name fits better (the stable name should reflect where it physically lives; `onn-mymts` is a role-ish placeholder). The full provisioning runbook is in `docs/OPERATIONS.md §"New MyMTS box provisioning" → "Migration runbook"`.

When provisioning completes:
- `onn-office`'s role line stays WyzeGrid-only (the lingering MyMTS dev install there is inert — kiosk OFF by default — and may be uninstalled).
- `onn-mymts` becomes the active MyMTS deploy target; MyMTS runs kiosk mode there as the **sole** kiosk on that box (Model A).
- No existing names change — only the new row's IP/name/details get filled in.

## Maintenance

- This file lives in both `wyzegrid/` and `MyMTS/` repos. Keep the two copies identical.
- The same mapping is recorded in Claude's cross-project memory so it surfaces in future sessions of either project.
- If a box is re-IP'd, ensure the DHCP reservation is updated and correct the IP here; the stable name stays the same.
