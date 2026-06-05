package com.mymts.ui.nav

/**
 * Pure focus-transition logic for the wall. No Compose, no Android —
 * the whole navigation model is a single function the call site
 * invokes on every D-pad event. **Every transition is unit-tested in
 * `WallFocusModelTest`** — focus bugs are the worst TV-UX failures, so
 * this layer's job is to make them visible from the test suite, not
 * from the box.
 *
 * Design properties the tests pin:
 *   - **No traps.** Every zone is reachable from every other zone.
 *     Every zone is exitable.
 *   - **Spatial sense.** LEFT/RIGHT/UP/DOWN match the layout's
 *     geometry: UP from grid/feed reaches the ticker; LEFT from grid's
 *     leftmost column lands on the feed at its preserved index; RIGHT
 *     from feed enters the grid at slot 0; DOWN from the ticker
 *     returns to whichever zone the operator came up from
 *     (`lastLowerZone`), not always the feed.
 *   - **State preservation.** Switching zones does NOT clobber the
 *     other zone's `Index`. Coming back lands where you left off.
 *   - **BACK is always sane.** Within a zone with expansion state,
 *     BACK collapses first; from a non-expanded state, BACK bubbles
 *     to the caller (which handles app-level back like "close menu"
 *     or "exit kiosk-confirm").
 *
 * The model emits a [NavResult] — a small sum type describing what the
 * call site should do. Most results are pure state moves; a couple are
 * "side-effect" requests (open menu, open the slot controls overlay)
 * that the caller dispatches into its own state holders.
 */
object WallFocusModel {

    /**
     * Apply [intent] against [focus] given the wall's current
     * dimensions. The function is pure — same inputs, same output —
     * which is what lets the test suite cover the whole transition
     * graph.
     *
     * @param focus the current focus state.
     * @param intent the operator's D-pad / SELECT / BACK action.
     * @param feedItemCount how many items the feed currently shows.
     *   May be 0 (empty feed); transitions handle that gracefully.
     * @param gridTileCount how many tiles the grid has — typically
     *   `BuildConfig.DEFAULT_MAX_TILES` (4 by default). May be 0 (no
     *   tiles); transitions handle that gracefully.
     * @param gridColumns the grid's column count (typically 2 for
     *   N=4). Drives row/column math.
     */
    fun apply(
        focus: WallFocus,
        intent: NavIntent,
        feedItemCount: Int,
        gridTileCount: Int,
        gridColumns: Int,
    ): NavResult {
        require(feedItemCount >= 0)
        require(gridTileCount >= 0)
        require(gridColumns >= 1)

        // SELECT and BACK are independent of the DPad-direction graph.
        if (intent == NavIntent.Select) return applySelect(focus)
        if (intent == NavIntent.Back) return applyBack(focus)

        return when (focus.active) {
            WallZone.Ticker -> applyTicker(focus, intent, feedItemCount, gridTileCount)
            WallZone.Feed -> applyFeed(focus, intent, feedItemCount, gridTileCount)
            WallZone.Grid -> applyGrid(focus, intent, feedItemCount, gridTileCount, gridColumns)
        }
    }

    // ============== SELECT semantics, per zone ==============

    private fun applySelect(focus: WallFocus): NavResult = when (focus.active) {
        // Ticker SELECT pauses / resumes the marquee scroll. Useful from
        // 10 ft when a value the operator wants to read is sliding off
        // the right edge — the in-zone action with the lowest UX cost
        // ("press OK to stop, again to start") and zero player or
        // network side-effects.
        WallZone.Ticker -> NavResult.Focus(focus.copy(tickerPaused = !focus.tickerPaused))
        WallZone.Feed -> NavResult.Focus(focus.copy(feedExpanded = !focus.feedExpanded))
        WallZone.Grid -> NavResult.OpenSlotControls(focus.gridIndex)
    }

    // ============== BACK semantics ==============

    private fun applyBack(focus: WallFocus): NavResult {
        // Expanded feed item collapses first; only un-nested back
        // bubbles. This mirrors the Stage 5 menu/picker BACK habit:
        // BACK closes the deepest overlay, not the whole stack at once.
        if (focus.active == WallZone.Feed && focus.feedExpanded) {
            return NavResult.Focus(focus.copy(feedExpanded = false))
        }
        return NavResult.BackBubble
    }

    // ============== TICKER ==============

    private fun applyTicker(
        focus: WallFocus,
        intent: NavIntent,
        feedItemCount: Int,
        gridTileCount: Int,
    ): NavResult = when (intent) {
        NavIntent.Down -> {
            // Return to whichever zone we came up from. If the lower
            // zone is empty, fall through to the other lower zone (no
            // trap — the operator should always escape the ticker).
            val target = when {
                focus.lastLowerZone == WallZone.Feed && feedItemCount > 0 -> WallZone.Feed
                focus.lastLowerZone == WallZone.Grid && gridTileCount > 0 -> WallZone.Grid
                feedItemCount > 0 -> WallZone.Feed
                gridTileCount > 0 -> WallZone.Grid
                else -> return NavResult.Stay  // genuinely nowhere to go — empty wall
            }
            NavResult.Focus(focus.copy(active = target))
        }
        NavIntent.Up -> NavResult.Stay  // already at the top of the screen
        NavIntent.Left -> NavResult.OpenMenu  // ticker's left-edge gesture opens the menu
        NavIntent.Right -> NavResult.Stay  // ticker is one focus position; no intra-navigation in v1
        else -> NavResult.Stay
    }

