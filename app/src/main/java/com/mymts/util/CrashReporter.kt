package com.mymts.util

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The long-uptime crash-record hardening (the "Stage 6" handler MyMtsApp's comment
 * long promised — shipped 2026-07 after an overnight native crash on the box was
 * LOST because there was no durable record: dropbox is disabled on this Google TV
 * build and logcat rotated past the event within ~2 days).
 *
 * Installs a global [Thread.UncaughtExceptionHandler] that appends a full crash
 * record to the durable event log ([CrashLog], which writes to the ADB-pullable
 * external files dir) BEFORE chaining to the platform handler — so a Java/Kotlin
 * uncaught exception leaves a forensic trail that survives the process death and
 * buffer rotation.
 *
 * Honest limit: a NATIVE crash (SIGSEGV/SIGABRT — the failure actually seen on the
 * box) bypasses every JVM handler, so this cannot capture the native stack. What it
 * DOES give for a native crash is the BREADCRUMB trail: [CrashLog] lifecycle +
 * per-radar-refresh markers are already on disk, and [markSessionStart] records each
 * (re)launch — so after a native death the durable log shows the restart timeline
 * and exactly what the app was doing last. Getting the native stack itself is a
 * separate escalation (a GWP-ASan/HWASan debug soak — see tools/soak).
 */
object CrashReporter {
    private const val MAX_STACK_CHARS = 8 * 1024
    private const val MAX_MSG_CHARS = 512

    @Volatile private var installed = false

    /** Idempotent. Records the session start and installs the uncaught handler. */
    fun install(context: Context, sha: String) {
        markSessionStart(context, sha)
        if (installed) return
        installed = true
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Never let the record-write throw and mask the real crash.
            try {
                CrashLog.log(context, formatCrash(thread.name, throwable, sha))
            } catch (_: Throwable) {
                // best-effort only
            }
            // Chain to the platform handler so the system still logs + kills the
            // process exactly as before (we ADD a record, we don't swallow the crash).
            prior?.uncaughtException(thread, throwable)
        }
    }

    /** Record a (re)launch so the durable log carries the restart timeline. */
    fun markSessionStart(context: Context, sha: String) {
        CrashLog.log(context, "SESSION_START | sha=$sha | a fresh (re)launch — if the prior line isn't a clean stop, the previous instance died")
    }

    /**
     * Build the crash record text. PURE (no Android deps) so it is unit-tested. Caps
     * the stack so a pathological trace can't blow the rolling log's budget, and
     * tolerates a null message.
     */
    fun formatCrash(threadName: String, throwable: Throwable, sha: String): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        var stack = sw.toString()
        if (stack.length > MAX_STACK_CHARS) {
            stack = stack.substring(0, MAX_STACK_CHARS) + "\n…(stack truncated)"
        }
        val type = throwable.javaClass.name
        val rawMsg = throwable.message ?: "(no message)"
        val msg = if (rawMsg.length > MAX_MSG_CHARS) rawMsg.substring(0, MAX_MSG_CHARS) + "…" else rawMsg
        // Single logical record; embedded newlines are fine (the log is line-appended
        // but a crash is a rare, deliberately-multiline entry).
        return "FATAL_UNCAUGHT | sha=$sha | thread=$threadName | $type: $msg\n$stack"
    }
}
