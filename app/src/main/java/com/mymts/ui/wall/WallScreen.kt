package com.mymts.ui.wall

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymts.data.helper.Channel
import com.mymts.data.helper.ChannelsRepository
import com.mymts.data.helper.FeedRepository
import com.mymts.data.helper.HelperClient
import com.mymts.data.lineup.LineupStore
import com.mymts.data.settings.FeedSide
import com.mymts.data.ticker.HelperTickerSource
import com.mymts.ui.menu.AudioState
import com.mymts.ui.menu.BackOutcome
import com.mymts.ui.menu.CaptionsState
import com.mymts.ui.menu.ChannelPickerOverlay
import com.mymts.ui.menu.MenuOverlay
import com.mymts.ui.menu.MenuState
import com.mymts.ui.menu.menuBackOutcome
import com.mymts.ui.menu.SettingsOverlay
import com.mymts.ui.menu.SourceFilterOverlay
import com.mymts.ui.wall.feed.FeedListBuilder
import com.mymts.ui.menu.SlotControlsOverlay
import com.mymts.ui.menu.SlotRow
import com.mymts.ui.menu.rememberMenuState
import com.mymts.ui.nav.NavIntent
import com.mymts.ui.nav.NavResult
import com.mymts.ui.nav.WallFocus
import com.mymts.ui.nav.WallFocusModel
import com.mymts.ui.nav.WallZone
import kotlinx.coroutines.delay

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
/** Hold SELECT on a focused tile at least this long to RESYNC it (Part D), vs a
 *  short press which opens the tile's controls. Tunable by feel on the remote. */
private const val LONG_PRESS_RESYNC_MS = 500L

