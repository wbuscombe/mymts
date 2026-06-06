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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import com.mymts.data.settings.WallSettings

/**
 * Wall settings overlay — UX & Config chapter (2026-06-04).
 *
 * Centered popup with three rows: feed width, feed font, feed side.
 * The operator focuses a row, presses LEFT/RIGHT to cycle the row's
 * preset values, and the wall updates live (each cycle commits +
 * persists via [com.mymts.data.lineup.LineupStore]). SELECT also
 * cycles forward (a friendly second gesture). UP/DOWN moves between
 * rows via Compose's standard focus traversal. BACK dismisses the
 * overlay — the side menu stays open underneath so the operator can
 * navigate to another setting later without reopening MENU.
 *
 * No new fetch surface (A1 unchanged): all three controls write only
 * to the existing on-device SharedPreferences blob; no network,
 * no helper call, no HTML render, no markup parsing.
 */
@Composable
fun SettingsOverlay(
    settings: WallSettings,
    onCycleFeedWidth: () -> Unit,
    onCycleFeedFontScale: () -> Unit,
    onCycleFeedSide: () -> Unit,
    onCycleFeedRecency: () -> Unit,
    onOpenSourceFilter: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            SettingsCard(
                settings = settings,
                onCycleFeedWidth = onCycleFeedWidth,
                onCycleFeedFontScale = onCycleFeedFontScale,
                onCycleFeedSide = onCycleFeedSide,
                onCycleFeedRecency = onCycleFeedRecency,
                onOpenSourceFilter = onOpenSourceFilter,
                onCancel = onCancel,
            )
        }
    }
}

@Composable
private fun SettingsCard(
    settings: WallSettings,
    onCycleFeedWidth: () -> Unit,
    onCycleFeedFontScale: () -> Unit,
    onCycleFeedSide: () -> Unit,
    onCycleFeedRecency: () -> Unit,
    onOpenSourceFilter: () -> Unit,
    onCancel: () -> Unit,
) {
    val firstRowFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstRowFocusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .widthIn(min = 420.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MenuColors.PanelBackground)
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .onPreviewKeyEvent { event ->
                // Card-level BACK so the operator can dismiss from
                // any focused row without having to navigate first.
                if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                    onCancel(); true
                } else false
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "WALL SETTINGS",
            color = MenuColors.RowLabelMuted,
            fontSize = 10.sp,
            letterSpacing = 3.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))

        SettingRow(
            title = "Feed width",
            valueLabel = settings.feedWidth.displayName,
            onCycle = onCycleFeedWidth,
            modifier = Modifier.focusRequester(firstRowFocusRequester),
        )
        SettingRow(
            title = "Feed font",
            valueLabel = settings.feedFontScale.displayName,
            onCycle = onCycleFeedFontScale,
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
        // "Feed sources…" opens the source-toggle overlay. The value
        // shows how many sources are currently hidden (0 = all shown).
        val hiddenCount = settings.hiddenSources.size
        SettingRow(
            title = "Feed sources…",
            valueLabel = if (hiddenCount == 0) "all shown" else "$hiddenCount hidden",
            onCycle = onOpenSourceFilter,   // SELECT/LEFT/RIGHT all open the sub-overlay
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
            .background(if (isFocused) MenuColors.FocusBackground else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onCycle,
            )
            .focusable(interactionSource = interactionSource)
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
