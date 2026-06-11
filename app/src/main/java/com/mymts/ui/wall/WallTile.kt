package com.mymts.ui.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.unit.Dp
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
    isBottomRow: Boolean = false,
) {
    Box(modifier = modifier.background(WallColors.TileGap)) {
        when (val slot = bound.slot) {
            is TileSlotResolver.Slot.Empty -> EmptyTile()
            is TileSlotResolver.Slot.Offline -> OfflineTile(channel = slot.channel)
            is TileSlotResolver.Slot.Playing -> PlayingTile(slot, bound.player, isBottomRow)
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
private fun PlayingTile(
    slot: TileSlotResolver.Slot.Playing,
    player: StreamPlayer?,
    isBottomRow: Boolean = false,
) {
    val p = player ?: return DeadTile(slot.channel.label)
    val state by p.state.collectAsState()
    val videoAspect by p.videoAspect.collectAsState()
    val isDead = state == StreamPlayer.State.DEAD || state == StreamPlayer.State.OFFLINE

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDead) WallColors.DeadTile else Color.Black),
    ) {
        if (!isDead) {
            VideoSurface(player = p.getPlayer(), state = state)
        }
        // Label placed using the video's REAL rendered size: below the picture
        // (on the letterbox) when there's room that clears the panel overscan,
        // else above it, else a tinted overlay. A dead tile has no video → null
        // aspect → the tinted overlay at the bottom safe-area.
        TileLabelOverlay(
            label = slot.channel.label,
            state = state,
            cellW = maxWidth,
            cellH = maxHeight,
            videoAspect = if (isDead) null else videoAspect,
            isBottomRow = isBottomRow,
        )
        StateBadge(state = state)
    }
}

/** Label cell height estimate + the overscan margin a below-label must clear. */
private val TILE_LABEL_SLOT = 18.dp
private val TILE_LABEL_CLIP_SAFE = 22.dp

@Composable
private fun BoxScope.TileLabelOverlay(
    label: String,
    state: StreamPlayer.State,
    cellW: Dp,
    cellH: Dp,
    videoAspect: Float?,
    isBottomRow: Boolean,
) {
    val color = when (state) {
        StreamPlayer.State.DEAD, StreamPlayer.State.OFFLINE -> WallColors.LabelGhost
        else -> WallColors.LabelPrimary
    }
    val asp = videoAspect
    val videoH = if (asp != null && asp > 0f) TileLabel.renderedVideoHeight(cellW.value, cellH.value, asp) else 0f
    // Only the bottom row needs to clear the overscan band before choosing
    // "below"; other rows have headroom, so a tiny margin lets them prefer the
    // below-the-picture look the operator wants.
    val clipSafe = if (isBottomRow) TILE_LABEL_CLIP_SAFE.value else 4f
    when (TileLabel.placement(cellW.value, cellH.value, asp, TILE_LABEL_SLOT.value, clipSafe)) {
        // Just below the picture, on the black letterbox — the original look.
        TileLabel.Placement.BELOW -> Box(
            modifier = Modifier.align(Alignment.TopStart).offset(x = 6.dp, y = (TileLabel.videoBottom(cellH.value, videoH) + 2f).dp),
        ) { LabelChip(label, color, tinted = false) }
        // Tight at the bottom (near the clip) → the matching top letterbox.
        TileLabel.Placement.ABOVE -> Box(
            modifier = Modifier.align(Alignment.TopStart)
                .offset(x = 6.dp, y = (TileLabel.videoTop(cellH.value, videoH) - TILE_LABEL_SLOT.value).coerceAtLeast(2f).dp),
        ) { LabelChip(label, color, tinted = false) }
        // No usable letterbox (pillarbox / unknown) → tinted bubble, lifted into
        // the safe area so the bottom row doesn't re-clip.
        TileLabel.Placement.OVERLAY -> Box(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = TILE_LABEL_BOTTOM_SAFE),
        ) { LabelChip(label, color, tinted = true) }
    }
}

@Composable
private fun LabelChip(label: String, color: Color, tinted: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(2.dp))
            .then(if (tinted) Modifier.background(Color(0x99000000)) else Modifier)
            .padding(horizontal = if (tinted) 6.dp else 0.dp, vertical = if (tinted) 2.dp else 0.dp),
    ) {
        Text(text = label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
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

/**
 * Bottom safe-area reserved inside each tile for its name label
 * (panel-fit, 2026-06-11). The bottom-row tiles' bottom edge sits at the
 * fitted wall's bottom, where a panel's overscan crops a sliver — a label
 * flush at the edge (the old 6.dp) fell off on `.92`. Lifting it clear of
 * that band keeps all four tiles' labels visible WITHIN the fitted output,
 * without touching the global fit (Fit scale / Vertical stretch). Applied to
 * every tile so the labels sit consistently in the lower third.
 */
private val TILE_LABEL_BOTTOM_SAFE = 28.dp

@Composable
private fun ChannelLabel(label: String, state: StreamPlayer.State) {
    val color = when (state) {
        StreamPlayer.State.DEAD, StreamPlayer.State.OFFLINE -> WallColors.LabelGhost
        else -> WallColors.LabelPrimary
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 6.dp, top = 6.dp, end = 6.dp, bottom = TILE_LABEL_BOTTOM_SAFE),
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
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = TILE_LABEL_BOTTOM_SAFE),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(text = label, color = WallColors.LabelGhost, fontSize = 11.sp)
        }
    }
}
