package com.mymts.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure crash-record formatter (the durable uncaught-exception trail added
 * 2026-07 after an overnight crash's evidence was lost to log rotation). The Android
 * wiring (setDefaultUncaughtExceptionHandler / external-dir write) is exercised on
 * device; these pin the record shape + the safety caps.
 */
class CrashReporterTest {

    @Test fun `record carries type, message, thread, sha, and a stack frame`() {
        val rec = CrashReporter.formatCrash("main", IllegalStateException("boom"), "abc123")
        assertTrue(rec.startsWith("FATAL_UNCAUGHT"))
        assertTrue(rec.contains("sha=abc123"))
        assertTrue(rec.contains("thread=main"))
        assertTrue(rec.contains("java.lang.IllegalStateException: boom"))
        assertTrue("has at least one stack frame", rec.contains("\tat "))
    }

    @Test fun `tolerates a null exception message`() {
        val rec = CrashReporter.formatCrash("worker", NullPointerException(), "sha")
        assertTrue(rec.contains("(no message)"))
        assertFalse(rec.contains("null: null"))
    }

    @Test fun `caps a pathological stack so it cannot blow the rolling log budget`() {
        val rec = CrashReporter.formatCrash("t", RuntimeException("x".repeat(30_000)), "s")
        assertTrue("truncation marker present", rec.contains("(stack truncated)"))
        assertTrue("record is bounded well under the huge input", rec.length < 12_000)
    }
}
