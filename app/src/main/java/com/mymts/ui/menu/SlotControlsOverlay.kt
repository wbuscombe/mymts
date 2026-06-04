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
import androidx.compose.material3.Divider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The per-tile controls — opens when the operator hits SELECT on a
 * slot row in the side menu. Centered popup, WyzeGrid-family styling,
 * D-pad navigation.
 *
 * Actions (Stage 6+ controls track):
 *
 *   - **Channel** → opens the existing channel picker (the Stage 5
 *     [ChannelPickerOverlay]) — re-routes through `onPickChannel`.
 *   - **Audio** → toggles this tile audible. The wall enforces a
 *     single-audible-tile model (selecting "audible" here mutes every
 *     other tile); the row's detail line reflects the current state.
 *   - **Captions** → toggles the soft caption track. The detail line
 *     surfaces honest "captions not available" when the stream has no
 *     soft text track (burned-in captions are pixels in the video,
 *     not a track — see the per-channel caption table for the honest
 *     map). Default OFF for every tile at startup.
 *   - **Close** → dismiss the controls overlay; the side menu stays
 *     open.
 *
 * BACK at any time dismisses just the controls; the side menu stays
 * open so the operator can navigate to another slot. Consistent with
 * the channel-picker's BACK semantics.
 */
@Composable
fun SlotControlsOverlay(
    slotIndex: Int,
    channelLabel: String,
    audioState: AudioState,
    captionsState: CaptionsState,
    onPickChannel: () -> Unit,
    onToggleAudio: () -> Unit,
    onToggleCaptions: () -> Unit,
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
            ControlsCard(
                slotIndex = slotIndex,
                channelLabel = channelLabel,
                audioState = audioState,
                captionsState = captionsState,
                onPickChannel = onPickChannel,
                onToggleAudio = onToggleAudio,
                onToggleCaptions = onToggleCaptions,
                onCancel = onCancel,
            )
        }
    }
}

@Composable
private fun ControlsCard(
    slotIndex: Int,
    channelLabel: String,
    audioState: AudioState,
    captionsState: CaptionsState,
    onPickChannel: () -> Unit,
    onToggleAudio: () -> Unit,
    onToggleCaptions: () -> Unit,
    onCancel: () -> Unit,
) {
    val firstRowFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstRowFocusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .widthIn(min = 360.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MenuColors.PanelBackground)
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "SLOT ${slotIndex + 1}",
            color = MenuColors.RowLabelMuted,
            fontSize = 10.sp,
            letterSpacing = 3.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = channelLabel,
            color = MenuColors.RowLabel,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Divider(color = MenuColors.PanelDivider, thickness = 1.dp)

        ControlRow(
            title = "Channel",
            detail = "tap to choose",
            detailStyle = MenuColors.RowDetailMuted,
            onSelect = onPickChannel,
            modifier = Modifier.focusRequester(firstRowFocusRequester),
        )
        ControlRow(
            title = "Audio",
            detail = audioState.detail(),
            detailStyle = if (audioState == AudioState.Audible) MenuColors.RowDetail
            else MenuColors.RowDetailMuted,
            onSelect = onToggleAudio,
        )
        ControlRow(
            title = "Captions",
            detail = captionsState.detail(),
            detailStyle = when (captionsState) {
                CaptionsState.On -> MenuColors.RowDetail
                CaptionsState.NotAvailable -> MenuColors.RowDetailOffline
                CaptionsState.Off -> MenuColors.RowDetailMuted
            },
            onSelect = onToggleCaptions,
            // Disabled-looking when the stream has no soft track:
            // the row still navigates (we want focus to land here so
            // the operator can confirm what's happening) but pressing
            // SELECT is a no-op — caller doesn't toggle when no track.
            isEffectivelyDisabled = captionsState == CaptionsState.NotAvailable,
        )
        Divider(color = MenuColors.PanelDivider, thickness = 1.dp)
        ControlRow(
            title = "Close",
            detail = "back to menu",
            detailStyle = MenuColors.RowLabelMuted,
            onSelect = onCancel,
        )

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "SELECT to toggle      BACK to dismiss",
            color = MenuColors.RowLabelMuted,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun ControlRow(
    title: String,
    detail: String,
    detailStyle: Color,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    isEffectivelyDisabled: Boolean = false,
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
                onClick = { if (!isEffectivelyDisabled) onSelect() },
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (isFocused) MenuColors.FocusAccent else Color.Transparent),
        ) { Text(text = " ", fontSize = 14.sp) }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                color = if (isFocused) MenuColors.RowLabel else MenuColors.RowLabelMuted,
                fontSize = 14.sp,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(text = detail, color = detailStyle, fontSize = 11.sp)
        }
    }
}

enum class AudioState {
    Audible, Muted;

    fun detail(): String = when (this) {
        Audible -> "audible — others muted"
        Muted -> "muted"
    }
}

enum class CaptionsState {
    On, Off, NotAvailable;

    fun detail(): String = when (this) {
        On -> "on"
        Off -> "off"
        NotAvailable -> "not available on this channel"
    }
}
