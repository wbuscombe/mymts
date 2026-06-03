package com.mymts.ui.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import com.mymts.player.StreamPlayer
import com.mymts.ui.components.StreamSurface

/**
 * One tile on the wall. Renders the live video surface when there is
 * one, and surfaces the player's state via a single small badge.
 *
 * Trust Bar rules made visible here:
 *   - **C3 (no silent staleness):** the badge reflects the
 *     [LivenessTracker]-derived state on each frame, not the
 *     ExoPlayer-reported playWhenReady. A frozen surface is never
 *     labelled LIVE.
 *   - **C2 (graceful degradation):** a DEAD tile collapses to a
 *     quiet near-black panel, not an error card. No retry button,
 *     no red exclamation, no chrome. The tile *was* there; now
 *     it isn't — and the room still feels calm.
 *
 * Visual register chosen for a 10-foot UI: badge in a small top-right
 * pill, channel name in a low-contrast bottom-left tag. Both stay out
 * of the picture's centre so an actually-LIVE tile reads as video,
 * not chrome.
 */
@Composable
fun WallTile(
    slot: TileSlotResolver.Slot,
    playerFor: (TileSlotResolver.Slot.Playing) -> StreamPlayer?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(WallColors.TileGap)
            .padding(2.dp),
    ) {
        when (slot) {
            is TileSlotResolver.Slot.Empty -> EmptyTile()
            is TileSlotResolver.Slot.Playing -> PlayingTile(slot, playerFor(slot))
        }
    }
}

@Composable
private fun EmptyTile() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WallColors.EmptyTile),
    )
}

@Composable
private fun PlayingTile(slot: TileSlotResolver.Slot.Playing, player: StreamPlayer?) {
    val state by (player?.state?.collectAsState()
        ?: return DeadTile(slot.channel.label))

    val isDead = state == StreamPlayer.State.DEAD || state == StreamPlayer.State.OFFLINE

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDead) WallColors.DeadTile else Color.Black),
    ) {
        if (!isDead) {
            VideoSurface(player = player?.getPlayer(), state = state)
        }
        ChannelLabel(label = slot.channel.label, state = state)
        StateBadge(state = state)
    }
}

@Composable
private fun VideoSurface(player: ExoPlayer?, state: StreamPlayer.State) {
    val alpha = when (state) {
        StreamPlayer.State.LIVE -> 1f
        StreamPlayer.State.CONNECTING -> 0.55f
        StreamPlayer.State.STALE,
        StreamPlayer.State.RECOVERING -> 0.7f
        StreamPlayer.State.DEAD,
        StreamPlayer.State.OFFLINE -> 0f
    }
    Box(modifier = Modifier.fillMaxSize().alpha(alpha)) {
        StreamSurface(player = player, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ChannelLabel(label: String, state: StreamPlayer.State) {
    val color = when (state) {
        StreamPlayer.State.DEAD, StreamPlayer.State.OFFLINE -> WallColors.LabelGhost
        else -> WallColors.LabelPrimary
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0x66000000))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = label,
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun StateBadge(state: StreamPlayer.State) {
    // LIVE doesn't earn a badge here — the picture being there is the
    // signal. Anything else gets a quiet, non-flashing label.
    val (text, color) = when (state) {
        StreamPlayer.State.LIVE -> return
        StreamPlayer.State.CONNECTING -> "CONNECTING" to WallColors.BadgeConnecting
        StreamPlayer.State.STALE -> "STALE" to WallColors.BadgeStale
        StreamPlayer.State.RECOVERING -> "RECONNECTING" to WallColors.BadgeRecovering
        StreamPlayer.State.DEAD -> "OFFLINE" to WallColors.BadgeDead
        StreamPlayer.State.OFFLINE -> "OFFLINE" to WallColors.BadgeOffline
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0x88000000))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(text = text, color = color, fontSize = 10.sp)
        }
    }
}

@Composable
private fun DeadTile(label: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WallColors.DeadTile),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(text = label, color = WallColors.LabelGhost, fontSize = 11.sp)
        }
    }
}
