package com.mymts

import android.app.Application
import com.mymts.util.CrashLog

/**
 * Application class. Stage 1 keeps this minimal — the WyzeGrid pattern of
 * crash handler + watchdog service + boot receiver lands in Stage 6 with
 * the long-uptime hardening pass. For Stage 1 we just bootstrap logging.
 */
class MyMtsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.log(this, "APP_START | MyMTS launched | sha=${BuildConfig.BUILD_SHA}")
    }
}
