package com.mymts.nav

import com.mymts.data.settings.FeedSide
import com.mymts.ui.nav.NavIntent
import com.mymts.ui.nav.NavResult
import com.mymts.ui.nav.WallFocus
import com.mymts.ui.nav.WallFocusModel
import com.mymts.ui.nav.WallZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pin every transition in the wall's global focus model.
 *
 * Focus bugs are the worst TV-UX failures: a trap, an off-by-one, a
 * silent stay where the operator expected movement. This suite covers
 * every zone × every D-pad direction × SELECT × BACK, plus the
 * no-trap invariants every zone is reachable + exitable, and the
 * spatial-sense invariants UP from feed reaches the ticker, LEFT from
 * grid's leftmost column lands on the feed, etc.
 */
class WallFocusModelTest {

    private val FEED_COUNT = 30
    private val GRID_COUNT = 4
    private val GRID_COLS = 2

    private fun WallFocus.apply(intent: NavIntent): NavResult = WallFocusModel.apply(
        focus = this,
        intent = intent,
        feedItemCount = FEED_COUNT,
        gridTileCount = GRID_COUNT,
        gridColumns = GRID_COLS,
    )

    // ============== Initial state ==============

    @Test fun `initial focus is on Feed at index 0`() {
        val f = WallFocus.Initial
        assertEquals(WallZone.Feed, f.active)
        assertEquals(0, f.feedIndex)
        assertEquals(0, f.gridIndex)
        assertEquals(false, f.feedExpanded)
    }

    // ============== Feed ==============

    @Test fun `feed DOWN moves to next item, collapses expanded`() {
        val r = WallFocus(active = WallZone.Feed, feedIndex = 5, feedExpanded = true).apply(NavIntent.Down)
        val next = (r as NavResult.Focus).focus
        assertEquals(6, next.feedIndex)
        assertEquals(false, next.feedExpanded)
    }

    @Test fun `feed DOWN at last item stays`() {
        val r = WallFocus(active = WallZone.Feed, feedIndex = FEED_COUNT - 1).apply(NavIntent.Down)
        assertTrue("DOWN at last feed item must stay, not wrap", r is NavResult.Stay)
    }

    @Test fun `feed UP at index 0 goes to TICKER and remembers Feed as lastLowerZone`() {
        val r = WallFocus(active = WallZone.Feed, feedIndex = 0).apply(NavIntent.Up)
        val next = (r as NavResult.Focus).focus
        assertEquals(WallZone.Ticker, next.active)
        assertEquals(WallZone.Feed, next.lastLowerZone)
        assertEquals("feedIndex preserved on zone change", 0, next.feedIndex)
    }

    @Test fun `feed UP from middle moves to prev item, collapses expanded`() {
        val r = WallFocus(active = WallZone.Feed, feedIndex = 5, feedExpanded = true).apply(NavIntent.Up)
        val next = (r as NavResult.Focus).focus
        assertEquals(4, next.feedIndex)
        assertEquals(false, next.feedExpanded)
    }

    @Test fun `feed RIGHT enters Grid, preserves feedIndex, collapses expanded`() {
        val r = WallFocus(active = WallZone.Feed, feedIndex = 7, feedExpanded = true).apply(NavIntent.Right)
        val next = (r as NavResult.Focus).focus
        assertEquals(WallZone.Grid, next.active)
        assertEquals("feedIndex preserved across zones", 7, next.feedIndex)
        assertEquals(false, next.feedExpanded)
    }

    @Test fun `feed LEFT opens the menu`() {
        val r = WallFocus(active = WallZone.Feed, feedIndex = 0).apply(NavIntent.Left)
        assertEquals(NavResult.OpenMenu, r)
    }

    @Test fun `feed SELECT toggles feedExpanded in place`() {
        val collapsed = WallFocus(active = WallZone.Feed, feedIndex = 3, feedExpanded = false)
        val expanded = (collapsed.apply(NavIntent.Select) as NavResult.Focus).focus
        assertEquals(true, expanded.feedExpanded)
        assertEquals("feedIndex unchanged on expand", 3, expanded.feedIndex)
        val backToCollapsed = (expanded.apply(NavIntent.Select) as NavResult.Focus).focus
        assertEquals(false, backToCollapsed.feedExpanded)
    }

