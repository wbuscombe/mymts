# Finding 13 — Feed filtering (by source + recency)

> **Status: BUILT + TESTED 2026-06-06.** The feed can now be narrowed by **source** (toggle which of the sources appear) and by **recency** (All / last hour / 6h / 24h), in the settings menu, persisted on-device. The filtering half of feedback item B (the sectioning half shipped in Stage 7). All of it operates on the plain-text items the helper already serves — no new fetch, no web, A1 holds. App suite 233 green (+9 filter tests). **Free-text search was deliberately deferred** — see below.

## Why this chapter happened

The feed-restructure chapter (Stage 7) made the feed sectioned-by-source and explicitly deferred the *filtering* half of item B. With 13 sources now flowing, "show me just these sources" / "just the last few hours" is the natural next control.

## The search decision (deferred — flagged for the operator)

The chapter prompt asked to recommend the lower-friction path and surface search as a genuine question if D-pad text entry seemed more trouble than it's worth. It is: **free-text search via an on-screen keyboard on a 10-ft ambient wall is high-friction for low value**, and rich **source + recency** filtering delivers most of the "narrow the feed" benefit with zero text entry. So this chapter built source + recency filtering and **deferred free-text search** to BACKLOG.

**Operator: this is the one decision in this chapter worth reversing if you disagree.** If, after using the wall, you want to find a specific story by keyword, say the word and I'll add a search view (it would still operate on the already-fetched plain text — no web search — with an Android-TV on-screen keyboard or a character picker). It's logged, not lost.

## What got built

- **Source filter — a denylist.** `WallSettings.hiddenSources: Set<String>`. Stored as "hide these," **not** "show these" — so a newly-added feed source appears by default rather than being silently excluded. (This also satisfies item C, "feed source enable/disable.")
- **Recency filter.** `WallSettings.feedRecency` (`All` / `Last hour` / `Last 6h` / `Last 24h`). Published time preferred, fetched-time fallback; an item with no parseable timestamp is kept under `All` and dropped under a bounded window (we can't prove it recent).
- **The pure core.** `FeedListBuilder.applyFilters(items, hiddenSources, recency, now)` — applied *before* `build()` groups the items, so the sectioned layout, the per-source freshness chips, and the focus flat-index all operate on the visible set. 9 unit tests pin it (case-insensitive denylist, recency boundaries, no-timestamp behaviour, source+recency composition, `distinctSources` ordering).
- **The UI.** `SettingsOverlay` gains a "Feed recency" cycle row and a "Feed sources…" opener (showing N hidden); `SourceFilterOverlay` is a D-pad toggle list of the feed's distinct sources (SELECT show/hide, BACK done). Persisted via `LineupStore`.

## Focus model + honesty

- The filter UIs are **modal overlays** — they don't touch `WallFocusModel`'s zone graph, so the no-trap invariants hold unmodified (the 49 navigation tests pass unchanged).
- **Focus correctness:** `FeedPane` reports the **filtered** item count to the focus model, so `feedIndex` can't run off the end of a filtered list.
- **Honest empty states:** when a filter hides everything, the pane shows a filter-aware message ("No items match your feed filters… widen them in Settings" / "No items from the selected sources…") — never a blank pane that looks broken. Per-source freshness chips persist on remaining sections; staleness is never hidden by filtering.
- **A1:** filtering changes *which* inert items show, never *how* they render — same `Text`-only path, no fetch, no web. Confirmed in the THREAT-MODEL reverify entry.

## Tie-in to a future ticker-news mode (noted, not built)

The operator flagged wanting "parameters for news in the ticker." That overlaps with "which feed items matter" — exactly what this filter computes (source subset + recency). The filter logic is kept **pure and parameterized** (`applyFilters` takes the source set + recency as arguments), so a future ticker-news mode can reuse it to pick which headlines to scroll without rework. The ticker-news mode itself is a separate chapter (the curation pass) — not built here, and the filtering is deliberately not architected in a way that would block that reuse.

## What's deferred

- **Free-text search** — D-pad friction; BACKLOG (operator-reversible, see above).
- **Topic/keyword auto-classification** — a foundation v2 idea; topic filtering = keyword search, not auto-tagging. Stays deferred.

## Open items for the at-the-box feel-test

- Does source + recency filtering feel like enough, or is keyword search genuinely wanted at the wall?
- Is the "Feed sources…" toggle list comfortable to navigate with the remote, or would inline per-section toggles read better?
- Do the recency windows (1h / 6h / 24h) match how the operator actually skims?

## Standing rules at this stage

- A1 held (filters operate on already-fetched inert plain text; no new surface).
- unrelated host services never touched; `.182`/WyzeGrid untouched; no secrets/absolute-paths.
