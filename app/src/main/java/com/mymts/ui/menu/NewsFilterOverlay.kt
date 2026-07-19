package com.mymts.ui.menu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * News genre & source filter overlay (news-genre-groups chapter, Part E).
 *
 * A centered, D-pad-navigable **two-level** toggle list over the feed's
 * sources, grouped by genre ([com.mymts.ui.wall.feed.FeedGenres]). The top
 * level is a genre toggle (off ⇒ all of its sources leave the feed); under each
 * enabled genre are its per-source toggles. UP/DOWN move between rows (Compose
 * focus); SELECT toggles the focused row; BACK returns to settings. A genre or
 * source reads **Shown** when it currently contributes to the feed.
 *
 * The rows are rendered ENTIRELY from [groups] — the genre/source names live in
 * the data (the taxonomy + the caller's model), never hardcoded here — so a
 * source added to the taxonomy appears here with no edit to this surface.
 *
 * Scroll-follows-focus: one `Column` + `verticalScroll`, every row pulling
 * itself into view on focus (the [bringFocusedIntoView] discipline proven on the
 * overscan-clipped dev panel) — no genre or source can sit unreachable below the
 * fold.
 *
 * Honesty + A1: this only flips on-device preference sets (persisted by
 * `LineupStore`) over the already-fetched inert plain-text items — no fetch, no
 * web, no markup. A newly-added genre/source is enabled by default.
 */
@Composable
fun NewsFilterOverlay(
    groups: List<NewsGenreGroup>,
    onToggleGenre: (String) -> Unit,
    onToggleSource: (genre: String, source: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "NEWS GENRES & SOURCES",
    emptyText: String = "No sources yet — waiting for the feed to load.",
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(MenuColors.Scrim),
        contentAlignment = Alignment.Center,
    ) {
        // Cap the card to the visible area so the grouped (taller) list scrolls
        // rather than clipping off the panel's top/bottom.
        val maxCardHeight = maxHeight - 24.dp
        AnimatedVisibility(
            visible = true,
            enter = scaleIn(tween(160), initialScale = 0.92f) + fadeIn(tween(160)),
            exit = scaleOut(tween(120), targetScale = 0.92f) + fadeOut(tween(120)),
        ) {
            Card(maxCardHeight, groups, onToggleGenre, onToggleSource, onCancel, title, emptyText)
        }
    }
}

/**
 * One genre group in the two-level filter: the genre, whether it currently
 * contributes to the feed ([enabled]), and its per-source toggles. Built by the
 * caller from [com.mymts.ui.wall.feed.FeedGenres.sectioned] + the persisted
 * preference sets. `@Immutable` so Compose skips correctly (the `List` member is
 * rebuilt, never mutated).
 */
@Immutable
data class NewsGenreGroup(
    val genre: String,
    val enabled: Boolean,
    val sources: List<NewsSourceToggle>,
)

/**
 * One source toggle within a [NewsGenreGroup] — its label and whether it is
 * currently enabled (it contributes only when its genre is also enabled).
 */
@Immutable
data class NewsSourceToggle(
    val label: String,
    val enabled: Boolean,
)

@Composable
private fun Card(
    maxCardHeight: Dp,
    groups: List<NewsGenreGroup>,
    onToggleGenre: (String) -> Unit,
    onToggleSource: (genre: String, source: String) -> Unit,
    onCancel: () -> Unit,
    title: String,
    emptyText: String,
) {
    val firstRowFocusRequester = remember { FocusRequester() }
    LaunchedEffect(groups.isNotEmpty()) {
        if (groups.isNotEmpty()) firstRowFocusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .widthIn(min = 460.dp)
            .heightIn(max = maxCardHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(MenuColors.PanelBackground)
            .onPreviewKeyEvent { event ->
                // Card-level BACK so any focused row can dismiss without navigating first.
                if (event.type == KeyEventType.KeyDown && event.key == Key.Back) { onCancel(); true } else false
            }
            // Scrolls when the grouped list is taller than the panel; each row
            // pulls itself into the viewport on focus (bringFocusedIntoView).
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            color = MenuColors.RowLabelMuted,
            fontSize = 10.sp,
            letterSpacing = 3.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))

        if (groups.isEmpty()) {
            Text(
                text = emptyText,
                color = MenuColors.RowLabelMuted,
                fontSize = 12.sp,
            )
        } else {
            val firstGenre = groups.first().genre
            // Two levels rendered as one flat focus order: a genre row, then its
            // source rows, then the next genre — every row D-pad reachable.
            groups.forEach { group ->
                GenreRow(
                    group = group,
                    onToggle = { onToggleGenre(group.genre) },
                    modifier = if (group.genre == firstGenre) {
                        Modifier.focusRequester(firstRowFocusRequester)
                    } else Modifier,
                )
                group.sources.forEach { source ->
                    SourceRow(
                        label = source.label,
                        enabled = source.enabled,
                        genreEnabled = group.enabled,
                        onToggle = { onToggleSource(group.genre, source.label) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "SELECT to toggle   ·   a genre off rests its whole group   ·   BACK to done",
            color = MenuColors.RowLabelMuted,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
        )
    }
}

/**
 * The genre (top-level) row — a bold header with a check that reads **Shown**
 * when the genre is contributing. SELECT toggles the whole genre on/off.
 */
@Composable
private fun GenreRow(
    group: NewsGenreGroup,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .bringFocusedIntoView()
            .background(if (isFocused) MenuColors.FocusBackground else Color.Transparent)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onToggle)
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> { onToggle(); true }
                    else -> false
                }
            }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(16.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (group.enabled) MenuColors.FocusAccent else Color(0x22FFFFFF)),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = group.genre,
                color = if (isFocused) MenuColors.RowLabel else MenuColors.RowLabelMuted,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = if (group.enabled) "Shown" else "Off · ${group.sources.size} sources",
            color = if (group.enabled) MenuColors.RowDetail else MenuColors.RowLabelMuted,
            fontSize = 11.sp,
        )
    }
}

