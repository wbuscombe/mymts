package com.mymts.ui.menu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.mymts.data.helper.Channel
import kotlinx.coroutines.launch

/**
 * The channel picker — a **scrollable LIST** (2026-06-11, replacing the old
 * 1-at-a-time left/right cycler). The operator D-pads **UP/DOWN** through every
 * channel and presses **SELECT** to assign one to the slot; **BACK** cancels.
 *
 * Focus discipline (same fixes as the menu chapter): the list **scrolls to keep
 * the focused row visible** (explicit bring-into-view, so the cursor never
 * slides into the overscan-clipped edge), it opens **focused on the slot's
 * current channel**, and closing returns focus cleanly to the controls/menu
 * underneath (the parent re-homes focus on dismiss).
 *
 * Honest live/offline marking (Trust Bar C3): the list is sorted live-first
 * (by the call site) and each row carries its real status — a `LIVE` / `OFFLINE`
 * section header + a per-row `live`/`offline` tag — so the operator picks an
 * informed channel. Assigning an offline channel is allowed (mark-and-allow;
 * the wall renders the honest C2 panel for it).
 */
@Composable
fun ChannelPickerOverlay(
    slotIndex: Int,
    channels: List<Channel>,
    currentSelection: String?,
    onAssign: (slug: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (channels.isEmpty()) return  // nothing to choose — caller decides what to do.

    val initialIndex = remember(channels, currentSelection) {
        initialChannelIndex(channels, currentSelection)
    }
    // Open scrolled so the current channel is visible (with a little lead-in
    // above it where possible), and focus it.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (initialIndex - 1).coerceAtLeast(0),
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MenuColors.Scrim),
        contentAlignment = Alignment.Center,
    ) {
        val maxCardHeight = maxHeight - 24.dp
        AnimatedVisibility(
            visible = true,
            enter = scaleIn(tween(160), initialScale = 0.92f) + fadeIn(tween(160)),
            exit = scaleOut(tween(120), targetScale = 0.92f) + fadeOut(tween(120)),
        ) {
            PickerListCard(
                slotIndex = slotIndex,
                channels = channels,
                initialIndex = initialIndex,
                listState = listState,
                maxCardHeight = maxCardHeight,
                onAssign = onAssign,
                onCancel = onCancel,
            )
        }
    }
}

@Composable
private fun PickerListCard(
    slotIndex: Int,
    channels: List<Channel>,
    initialIndex: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    maxCardHeight: Dp,
    onAssign: (slug: String) -> Unit,
    onCancel: () -> Unit,
) {
    val liveCount = remember(channels) { channels.count { it.isPlayable } }

    Column(
        modifier = Modifier
            .widthIn(min = 440.dp)
            .heightIn(max = maxCardHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(MenuColors.PanelBackground)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                    onCancel(); true
                } else false
            }
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "SLOT ${slotIndex + 1}  ·  CHOOSE CHANNEL",
            color = MenuColors.RowLabelMuted,
            fontSize = 10.sp,
            letterSpacing = 3.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(max = (maxCardHeight.value - 90f).coerceAtLeast(120f).dp),
        ) {
            itemsIndexed(channels, key = { _, c -> c.slug }) { index, channel ->
                // A light LIVE / OFFLINE section header at the group boundary
                // (the list is sorted live-first), so the grouping reads at 10ft.
                if (index == 0 && liveCount > 0) SectionLabel("LIVE", MenuColors.RowDetail)
                if (index == liveCount && index < channels.size) {
                    SectionLabel("OFFLINE", MenuColors.RowDetailOffline)
                }
                ChannelRow(
                    channel = channel,
                    isInitial = index == initialIndex,
                    onSelect = { onAssign(channel.slug) },
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "▲▼ choose      SELECT to assign      BACK to cancel",
            color = MenuColors.RowLabelMuted,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
        )
    }
}

/**
 * The list opens focused on the slot's current channel; if it's unset or no
 * longer in the list, it opens at the top. Pure — unit-tested.
 */
internal fun initialChannelIndex(channels: List<Channel>, currentSelection: String?): Int =
    currentSelection
        ?.let { sel -> channels.indexOfFirst { it.slug == sel } }
        ?.takeIf { it >= 0 } ?: 0

@Composable
private fun SectionLabel(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 9.sp,
        letterSpacing = 2.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: Channel,
    isInitial: Boolean,
    onSelect: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()

    // Open with focus on the slot's current channel.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isInitial) focusRequester.requestFocus() }

    // Scroll the focused row into view (the picker follows the cursor — no
    // sliding into the overscan-clipped edge).
    val bring = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    val statusText = if (channel.isPlayable) "live" else "offline"
    val statusColor = if (channel.isPlayable) MenuColors.RowDetail else MenuColors.RowDetailOffline

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (isInitial) it.focusRequester(focusRequester) else it }
            .bringIntoViewRequester(bring)
            .onFocusEvent { if (it.isFocused) scope.launch { bring.bringIntoView() } }
            .background(if (isFocused) MenuColors.FocusBackground else Color.Transparent)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> { onSelect(); true }
                    else -> false
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (isFocused) MenuColors.FocusAccent else Color.Transparent),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = channel.label,
                color = if (isFocused) MenuColors.RowLabel else MenuColors.RowLabelMuted,
                fontSize = 15.sp,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        Text(
            text = statusText,
            color = statusColor,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
        )
    }
}
