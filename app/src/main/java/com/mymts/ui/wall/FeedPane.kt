package com.mymts.ui.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mymts.data.helper.FeedItem
import com.mymts.data.helper.FeedRepository

/**
 * Feed pane — the dark, dense, chronological news column on the left.
 *
 * Composed in three layers:
 *   - a fixed pane header carrying a calm staleness indicator (Trust
 *     Bar **C3** — if the helper hasn't refreshed within the staleness
 *     window or is unreachable, the pane says so).
 *   - a thin divider.
 *   - a `LazyColumn` of [FeedRow]s, newest first.
 *
 * 10-foot legibility is the design constraint. Type sizes are larger
 * than a desktop-list equivalent; line spacing is generous; the
 * summary clips after two lines so a tile of items shows enough to
 * scan from across the room without scrolling.
 *
 * **A1 boundary:** every field renders as native `Text`. There is no
 * code path that mounts a `WebView` or interprets HTML. The helper
 * already strips HTML in `feeds/parser.py` (Trust Bar A1) and we
 * treat the rendered string as inert.
 */
@Composable
fun FeedPane(
    repository: FeedRepository,
    modifier: Modifier = Modifier,
    focusedIndex: Int? = null,
    expandedIndex: Int? = null,
    onItemCountChanged: (Int) -> Unit = {},
) {
    val state by repository.state.collectAsState()
    val items = remember(state.snapshot) { state.snapshot?.items.orEmpty() }
    val stale = repository.isStale()

    LaunchedEffect(items.size) { onItemCountChanged(items.size) }

    val listState = rememberLazyListState()

    LaunchedEffect(focusedIndex) {
        // Keep the focused row visible. animateScrollToItem is a no-op
        // when the item is already inside the viewport, so this is
        // safe to call on every focus change.
        focusedIndex?.takeIf { it in items.indices }?.let { listState.animateScrollToItem(it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WallColors.Background),
    ) {
        PaneHeader(stale = stale, fetchOk = state.lastFetchOk, itemCount = items.size)
        Divider(color = Color(0x22FFFFFF), thickness = 1.dp)
        if (items.isEmpty()) {
            EmptyFeed(stale = stale, fetchOk = state.lastFetchOk)
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(
                    count = items.size,
                    key = { idx -> items[idx].id.takeIf { x -> x >= 0 } ?: items[idx].title.hashCode() },
                ) { idx ->
                    FeedRow(
                        item = items[idx],
                        focused = focusedIndex == idx,
                        expanded = expandedIndex == idx,
                    )
                    Divider(color = Color(0x14FFFFFF), thickness = 1.dp)
                }
            }
        }
    }
}

@Composable
private fun PaneHeader(stale: Boolean, fetchOk: Boolean, itemCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0A0A))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "FEED",
            color = WallColors.LabelPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
        )
        val (label, color) = when {
            stale && !fetchOk -> "helper unreachable" to WallColors.BadgeRecovering
            stale -> "feed not updating" to WallColors.BadgeStale
            itemCount == 0 -> "waiting for items" to WallColors.LabelMuted
            else -> "$itemCount items" to WallColors.LabelMuted
        }
        Text(text = label, color = color, fontSize = 11.sp)
    }
}

@Composable
private fun FeedRow(
    item: FeedItem,
    focused: Boolean = false,
    expanded: Boolean = false,
) {
    val timeChip = remember(item.publishedAtIso, item.fetchedAtIso) {
        RelativeTime.render(item.publishedAtIso) ?: RelativeTime.render(item.fetchedAtIso)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (focused) Color(0x14FFFFFF) else Color.Transparent),
    ) {
        // 3dp green accent on the left edge for the focused row. The
        // colour matches WallColors.BadgeLive — the WyzeGrid-family
        // accent — and is the same focus signal used by the grid and
        // ticker so a 10-foot read of "where am I" stays consistent.
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (focused) WallColors.BadgeLive else Color.Transparent),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.source.uppercase(),
                    color = WallColors.LabelGhost,
                    fontSize = 10.sp,
                    letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (timeChip != null) {
                    Text(
                        text = timeChip,
                        color = WallColors.LabelGhost,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Text(
                text = item.title,
                color = WallColors.LabelPrimary,
                // Expanded headlines step up to be readable from the
                // couch (the wall's primary viewing distance). Same
                // type ramp as the SlotControlsOverlay's panel title.
                fontSize = if (expanded) 18.sp else 15.sp,
                lineHeight = if (expanded) 23.sp else 19.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.summary.isNullOrBlank()) {
                // Trust Bar A1 (boundary): the summary is the
                // helper's pre-rendered plain text — `feeds/parser.py`
                // strips HTML and we treat it as inert. Expand here
                // just shows MORE of the same plain text in place; we
                // never WebView, never re-fetch (BUILD-PROMPT §4 closed
                // door — confirmed in THREAT-MODEL §"feed-expand").
                Text(
                    text = item.summary,
                    color = if (expanded) WallColors.LabelPrimary else WallColors.LabelMuted,
                    fontSize = if (expanded) 14.sp else 12.sp,
                    lineHeight = if (expanded) 19.sp else 16.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) {
                Spacer(Modifier.padding(top = 4.dp))
                Text(
                    text = "OK to collapse · BACK to collapse",
                    color = WallColors.LabelGhost,
                    fontSize = 10.sp,
                    letterSpacing = 0.6.sp,
                )
            }
        }
    }
}

@Composable
private fun EmptyFeed(stale: Boolean, fetchOk: Boolean) {
    val text = when {
        stale && !fetchOk -> "Helper unreachable — feed paused."
        stale -> "Feed not updating — sources may be stale."
        else -> "Waiting for the first feed sweep…"
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Text(
            text = text,
            color = WallColors.LabelGhost,
            fontSize = 12.sp,
        )
    }
}
