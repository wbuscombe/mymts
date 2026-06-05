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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.mymts.ui.menu.AudioState
import com.mymts.ui.menu.CaptionsState
import com.mymts.ui.menu.ChannelPickerOverlay
import com.mymts.ui.menu.MenuOverlay
import com.mymts.ui.menu.MenuState
import com.mymts.ui.menu.SlotControlsOverlay
import com.mymts.ui.menu.SlotRow
import com.mymts.ui.menu.rememberMenuState
import com.mymts.ui.nav.NavIntent
import com.mymts.ui.nav.NavResult
import com.mymts.ui.nav.WallFocus
import com.mymts.ui.nav.WallFocusModel
import com.mymts.ui.nav.WallZone

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
    val audibleSlot by lineupStore.audibleSlot
    val captionsOnSlots by lineupStore.captionsOnSlots

    // Per-slot soft-caption-track availability. Updated from VideoGrid's
    // Player.Listener.onTracksChanged forwarding. Read by the controls
    // overlay to surface honest "not available on this channel" when
    // the stream has no text track to toggle. A missing entry means
    // "unknown yet" — the row defaults to On/Off based on the saved
    // preference, which is the right behaviour until the manifest
    // parses.
    val softCaptionAvailability = remember { mutableStateMapOf<Int, Boolean>() }

    // Whole-wall D-pad focus (2026-06-04, usage-feedback item A). The
    // focus model in `com.mymts.ui.nav` is pure; this composable owns
    // its state and dispatches results into the existing menu /
    // pending-selection state holders.
    var focus by remember { mutableStateOf(WallFocus.Initial) }
    var feedItemCount by remember { mutableStateOf(0) }

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
    // Restore root focus whenever the menu closes OR a modal
    // (controls / picker) dismisses. The pendingSelection key matters
    // when the modal was opened straight from a focused grid cell (no
    // menu involved) — without it the wall's onPreviewKeyEvent stops
    // receiving D-pad events after the modal goes away.
    DisposableEffect(menu.isOpen, menu.pendingSelection) {
        if (!menu.isOpen && menu.pendingSelection == null) {
            rootFocusRequester.requestFocus()
        }
        onDispose { }
    }

    val gridColumns = remember(slots.size) { gridColumnsFor(slots.size) }

    // Dispatch a NavIntent through the pure focus model and apply its
    // result. Returns true if the event was handled (caller should
    // consume it).
    fun dispatchNav(intent: NavIntent): Boolean {
        val result = WallFocusModel.apply(
            focus = focus,
            intent = intent,
            feedItemCount = feedItemCount,
            gridTileCount = slots.size,
            gridColumns = gridColumns,
        )
        return when (result) {
            is NavResult.Stay -> true  // consume — no transition but the key isn't bubbling
            is NavResult.Focus -> {
                focus = result.focus
                true
            }
            is NavResult.OpenMenu -> {
                menu.open()
                true
            }
            is NavResult.OpenSlotControls -> {
                menu.openControls(result.slotIndex)
                true
            }
            is NavResult.BackBubble -> false  // let the system handle BACK
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WallColors.Background)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // KEY_MENU always toggles the side panel — keeps the
                // legacy gesture working for remotes that have one.
                if (event.key == Key.Menu) {
                    menu.toggle()
                    return@onPreviewKeyEvent true
                }
                // When a modal sub-overlay (controls / picker) is up,
                // its own composable handles input; do not steal events
                // here.
                if (menu.pendingSelection != null) return@onPreviewKeyEvent false
                // When the side menu is open WITHOUT a modal, its rows
                // own their focus / SELECT; only handle BACK here.
                if (menu.isOpen) {
                    return@onPreviewKeyEvent if (event.key == Key.Back) {
                        menu.close(); true
                    } else false
                }
                // Side menu closed → route through the wall focus model.
                when (event.key) {
                    Key.DirectionUp -> dispatchNav(NavIntent.Up)
                    Key.DirectionDown -> dispatchNav(NavIntent.Down)
                    Key.DirectionLeft -> dispatchNav(NavIntent.Left)
                    Key.DirectionRight -> dispatchNav(NavIntent.Right)
                    Key.DirectionCenter, Key.Enter -> dispatchNav(NavIntent.Select)
                    Key.Back -> dispatchNav(NavIntent.Back)
                    else -> false
                }
            },
    ) {
        val wallAlpha = if (menu.isOpen || menu.pendingSelection != null) 0.45f else 1f
        Column(modifier = Modifier.fillMaxSize().alpha(wallAlpha)) {
            TickerStrip(
                source = ticker,
                focused = focus.active == WallZone.Ticker,
                paused = focus.tickerPaused,
            )
            Divider(color = Color(0x22FFFFFF), thickness = 1.dp)
            Row(modifier = Modifier.fillMaxSize()) {
                FeedPane(
                    repository = feed,
                    modifier = Modifier.fillMaxHeight().fillMaxWidth(0.28f),
                    focusedIndex = if (focus.active == WallZone.Feed) focus.feedIndex else null,
                    expandedIndex = if (focus.active == WallZone.Feed && focus.feedExpanded) {
                        focus.feedIndex
                    } else null,
                    onItemCountChanged = { feedItemCount = it },
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
                    audibleSlot = audibleSlot,
                    captionsOnSlots = captionsOnSlots,
                    onSoftCaptionAvailabilityChanged = { idx, available ->
                        softCaptionAvailability[idx] = available
                    },
                    focusedCellIndex = if (focus.active == WallZone.Grid) focus.gridIndex else null,
                )
            }
        }

        MenuOverlay(
            state = menu,
            slotRows = slotRows,
            versionLine = "MyMTS · $buildVersion · $buildSha",
            // Stage 6 controls track: SELECT on a slot row opens the
            // small controls popup (channel / audio / captions / close)
            // rather than jumping directly to the channel picker. The
            // controls overlay then re-routes "Channel" to the picker.
            onSlotSelected = { slotIndex -> menu.openControls(slotIndex) },
            modifier = Modifier.fillMaxSize(),
        )

        // Sub-overlays. Only one is visible at a time; the menu's
        // `pendingSelection` is the source of truth for which one. BACK
        // dismisses the sub-overlay (set via `dismissSelection`) and
        // leaves the side menu open so the operator can navigate to
        // another slot without reopening MENU.
        val pending = menu.pendingSelection
        if (pending is MenuState.PendingSelection.SlotControls) {
            val playingSlot = slots.getOrNull(pending.slotIndex) as? TileSlotResolver.Slot.Playing
            SlotControlsOverlay(
                slotIndex = pending.slotIndex,
                channelLabel = slots.getOrNull(pending.slotIndex)?.displayLabel() ?: "—",
                audioState = if (pending.slotIndex == audibleSlot) AudioState.Audible
                else AudioState.Muted,
                captionsState = run {
                    // Three states, honest about all three:
                    //   - NotAvailable: the slot is currently Playing
                    //     AND the player has reported (via onTracksChanged)
                    //     that no text track exists in the manifest.
                    //     Toggle is a no-op for this slot.
                    //   - On / Off: the saved preference. Used when
                    //     a soft track exists OR when the manifest
                    //     hasn't parsed yet (the row will flip to
                    //     NotAvailable as soon as onTracksChanged fires
                    //     for a stream without a text track).
                    val playing = playingSlot
                    val isOn = pending.slotIndex in captionsOnSlots
                    val knownNoTrack =
                        playing != null && softCaptionAvailability[pending.slotIndex] == false
                    when {
                        knownNoTrack -> CaptionsState.NotAvailable
                        isOn -> CaptionsState.On
                        else -> CaptionsState.Off
                    }
                },
                onPickChannel = { menu.pickSlot(pending.slotIndex) },
                onToggleAudio = { lineupStore.toggleAudible(pending.slotIndex) },
                onToggleCaptions = { lineupStore.toggleCaptions(pending.slotIndex) },
                onCancel = { menu.dismissSelection() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (pending is MenuState.PendingSelection.SlotPicker) {
            ChannelPickerOverlay(
                slotIndex = pending.slotIndex,
                channels = pickerChannels,
                currentSelection = slots.getOrNull(pending.slotIndex)?.currentSlug(),
                onAssign = { slug ->
                    lineupStore.assign(pending.slotIndex, slug)
                    // Return to the controls overlay so the operator
                    // can immediately toggle audio/captions on the
                    // newly-chosen channel — calmer than punting them
                    // back to the side menu.
                    menu.openControls(pending.slotIndex)
                },
                onCancel = { menu.openControls(pending.slotIndex) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun TileSlotResolver.Slot.displayLabel(): String = when (this) {
    is TileSlotResolver.Slot.Playing -> channel.label
    is TileSlotResolver.Slot.Offline -> "${channel.label} (offline)"
    is TileSlotResolver.Slot.Empty -> "— empty —"
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
