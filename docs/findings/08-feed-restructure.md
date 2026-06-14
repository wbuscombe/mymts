# Finding 08 — Feed restructure with per-source freshness

> **Status: BUILT + TESTED 2026-06-04.** The wall's feed pane is now a sectioned list grouped by source, replacing the chronological river. Per-source freshness chips (Trust Bar **C3** at the section layer) let the operator see at a glance which sources are flowing and which have stalled. The navigation chapter's `WallFocusModel` remains unchanged; the flat-item invariant preserved the no-trap property without modification. **A1 boundary re-verified** — no new web-fetch or HTML render. Staged for the at-the-box feel-test.

## What this chapter does (and what it doesn't)

**Does:**
- Builds a **pure grouping function** (`FeedListBuilder.build()`) that transforms a flat `List<FeedItem>` into a sectioned `List<FeedListEntry>` (sealed Header | Item), grouped by source, alphabetical case-insensitive, newest-first within each section.
- Adds **per-section freshness classification** — `SectionFreshness` enum (Fresh / Warm / NotUpdating / Unknown) computed from each section's newest item, with C3 applied at the source layer. Fresh < 2h, Warm < 12h, NotUpdating ≥ 12h.
- Renders the sectioned feed in **`FeedPane`** — per-source `SectionHeader` (uppercase name, item count, freshness chip in the header bar) followed by focusable item rows. Headers are visual only, never focusable.
- Preserves the **flat-item invariant** — `WallFocusModel`'s `feedIndex` still traverses items 0..N-1 in visible-list order; `entriesIndexForFocus()` maps focus indices to the correct entries-list row, skipping headers. No focus-model change.
- Applies the same **live/offline grouping logic** to the channel-picker cycler as a companion win — `pickerGroupAt()` helper + a "LIVE 3/8" / "OFFLINE 2/5" orientation chip so the operator sees which group the picker cursor is in.
- **Re-verifies A1 boundary** — the restructure adds zero new web-fetch, WebView, or HTML render. All code is pure Kotlin (grouping) + Compose Text widgets (rendering).

**Does NOT:**
- Change the navigation focus model. `WallFocusModel.apply()` and its invariants are unchanged; the feed zone is still a single contiguous list indexed 0..itemCount-1.
- Rebuild the feed's per-item rendering — item expansion (SELECT toggles `feedExpanded` to show the full HTML-stripped summary) remains the same as the navigation chapter.
- Add feed filtering or search UI — logged for a separate future chapter.
- Implement section collapse / jump-by-source — the operator's current item-focus through sections is sufficient for v1.
- Touch unrelated host services, helper service (still non-root, read_only, cap_drop ALL), or kiosk story — all deferred.

## Why this chapter happened

**Operator feedback (2026-06-04 hands-on session, BACKLOG item B):** *"Feed is still a list, not individual-scroll. Very unintuitive and inefficient. Needs sections."*

The navigation chapter (Stage 7) made the feed focusable and expandable from the couch. The operator validated the D-pad navigation with a feel-test and flagged the feed's layout as the gap: the chronological river mixed all sources together ("What is BBC reporting?" required scrolling past NPR, Guardian, and Al Jazeera items). Item B called for sections — grouping items by source so the operator can quickly scan "what is X saying?" without wading through other sources. This chapter lands that layout shift, applying the same "honest staleness" (C3) idea per source instead of once for the whole feed.

## The grouping design — by source, not recency

**Why group by source?**

Two natural grouping axes:
- **By recency** (latest item globally, then second-latest, etc. — the current chronological river). Pro: complete temporal ordering. Con: operator's "what is BBC reporting?" requires vertical scan past other sources.
- **By source** (BBC, then Al Jazeera, then Guardian, then NPR, alphabetically). Pro: operator's muscle memory — "BBC is at the top" — survives across feeds. "What is X reporting?" is a single vertical scan. Con: loses global recency, but...

**Recency is implicit:** within each source section, items are newest-first. The operator sees the most current X reporting at the top of X's section. Across sources, alphabetical order is predictable (no shuffle on every poll) and trades off the global timestamp for the more useful axis: "is my preferred source (or section) saying something new?"

