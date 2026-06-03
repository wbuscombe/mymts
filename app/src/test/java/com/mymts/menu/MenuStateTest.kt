package com.mymts.menu

import com.mymts.ui.menu.MenuState
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
}
