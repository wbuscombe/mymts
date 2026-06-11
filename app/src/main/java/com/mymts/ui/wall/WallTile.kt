package com.mymts.ui.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
 * One tile — a [video + label] UNIT (2026-06-11 refactor).
 *
 * Each tile is a Column: a **video area** (weight 1f) with the label in a
 * fixed **strip BELOW it**. The video keeps aspect inside its area via
 * `StreamSurface`'s `RESIZE_MODE_FIT`; the label lives in its own reserved
 * strip — INSIDE the cell, which the grid lays out inside the overscan-safe
 * region — so the label is uniformly below the picture and can never fall into
 * the clipped band, at any grid size. This replaces the earlier overlay /
 * letterbox-heuristic approaches (which fought the clip on the bottom row).
 *
 * Trust Bar rules preserved:
 *   - **Channel-identity honesty:** the tile takes a [BoundTile] whose `init`
 *     guarantees the player matches the slot — a label can't describe the wrong
 *     stream.
 *   - **C3 (no silent staleness):** the [StateBadge] reflects the
 *     [com.mymts.player.LivenessTracker] state; a frozen surface is never LIVE.
 *   - **C2 (graceful degradation):** dead / offline / empty collapse to a quiet
 *     panel; an offline-but-assigned slot shows its channel name as a ghost.
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

/** Height of the label strip reserved below the video in every cell. */
private val LABEL_STRIP_HEIGHT = 18.dp

@Composable
private fun EmptyTile() {
    Box(modifier = Modifier.fillMaxSize().background(WallColors.EmptyTile))
}

@Composable
private fun OfflineTile(channel: Channel) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f).background(WallColors.DeadTile)) {
            StateBadge(state = StreamPlayer.State.OFFLINE)
        }
        LabelStrip(label = channel.label, color = WallColors.LabelGhost)
    }
}

@Composable
private fun PlayingTile(slot: TileSlotResolver.Slot.Playing, player: StreamPlayer?) {
    val p = player ?: return DeadTile(slot.channel.label)
    val state by p.state.collectAsState()
    val isDead = state == StreamPlayer.State.DEAD || state == StreamPlayer.State.OFFLINE
    val labelColor = if (isDead) WallColors.LabelGhost else WallColors.LabelPrimary

    Column(modifier = Modifier.fillMaxSize()) {
        // Video area — takes the cell minus the label strip; the surface
        // letterboxes within it (RESIZE_MODE_FIT keeps the real aspect).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(if (isDead) WallColors.DeadTile else Color.Black),
        ) {
            if (!isDead) VideoSurface(player = p.getPlayer(), state = state)
            StateBadge(state = state)
        }
        LabelStrip(label = slot.channel.label, color = labelColor)
    }
}

@Composable
private fun DeadTile(label: String) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f).background(WallColors.DeadTile))
        LabelStrip(label = label, color = WallColors.LabelGhost)
    }
}

/**
 * The reserved label strip below the video — the structural BELOW-video
 * placement. Within the cell (which the grid keeps inside the overscan-safe
 * region), so it never clips, uniformly, at any grid size.
 */
@Composable
private fun LabelStrip(label: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(LABEL_STRIP_HEIGHT)
            .background(WallColors.TileGap)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
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
        modifier = Modifier.fillMaxSize().padding(6.dp),
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
