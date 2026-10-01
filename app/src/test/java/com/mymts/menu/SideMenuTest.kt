package com.mymts.menu

import com.mymts.data.settings.FeedSide
import com.mymts.ui.menu.BackOutcome
import com.mymts.ui.menu.MenuState
import com.mymts.ui.menu.SideMenuFocus
import com.mymts.ui.menu.SideMenuFocusTarget
import com.mymts.ui.menu.SideMenuRow
import com.mymts.ui.menu.SlotRow
import com.mymts.ui.menu.WallRefreshActions
import com.mymts.ui.menu.menuBackOutcome
import com.mymts.ui.menu.refreshAllFeeds
import com.mymts.ui.menu.refreshAllVideo
import com.mymts.ui.menu.resyncAllFeeds
import com.mymts.ui.menu.sideMenuSections
import com.mymts.ui.nav.NavIntent
import com.mymts.ui.nav.NavResult
import com.mymts.ui.nav.WallFocus
import com.mymts.ui.nav.WallFocusModel
import com.mymts.ui.nav.WallZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** One operator step on the menu: a MenuState change or a refresh row's activation. */
private typealias MenuStep = (MenuState, WallRefreshActions) -> Unit

/**
 * "Refresh all feeds" leads the side menu the LEFT key opens.
 *
 * The row order, the focus rule and each refresh row's activation are plain Kotlin
 * (`ui/menu/SideMenu.kt`); MenuOverlay and WallScreen only bind them. These tests pin
 * those decisions. The Compose bindings — the focus requesters, the key routing while
 * the menu is open, the refresh signals reaching VideoGrid — are checked on the device.
 */
class SideMenuTest {

    private fun slots(count: Int): List<SlotRow> = (0 until count).map { i ->
        SlotRow(
            slotIndex = i,
            title = "Slot ${i + 1}",
            detail = "Channel ${i + 1} · live",
            detailStyle = SlotRow.DetailStyle.Live,
        )
    }

    /** The side menu's rows before "Refresh all feeds": every slot, then Preset, Settings, Resync. */
    private fun rowsBefore(slotRows: List<SlotRow>): List<SideMenuRow> =
        slotRows.map { SideMenuRow.Slot(it) } +
            listOf(SideMenuRow.Preset, SideMenuRow.Settings, SideMenuRow.ResyncAllFeeds)

    // ---- order ----

    @Test fun `refresh all feeds is the first row and every existing row follows in its order`() {
        for (count in listOf(1, 4, 6, 9)) {
            val slotRows = slots(count)
            assertEquals(
                "grid of $count",
                listOf(SideMenuRow.RefreshAllFeeds) + rowsBefore(slotRows),
                sideMenuSections(slotRows).rows,
            )
        }
    }

    @Test fun `the new row sits alone above CHANNELS and the other sections are unchanged`() {
        val slotRows = slots(4)
        val sections = sideMenuSections(slotRows)
        assertEquals(listOf(SideMenuRow.RefreshAllFeeds), sections.top)
        assertEquals(slotRows, sections.channels.map { it.slot })
        assertEquals(
            listOf(SideMenuRow.Preset, SideMenuRow.Settings, SideMenuRow.ResyncAllFeeds),
            sections.wall,
        )
        assertEquals("exactly one row is added", rowsBefore(slotRows).size + 1, sections.rows.size)
    }

    @Test fun `the row is labelled exactly Refresh all feeds`() {
        assertEquals("Refresh all feeds", strings["menu_refresh_all_feeds"])
        assertTrue(
            "the row carries a detail line like its siblings",
            !strings["menu_refresh_all_feeds_detail"].isNullOrBlank(),
        )
    }

