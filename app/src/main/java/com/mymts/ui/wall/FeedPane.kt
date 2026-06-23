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
import com.mymts.ui.wall.feed.FeedListBuilder

/**
 * Feed pane — a single **agnostic, newest-first list** of news items
 * across all sources, with the **source shown next to each headline**.
 *
 * Panel-fit-&-agnostic-feed chapter (2026-06-07) replaced the per-source
 * sectioned layout (Stage 7) with this Onn-style chronological river per
 * the operator's decision after living with sections on hardware — now
 * consistent with the reworked web client (`feedChronological` +
 * `sourceLabel`). Each row shows the source label (accent, glanceable) +
 * its age, then the headline (dominant), then the summary.
 *
 * **Focus is unchanged:** the wall focus model (`WallFocusModel`) treats
 * the feed as a single contiguous list indexed `0..itemCount-1`. With the
 * agnostic list there are no headers, so `feedIndex == list index`
 * directly (a `LaunchedEffect(focusedIndex)` scrolls the focused row into
 * view). No focus-model change — the navigation chapter's no-trap
 * invariants are preserved.
 *
 * **Honest staleness (C3) without sections:** per-item age rides on each
 * row, and the pane header surfaces whole-feed "not updating" / "helper
 * unreachable" honestly — the section-level freshness chip is gone, its
 * job split between the per-item age and the pane header.
 *
 * **A1 boundary (unchanged):** every field renders as native Compose
 * `Text`; no `WebView`, no HTML interpretation. SELECT-on-focused-item
 * expands the helper's already-plain-text `summary` in place — no fetch,
 * no markup render.
 */
