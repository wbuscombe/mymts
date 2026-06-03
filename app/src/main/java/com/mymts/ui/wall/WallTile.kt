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
import com.mymts.data.helper.Channel
import com.mymts.player.StreamPlayer
import com.mymts.ui.components.StreamSurface

/**
 * One tile on the wall.
 *
 * Trust Bar rules made visible here:
 *   - **Channel-identity honesty:** the tile takes a [BoundTile] — a
 *     slot paired with the player whose `spec.id` matches that slot.
 *     Label, channel, and stream all flow from that single object;
 *     no separate lookup happens inside this composable, and
 *     [BoundTile]'s `init` check guarantees the player matches the
 *     slot it's drawn under. A label can never describe the wrong
 *     stream by construction.
 *   - **C3 (no silent staleness):** the state badge reflects the
 *     [com.mymts.player.LivenessTracker]-derived state on each frame,
 *     not the ExoPlayer-reported playWhenReady. A frozen surface is
 *     never labelled LIVE.
 *   - **C2 (graceful degradation):** a DEAD tile, an `Offline` slot,
 *     and an `Empty` slot all collapse to a quiet near-black panel
 *     with at most a low-contrast label. Stage 5 adds the
 *     operator-assigned-but-currently-offline case: the panel reads
 *     the channel's name as a ghost label so the operator sees what's
 *     planned for the slot (not a fake live label, not a stale label
 *     for a vanished channel).
 */
@Composable
internal fun WallTile(
    bound: BoundTile,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(WallColors.TileGap)) {
        when (val slot = bound.slot) {
            is TileSlotResolver.Slot.Empty -> EmptyTile()
            is TileSlotResolver.Slot.Offline -> OfflineTile(channel = slot.channel)
            is TileSlotResolver.Slot.Playing -> PlayingTile(slot, bound.player)
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
private fun OfflineTile(channel: Channel) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WallColors.DeadTile),
    ) {
        ChannelLabel(label = channel.label, state = StreamPlayer.State.OFFLINE)
        StateBadge(state = StreamPlayer.State.OFFLINE)
    }
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
