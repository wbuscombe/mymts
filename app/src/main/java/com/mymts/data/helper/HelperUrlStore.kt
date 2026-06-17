package com.mymts.data.helper

import android.content.Context

/**
 * Persists the user-entered runtime helper base URL — the top of the resolution
 * precedence chain (see [HelperUrl]). This is what lets a STOCK APK point at any
 * helper with no rebuild: the first-run setup (or the Settings "Helper URL" field)
 * writes the reachability-tested URL here, and it wins over the adb-extra +
 * BuildConfig default on every launch.
 *
 * SharedPreferences (not DataStore) for the same reason as `KioskPrefs` /
 * `LineupStore`: a single short string, read synchronously at launch (so onCreate
 * can decide setup-vs-wall without an async hop), written rarely. No secrets — a
 * LAN address the operator typed.
 */
class HelperUrlStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The persisted runtime URL, or null if the user hasn't set one. */
    fun get(): String? = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() }

    /** Persist a (already normalized + reachability-tested) helper base URL. */
    fun set(url: String) {
        prefs.edit().putString(KEY_URL, url).apply()
    }

    /** Forget the runtime URL (resolution falls back to adb-extra / BuildConfig). */
    fun clear() {
        prefs.edit().remove(KEY_URL).apply()
    }

    companion object {
        private const val PREFS_NAME = "mymts_helper_url"
        private const val KEY_URL = "helper_base_url"
    }
}