    // ============== Grid ==============

    @Test fun `grid DOWN from top row moves into bottom row at same column`() {
        // 2x2 grid: index 0 (row0,col0) DOWN -> index 2 (row1,col0)
        val r = WallFocus(active = WallZone.Grid, gridIndex = 0).apply(NavIntent.Down)
        assertEquals(2, (r as NavResult.Focus).focus.gridIndex)
    }

    @Test fun `grid DOWN from last row stays (no wrap)`() {
        val r = WallFocus(active = WallZone.Grid, gridIndex = 3).apply(NavIntent.Down)
        assertTrue("DOWN from last grid row must stay, not wrap", r is NavResult.Stay)
    }

    @Test fun `grid UP from top row goes to TICKER and remembers Grid`() {
        val r = WallFocus(active = WallZone.Grid, gridIndex = 1).apply(NavIntent.Up)
        val next = (r as NavResult.Focus).focus
        assertEquals(WallZone.Ticker, next.active)
        assertEquals(WallZone.Grid, next.lastLowerZone)
        assertEquals("gridIndex preserved", 1, next.gridIndex)
    }

    @Test fun `grid UP from row 1 moves into row 0 same column`() {
        // 2x2: index 2 (row1, col0) UP -> index 0
        val r = WallFocus(active = WallZone.Grid, gridIndex = 2).apply(NavIntent.Up)
        assertEquals(0, (r as NavResult.Focus).focus.gridIndex)
    }

    @Test fun `grid RIGHT moves within row, never wraps to next row`() {
        val r = WallFocus(active = WallZone.Grid, gridIndex = 0).apply(NavIntent.Right)
        assertEquals(1, (r as NavResult.Focus).focus.gridIndex)
    }

    @Test fun `grid RIGHT at last column of row stays`() {
        // 2x2: index 1 is row0 last column; RIGHT must NOT wrap to 2.
        val r = WallFocus(active = WallZone.Grid, gridIndex = 1).apply(NavIntent.Right)
        assertTrue("RIGHT at row's last column must stay, not wrap", r is NavResult.Stay)
    }

    @Test fun `grid LEFT in column 0 spills back to Feed at preserved feedIndex`() {
        val r = WallFocus(
            active = WallZone.Grid, gridIndex = 2, feedIndex = 7,
        ).apply(NavIntent.Left)
        val next = (r as NavResult.Focus).focus
        assertEquals(WallZone.Feed, next.active)
        assertEquals("feedIndex preserved across the round-trip", 7, next.feedIndex)
        assertEquals("gridIndex preserved", 2, next.gridIndex)
    }

    @Test fun `grid LEFT within row moves to the prev cell`() {
        val r = WallFocus(active = WallZone.Grid, gridIndex = 1).apply(NavIntent.Left)
        assertEquals(0, (r as NavResult.Focus).focus.gridIndex)
    }

    @Test fun `grid SELECT requests OpenSlotControls for the focused cell`() {
        val r = WallFocus(active = WallZone.Grid, gridIndex = 2).apply(NavIntent.Select)
        assertEquals(NavResult.OpenSlotControls(2), r)
    }

    // ============== Ticker ==============

    @Test fun `ticker DOWN returns to lastLowerZone (Feed)`() {
        val r = WallFocus(
            active = WallZone.Ticker, lastLowerZone = WallZone.Feed, feedIndex = 4,
        ).apply(NavIntent.Down)
        val next = (r as NavResult.Focus).focus
        assertEquals(WallZone.Feed, next.active)
        assertEquals("feedIndex preserved", 4, next.feedIndex)
    }

    @Test fun `ticker DOWN returns to lastLowerZone (Grid)`() {
        val r = WallFocus(
            active = WallZone.Ticker, lastLowerZone = WallZone.Grid, gridIndex = 3,
        ).apply(NavIntent.Down)
        val next = (r as NavResult.Focus).focus
        assertEquals(WallZone.Grid, next.active)
        assertEquals(3, next.gridIndex)
    }

    @Test fun `ticker DOWN falls back to Grid if Feed is empty`() {
        val r = WallFocusModel.apply(
            focus = WallFocus(active = WallZone.Ticker, lastLowerZone = WallZone.Feed),
            intent = NavIntent.Down,
            feedItemCount = 0,  // empty feed
            gridTileCount = GRID_COUNT,
            gridColumns = GRID_COLS,
        )
        assertEquals(WallZone.Grid, (r as NavResult.Focus).focus.active)
    }

