package com.mymts

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.mymts.util.CrashLog
import com.mymts.util.CrashReporter
import com.mymts.util.RadarDecoder

/**
 * Application class.
 *
 * Two long-uptime concerns land here (2026-07, after an overnight NATIVE crash on
 * the box — a use-after-free surfacing as a SIGSEGV in ART's GC — whose evidence was
 * lost to buffer rotation):
 *
 *  1. **One shared Coil [ImageLoader]** ([ImageLoaderFactory]). The radar tile used
 *     to build a fresh ImageLoader inside its composable and never `shutdown()` it —
 *     a Coil anti-pattern (each loader roots itself on the Application and owns a
 *     memory cache + disk cache + OkHttp dispatcher threads), leaking one per tile
 *     rotation. A single app-scoped loader fixes that AND lets us pick the decoder
 *     ONCE: the platform `ImageDecoderDecoder` on API 28+ (the Onn box is API 34),
 *     which retires the deprecated `android.graphics.Movie`/`MovieDrawable` software
 *     GIF path — the most plausible source of the native heap corruption — keeping
 *     the legacy `GifDecoder` only for the minSdk-23 floor. See [RadarDecoder].
 *
 *  2. **A durable crash record** ([CrashReporter]) — the uncaught-exception handler
 *     the old comment promised for "Stage 6", finally shipped, plus a retrievable
 *     event/breadcrumb log so the NEXT incident isn't lost to rotation.
 */
class MyMtsApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this, BuildConfig.BUILD_SHA)
        CrashLog.log(this, "APP_START | MyMTS launched | sha=${BuildConfig.BUILD_SHA}")
    }

    /** The single shared image loader (Coil resolves `context.imageLoader` to this). */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                if (RadarDecoder.preferAnimatedImageDecoder()) {
                    add(ImageDecoderDecoder.Factory())  // platform ImageDecoder (API 28+)
                } else {
                    add(GifDecoder.Factory())           // legacy Movie path, <28 only
                }
            }
            .build()
}
