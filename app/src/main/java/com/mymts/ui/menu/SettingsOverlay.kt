package com.mymts.ui.menu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymts.data.settings.FIT_SCALE_STEP_PCT
import com.mymts.data.settings.FIT_STRETCH_Y_STEP_PCT
import com.mymts.data.settings.OFFSET_STEP_DP
import com.mymts.data.settings.TICKER_SPEED_STEP_PCT
import com.mymts.data.settings.WallScaleSteps
import com.mymts.data.settings.WallSettings

/**
 * Wall settings overlay — UX & Config chapter (2026-06-04), reorganized into
 * labelled SECTIONS (2026-06-11).
 *
 * The settings grew organically across many chapters into a long flat list;
 * they're now grouped under section headers — **Display & Fit**, **Layout &
 * Feed**, **Sports** — while keeping the exact single-level D-pad nav (the
 * headers are non-focusable, so UP/DOWN focus traversal skips them and every
 * row stays reachable — no two-level nav, no focus traps). The card
 * **vertical-scrolls** and follows focus, so the longer grouped list never
 * clips on the panel. The operator focuses a row, presses LEFT/RIGHT (or
 * SELECT) to cycle/adjust, and the wall updates live (each commit persists via
 * [com.mymts.data.lineup.LineupStore]). BACK dismisses — the side menu stays
 * open underneath.
 *
 * This is a presentation/organization change ONLY: every setting is preserved
 * at its current persisted value (the locked panel-fit especially — Fit scale /
 * Vertical stretch / Overscan / Position are regrouped, never reset).
 *
 * No new fetch surface (A1 unchanged): all controls write only to the existing
 * on-device SharedPreferences blob; no network, no helper call, no markup.
 */
@Composable
fun SettingsOverlay(
    settings: WallSettings,
    onNudgeFeedWidth: (Int) -> Unit,
    onNudgeFeedText: (Int) -> Unit,
    onNudgeTickerHeight: (Int) -> Unit,
    onNudgeTickerText: (Int) -> Unit,
    onCycleFeedSide: () -> Unit,
    onCycleFeedRecency: () -> Unit,
    onOpenNewsFilter: () -> Unit,
    onToggleTickerNews: () -> Unit,
    onOpenLeagueFilter: () -> Unit,
    onCycleUiScale: () -> Unit,
    onCycleOverscan: () -> Unit,
    onNudgeOffsetX: (Int) -> Unit,
    onNudgeOffsetY: (Int) -> Unit,
    onNudgeFitScale: (Int) -> Unit,
    onNudgeFitStretchY: (Int) -> Unit,
    onNudgeGridRows: (Int) -> Unit,
    onNudgeGridCols: (Int) -> Unit,
    onNudgeTickerScroll: (Int) -> Unit,
    onNudgeTickerFlip: (Int) -> Unit,
    onCycleTickerMotion: () -> Unit,
    onRefreshAllVideo: () -> Unit,
    onToggleCalibration: () -> Unit,
    onOpenHelperUrl: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MenuColors.Scrim),
        contentAlignment = Alignment.Center,
    ) {
        // Cap the card to the visible area so the grouped (taller) list scrolls
        // rather than clipping off the panel's top/bottom.
        val maxCardHeight = maxHeight - 24.dp
        AnimatedVisibility(
            visible = true,
            enter = scaleIn(tween(160), initialScale = 0.92f) + fadeIn(tween(160)),
            exit = scaleOut(tween(120), targetScale = 0.92f) + fadeOut(tween(120)),
        ) {
            SettingsCard(
                maxCardHeight = maxCardHeight,
                settings = settings,
                onNudgeFeedWidth = onNudgeFeedWidth,
                onNudgeFeedText = onNudgeFeedText,
                onNudgeTickerHeight = onNudgeTickerHeight,
                onNudgeTickerText = onNudgeTickerText,
                onCycleFeedSide = onCycleFeedSide,
                onCycleFeedRecency = onCycleFeedRecency,
                onOpenNewsFilter = onOpenNewsFilter,
                onToggleTickerNews = onToggleTickerNews,
                onOpenLeagueFilter = onOpenLeagueFilter,
                onCycleUiScale = onCycleUiScale,
                onCycleOverscan = onCycleOverscan,
                onNudgeOffsetX = onNudgeOffsetX,
                onNudgeOffsetY = onNudgeOffsetY,
                onNudgeFitScale = onNudgeFitScale,
                onNudgeFitStretchY = onNudgeFitStretchY,
                onNudgeGridRows = onNudgeGridRows,
                onNudgeGridCols = onNudgeGridCols,
                onNudgeTickerScroll = onNudgeTickerScroll,
                onNudgeTickerFlip = onNudgeTickerFlip,
                onCycleTickerMotion = onCycleTickerMotion,
                onRefreshAllVideo = onRefreshAllVideo,
                onToggleCalibration = onToggleCalibration,
                onOpenHelperUrl = onOpenHelperUrl,
                onCancel = onCancel,
            )
        }
    }
}

