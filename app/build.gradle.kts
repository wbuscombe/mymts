import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// versionName tracks the latest RELEASE tag (e.g. "v0.1.0" -> "0.1.0") so released
// builds are self-describing. Falls back to a literal when git is unavailable
// (source tarball, shallow checkout with no tags, etc.).
//
// --match 'v[0-9]*' is load-bearing, not cosmetic: the repo deliberately carries
// local `pre-*` scaffolding tags as rollback anchors, and a bare `git describe
// --tags --abbrev=0` returns whichever tag is nearest — so a build made after a
// rollback tag would stamp the APK with a versionName like
// "pre-housekeeping-sweep". Matching only v<digit> keeps semver tags authoritative
// and ignores every scaffolding tag, present or future. Mirrors the same guard in
// scripts/deploy-helper.sh + scripts/deploy-app.sh.
fun getVersionFromGit(): String = try {
    val out = ByteArrayOutputStream()
    val result = exec {
        commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*")
        workingDir = rootDir
        standardOutput = out
        errorOutput = ByteArrayOutputStream()
        isIgnoreExitValue = true
    }
    val raw = out.toString().trim()
    if (result.exitValue == 0 && raw.isNotEmpty()) raw.removePrefix("v") else "0.0.0"
} catch (_: Exception) {
    "0.0.0"
}

// Short git SHA for build-identity surfaces (/health analog on the TV side).
fun getGitSha(): String = try {
    val out = ByteArrayOutputStream()
    exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        workingDir = rootDir
        standardOutput = out
        isIgnoreExitValue = true
    }
    out.toString().trim().ifEmpty { "dev" }
} catch (_: Exception) {
    "dev"
}

// Default sustained tile budget — sourced from gradle.properties. The number
// itself is derived from docs/findings/01-onn4k-tile-budget.md (Stage 1 gate).
// Keeping it as a Gradle property means re-homing to higher-capacity hardware
// later is a config change, not a code change.
val defaultMaxTiles: Int =
    (project.findProperty("MYMTS_DEFAULT_MAX_TILES") as? String)?.toIntOrNull() ?: 6

// Helper base URL — the TV consumes /api/channels + /api/feed from here.
// Resolved highest-first: a -P/gradle property, then the gitignored
// local.properties (where the operator keeps their real helper host — kept OUT
// of the shared repo), then a localhost default for a collaborator / demo (a
// locally-run helper). No internal topology is hardcoded or committed.
val helperBaseUrlRaw: String? = run {
    val local = Properties()
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) localFile.inputStream().use { local.load(it) }
    (project.findProperty("MYMTS_HELPER_BASE_URL") as? String)
        ?: local.getProperty("MYMTS_HELPER_BASE_URL")
}
// True iff a real helper URL was supplied at build time (the operator's build, a
// `-P` override, or local.properties). When false, HELPER_BASE_URL is only the
// localhost demo fallback — a STOCK APK — so the app shows its runtime first-run
// setup instead of silently resolving to a localhost the TV can't reach.
val helperBaseUrlConfigured: Boolean = helperBaseUrlRaw != null
val helperBaseUrl: String = helperBaseUrlRaw ?: "http://localhost:8091"

// Release signing — Stage 6 update path.
//
// The signing keystore + credentials are SECRETS. They are never committed
// to git (see .gitignore for `*.jks`, `keystore.properties`). The operator
// supplies them via `app/keystore.properties` (gitignored) modeled on
// `app/keystore.properties.example`. Alternatively, the same four values
// can come from environment variables — useful for CI or a fresh checkout
// where the file isn't materialised yet.
//
// If no keystore is configured, the release build falls back to the debug
// signing config so dev builds still work — but the deploy script REFUSES
// to push a debug-signed release to a real device. Production releases
// require the real keystore.
data class SigningConfigSource(
    val storeFile: String?,
    val storePassword: String?,
    val keyAlias: String?,
    val keyPassword: String?,
) {
    val isComplete: Boolean
        get() = !storeFile.isNullOrBlank() && !storePassword.isNullOrBlank() &&
            !keyAlias.isNullOrBlank() && !keyPassword.isNullOrBlank()
}

fun loadSigningSource(): SigningConfigSource {
    // 1. Local file wins (the operator's normal path).
    val propsFile = file("keystore.properties")
    if (propsFile.exists()) {
        val p = Properties().apply { propsFile.inputStream().use { load(it) } }
        return SigningConfigSource(
            storeFile = p.getProperty("MYMTS_RELEASE_STORE_FILE"),
            storePassword = p.getProperty("MYMTS_RELEASE_STORE_PASSWORD"),
            keyAlias = p.getProperty("MYMTS_RELEASE_KEY_ALIAS"),
            keyPassword = p.getProperty("MYMTS_RELEASE_KEY_PASSWORD"),
        )
    }
    // 2. Environment variables (CI / fresh checkout).
    return SigningConfigSource(
        storeFile = System.getenv("MYMTS_RELEASE_STORE_FILE"),
        storePassword = System.getenv("MYMTS_RELEASE_STORE_PASSWORD"),
        keyAlias = System.getenv("MYMTS_RELEASE_KEY_ALIAS"),
        keyPassword = System.getenv("MYMTS_RELEASE_KEY_PASSWORD"),
    )
}

val signingSource = loadSigningSource()

