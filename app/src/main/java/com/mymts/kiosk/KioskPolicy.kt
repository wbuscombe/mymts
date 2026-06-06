package com.mymts.kiosk

/**
 * Pure decision logic for MyMTS's own-the-box kiosk behaviour.
 *
 * **Model A (one kiosk app per box):** the dedicated MyMTS Onn box runs
 * MyMTS as the *sole* kiosk — so this module is about staying up and
 * foregrounded reliably on its own box, NOT reclaiming the foreground
 * from a competitor (that coexistence complexity is deliberately not
 * built; see `docs/THREAT-MODEL.md`). `.182` is WyzeGrid's box and is
 * never touched.
 *
 * Everything here is **pure Kotlin** (no Android imports) so the boot
 * + restart decisions are unit-tested without a device — the on-hardware
 * behaviour (does the service actually hold the foreground for hours,
 * does a real reboot relaunch it) is STAGED for the migration session
 * and is honestly labelled unverified-until-then.
 */
object KioskPolicy {

    /**
     * The boot broadcast actions that should (re)start the kiosk. We
     * accept the standard `BOOT_COMPLETED`, the pre-unlock
     * `LOCKED_BOOT_COMPLETED` (direct-boot devices fire this first), and
     * the OEM quickboot/HTC variants some Android-TV boxes use instead
     * of the standard action. Anything NOT in this allowlist is ignored
     * — a receiver that acts on arbitrary actions is an injection
     * surface (A1: treat external triggers as untrusted).
     */
    val BOOT_ACTIONS: Set<String> = setOf(
        "android.intent.action.BOOT_COMPLETED",
        "android.intent.action.LOCKED_BOOT_COMPLETED",
        "android.intent.action.QUICKBOOT_POWERON",
        "com.htc.intent.action.QUICKBOOT_POWERON",
    )

    /** True iff [action] is a recognised boot trigger. */
    fun isBootAction(action: String?): Boolean = action != null && action in BOOT_ACTIONS

    /**
     * The single gate for "should the boot receiver start the kiosk
     * service?": a recognised boot action AND kiosk mode is enabled on
     * this device. **Kiosk mode is opt-in (off by default)** so the same
     * signed APK is inert on a non-kiosk box (e.g. `.182`, WyzeGrid's
     * box) — no foreground service, no boot autostart — and only the
     * provisioned MyMTS box (where the runbook enables it) owns its
     * screen.
     */
    fun shouldStartOnBoot(action: String?, kioskEnabled: Boolean): Boolean =
        kioskEnabled && isBootAction(action)

    /**
     * Whether the foreground service should relaunch the wall activity.
     * Only when kiosk is enabled AND the activity isn't already in the
     * foreground — re-launching an already-foreground activity would
     * churn the wall (player teardown/rebuild) for no reason.
     */
    fun shouldRelaunchActivity(kioskEnabled: Boolean, activityForeground: Boolean): Boolean =
        kioskEnabled && !activityForeground

    /**
     * Crash-loop backoff. If the service/activity keeps dying, an
     * unbounded instant-restart loop would hammer the box and spam logs.
     * Returns the delay (ms) before the next relaunch given how many
     * consecutive restarts happened inside the recent window.
     *
     * Schedule: 0s, 2s, 5s, 15s, 30s, then capped at 60s. The first
     * restart is immediate (a one-off crash should recover instantly);
     * sustained crashing backs off to once a minute so the box stays
     * responsive and the forensic log (`CrashLog`) stays legible.
     */
    fun restartBackoffMs(consecutiveRestarts: Int): Long {
        val steps = longArrayOf(0L, 2_000L, 5_000L, 15_000L, 30_000L, 60_000L)
        val idx = consecutiveRestarts.coerceIn(0, steps.size - 1)
        return steps[idx]
    }

    /**
     * Whether two restarts [gapMs] apart count as part of the same
     * crash-loop streak (so the backoff escalates) or a fresh,
     * unrelated restart (streak resets). A restart more than
     * [STREAK_RESET_MS] after the previous one is treated as healthy
     * uptime that recovered — reset the streak.
     */
    fun isSameCrashStreak(gapMs: Long): Boolean = gapMs in 0 until STREAK_RESET_MS

    /** Uptime beyond this since the last restart resets the crash streak. */
    const val STREAK_RESET_MS: Long = 5 * 60 * 1000L
}
