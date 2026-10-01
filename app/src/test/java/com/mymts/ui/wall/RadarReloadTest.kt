package com.mymts.ui.wall

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Refresh all feeds" reloads each radar tile once, through the tile's own timed reload:
 * [RadarReloads], which RadarImage's effect relaunches whenever the request count
 * changes. Every reload, timed or requested, advances the tile's one request key, so a
 * reload replaces the pending image request rather than adding a fetch. Coil executing
 * a painter's requests latest-only is checked on the device. These tests pin the
 * schedule and the request bookkeeping in virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadarReloadTest {

    /**
     * One radar tile as RadarImage drives it. [compose] (re)launches the reload loop for
     * the current request count and cancels the previous loop, as the LaunchedEffect does
     * when its key changes. [reloads] counts request-key bumps; [loopsAlive] counts loops
     * still running.
     */
    private class Tile(private val scope: CoroutineScope, requestsWhenPlaced: Int = 0) {
        private val state = RadarReloads(answeredRequests = requestsWhenPlaced)
        private var loop: Job? = null
        var reloads = 0
            private set
        var loopsAlive = 0
            private set

        fun compose(requests: Int) {
            loop?.cancel()
            loop = scope.launch {
                loopsAlive++
                try {
                    state.run(requestedReloads = requests, intervalMs = RADAR_REFRESH_MS) { reloads++ }
                } finally {
                    loopsAlive--
                }
            }
        }

        fun dispose() {
            loop?.cancel()
        }
    }

    @Test fun `the radar interval is unchanged at five minutes`() {
        assertEquals(5 * 60 * 1000L, RADAR_REFRESH_MS)
    }

    @Test fun `with no request a tile reloads only on its timer`() = runTest {
        val tile = Tile(this)
        tile.compose(requests = 0)
        runCurrent()
        assertEquals("placing the tile reloads nothing", 0, tile.reloads)
        advanceTimeBy(RADAR_REFRESH_MS - 1)
        runCurrent()
        assertEquals(0, tile.reloads)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, tile.reloads)
        advanceTimeBy(RADAR_REFRESH_MS)
        runCurrent()
        assertEquals(2, tile.reloads)
        tile.dispose()
    }

    @Test fun `one request reloads the tile once now and the next timed reload counts from it`() = runTest {
        val tile = Tile(this)
        tile.compose(requests = 0)
        runCurrent()
        advanceTimeBy(4 * 60_000L) // four minutes into the interval
        runCurrent()
        tile.compose(requests = 1)
        runCurrent()
        assertEquals("one reload, now", 1, tile.reloads)
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals("the old timer's due time passes with no stacked reload", 1, tile.reloads)
        advanceTimeBy(RADAR_REFRESH_MS - 60_000L - 1)
        runCurrent()
        assertEquals(1, tile.reloads)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("the next timed reload lands one interval after the request", 2, tile.reloads)
        assertEquals(1, tile.loopsAlive)
        tile.dispose()
    }

    @Test fun `rapid repeated requests never queue reloads or stack timers`() = runTest {
        val tile = Tile(this)
        tile.compose(requests = 0)
        runCurrent()
        // Three activations inside one frame reach the tile as one new count: one reload.
        tile.compose(requests = 3)
        runCurrent()
        assertEquals(1, tile.reloads)
        // Then one activation per frame: each is one more bump of the same key.
        tile.compose(requests = 4)
        runCurrent()
        tile.compose(requests = 5)
        runCurrent()
        assertEquals(3, tile.reloads)
        assertEquals("one reload loop, never one per request", 1, tile.loopsAlive)
        advanceTimeBy(RADAR_REFRESH_MS)
        runCurrent()
        assertEquals("a single timed reload follows, not one per request", 4, tile.reloads)
        tile.dispose()
    }

    @Test fun `a request just after a timed reload bumps the key once more and restarts the timer`() = runTest {
        val tile = Tile(this)
        tile.compose(requests = 0)
        runCurrent()
        advanceTimeBy(RADAR_REFRESH_MS)
        runCurrent()
        assertEquals("the timed reload bumped the key", 1, tile.reloads)
        advanceTimeBy(500) // while that reload's image would still be loading
        runCurrent()
        tile.compose(requests = 1)
        runCurrent()
        assertEquals("the request bumps the same key once more", 2, tile.reloads)
        assertEquals(1, tile.loopsAlive)
        advanceTimeBy(RADAR_REFRESH_MS - 500) // the cancelled timer's due time
        runCurrent()
        assertEquals("no timed reload at the old due time", 2, tile.reloads)
        advanceTimeBy(500) // one interval after the request
        runCurrent()
        assertEquals(3, tile.reloads)
        tile.dispose()
    }

    @Test fun `a tile placed after a refresh, or relaunched with no new request, reloads nothing now`() = runTest {
        val tile = Tile(this, requestsWhenPlaced = 7)
        tile.compose(requests = 7)
        runCurrent()
        assertEquals(0, tile.reloads)
        tile.compose(requests = 7) // the effect relaunching for another key, e.g. a new image url
        runCurrent()
        assertEquals(0, tile.reloads)
        assertEquals(1, tile.loopsAlive)
        tile.dispose()
    }
}
