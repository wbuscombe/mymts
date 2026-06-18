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
import com.mymts.data.helper.Preset

/**
 * Wall-preset picker overlay (server-authoritative presets).
 *
 * A centered, D-pad-navigable list of the presets the helper serves
 * (`/api/presets`). UP/DOWN move between rows (Compose focus); SELECT
 * applies that preset — replacing the tile lineup and applying its
 * suggested grid — and closes back to the wall; BACK dismisses to the
 * side menu without changing anything. The active preset is marked.
 *
 * Honesty: this only switches which server-defined channel-set the wall
 * renders; the helper is authoritative for each preset's contents, so a
 * new or changed preset flows with no app rebuild.
 */
@Composable
fun PresetPickerOverlay(
    presets: List<Preset>,
    activePresetId: String,
    onApply: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "WALL PRESET",
    emptyText: String = "No presets yet — waiting for the helper.",
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
            Card(presets, activePresetId, onApply, onCancel, title, emptyText)
        }
    }
}

@Composable
private fun Card(
    presets: List<Preset>,
    activePresetId: String,
    onApply: (String) -> Unit,
    onCancel: () -> Unit,
    title: String,
    emptyText: String,
) {
    val firstRowFocusRequester = remember { FocusRequester() }
    val cardFocusRequester = remember { FocusRequester() }
    // Non-empty → focus the first preset row. Empty (helper not yet reachable) →
    // focus the CARD itself, so the overlay still owns focus and D-pad input is
    // contained (consumed below) instead of silently bubbling to the wall.
    LaunchedEffect(presets) {
        if (presets.isNotEmpty()) firstRowFocusRequester.requestFocus()
        else runCatching { cardFocusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .widthIn(min = 420.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MenuColors.PanelBackground)
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .focusRequester(cardFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Back -> { onCancel(); true }
                    // While there's nothing to pick, swallow D-pad nav so it can't
                    // leak past the overlay (the rows own nav once they exist).
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft,
                    Key.DirectionRight, Key.DirectionCenter, Key.Enter -> presets.isEmpty()
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            color = MenuColors.RowLabelMuted,
            fontSize = 10.sp,
            letterSpacing = 3.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))

        if (presets.isEmpty()) {
            Text(
                text = emptyText,
                color = MenuColors.RowLabelMuted,
                fontSize = 12.sp,
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                items(presets, key = { it.id }) { preset ->
                    val active = preset.id == activePresetId
                    PresetRow(
                        preset = preset,
                        active = active,
                        onApply = { onApply(preset.id) },
                        modifier = if (preset.id == presets.first().id) {
                            Modifier.focusRequester(firstRowFocusRequester)
                        } else Modifier,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "SELECT to apply   ·   BACK to cancel",
            color = MenuColors.RowLabelMuted,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun PresetRow(
    preset: Preset,
    active: Boolean,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isFocused) MenuColors.FocusBackground else Color.Transparent)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onApply)
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> { onApply(); true }
                    else -> false
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Filled accent dot marks the active preset; muted box otherwise.
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (active) MenuColors.FocusAccent else Color(0x22FFFFFF)),
            ) { if (active) Text(" ", fontSize = 10.sp) }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = preset.name,
                    color = if (isFocused) MenuColors.RowLabel else MenuColors.RowLabelMuted,
                    fontSize = 14.sp,
                    fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    text = preset.summaryLine(),
                    color = MenuColors.RowLabelMuted,
                    fontSize = 11.sp,
                )
            }
        }
        Text(
            text = if (active) "active" else "apply",
            color = if (active) MenuColors.RowDetail else MenuColors.RowLabelMuted,
            fontSize = 11.sp,
        )
    }
}

/** Compact one-line summary of a preset's contents: channel count + grid. */
private fun Preset.summaryLine(): String = buildString {
    append(slugs.size)
    append(if (slugs.size == 1) " channel" else " channels")
    val r = gridRows
    val c = gridCols
    if (r != null && c != null) append(" · ${r}×$c")
}