    @Test fun `ticker DOWN stays when both lower zones are empty`() {
        val r = WallFocusModel.apply(
            focus = WallFocus(active = WallZone.Ticker),
            intent = NavIntent.Down,
            feedItemCount = 0,
            gridTileCount = 0,
            gridColumns = GRID_COLS,
        )
        assertTrue(r is NavResult.Stay)
    }

    @Test fun `ticker UP stays`() {
        val r = WallFocus(active = WallZone.Ticker).apply(NavIntent.Up)
        assertTrue(r is NavResult.Stay)
    }

    @Test fun `ticker LEFT opens the menu`() {
        val r = WallFocus(active = WallZone.Ticker).apply(NavIntent.Left)
        assertEquals(NavResult.OpenMenu, r)
    }

    @Test fun `ticker RIGHT stays`() {
        val r = WallFocus(active = WallZone.Ticker).apply(NavIntent.Right)
        assertTrue(r is NavResult.Stay)
    }

    @Test fun `ticker SELECT toggles tickerPaused`() {
        val running = WallFocus(active = WallZone.Ticker, tickerPaused = false)
        val paused = (running.apply(NavIntent.Select) as NavResult.Focus).focus
        assertEquals(true, paused.tickerPaused)
        val resumed = (paused.apply(NavIntent.Select) as NavResult.Focus).focus
        assertEquals(false, resumed.tickerPaused)
    }

    @Test fun `ticker SELECT does not change other focus fields`() {
        val before = WallFocus(
            active = WallZone.Ticker,
            feedIndex = 7,
            gridIndex = 3,
            lastLowerZone = WallZone.Grid,
        )
        val after = (before.apply(NavIntent.Select) as NavResult.Focus).focus
        // Only tickerPaused flips — every other field is preserved so
        // the operator's positions in feed/grid don't reset when they
        // poke OK on the ticker.
        assertEquals(true, after.tickerPaused)
        assertEquals(before.feedIndex, after.feedIndex)
        assertEquals(before.gridIndex, after.gridIndex)
        assertEquals(before.lastLowerZone, after.lastLowerZone)
        assertEquals(before.active, after.active)
    }

    @Test fun `tickerPaused survives leaving and returning to the ticker zone`() {
        // Pause the ticker, leave for the feed, come back via UP from
        // feed item 0. tickerPaused must remain true — the operator
        // shouldn't have to re-pause it every time they navigate away.
        val paused = WallFocus(active = WallZone.Ticker, tickerPaused = true)
        val toFeed = (paused.apply(NavIntent.Down) as NavResult.Focus).focus
        assertEquals(WallZone.Feed, toFeed.active)
        assertEquals("tickerPaused preserved across zone exit", true, toFeed.tickerPaused)
        val backToTicker = (toFeed.apply(NavIntent.Up) as NavResult.Focus).focus
        assertEquals(WallZone.Ticker, backToTicker.active)
        assertEquals("tickerPaused preserved across return", true, backToTicker.tickerPaused)
    }

    // ============== Per-zone SELECT (per #124 action layer) ==============

    @Test fun `grid SELECT does not modify focus state — only requests OpenSlotControls`() {
        // Per-zone action layer rule: opening a modal is a SIDE EFFECT,
        // not a focus state change. If a future iteration changes this,
        // the test fails and forces a deliberate decision.
        val before = WallFocus(active = WallZone.Grid, gridIndex = 2, feedIndex = 8)
        val result = before.apply(NavIntent.Select)
        assertEquals(NavResult.OpenSlotControls(2), result)
    }

    @Test fun `feed SELECT only flips feedExpanded — no zone or index change`() {
        val before = WallFocus(active = WallZone.Feed, feedIndex = 5, gridIndex = 1)
        val after = (before.apply(NavIntent.Select) as NavResult.Focus).focus
        // Per-zone action layer rule for the feed: SELECT toggles in
        // place. No scroll, no zone change, no grid touch.
        assertEquals(true, after.feedExpanded)
        assertEquals(before.active, after.active)
        assertEquals(before.feedIndex, after.feedIndex)
        assertEquals(before.gridIndex, after.gridIndex)
    }

