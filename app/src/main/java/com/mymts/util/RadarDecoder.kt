package com.mymts.util

import android.os.Build

/**
 * Decoder selection for the weather-radar animated GIF (2026-07 hardening).
 *
 * WHY: the radar tile is the only NEW in-process native decode v0.4.0 added, and it
 * shipped through Coil's legacy `GifDecoder`, which wraps `android.graphics.Movie` —
 * a codec Google DEPRECATED at API 28, documented not-thread-safe, whose Coil
 * `MovieDrawable` still calls an unsynchronised `softwareBitmap.recycle()`. That is
 * the single most plausible source of the native heap use-after-free seen on the box
 * (a SIGSEGV inside ART's GC on freed-poison memory — see the crash post-mortem).
 * The Onn box is API 34, far above the minSdk-23 floor that forced `GifDecoder`, so
 * on 28+ we use the platform `ImageDecoderDecoder` (AnimatedImageDrawable via
 * `ImageDecoder`) and keep `GifDecoder` only for the <28 floor.
 *
 * This holds the pure threshold so it is unit-testable without an Android runtime;
 * the actual decoder-factory wiring lives in [com.mymts.MyMtsApp.newImageLoader].
 */
object RadarDecoder {
    /** True → use the platform ImageDecoder path (API 28+); false → legacy Movie GifDecoder. */
    fun preferAnimatedImageDecoder(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.P  // API 28
}
