package com.mymts

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

        setContent {
            MyMtsTheme {
                when (mode) {
                    "soak" -> SoakHarness(
                        spec = SoakSpec(
                            tiles = tiles.coerceIn(1, 16),
                            resolutionHint = resolution,
                            pool = pool,
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