// Network-security-config is GENERATED at build time from the helper host, so
// no operator IP/hostname is committed. Single source of truth: the same
// `helperBaseUrl` (gitignored local.properties / -P / localhost default).
//   - https helper host  -> domain-config pinning @raw/helper_cert for that
//     host, cleartext refused (the operator's real release posture).
//   - http/localhost     -> cleartext allowed only to loopback + the emulator
//     alias (10.0.2.2), no pinning (the demo/local default).
// The generated file lands in a generated res dir wired into sourceSets; the
// static committed copy was removed. See PHASE 1b of the professionalization.
val nscResDir = layout.buildDirectory.dir("generated/res/nsc")
val generateNetworkSecurityConfig by tasks.registering {
    val outDir = nscResDir
    val helperUrl = helperBaseUrl
    inputs.property("helperUrl", helperUrl)
    outputs.dir(outDir)
    doLast {
        val host = runCatching { URI(helperUrl).host }.getOrNull()
        val isHttps = helperUrl.startsWith("https://") && !host.isNullOrBlank()
        val domainBlock = if (isHttps) """
    <!-- Helper host pinned to its own self-signed cert (host from local
         config, not committed). System/user CAs are NOT trust anchors here, so
         even a global-CA-signed MITM cert is refused. -->
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="false">$host</domain>
        <trust-anchors>
            <certificates src="@raw/helper_cert" />
        </trust-anchors>
    </domain-config>""" else """
    <!-- Demo/local default: helper on http://localhost (or the emulator alias
         10.0.2.2). Cleartext allowed ONLY to loopback/emulator; no pinning. -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">localhost</domain>
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">10.0.2.2</domain>
    </domain-config>"""
        val xmlDir = outDir.get().dir("xml").asFile
        xmlDir.mkdirs()
        xmlDir.resolve("network_security_config.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED at build time from the helper host (app/build.gradle.kts ->
     generateNetworkSecurityConfig). Do NOT edit or commit this file. -->
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>$domainBlock
</network-security-config>
"""
        )
    }
}

android {
    namespace = "com.mymts"
    compileSdk = 35

    // Pick up the generated network_security_config.xml (TLS-pin host from
    // local config). The generator runs before resource merge (see below).
    sourceSets["main"].res.srcDir(nscResDir)

    defaultConfig {
        applicationId = "com.mymts"
        // Onn 4K Streaming Box runs Android 14 (API 34); minSdk 23 covers
        // it with headroom and matches Media3 1.5.0's effective floor.
        minSdk = 23
        targetSdk = 35
        // Monotonic, version-derived: MAJOR*10000 + MINOR*100 + PATCH. 0.4.1 -> 401.
        // (Was pinned at the stale `1`.) Bump in lockstep with the released tag so
        // versionCode rises with versionName (which tracks the git tag below).
        versionCode = 600
        versionName = getVersionFromGit()

        buildConfigField("String", "BUILD_SHA", "\"${getGitSha()}\"")
        buildConfigField("int", "DEFAULT_MAX_TILES", "$defaultMaxTiles")
        buildConfigField("String", "HELPER_BASE_URL", "\"$helperBaseUrl\"")
        // Whether HELPER_BASE_URL is a real configured value (vs the localhost
        // demo fallback). The runtime resolver treats the BuildConfig default as
        // valid only when this is true; otherwise a stock APK falls to first-run setup.
        buildConfigField("boolean", "HELPER_URL_CONFIGURED", "$helperBaseUrlConfigured")
        // Stage 6 update path — the deploy script reads this to confirm
        // that a signed release came out of a configured (not fallback)
        // signing config. `true` means "the keystore was wired up at
        // build time" — never trust this from a debug-signed APK.
        buildConfigField("boolean", "IS_RELEASE_SIGNED",
            signingSource.isComplete.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signingSource.isComplete) {
            create("release") {
                storeFile = file(signingSource.storeFile!!)
                storePassword = signingSource.storePassword
                keyAlias = signingSource.keyAlias
                keyPassword = signingSource.keyPassword
                // v1+v2+v3 signing — Android 7+ devices verify v2; v3 enables
                // future key rotation if needed. v1 keeps install compatibility
                // with the rare older signing-verifier code path.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // If the operator's keystore is wired up, sign with it. Otherwise
            // fall back to debug signing so a release build still produces
            // an APK — the deploy script then refuses to push a debug-signed
            // APK to a real device, which keeps an unconfigured environment
            // from accidentally shipping an unsigned build.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Generate the network-security-config before any resource processing so the
// merged resources always include the freshly-derived (uncommitted) file.
tasks.named("preBuild") { dependsOn(generateNetworkSecurityConfig) }

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    implementation(libs.compose.tv.foundation)
    implementation(libs.compose.tv.material)

    // HLS only — see libs.versions.toml note on the RTSP boundary.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)

    // Coil — animated-GIF rendering for the weather-radar WIDGET tiles (the NWS
    // RIDGE loop, helper-proxied). Video tiles use ExoPlayer above; widget tiles
    // use Coil (no audio, no captions). See ui/wall/WallTile.kt (RadarTile).
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Real org.json impl on the JVM test classpath — Android's bundled
    // org.json is a stub that throws "Method ... not mocked" under
    // testDebugUnitTest. Production code links the framework version.
    testImplementation(libs.org.json)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.leakcanary)
}
