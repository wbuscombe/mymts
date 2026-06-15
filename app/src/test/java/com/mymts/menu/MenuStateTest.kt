package com.mymts.menu

import com.mymts.ui.menu.BackOutcome
import com.mymts.ui.menu.MenuState
import com.mymts.ui.menu.menuBackOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural guards for the wall menu's visibility + sub-overlay state.
 *
 * Focus inside the menu is delegated to Compose's standard focus system,
 * so these tests cover only what [MenuState] itself owns: open/close,
 * toggle, and the pending sub-overlay payload (currently just the
 * slot-picker target).
 */
class MenuStateTest {

    @Test fun `initial state is closed with no pending selection`() {
        val s = MenuState()
        assertFalse(s.isOpen)
        assertNull(s.pendingSelection)
    }

    @Test fun `open flips isOpen but leaves pendingSelection untouched`() {
        val s = MenuState()
        s.open()
        assertTrue(s.isOpen)
        assertNull(s.pendingSelection)
    }

    @Test fun `open is idempotent`() {
        val s = MenuState()
        s.open()
        s.open()
        assertTrue(s.isOpen)
    }

    @Test fun `close resets isOpen AND clears pendingSelection`() {
        val s = MenuState()
        s.open()
        s.pickSlot(2)
        s.close()
        assertFalse(s.isOpen)
        assertNull(
            "close must clear any sub-overlay so a re-open does not restore it",
            s.pendingSelection,
        )
    }

    @Test fun `toggle flips isOpen and clears pendingSelection on close`() {
        val s = MenuState()
        s.toggle()
        assertTrue(s.isOpen)
        s.pickSlot(1)
        s.toggle()
        assertFalse(s.isOpen)
        assertNull(s.pendingSelection)
    }

    @Test fun `pickSlot sets the SlotPicker payload`() {
        val s = MenuState()
        s.open()
        s.pickSlot(3)
        val sel = s.pendingSelection
        assertTrue(sel is MenuState.PendingSelection.SlotPicker)
        assertEquals(3, (sel as MenuState.PendingSelection.SlotPicker).slotIndex)
    }

    @Test fun `pickSlot replaces a prior selection (only one sub-overlay at a time)`() {
        val s = MenuState()
        s.open()
        s.pickSlot(0)
        s.pickSlot(2)
        val sel = s.pendingSelection as MenuState.PendingSelection.SlotPicker
        assertEquals(2, sel.slotIndex)
    }

    @Test fun `dismissSelection clears the picker but leaves the menu open`() {
        val s = MenuState()
        s.open()
        s.pickSlot(0)
        s.dismissSelection()
        assertTrue("dismissing the picker keeps the menu open", s.isOpen)
        assertNull(s.pendingSelection)
    }

    // ---- coherent BACK pop (Part A — BACK never escapes to the launcher) ----

    @Test fun `back pops the channel picker UP to its slot controls`() {
        // picker → controls of the SAME slot (one level up, menu state preserved).
        assertEquals(
            BackOutcome.ToSlotControls(2),
            menuBackOutcome(isOpen = false, pending = MenuState.PendingSelection.SlotPicker(2)),
        )
        assertEquals(
            BackOutcome.ToSlotControls(2),
            menuBackOutcome(isOpen = true, pending = MenuState.PendingSelection.SlotPicker(2)),
        )
    }

    @Test fun `back dismisses any other sub-overlay`() {
        for (p in listOf(
            MenuState.PendingSelection.SlotControls(0),
            MenuState.PendingSelection.Settings,
            MenuState.PendingSelection.SourceFilter,
            MenuState.PendingSelection.SportsLeagueFilter,
        )) {
            assertEquals("$p → dismiss", BackOutcome.DismissOverlay, menuBackOutcome(isOpen = true, pending = p))
            assertEquals("$p → dismiss (from tile)", BackOutcome.DismissOverlay, menuBackOutcome(isOpen = false, pending = p))
        }
    }

    @Test fun `back closes the side menu when only it is open`() {
        assertEquals(BackOutcome.CloseMenu, menuBackOutcome(isOpen = true, pending = null))
    }

    @Test fun `back does NOT consume at the bare wall (root back may exit)`() {
        // The ONLY state where BACK is not consumed — so a stray BACK inside any
        // overlay can never reach the Activity, but root BACK still backgrounds.
        assertEquals(BackOutcome.Pass, menuBackOutcome(isOpen = false, pending = null))
    }
}
