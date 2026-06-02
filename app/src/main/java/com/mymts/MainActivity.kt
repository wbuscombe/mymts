package com.mymts

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mymts.player.StreamSpec
import com.mymts.soak.SoakHarness
import com.mymts.soak.SoakSpec
import com.mymts.ui.components.PlaceholderScreen
import com.mymts.ui.theme.MyMtsTheme

/**
 * Single activity. Stage 1 has two modes:
 *
 *   1. Default: a placeholder screen confirming the build runs on the box.
 *   2. Soak harness: multi-tile playback with telemetry, gated by an intent
 *      extra so a normal launch never accidentally triggers a soak.
 *
 * Switching modes by intent extra (rather than build flavor or BuildConfig)
 * keeps the soak harness reachable from a single debug APK without
 * forking the build.
 *
 * Activate soak:
 *   adb shell am start -n com.mymts/.MainActivity --es mode soak --ei tiles 4
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Stage 1 / C4 (long uptime): keep the screen on during dev so we
        // can observe the soak through to the failure mode that matters
        // (slow leaks over hours).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val mode = intent?.getStringExtra("mode")?.lowercase()
        val tiles = intent?.getIntExtra("tiles", BuildConfig.DEFAULT_MAX_TILES)
            ?: BuildConfig.DEFAULT_MAX_TILES
        val resolution = intent?.getStringExtra("resolution") ?: "auto"
        val pool = intent?.getStringExtra("pool")?.lowercase() ?: "live"

        // Ad-hoc fixture override(s). Two shapes:
        //
        //   1. Single fixture (Stage 1 validate-fixture.sh path):
        //        --es url U --es label L [--es id ID]    → tiles=1, one StreamSpec
        //
        //   2. Multi-fixture (Stage 2 Part C capacity probe):
        //        --es urls "U1,U2,U3" --es labels "L1,L2,L3" [--es ids "A,B,C"]
        //        → tiles=N, N StreamSpecs in order; missing ids are auto-named
        //
        // When the multi-fixture form is used, the harness's `tiles` param is
        // implicitly the length of the list.
        val adhocSingleUrl = intent?.getStringExtra("url")
        val adhocSingleLabel = intent?.getStringExtra("label")
        val adhocSingleId = intent?.getStringExtra("id")
        val adhocMultiUrls = intent?.getStringExtra("urls")?.takeIf { it.isNotEmpty() }
        val adhocMultiLabels = intent?.getStringExtra("labels")?.takeIf { it.isNotEmpty() }
        val adhocMultiIds = intent?.getStringExtra("ids")

        val adhocFixtures: List<StreamSpec>? = when {
            adhocMultiUrls != null -> {
                val urls = adhocMultiUrls.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val labels = adhocMultiLabels?.split(',')?.map { it.trim() } ?: urls.indices.map { "Tile $it" }
                val ids = adhocMultiIds?.split(',')?.map { it.trim() } ?: urls.indices.map { "adhoc-$it" }
                urls.mapIndexed { i, u ->
                    StreamSpec(
                        id = ids.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: "adhoc-$i",
                        label = labels.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: "Tile $i",
                        url = u,
                    )
                }
            }
            adhocSingleUrl != null && adhocSingleLabel != null -> listOf(
                StreamSpec(id = adhocSingleId ?: "adhoc", label = adhocSingleLabel, url = adhocSingleUrl)
            )
            else -> null
        }

        setContent {
            MyMtsTheme {
                when (mode) {
                    "soak" -> SoakHarness(
                        spec = SoakSpec(
                            tiles = adhocFixtures?.size ?: tiles.coerceIn(1, 16),
                            resolutionHint = resolution,
                            pool = if (adhocFixtures != null) "adhoc" else pool,
                            adhocFixtures = adhocFixtures,
                        )
                    )
                    else -> PlaceholderScreen(
                        version = BuildConfig.VERSION_NAME,
                        buildSha = BuildConfig.BUILD_SHA,
                        defaultMaxTiles = BuildConfig.DEFAULT_MAX_TILES,
                    )
                }
            }
        }
    }
}
