package com.mymts.ui.wall

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.mymts.player.LivenessTracker
import com.mymts.player.StreamPlayer
import com.mymts.player.StreamPlayerManager
import com.mymts.player.StreamSpec
import com.mymts.ui.menu.MenuState
import com.mymts.ui.menu.WallRefreshActions
import com.mymts.ui.menu.refreshAllFeeds
import com.mymts.ui.menu.refreshAllVideo
import com.mymts.ui.menu.resyncAllFeeds
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One "Refresh all feeds" activation in plain Kotlin, end to end: the real row decision,
 * the real StreamPlayerManager over fake players, and each radar tile's real
 * [RadarReloads]. [Wall] binds the refresh actions as WallScreen does, and applies
 * VideoGrid's and RadarImage's reactions directly; those Compose effects are checked on
 * the device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshAllFeedsWallTest {

    private class Wall(videoTiles: Int, radarTiles: Int) : WallRefreshActions {
        val menu = MenuState()
        val players = mutableListOf<StreamPlayer>()
        val manager = StreamPlayerManager(
            context = mockk<Context>(relaxed = true),
            specs = List(videoTiles) {
                StreamSpec(id = "video-$it", label = "Video $it", url = "https://example.test/$it.m3u8")
            },
            playerFactory = { _, spec -> fakePlayer(spec).also { players += it } },
        ).also { it.onStart(mockk<LifecycleOwner>(relaxed = true)) }

        var radarRequests = 0
            private set
        private val radar = List(radarTiles) { RadarReloads(answeredRequests = radarRequests) }
        val radarReloads = IntArray(radarTiles)

        override fun reconnectAllVideo() = manager.reconnectAll()
        override fun reloadAllRadar() {
            radarRequests++
        }
        override fun resyncAll() = manager.resyncAll()
        override fun closeMenu() = menu.close()

        /** Each radar tile's effect relaunching for the current request count. */
        fun recomposeRadar(scope: TestScope) {
            radar.forEachIndexed { i, tile ->
                val loop = scope.launch {
                    tile.run(requestedReloads = radarRequests, intervalMs = RADAR_REFRESH_MS) { radarReloads[i]++ }
                }
                scope.runCurrent()
                loop.cancel()
            }
            scope.runCurrent()
        }

        private fun fakePlayer(spec: StreamSpec): StreamPlayer = mockk(relaxed = true) {
            every { specId } returns spec.id
            every { state } returns MutableStateFlow(StreamPlayer.State.LIVE)
        }
    }

    @Test fun `one activation reconnects every video tile once, reloads each radar tile once and closes`() = runTest {
        val wall = Wall(videoTiles = 2, radarTiles = 2)
        wall.menu.open()
        wall.refreshAllFeeds()
        wall.recomposeRadar(this)
        assertEquals(2, wall.players.size)
        wall.players.forEach { p ->
            verify(exactly = 1) { p.reconnect() } // the full reconnect, recovery reset included
            verify(exactly = 0) { p.needsReconnect() } // not the resync path's dead-only test
            verify(exactly = 0) { p.seekToLive() }
        }
        assertArrayEquals("one reload per radar tile", intArrayOf(1, 1), wall.radarReloads)
        assertFalse(wall.menu.isOpen)
        wall.recomposeRadar(this)
        assertArrayEquals("a later relaunch fetches nothing more", intArrayOf(1, 1), wall.radarReloads)
    }

    @Test fun `with no radar tile only the video reconnect runs`() = runTest {
        val wall = Wall(videoTiles = 3, radarTiles = 0)
        wall.menu.open()
        wall.refreshAllFeeds()
        wall.recomposeRadar(this)
        assertEquals(3, wall.players.size)
        wall.players.forEach { p ->
            verify(exactly = 1) { p.reconnect() }
            verify(exactly = 0) { p.seekToLive() }
        }
        assertEquals(0, wall.radarReloads.size)
        assertFalse(wall.menu.isOpen)
    }

    @Test fun `resync all feeds and refresh all video keep their own actions on the wall`() = runTest {
        val resync = Wall(videoTiles = 2, radarTiles = 1).apply { menu.open() }
        resync.resyncAllFeeds()
        resync.recomposeRadar(this)
        resync.players.forEach { p ->
            verify(exactly = 0) { p.reconnect() } // the fake tiles are live, so resync only seeks
            verify(exactly = 1) { p.seekToLive() }
        }
        assertArrayEquals("Resync leaves radar alone", intArrayOf(0), resync.radarReloads)
        assertFalse(resync.menu.isOpen)

        val video = Wall(videoTiles = 2, radarTiles = 1).apply {
            menu.open()
            menu.openSettings()
        }
        video.refreshAllVideo()
        video.recomposeRadar(this)
        video.players.forEach { p ->
            verify(exactly = 1) { p.reconnect() }
            verify(exactly = 0) { p.seekToLive() }
        }
        assertArrayEquals("Refresh all video leaves radar alone", intArrayOf(0), video.radarReloads)
        assertTrue("WALL SETTINGS stays open over the menu", video.menu.isOpen && video.menu.pendingSelection != null)
    }

    @Test fun `the refresh's reconnect counts no strike and clears earlier ones`() {
        // StreamPlayer.reconnect() runs initialize(), which calls this same reset(). Only
        // the liveness ladder's onTick counts a strike.
        var now = 0L
        val tracker = LivenessTracker(clock = { now })
        tracker.onFrameRendered()
        now += tracker.staleThresholdMs + 1
        tracker.onTick() // LIVE -> STALE
        now += tracker.backoffMs[0]
        assertEquals(LivenessTracker.TickAction.ATTEMPT_PREPARE, tracker.onTick())
        assertEquals("one strike before the refresh", 1, tracker.recoveryAttempts)
        tracker.reset()
        assertEquals(0, tracker.recoveryAttempts)
        assertEquals(LivenessTracker.State.CONNECTING, tracker.state)
        assertEquals(LivenessTracker.TickAction.NONE, tracker.onTick())
        assertEquals("the refresh itself adds no strike", 0, tracker.recoveryAttempts)
    }
}
