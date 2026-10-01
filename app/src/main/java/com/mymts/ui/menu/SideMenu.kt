package com.mymts.ui.menu

/*
 * The side menu's rows, focus and activation decisions, kept as plain Kotlin so they
 * are unit-testable (the Compose layer is not). [MenuOverlay] renders
 * [sideMenuSections] in order and binds [SideMenuFocus] to its focus requesters;
 * WallScreen binds [WallRefreshActions] to the wall's refresh signals.
 */

/** One focusable row of the side menu. */
sealed interface SideMenuRow {
    /** The full reconnect of every video tile plus one reload of each radar tile. */
    data object RefreshAllFeeds : SideMenuRow

    /** One wall tile's slot; SELECT opens its controls. */
    data class Slot(val slot: SlotRow) : SideMenuRow

    data object Preset : SideMenuRow

    data object Settings : SideMenuRow

    /** Jump every tile to live, reconnecting only the dead ones. */
    data object ResyncAllFeeds : SideMenuRow
}

/**
 * The side menu's rows by section, top to bottom. "Refresh all feeds" sits alone above
 * the CHANNELS list; the CHANNELS and WALL sections keep their rows in their existing
 * order.
 */
data class SideMenuSections(
    val top: List<SideMenuRow>,
    val channels: List<SideMenuRow.Slot>,
    val wall: List<SideMenuRow>,
) {
    /** Every row, in the order D-pad DOWN reaches them. */
    val rows: List<SideMenuRow> get() = top + channels + wall
}

fun sideMenuSections(slotRows: List<SlotRow>): SideMenuSections = SideMenuSections(
    top = listOf(SideMenuRow.RefreshAllFeeds),
    channels = slotRows.map { SideMenuRow.Slot(it) },
    wall = listOf(SideMenuRow.Preset, SideMenuRow.Settings, SideMenuRow.ResyncAllFeeds),
)

/** The row the side menu hands focus to. */
enum class SideMenuFocusTarget { RefreshAllFeeds, FirstSlot }

/**
 * Where the side menu puts focus. Every opening, first or repeated, lands on "Refresh
 * all feeds". A sub-overlay (slot controls, settings, a picker) dismissing back to a
 * menu that has already landed keeps its existing landing, the first slot row.
 *
 * [MenuOverlay] asks [targetFor] each time [MenuState.openings] or the sub-overlay flag
 * changes, requests focus on the row it returns, then calls [landed]. Its effect must be
 * keyed on exactly those two values: [targetFor] reads any relaunch with the flag down
 * and the opening landed as a sub-overlay closing, so another key would send focus to
 * the first slot row whenever it changed. A claim cut short before [landed] leaves the
 * opening unlanded, so the next change still lands on "Refresh all feeds".
 */
class SideMenuFocus {
    private var landedOpening = NOT_LANDED

    /**
     * The row to focus after [openings] or [subOverlayActive] changed, or null while a
     * sub-overlay owns focus. An opening not yet landed goes to "Refresh all feeds" — a
     * menu that opened beneath a sub-overlay included, once that sub-overlay clears.
     * Otherwise the sub-overlay flag has just cleared back to the menu: the first slot row.
     */
    fun targetFor(openings: Int, subOverlayActive: Boolean): SideMenuFocusTarget? = when {
        subOverlayActive -> null
        openings != landedOpening -> SideMenuFocusTarget.RefreshAllFeeds
        else -> SideMenuFocusTarget.FirstSlot
    }

    /** Records that the focus claim for opening number [openings] was made. */
    fun landed(openings: Int) {
        landedOpening = openings
    }

    private companion object {
        const val NOT_LANDED = -1
    }
}

/**
 * The wall effects the all-tile refresh rows trigger. WallScreen binds each one to a
 * wall signal: the existing reconnect, resync and menu-close paths, and the radar
 * reload request that "Refresh all feeds" adds. None of them writes a setting.
 */
interface WallRefreshActions {
    /** The full reconnect of every video tile, recovery reset included. */
    fun reconnectAllVideo()

    /** One immediate reload of each radar tile, through its timed reload. */
    fun reloadAllRadar()

    /** Jump every tile to live, reconnecting only the dead ones. */
    fun resyncAll()

    /** Close the side menu back to the wall. */
    fun closeMenu()
}

/**
 * The side menu's "Refresh all feeds": reconnect every video tile, reload each radar
 * tile once, then close the menu. It shows no message of its own (each video tile
 * shows its CONNECTING badge) and has no cooldown.
 */
fun WallRefreshActions.refreshAllFeeds() {
    reconnectAllVideo()
    reloadAllRadar()
    closeMenu()
}

/** The side menu's "Resync all feeds", unchanged: resync every tile, then close. */
fun WallRefreshActions.resyncAllFeeds() {
    resyncAll()
    closeMenu()
}

/** WALL SETTINGS "Refresh all video", unchanged: the video reconnect only; WALL SETTINGS stays open. */
fun WallRefreshActions.refreshAllVideo() {
    reconnectAllVideo()
}
