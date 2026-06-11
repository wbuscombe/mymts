package com.mymts.ui.wall

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymts.data.ticker.TickerEntry
import com.mymts.data.ticker.TickerGame
import com.mymts.data.ticker.TickerSource
import kotlinx.coroutines.delay

/**
 * Top-of-wall ticker.
 *
 * Two presentations on the one strip (sports flips, markets/news scroll —
 * 2026-06-10): when the current entries carry structured games it draws the
 * **BottomLine-style sports flip** — league-grouped game cards held long enough
 * to read, then flipping to the next league (clear boundaries + a weighted,
 * colour-coded status block, the operator's asks). Otherwise (markets/news, or
 * an honest "scores unavailable" line) it scrolls as a marquee, unchanged.
 *
 * Honesty (C3): every sample entry still shows a SAMPLE pill; a sports mode
 * with no games falls back to the honest scrolling line, never a faked card.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TickerStrip(
    source: TickerSource,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    paused: Boolean = false,
) {
    val entries by source.state.collectAsState()
    val stale by source.stale.collectAsState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Color(0xFF050505))
            .then(
                if (focused) Modifier.border(width = 2.dp, color = WallColors.BadgeLive)
                else Modifier,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (entries.isEmpty()) {
            // C2: empty source = empty strip. No error text, no chrome.
            return@Box
        }
        val blocks = remember(entries) { SportsTicker.blocks(entries) }
        if (blocks.isNotEmpty()) {
            // SPORTS: held, flipping league card-sets (the flip ignores pause —
            // it's already legible; pause is a marquee-only affordance).
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (stale) StaleChip()
                SportsFlip(blocks)
            }
            return@Box
        }
        // MARKETS / NEWS / honest fallback: scroll as before.
        val scrollModifier = if (paused) Modifier else {
            Modifier.basicMarquee(iterations = Int.MAX_VALUE, velocity = 32.dp)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(scrollModifier)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (paused) PausedChip()
            if (stale) StaleChip()
            TickerGrouping.group(entries).forEach { run -> TickerRun(run) }
        }
    }
}

/** How long a league's card-set holds before flipping to the next. */
private const val BLOCK_DWELL_MS = 6500L

/**
 * The sports flip: shows one [SportsTicker.LeagueBlock] at a time and advances
 * on a calm dwell, wrapping. A vertical slide + fade reads as a BottomLine
 * "flip". The index resets whenever the block set changes (a new poll) so a
 * shrunk slate can't land out of range.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun SportsFlip(
    blocks: List<SportsTicker.LeagueBlock>,
    modifier: Modifier = Modifier,
) {
    var index by remember(blocks) { mutableIntStateOf(0) }
    LaunchedEffect(blocks) {
        if (blocks.size <= 1) return@LaunchedEffect
        while (true) {
            delay(BLOCK_DWELL_MS)
            index = SportsTicker.nextBlock(index, blocks.size)
        }
    }
    val safe = index.coerceIn(0, blocks.lastIndex)
    AnimatedContent(
        // Key the transition on the POSITION, not the block's content — two
        // leagues with equal data would otherwise suppress the flip and look
        // frozen for a dwell.
        targetState = safe,
        transitionSpec = {
            (slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(260))) togetherWith
                (slideOutVertically(tween(260)) { -it / 2 } + fadeOut(tween(200)))
        },
        label = "sports-flip",
        modifier = modifier,
    ) { idx ->
        LeagueBlockRow(blocks[idx.coerceIn(0, blocks.lastIndex)])
    }
}

@Composable
private fun LeagueBlockRow(block: SportsTicker.LeagueBlock) {
    Row(
        modifier = Modifier.fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LeagueMarker(block.label)
        block.games.forEach { entry -> GameCard(entry) }
    }
}

/**
 * One game as a discrete CARD — a bordered, dark box (the strong divider the
 * operator wanted: each game is its own boxed unit, not a run-together stream)
 * holding the matchup + a weighted, colour-coded status block. A SAMPLE game
 * still wears the SAMPLE pill (C3 — the card never passes sample data off as
 * live, same contract as the scroll path).
 */
