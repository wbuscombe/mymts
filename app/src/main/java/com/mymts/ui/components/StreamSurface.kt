package com.mymts.ui.components

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer

/**
 * Compose-host for an ExoPlayer's SurfaceView. The cleanup discipline
 * (clear-surface-before-dispose, re-attach in surfaceCreated, clear in
 * surfaceDestroyed) is copied verbatim from WyzeGrid's pattern because
 * skipping any of it leaks the native graphics buffer.
 */
@Composable
fun StreamSurface(
    player: ExoPlayer?,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(player) {
        onDispose { player?.clearVideoSurface() }
    }

    AndroidView(
        factory = { context ->
            SurfaceView(context).also { surfaceView ->
                surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        player?.setVideoSurfaceView(surfaceView)
                    }
                    override fun surfaceChanged(
                        holder: SurfaceHolder, format: Int, width: Int, height: Int,
                    ) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        player?.clearVideoSurface()
                    }
                })
                if (surfaceView.holder.surface.isValid) {
                    player?.setVideoSurfaceView(surfaceView)
                }
            }
        },
        update = { it.also { sv -> player?.setVideoSurfaceView(sv) } },
        modifier = modifier,
    )
}
