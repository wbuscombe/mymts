# Onn Box Identity — Source of Truth

> **Shared reference for the WyzeGrid and MyMTS projects.** Both projects deploy to the same two physical Onn 4K Streaming Boxes. This file is the authoritative mapping of IP → physical box → role. Drop a copy in both repos and keep them identical. When a box's *role* changes, update the role line — never the stable name.

## The mapping (authoritative)

Confirmed by Will **in person** — he physically operated the MyMTS dashboard on the office box, so the box running MyMTS is definitively the upstairs office box.

| Stable name | IP (ADB) | Physical location | Hardware | Roles (changeable) |
|---|---|---|---|---|
| **`onn-office`** | `<LAN_IP>:5555` | Upstairs office | onn. 4K Streaming Box (Amlogic S905Y4, armeabi-v7a, Android 14 / API 34, ~1.97 GB RAM). Has a small attached panel (1280×720). | WyzeGrid (cameras) **+ currently MyMTS dashboard (temporary)** |
| **`onn-basement`** | `<LAN_IP>:5555` | Basement | onn. 4K Streaming Box | WyzeGrid (cameras) |

## ⚠️ Correction notice — older WyzeGrid records are BACKWARDS

Older WyzeGrid project chats/prompts repeatedly state **`.158` = office** and **`.182` = basement**. **That mapping is WRONG.** The correct, in-person-confirmed mapping is the table above: **`.182` = office**, **`.158` = basement**.

Practical consequence for WyzeGrid: any past "deploy to office" command that targeted `.158` was actually hitting the **basement** box, and vice versa. If anything in WyzeGrid's history looks like it landed on the wrong box, this is why. Going forward, use the names/IPs in the table above.

## Naming principle (so this never rots again)

- **Name boxes by physical location** (`onn-office`, `onn-basement`) — this is stable and never changes.
- **Treat function/role as a separate, changeable attribute** — listed in the "Roles" column, updated freely as what-runs-where changes.
- Do **not** name a box by its current job (e.g. "the MyMTS box" or "the cameras box"). The office box currently runs *both* WyzeGrid and MyMTS; naming it by one job breaks when that changes.

## Known upcoming change

MyMTS will eventually move to its **own dedicated hardware** (per `MyMTS/docs/foundation/04-TECHNICAL-APPROACH.md §4` portability posture). When that happens:
- `onn-office`'s role line drops the MyMTS dashboard and reverts to WyzeGrid-only.
- The new MyMTS hardware gets its own stable location-based name here (e.g. `mymts-display` or wherever it physically lives) with its IP and role.
- No existing names change — only roles and the new row.

## Maintenance

- This file lives in both `wyzegrid/` and `MyMTS/` repos. Keep the two copies identical.
- The same mapping is recorded in Claude's cross-project memory so it surfaces in future sessions of either project.
- If a box is re-IP'd, ensure the DHCP reservation is updated and correct the IP here; the stable name stays the same.