@Composable
fun FeedPane(
    repository: FeedRepository,
    modifier: Modifier = Modifier,
    focusedIndex: Int? = null,
    expandedIndex: Int? = null,
    onItemCountChanged: (Int) -> Unit = {},
    fontScale: Float = 1f,
    hiddenSources: Set<String> = emptySet(),
    hiddenLeagues: Set<String> = emptySet(),
    hiddenGenres: Set<String> = emptySet(),
    feedRecency: com.mymts.data.settings.FeedRecency = com.mymts.data.settings.FeedRecency.All,
) {
    val state by repository.state.collectAsState()
    val rawItems = remember(state.snapshot) { state.snapshot?.items.orEmpty() }
    val stale = repository.isStale()
    val filtersActive = hiddenSources.isNotEmpty() || hiddenGenres.isNotEmpty() ||
        feedRecency != com.mymts.data.settings.FeedRecency.All

    // Apply the operator's feed filters (source denylist + recency + the
    // Sports-leagues pool gating sports-news), THEN order into one agnostic
    // newest-first river. Both pure; operate on already-fetched plain text
    // (A1). The ordered list IS the focus order.
    val items = remember(state.snapshot, hiddenSources, hiddenLeagues, hiddenGenres, feedRecency) {
        FeedListBuilder.applyFilters(
            rawItems, hiddenSources, hiddenLeagues, feedRecency, System.currentTimeMillis(),
            hiddenGenres = hiddenGenres,
        )
    }
    val ordered = remember(items) { FeedListBuilder.build(items) }

    // Report the visible item count — this is what the focus model
    // navigates (feedIndex 0..count-1), and it == the list index directly.
    LaunchedEffect(ordered.size) { onItemCountChanged(ordered.size) }

    val listState = rememberLazyListState()
    LaunchedEffect(focusedIndex, ordered) {
        // Keep the focused row visible. feedIndex == list index now (no
        // headers to skip). animateScrollToItem is a no-op when visible.
        val target = focusedIndex?.takeIf { it in ordered.indices }
        if (target != null) listState.animateScrollToItem(target)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WallColors.Background),
    ) {
        PaneHeader(stale = stale, fetchOk = state.lastFetchOk, itemCount = ordered.size)
        Divider(color = Color(0x22FFFFFF), thickness = 1.dp)
        if (ordered.isEmpty()) {
            // Honest empty state — distinguish "filters hid everything"
            // (operator can widen) from "no data yet / stale".
            if (filtersActive && rawItems.isNotEmpty()) {
                EmptyFeedFiltered(recencyActive = feedRecency != com.mymts.data.settings.FeedRecency.All)
            } else {
                EmptyFeed(stale = stale, fetchOk = state.lastFetchOk)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(
                    count = ordered.size,
                    // Unique+stable key (a LazyColumn throws on duplicates) —
                    // pure + unit-tested in FeedListBuilder.rowKey.
                    key = { idx -> FeedListBuilder.rowKey(ordered[idx], idx) },
                ) { idx ->
                    FeedRow(
                        item = ordered[idx],
                        focused = focusedIndex == idx,
                        expanded = expandedIndex == idx,
                        fontScale = fontScale,
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
        // Whole-feed honesty (C3): the section freshness chips are gone, so
        // the pane header carries the "not updating / unreachable" signal.
        val (label, color) = when {
            stale && !fetchOk -> "helper unreachable" to WallColors.BadgeRecovering
            stale -> "feed not updating" to WallColors.BadgeStale
            itemCount == 0 -> "waiting for items" to WallColors.LabelMuted
            else -> "$itemCount items" to WallColors.LabelMuted
        }
        Text(text = label, color = color, fontSize = 11.sp)
    }
}

/**
 * One feed row — the agnostic-list row. A meta line carries the **source**
 * (accent, glanceable) and the item's **age**; the headline dominates;
 * the summary follows. Source + age are the per-item honesty signals now
 * that there are no section headers.
 */
@Composable
private fun FeedRow(
    item: FeedItem,
    focused: Boolean = false,
    expanded: Boolean = false,
    fontScale: Float = 1f,
) {
    val timeChip = remember(item.publishedAtIso, item.fetchedAtIso) {
        RelativeTime.render(item.publishedAtIso) ?: RelativeTime.render(item.fetchedAtIso)
    }
    val source = remember(item.source) { FeedListBuilder.sourceLabel(item) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (focused) Color(0x14FFFFFF) else Color.Transparent),
    ) {
        // 3dp accent on the left edge for the focused row — same focus
        // signal the grid + ticker use, for a consistent 10-ft "where am I".
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (focused) WallColors.BadgeLive else Color.Transparent),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // Meta line: SOURCE (accent, glanceable) ··· age (ghost, mono).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = source.uppercase(),
                    color = WallColors.BadgeLive,
                    fontSize = (10.sp.value * fontScale).sp,
                    letterSpacing = 0.8.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (timeChip != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = timeChip,
                        color = WallColors.LabelGhost,
                        fontSize = (10.sp.value * fontScale).sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Text(
                text = item.title,
                color = WallColors.LabelPrimary,
                fontSize = ((if (expanded) 18 else 15).sp.value * fontScale).sp,
                lineHeight = ((if (expanded) 23 else 19).sp.value * fontScale).sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.summary.isNullOrBlank()) {
                // A1: the summary is the helper's pre-stripped plain text;
                // expand just shows MORE of the same in place — no WebView,
                // no re-fetch.
                Text(
                    text = item.summary,
                    color = if (expanded) WallColors.LabelPrimary else WallColors.LabelMuted,
                    fontSize = ((if (expanded) 14 else 12).sp.value * fontScale).sp,
                    lineHeight = ((if (expanded) 19 else 16).sp.value * fontScale).sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.padding(top = 4.dp))
                Text(
                    text = "OK to collapse · BACK to collapse",
                    color = WallColors.LabelGhost,
                    fontSize = (10.sp.value * fontScale).sp,
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
        Text(text = text, color = WallColors.LabelGhost, fontSize = 12.sp)
    }
}

/**
 * Honest empty state when the operator's filters hid everything (there
 * IS data, the filters just excluded it) — never a blank pane that looks
 * broken. Tells the operator it's a filter, not an outage.
 */
@Composable
private fun EmptyFeedFiltered(recencyActive: Boolean) {
    val text = if (recencyActive) {
        "No items match your feed filters (sources / recency). Widen them in Settings."
    } else {
        "No items from the selected sources. Turn sources back on in Settings → Feed sources."
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Text(text = text, color = WallColors.LabelGhost, fontSize = 12.sp)
    }
}
