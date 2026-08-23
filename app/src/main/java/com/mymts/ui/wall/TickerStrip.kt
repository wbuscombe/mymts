package com.mymts.ui.wall

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymts.data.settings.TickerMotion
import com.mymts.data.ticker.SportCard
import com.mymts.data.ticker.TickerEntry
import com.mymts.data.ticker.TickerGame
import com.mymts.data.ticker.TickerSource
import kotlinx.coroutines.delay

/**
 * Top-of-wall ticker — a whole-ticker **paged flip** (2026-06-11), or a
 * continuous horizontal **crawl** when the operator picks that motion
 * (cross-platform parity, 2026-06-13).
 *
 * FLIP (the default): every mode is a flip page with the SAME motion — the
 * market quotes are one carded page, each sports league is its own page
 * (BottomLine game cards), news is one page — and the strip flips between them
 * all (markets → league blocks → back) with a single hold-then-flip animation.
 * A page wider than the panel scrolls horizontally; the flip happens between
 * pages.
 *
 * CRAWL ([TickerMotion.Crawl], the web wall's motion offered here too): all the
 * pages' cards are laid out in ONE row, each behind its inline marker, and the
 * whole strip scrolls left continuously (a seamless marquee — a duplicated copy
 * makes the one-copy-width loop seamless, the same trick the web client uses).
 *
 * Both motions share the bordered-card visual language. Honesty (C3): sample
 * entries keep the SAMPLE pill, aged real data shows the STALE pill, a mode with
 * nothing real falls back to its honest line. Nothing is fabricated.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TickerStrip(
    source: TickerSource,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    paused: Boolean = false,
    scrollPct: Int = 100,
    flipPct: Int = 100,
    motion: TickerMotion = TickerMotion.Flip,
    boxScale: Float = 1f,
    textScale: Float = 1f,
) {
    val entries by source.state.collectAsState()
    val stale by source.stale.collectAsState()
    CompositionLocalProvider(
        LocalTickerScale provides TickerScale(box = boxScale, text = textScale),
    ) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            // heightIn(min=), NOT height(): a FLOOR, not a cap. Compose's height()
            // is a hard constraint, so text larger than the bar would be clipped —
            // and because the content is centre-aligned it clips ascenders AND
            // descenders equally, which reads as a broken font rather than a sizing
            // mistake. This is the same hazard the web wall hit when --tu/--ttu were
            // split (ARCHITECTURE §44); the fix is the same shape. Ticker height is
            // therefore a minimum: oversized text grows the bar instead of losing its
            // ascenders.
            .heightIn(min = tu(40))
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
        when (motion) {
            TickerMotion.Crawl -> CrawlTicker(
                pages, stale = stale, paused = paused, scrollPct = scrollPct,
                modifier = Modifier.padding(horizontal = tu(12)),
            )
            TickerMotion.Flip -> PagedTicker(
                pages, stale = stale, paused = paused,
                scrollPct = scrollPct, flipPct = flipPct,
                modifier = Modifier.padding(horizontal = tu(12)),
            )
        }
    }
    }
}

/** Base page dwell at 100% flip speed — calmer than the original 6500ms (the
 *  operator wanted the flip slowed). The "Flip speed" slider scales it: a higher
 *  percent shortens the dwell (faster), lower lengthens it (slower). */
private const val BASE_DWELL_MS = 9000L

/** Base horizontal reveal velocity at 100% scroll speed for the FLIP's per-page
 *  reveal; the "Scroll speed" slider scales it linearly. */
/**
 * The ticker's two INDEPENDENT design units — the native mirror of the web wall's
 * `--tu` (box) / `--ttu` (text) split (PR-026, ARCHITECTURE §47).
 *
 * [box] scales bar height, gaps, paddings and corner radii; [text] scales font sizes
 * and their letter spacing. Independent, so a tall bar with small text (or the reverse)
 * is expressible; identical values reproduce the old single-unit proportional feel.
 *
 * Carried in a CompositionLocal rather than threaded through every private composable:
 * the strip is ~10 nested composables deep and every one of them sizes something.
 */
