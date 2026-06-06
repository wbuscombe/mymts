package com.mymts.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure kiosk decision logic. The on-hardware behaviour (the
 * service actually holding the foreground for hours; a real reboot
 * relaunching it) is STAGED for the migration session — these tests
 * cover the decisions that gate that behaviour so the device session is
 * validating wiring, not logic.
 */
class KioskPolicyTest {

    // ---- boot-action allowlist ----

    @Test fun `recognises standard + locked + quickboot boot actions`() {
        assertTrue(KioskPolicy.isBootAction("android.intent.action.BOOT_COMPLETED"))
        assertTrue(KioskPolicy.isBootAction("android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertTrue(KioskPolicy.isBootAction("android.intent.action.QUICKBOOT_POWERON"))
        assertTrue(KioskPolicy.isBootAction("com.htc.intent.action.QUICKBOOT_POWERON"))
    }

    @Test fun `ignores non-boot, null, and spoofed actions`() {
        assertFalse(KioskPolicy.isBootAction(null))
        assertFalse(KioskPolicy.isBootAction(""))
        assertFalse(KioskPolicy.isBootAction("android.intent.action.MAIN"))
        assertFalse(KioskPolicy.isBootAction("com.evil.FAKE_BOOT"))
        assertFalse(KioskPolicy.isBootAction("android.intent.action.BOOT_COMPLETED_NOT"))
    }

    // ---- the opt-in boot gate (the .182-safety property) ----

    @Test fun `boot start requires BOTH a boot action AND kiosk enabled`() {
        // Enabled + boot action -> start.
        assertTrue(KioskPolicy.shouldStartOnBoot("android.intent.action.BOOT_COMPLETED", kioskEnabled = true))
        // Kiosk disabled -> never start, even on a real boot action.
        // This is what keeps the same APK inert on .182.
        assertFalse(KioskPolicy.shouldStartOnBoot("android.intent.action.BOOT_COMPLETED", kioskEnabled = false))
        // Enabled but not a boot action -> don't start.
        assertFalse(KioskPolicy.shouldStartOnBoot("android.intent.action.MAIN", kioskEnabled = true))
        // Disabled + junk -> don't start.
        assertFalse(KioskPolicy.shouldStartOnBoot("com.evil.FAKE_BOOT", kioskEnabled = false))
    }

    // ---- relaunch decision ----

    @Test fun `relaunch only when enabled and not already foreground`() {
        assertTrue(KioskPolicy.shouldRelaunchActivity(kioskEnabled = true, activityForeground = false))
        assertFalse(KioskPolicy.shouldRelaunchActivity(kioskEnabled = true, activityForeground = true))
        assertFalse(KioskPolicy.shouldRelaunchActivity(kioskEnabled = false, activityForeground = false))
        assertFalse(KioskPolicy.shouldRelaunchActivity(kioskEnabled = false, activityForeground = true))
    }

    // ---- crash-loop backoff ----

    @Test fun `restart backoff escalates then caps at 60s`() {
        assertEquals(0L, KioskPolicy.restartBackoffMs(0))      // first restart immediate
        assertEquals(2_000L, KioskPolicy.restartBackoffMs(1))
        assertEquals(5_000L, KioskPolicy.restartBackoffMs(2))
        assertEquals(15_000L, KioskPolicy.restartBackoffMs(3))
        assertEquals(30_000L, KioskPolicy.restartBackoffMs(4))
        assertEquals(60_000L, KioskPolicy.restartBackoffMs(5))
        assertEquals(60_000L, KioskPolicy.restartBackoffMs(50))  // capped
    }

    @Test fun `restart backoff clamps negative input to immediate`() {
        assertEquals(0L, KioskPolicy.restartBackoffMs(-3))
    }

    // ---- crash-streak window ----

    @Test fun `restarts within the window are the same streak, beyond it reset`() {
        assertTrue(KioskPolicy.isSameCrashStreak(0L))
        assertTrue(KioskPolicy.isSameCrashStreak(60_000L))
        assertTrue(KioskPolicy.isSameCrashStreak(KioskPolicy.STREAK_RESET_MS - 1))
        // Exactly at / beyond the reset window: a fresh streak (healthy uptime recovered).
        assertFalse(KioskPolicy.isSameCrashStreak(KioskPolicy.STREAK_RESET_MS))
        assertFalse(KioskPolicy.isSameCrashStreak(10 * 60 * 1000L))
        // Negative gap (clock skew) is not a streak.
        assertFalse(KioskPolicy.isSameCrashStreak(-1L))
    }
}
