import java.io.ByteArrayOutputStream

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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.leakcanary)
}
