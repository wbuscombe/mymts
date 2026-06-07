package com.mymts.data.lineup

import com.mymts.data.settings.FeedWidth
import com.mymts.data.settings.Overscan
import com.mymts.data.settings.UiScale
import com.mymts.data.settings.WallSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the PURE wall-settings resolver (`LineupStore.resolveWallSettings`)
 * — the SharedPreferences key-wiring + per-field defaults — without needing
 * an Android context. Focus: the panel-fit fields (uiScale / overscan) added
 * 2026-06-07, including that overscan's default is the TV-safe Medium.
 */
class LineupStoreWallSettingsResolveTest {

    @Test fun `all keys absent resolves to the full Default`() {
        val s = LineupStore.resolveWallSettings(
            contains = { false },
            getInt = { _, d -> d },
            getStringSet = { emptySet() },
            getBoolean = { _, d -> d },
        )
        assertEquals(WallSettings.Default, s)
        assertEquals(Overscan.Medium, s.overscan)
        assertEquals(UiScale.Default, s.uiScale)
    }

    @Test fun `present ordinals are READ from prefs, not hardcoded`() {
        // getInt returns 0 for every key → the 0th of each enum. If uiScale
        // or overscan were hardcoded to their defaults this would fail.
        val s = LineupStore.resolveWallSettings(
            contains = { true },
            getInt = { _, _ -> 0 },
            getStringSet = { emptySet() },
            getBoolean = { _, _ -> false },
        )
        assertEquals(UiScale.Compact, s.uiScale)     // ordinal 0
        assertEquals(Overscan.None, s.overscan)      // ordinal 0
        assertEquals(FeedWidth.Narrow, s.feedWidth)  // ordinal 0 — general read works
    }

    @Test fun `absent-field defaults are wired correctly (overscan Medium, scale Default)`() {
        // Not the all-absent shortcut (contains=true), but getInt echoes the
        // DEFAULT arg the resolver passes per key — so the result reveals
        // exactly which default each field was given. Catches a wrong default
        // (e.g. overscan wired to None instead of the TV-safe Medium).
        val s = LineupStore.resolveWallSettings(
            contains = { true },
            getInt = { _, default -> default },
            getStringSet = { emptySet() },
            getBoolean = { _, d -> d },
        )
        assertEquals(Overscan.Medium, s.overscan)
        assertEquals(UiScale.Default, s.uiScale)
        assertEquals(FeedWidth.Default, s.feedWidth)
    }
}