/**
 * A per-source (second-level) row, indented under its genre. SELECT toggles the
 * source. The check reads **Shown** only when both the source AND its genre are
 * enabled; when the genre is off the row is dimmed to show the genre gates it.
 */
@Composable
private fun SourceRow(
    label: String,
    enabled: Boolean,
    genreEnabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    // Effective contribution needs BOTH levels — the genre is the master switch.
    val effective = enabled && genreEnabled

    Row(
        modifier = modifier
            .fillMaxWidth()
            .bringFocusedIntoView()
            .background(if (isFocused) MenuColors.FocusBackground else Color.Transparent)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onToggle)
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> { onToggle(); true }
                    else -> false
                }
            }
            .padding(start = 38.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(12.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (effective) MenuColors.FocusAccent else Color(0x22FFFFFF)),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                color = if (isFocused && genreEnabled) MenuColors.RowLabel else MenuColors.RowLabelMuted,
                fontSize = 13.sp,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        Text(
            text = when {
                !genreEnabled -> "genre off"
                enabled -> "Shown"
                else -> "Off"
            },
            color = if (effective) MenuColors.RowDetail else MenuColors.RowLabelMuted,
            fontSize = 11.sp,
        )
    }
}

/**
 * Scroll the focused row INTO the card's viewport (the list follows the cursor).
 * The same discipline `SettingsOverlay` uses: the default `.focusable()`
 * bring-into-view scrolled flush to the edge, leaving the focused row at/below
 * the visible bottom on the overscan-clipped, fit-scaled dev panel — an explicit
 * [BringIntoViewRequester] fired on focus reliably pulls the row fully in.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.bringFocusedIntoView(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return this
        .bringIntoViewRequester(requester)
        .onFocusEvent { if (it.isFocused) scope.launch { requester.bringIntoView() } }
}
