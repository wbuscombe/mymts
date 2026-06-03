package com.mymts.ui.wall

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Render an ISO-8601 timestamp as a 10-foot-readable relative tag:
 * "now", "3m", "47m", "2h", "9h", "3d". Anything older than 30 days
 * collapses to the date only.
 *
 * Returns `null` when the input is null/blank/unparseable — callers
 * should treat that as "don't show a time chip" rather than guessing.
 */
internal object RelativeTime {

    fun render(iso: String?, now: () -> Long = System::currentTimeMillis): String? {
        if (iso.isNullOrBlank()) return null
        val tMs = try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: DateTimeParseException) {
            return null
        }
        val deltaSec = (now() - tMs) / 1000
        return when {
            deltaSec < 0 -> "now"            // clock skew — treat as fresh, not future
            deltaSec < 60 -> "now"
            deltaSec < 3_600 -> "${deltaSec / 60}m"
            deltaSec < 86_400 -> "${deltaSec / 3_600}h"
            deltaSec < 30 * 86_400L -> "${deltaSec / 86_400}d"
            else -> iso.take(10)              // YYYY-MM-DD
        }
    }
}
