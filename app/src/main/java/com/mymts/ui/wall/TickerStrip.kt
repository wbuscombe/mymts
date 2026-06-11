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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymts.data.ticker.SportCard
import com.mymts.data.ticker.TickerEntry
import com.mymts.data.ticker.TickerGame
import com.mymts.data.ticker.TickerSource
import kotlinx.coroutines.delay

/**
 * Top-of-wall ticker — a whole-ticker **paged flip** (2026-06-11).
 *
 * Every mode is a flip page with the SAME motion: the market quotes are one
 * carded page, each sports league is its own page (BottomLine game cards), news
 * is one page — and the strip flips between them all (markets → league blocks →
 * back) with a single hold-then-flip animation. A page wider than the panel
 * scrolls horizontally (a marquee); the flip happens between pages. Consistent
 * bordered-card visual language across markets + sports.
 *
 * Honesty (C3): sample entries keep the SAMPLE pill (on market cards + game
 * cards); aged real data shows the STALE pill; a mode with nothing real falls
 * back to its honest line. Nothing is fabricated.
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
        if (entries.isEmpty()) return@Box // C2: empty source = empty strip.
        val pages = remember(entries) { TickerPaging.pagesFor(entries) }
        if (pages.isEmpty()) return@Box
        PagedTicker(pages, stale = stale, paused = paused, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

/** How long a page holds before flipping to the next (within a multi-page mode). */
private const val PAGE_DWELL_MS = 6500L

/**
 * Flip through [pages] on a calm dwell, wrapping. The flip triggers on the
 * page KEY (advance to the next league, or a mode rotation) so two equal-content
 * pages can't suppress it; the index resets when the page set changes (a poll /
 * mode switch) so a shrunk set can't land out of range.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun PagedTicker(
    pages: List<TickerPaging.Page>,
    stale: Boolean,
    paused: Boolean,
    modifier: Modifier = Modifier,
) {
    var index by remember(pages) { mutableIntStateOf(0) }
    LaunchedEffect(pages) {
        if (pages.size <= 1) return@LaunchedEffect // single page (markets/news): hold until mode change
        while (true) {
            delay(PAGE_DWELL_MS)
            index = TickerPaging.nextPage(index, pages.size)
        }
    }
    val current = pages[index.coerceIn(0, pages.lastIndex)]
    AnimatedContent(
        targetState = current,
        contentKey = { it.key }, // flip on page change, not incidental content equality
        transitionSpec = {
            (slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(260))) togetherWith
                (slideOutVertically(tween(260)) { -it / 2 } + fadeOut(tween(200)))
        },
        label = "ticker-paged-flip",
        modifier = modifier,
    ) { page ->
        PageRow(page, stale = stale, paused = paused)
    }
}

/**
 * One page's content as a horizontally-scrolling row of cards, with the page's
 * marker **PINNED** at the left edge (ESPN BottomLine "curtain").
 *
 * The marker is drawn ON TOP of the scroll row (opaque, full-height) and the
 * row is clipped to this area, so a card scrolling leftward **vanishes cleanly
 * AT the marker's right edge** — it never visibly slides underneath. A leading
 * [Spacer] of the marker's width keeps a *static* (non-overflowing) page's first
 * card to the right of the marker; on overflow that reserve scrolls away and the
 * cards pass behind the curtain. The marquee only animates when the cards
 * overflow the panel width. A leading STALE pill (fixed) flags aged real data.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageRow(page: TickerPaging.Page, stale: Boolean, paused: Boolean) {
    // The page area spans the FULL strip width and is clipped. The marker overlays
    // the TRUE left edge; the STALE flag (when present) overlays the RIGHT edge —
    // both pinned, on top of the scroll, so a stale pill can never shove the curtain
    // inboard (the marker must stay pinned at the panel's left edge regardless).
    Box(modifier = Modifier.fillMaxWidth().fillMaxHeight().clipToBounds()) {
        val scroll = if (paused) Modifier else Modifier.basicMarquee(iterations = Int.MAX_VALUE, velocity = 32.dp)
        Row(
            modifier = Modifier.fillMaxSize().then(scroll),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Leading reserve: holds a static page's cards to the right of the
            // pinned marker; scrolls away (cards pass behind the curtain) on overflow.
            Spacer(modifier = Modifier.width(MARKER_WIDTH))
            if (paused) PausedChip()
            when (page) {
                is TickerPaging.Markets -> page.quotes.forEach { MarketCard(it) }
                is TickerPaging.League -> page.block.games.forEach { SportsEntryCard(it) }
                is TickerPaging.News -> page.items.forEach { NewsCard(it) }
            }
        }
        // The pinned, persistent, opaque curtain — drawn last (on top) so cards
        // disappear at its right edge rather than showing through it.
        PageMarker(label = page.markerLabel, modifier = Modifier.align(Alignment.CenterStart))
        // Staleness flag pinned to the RIGHT edge so it never displaces the marker.
        if (stale) StaleChip(modifier = Modifier.align(Alignment.CenterEnd))
    }
}

// ---------- card visual language (shared bordered cell) ----------

private val CardBg = Color(0xFF15171A)
private val CardBorder = Color(0x2EFFFFFF)

@Composable
private fun cardRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(CardBg)
            .border(1.dp, CardBorder, RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/**
 * A market quote as a bordered card — the same cell language as a game card.
 * The value/symbol lead; the not-live (sample) state is shown HONESTLY but
 * subtly (C3): a sample card is **dimmed** (muted symbol + ghost value, the
 * 10-ft cue that it isn't a live quote) with a small lowercase "sample" tag
 * consolidated into the cell — not the old bulky boxed pill. A LIVE quote (e.g.
 * BTC/ETH from CoinGecko, which the NAS can reach) renders clean + bright, so
 * live-vs-sample reads at a glance.
 */