@Composable
private fun GameCard(entry: TickerEntry) {
    val game = entry.game ?: return
    val kind = SportsTicker.kindOf(game.state)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xFF15171A))
            .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Matchup(game, kind)
        StatusBlock(SportsTicker.formatStatus(game.state, game.status), kind)
        if (entry.isSample) SampleChip()
    }
}

@Composable
private fun Matchup(game: TickerGame, kind: SportsTicker.StatusKind) {
    // Pre-game has no score → "AWY @ HOM"; live/final → "AWY 2  1 HOM".
    val teamColor = if (kind == SportsTicker.StatusKind.FINAL) WallColors.LabelMuted else WallColors.LabelPrimary
    // Fall back to the matchup line whenever EITHER score is missing — a
    // half-populated live/final payload must never show a dangling blank score
    // or silently hide a real one.
    val noScores = kind == SportsTicker.StatusKind.UPCOMING ||
        game.awayScore.isBlank() || game.homeScore.isBlank()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (noScores) {
            Text(
                text = "${game.away} @ ${game.home}",
                color = teamColor,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            // Brighten only the ACTUAL leader's score, and only while live.
            val a = game.awayScore.toIntOrNull()
            val h = game.homeScore.toIntOrNull()
            val live = kind == SportsTicker.StatusKind.LIVE
            TeamScore(game.away, game.awayScore, teamColor, leading = live && a != null && h != null && a > h)
            TeamScore(game.home, game.homeScore, teamColor, leading = live && a != null && h != null && h > a)
        }
    }
}

@Composable
private fun SampleChip() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0x33FFFFFF))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(text = "SAMPLE", color = WallColors.LabelGhost, fontSize = 8.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun TeamScore(abbr: String, score: String, color: Color, leading: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = abbr, color = color, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        Text(
            text = score,
            color = if (leading) WallColors.LabelPrimary else color,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * The weighted status block — clearly separated from the score and colour-coded
 * so Final vs live vs upcoming reads at a glance (the operator's #2 ask):
 * LIVE = bright green, FINAL = muted grey, UPCOMING = neutral.
 */
@Composable
private fun StatusBlock(text: String, kind: SportsTicker.StatusKind) {
    val (fg, bg) = when (kind) {
        SportsTicker.StatusKind.LIVE -> WallColors.BadgeLive to Color(0x2200E5A0)
        SportsTicker.StatusKind.FINAL -> WallColors.LabelMuted to Color(0x1AFFFFFF)
        SportsTicker.StatusKind.UPCOMING -> Color(0xFFBFC6CC) to Color(0x14FFFFFF)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
    }
}

/** C3: aged real data is flagged, never shown as live. */
@Composable
private fun StaleChip() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0x33FFFFFF))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "STALE",
            color = WallColors.BadgeStale,
            fontSize = 9.sp,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PausedChip() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0x33FFFFFF))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "PAUSED",
            color = WallColors.BadgeLive,
            fontSize = 9.sp,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * One labelled run for the SCROLLING modes (markets / news / honest fallback):
 * the symbol/marker shown once, then its values follow. Unchanged from the
 * marquee era — only sports moved to the flip presenter above.
 */
@Composable
private fun TickerRun(run: TickerGrouping.Run) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LeagueMarker(run.label)
        run.entries.forEach { entry -> TickerValue(entry) }
    }
}

@Composable
private fun LeagueMarker(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(WallColors.BadgeLive)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = Color(0xFF000000),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
    }
}

@Composable
private fun TickerValue(entry: TickerEntry) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = entry.display,
            color = WallColors.LabelMuted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        val (arrow, color) = when (entry.direction) {
            TickerEntry.Direction.UP -> "▲" to WallColors.BadgeLive
            TickerEntry.Direction.DOWN -> "▼" to Color(0xFFEF5350)
            TickerEntry.Direction.FLAT -> "■" to WallColors.LabelMuted
            TickerEntry.Direction.NONE -> null to WallColors.LabelMuted
        }
        if (arrow != null) {
            Text(text = arrow, color = color, fontSize = 11.sp)
        }
        if (entry.isSample) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x33FFFFFF))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    text = "SAMPLE",
                    color = WallColors.LabelGhost,
                    fontSize = 8.sp,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}
