package com.mymts.ui.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.mymts.data.helper.Channel
import com.mymts.util.CrashLog
import com.mymts.player.StreamPlayer
import com.mymts.ui.components.StreamSurface
import kotlinx.coroutines.delay

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
            is TileSlotResolver.Slot.Radar -> RadarTile(slot)
            is TileSlotResolver.Slot.Playing -> PlayingTile(slot, bound.player)
        }
    }
}

/**
 * Label-strip geometry. The strip reserves [LABEL_STRIP_HEIGHT] below the video
 * in every cell; [LABEL_BOTTOM_BUFFER] is breathing room between the title text
 * and the cell's bottom border so descenders aren't flush against the edge
 * (the operator's preference — additive, no placement change, stays below). The
 * extra dp come out of the video area's `weight(1f)`, so the strip + buffer stay
 * inside the cell and within the section's overscan-safe band — never re-clipped.
 */
internal val LABEL_BOTTOM_BUFFER = 4.dp
internal val LABEL_STRIP_HEIGHT = 22.dp

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

/**
 * A weather-radar WIDGET tile (unified registry, 2026-07) — the helper-proxied NWS
 * RIDGE animated loop, rendered as an animated GIF via Coil (NOT an ExoPlayer surface).
 * Same [video-area + label-strip] shape as a video tile so the wall reads uniformly.
 * No audio, no captions, no LIVE/state badge — a widget has no stream liveness to
 * assert; its honesty is the `<img>` load itself. Honest fallback: until the FIRST
 * frame loads it shows a quiet "radar unavailable" state (never a fake radar); the
 * helper already serves last-good-stale frames, so a later refresh almost always still
 * yields a real (if stale) image rather than an error.
 */
@Composable
private fun RadarTile(slot: TileSlotResolver.Slot.Radar) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            RadarImage(imageUrl = slot.imageUrl, label = slot.channel.label)
        }
        LabelStrip(label = slot.channel.label, color = WallColors.LabelPrimary)
    }
}

/** A radar tile re-pulls a fresh NWS scan on this cadence (the loop GIF refreshes
 *  ~every 5 min; the helper cache is region-keyed so the cache-bust hits a warm cache,
 *  not NWS). Mirrors the web client's RADAR_REFRESH_MS. */
private const val RADAR_REFRESH_MS = 5 * 60 * 1000L

@Composable
private fun RadarImage(imageUrl: String, label: String) {
    val context = LocalContext.current
    // The decoder + a SINGLE shared ImageLoader live in MyMtsApp (ImageLoaderFactory):
    // on API 28+ that's the platform ImageDecoderDecoder, NOT the legacy Movie-based
    // GifDecoder — retiring the deprecated software GIF path (the 2026-07 crash
    // hardening). SubcomposeAsyncImage below defaults imageLoader to that app
    // singleton, so we no longer build (and leak) a per-tile loader.

    // Bump a refresh tick every RADAR_REFRESH_MS so the tile pulls the next scan.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(imageUrl) {
        while (true) {
            delay(RADAR_REFRESH_MS)
            tick++
            // Forensic breadcrumb (durable, adb-pullable log): if a later native
            // crash happens, the trail shows the radar was decoding right before.
            CrashLog.log(context, "RADAR_REFRESH | ${label.take(24)} | tick=$tick")
        }
    }
    // Re-fetch on each tick with caching DISABLED — the helper already region-caches
    // server-side (no NWS re-hit), and disabling Coil's memory+disk cache means the
    // per-tick key never accumulates distinct cache entries (the prior monotonic
    // "?t=" cache-bust minted unbounded keys). tick is only a request-change trigger.
    val model = remember(imageUrl, tick) {
        val sep = if (imageUrl.contains('?')) "&" else "?"
        "$imageUrl${sep}t=$tick"
    }

    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(model)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .crossfade(false)
            .build(),
        contentDescription = label,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
        loading = { RadarPlaceholder(label, "loading radar…") },
        error = { RadarPlaceholder(label, "weather radar unavailable") },
    )
}

@Composable
private fun RadarPlaceholder(label: String, sub: String) {
    Column(
        modifier = Modifier.fillMaxSize().background(WallColors.DeadTile),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = label, color = WallColors.LabelPrimary, fontSize = 12.sp, maxLines = 1)
        Text(text = sub, color = WallColors.LabelGhost, fontSize = 10.sp)
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
            .padding(start = 6.dp, end = 6.dp, bottom = LABEL_BOTTOM_BUFFER),
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
