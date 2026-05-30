package com.mymts.soak

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymts.player.StreamPlayer
import com.mymts.player.StreamPlayerManager
import com.mymts.ui.components.StreamSurface
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlinx.coroutines.delay

/**
 * The soak harness. Multi-tile playback at N tiles with structured telemetry
 * for host-side analysis.
 *
 * This is the apparatus the Stage-1 gate measures with; it is not a product
 * surface. The real grid + lineup land in Stage 3+. Keep this minimal so
 * the budget number we produce is about the *box*, not about whatever
 * complexity the harness happens to carry.
 */
@Composable
fun SoakHarness(spec: SoakSpec) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val pool = if (spec.pool == "stable") SoakFixtures.STABLE else SoakFixtures.LIVE
    val specs = remember(spec.tiles, spec.pool) { SoakFixtures.pick(spec.tiles, pool) }

    val manager = remember(specs) { StreamPlayerManager(context, specs) }

    DisposableEffect(manager) {
        lifecycleOwner.lifecycle.addObserver(manager)
        SoakLog.start(specs.size, spec.resolutionHint)
        specs.forEach { SoakLog.tileMount(it.id, it.url) }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(manager)
            Log.i(SoakLog.TAG, "EV=END")
        }
    }

    // Heartbeat emitter — gives the host-side parser a regular "I'm still
    // alive" anchor so a stuck app is detectable without parsing absences.
    LaunchedEffect(manager, spec.heartbeatIntervalMs) {
        while (true) {
            delay(spec.heartbeatIntervalMs)
            manager.players.forEach { (idx, player) ->
                SoakLog.heartbeat(
                    idx = idx,
                    id = player.specId,
                    state = player.state.value.name,
                    dropped = player.droppedFrames,
                    lastFrameAtMs = player.lastFrameAtMs,
                )
            }
        }
    }

    // Pick a grid shape that fits the tile count. Square-ish to keep aspect
    // ratios sane on a 16:9 screen.
    val columns = ceil(sqrt(specs.size.toDouble())).toInt().coerceAtLeast(1)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Slim header surfacing the live state — useful when watching the
        // box from across the room during a soak.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111111))
                .padding(8.dp),
        ) {
            Text(
                text = "SOAK · ${specs.size} tile${if (specs.size == 1) "" else "s"} · pool=${spec.pool}",
                color = Color(0xFF66BB6A),
                fontSize = 14.sp,
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items = specs, key = { it.id }) { streamSpec ->
                val idx = specs.indexOf(streamSpec)
                val player = manager.player(idx)
                TileBox(player = player, label = streamSpec.label)
            }
        }
    }
}

@Composable
private fun TileBox(player: StreamPlayer?, label: String) {
    val state by (player?.state?.collectAsState() ?: return TilePlaceholder(label))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color(0xFF050505)),
    ) {
        StreamSurface(
            player = player.getPlayer(),
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            contentAlignment = androidx.compose.ui.Alignment.TopStart,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = label,
                    color = Color(0xCCFFFFFF),
                    fontSize = 12.sp,
                )
                Text(
                    text = state.name,
                    color = when (state) {
                        StreamPlayer.State.LIVE -> Color(0xFF66BB6A)
                        StreamPlayer.State.CONNECTING -> Color(0xFFFFEE58)
                        StreamPlayer.State.RECONNECTING -> Color(0xFFFFCC80)
                        StreamPlayer.State.OFFLINE -> Color(0xFFEF5350)
                    },
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun TilePlaceholder(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color(0xFF222222)),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text(label, color = Color.LightGray, fontSize = 12.sp)
    }
}

