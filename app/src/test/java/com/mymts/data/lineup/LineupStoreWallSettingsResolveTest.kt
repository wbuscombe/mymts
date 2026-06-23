package com.mymts.data.lineup

import com.mymts.data.settings.FeedWidth
import com.mymts.data.settings.FIT_SCALE_MAX_PCT
import com.mymts.data.settings.FIT_STRETCH_Y_MAX_PCT
import com.mymts.data.settings.OFFSET_RANGE_DP
import com.mymts.data.settings.Overscan
import com.mymts.data.settings.TickerMotion
import com.mymts.data.settings.UiScale
import com.mymts.data.settings.WallSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertEquals(0, s.offsetXDp)
        assertEquals(0, s.offsetYDp)
        assertFalse(s.calibrationBorder)
        assertEquals(100, s.fitScalePct)
        assertEquals(100, s.fitStretchYPct)
        assertEquals(2, s.gridRows)
        assertEquals(2, s.gridCols)
    }

    @Test fun `fit scale is read from prefs and clamped on read`() {
        // Only the fit-scale key returns an over-range value → it must clamp to
        // the max (a corrupt stored scale can't blow the wall up or vanish it),
        // and it must be wired to its OWN key, not another Int setting.
        val s = LineupStore.resolveWallSettings(
            contains = { true },
            getInt = { key, d -> if (key.contains("fit_scale")) 9999 else d },
            getStringSet = { emptySet() },
            getBoolean = { _, d -> d },
        )
        assertEquals(FIT_SCALE_MAX_PCT, s.fitScalePct)
        assertEquals(UiScale.Default, s.uiScale)   // other Int settings unaffected
    }

    @Test fun `vertical stretch is read from its own key and clamped on read`() {
        val s = LineupStore.resolveWallSettings(
            contains = { true },
            getInt = { key, d -> if (key.contains("fit_stretch")) 9999 else d },
            getStringSet = { emptySet() },
            getBoolean = { _, d -> d },
        )
        assertEquals(FIT_STRETCH_Y_MAX_PCT, s.fitStretchYPct)
        assertEquals(FIT_SCALE_MAX_PCT, s.fitScalePct)   // the OTHER fit key unaffected
    }

    @Test fun `calibration border is read from prefs (its own key)`() {
        // getBoolean returns true ONLY for the calibration key — so a true
        // result proves the resolver wired calibration to its own pref, not
        // (e.g.) to the ticker-news boolean.
        val on = LineupStore.resolveWallSettings(
            contains = { true },
            getInt = { _, d -> d },
            getStringSet = { emptySet() },
            getBoolean = { key, _ -> key.contains("calibration") },
        )
        assertTrue(on.calibrationBorder)
        assertFalse(on.tickerNewsEnabled)   // the other boolean stays off
        val off = LineupStore.resolveWallSettings(
            contains = { true },
            getInt = { _, d -> d },
            getStringSet = { emptySet() },
            getBoolean = { _, _ -> false },
        )
        assertFalse(off.calibrationBorder)
    }

    @Test fun `position offset is read from prefs and clamped on read`() {
        // contains=true (not the all-absent shortcut); getInt returns an
        // out-of-range value for every key → the offset must clamp to the
        // bound (a corrupt stored value can't shove the wall off-screen).
        val s = LineupStore.resolveWallSettings(
            contains = { true },
            getInt = { _, _ -> 9999 },
            getStringSet = { emptySet() },
            getBoolean = { _, _ -> false },
        )
        assertEquals(OFFSET_RANGE_DP, s.offsetXDp)
        assertEquals(OFFSET_RANGE_DP, s.offsetYDp)
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
        assertEquals(TickerMotion.Flip, s.tickerMotion)   // absent → native default
    }

    @Test fun `ticker motion is read from its own key (default Flip when absent)`() {
        // getInt returns Crawl's ordinal ONLY for the ticker-motion key → proves
        // the resolver wired motion to its own pref (not, say, the feed-side int).
        val crawl = LineupStore.resolveWallSettings(
            contains = { true },
            getInt = { key, d -> if (key.contains("ticker_motion")) TickerMotion.Crawl.ordinal else d },
            getStringSet = { emptySet() },
            getBoolean = { _, d -> d },
        )
        assertEquals(TickerMotion.Crawl, crawl.tickerMotion)
        // A corrupt/out-of-range stored ordinal falls back to the default (Flip).
        val corrupt = LineupStore.resolveWallSettings(
            contains = { true },
            getInt = { key, d -> if (key.contains("ticker_motion")) 999 else d },
            getStringSet = { emptySet() },
            getBoolean = { _, d -> d },
        )
        assertEquals(TickerMotion.Flip, corrupt.tickerMotion)
    }

    @Test fun `hiddenGenres is read from its own key (not the sources or leagues set)`() {
        // getStringSet returns a sentinel ONLY for the genres key → proves the
        // resolver wired hiddenGenres to its OWN pref, not (e.g.) hidden_sources.
        // A field-isolation guard the whole-object round-trip can't pinpoint.
        val s = LineupStore.resolveWallSettings(
            contains = { true },
            getInt = { _, d -> d },
            getStringSet = { key -> if (key.contains("hidden_genres")) setOf("Sports") else emptySet() },
            getBoolean = { _, d -> d },
        )
        assertEquals(setOf("Sports"), s.hiddenGenres)
        assertEquals(emptySet<String>(), s.hiddenSources)   // the other string-sets unaffected
        assertEquals(emptySet<String>(), s.hiddenLeagues)
    }
}
