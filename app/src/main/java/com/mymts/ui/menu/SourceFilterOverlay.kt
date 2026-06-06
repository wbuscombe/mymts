package com.mymts.ui.menu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

/**
 * Feed source-filter overlay (feed-filtering chapter).
 *
 * A centered, D-pad-navigable toggle list of the feed's distinct
 * sources. UP/DOWN move between rows (Compose focus); SELECT toggles a
 * source on/off; BACK dismisses back to the settings overlay. A green
 * check marks shown sources; muted/empty marks hidden ones.
 *
 * Honesty + A1: this only flips an on-device denylist of source labels
 * (persisted by `LineupStore`). It does no fetch, no web, no markup —
 * it filters the already-fetched inert plain-text items. New sources are
 * NOT in the denylist, so they show by default.
 */
@Composable
fun SourceFilterOverlay(
    sources: List<String>,
    hiddenSources: Set<String>,
    onToggle: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().background(MenuColors.Scrim),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = true,
            enter = scaleIn(tween(160), initialScale = 0.92f) + fadeIn(tween(160)),
            exit = scaleOut(tween(120), targetScale = 0.92f) + fadeOut(tween(120)),
        ) {
            Card(sources, hiddenSources, onToggle, onCancel)
        }
    }
}

@Composable
private fun Card(
    sources: List<String>,
    hiddenSources: Set<String>,
    onToggle: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val firstRowFocusRequester = remember { FocusRequester() }
    LaunchedEffect(sources) { if (sources.isNotEmpty()) firstRowFocusRequester.requestFocus() }

    val hiddenLower = remember(hiddenSources) { hiddenSources.map { it.lowercase() }.toSet() }

    Column(
        modifier = Modifier
            .widthIn(min = 420.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MenuColors.PanelBackground)
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Back) { onCancel(); true } else false
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "FEED SOURCES",
            color = MenuColors.RowLabelMuted,
            fontSize = 10.sp,
            letterSpacing = 3.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))

        if (sources.isEmpty()) {
            Text(
                text = "No sources yet — waiting for the feed to load.",
                color = MenuColors.RowLabelMuted,
                fontSize = 12.sp,
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                items(sources, key = { it }) { source ->
                    val shown = source.lowercase() !in hiddenLower
                    SourceRow(
                        source = source,
                        shown = shown,
                        onToggle = { onToggle(source) },
                        modifier = if (source == sources.first()) {
                            Modifier.focusRequester(firstRowFocusRequester)
                        } else Modifier,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "SELECT to show/hide   ·   BACK to done",
            color = MenuColors.RowLabelMuted,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun SourceRow(
    source: String,
    shown: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isFocused) MenuColors.FocusBackground else Color.Transparent)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onToggle)
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> { onToggle(); true }
                    else -> false
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Green check when shown; muted dash-box when hidden.
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (shown) MenuColors.FocusAccent else Color(0x22FFFFFF)),
            ) { if (shown) Text(" ", fontSize = 10.sp) }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = source,
                color = if (isFocused) MenuColors.RowLabel else MenuColors.RowLabelMuted,
                fontSize = 14.sp,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        Text(
            text = if (shown) "shown" else "hidden",
            color = if (shown) MenuColors.RowDetail else MenuColors.RowLabelMuted,
            fontSize = 11.sp,
        )
    }
}