    @Test fun `actions layer does not introduce a trap (re-verify the no-trap invariant)`() {
        // After adding per-zone SELECT actions, EVERY zone still has at
        // least one direction that leaves it. The original no-trap test
        // covered the directional graph; this one re-pins that adding
        // SELECT (a non-directional intent) hasn't changed the graph.
        for (zone in listOf(WallZone.Feed, WallZone.Grid, WallZone.Ticker)) {
            val focus = WallFocus.Initial.copy(active = zone)
            val canExitDirectionally = listOf(NavIntent.Up, NavIntent.Down, NavIntent.Left, NavIntent.Right)
                .map { focus.apply(it) }
                .any { result ->
                    when (result) {
                        is NavResult.Focus -> result.focus.active != zone
                        is NavResult.OpenMenu -> true
                        is NavResult.OpenSlotControls -> true
                        else -> false
                    }
                }
            assertTrue("zone $zone must still be exitable directionally after action layer", canExitDirectionally)
        }
    }

    // ============== BACK semantics ==============

    @Test fun `BACK with feed expanded collapses, does NOT bubble`() {
        val r = WallFocus(
            active = WallZone.Feed, feedIndex = 3, feedExpanded = true,
        ).apply(NavIntent.Back)
        val next = (r as NavResult.Focus).focus
        assertEquals(false, next.feedExpanded)
        assertEquals("feedIndex unchanged on collapse", 3, next.feedIndex)
    }

    @Test fun `BACK from collapsed feed bubbles to caller`() {
        val r = WallFocus(active = WallZone.Feed, feedExpanded = false).apply(NavIntent.Back)
        assertEquals(NavResult.BackBubble, r)
    }

    @Test fun `BACK from grid bubbles (grid has no in-zone expand state)`() {
        val r = WallFocus(active = WallZone.Grid, gridIndex = 1).apply(NavIntent.Back)
        assertEquals(NavResult.BackBubble, r)
    }

    @Test fun `BACK from ticker bubbles`() {
        val r = WallFocus(active = WallZone.Ticker).apply(NavIntent.Back)
        assertEquals(NavResult.BackBubble, r)
    }

    // ============== No-trap invariants ==============

    @Test fun `every zone is reachable from every other zone`() {
        // Start at each zone in turn and verify the others are
        // reachable through some finite navigation path.
        val zones = listOf(WallZone.Feed, WallZone.Grid, WallZone.Ticker)
        for (start in zones) {
            for (target in zones) {
                if (start == target) continue
                val reached = reach(start, target)
                assertTrue(
                    "from $start, target $target should be reachable in <= 4 moves; took $reached",
                    reached in 1..4,
                )
            }
        }
    }

    private fun reach(start: WallZone, target: WallZone): Int {
        // Minimal BFS through the navigation graph; the constraints
        // make it tiny (3 zones, four intents).
        val visited = mutableSetOf<Pair<WallZone, Int>>()
        val queue = ArrayDeque<Triple<WallFocus, Int, Int>>()
        queue.add(Triple(WallFocus.Initial.copy(active = start), 0, -1))
        while (queue.isNotEmpty()) {
            val (focus, dist, _) = queue.removeFirst()
            if (focus.active == target) return dist
            if (dist >= 6) continue
            for (intent in listOf(NavIntent.Up, NavIntent.Down, NavIntent.Left, NavIntent.Right)) {
                val result = focus.apply(intent) as? NavResult.Focus ?: continue
                val key = result.focus.active to result.focus.gridIndex
                if (key in visited) continue
                visited.add(key)
                queue.add(Triple(result.focus, dist + 1, intent.ordinal))
            }
        }
        return Int.MAX_VALUE
    }

    @Test fun `every zone is exitable (no trap)`() {
        for (zone in listOf(WallZone.Feed, WallZone.Grid, WallZone.Ticker)) {
            val focus = WallFocus.Initial.copy(active = zone)
            // At least one of UP / DOWN / LEFT / RIGHT must produce a
            // result that LEAVES this zone — either a new zone, an
            // OpenMenu (which removes wall focus altogether), or
            // BackBubble. Stay-everywhere = trap.
            val canExit = listOf(NavIntent.Up, NavIntent.Down, NavIntent.Left, NavIntent.Right)
                .map { focus.apply(it) }
                .any { result ->
                    when (result) {
                        is NavResult.Focus -> result.focus.active != zone
                        is NavResult.OpenMenu -> true
                        is NavResult.OpenSlotControls -> true
                        else -> false
                    }
                }
            assertTrue("zone $zone must be exitable from some direction", canExit)
        }
    }

