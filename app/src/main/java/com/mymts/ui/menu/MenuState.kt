package com.mymts.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Holder for the wall's side-menu interaction state.
 *
 * Stage 5 introduces a WyzeGrid-style left-side panel that the operator
 * opens with the D-pad to control the wall's channel lineup. This class
 * is the small mutable state owner — when [isOpen] flips, the overlay
 * slides in over the wall; [pendingSelection] carries the row the
 * operator picked (so a sub-overlay like the channel picker can read it).
 *
 * Focus inside the menu is delegated to Compose's standard focus system
 * (each row is a focusable composable; D-pad UP/DOWN navigates between
 * them, SELECT/CENTER triggers the row's `onClick`). This object holds
 * only the visibility + sub-overlay state.
 */
class MenuState {
    var isOpen: Boolean by mutableStateOf(false)
        private set

    var pendingSelection: PendingSelection? by mutableStateOf(null)
        private set

    fun open() {
        isOpen = true
    }

    fun close() {
        isOpen = false
        pendingSelection = null
    }

    fun toggle() {
        if (isOpen) close() else open()
    }

    fun pickSlot(slotIndex: Int) {
        pendingSelection = PendingSelection.SlotPicker(slotIndex)
    }

    fun dismissSelection() {
        pendingSelection = null
    }

    /**
     * What kind of secondary overlay the focused row triggers when the
     * operator presses CENTER/SELECT. Stage 5 only uses [SlotPicker];
     * later stages (settings rows, layout config) add new variants.
     */
    sealed class PendingSelection {
        data class SlotPicker(val slotIndex: Int) : PendingSelection()
    }
}

@Composable
fun rememberMenuState(): MenuState = remember { MenuState() }
