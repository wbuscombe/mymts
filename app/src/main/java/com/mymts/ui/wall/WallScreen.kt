package com.mymts.ui.wall

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Divider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.mymts.data.helper.ChannelsRepository
import com.mymts.data.helper.FeedRepository
import com.mymts.data.helper.HelperClient
import com.mymts.data.ticker.SampleTickerSource
import com.mymts.ui.menu.MenuOverlay
import com.mymts.ui.menu.MenuState
import com.mymts.ui.menu.SlotRow
import com.mymts.ui.menu.rememberMenuState

/**
 * Stage 3 assembled wall + Stage 5 side menu.
 *
 * Layout (dark, dense, single-screen, ambient):
 *
 *   ┌──────────────────────────────────────────────┐
 *   │  ticker (thin marquee strip)                 │
 *   ├──────────────┬───────────────────────────────┤
 *   │              │                               │
 *   │  feed pane   │   video grid (2×2 at N=4)     │
 *   │  ~28% width  │   ~72% width                  │
 *   │              │                               │
 *   └──────────────┴───────────────────────────────┘
 *
 * Stage 5: a left-side menu slides in over this layout on the D-pad
 * MENU key, dimming (not pausing) the wall behind a scrim. The menu
 * lists one row per grid tile so the operator can pick which channel
 * fills each slot. Compose's standard focus system drives UP/DOWN
 * navigation between rows; SELECT/CENTER triggers each row's action;
 * BACK closes the menu via [BackHandler].
 *
 * Trust Bar **C2**: opening the menu never touches the
 * [com.mymts.player.StreamPlayerManager]; the grid keeps playing
 * behind the scrim. The menu state is local UI; it cannot cascade
 * into a video failure.
 */
@Composable
fun WallScreen(
    helperBaseUrl: String,
    tileCount: Int,
    buildVersion: String,
    buildSha: String,
    menu: MenuState = rememberMenuState(),
    modifier: Modifier = Modifier,
) {
    val client = remember(helperBaseUrl) { HelperClient(helperBaseUrl) }
    val channels = remember(client) { ChannelsRepository(client) }
    val feed = remember(client) { FeedRepository(client) }
    val ticker = remember { SampleTickerSource() }

    DisposableEffect(channels, feed, ticker) {
        channels.start()
        feed.start()
        ticker.start()
        onDispose {
            channels.stop()
            feed.stop()
            ticker.stop()
        }
    }

    // Stage 5 checkpoint 1: placeholder slot rows so the operator can
    // confirm the navigation feel before the channel picker + lineup
    // wiring lands in checkpoint 2.
    val slotRows = remember(tileCount) {
        (0 until tileCount).map { i ->
            SlotRow(
                slotIndex = i,
                title = "Slot ${i + 1}",
                detail = "(channel picker — coming next)",
                detailStyle = SlotRow.DetailStyle.Default,
            )
        }
    }

    BackHandler(enabled = menu.isOpen) { menu.close() }

    // The root Box must be focusable for `onPreviewKeyEvent` to fire —
    // Compose only dispatches key events to focused composables. We
    // grab focus initially and after every menu close so the wall's
    // MENU/LEFT bindings keep working without a manual focus poke.
    val rootFocusRequester = remember { FocusRequester() }
    DisposableEffect(menu.isOpen) {
        if (!menu.isOpen) rootFocusRequester.requestFocus()
        onDispose { }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WallColors.Background)
            .focusRequester(rootFocusRequester)
            .focusable()
            // Toggle the menu on D-pad MENU or, when closed, on D-pad
            // LEFT (the natural gesture for opening a left-side panel
            // when the Onn remote has no hardware MENU button).
            // `onPreviewKeyEvent` here covers the case where focus has
            // moved into the panel: the preview pass runs from the root
            // down, so a MENU press still closes the panel even after
            // focus has entered it. LEFT inside the open panel does
            // NOT bubble here — when the menu is open we return false
            // and let the panel's own focus system handle it.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Menu -> { menu.toggle(); true }
                    Key.DirectionLeft -> if (!menu.isOpen) { menu.open(); true } else false
                    // BackHandler is the primary close path, but on TV
                    // Modifier.focusable can consume BACK to exit a
                    // focus group before the dispatcher sees it. Catch
                    // BACK here as a belt-and-braces close when the
                    // menu is open.
                    Key.Back -> if (menu.isOpen) { menu.close(); true } else false
                    else -> false
                }
            },
    ) {
        val wallAlpha = if (menu.isOpen) 0.45f else 1f
        Column(modifier = Modifier.fillMaxSize().alpha(wallAlpha)) {
            TickerStrip(source = ticker)
            Divider(color = Color(0x22FFFFFF), thickness = 1.dp)
            Row(modifier = Modifier.fillMaxSize()) {
                FeedPane(
                    repository = feed,
                    modifier = Modifier.fillMaxHeight().fillMaxWidth(0.28f),
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(Color(0x22FFFFFF)),
                )
                VideoGrid(
                    repository = channels,
                    tileCount = tileCount,
                    modifier = Modifier.fillMaxSize(),
                    lineupSelector = LineupSelector.forWall(maxCount = tileCount)::invoke,
                )
            }
        }

        MenuOverlay(
            state = menu,
            slotRows = slotRows,
            versionLine = "MyMTS · $buildVersion · $buildSha",
            onSlotSelected = { slotIndex -> menu.pickSlot(slotIndex) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