    @Test fun `FEED to GRID to FEED round-trip preserves feedIndex AND gridIndex`() {
        val start = WallFocus(active = WallZone.Feed, feedIndex = 11, gridIndex = 2)
        val toGrid = (start.apply(NavIntent.Right) as NavResult.Focus).focus
        // Now in Grid at preserved gridIndex 2 (row 1, col 0).
        assertEquals(WallZone.Grid, toGrid.active)
        assertEquals(2, toGrid.gridIndex)
        // LEFT from grid col 0 → Feed at preserved feedIndex 11.
        val backToFeed = (toGrid.apply(NavIntent.Left) as NavResult.Focus).focus
        assertEquals(WallZone.Feed, backToFeed.active)
        assertEquals(11, backToFeed.feedIndex)
        assertEquals("gridIndex preserved across the round-trip", 2, backToFeed.gridIndex)
    }

    // ============== Feed-Right orientation (UX & Config chapter) ==============
    //
    // Helper that applies an intent with the feed on the RIGHT — i.e.
    // the operator's "Feed Right" layout setting. The spatial rules
    // invert: LEFT-from-feed-edge becomes the grid-bound move; RIGHT
    // becomes the menu-open gesture. The model is the same pure
    // function; only the feedSide parameter changes.

    private fun WallFocus.applyRight(intent: NavIntent): NavResult = WallFocusModel.apply(
        focus = this,
        intent = intent,
        feedItemCount = FEED_COUNT,
        gridTileCount = GRID_COUNT,
        gridColumns = GRID_COLS,
        feedSide = FeedSide.Right,
    )

    @Test fun `feed-right LEFT enters grid (mirrors feed-left RIGHT)`() {
        val r = WallFocus(active = WallZone.Feed, feedIndex = 7, feedExpanded = true)
            .applyRight(NavIntent.Left)
        val next = (r as NavResult.Focus).focus
        assertEquals(WallZone.Grid, next.active)
        assertEquals("feedIndex preserved across the side-aware transition", 7, next.feedIndex)
        assertEquals(false, next.feedExpanded)
    }

    @Test fun `feed-right RIGHT opens menu (mirrors feed-left LEFT)`() {
        val r = WallFocus(active = WallZone.Feed, feedIndex = 0).applyRight(NavIntent.Right)
        assertEquals(NavResult.OpenMenu, r)
    }

    @Test fun `feed-right grid RIGHT in last column spills to Feed`() {
        // 2x2: index 1 is row0 last col. With feed on the right, RIGHT
        // from the right-most column should hand off to the feed.
        val r = WallFocus(active = WallZone.Grid, gridIndex = 1, feedIndex = 4)
            .applyRight(NavIntent.Right)
        val next = (r as NavResult.Focus).focus
        assertEquals(WallZone.Feed, next.active)
        assertEquals(4, next.feedIndex)
    }

    @Test fun `feed-right grid RIGHT in interior column moves toward feed within the row`() {
        // 2x2: index 0 is row0 col0. With feed on the right, RIGHT
        // from col 0 moves to col 1 (one step toward the feed) — same
        // row, no wrap.
        val r = WallFocus(active = WallZone.Grid, gridIndex = 0).applyRight(NavIntent.Right)
        assertEquals(1, (r as NavResult.Focus).focus.gridIndex)
    }

    @Test fun `feed-right grid LEFT in column 0 stays (no wrap)`() {
        // With the feed on the right, LEFT is the "deeper into the
        // grid, away from the feed" direction. At column 0 there is
        // no more grid — stay, never wrap to a previous row's last
        // column.
        val r = WallFocus(active = WallZone.Grid, gridIndex = 0).applyRight(NavIntent.Left)
        assertTrue("LEFT at row's first column with feed-right must stay", r is NavResult.Stay)
    }

