package com.mymts.soak

import com.mymts.player.StreamSpec

/**
 * Stage 1 soak fixtures.
 *
 * These are validation streams — chosen for stability of the HOST endpoint,
 * not for product fit. Real channel selection lands in Stage 5 (lineup +
 * picker). The point of these fixtures is to prove the soak method works
 * end-to-end against bytes-on-the-wire HLS, not to pick what the operator
 * will ultimately watch.
 *
 * Two pools:
 *   - LIVE: actual live broadcasts. May go off-air, change URL, or rate-limit.
 *     Used for the realistic soak — the failure shapes are the ones the
 *     production wall will see.
 *   - STABLE: looped test pattern HLS from a well-known CDN. Used when the
 *     LIVE pool is misbehaving so the method itself can still be validated.
 *
 * If a fixture URL goes dead during a soak, update this file in a single
 * commit and re-run; do NOT silently drop the dead fixture from the result.
 *
 * Soak harness picks `tiles` fixtures in order, cycling through the chosen
 * pool. The pool defaults to LIVE but can be overridden via intent extra
 * `--es pool stable`.
 */
object SoakFixtures {

    // LIVE-pool composition is mixed-on-purpose for the gate soak: 2 actual
    // live broadcasts give us steady-state playback, and 4 deliberately
    // flaky/reconnect-prone streams ensure the reconnect path (where leaks
    // hide) is exercised repeatedly across a multi-hour run. The mix was
    // validated by HEAD probe just before the long soak — if any URL stops
    // responding mid-run, replace it in a single commit and re-run; never
    // silently drop a dead fixture from a result.
    val LIVE: List<StreamSpec> = listOf(
        // Real live broadcasts — steady playback.
        StreamSpec(
            id = "nasa-public",
            label = "NASA TV Public (live)",
            url = "https://ntv1.akamaized.net/hls/live/2014075/NASA-NTV1-HLS/master.m3u8",
        ),
        StreamSpec(
            id = "redbull-tv",
            label = "Red Bull TV (live)",
            url = "https://rbmn-live.akamaized.net/hls/live/590964/BoRB-AT/master.m3u8",
        ),
        // Long-running test stream — public, well-known, exercises buffer
        // discipline over many hours.
        StreamSpec(
            id = "moctobpltc-eight",
            label = "moctobpltc 'eight' (long-running test)",
            url = "https://moctobpltc-i.akamaihd.net/hls/live/571329/eight/playlist.m3u8",
        ),
        // Mux's PTS-shift test stream — deliberately introduces presentation-
        // timestamp anomalies that drive periodic recovery. Exactly the path
        // we want exercised for leak hunting.
        StreamSpec(
            id = "mux-pts-shift",
            label = "Mux PTS-shift (deliberate timing anomalies)",
            url = "https://test-streams.mux.dev/pts_shift/master.m3u8",
        ),
        // Multi-variant VOD packaged as HLS — long enough that loop boundary
        // hits are spaced out, short enough that BEHIND_LIVE_WINDOW recoveries
        // happen multiple times across a 33h run.
        StreamSpec(
            id = "mux-x36xhzz",
            label = "Mux x36xhzz (multi-variant test asset)",
            url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        ),
        // Long-form VOD treated as live — guarantees BEHIND_LIVE_WINDOW
        // reconnect on finish, hammering the reconnect lifecycle.
        StreamSpec(
            id = "unified-tears",
            label = "Unified Streaming 'Tears of Steel' (VOD as live)",
            url = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
        ),
    )

    val STABLE: List<StreamSpec> = listOf(
        StreamSpec(
            id = "apple-bipbop-adv",
            label = "Apple BipBop (advanced)",
            url = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8",
        ),
        StreamSpec(
            id = "mux-test",
            label = "Mux test stream",
            url = "https://stream.mux.com/v69RSHhFelSm4701snP22dYz2jICy4E4FUyk02rW4gxRM.m3u8",
        ),
        StreamSpec(
            id = "akamai-bbb",
            label = "Akamai Big Buck Bunny",
            url = "https://test-streams.mux.dev/test_001/stream.m3u8",
        ),
    )

    fun pick(tiles: Int, pool: List<StreamSpec> = LIVE): List<StreamSpec> {
        require(tiles >= 1) { "tiles must be >= 1; got $tiles" }
        require(pool.isNotEmpty()) { "fixture pool is empty" }
        return List(tiles) { i ->
            // Cycle the pool, suffix the id so the manager keeps them distinct.
            val src = pool[i % pool.size]
            src.copy(id = "${src.id}-$i")
        }
    }
}
