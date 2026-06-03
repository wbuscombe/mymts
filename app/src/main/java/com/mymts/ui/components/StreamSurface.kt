package com.mymts.ui.components

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Compose-host for an ExoPlayer's video output.
 *
 * Stage 3 polish: switched from a raw `SurfaceView` to Media3's
 * `PlayerView` so we get the standard aspect-ratio-preserving resize
 * modes "for free" — the right tool for a video wall whose cells
 * have arbitrary dimensions vs. arbitrary source resolutions.
 *
 * Default `resizeMode = RESIZE_MODE_FIT`: each tile fills its cell's
 * width as much as possible while keeping the source aspect ratio,
 * letterboxing top/bottom (or pillarboxing left/right) with the
 * wall's near-black background where dimensions don't match. No
 * stretching, no center-cropping. Operator-approved default for a
 * video wall (clean black bars beat distorted or off-center video).
 *
 * Player chrome is disabled: no controller, no buffering spinner,
 * no error overlay. The wall's own state-machine UI in [WallTile]
 * is the single source of truth for what each tile is doing.
 */
@OptIn(UnstableApi::class)
@Composable
fun StreamSurface(
    player: ExoPlayer?,
    modifier: Modifier = Modifier,
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
) {
    DisposableEffect(player) {
        onDispose { player?.clearVideoSurface() }
    }

    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                this.resizeMode = resizeMode
                // No tap-to-toggle controller, no rewind/forward inc — TV
                // remote D-pad sanity comes from the surrounding wall, not
                // the player chrome.
                setUseArtwork(false)
                this.player = player
            }
        },
        update = { view ->
            view.resizeMode = resizeMode
            if (view.player !== player) view.player = player
        },
        modifier = modifier,
    )
}
