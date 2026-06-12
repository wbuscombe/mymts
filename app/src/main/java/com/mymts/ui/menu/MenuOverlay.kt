package com.mymts.ui.menu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymts.data.settings.FeedSide

/**
 * The wall's side menu.
 *
 * Slides in from the left when [state].`isOpen` flips. The wall keeps
 * playing behind a translucent scrim — calm, not jarring (Vision §4).
 * Each row is a Compose-focusable element; UP/DOWN navigates between
 * them via the standard focus system, SELECT/CENTER triggers the row's
 * `onSelect` callback. BACK is handled at the screen level (see
 * [com.mymts.ui.wall.WallScreen]) and closes the menu.
 *
 * Stage 5 (this commit) renders one **slot row per wall tile**: each
 * row shows "Slot N · current channel" and SELECT will open the channel
 * picker (checkpoint 2). The version footer matches WyzeGrid's
 * vocabulary so the two apps read as the same family.
 */
@Composable
fun MenuOverlay(
    state: MenuState,
    slotRows: List<SlotRow>,
    versionLine: String,
    onSlotSelected: (Int) -> Unit,
    onSettingsSelected: () -> Unit,
    modifier: Modifier = Modifier,
    feedSide: FeedSide = FeedSide.Left,
) {
    AnimatedVisibility(
        visible = state.isOpen,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(120)),
        modifier = modifier.fillMaxSize(),
    ) {
        // Panel sits on the same side as the feed pane (its anchor
        // edge). When the operator has the feed on the right, the
        // panel slides in from the right; the gesture that opens it
        // (RIGHT-from-feed in feed-right layout) feels spatially
        // correct — the menu appears where the eye is already
        // pointed.
        val isLeft = feedSide == FeedSide.Left
        val alignment = if (isLeft) Alignment.CenterStart else Alignment.CenterEnd
        Box(
            modifier = Modifier.fillMaxSize().background(MenuColors.Scrim),
            contentAlignment = alignment,
        ) {
            AnimatedVisibility(
                visible = state.isOpen,
                enter = slideInHorizontally(tween(180)) { if (isLeft) -it else it } + fadeIn(tween(140)),
                exit = slideOutHorizontally(tween(140)) { if (isLeft) -it else it } + fadeOut(tween(120)),
            ) {
                MenuPanel(
                    // When a sub-overlay (settings / picker / controls) is up,
                    // IT owns focus; when it dismisses, the side menu must
                    // reclaim focus (keyed below) rather than leave it unset.
                    subOverlayActive = state.pendingSelection != null,
                    slotRows = slotRows,
                    versionLine = versionLine,
                    onSlotSelected = onSlotSelected,
                    onSettingsSelected = onSettingsSelected,
                )
            }
        }
    }
}

@Composable
private fun MenuPanel(
    subOverlayActive: Boolean,
    slotRows: List<SlotRow>,
    versionLine: String,
    onSlotSelected: (Int) -> Unit,
    onSettingsSelected: () -> Unit,
) {
    val firstRowFocusRequester = remember { FocusRequester() }

    // Claim focus on first open AND whenever a sub-overlay dismisses back to
    // the side menu (key flips false). Yield a frame first so the dismissed
    // overlay releases focus before we reclaim it — deterministic re-home,
    // never a dead/unset focus state on the way back from Settings/picker.
    LaunchedEffect(subOverlayActive) {
        if (!subOverlayActive) {
            withFrameNanos { }
            runCatching { firstRowFocusRequester.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(MenuColors.PanelBackground),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            PanelHeader()
            Divider(color = MenuColors.PanelDivider, thickness = 1.dp)
            slotRows.forEachIndexed { idx, row ->
                MenuRow(
                    row = row,
                    onSelect = { onSlotSelected(row.slotIndex) },
                    modifier = if (idx == 0) {
                        Modifier.focusRequester(firstRowFocusRequester)
                    } else {
                        Modifier
                    },
                )
            }
            // UX & Config chapter — operator's wall-layout controls
            // live in a small section below the channels block. Same
            // WyzeGrid family: a section title + a focusable row.
            Divider(color = MenuColors.PanelDivider, thickness = 1.dp)
            SectionTitle("WALL")
            MenuRow(
                row = SlotRow(
                    slotIndex = -1,
                    title = "Settings",
                    detail = "layout · width · font · side",
                    detailStyle = SlotRow.DetailStyle.Default,
                ),
                onSelect = onSettingsSelected,
            )
        }
        Column {
            Divider(color = MenuColors.PanelDivider, thickness = 1.dp)
            FooterLine(versionLine)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            color = MenuColors.RowLabel,
            fontSize = 13.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PanelHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = "CHANNELS",
            color = MenuColors.RowLabel,
            fontSize = 13.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MenuRow(
    row: SlotRow,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isFocused) MenuColors.FocusBackground else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelect,
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (isFocused) MenuColors.FocusAccent else Color.Transparent),
        ) { Text(text = " ", fontSize = 16.sp) }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = row.title,
                color = if (isFocused) MenuColors.RowLabel else MenuColors.RowLabelMuted,
                fontSize = 14.sp,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = row.detail,
                color = when (row.detailStyle) {
                    SlotRow.DetailStyle.Live -> MenuColors.RowDetail
                    SlotRow.DetailStyle.Offline -> MenuColors.RowDetailOffline
                    SlotRow.DetailStyle.Default -> MenuColors.RowDetailMuted
                    SlotRow.DetailStyle.Empty -> MenuColors.RowLabelMuted
                },
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun FooterLine(versionLine: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = versionLine, color = MenuColors.Footer, fontSize = 10.sp)
    }
}

data class SlotRow(
    val slotIndex: Int,
    val title: String,
    val detail: String,
    val detailStyle: DetailStyle,
) {
    enum class DetailStyle { Live, Offline, Default, Empty }
}
