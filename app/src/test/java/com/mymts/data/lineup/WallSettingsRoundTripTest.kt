package com.mymts.data.lineup

import android.content.Context
import android.content.SharedPreferences
import com.mymts.data.settings.FeedFontScale
import com.mymts.data.settings.FeedRecency
import com.mymts.data.settings.FeedSide
import com.mymts.data.settings.FeedWidth
import com.mymts.data.settings.Overscan
import com.mymts.data.settings.UiScale
import com.mymts.data.settings.WallSettings
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Closes review finding ARCH-3 — the WallSettings persistence round-trip.
 *
 * PROTECTED INVARIANT: an operator's tuned wall must survive an
 * `updateWallSettings` write + reload UNCHANGED. In particular the locked
 * panel-fit values (Fit scale, Vertical stretch, Overscan, Position) must
 * NEVER silently reset on an update. A forgotten `KEY_` in the all-absent
 * guard, a typo'd key string, or a missing put/get pair would silently
 * desync exactly one field — invisible to a field-by-field test that only
 * exercises one knob at a time. This test sets EVERY field to a clearly
 * non-default value, runs the real persist path, reloads via the real
 * resolver, and asserts whole-object equality.
 *
 * Hermetic: the SharedPreferences fake is an in-memory backing map driven
 * through mockk (the test framework already on the app's classpath) — the
 * real production code paths [LineupStore.updateWallSettings] (write) and
 * [LineupStore.resolveWallSettings] (read) do the work, no Android runtime.
 */
class WallSettingsRoundTripTest {

    /**
     * An in-memory SharedPreferences fake. The [Editor] writes straight
     * into [store]; the getters read from it. This is the exact key→value
     * map the on-device SharedPreferences would hold, so driving the real
     * `updateWallSettings`/`resolveWallSettings` over it reproduces the
     * on-device round trip without an Android context.
     */
    private class FakePrefs {
        val store = mutableMapOf<String, Any?>()

        fun build(): SharedPreferences {
            val editor = mockk<SharedPreferences.Editor>()
            every { editor.putInt(any(), any()) } answers {
                store[firstArg()] = secondArg<Int>(); editor
            }
            every { editor.putBoolean(any(), any()) } answers {
                store[firstArg()] = secondArg<Boolean>(); editor
            }
            every { editor.putString(any(), any()) } answers {
                store[firstArg()] = secondArg<String?>(); editor
            }
            every { editor.remove(any()) } answers {
                store.remove(firstArg<String>()); editor
            }
            every { editor.apply() } answers { }

            val prefs = mockk<SharedPreferences>()
            every { prefs.edit() } returns editor
            every { prefs.contains(any()) } answers { store.containsKey(firstArg<String>()) }
            every { prefs.getInt(any(), any()) } answers {
                store[firstArg()] as? Int ?: secondArg()
            }
            every { prefs.getBoolean(any(), any()) } answers {
                store[firstArg()] as? Boolean ?: secondArg()
            }
            every { prefs.getString(any(), any()) } answers {
                store[firstArg()] as? String ?: secondArg()
            }
            return prefs
        }
    }

    private fun newStore(prefs: SharedPreferences): LineupStore {
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return LineupStore(context)
    }

    /**
     * A WallSettings with EVERY field set to a value clearly different from
     * [WallSettings.Default], each within its on-read clamp so nothing is
     * lost to clamping (that would mask a real desync). Covers: every enum
     * knob, both grid dims, both ticker speeds, both string-set denylists,
     * both booleans, and all four locked panel-fit fields.
     */
    private val tuned = WallSettings(
        feedWidth = FeedWidth.Wide,            // default Default
        feedFontScale = FeedFontScale.Large,   // default Default
        feedSide = FeedSide.Right,             // default Left
        hiddenSources = setOf("espn", "cbs-sports-hq"), // default empty
        feedRecency = FeedRecency.Day,         // default All
        hiddenLeagues = setOf("nhl", "mlb"),   // default empty
        tickerNewsEnabled = true,              // default false
        uiScale = UiScale.Compact,             // default Default
        overscan = Overscan.Large,             // PANEL-FIT: default Medium, != None
        offsetXDp = 24,                        // PANEL-FIT (position): default 0, within ±64
        offsetYDp = -16,                       // PANEL-FIT (position): default 0
        calibrationBorder = true,              // default false
        fitScalePct = 70,                      // PANEL-FIT (fit scale): default 100, within 50..100
        fitStretchYPct = 118,                  // PANEL-FIT (vertical stretch): default 100, within 100..130
        gridRows = 3,                          // default 2, within 1..3
        gridCols = 1,                          // default 2, within 1..3
        tickerScrollPct = 160,                 // default 100, within 40..200
        tickerFlipPct = 60,                    // default 100, within 40..200
    )

    @Test fun `every field round-trips through persist then resolve`() {
        // Sanity: the tuned value really differs from Default on the knobs
        // we care about — guards against accidentally testing the default.
        assert(tuned != WallSettings.Default)

        val fake = FakePrefs()
        val writer = newStore(fake.build())

        // PERSIST via the real production write path.
        writer.updateWallSettings(tuned)

        // RELOAD via the real production resolver over the SAME backing map.
        val resolved = LineupStore.resolveWallSettings(
            contains = fake.store::containsKey,
            getInt = { key, d -> fake.store[key] as? Int ?: d },
            getStringSet = { key ->
                (fake.store[key] as? String)?.let { LineupStore.decodeStringSet(it) } ?: emptySet()
            },
            getBoolean = { key, d -> fake.store[key] as? Boolean ?: d },
        )

        // Whole-object equality: every field survived the write/read.
        assertEquals(tuned, resolved)
    }

    @Test fun `locked panel-fit values survive the round trip (ARCH-3 invariant)`() {
        val fake = FakePrefs()
        val writer = newStore(fake.build())
        writer.updateWallSettings(tuned)

        val resolved = LineupStore.resolveWallSettings(
            contains = fake.store::containsKey,
            getInt = { key, d -> fake.store[key] as? Int ?: d },
            getStringSet = { key ->
                (fake.store[key] as? String)?.let { LineupStore.decodeStringSet(it) } ?: emptySet()
            },
            getBoolean = { key, d -> fake.store[key] as? Boolean ?: d },
        )

        // The protected invariant, called out explicitly: the four locked
        // panel-fit values must equal what was written — never silently
        // reset to a default on an update.
        assertEquals("Fit scale must not silently reset", 70, resolved.fitScalePct)
        assertEquals("Vertical stretch must not silently reset", 118, resolved.fitStretchYPct)
        assertEquals("Overscan must not silently reset", Overscan.Large, resolved.overscan)
        assertEquals("Position X must not silently reset", 24, resolved.offsetXDp)
        assertEquals("Position Y must not silently reset", -16, resolved.offsetYDp)
    }
}
