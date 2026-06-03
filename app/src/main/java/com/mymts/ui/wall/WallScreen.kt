package com.mymts.ui.wall

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mymts.data.helper.Channel
import com.mymts.data.helper.ChannelsRepository
import com.mymts.data.helper.FeedRepository
import com.mymts.data.helper.HelperClient
import com.mymts.data.lineup.LineupStore
import com.mymts.data.ticker.SampleTickerSource
import com.mymts.ui.menu.ChannelPickerOverlay
import com.mymts.ui.menu.MenuOverlay
import com.mymts.ui.menu.MenuState
import com.mymts.ui.menu.SlotRow
import com.mymts.ui.menu.rememberMenuState

/**
 * Stage 3 wall + Stage 5 menu + channel picker.
 *
 * This composable owns the **single source of truth** for the wall's
 * slot list: a `List<TileSlotResolver.Slot>` derived from the helper's
 * channel state, the operator's [LineupStore] overrides, and the
 * default cycler. The same slot list drives:
 *   - the video grid (each slot's player is created from
 *     `Slot.Playing.spec`);
 *   - the menu (each `SlotRow` reads the channel from the same slot);
 *   - the picker (the slot index it edits is the index into this list).
 *
 * The menu and the wall can never disagree about which channel is in
 * which slot because they read the same list.
 *
 * Trust Bar **C2**: opening the menu / picker never touches the
 * `StreamPlayerManager` for un-reassigned slots; the grid keeps playing
 * behind a scrim. **C3**: offline channels are surfaced honestly at
 * every layer — the row, the picker, the tile.
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
    val context = LocalContext.current
    val client = remember(helperBaseUrl) { HelperClient(helperBaseUrl) }
    val channels = remember(client) { ChannelsRepository(client) }
    val feed = remember(client) { FeedRepository(client) }
    val ticker = remember { SampleTickerSource() }
    val lineupStore = remember(context) { LineupStore(context) }
    val overrides by lineupStore.overrides

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

    // The slot list — single source of truth.
    val state by channels.state.collectAsState()
    val allChannels = remember(state.snapshot) { state.snapshot?.channels.orEmpty() }
    val playable = remember(allChannels) { allChannels.filter { it.isPlayable } }
    val defaultOrder = remember(playable) {
        LineupSelector.forWall(maxCount = tileCount).invoke(playable)
    }
    val slots = remember(tileCount, defaultOrder, allChannels, overrides) {
        TileSlotResolver.resolve(
            tileCount = tileCount,
            defaultChannels = defaultOrder,
            allChannels = allChannels,
            overrides = overrides,
        )
    }

    // Build the menu's per-slot rows from the SAME slot list.
    val slotRows = remember(slots) { slots.map { it.toRow() } }

    // Picker channels: every helper channel, sorted live-first so the
    // operator can scan the working ones quickly. Honest (live/offline)
    // status travels into the picker via Channel.isPlayable.
    val pickerChannels = remember(allChannels) { allChannels.sortedByLiveFirst() }

    BackHandler(enabled = menu.isOpen) { menu.close() }

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
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Menu -> { menu.toggle(); true }
                    Key.DirectionLeft -> if (!menu.isOpen) { menu.open(); true } else false
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
                    slots = slots,
                    modifier = Modifier.fillMaxSize(),
                    helperUnreachable = state.snapshot == null && !state.lastFetchOk,
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

        // Picker overlays the menu when a slot row was activated. It
        // renders only when both the menu is open AND there's a pending
        // SlotPicker selection — closing the menu via BACK clears the
        // pending state automatically.
        val pending = menu.pendingSelection
        if (menu.isOpen && pending is MenuState.PendingSelection.SlotPicker) {
            ChannelPickerOverlay(
                slotIndex = pending.slotIndex,
                channels = pickerChannels,
                currentSelection = slots.getOrNull(pending.slotIndex)?.currentSlug(),
                onAssign = { slug ->
                    lineupStore.assign(pending.slotIndex, slug)
                    menu.dismissSelection()
                },
                onCancel = { menu.dismissSelection() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Render a [TileSlotResolver.Slot] as a menu [SlotRow]. The detail
 * line and style come from the slot's own kind — Playing renders as
 * "live", Offline as "offline", Empty as "— empty —".
 */
private fun TileSlotResolver.Slot.toRow(): SlotRow = when (this) {
    is TileSlotResolver.Slot.Playing -> SlotRow(
        slotIndex = index,
        title = "Slot ${index + 1}",
        detail = "${channel.label} · live",
        detailStyle = SlotRow.DetailStyle.Live,
    )
    is TileSlotResolver.Slot.Offline -> SlotRow(
        slotIndex = index,
        title = "Slot ${index + 1}",
        detail = "${channel.label} · offline",
        detailStyle = SlotRow.DetailStyle.Offline,
    )
    is TileSlotResolver.Slot.Empty -> SlotRow(
        slotIndex = index,
        title = "Slot ${index + 1}",
        detail = "— empty —",
        detailStyle = SlotRow.DetailStyle.Empty,
    )
}

private fun TileSlotResolver.Slot.currentSlug(): String? = when (this) {
    is TileSlotResolver.Slot.Playing -> channel.slug
    is TileSlotResolver.Slot.Offline -> channel.slug
    is TileSlotResolver.Slot.Empty -> null
}

/** Live channels first, then offline. Alphabetical within each group. */
private fun List<Channel>.sortedByLiveFirst(): List<Channel> =
    sortedWith(compareByDescending<Channel> { it.isPlayable }.thenBy { it.label.lowercase() })
