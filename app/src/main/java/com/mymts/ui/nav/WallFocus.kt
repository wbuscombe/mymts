package com.mymts.ui.nav

/**
 * The wall's global D-pad focus state.
 *
 * The whole-wall navigation chapter (2026-06-04, item A in the upstairs
 * usage feedback) gives every zone — feed pane, video grid, ticker,
 * menu — a single coherent focus model. This data class is the
 * **entire** UI state of where focus lives; transitions are computed
 * in [WallFocusModel] as a pure function so navigation is unit-testable
 * without Compose.
 *
 * **Why this design:**
 * - One `active` zone at a time; focus indicators are derived from
 *   reading this state, never from racing input events.
 * - `feedIndex` and `gridIndex` are *preserved* across zone transitions
 *   so the operator returning to a zone lands where they left off
 *   (the sibling-app "where I was, not where you think I should be"
 *   habit — the menu and channel picker already follow it).
 * - `lastLowerZone` is the "where did I come from when I went UP into
 *   the ticker?" memo, so going DOWN from the ticker returns to the
 *   right place rather than always to the feed.
 * - `feedExpanded` is in-place state (per the operator's
 *   safe-summary-only contract — `04-TECHNICAL-APPROACH.md §5`); when
 *   true, the focused feed item is showing its expanded plain-text
 *   summary instead of its collapsed-row form.
 *
 * Menu visibility is **orthogonal** to wall focus — it lives in
 * [com.mymts.ui.menu.MenuState] and overlays the wall. When the menu
 * is open, wall focus is preserved but inactive; closing the menu
 * returns control to whichever zone was last focused.
 */
data class WallFocus(
    val active: WallZone,
    val feedIndex: Int = 0,
    val gridIndex: Int = 0,
    val lastLowerZone: WallZone = WallZone.Feed,
    val feedExpanded: Boolean = false,
    val tickerPaused: Boolean = false,
) {
    init {
        require(feedIndex >= 0) { "feedIndex must be >= 0 (was $feedIndex)" }
        require(gridIndex >= 0) { "gridIndex must be >= 0 (was $gridIndex)" }
        require(lastLowerZone != WallZone.Ticker) {
            "lastLowerZone cannot be Ticker — it's the memo of where we " +
                "came FROM when we entered the ticker"
        }
    }

    companion object {
        /**
         * Default focus on launch — feed item 0. Feed is the leftmost
         * zone, leading from the room's natural left-to-right
         * scanning direction; landing there means the first thing the
         * D-pad acts on is the freshest news headline.
         */
        val Initial: WallFocus = WallFocus(active = WallZone.Feed)
    }
}

/**
 * The three navigable wall zones. **Menu is not here** — it's an
 * overlay that captures focus orthogonally (see [com.mymts.ui.menu.MenuState]).
 *
 * Layout reminder (matches `WallScreen`'s composition):
 *
 *   +-------------------------------+
 *   |             TICKER            |   thin top strip
 *   +-----+-------------------------+
 *   |     |                         |
 *   | FEED|         GRID            |   ~28 % feed, rest grid
 *   |     |                         |
 *   +-----+-------------------------+
 *
 * Movements between zones in [WallFocusModel.apply] should match this
 * spatial sense — moving feels like moving around the screen, not
 * cycling a hidden list. UP from anywhere reaches the ticker, LEFT
 * from the grid's left column lands back on the feed, etc.
 */
enum class WallZone { Ticker, Feed, Grid }
