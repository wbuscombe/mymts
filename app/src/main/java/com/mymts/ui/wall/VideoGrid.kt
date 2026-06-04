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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mymts.player.StreamPlayer
import com.mymts.player.StreamPlayerManager
import com.mymts.ui.wall.TileSlotResolver.Slot
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * The N=`slots.size`-tile video grid.
 *
 * Stage 5: the slot list is computed by the caller ([com.mymts.ui.wall.WallScreen])
 * and passed in. This is the **single source of truth** the menu and
 * the wall share — the menu's [com.mymts.ui.menu.SlotRow]s read the
 * same list, so the two surfaces can never disagree about which
 * channel is in which slot.
 *
 * Channel-identity honesty (Trust Bar C3, identity layer): each tile
 * receives a [BoundTile] — a slot paired with the player whose
 * `spec.id` matches that slot. The pairing is enforced at construction
 * (see [BoundTile.init]) and Compose `key(slot.id)` around each
 * [WallTile] guarantees identity changes when a slot's channel
 * changes. A label can never describe the wrong stream.
 *
 * Autofit (Stage 3 polish): rows × columns each take `weight(1f)`, so
 * the grid fills its parent in both dimensions; cell dimensions follow
 * from the parent. Aspect-ratio correctness lives inside the tile via
 * [com.mymts.ui.components.StreamSurface]'s RESIZE_MODE_FIT.
 */
@Composable
fun VideoGrid(
    slots: List<Slot>,
    modifier: Modifier = Modifier,
    helperUnreachable: Boolean = false,
    audibleSlot: Int = -1,
    captionsOnSlots: Set<Int> = emptySet(),
) {
    val playingSlots = remember(slots) { slots.filterIsInstance<Slot.Playing>() }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val specs = remember(playingSlots) { playingSlots.map { it.spec } }
    val manager = remember(specs) { StreamPlayerManager(context, specs) }

    DisposableEffect(manager) {
        lifecycleOwner.lifecycle.addObserver(manager)
        onDispose { lifecycleOwner.lifecycle.removeObserver(manager) }
    }

    // Subscribe to the manager's readiness signal so this composable
    // recomposes when `onStart` populates real players. See
    // `StreamPlayerManager.readyVersion` for the bug history.
    val readyVersion by manager.readyVersion

    val bound: List<BoundTile> = remember(slots, manager, readyVersion) {
        bindTiles(slots) { specId ->
            val idx = playingSlots.indexOfFirst { it.spec.id == specId }
            if (idx >= 0) manager.player(idx) else null
        }
    }

    // Apply audio + captions state to each player whenever the state
    // changes. The single-audible-tile model is enforced HERE — every
    // tile's audibility is set to `slot.index == audibleSlot` so a
    // change to `audibleSlot` mutes the prior tile automatically. No
    // race condition: the apply runs in a LaunchedEffect that re-keys
    // on (bound, audibleSlot, captionsOnSlots) — the manager's
    // `readyVersion` already gates `bound` having non-null players.
    LaunchedEffect(bound, audibleSlot, captionsOnSlots) {
        bound.forEach { tile ->
            val player = tile.player ?: return@forEach
            val slot = tile.slot as? Slot.Playing ?: return@forEach
            player.setAudible(slot.index == audibleSlot)
            player.setCaptionsEnabled(slot.index in captionsOnSlots)
        }
    }

    val columns = remember(slots.size) {
        when (slots.size) {
            0, 1 -> 1
            in 2..4 -> 2
            else -> ceil(sqrt(slots.size.toDouble())).toInt().coerceAtLeast(1)
        }
    }

    Box(modifier = modifier.background(WallColors.Background)) {
        if (bound.isEmpty()) {
            EmptyState(
                reason = if (helperUnreachable) "Helper unreachable — wall idle."
                else "No channels reported by the helper.",
            )
        } else {
            AutofitGrid(
                columns = columns,
                bound = bound,
                modifier = Modifier.fillMaxSize(),
            )
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