@Immutable
data class TickerScale(val box: Float = 1f, val text: Float = 1f)

val LocalTickerScale = compositionLocalOf { TickerScale() }

/** A ticker BOX dimension: `N.tu` is the analogue of CSS `calc(N * var(--tu))`. */
@Composable
private fun tu(v: Number): Dp = (v.toFloat() * LocalTickerScale.current.box).dp

/** A ticker TEXT dimension: the analogue of CSS `calc(N * var(--ttu))`. */
@Composable
private fun ttu(v: Number): TextUnit = (v.toFloat() * LocalTickerScale.current.text).sp

private val BASE_SCROLL_VELOCITY = 32.dp

/** Base velocity (dp/sec at 100% scroll speed) for the CRAWL marquee — separate
 *  from (and calmer than) the flip's reveal so the crawl reads comfortably at
 *  10 ft. With the 10% slider floor this gives a genuinely slow slow-end; the
 *  slider (`tickerScrollPct`) multiplies it. Tunable; `internal` for the test. */
internal const val CRAWL_BASE_DP_PER_SEC = 24f

/** A page rests at its START (left edge fully shown) for this long before the
 *  single-pass reveal begins, so the operator catches the leftmost content. */
private const val START_HOLD_MS = 800L

/** A page HOLDS at its fully-revealed end (right edge shown) for this long
 *  before the next flip, so the operator can READ the rightmost content — the
 *  fix for the "flips almost instantly once the right edge is revealed" bug. */
private const val END_HOLD_MS = 750L

/**
 * Pure, testable reveal-duration policy for one page's single-pass horizontal
 * reveal.
 *
 * [overflowPx] is the row's horizontal overflow (`scrollState.maxValue`, the px
 * that must scroll past the panel; 0 means the page already fits). The NATURAL
 * duration is `overflowPx / velocityPxPerSec` (the configured scroll velocity,
 * in dp/sec converted to px/sec via [density]). To GUARANTEE the end-hold is
 * visible before [PagedTicker] flips this page, the reveal is CAPPED so
 * `startHoldMs + reveal + endHoldMs <= dwellMs` — very wide content simply
 * reveals a little faster rather than eating the end-hold.
 *
 * Returns 0 when there's nothing to reveal ([overflowPx] <= 0): the page just
 * sits and holds.
 */
