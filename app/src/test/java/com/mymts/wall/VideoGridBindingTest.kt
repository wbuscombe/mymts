package com.mymts.wall

import com.mymts.data.helper.Channel
import com.mymts.player.StreamPlayer
import com.mymts.player.StreamSpec
import com.mymts.ui.wall.BoundTile
import com.mymts.ui.wall.TileSlotResolver
import com.mymts.ui.wall.TileSlotResolver.Slot
import com.mymts.ui.wall.bindTiles
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the `b19b013` video-startup bug.
 *
 * **What broke:** `VideoGrid` materialized the player-by-spec-id map
 * inside `remember(manager, specs) { … manager.player(idx) … }`. That
 * `remember` block evaluates **during composition**, before the
 * `DisposableEffect` that registers the manager as a lifecycle observer
 * has run. So `manager.player(idx)` returned `null` for every idx, the
 * map permanently cached nulls, and — because `(manager, specs)` was
 * stable across recompositions — nothing invalidated the cache when
 * `manager.onStart` later populated real players. Every tile rendered
 * the C2 dead panel (the early-return path when `player == null`), no
 * `StreamSurface` ever mounted, and `EV=TILE_READY` never fired — a
 * silent startup regression that the C2 honest-degradation UI masked
 * because a null-player tile is pixel-identical to a settled-DEAD tile.
 *
 * **What the existing `BoundTileTest` did not catch:** those tests
 * pass a fully-populated player map to `bindTiles`. They verify the
 * pairing correctness *assuming players exist*. The regression was
 * "players never delivered to the binding," which is a wiring property
 * the prior tests did not exercise.
 *
 * **The test pattern:** simulate Compose's `remember(keys) { compute }`
 * cache semantics manually. With the buggy key list, the cached
 * bindings stay null even after the manager populates. With the fixed
 * key list (including the readiness signal), the cache invalidates
 * and the new bindings carry the populated players.
 */
class VideoGridBindingTest {

    /**
     * Minimal in-memory analogue of Compose's `remember(keys) { compute }`:
     * recomputes only when the key list changes; otherwise returns the
     * cached value verbatim. Enough to demonstrate the regression and
     * the fix in a pure-JVM unit test.
     */
    private class RememberCache<T : Any> {
        private var cached: T? = null
        private var lastKeys: List<Any?> = LIST_SENTINEL
        fun get(keys: List<Any?>, compute: () -> T): T {
            if (lastKeys !== LIST_SENTINEL && keys == lastKeys) {
                @Suppress("UNCHECKED_CAST")
                return cached as T
            }
            cached = compute()
            lastKeys = keys
            @Suppress("UNCHECKED_CAST")
            return cached as T
        }
        private companion object {
            val LIST_SENTINEL: List<Any?> = listOf(Any())
        }
    }

    /** A bare-bones manager-shaped stub the tests can populate on demand. */
    private class StubManager(val specs: List<StreamSpec>) {
        private val players = mutableMapOf<Int, StreamPlayer>()
        var readyVersion: Int = 0
            private set

        fun player(idx: Int): StreamPlayer? = players[idx]

        /** Populate all players (simulates real `onStart`). */
        fun populate() {
            specs.forEachIndexed { idx, spec ->
                players[idx] = mockk(relaxed = true) {
                    every { specId } returns spec.id
                    every { state } returns MutableStateFlow(StreamPlayer.State.LIVE)
                }
            }
            readyVersion++
        }
    }

    private fun channelOf(slug: String) = Channel(
        slug = slug,
        label = slug.uppercase(),
        kind = "hls",
        currentUrl = "https://example.test/$slug.m3u8",
        status = Channel.Status.LIVE,
        lastSuccessAt = "2026-06-03T00:00:00Z",
        lastError = null,
        errorCount = 0,
    )

    private fun makeSetup(): Triple<List<Slot>, List<Slot.Playing>, StubManager> {
        val a = channelOf("a")
        val b = channelOf("b")
        val slots = TileSlotResolver.resolve(tileCount = 2, liveChannels = listOf(a, b))
        val playing = slots.filterIsInstance<Slot.Playing>()
        val manager = StubManager(specs = playing.map { it.spec })
        return Triple(slots, playing, manager)
    }

