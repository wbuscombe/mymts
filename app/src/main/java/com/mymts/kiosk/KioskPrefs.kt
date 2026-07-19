package com.mymts.kiosk

import android.content.Context

/**
 * Tiny on-device flag for whether this box runs MyMTS in kiosk mode.
 *
 * **Opt-in, off by default.** This is the load-bearing safety property
 * of the whole kiosk chapter: the same signed APK installed on a
 * non-kiosk box (where a MyMTS dev install may linger) must NOT start
 * a foreground service or
 * autostart on boot. Kiosk mode is flipped on only during the
 * provisioning runbook for the dedicated MyMTS box (via the
 * `--ez kiosk true` launch extra `MainActivity` honours), so an
 * un-provisioned install is completely inert as a kiosk.
 *
 * SharedPreferences (not DataStore) for the same reason as
 * `LineupStore`: one boolean, read at boot + launch, written once at
 * provisioning. No secrets, no PII.
 */
class KioskPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "mymts_kiosk"
        private const val KEY_ENABLED = "kiosk_enabled"

        /**
         * **Off by default** — see the class doc. An install is not a
         * kiosk until the provisioning step explicitly enables it.
         */
        const val DEFAULT_ENABLED = false

        /** Read kiosk-enabled without holding a [KioskPrefs] instance. */
        fun isEnabled(context: Context): Boolean = KioskPrefs(context).isEnabled
    }
}
