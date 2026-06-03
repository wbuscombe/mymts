package com.mymts.ui.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Divider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mymts.data.helper.ChannelsRepository
import com.mymts.data.helper.FeedRepository
import com.mymts.data.helper.HelperClient
import com.mymts.data.ticker.SampleTickerSource

/**
 * Stage 3 final assembled wall.
 *
 * Layout (dark, dense, single-screen, ambient):
 *
 *   ┌──────────────────────────────────────────────┐
 *   │  ticker (thin marquee strip)                 │
 *   ├──────────────┬───────────────────────────────┤
 *   │              │                               │
 *   │  feed pane   │   video grid (2×2 at N=4)     │
 *   │  ~28% width  │   ~72% width                  │
 *   │              │                               │
 *   └──────────────┴───────────────────────────────┘
 *
 * Each region owns its own data source and refresh cadence; nothing
 * cross-talks. A failure in one region must not cascade — Trust Bar
 * **C2**. The whole screen is below 40 dp of chrome (the ticker); the
 * rest is content.
 */
@Composable
fun WallScreen(
    helperBaseUrl: String,
    tileCount: Int,
    modifier: Modifier = Modifier,
) {
    val client = remember(helperBaseUrl) { HelperClient(helperBaseUrl) }
    val channels = remember(client) { ChannelsRepository(client) }
    val feed = remember(client) { FeedRepository(client) }
    val ticker = remember { SampleTickerSource() }

    DisposableEffect(channels, feed, ticker) {
        channels.start()
        feed.start()
        ticker.start()
        onDispose {
            channels.stop()
            feed.stop()
            ticker.stop()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WallColors.Background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TickerStrip(source = ticker)
            Divider(color = Color(0x22FFFFFF), thickness = 1.dp)
            Row(modifier = Modifier.fillMaxSize()) {
                FeedPane(
                    repository = feed,
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.28f),
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(Color(0x22FFFFFF)),
                )
                VideoGrid(
                    repository = channels,
                    tileCount = tileCount,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

