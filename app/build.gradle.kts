import java.io.ByteArrayOutputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// versionName tracks the latest git tag (e.g. "v0.1.0" -> "0.1.0") so released
// builds are self-describing. Falls back to a literal when git is unavailable
// (source tarball, shallow checkout with no tags, etc.).
fun getVersionFromGit(): String = try {
    val out = ByteArrayOutputStream()
    val result = exec {
        commandLine("git", "describe", "--tags", "--abbrev=0")
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
// Sourced from gradle.properties so retargeting the helper host doesn't
// require a source edit.
val helperBaseUrl: String =
    (project.findProperty("MYMTS_HELPER_BASE_URL") as? String) ?: "http://<LAN_IP>:8091"

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

android {
    namespace = "com.mymts"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mymts"
        // Onn 4K Streaming Box runs Android 14 (API 34); minSdk 23 covers
        // it with headroom and matches Media3 1.5.0's effective floor.
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = getVersionFromGit()

        buildConfigField("String", "BUILD_SHA", "\"${getGitSha()}\"")
        buildConfigField("int", "DEFAULT_MAX_TILES", "$defaultMaxTiles")
        buildConfigField("String", "HELPER_BASE_URL", "\"$helperBaseUrl\"")
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
