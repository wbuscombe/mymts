package com.mymts.ui.menu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymts.data.helper.Channel

/**
 * The TV-style centered channel picker — WyzeGrid's signature popup
 * pattern, adapted for the news wall.
 *
 * The picker shows the **focused channel** in the centre as
 * `< Channel Name (status) >`. D-pad **LEFT/RIGHT cycles** through the
 * available channels (wrapping at the ends); **SELECT/ENTER assigns**
 * it to the slot and dismisses the popup; **BACK cancels**, leaving
 * the slot unchanged.
 *
 * **Honest live/offline marking (Trust Bar C3 at the menu layer):**
 * every channel in the cycle is decorated with its real current
 * status — `(live)` or `(offline)` — so the operator can never
 * mistake an offline channel for one that will play. Assignment of
 * an offline channel is allowed (mark-and-allow); the wall already
 * renders the C2 honest panel for offline-assigned slots
 * (see `WallTile`'s `OfflineTile` path).
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
    if (channels.isEmpty()) return  // nothing to cycle through — caller decides what to do.

    // Index of the channel currently displayed. Starts at the channel
    // already in the slot if present, otherwise at 0. Wrapped on cycle.
    val initialIndex = remember(channels, currentSelection) {
        val byIdx = currentSelection?.let { sel -> channels.indexOfFirst { it.slug == sel } }
        if (byIdx == null || byIdx < 0) 0 else byIdx
    }
    var cursor by rememberSaveable(channels, slotIndex) { mutableIntStateOf(initialIndex) }
    // Re-anchor to the current selection if the channel list shape
    // changes underneath us (e.g. helper poll returns new set).
    LaunchedEffect(channels, currentSelection) {
        cursor = initialIndex.coerceIn(0, channels.lastIndex)
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MenuColors.Scrim),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = true,
            enter = scaleIn(tween(160), initialScale = 0.92f) + fadeIn(tween(160)),
            exit = scaleOut(tween(120), targetScale = 0.92f) + fadeOut(tween(120)),
        ) {
            PickerCard(
                slotIndex = slotIndex,
                channel = channels[cursor.coerceIn(0, channels.lastIndex)],
                onLeft = { cursor = (cursor - 1 + channels.size) % channels.size },
                onRight = { cursor = (cursor + 1) % channels.size },
                onAssign = { onAssign(channels[cursor.coerceIn(0, channels.lastIndex)].slug) },
                onCancel = onCancel,
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .focusable(),
            )
        }
    }
}

@Composable
private fun PickerCard(
    slotIndex: Int,
    channel: Channel,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onAssign: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusText = if (channel.isPlayable) "live" else "offline"
    val statusColor = if (channel.isPlayable) MenuColors.RowDetail else MenuColors.RowDetailOffline

    Column(
        modifier = modifier
            .widthIn(min = 420.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MenuColors.PanelBackground)
            .padding(horizontal = 28.dp, vertical = 22.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onLeft(); true }
                    Key.DirectionRight -> { onRight(); true }
                    Key.DirectionCenter, Key.Enter -> { onAssign(); true }
                    Key.Back -> { onCancel(); true }
                    else -> false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "SLOT ${slotIndex + 1}",
            color = MenuColors.RowLabelMuted,
            fontSize = 11.sp,
            letterSpacing = 3.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "‹", color = MenuColors.FocusAccent, fontSize = 32.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = channel.label,
                    color = MenuColors.RowLabel,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "›", color = MenuColors.FocusAccent, fontSize = 32.sp)
        }
        // Gesture hint so a first-run operator knows the model.
        Text(
            text = "‹  cycle  ›        SELECT to assign        BACK to cancel",
            color = MenuColors.RowLabelMuted,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
        )
    }
}
