package com.mymts.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash + lifecycle log. Mirrors a sibling TV app's pattern: write a forensic trail that
 * survives a process restart AND logcat rotation. Also tees to logcat so dev runs
 * are observable in real time.
 *
 * RETRIEVABILITY (2026-07): the record is written to the app's EXTERNAL files dir
 * (`getExternalFilesDir`) — `/sdcard/Android/data/com.mymts/files/` — which is
 * `adb pull`-able by the shell user WITHOUT root or `run-as`. That matters because
 * the shipped APK is a non-debuggable RELEASE: an INTERNAL-only log (the prior
 * behaviour) is unreachable on the box, which is exactly why an overnight crash's
 * evidence was unrecoverable. Falls back to the internal files dir if external
 * storage is unavailable, so the log always writes somewhere.
 *
 * Trust Bar A7 + log-redaction posture (Op Bar C3): the caller is responsible for
 * not passing secrets. The TV app's logs are deliberately narrow because the TV
 * holds no secrets to leak (the only credentials in the system live with the
 * helper); the external dir is app-scoped, and the log carries only lifecycle +
 * crash breadcrumbs, so world-adjacent placement on the TV is acceptable.
 */
object CrashLog {
    private const val TAG = "MyMTS"
    private const val FILE_NAME = "mymts-events.log"
    private const val MAX_BYTES = 512 * 1024  // 512KB rolling cap

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** The retrievable (external, adb-pullable) log file, or the internal one if
     *  external storage is unavailable. Exposed for the retrieval doc / callers. */
    fun logFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    fun log(context: Context, line: String) {
        Log.i(TAG, line)
        try {
            val file = logFile(context)
            if (file.length() > MAX_BYTES) {
                // Cheap rotation: truncate to zero. We're not building a forensic
                // archive — the post-mortem window is "what happened recently."
                file.writeText("")
            }
            val ts = fmt.format(Date())
            file.appendText("$ts | $line\n")
        } catch (e: Exception) {
            // Logging must never crash the app. If the disk is full or the
            // file is unwritable, fall back to logcat-only.
            Log.w(TAG, "log_write_failed | ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