@Composable
fun WallScreen(
    helperBaseUrl: String,
    tileCount: Int,
    buildVersion: String,
    buildSha: String,
    menu: MenuState = rememberMenuState(),
    onOpenHelperUrl: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val client = remember(helperBaseUrl) { HelperClient(helperBaseUrl) }
    val channels = remember(client) { ChannelsRepository(client) }
    val feed = remember(client) { FeedRepository(client) }
    // Real ticker: the helper serves markets (Stooq + CoinGecko) and
    // sports (ESPN) data; this source polls both and alternates modes.
    // SampleTickerSource remains the honest fallback inside it when the
    // helper is unreachable (markets → SAMPLE pills, never frozen-live).
    val ticker = remember(client) { HelperTickerSource(client) }
    val lineupStore = remember(context) { LineupStore(context) }
    val overrides by lineupStore.overrides
    val audibleSlot by lineupStore.audibleSlot
    val captionsOnSlots by lineupStore.captionsOnSlots
    val wallSettings by lineupStore.wallSettings

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

    // Push curation into the ticker whenever the operator's settings OR
    // the feed change: hidden leagues filter the sports mode; when news is
    // on, build the news entries from the feed the wall already polls (no
    // duplicate fetch) using the same source denylist as the feed pane.
    val feedSnapshotForTicker = feed.state.collectAsState().value.snapshot
    LaunchedEffect(
        wallSettings.hiddenLeagues,
        wallSettings.tickerNewsEnabled,
        wallSettings.hiddenSources,
        feedSnapshotForTicker,
    ) {
        val news = if (wallSettings.tickerNewsEnabled) {
            HelperTickerSource.newsEntries(
                items = feedSnapshotForTicker?.items.orEmpty(),
                hiddenSources = wallSettings.hiddenSources,
            )
        } else emptyList()
        ticker.setCuration(
            hiddenLeagues = wallSettings.hiddenLeagues,
            newsEnabled = wallSettings.tickerNewsEnabled,
            news = news,
        )
    }

    // The slot list — single source of truth.
    val state by channels.state.collectAsState()
    val allChannels = remember(state.snapshot) { state.snapshot?.channels.orEmpty() }
    val playable = remember(allChannels) { allChannels.filter { it.isPlayable } }
    // The grid size is operator-configurable at runtime (MENU → Settings →
    // Video grid); the build-time `tileCount` is the initial default the setting
    // defaults to (Four = 4). Channel overrides are keyed by slot index, so the
    // operator's per-cell channel choices survive a grid-size change for the
    // slots that still exist.
    val effectiveTileCount = wallSettings.gridCells   // rows × cols
    val defaultOrder = remember(playable, effectiveTileCount) {
        LineupSelector.forWall(maxCount = effectiveTileCount).invoke(playable)
    }
    val slots = remember(effectiveTileCount, defaultOrder, allChannels, overrides) {
        TileSlotResolver.resolve(
            tileCount = effectiveTileCount,
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

    // Single coherent, focus-INDEPENDENT BACK handler (Part A). Enabled whenever
    // ANY overlay is open; it pops one level via the pure `menuBackOutcome`. This
    // guarantees BACK is consumed from inside any menu/overlay — it can never fall
    // through to the Activity (→ launcher), the intermittent-exit bug (which hit
    // when a sub-overlay was open but its content didn't hold focus, so no
    // overlay's own key handler caught BACK). At the bare wall (Pass) the handler
    // is DISABLED, so root BACK backgrounds the app — correct only there.
    val backOutcome = menuBackOutcome(menu.isOpen, menu.pendingSelection)
    BackHandler(enabled = backOutcome != BackOutcome.Pass) {
        when (val o = backOutcome) {
            is BackOutcome.ToSlotControls -> menu.openControls(o.slotIndex)
            BackOutcome.DismissOverlay -> menu.dismissSelection()
            BackOutcome.CloseMenu -> menu.close()
            BackOutcome.Pass -> Unit // handler disabled in this state; unreachable
        }
    }

    val rootFocusRequester = remember { FocusRequester() }
    // Deterministically RE-HOME focus to the wall root whenever the menu fully
    // closes (or a modal opened straight from a grid cell dismisses). This is
    // the fix for the Onn-box focus-escape the operator hit (focus lost /
    // leaking to the video "main panel" on menu exit): we yield a frame so the
    // dismissed overlay releases focus FIRST, then explicitly claim it
    // (runCatching so a transient detached requester can't crash). The video
    // surfaces are non-focusable (StreamSurface), so nothing else can grab it.
    // The pendingSelection key matters when a modal was opened straight from a
    // focused grid cell (no side menu) — without it the wall's onPreviewKeyEvent
    // stops receiving D-pad after the modal goes away.
    LaunchedEffect(menu.isOpen, menu.pendingSelection) {
        if (!menu.isOpen && menu.pendingSelection == null) {
            withFrameNanos { }
            runCatching { rootFocusRequester.requestFocus() }
        }
    }

    // Explicit column count from the operator's R×C setting (clamped to the
    // actual slot count) — drives the grid layout AND the focus model's
    // row/column nav, so D-pad movement matches the visible R×C exactly.
    val gridColumns = wallSettings.gridCols.coerceIn(1, slots.size.coerceAtLeast(1))

    // Manual stream-reconnect signal (Part D): bumping the nonce triggers a
    // reconnect inside VideoGrid (the StreamPlayerManager lives there). slot
    // index -1 = all tiles; >=0 = that one tile.
    var reconnectNonce by remember { mutableIntStateOf(0) }
    var reconnectSlot by remember { mutableIntStateOf(-1) }
    fun requestReconnect(slot: Int) {
        reconnectSlot = slot
        reconnectNonce++
    }

    // Quick RESYNC signal (Part D) — jump to the live edge (drop the backlog), or
    // reconnect a tile that's actually dead. Lighter than a full reconnect, reusing
    // the live-edge primitive. slot -1 = all tiles; >=0 = that one. A brief,
    // self-dismissing "Resyncing…" flash is the ONLY chrome (no persistent button).
    var resyncNonce by remember { mutableIntStateOf(0) }
    var resyncSlot by remember { mutableIntStateOf(-1) }
    var resyncFlash by remember { mutableStateOf(false) }
    fun requestResync(slot: Int) {
        resyncSlot = slot
        resyncNonce++
        resyncFlash = true
    }
    // Long-press SELECT timing for the per-tile resync gesture (Part D).
    var centerDownAtMs by remember { mutableLongStateOf(0L) }
    // Auto-dismiss the brief "Resyncing…" flash (no persistent chrome).
    LaunchedEffect(resyncNonce) {
        if (resyncNonce <= 0) return@LaunchedEffect
        resyncFlash = true
        delay(1500)
        resyncFlash = false
    }

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

    // Side-aware dispatch wrapper — pass the operator's current
    // feedSide into the focus model so LEFT/RIGHT spatial rules match
    // the visible layout.
    fun dispatchNavWithSide(intent: NavIntent): Boolean {
        val result = WallFocusModel.apply(
            focus = focus,
            intent = intent,
            feedItemCount = feedItemCount,
            gridTileCount = slots.size,
            gridColumns = gridColumns,
            feedSide = wallSettings.feedSide,
        )
        return when (result) {
            is NavResult.Stay -> true
            is NavResult.Focus -> { focus = result.focus; true }
            is NavResult.OpenMenu -> { menu.open(); true }
            is NavResult.OpenSlotControls -> { menu.openControls(result.slotIndex); true }
            is NavResult.BackBubble -> false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WallColors.Background)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                // Part D — long-press SELECT on a FOCUSED VIDEO TILE = quick RESYNC
                // that tile (jump to live, or reconnect if dead). Short press = open
                // its controls. We act on KeyUp here ONLY for the grid SELECT so we
                // can tell a tap from a hold; every other key keeps its KeyDown
                // behaviour untouched (this is the one place the wall reads KeyUp).
                val onGridTile = !menu.isOpen && menu.pendingSelection == null &&
                    focus.active == WallZone.Grid
                if (onGridTile && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            if (centerDownAtMs == 0L) centerDownAtMs = SystemClock.uptimeMillis()
                            return@onPreviewKeyEvent true
                        }
                        KeyEventType.KeyUp -> {
                            val held = SystemClock.uptimeMillis() - centerDownAtMs
                            centerDownAtMs = 0L
                            if (held >= LONG_PRESS_RESYNC_MS) requestResync(focus.gridIndex)
                            else dispatchNavWithSide(NavIntent.Select)
                            return@onPreviewKeyEvent true
                        }
                        else -> return@onPreviewKeyEvent false
                    }
                }

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
                // Side menu closed → route through the wall focus
                // model, passing the operator's current feedSide so
                // LEFT/RIGHT spatial rules match the visible layout.
                when (event.key) {
                    Key.DirectionUp -> dispatchNavWithSide(NavIntent.Up)
                    Key.DirectionDown -> dispatchNavWithSide(NavIntent.Down)
                    Key.DirectionLeft -> dispatchNavWithSide(NavIntent.Left)
                    Key.DirectionRight -> dispatchNavWithSide(NavIntent.Right)
                    Key.DirectionCenter, Key.Enter -> dispatchNavWithSide(NavIntent.Select)
                    Key.Back -> dispatchNavWithSide(NavIntent.Back)
                    else -> false
                }
            },
    ) {
      // Panel-fit (2026-06-07): an overscan-safe inset + a global UI scale.
      // The inset is measured in REAL screen space (BoxWithConstraints,
      // OUTSIDE the density override) so it stays a true physical safe-area
      // margin; the LocalDensity override then scales ALL wall chrome
      // (ticker, feed, grid, labels, overlays) together inside that inset.
      // The black root Box shows through the inset margin.
      BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val insetH = maxWidth * wallSettings.overscan.fraction
        val insetV = maxHeight * wallSettings.overscan.fraction
        val baseDensity = LocalDensity.current
        val scaledDensity = Density(
            density = baseDensity.density * wallSettings.uiScale.multiplier,
            fontScale = baseDensity.fontScale,
        )
        // Fit scale — a uniform downscale of the WHOLE wall anchored at the
        // TOP-LEFT corner (transformOrigin 0,0), for a panel that renders the
        // wall larger than its visible area from a top-left origin. Shrinking
        // keeps top-left pinned and brings the off-screen bottom-right back in;
        // black fills the freed bottom/right. A pure render transform on top of
        // the inset/offset/density layout, so it composes with them.
        val fitScale = wallSettings.fitScalePct / 100f
        // Vertical stretch — an EXTRA height-only factor on top of the uniform
        // fit scale (also top-left anchored), to close a residual bottom band
        // without moving the sides. 100% = none.
        val fitScaleY = fitScale * (wallSettings.fitStretchYPct / 100f)
        // Position offset (real screen space — applied OUTSIDE the density
        // override, like the inset, so it's a true physical nudge that recenters
        // a shifted panel regardless of the Display-size scale).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = fitScale
                    scaleY = fitScaleY
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                .offset(x = wallSettings.offsetXDp.dp, y = wallSettings.offsetYDp.dp)
                .padding(horizontal = insetH, vertical = insetV),
        ) {
          CompositionLocalProvider(LocalDensity provides scaledDensity) {
        val wallAlpha = if (menu.isOpen || menu.pendingSelection != null) 0.45f else 1f
        Column(modifier = Modifier.fillMaxSize().alpha(wallAlpha)) {
            TickerStrip(
                source = ticker,
                focused = focus.active == WallZone.Ticker,
                paused = focus.tickerPaused,
                scrollPct = wallSettings.tickerScrollPct,
                flipPct = wallSettings.tickerFlipPct,
                motion = wallSettings.tickerMotion,
            )
            Divider(color = Color(0x22FFFFFF), thickness = 1.dp)
            // Compose three children — the feed pane, a thin divider,
            // and the video grid — and lay them out in the order
            // dictated by the operator's `feedSide` setting. Defining
            // them once and choosing the order at the Row's call site
            // keeps the children's modifiers (focus/recompose keys,
            // weights, focus binding) identical across orientations —
            // a side-swap doesn't recreate the FeedRepository
            // subscription or the StreamPlayerManager.
            val feedPane = @Composable {
                FeedPane(
                    repository = feed,
                    modifier = Modifier.fillMaxHeight().fillMaxWidth(wallSettings.feedWidth.fraction),
                    focusedIndex = if (focus.active == WallZone.Feed) focus.feedIndex else null,
                    expandedIndex = if (focus.active == WallZone.Feed && focus.feedExpanded) {
                        focus.feedIndex
                    } else null,
                    onItemCountChanged = { feedItemCount = it },
                    fontScale = wallSettings.feedFontScale.multiplier,
                    hiddenSources = wallSettings.hiddenSources,
                    hiddenLeagues = wallSettings.hiddenLeagues,
                    feedRecency = wallSettings.feedRecency,
                )
            }
            val divider = @Composable {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(Color(0x22FFFFFF)),
                )
            }
            val videoGrid = @Composable {
                VideoGrid(
                    slots = slots,
                    columns = gridColumns,
                    reconnectNonce = reconnectNonce,
                    reconnectSlot = reconnectSlot,
                    resyncNonce = resyncNonce,
                    resyncSlot = resyncSlot,
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
            Row(modifier = Modifier.fillMaxSize()) {
                if (wallSettings.feedSide == FeedSide.Left) {
                    feedPane(); divider(); videoGrid()
                } else {
                    videoGrid(); divider(); feedPane()
                }
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
            onSettingsSelected = { menu.openSettings() },
            onResyncAll = { requestResync(-1); menu.close() },
            modifier = Modifier.fillMaxSize(),
            feedSide = wallSettings.feedSide,
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
                onReconnect = { requestReconnect(pending.slotIndex); menu.dismissSelection() },
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
                    // Flow-smoothing (Part A): picking a channel is the primary
                    // reason the picker is open, so SELECT completes straight to
                    // the WALL — one less hop. The other slot controls (audio /
                    // captions / reconnect) stay one tile-select away. BACK from
                    // the picker still steps up to the controls (onCancel), so
                    // cancel and commit have distinct, sensible destinations.
                    menu.close()
                },
                onCancel = { menu.openControls(pending.slotIndex) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (pending is MenuState.PendingSelection.Settings) {
            SettingsOverlay(
                settings = wallSettings,
                onCycleFeedWidth = { lineupStore.cycleFeedWidth() },
                onCycleFeedFontScale = { lineupStore.cycleFeedFontScale() },
                onCycleFeedSide = { lineupStore.cycleFeedSide() },
                onCycleFeedRecency = { lineupStore.cycleFeedRecency() },
                onOpenSourceFilter = { menu.openSourceFilter() },
                onToggleTickerNews = { lineupStore.toggleTickerNews() },
                onOpenLeagueFilter = { menu.openLeagueFilter() },
                onCycleUiScale = { lineupStore.cycleUiScale() },
                onCycleOverscan = { lineupStore.cycleOverscan() },
                onNudgeOffsetX = { delta -> lineupStore.nudgeOffsetX(delta) },
                onNudgeOffsetY = { delta -> lineupStore.nudgeOffsetY(delta) },
                onNudgeFitScale = { delta -> lineupStore.nudgeFitScale(delta) },
                onNudgeFitStretchY = { delta -> lineupStore.nudgeFitStretchY(delta) },
                onNudgeGridRows = { delta -> lineupStore.nudgeGridRows(delta) },
                onNudgeGridCols = { delta -> lineupStore.nudgeGridCols(delta) },
                onNudgeTickerScroll = { delta -> lineupStore.nudgeTickerScroll(delta) },
                onNudgeTickerFlip = { delta -> lineupStore.nudgeTickerFlip(delta) },
                onCycleTickerMotion = { lineupStore.cycleTickerMotion() },
                onRefreshAllVideo = { requestReconnect(-1) },
                onToggleCalibration = { lineupStore.toggleCalibration() },
                onOpenHelperUrl = { menu.dismissSelection(); onOpenHelperUrl() },
                onCancel = { menu.dismissSelection() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (pending is MenuState.PendingSelection.SportsLeagueFilter) {
            // Curation: toggle which sports leagues the ticker shows.
            // The offered set is the helper's default leagues (MLB/NFL/
            // NBA/NHL); filtering is TV-side (the helper serves all).
            SourceFilterOverlay(
                sources = CURATED_LEAGUES,
                hiddenSources = wallSettings.hiddenLeagues,
                onToggle = { league -> lineupStore.toggleHiddenLeague(league) },
                onCancel = { menu.openSettings() },
                modifier = Modifier.fillMaxSize(),
                title = "SPORTS LEAGUES",
                emptyText = "No leagues configured.",
                // Honest roadmap: any sport still staged behind its bespoke card
                // shows as coming-soon (none now — all four shipped).
                footerNote = STAGED_LEAGUES.takeIf { it.isNotEmpty() }
                    ?.let { "Coming soon: ${it.joinToString(" · ")}" },
            )
        }
        if (pending is MenuState.PendingSelection.SourceFilter) {
            // Distinct sources from the current feed snapshot drive the
            // toggle list. Toggling persists to the denylist; BACK returns
            // to the settings overlay (calmer than punting to the wall).
            val feedSnapshot = feed.state.collectAsState().value.snapshot
            val feedSources = remember(feedSnapshot) {
                FeedListBuilder.distinctSources(feedSnapshot?.items.orEmpty())
            }
            SourceFilterOverlay(
                sources = feedSources,
                hiddenSources = wallSettings.hiddenSources,
                onToggle = { source -> lineupStore.toggleHiddenSource(source) },
                onCancel = { menu.openSettings() },
                modifier = Modifier.fillMaxSize(),
            )
        }
          } // CompositionLocalProvider (global UI scale)
          // Panel-fit calibration overlay — a sibling of the scaled wall inside
          // the inset Box but OUTSIDE the density override, so it fills the TRUE
          // wall content rectangle (after position offset + overscan inset) and
          // draws real-px boundary lines exactly where the content edge is.
          // Drawn last → on top, so the operator can see which edges the panel
          // crops. Toggled from WALL SETTINGS; default off.
          if (wallSettings.calibrationBorder) {
              CalibrationOverlay(modifier = Modifier.fillMaxSize())
          }
          // Brief, self-dismissing "Resyncing…" flash (Part D) — the ONLY resync
          // chrome; no persistent button cluttering the ambient wall.
          ResyncFlash(visible = resyncFlash, modifier = Modifier.fillMaxSize())
        }   // inset Box
      }     // BoxWithConstraints (overscan safe-area)
    }
}

/** The brief "Resyncing…" flash (Part D) — a centered chip shown for ~1.5s when
 *  the operator triggers a resync, then gone. No persistent wall chrome. */
@Composable
private fun ResyncFlash(visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .background(Color(0xCC000000))
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(
                text = "Resyncing…",
                color = WallColors.LabelPrimary,
                fontSize = 14.sp,
                letterSpacing = 1.sp,
            )
        }
    }
}

/**
 * The sports leagues offered in the curation toggle list — the helper's
 * default scoreboard set (`ticker/sports.py::DEFAULT_LEAGUES`). Filtering
 * is TV-side (the helper serves all of these; the operator hides the ones
 * they don't want in the ticker). Kept in sync with the helper's default
 * set; if the helper adds a league, add it here too.
 */
// The configurable sports pool — the eight team leagues ESPN's keyless
// scoreboard exposes in the standard shape (2026-06-10). The operator picks
// which actually cycle via the in-menu "Sports leagues…" filter (enabled =
// pool); the helper fetches this set. UFC/PGA/tennis/F1 are structurally
// different and staged (see docs/findings/19 + BACKLOG). Kept in sync with the
// helper's sports.DEFAULT_LEAGUES.
private val CURATED_LEAGUES =
    listOf("NFL", "NCAAF", "UFL", "NBA", "WNBA", "NCAAB", "MLB", "NHL", "PGA", "UFC", "Tennis", "F1")

/**
 * Sports still staged behind their bespoke cards — shown as "coming soon" in
 * the picker, not offered as toggles. **Now empty**: UFC/PGA/Tennis/F1 all
 * shipped (2026-06-11), so every supported sport is a live toggle.
 */
private val STAGED_LEAGUES = emptyList<String>()

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