@Composable
private fun SettingsCard(
    maxCardHeight: Dp,
    settings: WallSettings,
    onNudgeFeedWidth: (Int) -> Unit,
    onNudgeFeedText: (Int) -> Unit,
    onNudgeTickerHeight: (Int) -> Unit,
    onNudgeTickerText: (Int) -> Unit,
    onCycleFeedSide: () -> Unit,
    onCycleFeedRecency: () -> Unit,
    onOpenNewsFilter: () -> Unit,
    onToggleTickerNews: () -> Unit,
    onOpenLeagueFilter: () -> Unit,
    onCycleUiScale: () -> Unit,
    onCycleOverscan: () -> Unit,
    onNudgeOffsetX: (Int) -> Unit,
    onNudgeOffsetY: (Int) -> Unit,
    onNudgeFitScale: (Int) -> Unit,
    onNudgeFitStretchY: (Int) -> Unit,
    onNudgeGridRows: (Int) -> Unit,
    onNudgeGridCols: (Int) -> Unit,
    onNudgeTickerScroll: (Int) -> Unit,
    onNudgeTickerFlip: (Int) -> Unit,
    onCycleTickerMotion: () -> Unit,
    onRefreshAllVideo: () -> Unit,
    onToggleCalibration: () -> Unit,
    onOpenHelperUrl: () -> Unit,
    onCancel: () -> Unit,
) {
    val firstRowFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstRowFocusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .widthIn(min = 420.dp)
            .heightIn(max = maxCardHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(MenuColors.PanelBackground)
            .onPreviewKeyEvent { event ->
                // Card-level BACK so the operator can dismiss from
                // any focused row without having to navigate first.
                if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                    onCancel(); true
                } else false
            }
            // Scrolls when the grouped list is taller than the panel; the
            // focused row auto-scrolls into view (Compose bring-into-view).
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "WALL SETTINGS",
            color = MenuColors.RowLabelMuted,
            fontSize = 10.sp,
            letterSpacing = 3.sp,
            fontWeight = FontWeight.SemiBold,
        )

        // ── Display & Fit ────────────────────────────────────────────────
        // The panel-tuning levers. VALUES are the operator's locked fit
        // (Fit 80% / Stretch 110% / Overscan None / Position 0,0) — grouped
        // here, never reset. The operator lands on "Display size".
        SectionHeader("Display & Fit")
        SettingRow(
            title = "Display size",
            valueLabel = settings.uiScale.displayName,
            onCycle = onCycleUiScale,
            modifier = Modifier.focusRequester(firstRowFocusRequester),
        )
        // Fit scale — shrink the whole wall toward the TOP-LEFT corner so a
        // panel that overflows the bottom/right edges pulls back into view
        // (top-left stays pinned). LEFT = smaller, RIGHT = larger.
        AdjustRow(
            title = "Fit scale  ‹ smaller · larger ›",
            valueLabel = "${settings.fitScalePct}%",
            onLeft = { onNudgeFitScale(-FIT_SCALE_STEP_PCT) },
            onRight = { onNudgeFitScale(FIT_SCALE_STEP_PCT) },
        )
        // Vertical stretch — height-only grow (top-left anchored) to close a
        // residual bottom band after Fit scale has seated the sides.
        AdjustRow(
            title = "Vertical stretch  ‹ less · more ›",
            valueLabel = "${settings.fitStretchYPct}%",
            onLeft = { onNudgeFitStretchY(-FIT_STRETCH_Y_STEP_PCT) },
            onRight = { onNudgeFitStretchY(FIT_STRETCH_Y_STEP_PCT) },
        )
        SettingRow(
            title = "Overscan inset",
            valueLabel = settings.overscan.displayName,
            onCycle = onCycleOverscan,
        )
        // Position offset — recenter a panel that overscans off-center.
        AdjustRow(
            title = "Position X  ‹ left · right ›",
            valueLabel = formatOffset(settings.offsetXDp),
            onLeft = { onNudgeOffsetX(-OFFSET_STEP_DP) },
            onRight = { onNudgeOffsetX(OFFSET_STEP_DP) },
        )
        AdjustRow(
            title = "Position Y  ‹ up · down ›",
            valueLabel = formatOffset(settings.offsetYDp),
            onLeft = { onNudgeOffsetY(-OFFSET_STEP_DP) },
            onRight = { onNudgeOffsetY(OFFSET_STEP_DP) },
        )
        // Calibration border — draws a bright outline + labelled corners so the
        // operator can SEE which edges the panel crops while dialing the fit.
        SettingRow(
            title = "Calibration border",
            valueLabel = if (settings.calibrationBorder) "On" else "Off",
            onCycle = onToggleCalibration,
        )

        // ── Layout & Feed ────────────────────────────────────────────────
        SectionHeader("Layout & Feed")
        // Video grid — independent ROWS × COLUMNS (each 1–3). Cell count =
        // rows × cols; each cell is a [video + label] unit, grid-agnostic.
        AdjustRow(
            title = "Grid rows  ‹ fewer · more ›",
            valueLabel = "${settings.gridRows}",
            onLeft = { onNudgeGridRows(-1) },
            onRight = { onNudgeGridRows(1) },
        )
        AdjustRow(
            title = "Grid columns  ‹ fewer · more ›",
            valueLabel = "${settings.gridCols}",
            onLeft = { onNudgeGridCols(-1) },
            onRight = { onNudgeGridCols(1) },
        )
        // The four view tunables — 1..10 steps driven by the D-pad, matching /control/
        // rung-for-rung (PR-026). AdjustRow (not SettingRow) because ten rungs must be
        // nudged left/right and CLAMP at the ends; cycling would wrap widest→narrowest.
        // The label shows the step AND what it resolves to, exactly like the web panel.
        AdjustRow(
            title = "Feed width  ‹ narrower · wider ›",
            valueLabel = WallScaleSteps.feedWidthLabel(settings.feedWidthStep),
            onLeft = { onNudgeFeedWidth(-1) },
            onRight = { onNudgeFeedWidth(1) },
        )
        AdjustRow(
            title = "Feed text  ‹ smaller · larger ›",
            valueLabel = WallScaleSteps.scaleLabel(
                settings.feedTextStep, WallScaleSteps.feedTextScale(settings.feedTextStep)
            ),
            onLeft = { onNudgeFeedText(-1) },
            onRight = { onNudgeFeedText(1) },
        )
        AdjustRow(
            title = "Ticker height  ‹ shorter · taller ›",
            valueLabel = WallScaleSteps.scaleLabel(
                settings.tickerHeightStep, WallScaleSteps.tickerHeightScale(settings.tickerHeightStep)
            ),
            onLeft = { onNudgeTickerHeight(-1) },
            onRight = { onNudgeTickerHeight(1) },
        )
        AdjustRow(
            title = "Ticker text  ‹ smaller · larger ›",
            valueLabel = WallScaleSteps.scaleLabel(
                settings.tickerTextStep, WallScaleSteps.tickerTextScale(settings.tickerTextStep)
            ),
            onLeft = { onNudgeTickerText(-1) },
            onRight = { onNudgeTickerText(1) },
        )
        SettingRow(
            title = "Feed side",
            valueLabel = settings.feedSide.displayName,
            onCycle = onCycleFeedSide,
        )
        SettingRow(
            title = "Feed recency",
            valueLabel = settings.feedRecency.displayName,
            onCycle = onCycleFeedRecency,
        )
        // Manual refresh of every video tile (reload streams that have drifted /
        // stalled). SELECT triggers it; honest play-what-works on the result.
        SettingRow(
            title = "Refresh all video",
            valueLabel = "reload",
            onCycle = onRefreshAllVideo,
        )
        // Runtime helper address — point a stock build at any helper, no rebuild.
        // SELECT opens the URL editor (reachability-tested before it's saved).
        SettingRow(
            title = "Helper URL…",
            valueLabel = "change…",
            onCycle = onOpenHelperUrl,
        )

        // ── News ─────────────────────────────────────────────────────────
        // The two-level genre → source filter (news-genre-groups chapter). The
        // value summarises how many genres + sources are switched off (the
        // Sports genre's per-league toggles live in the Sports section's pool).
        SectionHeader("News")
        val newsOff = settings.hiddenGenres.size + settings.hiddenSources.size
        SettingRow(
            title = "News genres & sources…",
            valueLabel = if (newsOff == 0) "all shown" else "$newsOff off",
            onCycle = onOpenNewsFilter,   // SELECT/LEFT/RIGHT all open the sub-overlay
        )

        // ── Ticker ───────────────────────────────────────────────────────
        // Scroll/flip speed sliders (D-pad LEFT/RIGHT steps the percent; the
        // wall updates live). Bounded so neither gets unreadably fast/slow.
        SectionHeader("Ticker")
        // Motion (cross-platform setting): Flip is the wall's established paged
        // flip; Crawl is the web wall's continuous marquee, offered here too.
        SettingRow(
            title = "Ticker motion",
            valueLabel = settings.tickerMotion.displayName,
            onCycle = onCycleTickerMotion,
        )
        AdjustRow(
            title = "Scroll speed  ‹ slower · faster ›",
            valueLabel = "${settings.tickerScrollPct}%",
            onLeft = { onNudgeTickerScroll(-TICKER_SPEED_STEP_PCT) },
            onRight = { onNudgeTickerScroll(TICKER_SPEED_STEP_PCT) },
        )
        AdjustRow(
            title = "Flip speed  ‹ slower · faster ›",
            valueLabel = "${settings.tickerFlipPct}%",
            onLeft = { onNudgeTickerFlip(-TICKER_SPEED_STEP_PCT) },
            onRight = { onNudgeTickerFlip(TICKER_SPEED_STEP_PCT) },
        )

        // ── Sports ───────────────────────────────────────────────────────
        // The sports-league pool (the picker) + the ticker-news toggle. The
        // league picker drives the SAME pool as the ticker scores and the
        // sports-news in the feed.
        SectionHeader("Sports")
        val hiddenLeagues = settings.hiddenLeagues.size
        SettingRow(
            title = "Sports leagues…",
            valueLabel = if (hiddenLeagues == 0) "all shown" else "$hiddenLeagues hidden",
            onCycle = onOpenLeagueFilter,
        )
        SettingRow(
            title = "Ticker news",
            valueLabel = if (settings.tickerNewsEnabled) "On" else "Off",
            onCycle = onToggleTickerNews,
        )

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "‹  cycle  ›   ·   BACK to dismiss",
            color = MenuColors.RowLabelMuted,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
        )
    }
}

