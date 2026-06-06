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
import com.mymts.ui.wall.feed.FeedListEntry
import com.mymts.ui.wall.feed.SectionFreshness

/**
 * Feed pane — a structured, sectioned list of news items, grouped by
 * source.
 *
 * Stage 7 (the feed-restructure chapter) replaced the prior continuous
 * chronological river with a sectioned list per the operator's "list,
 * not individual-scroll, very unintuitive and inefficient" feedback.
 * Each source (BBC, Guardian, Al Jazeera, NPR, …) gets its own labelled
 * section with a per-source freshness chip (Trust Bar **C3** applied
 * per source) — the operator can see at a 10-foot glance which sources
 * are flowing and which have gone quiet. Items within a section stay
 * newest-first.
 *
 * **Focus is unchanged:** the wall focus model (`WallFocusModel`)
 * keeps treating the feed as a single contiguous list indexed
 * 0..itemCount-1. Headers are **visual only** — never focusable.
 * `feedIndex` traverses items in the same order they appear in the
 * visible list (alphabetical-source, newest-first-within-source); a
 * `LaunchedEffect(focusedIndex)` uses
 * [FeedListBuilder.entriesIndexForFocus] to scroll the right row into
 * view. **No focus-model change** — the navigation chapter's no-trap
 * invariants are preserved without modification.
 *
 * **A1 boundary (unchanged from the navigation chapter):** every field
 * renders as native Compose `Text`. There is no code path that mounts
 * a `WebView` or interprets HTML. Section headers are inert-text
 * labels. SELECT-on-focused-item expands the item's `summary` (already
 * HTML-stripped by the helper's `feeds/parser.py`) by flipping the
 * `Text` widget's `maxLines` to `Int.MAX_VALUE` — no fetch, no markup
 * render. The feed-restructure adds **no new input surface**.
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
    feedRecency: com.mymts.data.settings.FeedRecency = com.mymts.data.settings.FeedRecency.All,
) {
    val state by repository.state.collectAsState()
    val rawItems = remember(state.snapshot) { state.snapshot?.items.orEmpty() }
    val stale = repository.isStale()
    val filtersActive = hiddenSources.isNotEmpty() || feedRecency != com.mymts.data.settings.FeedRecency.All
    // Apply the operator's feed filters (source denylist + recency) to the
    // raw items BEFORE grouping — the sectioned layout, per-source freshness
    // chips, and the focus flat-index all then operate on exactly the
    // visible set. Pure; operates on already-fetched plain text (A1).
    val items = remember(state.snapshot, hiddenSources, feedRecency) {
        FeedListBuilder.applyFilters(rawItems, hiddenSources, feedRecency, System.currentTimeMillis())
    }

    // Build the sectioned entries. We memoize on the snapshot identity
    // so freshness chips refresh whenever the helper's poll lands a new
    // snapshot; between polls the chip ages naturally with `now`
    // baked in at build time. A subsequent polish pass could add a
    // periodic re-classification to age chips smoothly without a
    // network poll, but the operator's primary need is "is this source
    // flowing or not?", which the current per-poll rebuild already
    // answers.
    val entries = remember(items) {
        FeedListBuilder.build(items = items, now = System.currentTimeMillis())
    }

    // Report the FILTERED item count — this is what the focus model
    // navigates (feedIndex 0..count-1), so it must match what the pane
    // actually shows or focus would run off the end of a filtered list.
    LaunchedEffect(items.size) { onItemCountChanged(items.size) }

    val listState = rememberLazyListState()

    LaunchedEffect(focusedIndex, entries) {
        // Keep the focused row visible. Use `entriesIndexForFocus` to
        // skip over the visible-but-non-focusable headers so we scroll
        // to the right row in the layout. animateScrollToItem is a
        // no-op when the item is already visible.
        val target = focusedIndex
            ?.let { FeedListBuilder.entriesIndexForFocus(entries, it) }
            ?.takeIf { it >= 0 }
        if (target != null) listState.animateScrollToItem(target)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WallColors.Background),
    ) {
        PaneHeader(stale = stale, fetchOk = state.lastFetchOk, itemCount = items.size)
        Divider(color = Color(0x22FFFFFF), thickness = 1.dp)
        if (entries.isEmpty()) {
            // Honest empty state — distinguish "filters hid everything"
            // (operator can widen the filter) from "no data yet / stale".
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
                itemsIndexed(entries) { entryIndex, entry ->
                    when (entry) {
                        is FeedListEntry.Header -> SectionHeader(entry, fontScale)
                        is FeedListEntry.Item -> {
                            // Map back from entries-list index to flat
                            // focus index so the focused-row check is
                            // consistent with WallFocusModel's
                            // feedIndex.
                            val focusIndex = entriesIndexToFeedIndex(entries, entryIndex)
                            FeedRow(
                                item = entry.item,
                                focused = focusedIndex == focusIndex,
                                expanded = expandedIndex == focusIndex,
                                fontScale = fontScale,
                            )
                            Divider(color = Color(0x14FFFFFF), thickness = 1.dp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Map a position in the entries list back to the feed focus index
 * (which counts items only, not headers). Pure helper kept inline so
 * the call site reads naturally — same semantics as
 * [FeedListBuilder.entriesIndexForFocus] in the opposite direction.
 */
