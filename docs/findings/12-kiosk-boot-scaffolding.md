# Finding 12 — Kiosk / boot scaffolding for the dedicated MyMTS box

> **Status: BUILT (code) + STAGED (on-hardware) 2026-06-06.** The kiosk/foreground/boot code and the provisioning runbook are committed; app tests are green (7 new `KioskPolicyTest` cases) and the APK builds. **On-hardware validation is explicitly STAGED for the migration session** this afternoon when the dedicated box arrives — foreground hold over hours, boot-receiver via a real reboot, low-memory survival, and the accumulated feel-test. None of those hardware-dependent behaviours are claimed working yet. Model A: one kiosk app per box — the new Onn box runs MyMTS as the **sole** kiosk; `.182` stays WyzeGrid's and is untouched. **Kiosk mode is OPT-IN, OFF BY DEFAULT** — the same signed APK on a non-kiosk box does not autostart or foreground without explicit provisioning.

## Why this chapter happened

The dedicated MyMTS Onn box arrives this afternoon. Rather than build the kiosk story from scratch during the migration session (slow, error-prone, with the box as a live dependency), this chapter builds everything that does **not** need the hardware — the foreground service, boot receiver, the pure decision logic, the manifest wiring, and the provisioning runbook — so the at-the-box session is execution and validation, not construction.

Model A (one kiosk app per box) was confirmed earlier: the new box is MyMTS's permanent home and runs MyMTS alone; `.182` remains WyzeGrid's camera box. That decision is what makes this chapter *simpler* than the long-feared coexistence story (see below).

## The opt-in gate: the load-bearing safety decision

The central design decision is `shouldStartOnBoot = kioskEnabled AND isBootAction`, with `kioskEnabled` defaulting to **false**.

Why it matters: the *same* signed APK is (and will remain) installed on `.182` for dev, and will be installed on the new box for production. If kiosk mode were on by default — or if the boot gate were soft — then a reboot on `.182` would start a MyMTS foreground service and pin its wall over WyzeGrid's cameras, recreating exactly the Stage 1/2 two-watchdog thrash on the camera box. The opt-in gate (both conditions true) guarantees that does not happen: `.182` is never provisioned-on, so its kiosk flag stays false, so MyMTS there starts no foreground service and never autostarts on boot. The shared APK is inert as a kiosk everywhere except the one box the runbook explicitly enables.

This was adversarially verified — a refute-first reviewer traced every activation path (KioskPrefs default, BootReceiver gate, `startIfEnabled`, the MainActivity intent hook, `onTaskRemoved`, and whether merely holding the manifest permissions could start anything) and found no path that activates kiosk on a non-provisioned box. Verdict: not refuted, 12 file:line citations.

## Own-the-box, not coexistence

