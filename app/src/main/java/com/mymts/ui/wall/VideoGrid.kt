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
import androidx.compose.runtime.key
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
 * Channel-identity honesty (Trust Bar C3 at the identity layer):
 * each tile receives a [BoundTile] — a slot paired with the player
 * whose `spec.id` matches that slot. The pairing is enforced at
 * construction (see [BoundTile.init]) and Compose `key(slot.id)`
 * around each [WallTile] guarantees identity changes when a slot's
 * channel changes. A label can never describe the wrong stream.
 *
 * Autofit (Stage 3 polish): rows × columns each take `weight(1f)`,
 * so the grid fills its parent in both dimensions; cell dimensions
 * follow from the parent. Aspect-ratio correctness lives inside the
 * tile via [com.mymts.ui.components.StreamSurface]'s RESIZE_MODE_FIT.
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

    // Build slot.spec.id → player map ONCE per (manager, specs) change.
    // The lookup is by identity (spec.id), never by slot index, so a
    // reordered slot list cannot pair a tile with the wrong player.
    val playerBySpecId: Map<String, StreamPlayer?> = remember(manager, specs) {
        specs.mapIndexed { idx, spec -> spec.id to manager.player(idx) }.toMap()
    }

    val bound: List<BoundTile> = remember(slots, playerBySpecId) {
        bindTiles(slots) { specId -> playerBySpecId[specId] }
    }

    val columns = remember(tileCount) {
        when (tileCount) {
            0, 1 -> 1
            in 2..4 -> 2
            else -> ceil(sqrt(tileCount.toDouble())).toInt().coerceAtLeast(1)
        }
    }

    Box(modifier = modifier.background(WallColors.Background)) {
        if (bound.isEmpty()) {
            EmptyState(reason = "No channels reported by the helper.")
        } else {
            AutofitGrid(
                columns = columns,
                bound = bound,
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

@Composable
private fun AutofitGrid(
    columns: Int,
    bound: List<BoundTile>,
    modifier: Modifier = Modifier,
) {
    val rows = ceil(bound.size / columns.toDouble()).toInt().coerceAtLeast(1)
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
                    if (idx < bound.size) {
                        val tile = bound[idx]
                        // Explicit key on the slot id so Compose treats the
                        // tile's identity as the channel-slot pair, not the
                        // grid position. When a slot's channel changes, the
                        // tile is rebuilt from scratch — no stale label or
                        // surface state can leak across the identity change.
                        key(tile.key) {
                            WallTile(
                                bound = tile,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    } else {
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
