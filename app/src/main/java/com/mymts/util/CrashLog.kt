package com.mymts.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash + lifecycle log. Mirrors WyzeGrid's pattern: write to the app's
 * private files dir so a crash leaves a forensic trail that survives a
 * process restart. Also tees to logcat so dev runs are observable in
 * real time.
 *
 * Trust Bar A7 + log-redaction posture (Op Bar C3): the caller is
 * responsible for not passing secrets. The helper has a redaction pass
 * for its logs; the TV app's logs are deliberately narrow because the
 * TV holds no secrets to leak (the only credentials in the system live
 * with the helper).
 */
object CrashLog {
    private const val TAG = "MyMTS"
    private const val FILE_NAME = "mymts-events.log"
    private const val MAX_BYTES = 512 * 1024  // 512KB rolling cap

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(context: Context, line: String) {
        Log.i(TAG, line)
        try {
            val file = File(context.filesDir, FILE_NAME)
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
