package com.mymts.ui.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.mymts.data.helper.ChannelsRepository
import com.mymts.data.helper.HelperClient

/**
 * The Stage 3 wall — checkpoint A.
 *
 * Today this is just the [VideoGrid]; the feed pane and ticker assemble
 * around it in checkpoint B (see `docs/STAGE-3-PLAN.md`). Splitting the
 * screen here means the eventual full layout drops in without touching
 * the grid wiring.
 */
@Composable
fun WallScreen(
    helperBaseUrl: String,
    tileCount: Int,
    modifier: Modifier = Modifier,
) {
    val client = remember(helperBaseUrl) { HelperClient(helperBaseUrl) }
    val repository = remember(client) { ChannelsRepository(client) }

    DisposableEffect(repository) {
        repository.start()
        onDispose { repository.stop() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WallColors.Background),
    ) {
        VideoGrid(
            repository = repository,
            tileCount = tileCount,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