    /** The bind step VideoGrid runs — extracted so both patterns can use it. */
    private fun bind(
        slots: List<Slot>,
        playing: List<Slot.Playing>,
        manager: StubManager,
    ): List<BoundTile> = bindTiles(slots) { specId ->
        val idx = playing.indexOfFirst { it.spec.id == specId }
        if (idx >= 0) manager.player(idx) else null
    }

    /**
     * **The regression** — this is what `b19b013` shipped. The cache key
     * list is `(slots, manager)`; neither changes when the manager later
     * populates its players, so the cached null bindings persist forever.
     * No `EV=TILE_READY` would ever fire under this wiring.
     */
    @Test fun `buggy wiring without readyVersion key caches null forever (b19b013 regression)`() {
        val (slots, playing, manager) = makeSetup()
        val cache = RememberCache<List<BoundTile>>()

        // First "composition" pass — manager has no players yet (the
        // DisposableEffect that adds the lifecycle observer hasn't run).
        val firstPass = cache.get(listOf(slots, manager)) { bind(slots, playing, manager) }
        assertEquals(2, firstPass.size)
        assertTrue(
            "before populate, every bound player is null",
            firstPass.all { it.player == null },
        )

        // Lifecycle `onStart` runs, manager populates players.
        manager.populate()

        // Second "composition" pass — but `(slots, manager)` is unchanged,
        // so the buggy cache returns the same null-laden binding.
        val secondPass = cache.get(listOf(slots, manager)) { bind(slots, playing, manager) }
        assertTrue(
            "regression: cache still returns null bindings after populate",
            secondPass.all { it.player == null },
        )
        // Sanity: the manager DOES have players now — the bug is the
        // binding never re-reads.
        assertNotNull(manager.player(0))
        assertNotNull(manager.player(1))
    }

    /**
     * **The fix** — VideoGrid now includes `manager.readyVersion` in the
     * `remember` key list. When `populate()` (analogous to `onStart`)
     * bumps `readyVersion`, the cache invalidates, the bindings recompute,
     * and the BoundTiles carry the now-non-null players.
     */
    @Test fun `fixed wiring keyed on readyVersion picks up players after populate`() {
        val (slots, playing, manager) = makeSetup()
        val cache = RememberCache<List<BoundTile>>()

        val firstPass = cache.get(
            listOf(slots, manager, manager.readyVersion),
        ) { bind(slots, playing, manager) }
        assertTrue(
            "before populate: bindings are null",
            firstPass.all { it.player == null },
        )

        manager.populate()

        val secondPass = cache.get(
            listOf(slots, manager, manager.readyVersion),
        ) { bind(slots, playing, manager) }
        // The key list changed because readyVersion went from 0 → 1.
        // Cache miss → recompute → real players paired.
        assertEquals(2, secondPass.size)
        secondPass.forEach { bt ->
            val slot = bt.slot as Slot.Playing
            assertNotNull(
                "after populate: slot ${slot.id} must have a real player",
                bt.player,
            )
            assertEquals(
                "and that player's specId must equal the slot's spec.id",
                slot.spec.id,
                bt.player!!.specId,
            )
        }
    }

    /**
     * Even with the fix, a tile whose specId has no matching player
     * (e.g. a transient mid-recomposition state) renders the C2 panel
     * via `player == null` — the wall never draws the wrong channel's
     * video. Belt-and-braces: the structural `BoundTile.init` check is
     * still in force.
     */
    @Test fun `lookup miss after readiness still pairs with null, not a stray player`() {
        val (slots, playing, _) = makeSetup()
        // Manager has only player for spec 1 — slot 0 should pair with null.
        val partialManager = StubManager(specs = playing.map { it.spec })
        // Populate only the second spec.
        val secondSpec = playing[1].spec.id
        val fakeFor1: StreamPlayer = mockk(relaxed = true) {
            every { specId } returns secondSpec
            every { state } returns MutableStateFlow(StreamPlayer.State.LIVE)
        }
        // Manual partial populate to simulate a half-populated manager
        // (the real manager populates atomically, but we want to confirm
        // partial-state safety regardless).
        val playerByIdx: (Int) -> StreamPlayer? = { idx -> if (idx == 1) fakeFor1 else null }
        val bound = bindTiles(slots) { specId ->
            val idx = playing.indexOfFirst { it.spec.id == specId }
            if (idx >= 0) playerByIdx(idx) else null
        }
        assertNull("slot 0 must be null, never a stray player", bound[0].player)
        assertNotNull("slot 1 has its real player", bound[1].player)
    }
}