    @Test fun `feed-right grid LEFT in interior column moves away from feed`() {
        // 2x2: index 1 is row0 col1. LEFT moves to col 0 (one step
        // away from the feed on the right).
        val r = WallFocus(active = WallZone.Grid, gridIndex = 1).applyRight(NavIntent.Left)
        assertEquals(0, (r as NavResult.Focus).focus.gridIndex)
    }

    @Test fun `feed-right ticker RIGHT opens menu (matching the outer-edge rule)`() {
        val r = WallFocus(active = WallZone.Ticker).applyRight(NavIntent.Right)
        assertEquals(NavResult.OpenMenu, r)
    }

    @Test fun `feed-right ticker LEFT stays (left-edge gesture only opens menu when feed is left)`() {
        val r = WallFocus(active = WallZone.Ticker).applyRight(NavIntent.Left)
        assertTrue(r is NavResult.Stay)
    }

    @Test fun `feed-right FEED→GRID→FEED round-trip preserves both indices`() {
        val start = WallFocus(active = WallZone.Feed, feedIndex = 9, gridIndex = 2)
        val toGrid = (start.applyRight(NavIntent.Left) as NavResult.Focus).focus
        assertEquals(WallZone.Grid, toGrid.active)
        assertEquals(2, toGrid.gridIndex)
        // Grid index 2 in 2x2 grid is row=1, col=0. RIGHT spills to
        // feed because col 0 is interior; col 1 (last column) is the
        // feed-adjacent column. So we need to step toward feed first:
        // RIGHT moves to col 1 (interior → step), then RIGHT spills.
        val step = (toGrid.applyRight(NavIntent.Right) as NavResult.Focus).focus
        assertEquals(3, step.gridIndex)  // row 1 col 1
        val backToFeed = (step.applyRight(NavIntent.Right) as NavResult.Focus).focus
        assertEquals(WallZone.Feed, backToFeed.active)
        assertEquals("feedIndex preserved across the side-aware round-trip", 9, backToFeed.feedIndex)
        assertEquals("gridIndex preserved", 3, backToFeed.gridIndex)
    }

    @Test fun `feed-right no-trap invariant — every zone still exitable`() {
        // After swapping the feed side, every zone must still be
        // exitable via at least one directional intent. A trap intro-
        // duced by side-swap would surface here.
        for (zone in listOf(WallZone.Feed, WallZone.Grid, WallZone.Ticker)) {
            val focus = WallFocus.Initial.copy(active = zone)
            val canExit = listOf(NavIntent.Up, NavIntent.Down, NavIntent.Left, NavIntent.Right)
                .map { focus.applyRight(it) }
                .any { result ->
                    when (result) {
                        is NavResult.Focus -> result.focus.active != zone
                        is NavResult.OpenMenu -> true
                        is NavResult.OpenSlotControls -> true
                        else -> false
                    }
                }
            assertTrue("zone $zone must be exitable in the feed-right layout", canExit)
        }
    }

    @Test fun `feed-right vs feed-left LEFT-RIGHT are mirror images`() {
        // For any intent, swapping the feedSide should swap the
        // semantics of LEFT and RIGHT (the OTHER intents — Up/Down,
        // Select, Back — are unaffected by the side).
        val focus = WallFocus(active = WallZone.Feed, feedIndex = 3)
        val leftLeft = focus.apply(NavIntent.Left)
        val rightRight = focus.applyRight(NavIntent.Right)
        // Feed-left LEFT and feed-right RIGHT should produce the same outcome
        // (both open the menu, the operator's outer-edge gesture).
        assertEquals(leftLeft, rightRight)
    }

    @Test fun `ticker round-trip preserves the lastLowerZone memo across multiple bounces`() {
        // Operator goes Grid → Ticker → Grid → Ticker → ... lastLowerZone
        // should always reflect "Grid" not "Feed".
        var focus = WallFocus(active = WallZone.Grid, gridIndex = 1)
        for (i in 1..3) {
            focus = (focus.apply(NavIntent.Up) as NavResult.Focus).focus
            assertEquals(WallZone.Ticker, focus.active)
            assertEquals(WallZone.Grid, focus.lastLowerZone)
            focus = (focus.apply(NavIntent.Down) as NavResult.Focus).focus
            assertEquals(WallZone.Grid, focus.active)
            assertEquals(1, focus.gridIndex)
        }
    }
}
