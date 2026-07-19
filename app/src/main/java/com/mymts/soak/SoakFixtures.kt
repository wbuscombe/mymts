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

    // LIVE-pool composition for the gate-clearing soak: 5 streams that
    // sustained playback in solo validation on the dev box + 1 deliberate reconnect
    // exerciser. Inverts the prior pool's mostly-flaky ratio — the first
    // gate-clearing attempt's PSS data measured "1 active tile + 5 stale"
    // because nearly every fixture was flaky.
    //
    // Validation method (scripts/validate-fixture.sh) — see
    // docs/findings/validation/. Each fixture below has:
    //   - TILE_READY > 0
    //   - state=LIVE, playing=true at 75s and 5 min in solo runs
    //   - 0 errors over the validation window
    //
    // International broadcasters tried but failing on the dev box with
    // ERROR_CODE_IO_BAD_HTTP_STATUS (likely geo-restriction or auth):
    // NASA TV Public/Media (4xx mid-validation), France 24, NHK, Al Jazeera,
    // Sky News, TV5MONDE, moctobpltc 'eight', ABC Australia. Do not silently
    // re-add without revalidation on the same network.
    val LIVE: List<StreamSpec> = listOf(
        // ---- 5 stable ----
        // Real live broadcasts (validated solo on the dev box, sustained 5 min LIVE).
        StreamSpec(
            id = "redbull-tv",
            label = "Red Bull TV (live)",
            url = "https://rbmn-live.akamaized.net/hls/live/590964/BoRB-AT/master.m3u8",
        ),
        StreamSpec(
            id = "dw-news-en",
            label = "DW News English (live)",
            url = "https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8",
        ),
        // Multi-variant VOD HLS — advanced multi-variant test asset; the
        // multi-variant path exercises variant switching without ending. In
        // the 87-min partial-soak it kept producing dropped-frame events
        // throughout, meaning the decoder stayed engaged.
        StreamSpec(
            id = "apple-bipbop-adv",
            label = "Apple BipBop advanced (multi-variant VOD)",
            url = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8",
        ),
        // Single-variant VOD HLS (Big Buck Bunny on test-streams.mux.dev) —
        // validated solo at real-time position advance (240s over 240s wall).
        StreamSpec(
            id = "akamai-bbb",
            label = "Big Buck Bunny (VOD as live)",
            url = "https://test-streams.mux.dev/test_001/stream.m3u8",
        ),
        // Multi-variant VOD; in the prior 87-min soak its drop counter
        // climbed from 0 to 2953, confirming continuous decode activity.
        StreamSpec(
            id = "mux-x36xhzz",
            label = "Mux x36xhzz (multi-variant VOD)",
            url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        ),
        // ---- 1 reconnect exerciser ----
        // Long-form VOD treated as live — ~12 min runtime → BEHIND_LIVE_WINDOW
        // reconnect once per loop. Hammers the reconnect lifecycle without
        // dominating the run.
        StreamSpec(
            id = "unified-tears",
            label = "Unified Streaming Tears of Steel (reconnect exerciser)",
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
