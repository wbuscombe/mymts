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

    val LIVE: List<StreamSpec> = listOf(
        StreamSpec(
            id = "nasa-public",
            label = "NASA TV Public",
            url = "https://ntv1.akamaized.net/hls/live/2014075/NASA-NTV1-HLS/master.m3u8",
        ),
        StreamSpec(
            id = "nasa-media",
            label = "NASA TV Media",
            url = "https://ntv2.akamaized.net/hls/live/2037455/NASA-NTV2-HLS/master.m3u8",
        ),
        StreamSpec(
            id = "redbull-tv",
            label = "Red Bull TV",
            url = "https://rbmn-live.akamaized.net/hls/live/590964/BoRB-AT/master.m3u8",
        ),
        StreamSpec(
            id = "ndr-info",
            label = "NDR Info",
            url = "https://ndr_fs-lh.akamaihd.net/i/ndrfs_nds@430212/master.m3u8",
        ),
        StreamSpec(
            id = "tagesschau",
            label = "Tagesschau 24",
            url = "https://tagesschau-lh.akamaihd.net/i/tagesschau_1@119231/master.m3u8",
        ),
        StreamSpec(
            id = "abc-news-au",
            label = "ABC News Australia",
            url = "https://abc-iview-mediapackagestreams-2.akamaized.net/out/v1/6e1cc6d25ec0480b9520177cc8127a06/index.m3u8",
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
