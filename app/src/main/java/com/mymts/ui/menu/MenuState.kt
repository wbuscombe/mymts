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

    /**
     * Open the tile-controls overlay for [slotIndex] — the small actions
     * popup that lets the operator change the channel, toggle audio,
     * toggle captions, etc. SELECT on a slot row in the side panel
     * lands here; the controls overlay then routes "Channel" to
     * [pickSlot] which opens the channel picker.
     */
    fun openControls(slotIndex: Int) {
        pendingSelection = PendingSelection.SlotControls(slotIndex)
    }

    fun pickSlot(slotIndex: Int) {
        pendingSelection = PendingSelection.SlotPicker(slotIndex)
    }

    /**
     * Open the wall settings overlay — the UX & Config chapter's new
     * surface for the operator's feed-width / feed-font / feed-side
     * knobs. Shows over the menu (the menu stays open underneath so
     * BACK returns to the side panel rather than to the bare wall).
     */
    fun openSettings() {
        pendingSelection = PendingSelection.Settings
    }

    fun dismissSelection() {
        pendingSelection = null
    }

    /**
     * What kind of secondary overlay the focused row triggers when the
     * operator presses CENTER/SELECT.
     *
     * Stage 5 introduced [SlotPicker]; this commit adds [SlotControls]
     * as the new SELECT-on-slot landing — it's the small actions
     * popup that fans out to the channel picker, the audio toggle, and
     * the captions toggle. SlotPicker is now reached **through** the
     * controls overlay's "Channel" action.
     */
    sealed class PendingSelection {
        data class SlotControls(val slotIndex: Int) : PendingSelection()
        data class SlotPicker(val slotIndex: Int) : PendingSelection()
        data object Settings : PendingSelection()
    }
}

@Composable
fun rememberMenuState(): MenuState = remember { MenuState() }