/**
 * A non-focusable section divider. UP/DOWN focus traversal skips it (it's
 * plain Text), so grouping adds zero nav complexity — every setting row stays
 * reachable with the same single-level D-pad nav.
 */
/**
 * Scroll the focused row INTO the card's viewport (the menu follows the
 * cursor). The default `.focusable()` bring-into-view scrolled flush to the
 * edge — on this overscan-clipped, fit-scaled panel that left the focused row
 * at/below the visible bottom (the operator's "cursor scrolls offscreen" bug).
 * An explicit [BringIntoViewRequester] fired on focus reliably pulls the row in.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.bringFocusedIntoView(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return this
        .bringIntoViewRequester(requester)
        .onFocusEvent { if (it.isFocused) scope.launch { requester.bringIntoView() } }
}

@Composable
private fun SectionHeader(title: String) {
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = title.uppercase(),
        color = MenuColors.FocusAccent,
        fontSize = 10.sp,
        letterSpacing = 2.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingRow(
    title: String,
    valueLabel: String,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .bringFocusedIntoView()
            .background(if (isFocused) MenuColors.FocusBackground else Color.Transparent)
            // The key handler MUST sit ABOVE .clickable()/.focusable() in the
            // chain. A key-input modifier only receives events when the focus
            // target is its DESCENDANT; placed below .focusable() it silently
            // never fires, so LEFT/RIGHT would no-op (the row still takes focus
            // and SELECT still cycles via clickable, which masks the bug).
            // Verified on-device 2026-06-09.
            .onPreviewKeyEvent { event ->
                // LEFT and RIGHT both cycle — discrete presets cycle
                // forward unidirectionally (only 2-3 values per
                // setting, wrap quickly). Documenting the gesture in
                // the card footer rather than encoding bidirectional
                // skipping that the operator can't easily see.
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> { onCycle(); true }
                    else -> false
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onCycle,
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (isFocused) MenuColors.FocusAccent else Color.Transparent),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                color = if (isFocused) MenuColors.RowLabel else MenuColors.RowLabelMuted,
                fontSize = 14.sp,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "‹", color = MenuColors.FocusAccent, fontSize = 18.sp)
            Text(
                text = valueLabel,
                color = MenuColors.RowDetail,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = "›", color = MenuColors.FocusAccent, fontSize = 18.sp)
        }
    }
}

/**
 * A bidirectional adjust row — LEFT decrements ([onLeft]), RIGHT increments
 * ([onRight]), each live-applied so the operator sees the wall move and dials
 * the value in by eye (used for the Position X/Y nudges, where a number
 * against the panel edge isn't readable). SELECT nudges RIGHT (a friendly
 * forward). Same visual register as [SettingRow].
 */
@Composable
private fun AdjustRow(
    title: String,
    valueLabel: String,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .bringFocusedIntoView()
            .background(if (isFocused) MenuColors.FocusBackground else Color.Transparent)
            // Key handler ABOVE .clickable()/.focusable() — see SettingRow note:
            // below .focusable() it never fires and the nudges silently no-op.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onLeft(); true }
                    Key.DirectionRight -> { onRight(); true }
                    else -> false
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onRight,
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (isFocused) MenuColors.FocusAccent else Color.Transparent),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                color = if (isFocused) MenuColors.RowLabel else MenuColors.RowLabelMuted,
                fontSize = 14.sp,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "‹", color = MenuColors.FocusAccent, fontSize = 18.sp)
            Text(
                text = valueLabel,
                color = MenuColors.RowDetail,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = "›", color = MenuColors.FocusAccent, fontSize = 18.sp)
        }
    }
}

/** Format a position offset (dp) for display: 0 → "0", positive → "+N dp". */
private fun formatOffset(dp: Int): String = when {
    dp == 0 -> "0"
    dp > 0 -> "+$dp dp"
    else -> "$dp dp"
}