internal fun revealDurationMs(
    overflowPx: Int,
    velocityDpPerSec: Float,
    density: Float,
    dwellMs: Long,
    startHoldMs: Long,
    endHoldMs: Long,
): Int {
    if (overflowPx <= 0) return 0
    val velocityPxPerSec = (velocityDpPerSec * density).coerceAtLeast(1f)
    val natural = (overflowPx / velocityPxPerSec * 1000f).toLong()
    // The reveal may use at most whatever dwell remains after both holds; never
    // negative (clamped to 0 so an absurdly short dwell still flips cleanly).
    val cap = (dwellMs - startHoldMs - endHoldMs).coerceAtLeast(0L)
    return natural.coerceAtMost(cap).toInt()
}

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
    scrollPct: Int,
    flipPct: Int,
    modifier: Modifier = Modifier,
) {
    // Higher flip % = faster = shorter dwell (clamped so a busy poll can't make
    // the dwell zero). Re-keys the loop on flipPct so a live change applies.
    val dwellMs = (BASE_DWELL_MS * 100 / flipPct.coerceAtLeast(1)).coerceAtLeast(2000L)
    var index by remember(pages) { mutableIntStateOf(0) }
    LaunchedEffect(pages, dwellMs) {
        if (pages.size <= 1) return@LaunchedEffect // single page (markets/news): hold until mode change
        while (true) {
            delay(dwellMs)
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
        PageRow(page, stale = stale, paused = paused, scrollPct = scrollPct, dwellMs = dwellMs)
    }
}

/** End-of-crawl dwell ("slip time") — how long the strip stays STILL at the loop
 *  point after a completed pass before the next pass begins. A calm beat that lets
 *  the eye reset before the content streams again. Named constant, tunable.
 *  DOUBLED from the original 1500ms so the rest reads clearly at 10 ft. */
internal const val CRAWL_DWELL_MS = 3000L

/** Pure: crawl velocity in px/sec from the base dp/sec, the operator's speed
 *  percent, and the display density. Frame-rate-independent. Unit-tested. */
internal fun crawlPxPerSec(baseDpPerSec: Float, scrollPct: Int, density: Float): Float =
    (baseDpPerSec * density * (scrollPct.coerceAtLeast(1) / 100f)).coerceAtLeast(1f)

/** Pure: the tween DURATION (ms) to crawl [distancePx] at [pxPerSec]. Because the
 *  crawl is framework-timed (a real-clock `animateTo`), feeding it a distance/speed
 *  duration makes the velocity CONSTANT regardless of frame load — under load the
 *  framework renders fewer intermediate frames (less smooth) instead of varying
 *  the speed. This REPLACES the old clamped-per-frame-delta accumulator, which lost
 *  motion (looked slow/stalled) whenever the box's variable CPU load stretched a
 *  frame past the clamp. Float math (no integer-px stutter); monotonic in distance,
 *  inverse in speed. Returns 0 when there's nothing to crawl. Unit-tested. */
internal fun crawlDurationMs(distancePx: Float, pxPerSec: Float): Int {
    if (distancePx <= 0f) return 0
    val v = pxPerSec.coerceAtLeast(1f)
    return (distancePx / v * 1000f).toInt()
}

/** The gap BETWEEN the two marquee copies (and between cards) — the same spacing
 *  used in the crawl Rows. It is part of the seamless-loop repeat period: the row
 *  lays out `[copy1][CRAWL_GAP][copy2]`, so copy 2 begins one gap PAST copy 1's
 *  width. The single source of truth for both the layout spacing and the period. */
/** Inter-card gap — a BOX dimension, so it tracks the ticker's box scale. */
private val CRAWL_GAP: Dp @Composable get() = tu(8)

/** Pure: the seamless-loop REPEAT PERIOD (px) = one copy's width PLUS the inter-copy
 *  gap. Wrapping at the copy width ALONE (ignoring the gap) lands copy 2 one gap-width
 *  off copy 1's origin, so the snap-back pops by a gap every cycle (the seam bug).
 *  Wrapping at copyWidth + gap lands copy 2 exactly on copy 1's origin → truly
 *  seamless. Floored so a degenerate measurement never yields a zero period.
 *  Unit-tested. */
internal fun crawlPeriodPx(copyWidthPx: Int, gapPx: Float): Float =
    copyWidthPx.coerceAtLeast(1).toFloat() + gapPx.coerceAtLeast(0f)

/**
 * CRAWL motion — all pages' cards laid out in ONE row (each behind its inline
 * marker), scrolling left continuously. Two identical copies are laid out so a
 * scroll of exactly ONE copy-width loops seamlessly (copy 2 lands where copy 1
 * began — the web client's 0→-50% trick). The second copy is added only once
 * we've measured that one copy overflows the strip; if it fits, the row holds
 * static (nothing to crawl). Honest pills ride the cards unchanged.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CrawlTicker(
    pages: List<TickerPaging.Page>,
    stale: Boolean,
    paused: Boolean,
    scrollPct: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    // NOT keyed on `pages`: a ticker CONTENT refresh (a markets/scores poll → a
    // new pages list) must NOT reset the crawl to the start. The measured copy
    // width updates IN PLACE via onGloballyPositioned, and the offset persists
    // across data refreshes (the new content integrates at the next cycle
    // boundary). This was the visible "jumps back on every refresh" bug.
    var copyWidthPx by remember { mutableIntStateOf(0) }
    val offset = remember { Animatable(0f) }
    // HEIGHT MUST WRAP, NOT FILL — see the note on PagedTicker's page area. Only
    // `maxWidth` is read below (the crawl viewport), so dropping the height fill is
    // inert for the crawl maths and stops the ticker consuming the whole wall Column.
    BoxWithConstraints(modifier = modifier.fillMaxWidth().wrapContentHeight().clipToBounds()) {
        val viewportPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val overflow = copyWidthPx > 0 && copyWidthPx >= viewportPx
        Row(
            // CHEAP motion: translate the already-composed strip on the COMPOSITOR
            // (graphicsLayer translationX) — NOT a horizontalScroll + animateScrollTo,
            // which re-lays-out the wide two-copy row every frame and starved video
            // decode on the modest S905Y4. `unbounded` lets the two-copy row exceed
            // the viewport width; the surrounding Box clips the overflow. Reading the
            // Animatable's float value INSIDE the graphicsLayer block updates the
            // layer in the draw phase (sub-pixel, no recomposition, no rounding).
            modifier = Modifier
                // wrap, not fill: `verticalAlignment = CenterVertically` below already
                // centres the cards, and the bar's own `heightIn(min = tu(40))` floor
                // still sets the minimum bar height.
                .wrapContentHeight()
                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                .graphicsLayer { translationX = -offset.value },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CRAWL_GAP),
        ) {
            if (paused) PausedChip()
            CrawlContent(pages, modifier = Modifier.onGloballyPositioned { copyWidthPx = it.size.width })
            if (overflow) CrawlContent(pages)   // second copy only when it will actually scroll
        }
        // Staleness flag pinned to the RIGHT edge (same as the flip motion).
        if (stale) StaleChip(modifier = Modifier.align(Alignment.CenterEnd))

        // SINGLE framework-timed driver. The animation clock is REAL-TIME based
        // (`animateTo` + LinearEasing), so the crawl holds a CONSTANT velocity
        // regardless of frame load — under heavy load the framework renders fewer
        // intermediate frames (slightly less smooth) instead of varying the speed.
        // This replaces the clamped-per-frame-delta accumulator, which lost motion
        // (looked slow/stalled) when the box's variable CPU load stretched a frame
        // past the 33ms clamp. Re-keyed ONLY on the levers that change the timing
        // (speed, pause, overflow) — NOT on `pages`, so a content refresh never
        // restarts it. Each cycle: animate one copy-width, snap back (seamless —
        // copy 2 sits exactly where copy 1 began), then DWELL (the slip time).
        // CRAWL_GAP is a @Composable accessor now that it tracks the ticker's box scale,
        // so it must be read HERE (composable scope) and captured — a LaunchedEffect body
        // is not a composable context. Keyed into the effect below so a box-scale change
        // re-derives the loop period instead of scrolling to a stale one.
        val gapPx = CRAWL_GAP.value * density   // the inter-copy gap, in px (part of the loop period)
        LaunchedEffect(scrollPct, paused, overflow, gapPx) {
            if (paused || !overflow) { offset.snapTo(0f); return@LaunchedEffect }
            val pxPerSec = crawlPxPerSec(CRAWL_BASE_DP_PER_SEC, scrollPct, density)
            while (true) {
                // The repeat PERIOD is one copy width + the inter-copy gap (read fresh
                // each cycle, so new content integrates here). Wrapping at the full
                // period lands copy 2 exactly on copy 1's origin — truly seamless.
                val period = crawlPeriodPx(copyWidthPx, gapPx)
                val start = offset.value.mod(period)                 // resume from current position (no jump)
                if (offset.value != start) offset.snapTo(start)
                val durationMs = crawlDurationMs(period - start, pxPerSec)
                if (durationMs > 0) {
                    offset.animateTo(period, tween(durationMillis = durationMs, easing = LinearEasing))
                }
                offset.snapTo(0f)            // seamless wrap: copy 2 sits exactly at copy 1's origin
                delay(CRAWL_DWELL_MS)         // end-of-crawl dwell (slip time)
            }
        }
    }
}

/** One copy of the crawl content: every page's inline marker followed by its
 *  cards, concatenated in flip-order (markets → leagues → news). */
@Composable
private fun CrawlContent(pages: List<TickerPaging.Page>, modifier: Modifier = Modifier) {
    Row(
        // wrap, not fill — last link in the crawl chain. `fillMaxHeight()` here
        // resolved against the bar's unbounded incoming maxHeight (the whole wall
        // Column), which is what expanded the ticker over the feed+grid Row.
        // CenterVertically below still centres the cards in the bar.
        modifier = modifier.wrapContentHeight(),
        verticalAlignment = Alignment.CenterVertically,
        // Same gap as the inter-copy spacing, so the seam reads as just another
        // card gap and the loop period (copy width + CRAWL_GAP) stays consistent.
        horizontalArrangement = Arrangement.spacedBy(CRAWL_GAP),
    ) {
        pages.forEach { page ->
            CrawlMarker(page.markerLabel)
            when (page) {
                is TickerPaging.Markets -> page.quotes.forEach { MarketCard(it) }
                is TickerPaging.League -> page.block.games.forEach { SportsEntryCard(it) }
                is TickerPaging.News -> page.items.forEach { NewsCard(it) }
            }
        }
    }
}

/** Inline league/market/news marker for the crawl — a compact green bug (the
 *  flip motion uses a full-height pinned curtain instead). */
@Composable
private fun CrawlMarker(label: String) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(tu(3))).background(WallColors.BadgeLive).padding(horizontal = tu(6), vertical = tu(2)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color(0xFF000000),
            fontSize = ttu(9),
            fontWeight = FontWeight.Bold,
            letterSpacing = ttu(1),
            maxLines = 1,
        )
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
private fun PageRow(
    page: TickerPaging.Page,
    stale: Boolean,
    paused: Boolean,
    scrollPct: Int = 100,
    dwellMs: Long = BASE_DWELL_MS,
) {
    // The page area spans the FULL strip width and is clipped. The marker overlays
    // the TRUE left edge; the STALE flag (when present) overlays the RIGHT edge —
    // both pinned, on top of the scroll, so a stale pill can never shove the curtain
    // inboard (the marker must stay pinned at the panel's left edge regardless).
    // HEIGHT MUST WRAP, NOT FILL. This Box is the page area inside TickerStrip's bar,
    // and that bar is a Column child whose only height rule is `heightIn(min = tu(40))` —
    // a FLOOR with no cap. `fillMaxHeight()` here resolved against the bar's incoming
    // maxHeight, which in the wall Column is the ENTIRE wall height: the ticker then
    // consumed the whole Column and the feed+grid Row below it was laid out at zero
    // height (no tile surfaces, no OFFLINE badges, nothing drawn). Wrapping keeps the
    // documented behaviour — the bar is `max(floor, text height)` and CenterStart
    // centres the cards in a taller bar — while leaving the rest of the wall its space.
    Box(modifier = Modifier.fillMaxWidth().wrapContentHeight().clipToBounds()) {
        // The configured reveal velocity (dp/sec, scaled by the scroll slider).
        val velocityDp = (BASE_SCROLL_VELOCITY * (scrollPct.coerceAtLeast(1) / 100f)).value
        val density = LocalDensity.current.density
        // Programmatic-only horizontal scroll (NOT user-scrollable): the page
        // reveals itself in one controlled pass, then HOLDS at the revealed end.
        val scrollState = rememberScrollState()
        // Drive the per-page reveal: scroll to start → start-hold → single-pass
        // animate-scroll to the fully-revealed end → END-HOLD (so the rightmost
        // content is readable) → loop. Re-runs when the page, the scroll slider,
        // or the paused state changes. Paused = no scroll at all.
        LaunchedEffect(page.key, scrollPct, paused) {
            if (paused) {
                scrollState.scrollTo(0)
                return@LaunchedEffect
            }
            while (true) {
                scrollState.scrollTo(0)
                val overflowPx = scrollState.maxValue // 0 → fits, nothing to reveal
                delay(START_HOLD_MS)
                val durationMs = revealDurationMs(
                    overflowPx = overflowPx,
                    velocityDpPerSec = velocityDp,
                    density = density,
                    dwellMs = dwellMs,
                    startHoldMs = START_HOLD_MS,
                    endHoldMs = END_HOLD_MS,
                )
                if (durationMs > 0) {
                    scrollState.animateScrollTo(
                        scrollState.maxValue,
                        animationSpec = tween(durationMillis = durationMs, easing = LinearEasing),
                    )
                }
                // HOLD at the fully-revealed end so the rightmost content is readable
                // before PagedTicker's dwell flips a multi-page league set away.
                delay(END_HOLD_MS)
            }
        }
        Row(
            modifier = Modifier.fillMaxSize().horizontalScroll(scrollState, enabled = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tu(8)),
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
            .clip(RoundedCornerShape(tu(5)))
            .background(CardBg)
            .border(1.dp, CardBorder, RoundedCornerShape(tu(5)))
            .padding(horizontal = tu(8), vertical = tu(3)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tu(6)),
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
        fontSize = ttu(12),
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        entry.display,
        color = if (sample) WallColors.LabelGhost else WallColors.LabelMuted,
        fontSize = ttu(12),
        fontFamily = FontFamily.Monospace,
    )
    val (arrow, color) = when (entry.direction) {
        TickerEntry.Direction.UP -> "▲" to WallColors.BadgeLive
        TickerEntry.Direction.DOWN -> "▼" to Color(0xFFEF5350)
        TickerEntry.Direction.FLAT -> "■" to WallColors.LabelMuted
        TickerEntry.Direction.NONE -> null to WallColors.LabelMuted
    }
    if (arrow != null) Text(arrow, color = if (sample) color.copy(alpha = 0.5f) else color, fontSize = ttu(11))
    if (sample) {
        Text(
            text = "sample",
            color = WallColors.LabelGhost,
            fontSize = ttu(7),
            fontStyle = FontStyle.Italic,
            letterSpacing = ttu(0.5),
        )
    }
}

/** A news headline as a bordered card — source accent + headline. */
@Composable
private fun NewsCard(entry: TickerEntry) = cardRow {
    Text(
        text = entry.symbol.uppercase(),
        color = WallColors.BadgeLive,
        fontSize = ttu(9),
        fontWeight = FontWeight.Bold,
        letterSpacing = ttu(1),
    )
    Text(text = entry.display, color = WallColors.LabelMuted, fontSize = ttu(12), maxLines = 1)
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
        "match" -> MatchCard(card, entry.isSample)
        "race" -> RaceCard(card, entry.isSample)
        else -> SportCardGeneric(card, entry.isSample)
    }
}

