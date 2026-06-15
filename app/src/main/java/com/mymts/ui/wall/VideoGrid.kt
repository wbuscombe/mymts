package com.mymts.ui.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import kotlinx.coroutines.launch
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
/**
 * Column count the grid uses for a given slot count. Extracted as a
 * top-level function so the wall's focus model
 * ([com.mymts.ui.nav.WallFocusModel]) can pre-compute the same value
 * without instantiating the composable — D-pad row/column math has to
 * match the visible layout exactly.
 */
fun gridColumnsFor(slotCount: Int): Int = when (slotCount) {
    0, 1 -> 1
    in 2..4 -> 2
    else -> ceil(sqrt(slotCount.toDouble())).toInt().coerceAtLeast(1)
}

/** Rows needed for [count] cells at [columns] columns — pure, so the layout and
 *  any caller share the same grid math at any configured grid size. */
fun gridRowsFor(count: Int, columns: Int): Int =
    if (count <= 0 || columns <= 0) 1 else ceil(count / columns.toDouble()).toInt().coerceAtLeast(1)

/** Overscan-safe bottom band reserved in the video section so the bottom row's
 *  [video + label] cell stays inside what the panel actually shows (the residual
 *  clip the operator's fit leaves). The cells + their below-labels are laid out
 *  within the section MINUS this band. Read-only wrt the fit config. */
private val SECTION_SAFE_BOTTOM = 28.dp

@Composable
fun VideoGrid(
    slots: List<Slot>,
    columns: Int,
    reconnectNonce: Int = 0,
    reconnectSlot: Int = -1,
    resyncNonce: Int = 0,
    resyncSlot: Int = -1,
    modifier: Modifier = Modifier,
    helperUnreachable: Boolean = false,
    audibleSlot: Int = -1,
    captionsOnSlots: Set<Int> = emptySet(),
    onSoftCaptionAvailabilityChanged: (slotIndex: Int, available: Boolean) -> Unit = { _, _ -> },
    focusedCellIndex: Int? = null,
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

    // Listen for soft-caption-track availability per slot so the
    // controls overlay can surface "not available" honestly. Each
    // player's hasSoftCaptionTrack flips on the manifest's
    // onTracksChanged; we forward those updates up to the caller via
    // the callback. Re-keyed on bound + readyVersion so we re-subscribe
    // when the player set changes.
    LaunchedEffect(bound, readyVersion) {
        bound.forEach { tile ->
            val player = tile.player ?: return@forEach
            val slot = tile.slot as? Slot.Playing ?: return@forEach
            launch {
                player.hasSoftCaptionTrack.collect { available ->
                    onSoftCaptionAvailabilityChanged(slot.index, available)
                }
            }
        }
    }

    // Manual reconnect (Part D): when the nonce bumps, reload the requested
    // tile's stream (or all of them) — the operator refreshing a drifted feed.
    // Skip nonce 0 (initial) so a recompose doesn't reconnect on first frame.
    LaunchedEffect(reconnectNonce) {
        if (reconnectNonce <= 0) return@LaunchedEffect
        if (reconnectSlot < 0) {
            manager.reconnectAll()
        } else {
            val pIdx = playingSlots.indexOfFirst { it.index == reconnectSlot }
            if (pIdx >= 0) manager.reconnect(pIdx)
        }
    }

    // Quick resync (Part D): jump the requested tile (or all) to the live edge —
    // dropping the behind-live backlog — or reconnect it if it's actually dead.
    // Lighter than a full reconnect. Skip nonce 0 (initial) like reconnect.
    LaunchedEffect(resyncNonce) {
        if (resyncNonce <= 0) return@LaunchedEffect
        if (resyncSlot < 0) {
            manager.resyncAll()
        } else {
            val pIdx = playingSlots.indexOfFirst { it.index == resyncSlot }
            if (pIdx >= 0) manager.resync(pIdx)
        }
    }

    // Explicit column count from the operator's R×C setting (clamped so a 1-tile
    // grid can't try to draw 3 columns). Rows are derived from count ÷ columns,
    // which equals the configured rows when count = rows × cols.
    val cols = columns.coerceIn(1, bound.size.coerceAtLeast(1))

    Box(modifier = modifier.background(WallColors.Background)) {
        if (bound.isEmpty()) {
            EmptyState(
                reason = if (helperUnreachable) "Helper unreachable — wall idle."
                else "No channels reported by the helper.",
            )
        } else {
            AutofitGrid(
                columns = cols,
                bound = bound,
                focusedCellIndex = focusedCellIndex,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AutofitGrid(
    columns: Int,
    bound: List<BoundTile>,
    focusedCellIndex: Int?,
    modifier: Modifier = Modifier,
) {
    val rows = gridRowsFor(bound.size, columns)
    Column(
        // Reserve the overscan-safe bottom band so the bottom row's label strip
        // (and video) sit inside what the panel actually shows — the cells +
        // their below-labels are laid out within this safe area, so no label
        // can fall into the clipped edge at any grid size. (Derived from the
        // residual clip the operator's fit leaves; the fit itself is untouched.)
        modifier = modifier.fillMaxSize().padding(start = 2.dp, top = 2.dp, end = 2.dp, bottom = SECTION_SAFE_BOTTOM),
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
                        // Focus highlight: the `bound` list only contains
                        // Playing slots — the focused cell's index is the
                        // grid slot index, which equals the bound list
                        // index when the grid only has playing tiles.
                        // For a mixed list (Playing/Offline/Empty) the
                        // bound index still represents this visible cell.
                        val isFocused = focusedCellIndex == idx
                        key(tile.key) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .then(
                                        if (isFocused) {
                                            Modifier.border(
                                                width = 3.dp,
                                                color = WallColors.BadgeLive,
                                            )
                                        } else Modifier,
                                    ),
                            ) {
                                WallTile(
                                    bound = tile,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
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
