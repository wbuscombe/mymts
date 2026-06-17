package com.mymts.data.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the runtime helper-URL resolution: the precedence chain
 * (persisted > adb-extra > configured BuildConfig) and input normalization.
 * These are the make-or-break distribution invariants — a regression here means
 * either the operator's build breaks or a stock APK can't be pointed anywhere.
 */
class HelperUrlTest {

    // ---- normalize ----

    @Test fun `normalize keeps a full url, trims, and strips trailing slash`() {
        assertEquals("http://192.168.1.5:8091", HelperUrl.normalize("http://192.168.1.5:8091"))
        assertEquals("https://nas.local:8443", HelperUrl.normalize("https://nas.local:8443/"))
        assertEquals("http://h:8091", HelperUrl.normalize("  http://h:8091  "))
        assertEquals("https://h:8443", HelperUrl.normalize("https://h:8443//"))
    }

    @Test fun `normalize defaults a schemeless host to http (never silently https)`() {
        assertEquals("http://192.168.1.5:8091", HelperUrl.normalize("192.168.1.5:8091"))
        assertEquals("http://nas.local", HelperUrl.normalize("nas.local"))
    }

    @Test fun `normalize rejects blank, scheme-only, spaces, and bad ports`() {
        assertNull(HelperUrl.normalize(null))
        assertNull(HelperUrl.normalize(""))
        assertNull(HelperUrl.normalize("   "))
        assertNull(HelperUrl.normalize("http://"))
        assertNull(HelperUrl.normalize("my helper"))          // whitespace in host
        assertNull(HelperUrl.normalize("host:notaport"))      // non-numeric port
        assertNull(HelperUrl.normalize("host:99999"))         // port out of range
    }

    // ---- resolve precedence: persisted > adb-extra > configured BuildConfig ----

    private val DEFAULT = "https://operator.nas:8443"

    @Test fun `persisted runtime value wins over everything`() {
        assertEquals(
            "http://persisted:8091",
            HelperUrl.resolve(
                persisted = "http://persisted:8091",
                adbExtra = "http://adb:8091",
                buildDefault = DEFAULT,
                buildConfigured = true,
            ),
        )
    }

    @Test fun `adb-extra wins over BuildConfig when no persisted value`() {
        assertEquals(
            "http://adb:8091",
            HelperUrl.resolve(
                persisted = null,
                adbExtra = "adb:8091",
                buildDefault = DEFAULT,
                buildConfigured = true,
            ),
        )
    }

    @Test fun `configured BuildConfig default resolves the operators build out-of-box`() {
        assertEquals(
            DEFAULT,
            HelperUrl.resolve(
                persisted = null, adbExtra = null,
                buildDefault = DEFAULT, buildConfigured = true,
            ),
        )
    }

    @Test fun `a stock APK (unconfigured default) resolves to null - first-run setup`() {
        // The localhost fallback is NOT a resolution when the build wasn't configured.
        assertNull(
            HelperUrl.resolve(
                persisted = null, adbExtra = null,
                buildDefault = "http://localhost:8091", buildConfigured = false,
            ),
        )
    }

    @Test fun `a persisted value still wins on a stock APK (the configured-via-setup case)`() {
        assertEquals(
            "http://192.168.1.5:8091",
            HelperUrl.resolve(
                persisted = "192.168.1.5:8091", adbExtra = null,
                buildDefault = "http://localhost:8091", buildConfigured = false,
            ),
        )
    }

    @Test fun `blank persisted falls through to the next candidate`() {
        assertEquals(
            "http://adb:8091",
            HelperUrl.resolve(
                persisted = "   ", adbExtra = "adb:8091",
                buildDefault = DEFAULT, buildConfigured = true,
            ),
        )
    }
}