/**
 * Tennis match card — the players ("A vs B" / "A d. B") + the set scores
 * (monospace, so '6-4 7-6(3)' aligns) + a weighted status block (Final / live).
 * One card per match in the Tennis block.
 */
@Composable
private fun MatchCard(card: SportCard, isSample: Boolean) = cardRow {
    Text(card.title, color = WallColors.LabelPrimary, fontSize = ttu(12), fontWeight = FontWeight.SemiBold, maxLines = 1)
    card.lines.forEach { sets ->
        Text(sets, color = WallColors.LabelMuted, fontSize = ttu(12), fontFamily = FontFamily.Monospace, maxLines = 1)
    }
    if (card.status.isNotBlank()) {
        StatusBlock(SportsTicker.formatStatus(card.state, card.status), SportsTicker.kindOf(card.state))
    }
    if (isSample) SampleChip()
}

/**
 * F1 race card — the GP name + the podium ("1. Verstappen …") for a run race,
 * or the race start for an upcoming weekend, + a weighted status block. One
 * card per race weekend in the F1 block.
 */
@Composable
private fun RaceCard(card: SportCard, isSample: Boolean) = cardRow {
    Text(card.title, color = WallColors.LabelPrimary, fontSize = ttu(12), fontWeight = FontWeight.SemiBold, maxLines = 1)
    card.lines.forEach { pos ->
        Text(pos, color = WallColors.LabelMuted, fontSize = ttu(12), maxLines = 1)
    }
    if (card.status.isNotBlank()) {
        StatusBlock(SportsTicker.formatStatus(card.state, card.status), SportsTicker.kindOf(card.state))
    }
    if (isSample) SampleChip()
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
        fontSize = ttu(13),
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
    card.lines.forEach { wclass ->
        Text(text = wclass, color = WallColors.LabelMuted, fontSize = ttu(10), maxLines = 1)
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
        fontSize = ttu(12),
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
    card.lines.forEach { line ->
        Text(
            text = line,
            color = WallColors.LabelMuted,
            fontSize = ttu(12),
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
    Text(card.title, color = WallColors.LabelPrimary, fontSize = ttu(12), fontWeight = FontWeight.SemiBold, maxLines = 1)
    card.lines.forEach { line ->
        Text(line, color = WallColors.LabelMuted, fontSize = ttu(12), maxLines = 1)
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
        horizontalArrangement = Arrangement.spacedBy(tu(6)),
    ) {
        if (noScores) {
            Text(
                text = "${game.away} @ ${game.home}",
                color = teamColor,
                fontSize = ttu(13),
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(tu(4))) {
        Text(text = abbr, color = color, fontSize = ttu(13), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        Text(
            text = score,
            color = if (leading) WallColors.LabelPrimary else color,
            fontSize = ttu(14),
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
        modifier = Modifier.clip(RoundedCornerShape(tu(3))).background(bg).padding(horizontal = tu(6), vertical = tu(2)),
    ) {
        Text(text = text, color = fg, fontSize = ttu(10), fontWeight = FontWeight.Bold, letterSpacing = ttu(0.8))
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
            fontSize = ttu(10),
            fontWeight = FontWeight.Bold,
            letterSpacing = ttu(1.2),
            maxLines = 1,
        )
    }
}

/** C3: a sample value never passes as live. */
@Composable
private fun SampleChip() {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(tu(2))).background(Color(0x33FFFFFF)).padding(horizontal = tu(4), vertical = tu(1)),
    ) {
        Text(text = "SAMPLE", color = WallColors.LabelGhost, fontSize = ttu(8), letterSpacing = ttu(1))
    }
}

/** C3: aged real data is flagged, never shown as live. Pinned to the strip's
 *  right edge (an overlay), with a near-opaque backing so scrolling cards behind
 *  it don't bleed through and hurt legibility. */
@Composable
private fun StaleChip(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(tu(2))).background(Color(0xE6050505)).padding(horizontal = tu(6), vertical = tu(2)),
    ) {
        Text(text = "STALE", color = WallColors.BadgeStale, fontSize = ttu(9), letterSpacing = ttu(1.4), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PausedChip() {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(tu(2))).background(Color(0x33FFFFFF)).padding(horizontal = tu(6), vertical = tu(2)),
    ) {
        Text(text = "PAUSED", color = WallColors.BadgeLive, fontSize = ttu(9), letterSpacing = ttu(1.4), fontWeight = FontWeight.SemiBold)
    }
}
