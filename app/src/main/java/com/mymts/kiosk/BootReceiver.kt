package com.mymts.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mymts.util.CrashLog

/**
 * Relaunches MyMTS after a box reboot/power-cycle (Operational Bar B1 —
 * an unattended wall must come back on its own).
 *
 * The decision is delegated to [KioskPolicy.shouldStartOnBoot], which
 * requires BOTH a recognised boot action AND kiosk mode enabled. So:
 *   - on the provisioned MyMTS box (kiosk on) a reboot starts the
 *     foreground service, which brings the wall to the front;
 *   - on any other box (kiosk off — the default), this receiver is a
 *     no-op, so the same signed APK never autostarts on a non-kiosk box.
 *
 * Registered for `BOOT_COMPLETED` + `LOCKED_BOOT_COMPLETED` + the
 * quickboot variants in the manifest; the policy allowlist is the
 * second line of defence against acting on an unexpected/spoofed action.
 *
 * **STAGED:** real-reboot validation happens on the new box in the
 * migration session.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (!KioskPolicy.shouldStartOnBoot(action, KioskPrefs.isEnabled(context))) {
            return
        }
        CrashLog.log(context, "BOOT_RECEIVER | action=$action | starting kiosk service")
        KioskService.startIfEnabled(context)
        KioskService.launchWall(context)
    }
}
