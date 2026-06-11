package com.mymts.ui.wall

import com.mymts.data.ticker.TickerEntry
import com.mymts.data.ticker.TickerGame

/**
 * Pure logic for the BottomLine-style sports flip (2026-06-10): group games
 * into league blocks, classify a game's status for colour/weight, and format
 * the ESPN-convention status text. No Compose / Android — unit-tested in
 * `SportsTickerTest`, so the grouping + formatting are verified without booting
 * the UI. Honest (C3): nothing here invents a score or status.
 */
object SportsTicker {

    /**
     * One league's games, drawn as a held card-set that flips to the next.
     * Carries the owning [TickerEntry]s (each with a non-null [TickerEntry.game])
     * — NOT bare games — so the card can honour [TickerEntry.isSample] (C3: a
     * sample game must still show the SAMPLE pill, same as the scroll path).
     */
    data class LeagueBlock(val label: String, val games: List<TickerEntry>)

    /** Status lifecycle bucket → drives colour + weight in the status block. */
    enum class StatusKind { LIVE, FINAL, UPCOMING }

    /**
     * Group adjacent sports entries (those carrying a [TickerEntry.game]) into
     * league blocks, preserving the helper's emit order (live-first within a
     * league). Entries without a game — markets/news, or an honest "scores
     * unavailable" line — are skipped, so a mixed or degraded list yields only
     * real game blocks (empty ⇒ the caller shows its honest fallback). Grouping
     * keys on the GAME's league so the on-screen label matches the filter key.
     */
    fun blocks(entries: List<TickerEntry>): List<LeagueBlock> {
        val out = ArrayList<LeagueBlock>()
        for (e in entries) {
            // A sports entry carries EITHER a team [game] or an individual-sport
            // [card]; both name their league. Markets/news (neither) are skipped.
            val league = e.game?.league ?: e.card?.league ?: continue
            val last = out.lastOrNull()
            if (last != null && last.label == league) {
                out[out.lastIndex] = last.copy(games = last.games + e)
            } else {
                out.add(LeagueBlock(league, listOf(e)))
            }
        }
        return out
    }

    fun kindOf(state: String): StatusKind = when (state.lowercase()) {
        "in" -> StatusKind.LIVE
        "post" -> StatusKind.FINAL
        else -> StatusKind.UPCOMING // "pre" + any unknown token
    }

    /**
     * ESPN-convention status text for the weighted block:
     *   post → "FINAL"
     *   in   → the live detail, uppercased, " - " normalised to a space
     *          ("5:42 - 1st" → "5:42 1ST", "Top 2nd" → "TOP 2ND")
     *   pre  → the start time as given ("7:30 PM ET")
     * Never invents — an empty live/upcoming status falls back to a token.
     */
    fun formatStatus(state: String, status: String): String {
        val s = status.trim()
        return when (kindOf(state)) {
            StatusKind.FINAL -> "FINAL"
            StatusKind.LIVE -> if (s.isEmpty()) "LIVE" else s.replace(" - ", " ").uppercase()
            StatusKind.UPCOMING -> s.ifEmpty { "—" }
        }
    }

    /** Advance the flip index, wrapping. count <= 0 → 0 (nothing to show). */
    fun nextBlock(index: Int, count: Int): Int = if (count <= 0) 0 else (index + 1) % count
}
