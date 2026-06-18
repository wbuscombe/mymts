package com.mymts.data.lineup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the PURE active-preset resolver — the no-regression guarantee: a
 * fresh install (no stored value) and a cleared/blank pref both resolve to
 * the default "news" preset, so the wall looks identical to before presets
 * existed until the operator switches.
 */
class LineupStoreActivePresetTest {

    @Test fun `absent value resolves to the default news preset`() {
        assertEquals(LineupStore.DEFAULT_PRESET, LineupStore.resolveActivePreset(null))
        assertEquals("news", LineupStore.resolveActivePreset(null))
    }

    @Test fun `blank value resolves to the default`() {
        assertEquals(LineupStore.DEFAULT_PRESET, LineupStore.resolveActivePreset(""))
        assertEquals(LineupStore.DEFAULT_PRESET, LineupStore.resolveActivePreset("   "))
    }

    @Test fun `a stored preset id is returned as-is`() {
        assertEquals("nature", LineupStore.resolveActivePreset("nature"))
        assertEquals("space", LineupStore.resolveActivePreset("space"))
    }
}