    // ============== FEED ==============

    private fun applyFeed(
        focus: WallFocus,
        intent: NavIntent,
        feedItemCount: Int,
        gridTileCount: Int,
    ): NavResult = when (intent) {
        NavIntent.Up -> {
            // If at top item OR feed is empty: go to ticker. Otherwise
            // move within the feed.
            if (focus.feedIndex <= 0 || feedItemCount == 0) {
                NavResult.Focus(focus.copy(active = WallZone.Ticker, lastLowerZone = WallZone.Feed))
            } else {
                NavResult.Focus(focus.copy(feedIndex = focus.feedIndex - 1, feedExpanded = false))
            }
        }
        NavIntent.Down -> {
            // Move within the feed (clamp at last). If feed is empty,
            // stay (no lower-zone fallback — DOWN from feed never
            // jumps to grid because that's a spatially-orthogonal move).
            val next = (focus.feedIndex + 1).coerceAtMost((feedItemCount - 1).coerceAtLeast(0))
            if (next == focus.feedIndex) NavResult.Stay
            else NavResult.Focus(focus.copy(feedIndex = next, feedExpanded = false))
        }
        NavIntent.Right -> {
            // Enter the grid. Land on the grid's preserved index so a
            // FEED → GRID → FEED round-trip returns to the same feed item.
            if (gridTileCount == 0) NavResult.Stay
            else NavResult.Focus(focus.copy(active = WallZone.Grid, feedExpanded = false))
        }
        NavIntent.Left -> NavResult.OpenMenu  // feed's left edge = menu trigger
        else -> NavResult.Stay
    }

    // ============== GRID ==============

    private fun applyGrid(
        focus: WallFocus,
        intent: NavIntent,
        feedItemCount: Int,
        gridTileCount: Int,
        gridColumns: Int,
    ): NavResult {
        val row = focus.gridIndex / gridColumns
        val col = focus.gridIndex % gridColumns
        return when (intent) {
            NavIntent.Up -> {
                if (row == 0) {
                    NavResult.Focus(focus.copy(active = WallZone.Ticker, lastLowerZone = WallZone.Grid))
                } else {
                    NavResult.Focus(focus.copy(gridIndex = focus.gridIndex - gridColumns))
                }
            }
            NavIntent.Down -> {
                val candidateIndex = focus.gridIndex + gridColumns
                if (candidateIndex >= gridTileCount) NavResult.Stay
                else NavResult.Focus(focus.copy(gridIndex = candidateIndex))
            }
            NavIntent.Left -> {
                if (col == 0) {
                    // Spill back into the feed at its preserved index.
                    // If feed is empty, stay rather than trapping the
                    // operator in column 0.
                    if (feedItemCount == 0) NavResult.Stay
                    else NavResult.Focus(focus.copy(active = WallZone.Feed))
                } else {
                    NavResult.Focus(focus.copy(gridIndex = focus.gridIndex - 1))
                }
            }
            NavIntent.Right -> {
                val candidateIndex = focus.gridIndex + 1
                val candidateRow = candidateIndex / gridColumns
                // Stay on the same row — RIGHT must not wrap to the
                // next row, that's a focus-trap-adjacent surprise.
                if (candidateRow != row || candidateIndex >= gridTileCount) NavResult.Stay
                else NavResult.Focus(focus.copy(gridIndex = candidateIndex))
            }
            else -> NavResult.Stay
        }
    }
}

/**
 * What the operator pressed. Translates raw `Key` events at the call
 * site so the model never has to think about Compose's `Key` type.
 */
enum class NavIntent { Up, Down, Left, Right, Select, Back }

/**
 * What [WallFocusModel.apply] decided. Most results are pure focus
 * moves; a couple ask the call site to dispatch a side-effect (open
 * the menu overlay, open the per-slot controls overlay).
 */
sealed class NavResult {
    /** Stay — no state change. Used when the intent has nowhere to go. */
    data object Stay : NavResult()

    /** Adopt this new focus state. */
    data class Focus(val focus: WallFocus) : NavResult()

    /** Open the wall's side menu (delegated to `MenuState.open()`). */
    data object OpenMenu : NavResult()

    /** Open the per-tile controls overlay for [slotIndex]. */
    data class OpenSlotControls(val slotIndex: Int) : NavResult()

    /** No deeper state to collapse; let the call site handle BACK. */
    data object BackBubble : NavResult()
}