private fun entriesIndexToFeedIndex(entries: List<FeedListEntry>, entryIndex: Int): Int {
    var focusIndex = 0
    for (i in 0 until entryIndex) {
        if (entries[i] is FeedListEntry.Item) focusIndex++
    }
    return focusIndex
}

/**
 * LazyListScope helper. Compose's `itemsIndexed` does not take a
 * `List<T>` of a sealed type cleanly with a stable key, so we wire it
 * here against [FeedListEntry] explicitly.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    entries: List<FeedListEntry>,
    content: @Composable (Int, FeedListEntry) -> Unit,
) {
    items(
        count = entries.size,
        key = { idx ->
            when (val e = entries[idx]) {
                is FeedListEntry.Header -> "h:${e.source}"
                is FeedListEntry.Item -> e.item.id.takeIf { it >= 0 }
                    ?: "t:${e.item.title.hashCode()}"
            }
        },
    ) { idx -> content(idx, entries[idx]) }
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

/**
 * Per-source section header. Honest staleness applied per source
 * (Trust Bar **C3** at the section layer) — when a source goes quiet,
 * its header changes the chip color and text so the operator sees the
 * gap rather than reading old items as current.
 */
@Composable
private fun SectionHeader(entry: FeedListEntry.Header, fontScale: Float = 1f) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF080808))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = entry.source.uppercase(),
                color = WallColors.LabelPrimary,
                fontSize = (12.sp.value * fontScale).sp,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            FreshnessChip(entry)
        }
        Text(
            text = "${entry.itemCount} ${if (entry.itemCount == 1) "item" else "items"}",
            color = WallColors.LabelGhost,
            fontSize = (10.sp.value * fontScale).sp,
            letterSpacing = 0.8.sp,
        )
    }
}

@Composable
private fun FreshnessChip(entry: FeedListEntry.Header) {
    val (text, color) = when (entry.freshness) {
        SectionFreshness.Fresh -> formatAge(entry.newestAgeMs) to WallColors.BadgeLive
        SectionFreshness.Warm -> formatAge(entry.newestAgeMs) to WallColors.BadgeStale
        SectionFreshness.NotUpdating -> "not updating" to WallColors.BadgeRecovering
        SectionFreshness.Unknown -> "no items" to WallColors.LabelGhost
    }
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
    )
}

private fun formatAge(ageMs: Long?): String {
    if (ageMs == null) return ""
    val sec = ageMs / 1000
    return when {
        sec < 60 -> "now"
        sec < 3_600 -> "${sec / 60}m"
        sec < 86_400 -> "${sec / 3_600}h"
        else -> "${sec / 86_400}d"
    }
}

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
            // Per-item time chip lives at the top-right; the SECTION
            // header carries the source, so we no longer repeat source
            // on every row (less visual noise, more density for the
            // headline itself).
            if (timeChip != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
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
                // Expanded headlines step up to be readable from the
                // couch (the wall's primary viewing distance). Same
                // type ramp as the SlotControlsOverlay's panel title.
                // fontScale (1.0 default) is the operator's "Feed
                // font" setting; the smallest preset enforces a
                // 10-ft legibility floor so we never collapse below
                // readability.
                fontSize = ((if (expanded) 18 else 15).sp.value * fontScale).sp,
                lineHeight = ((if (expanded) 23 else 19).sp.value * fontScale).sp,
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
                    fontSize = ((if (expanded) 14 else 12).sp.value * fontScale).sp,
                    lineHeight = ((if (expanded) 19 else 16).sp.value * fontScale).sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) {
                Spacer(Modifier.padding(top = 4.dp))
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
        Text(
            text = text,
            color = WallColors.LabelGhost,
            fontSize = 12.sp,
        )
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