    private val strings: Map<String, String> by lazy {
        // Gradle runs unit tests from the module dir; the root is a fallback.
        val file = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
        ).firstOrNull { it.isFile } ?: throw AssertionError("strings.xml not found; run via Gradle")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file).documentElement.getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to e.textContent
        }
    }

    // ---- focus ----

    /**
     * Drives [SideMenuFocus] the way MenuOverlay's panel does: on each change of the
     * opening count or the sub-overlay flag (its LaunchedEffect keys) it asks for the
     * target, claims focus, then records the landing. [dispose] is the panel leaving
     * composition once the close animation ends; the next opening builds a fresh panel.
     */
    private class Panel(private val menu: MenuState) {
        private var focus = SideMenuFocus()
        private var keys: Pair<Int, Boolean>? = null

        /** The row focused after the latest state change, or null if focus stayed put. */
        fun landing(): SideMenuFocusTarget? {
            val now = menu.openings to (menu.pendingSelection != null)
            if (now == keys) return null // unchanged keys: the effect does not relaunch
            keys = now
            val target = focus.targetFor(now.first, now.second) ?: return null
            focus.landed(now.first) // the claim completed
            return target
        }

        fun dispose() {
            focus = SideMenuFocus()
            keys = null
        }
    }

    @Test fun `the first opening lands on refresh all feeds`() {
        val menu = MenuState()
        val panel = Panel(menu)
        menu.open()
        assertEquals(SideMenuFocusTarget.RefreshAllFeeds, panel.landing())
    }

    @Test fun `reopening after any other row was used lands on refresh all feeds again`() {
        // Each use, step by step, ending with the menu closed.
        val uses: Map<String, List<MenuStep>> = mapOf(
            "a slot row, then BACK twice" to listOf<MenuStep>(
                { menu, _ -> menu.openControls(0) },
                { menu, _ -> menu.dismissSelection() },
                { menu, _ -> menu.close() },
            ),
            "a slot row, assigning a channel" to listOf<MenuStep>(
                { menu, _ -> menu.openControls(0) },
                { menu, _ -> menu.pickSlot(0) },
                { menu, _ -> menu.close() },
            ),
            "Preset, applying one" to listOf<MenuStep>(
                { menu, _ -> menu.openPresetPicker() },
                { menu, _ -> menu.close() },
            ),
            "Settings, then BACK twice" to listOf<MenuStep>(
                { menu, _ -> menu.openSettings() },
                { menu, _ -> menu.dismissSelection() },
                { menu, _ -> menu.close() },
            ),
            "Resync all feeds" to listOf<MenuStep>({ _, actions -> actions.resyncAllFeeds() }),
            "Refresh all feeds" to listOf<MenuStep>({ _, actions -> actions.refreshAllFeeds() }),
        )
        for ((name, steps) in uses) {
            for (disposed in listOf(true, false)) {
                val menu = MenuState()
                val panel = Panel(menu)
                val actions = RecordingActions(menu)
                menu.open()
                assertEquals(SideMenuFocusTarget.RefreshAllFeeds, panel.landing())
                steps.forEach { step ->
                    step(menu, actions)
                    panel.landing()
                }
                assertFalse("$name leaves the menu closed", menu.isOpen)
                // disposed = a later reopen; !disposed = a reopen during the close
                // animation, while the panel is still composed.
                if (disposed) panel.dispose()
                menu.open()
                assertEquals(
                    "reopen after $name (panel disposed: $disposed)",
                    SideMenuFocusTarget.RefreshAllFeeds,
                    panel.landing(),
                )
            }
        }
    }

    @Test fun `a sub-overlay closing back to the open menu still lands on the first slot row`() {
        val menu = MenuState()
        val panel = Panel(menu)
        menu.open()
        assertEquals(SideMenuFocusTarget.RefreshAllFeeds, panel.landing())
        menu.openSettings()
        assertNull("the sub-overlay owns focus", panel.landing())
        menu.openNewsFilter()
        assertNull(panel.landing())
        menu.openSettings()
        assertNull(panel.landing())
        menu.dismissSelection()
        assertEquals(SideMenuFocusTarget.FirstSlot, panel.landing())
        menu.openControls(2)
        assertNull(panel.landing())
        menu.dismissSelection()
        assertEquals(SideMenuFocusTarget.FirstSlot, panel.landing())
    }

    @Test fun `an opening whose focus claim was cut short still lands on refresh all feeds`() {
        // The claim for opening 1 is decided, then cancelled before it completes (a key
        // changed within the frame), so landed() is never called for it.
        val focus = SideMenuFocus()
        assertEquals(SideMenuFocusTarget.RefreshAllFeeds, focus.targetFor(1, subOverlayActive = false))
        assertNull(focus.targetFor(1, subOverlayActive = true))
        assertEquals(SideMenuFocusTarget.RefreshAllFeeds, focus.targetFor(1, subOverlayActive = false))
        focus.landed(1)
        assertNull(focus.targetFor(1, subOverlayActive = true))
        assertEquals(
            "only a landed opening re-homes to the first slot row",
            SideMenuFocusTarget.FirstSlot,
            focus.targetFor(1, subOverlayActive = false),
        )
        assertEquals(
            "the next opening lands on refresh all feeds again",
            SideMenuFocusTarget.RefreshAllFeeds,
            focus.targetFor(2, subOverlayActive = false),
        )
    }

    @Test fun `a menu opened beneath slot controls lands on refresh all feeds once they clear`() {
        // SELECT on a grid tile opens its controls with the menu closed; MENU then
        // opens the menu underneath them.
        val menu = MenuState()
        menu.openControls(1)
        menu.toggle()
        val panel = Panel(menu)
        assertNull("the controls keep focus", panel.landing())
        menu.dismissSelection()
        assertEquals(SideMenuFocusTarget.RefreshAllFeeds, panel.landing())
    }

    // ---- activation ----

    /** Records every effect a refresh row triggers; closeMenu also closes [menu]. */
    private class RecordingActions(private val menu: MenuState? = null) : WallRefreshActions {
        val calls = mutableListOf<String>()
        override fun reconnectAllVideo() { calls += "reconnectAllVideo" }
        override fun reloadAllRadar() { calls += "reloadAllRadar" }
        override fun resyncAll() { calls += "resyncAll" }
        override fun closeMenu() {
            calls += "closeMenu"
            menu?.close()
        }
    }

    @Test fun `refresh all feeds runs the full video reconnect and one radar reload, then closes`() {
        val menu = MenuState().apply { open() }
        val actions = RecordingActions(menu)
        actions.refreshAllFeeds()
        assertEquals(listOf("reconnectAllVideo", "reloadAllRadar", "closeMenu"), actions.calls)
        assertFalse("activation closes the menu", menu.isOpen)
    }

    @Test fun `refresh all feeds raises no message and has no cooldown`() {
        // The only message a refresh row raises is Resync's "Resyncing…" flash, bound to
        // resyncAll. A second activation runs in full: no cooldown swallows it.
        val actions = RecordingActions()
        actions.refreshAllFeeds()
        actions.refreshAllFeeds()
        assertEquals(
            List(2) { listOf("reconnectAllVideo", "reloadAllRadar", "closeMenu") }.flatten(),
            actions.calls,
        )
        assertFalse("resyncAll" in actions.calls)
    }

    @Test fun `resync all feeds calls only its own actions`() {
        val actions = RecordingActions()
        actions.resyncAllFeeds()
        assertEquals(listOf("resyncAll", "closeMenu"), actions.calls)
    }

    @Test fun `refresh all video calls only the video reconnect and leaves WALL SETTINGS open`() {
        val actions = RecordingActions()
        actions.refreshAllVideo()
        assertEquals(listOf("reconnectAllVideo"), actions.calls)
    }

    @Test fun `a refresh row reaches only these four effects, none of them a setting write`() {
        assertEquals(
            listOf("closeMenu", "reconnectAllVideo", "reloadAllRadar", "resyncAll"),
            WallRefreshActions::class.java.declaredMethods.map { it.name }.sorted(),
        )
    }

    // ---- BACK and the edge keys ----

    @Test fun `BACK on the open menu resolves to closing it`() {
        // BACK's decision is focus-independent, so it is the same on the new row as on
        // any other. That the key reaches only this close, never the focused row's
        // action, is Compose wiring and is checked on the device.
        val menu = MenuState()
        menu.open()
        assertEquals(BackOutcome.CloseMenu, menuBackOutcome(menu.isOpen, menu.pendingSelection))
        menu.close() // what WallScreen's BACK handling applies for CloseMenu
        assertFalse(menu.isOpen)
        assertNull(menu.pendingSelection)
        assertEquals(
            "BACK at the bare wall is still not consumed",
            BackOutcome.Pass,
            menuBackOutcome(menu.isOpen, menu.pendingSelection),
        )
    }

    @Test fun `the new row is the top row and Resync all feeds is still the bottom row`() {
        // The edge keys themselves are Compose focus traversal (no MyMTS decision logic),
        // checked on the device. What is decided here is which rows sit at the edges.
        val slotRows = slots(4)
        val rows = sideMenuSections(slotRows).rows
        // UP on the top row: the new row now, where it was Slot 1.
        assertEquals(SideMenuRow.RefreshAllFeeds, rows.first())
        // DOWN on the bottom row: still Resync all feeds.
        assertEquals(rowsBefore(slotRows).last(), rows.last())
    }

    @Test fun `LEFT still opens this menu from the feed and the ticker`() {
        fun left(zone: WallZone, side: FeedSide, intent: NavIntent) = WallFocusModel.apply(
            focus = WallFocus(active = zone),
            intent = intent,
            feedItemCount = 5,
            gridTileCount = 4,
            gridColumns = 2,
            feedSide = side,
        )
        assertEquals(NavResult.OpenMenu, left(WallZone.Feed, FeedSide.Left, NavIntent.Left))
        assertEquals(NavResult.OpenMenu, left(WallZone.Ticker, FeedSide.Left, NavIntent.Left))
        // The Feed-Right mirror is unchanged: RIGHT opens it there.
        assertEquals(NavResult.OpenMenu, left(WallZone.Feed, FeedSide.Right, NavIntent.Right))
    }
}
