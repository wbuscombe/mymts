package com.mymts.ui.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mymts.data.helper.ChannelsRepository
import com.mymts.player.StreamPlayer
import com.mymts.player.StreamPlayerManager
import com.mymts.ui.wall.TileSlotResolver.Slot
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * The N=[tileCount]-tile video grid.
 *
 * Stage 3 polish: the grid now **autofits** the region it's given —
 * tiles divide the available space evenly (rows × columns) rather
 * than each claiming a fixed 16:9 box. Aspect-ratio correctness moves
 * inside the tile, where [StreamSurface]'s RESIZE_MODE_FIT letterboxes
 * the video to its actual cell dimensions. Net effect: the grid fills
 * its parent edge-to-edge horizontally; each tile then renders the
 * video centered with clean black bars top/bottom (or left/right)
 * where the source ratio doesn't match the cell.
 *
 * Recomposition discipline (unchanged from Checkpoint A):
 *   - the set of live channels changes → slot ids change → Compose
 *     `key()`s rebuild only the affected tiles;
 *   - any tile's [StreamPlayer.State] changes → only that tile's
 *     badge/dim recomposes.
 */
@Composable
fun VideoGrid(
    repository: ChannelsRepository,
    tileCount: Int,
    modifier: Modifier = Modifier,
    lineupSelector: (List<com.mymts.data.helper.Channel>) -> List<com.mymts.data.helper.Channel> =
        { it },
) {
    val state by repository.state.collectAsState()
    val live = remember(state.snapshot) { state.snapshot?.playable.orEmpty() }
    val chosen = remember(live, lineupSelector) { lineupSelector(live) }
    val slots = remember(tileCount, chosen) { TileSlotResolver.resolve(tileCount, chosen) }
    val playingSlots = remember(slots) { slots.filterIsInstance<Slot.Playing>() }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val specs = remember(playingSlots) { playingSlots.map { it.spec } }
    val manager = remember(specs) { StreamPlayerManager(context, specs) }

    DisposableEffect(manager) {
        lifecycleOwner.lifecycle.addObserver(manager)
        onDispose { lifecycleOwner.lifecycle.removeObserver(manager) }
    }

    val playerBySlotId: (Slot.Playing) -> StreamPlayer? = { slot ->
        val idx = playingSlots.indexOfFirst { it.id == slot.id }
        if (idx >= 0) manager.player(idx) else null
    }

    val columns = remember(tileCount) {
        when (tileCount) {
            0, 1 -> 1
            in 2..4 -> 2
            else -> ceil(sqrt(tileCount.toDouble())).toInt().coerceAtLeast(1)
        }
    }

    Box(modifier = modifier.background(WallColors.Background)) {
        if (slots.isEmpty()) {
            EmptyState(reason = "No channels reported by the helper.")
        } else {
            AutofitGrid(
                columns = columns,
                slots = slots,
                playerFor = playerBySlotId,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (live.isEmpty() && state.snapshot != null) {
            OverlayBanner(text = "Helper reports 0 channels live — tiles waiting.")
        } else if (!state.lastFetchOk && state.snapshot == null) {
            OverlayBanner(text = "Helper unreachable — wall idle.")
        }
    }
}

/**
 * Even split: rows × columns each receive `weight(1f)` of the parent.
 * The grid fills its parent in both dimensions; each cell is therefore
 * `parentWidth/columns × parentHeight/rows`. Letterboxing happens
 * inside the tile via [StreamSurface]'s resize mode.
 */
@Composable
private fun AutofitGrid(
    columns: Int,
    slots: List<Slot>,
    playerFor: (Slot.Playing) -> StreamPlayer?,
    modifier: Modifier = Modifier,
) {
    val rows = ceil(slots.size / columns.toDouble()).toInt().coerceAtLeast(1)
    Column(
        modifier = modifier.fillMaxSize().padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (r in 0 until rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (c in 0 until columns) {
                    val idx = r * columns + c
                    if (idx < slots.size) {
                        WallTile(
                            slot = slots[idx],
                            playerFor = playerFor,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    } else {
                        // Pad the last row so a partial row aligns left.
                        Box(modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(reason: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(WallColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = reason, color = WallColors.LabelGhost, fontSize = 12.sp)
    }
}

@Composable
private fun OverlayBanner(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(text = text, color = WallColors.LabelMuted, fontSize = 11.sp)
    }
}
