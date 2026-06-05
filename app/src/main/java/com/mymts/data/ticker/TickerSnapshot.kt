package com.mymts.data.ticker

/**
 * A parsed `/api/ticker/{markets,sports}` response.
 *
 * The helper owns the real-vs-sample decision (`isSample` per entry)
 * and surfaces stale real data at the envelope level via [stale] — the
 * TV renders exactly what it's told and never upgrades a sample entry
 * to "live" or shows frozen real numbers as current.
 */
data class TickerSnapshot(
    /** "markets" or "sports" — which mode this snapshot belongs to. */
    val mode: String,
    /** ISO-8601 of the last real fetch, or null before any (pure sample). */
    val asOfIso: String?,
    /** True when real data we once had has aged past the helper's threshold. */
    val stale: Boolean,
    val entries: List<TickerEntry>,
)
