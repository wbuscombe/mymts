package com.mymts.ui.wall

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymts.data.ticker.TickerEntry
import com.mymts.data.ticker.TickerSource

/**
 * Top-of-wall scrolling ticker.
 *
 * Stage 3 reads from any [TickerSource]; the only implementation today
 * is the sample-data adapter, and every entry it emits is decorated as
 * SAMPLE so the operator can never mistake the values for live quotes
 * (Trust Bar C3 applied to the ticker).
 *
 * Visual register: a thin dark strip across the top of the wall, with
 * each entry rendered as `SYMBOL · value` (an arrow glyph encodes
 * direction). Values use a monospaced font so up/down don't reshuffle
 * the marquee width on each repaint.
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
        // Pause / resume: when the operator hits SELECT while the
        // ticker is focused (per the WallFocusModel), `paused` flips.
        // Conditionally drop basicMarquee — text renders statically
        // from the start of the row so values can be read at the
        // couch. A small chip in the leading edge surfaces the paused
        // state honestly (no silent "why isn't it moving?").
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
            // ESPN-BottomLine style: group consecutive same-symbol entries
            // so a league marker shows ONCE, then its games follow without
            // the redundant per-item prefix. Markets symbols are distinct,
            // so each stays its own marker+value (unchanged). Consistent
            // with the web client's groupTickerByLeague.
            TickerGrouping.group(entries).forEach { run -> TickerRun(run) }
        }
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
 * One labelled run: the league/market marker (a small accent pill) shown
 * ONCE, then each entry's value follows without repeating the symbol. This
 * is the ESPN-BottomLine layout — for sports it kills the redundant
 * per-game "MLB …" prefix; for markets each symbol is its own one-row run,
 * so it reads as "S&P 500 │ 5,820 ▲" — same information, marker-pill style.
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
            // Sports scores are non-directional — draw no glyph.
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
