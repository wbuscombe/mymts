package com.mymts.player

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Direct guard for the `readyVersion` mechanism that the wall's
 * `VideoGrid` depends on.
 *
 * The `b19b013` regression stemmed from `VideoGrid` materialising a
 * player-by-spec-id map inside a `remember(manager, specs) { … }`
 * block that ran during composition — before the manager's lifecycle
 * `onStart` had populated any players. Without an observable signal
 * for "players are now ready," Compose had no key to invalidate the
 * cached binding. This test pins the manager half of the contract:
 *   - before `onStart`, [StreamPlayerManager.readyVersion] is `0` and
 *     [StreamPlayerManager.player] returns `null` for every index;
 *   - after `onStart`, `readyVersion` has **incremented** and every
 *     spec has a real player.
 *
 * The fake [playerFactory] lets this run on plain JVM with no real
 * Context — the real factory builds an ExoPlayer.
 */
class StreamPlayerManagerReadinessTest {

    private val specs = listOf(
        StreamSpec(id = "spec-a", label = "A", url = "https://example.test/a.m3u8"),
        StreamSpec(id = "spec-b", label = "B", url = "https://example.test/b.m3u8"),
    )

    private val mockContext: Context = mockk(relaxed = true)
    private val owner: LifecycleOwner = mockk(relaxed = true)

    private fun fakePlayerFor(spec: StreamSpec): StreamPlayer = mockk(relaxed = true) {
        every { specId } returns spec.id
        every { state } returns MutableStateFlow(StreamPlayer.State.LIVE)
    }

    private fun managerWithFakeFactory(): Pair<StreamPlayerManager, MutableMap<String, StreamPlayer>> {
        val createdBySpec = mutableMapOf<String, StreamPlayer>()
        val factory: (Context, StreamSpec) -> StreamPlayer = { _, spec ->
            fakePlayerFor(spec).also { createdBySpec[spec.id] = it }
        }
        return StreamPlayerManager(mockContext, specs, playerFactory = factory) to createdBySpec
    }

    @Test fun `readyVersion starts at zero and player(idx) is null before onStart`() {
        val (manager, _) = managerWithFakeFactory()
        assertEquals("readyVersion starts at 0", 0, manager.readyVersion.value)
        specs.indices.forEach { idx ->
            assertNull("player($idx) is null before onStart", manager.player(idx))
        }
    }

    @Test fun `onStart populates players and flips readyVersion`() {
        val (manager, created) = managerWithFakeFactory()
        manager.onStart(owner)

        // The signal that VideoGrid observes flips to a non-zero value,
        // which is what invalidates Compose's remember and causes the
        // wall to re-bind to the now-non-null players.
        assertEquals(
            "readyVersion increments after onStart",
            1,
            manager.readyVersion.value,
        )

        specs.forEachIndexed { idx, spec ->
            val p = manager.player(idx)
            assertNotNull("player($idx) is non-null after onStart", p)
            assertEquals(
                "player($idx).specId matches the spec at that index",
                spec.id,
                p!!.specId,
            )
            // The exact instance the factory produced — not a different
            // player that happens to have the same specId.
            assertSame(
                "player($idx) IS the instance the factory produced for ${spec.id}",
                created[spec.id],
                p,
            )
            // initialize() was called on every player — startup wiring
            // is in place, not just the constructor.
            verify { p.initialize() }
        }
    }

    @Test fun `onStart is idempotent — re-entering doesn't double-init`() {
        val (manager, created) = managerWithFakeFactory()
        manager.onStart(owner)
        val versionAfterFirst = manager.readyVersion.value
        val firstPlayer = manager.player(0)

        manager.onStart(owner)

        assertEquals(
            "readyVersion does NOT bump on a duplicate onStart — players are unchanged",
            versionAfterFirst,
            manager.readyVersion.value,
        )
        assertSame("player(0) is the same instance", firstPlayer, manager.player(0))
        assertEquals(
            "factory was invoked exactly once per spec",
            specs.size,
            created.size,
        )
        // initialize() called exactly once per player across both onStarts.
        verify(exactly = 1) { firstPlayer!!.initialize() }
    }

    @Test fun `onDestroy releases players and bumps readyVersion`() {
        val (manager, _) = managerWithFakeFactory()
        manager.onStart(owner)
        val initialPlayer = manager.player(0)
        val versionAfterStart = manager.readyVersion.value

        manager.onDestroy(owner)

        verify { initialPlayer!!.release() }
        specs.indices.forEach { idx ->
            assertNull("player($idx) is null after onDestroy", manager.player(idx))
        }
        // Observers see the readiness change so they can collapse to the
        // C2 panel rather than holding a stale reference.
        assertEquals(
            "readyVersion bumps on onDestroy",
            versionAfterStart + 1,
            manager.readyVersion.value,
        )
    }

    // ---- P-N3: url-rotation swaps in place, never rebuilds the grid ----

    @Test fun `updateSpecs swaps only the changed tile's url in place — no rebuild`() {
        val (manager, created) = managerWithFakeFactory()
        manager.onStart(owner)
        val versionAfterStart = manager.readyVersion.value

        // spec-a's RESOLVED url rotates (a YouTube manifest expire token); spec-b is
        // unchanged. This is the common case that used to rebuild the WHOLE grid.
        val rotated = listOf(
            specs[0].copy(url = "https://example.test/a-NEWTOKEN.m3u8"),
            specs[1],
        )
        manager.updateSpecs(rotated)

        // Only the changed tile re-points IN PLACE; the other is untouched; the
        // manager is NOT torn down — readyVersion unchanged and the SAME instances.
        verify(exactly = 1) { created["spec-a"]!!.updateUrl("https://example.test/a-NEWTOKEN.m3u8") }
        verify(exactly = 0) { created["spec-b"]!!.updateUrl(any()) }
        assertEquals("no rebuild: readyVersion unchanged", versionAfterStart, manager.readyVersion.value)
        assertSame("player(0) is the SAME instance (not rebuilt)", created["spec-a"], manager.player(0))
        assertSame("player(1) is the SAME instance (not rebuilt)", created["spec-b"], manager.player(1))
    }

    @Test fun `updateSpecs with no url change is a no-op`() {
        val (manager, created) = managerWithFakeFactory()
        manager.onStart(owner)
        manager.updateSpecs(specs) // identical urls
        specs.forEach { verify(exactly = 0) { created[it.id]!!.updateUrl(any()) } }
    }

    @Test fun `releaseAll releases every player, clears the set, bumps readyVersion`() {
        val (manager, created) = managerWithFakeFactory()
        manager.onStart(owner)
        val v = manager.readyVersion.value

        manager.releaseAll()

        created.values.forEach { verify { it.release() } }
        specs.indices.forEach { assertNull("player($it) null after releaseAll", manager.player(it)) }
        assertEquals("readyVersion bumps on releaseAll", v + 1, manager.readyVersion.value)
    }
}
