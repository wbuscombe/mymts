package com.mymts

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.mymts.data.helper.HelperUrl
import com.mymts.data.helper.HelperUrlStore
import com.mymts.kiosk.KioskPrefs
import com.mymts.kiosk.KioskService
import com.mymts.player.StreamSpec
import com.mymts.soak.SoakHarness
import com.mymts.soak.SoakSpec
import com.mymts.ui.components.PlaceholderScreen
import com.mymts.ui.setup.HelperSetupScreen
import com.mymts.ui.theme.MyMtsTheme
import com.mymts.ui.wall.WallScreen

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
        // (slow leaks over hours). Also the kiosk keep-screen-on for the
        // ambient-wall role on the dedicated box.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Kiosk provisioning hook (own-the-box, Model A). The migration
        // runbook flips kiosk mode on for the dedicated MyMTS box with:
        //   adb shell am start -n com.mymts/.MainActivity --ez kiosk true
        // (and `--ez kiosk false` to disable). Kiosk mode is opt-in and
        // OFF by default, so an un-provisioned install — e.g. a lingering
        // MyMTS install on .182 (WyzeGrid's box) — never starts the
        // foreground service or autostarts on boot.
        applyKioskExtraIfPresent()
        // If this box is provisioned as a kiosk, ensure the foreground
        // service is up whenever the wall launches. No-ops when kiosk
        // is off.
        KioskService.startIfEnabled(this)

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

        // Stage 3: the default mode is the wall (video grid for the
        // checkpoint A build; feed + ticker layer in for checkpoint B).
        // The placeholder screen is kept reachable for build-identity
        // smoke checks via `--es mode placeholder`.
        //
        // Helper base URL is resolved at RUNTIME (HelperUrl.resolve) through the
        // precedence chain: a user-set persisted value > the adb `helper` extra >
        // the BuildConfig default (only when actually configured). A stock APK with
        // no configured default + no persisted value resolves to null → the wall
        // branch shows first-run setup. The operator's configured build resolves its
        // URL and goes straight to the wall — no regression.
        val adbExtraHelper = intent?.getStringExtra("helper")
        val helperUrlStore = HelperUrlStore(this)

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
                    "placeholder" -> PlaceholderScreen(
                        version = BuildConfig.VERSION_NAME,
                        buildSha = BuildConfig.BUILD_SHA,
                        defaultMaxTiles = BuildConfig.DEFAULT_MAX_TILES,
                    )
                    else -> {
                        var resolved by rememberSaveable {
                            mutableStateOf(
                                HelperUrl.resolve(
                                    persisted = helperUrlStore.get(),
                                    adbExtra = adbExtraHelper,
                                    buildDefault = BuildConfig.HELPER_BASE_URL,
                                    buildConfigured = BuildConfig.HELPER_URL_CONFIGURED,
                                )
                            )
                        }
                        var editing by rememberSaveable { mutableStateOf(false) }
                        val current = resolved
                        if (current == null || editing) {
                            HelperSetupScreen(
                                initialUrl = current ?: BuildConfig.HELPER_BASE_URL,
                                cancellable = current != null,
                                onConnected = { url ->
                                    helperUrlStore.set(url)
                                    resolved = url
                                    editing = false
                                },
                                onCancel = { editing = false },
                            )
                        } else {
                            WallScreen(
                                helperBaseUrl = current,
                                tileCount = tiles.coerceIn(1, 16),
                                buildVersion = BuildConfig.VERSION_NAME,
                                buildSha = BuildConfig.BUILD_SHA,
                                onOpenHelperUrl = { editing = true },
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Honour a `--ez kiosk true|false` launch extra: persist the kiosk
     * flag and start/stop the foreground service accordingly. This is
     * the provisioning mechanism (a single adb command), kept off the
     * normal-launch path — a launch without the extra never changes
     * kiosk state.
     */
    private fun applyKioskExtraIfPresent() {
        if (intent?.hasExtra("kiosk") != true) return
        val enable = intent.getBooleanExtra("kiosk", false)
        KioskPrefs(this).setEnabled(enable)
        if (enable) KioskService.startIfEnabled(this) else KioskService.stop(this)
    }
}
