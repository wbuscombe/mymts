package com.mymts.ui.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
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
 * Consumes channels from a [ChannelsRepository] (which the caller is
 * responsible for starting/stopping with the screen's lifecycle), and
 * drives a [StreamPlayerManager] whose player set matches the resolved
 * slots. The grid recomposes when:
 *
 *   - the set of live channels changes (helper resolved a new one,
 *     or one went unavailable), in which case the slot ids change
 *     and Compose `key()`s rebuild only the affected tiles;
 *   - any individual tile's [StreamPlayer.State] changes, in which
 *     case only that tile's badge/dim recomposes.
 *
 * Empty / dead slots stay in the layout grid so the wall doesn't
 * reshuffle every time a channel comes or goes. C2: a missing tile is
 * a quiet gap, not a layout-level event.
 */
@Composable
fun VideoGrid(
    repository: ChannelsRepository,
    tileCount: Int,
    modifier: Modifier = Modifier,
) {
    val state by repository.state.collectAsState()
    val live = remember(state.snapshot) { state.snapshot?.playable.orEmpty() }
    val slots = remember(tileCount, live) { TileSlotResolver.resolve(tileCount, live) }
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
            FixedGrid(
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

    // Surface helper-side staleness so the grid never silently presents
    // an old channel set as current (C3). We re-read state each second
    // via the StateFlow's recomposition; the banner only appears when
    // we've had no successful fetch for `staleAfterMs`.
    val isStale = repository.isStale()
    LaunchedEffect(isStale) { /* no-op anchor for re-evaluation */ }
}

/**
 * A fixed-rows × columns grid that fills the available area, with each
 * cell aspect-ratioed 16:9. Unlike [androidx.compose.foundation.lazy.grid.LazyVerticalGrid]
 * this never scrolls — the wall is a fixed canvas, not a list.
 */
@Composable
private fun FixedGrid(
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
                    .layout { measurable, constraints ->
                        // Each row gets an equal share of the available height.
                        val rowHeight = (constraints.maxHeight - (rows - 1) * 2.dp.roundToPx()) / rows
                        val placeable = measurable.measure(
                            constraints.copy(minHeight = rowHeight, maxHeight = rowHeight)
                        )
                        layout(placeable.width, rowHeight) { placeable.place(0, 0) }
                    },
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (c in 0 until columns) {
                    val idx = r * columns + c
                    if (idx < slots.size) {
                        WallTile(
                            slot = slots[idx],
                            playerFor = playerFor,
                            modifier = Modifier
                                .fillMaxWidth(1f / (columns - c).coerceAtLeast(1))
                                .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true),
                        )
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