@Composable
private fun MarketCard(entry: TickerEntry) = cardRow {
    val sample = entry.isSample
    Text(
        entry.symbol,
        color = if (sample) WallColors.LabelMuted else WallColors.LabelPrimary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        entry.display,
        color = if (sample) WallColors.LabelGhost else WallColors.LabelMuted,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
    )
    val (arrow, color) = when (entry.direction) {
        TickerEntry.Direction.UP -> "▲" to WallColors.BadgeLive
        TickerEntry.Direction.DOWN -> "▼" to Color(0xFFEF5350)
        TickerEntry.Direction.FLAT -> "■" to WallColors.LabelMuted
        TickerEntry.Direction.NONE -> null to WallColors.LabelMuted
    }
    if (arrow != null) Text(arrow, color = if (sample) color.copy(alpha = 0.5f) else color, fontSize = 11.sp)
    if (sample) {
        Text(
            text = "sample",
            color = WallColors.LabelGhost,
            fontSize = 7.sp,
            fontStyle = FontStyle.Italic,
            letterSpacing = 0.5.sp,
        )
    }
}

/** A news headline as a bordered card — source accent + headline. */
@Composable
private fun NewsCard(entry: TickerEntry) = cardRow {
    Text(
        text = entry.symbol.uppercase(),
        color = WallColors.BadgeLive,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
    Text(text = entry.display, color = WallColors.LabelMuted, fontSize = 12.sp, maxLines = 1)
    if (entry.isSample) SampleChip()
}

/**
 * Render one sports entry's card — a team [game] draws the BottomLine
 * [GameCard]; an individual-sport [card] (PGA/UFC/…) dispatches by kind to its
 * bespoke composable. An entry with neither draws its flat display fallback.
 */
@Composable
private fun SportsEntryCard(entry: TickerEntry) {
    val card = entry.card
    if (card == null) {
        GameCard(entry)
        return
    }
    when (card.kind) {
        "leaderboard" -> LeaderboardCard(card, entry.isSample)
        "fight" -> FightCard(card, entry.isSample)
        // "match" / "race" land as those sports ship; until then the generic
        // card renders title + lines + status honestly.
        else -> SportCardGeneric(card, entry.isSample)
    }
}

/**
 * UFC fight card — the matchup ("A vs B" / "A def. B") prominent, the weight
 * class as a small muted tag, and a weighted status block (the bout time /
 * round / method). Same bordered-card language; one card per bout in the UFC
 * block.
 */
@Composable
private fun FightCard(card: SportCard, isSample: Boolean) = cardRow {
    Text(
        text = card.title,
        color = WallColors.LabelPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
    card.lines.forEach { wclass ->
        Text(text = wclass, color = WallColors.LabelMuted, fontSize = 10.sp, maxLines = 1)
    }
    if (card.status.isNotBlank()) {
        StatusBlock(SportsTicker.formatStatus(card.state, card.status), SportsTicker.kindOf(card.state))
    }
    if (isSample) SampleChip()
}

/**
 * PGA leaderboard snippet — tournament name + the top players (score-to-par)
 * + a weighted round/status block. The same bordered-card visual language as a
 * game card; the marker/curtain + flip are unchanged (it's one card in the PGA
 * league block).
 */
@Composable
private fun LeaderboardCard(card: SportCard, isSample: Boolean) = cardRow {
    Text(
        text = card.title,
        color = WallColors.LabelPrimary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
    card.lines.forEach { line ->
        Text(
            text = line,
            color = WallColors.LabelMuted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
    if (card.status.isNotBlank()) {
        StatusBlock(SportsTicker.formatStatus(card.state, card.status), SportsTicker.kindOf(card.state))
    }
    if (isSample) SampleChip()
}

/** Generic individual-sport card (title + content lines + status) — the
 *  fallback render until a kind gets its own bespoke composable. */
@Composable
private fun SportCardGeneric(card: SportCard, isSample: Boolean) = cardRow {
    Text(card.title, color = WallColors.LabelPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    card.lines.forEach { line ->
        Text(line, color = WallColors.LabelMuted, fontSize = 12.sp, maxLines = 1)
    }
    if (card.status.isNotBlank()) {
        StatusBlock(SportsTicker.formatStatus(card.state, card.status), SportsTicker.kindOf(card.state))
    }
    if (isSample) SampleChip()
}

/**
 * One game as a discrete CARD (strong divider — each game its own boxed unit)
 * with a weighted, colour-coded status block. A SAMPLE game keeps the SAMPLE
 * pill (C3 — never passes sample off as live).
 */
@Composable
private fun GameCard(entry: TickerEntry) {
    val game = entry.game ?: return
    val kind = SportsTicker.kindOf(game.state)
    cardRow {
        Matchup(game, kind)
        StatusBlock(SportsTicker.formatStatus(game.state, game.status), kind)
        if (entry.isSample) SampleChip()
    }
}

@Composable
private fun Matchup(game: TickerGame, kind: SportsTicker.StatusKind) {
    val teamColor = if (kind == SportsTicker.StatusKind.FINAL) WallColors.LabelMuted else WallColors.LabelPrimary
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
            val a = game.awayScore.toIntOrNull()
            val h = game.homeScore.toIntOrNull()
            val live = kind == SportsTicker.StatusKind.LIVE
            TeamScore(game.away, game.awayScore, teamColor, leading = live && a != null && h != null && a > h)
            TeamScore(game.home, game.homeScore, teamColor, leading = live && a != null && h != null && h > a)
        }
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

/** Weighted, colour-coded status block — LIVE green, FINAL grey, UPCOMING neutral. */
@Composable
private fun StatusBlock(text: String, kind: SportsTicker.StatusKind) {
    val (fg, bg) = when (kind) {
        SportsTicker.StatusKind.LIVE -> WallColors.BadgeLive to Color(0x2200E5A0)
        SportsTicker.StatusKind.FINAL -> WallColors.LabelMuted to Color(0x1AFFFFFF)
        SportsTicker.StatusKind.UPCOMING -> Color(0xFFBFC6CC) to Color(0x14FFFFFF)
    }
    Box(
        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = text, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
    }
}

/** Fixed width of the pinned left-edge marker curtain — sized for the widest
 *  label ("MARKETS"); a uniform width gives a consistent left bug across pages. */
private val MARKER_WIDTH = 66.dp

/**
 * The pinned, persistent left-edge marker — a full-height **opaque** green
 * curtain labeled per page ("NBA"/"MLB"/… for leagues, "MARKETS", "NEWS").
 * Drawn on top of the scrolling row so cards vanish cleanly at its right edge
 * (the BottomLine curtain) instead of visibly sliding underneath. Full height
 * so it covers a card's whole height; fixed width for a consistent left bug.
 */
@Composable
private fun PageMarker(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(MARKER_WIDTH)
            .background(WallColors.BadgeLive),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color(0xFF000000),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            maxLines = 1,
        )
    }
}

/** C3: a sample value never passes as live. */
@Composable
private fun SampleChip() {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF)).padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(text = "SAMPLE", color = WallColors.LabelGhost, fontSize = 8.sp, letterSpacing = 1.sp)
    }
}

/** C3: aged real data is flagged, never shown as live. Pinned to the strip's
 *  right edge (an overlay), with a near-opaque backing so scrolling cards behind
 *  it don't bleed through and hurt legibility. */
@Composable
private fun StaleChip(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(2.dp)).background(Color(0xE6050505)).padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = "STALE", color = WallColors.BadgeStale, fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PausedChip() {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF)).padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = "PAUSED", color = WallColors.BadgeLive, fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold)
    }
}