**Alphabetical ordering decision:**
- Case-insensitive (`"bbc"` and `"BBC"` sort as the same position).
- Stable across polls — the wall never reshuffles itself on a new snapshot, so the operator's "BBC is the third section" habit survives.
- Implemented as `bySource.keys.sortedBy { it.lowercase() }` in `FeedListBuilder.build()`.

Within a section, items sort `newest-first` using published-time when present, falling back to fetched-time, with ID as a tiebreaker for determinism.

## The flat-item invariant — no focus-model change

**The core design choice:** the sections are **visual only**. The `WallFocusModel` still treats the feed as a single contiguous flat list.

**Why?**

The navigation chapter pinned 38 no-trap invariants. Adding sectioning in a way that changes the focus model's traversal order would require:
- Reworking `feedIndex` to account for headers in the count.
- Re-running the entire 38-case reachability graph to prove no new traps.
- Risk: a subtle focus bug (e.g., can't exit a section, or section becomes unreachable).

**The alternative (and what we chose):**
- Keep `feedIndex` as the count of visible **items only** (not headers).
- `FeedListEntry` is sealed: `Header` (not focusable) or `Item` (focusable).
- `entriesIndexForFocus(entries: List<FeedListEntry>, feedIndex: Int) → Int` maps from the flat focus index to the entries-list index, skipping headers on the fly.
- When the LazyColumn renders a row and needs to know "am I focused?", it calls `entriesIndexToFeedIndex()` to map back from entries-list position to feed index for the comparison.

**Flat-item invariant test:** `FeedListBuilderTest.flat-item order matches focus traversal order` pins that the sequence of items (after dropping headers) matches the order `WallFocusModel` will traverse. 18 tests in `FeedListBuilderTest` verify grouping, sorting, freshness boundaries, and the mapping functions.

## Per-section freshness — C3 applied per source

**What it is:**

Each section has a `SectionFreshness` enum computed from the **newest item in that section**:
- **Fresh:** newest item < 2h old. The operator can trust items in this section as current.
- **Warm:** newest item < 12h old. Items are aging; the source may be slow to publish.
- **NotUpdating:** newest item ≥ 12h old. The operator should know this source has stalled (helper's poller may have failed for this source, or the source itself stopped publishing).
- **Unknown:** no items in the section (shouldn't happen for real sources; rendered if an empty section slips through).

**Trust Bar C3 at the section layer:**

In the navigation chapter, the entire feed's staleness was signalled once at the pane header. Stage 8 distributes that honesty per source: when BBC is fresh but NPR hasn't updated in 16 hours, the operator sees "BBC: 5m (green chip)" and "NPR: not updating (red chip)" side by side. No ambiguity — each source shows its own truth.

**Implementation:**

- `classifyFreshness(newestAgeMs, staleAfter=2h, deadAfter=12h)` in `FeedListBuilder` (configurable for testing).
- `SectionHeader()` Composable renders the freshness chip with color and text from the enum.
- The pane memoizes the entries (and thus freshness classification) on `state.snapshot` identity, so chips refresh whenever a new poll lands. Between polls, chips age naturally (baked-in at build time via `System.currentTimeMillis()`).

**Future polish:** a follow-on could add a periodic re-classification to age chips smoothly without a network poll. For now, the operator's primary need — "is this source flowing or stalled?" — is answered at each poll.

## The channel-picker companion win — "LIVE 3/8" / "OFFLINE 2/5"

The operator's "sections for live/offline" feedback applied to the feed spawned a small companion idea at the cycler: the same grouping applied at the picker orientation layer.

**What it is:**

The channel list is sorted live-first by the call site, so live channels form a contiguous prefix and offline ones a contiguous suffix. `pickerGroupAt(channels: List<Channel>, cursor: Int) → PickerGroup` translates the cursor position into:
- `isLive: Boolean` — is the cursor in the live group?
- `positionWithinGroup: Int` — 1-based position within that group.
- `groupSize: Int` — total count of channels in that group.

The picker's header renders: "SLOT 2  ·  LIVE 3/8" (or "OFFLINE 2/5"), so the operator sees at a glance which group they're cycling through and where within it.

**Why?**

From the couch, cycling through a list of 8 channels is disorienting without orientation. The chip carries the same "live/offline sections" language the feed now uses, so the wall's vocabulary stays consistent. Test `PickerGroupTest` (6 cases) pins the position math at boundaries.

## A1 boundary — adversarially re-verified

**Closed door from the navigation chapter:** the feed-expand path renders via native Compose `Text` with HTML-stripped plain text. No WebView, no fetch, no markup render.

**What the feed-restructure adds:**

- Pure Kotlin grouping logic (zero external input surface).
- Section headers as native Compose `Text` (inert labels, no web load).
- Freshness chips as `Text` widgets with enum-driven colors and strings (no network, no parse).

**Threat surface change:**

- **New web-fetch?** No. `FeedListBuilder` reads the snapshot's items in memory; no new poller, no new endpoint.
- **New WebView?** No. The restructure only changes how items are laid out; rendering is still `Text` widgets.
- **New HTML render?** No. The summary remains HTML-stripped plain text from the helper.

**Confirmation:** the code review confirms zero A1-model changes. The flat-item invariant preserves the focus path (WallFocusModel → entriesIndexForFocus → LazyColumn scroll → FeedPane → Text widget), and no new input is accepted by the feed layer.

## What's deferred — the roadmap

- **Feed filtering and search UI** — separate future chapter. The item-level operations (SELECT = expand, BACK = collapse) are now in place; filtering / search for a subset of items is a future layer on top.
- **Section collapse / jump-by-source** — the operator currently scrolls through sections by pressing DOWN on items. Collapsing a section or jumping directly to "BBC" is deferred; the current item-focus through sections is sufficient for v1.
- **Configurable feed font and width** — UX & configuration work, separate push.
- **Real-source addition** — BACKLOG item F. The prototype uses a fixed set of sources (BBC, NPR, Guardian, Al Jazeera). Adding a 5th or 6th source is a future feature; the grouping logic scales to any source count.
- **Kiosk story, boot receiver, long-uptime watchdog** — all deferred to the new MyMTS box.
- **QR-to-phone richer reading** — logged as a closed-door-compatible alternative to in-app HTML reading; not built here.

## Open questions for the at-the-box feel-test

The grouping and freshness logic is locked and tested. The open questions are the *felt experience* on the actual Onn remote from the couch, specific to the restructure:

- **Four sources at 28% width readable?** The feed pane is ~28% of the wall. At standard 10-foot viewing distance, can the operator read 4 section headers and items comfortably? Or does width reduction make text too small?
- **Freshness chip color carry from 10ft?** The `SectionFreshness` enum drives chip color (Fresh = green, Warm = orange, NotUpdating = red). Does the color carry from across the room, or do colors wash out? 
- **Eye-find: do items land in the right section quickly?** The operator knows "I'm looking for BBC"; does the alphabetical grouping let them find the section quickly, or does section order feel arbitrary?
- **Item expansion within sections:** when an item is focused and expanded, does the expansion feel natural within the section context, or does it disrupt the section's visual rhythm?
- **Freshness chip stability:** between polls, chips age (baked in at build time). Does the chip appear to flicker on a new poll, or does the update feel smooth?

The OPERATIONS section carries the checklist for these. The chapter is closeable once the operator confirms the feel; any tweaks become a small follow-on.

## Standing rules at this stage

- **unrelated host services: never touched.**
- **Helper service:** non-root, read_only, cap_drop ALL, dedicated bridge — unchanged.
- **WyzeGrid** stays untouched on `.182` until the operator completes the at-the-box feel-test.
- **App test count:** 24 new tests (18 in `FeedListBuilderTest` covering grouping + freshness + flat-item invariant + focus mapping; 6 in `PickerGroupTest` covering live/offline position math). Total app test count: **175**.
- The grouping + freshness logic is **pure Kotlin**, compiled with zero Compose dependencies — it runs in plain `org.junit.Test` without a framework.