Coexistence (two kiosk apps negotiating the foreground on one box) was **never built** — it was deferred from the start as the risky path, and Model A removes the need for it entirely. There is no foreground-reclaim loop, no `SYSTEM_ALERT_WINDOW`, no "detect the other app and drop ours" state machine anywhere in this code. The kiosk simply assumes it owns its box and stays up + foregrounded. That assumption is true on the dedicated box (sole kiosk) and is enforced on every other box by the opt-in gate (kiosk off → MyMTS isn't a kiosk there at all). A future shared-box use case would need fresh threat-modelling before any reclaim logic is added; it is explicitly out of scope here.

## What got built

- **`KioskPolicy.kt`** — pure Kotlin, no Android imports (so the decisions are unit-tested without a device). `BOOT_ACTIONS` allowlist: `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `QUICKBOOT_POWERON`, HTC's `QUICKBOOT_POWERON`. `isBootAction(action)`; `shouldStartOnBoot(action, kioskEnabled)` returns true only when both hold. `shouldRelaunchActivity(enabled, foreground)`. Crash-loop backoff: `restartBackoffMs()` → 0 / 2 / 5 / 15 / 30 / 60 s (capped); `isSameCrashStreak(gapMs)` + `STREAK_RESET_MS` (5 min) reset the streak after healthy uptime so a one-off crash recovers instantly while sustained crashing backs off.
- **`KioskPrefs.kt`** — SharedPreferences flag, `DEFAULT_ENABLED=false`. `isEnabled` / `setEnabled` instance methods + static `isEnabled(context)`. No secrets, no PII — a single boolean.
- **`KioskService.kt`** — foreground `Service`: ongoing low-importance notification + dedicated channel; `START_STICKY` (system recreates it after a kill); `onTaskRemoved()` relaunches the wall (gated on `KioskPrefs`) so a swipe can't leave a dead screen; `startForeground()` with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` guarded by an API-34 check (`Build.VERSION_CODES.UPSIDE_DOWN_CAKE`), plain `startForeground()` below. Companion `startIfEnabled(context)` is a no-op when kiosk is off (the gate that keeps it dormant on a non-kiosk box), plus `stop(context)` and `launchWall(context)`.
- **`BootReceiver.kt`** — `BroadcastReceiver`; `onReceive()` returns early unless `KioskPolicy.shouldStartOnBoot(action, KioskPrefs.isEnabled(context))`, then `startIfEnabled()` + `launchWall()`.
- **`AndroidManifest.xml`** — permissions `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`; `<service .kiosk.KioskService exported="false" foregroundServiceType="specialUse">` with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification; `<receiver .kiosk.BootReceiver exported="true">` filtered to the four boot actions. Holding the permissions starts nothing — only an explicit (gated) start does.
- **`MainActivity.kt`** — `applyKioskExtraIfPresent()` persists the kiosk flag + starts/stops the service **only** when the launch intent carries the `kiosk` extra; a normal launch never changes kiosk state. `KioskService.startIfEnabled(this)` runs on launch (no-op when off). Keep-screen-on retained for the wall role.

## Buildable-now vs STAGED — the honesty line

**Unit-tested now (pure decisions, no box):** the boot-action allowlist (incl. spoofed/null rejected), the both-conditions boot gate (the `.182`-safety property), the relaunch decision, and the crash-loop backoff schedule + streak window — 7 `KioskPolicyTest` cases, all green.

**STAGED for the migration session (genuinely needs the box; NOT claimed working):**
- Foreground hold over hours — the service actually staying resident/foregrounded across a long run.
- Boot-receiver relaunch via a real power-cycle.
- Low-memory survival.
- The full provisioning runbook executed end-to-end.
- The accumulated nav + feed + config + ticker feel-test, now runnable on the MyMTS box (its permanent home) rather than borrowed `.182`.

The device session validates *wiring*, because the *logic* is already pinned by tests.

## Crash-loop backoff rationale

A foreground service that crashes repeatedly could hammer the box and flood `CrashLog`. The backoff (0 / 2 / 5 / 15 / 30 / 60 s, capped) makes the first restart immediate (a one-off crash recovers instantly) and escalates only under sustained crashing; `STREAK_RESET_MS` (5 min of healthy uptime) resets the streak so a later, unrelated restart isn't penalised.

## Provisioning readiness — the "make migration fast" deliverables

- **OPERATIONS "Migration runbook"** — ordered, runnable steps: (0) helper-redeploy prerequisite (13 feed sources + `/api/ticker/{markets,sports}` verified via `/health` + a ticker probe); (1) physical setup + enable debugging; (2) DHCP reservation → record `NEWIP`; (3) `adb connect NEWIP:5555`; (4) fill the `onn-mymts` row in `ONN-BOXES.md` (both repos); (5) `scripts/deploy-app.sh --device NEWIP:5555 …` (release-signed gate via `apksigner`; the HTTPS helper URL `https://<LAN_IP>:8443` + pinned cert is baked, no per-box edit); (6) verify the wall reaches the helper (feed sectioned, a tile LIVE, real ticker); (7) enable kiosk: `adb -s NEWIP:5555 shell am start -n com.mymts/.MainActivity --ez kiosk true`; (8) **[STAGED]** 80-min foreground hold, real-reboot relaunch, low-memory survival; (9) **[STAGED]** the accumulated feel-test; (10) confirm `.182` unchanged.
- **Operator inputs at migration:** `NEWIP`, and the release **keystore** reachable (the signing secret — never committed).
- **`ONN-BOXES.md`** pre-stages the `onn-mymts` row (IP `TBD-at-provision`, role MyMTS sole kiosk) so step 4 is a fill-in, not a fresh edit.

## What's deferred

- **Coexistence / foreground-reclaim** — Model A makes it unnecessary; not built (and never was).
- **A settings-menu kiosk toggle** — the `--ez kiosk` adb intent is the v1 provisioning mechanism; an in-app toggle is a possible later convenience, not needed for ops.
- **Anything on `.182`** — WyzeGrid's box is untouched; the shared APK is safe there by the opt-in gate.

## Open items for the migration session

- Does the foreground service hold across the ~80-min window on this hardware budget (Model A means nothing should contend — but it's the first real long run on the box)?
- Does a real `adb reboot` bring the wall back on its own (BootReceiver → KioskService → wall → LIVE) with no manual touch?
- Does MyMTS survive low memory without eviction?
- Does the accumulated feel-test (navigation, sectioned feed, config, real ticker) read well on the production TV at 10 ft?

## Standing rules at this stage

- **`.182` + WyzeGrid: untouched.** The opt-in gate is the guarantee for the shared APK.
- **unrelated host services: never touched.** Helper deploy uses its own bridge network.
- **The release keystore is the only signing secret** — never committed or logged.
- **No secrets / no absolute paths** in code or logs (the kiosk flag is a boolean).
- **No coexistence / reclaim logic** — Model A.
