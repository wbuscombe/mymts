# Finding 14 — Curation & preferences pass (sports / ticker news / source toggles)

> **Status: BUILT + TESTED 2026-06-06, with explicit FEEL-TEST caveats.** The "tune what I see" controls now live in the settings menu: sports-league curation for the ticker, news as an optional third ticker mode, and (reused from the feed-filtering chapter) feed-source toggles. App suite 241 green (+8). All operate on already-fetched inert plain text — no new fetch, no web, no faked data (A1/C3 held). **Some choices are deliberately flagged for the operator to revisit after using the wall on real hardware — see the FEEL-TEST list below.**

## Why this chapter happened

The data plumbing exists (real markets+sports ticker, 13 feed sources, channel lineup); the operator wanted control over *what* flows through it. The BACKLOG "Curation & preferences pass" entry scoped three pieces — A sports curation, B ticker-news parameters, C feed-source toggles. This builds them, in the settings surface.

## A — Sports curation (league-level, TV-side)

`WallSettings.hiddenLeagues` is a denylist; the ticker's sports mode drops entries for hidden leagues (`HelperTickerSource.filterLeagues`, case-insensitive on the entry's league symbol). **Filtering is TV-side** — the helper keeps serving all leagues, so per-device curation needs no helper state. This is the deliberate choice that **avoids the cross-platform-profiles architecture fork** (per-device prefs on the helper): the helper stays client-agnostic, the TV filters. Status lines ("no games right now") aren't leagues, so they survive a denylist; if curation empties the list, the honest "scores unavailable" line shows — never a faked game.

Offered leagues are the helper's default scoreboard set (MLB/NFL/NBA/NHL, `CURATED_LEAGUES`, kept in sync with `ticker/sports.py::DEFAULT_LEAGUES`).

## B — Ticker news (third rotation mode, default OFF)

When `tickerNewsEnabled` is on, the ticker rotates **markets → sports → news** (`HelperTickerSource.nextMode` — a pure 2-cycle when off, 3-cycle when on). News entries come from `HelperTickerSource.newsEntries(feedItems, hiddenSources, cap)`: **newest-first headlines from the operator's non-hidden feed sources** (reusing the Stage 12 source denylist), capped at 12, drawn from the feed the wall already polls (no duplicate fetch). Each entry is a real headline (`isSample=false`), inert plain text, `Direction.NONE` (no arrow — a headline has no up/down).

**The honest-engineering line on "breaking news":** RSS cannot reliably flag urgency. So this chapter **refuses to fake breaking-news/urgency detection** — it builds the achievable honest version (source-subset + newest-first) and logs true urgency detection as a separate future item that would need a real signal source. No fabricated "BREAKING" pills, no invented priority.

## C — Feed-source toggles

Built in the feed-filtering chapter (Stage 12) as the `hiddenSources` denylist. Reconciled + reused here — the ticker-news source set is the same `hiddenSources`, so the operator configures sources once. Not rebuilt.

## Where it lives + focus model

All controls are rows in `SettingsOverlay` ("Ticker news" on/off cycle, "Sports leagues…" opener) plus the `SourceFilterOverlay` toggle list (parameterized with a title, reused for leagues). They are **modal overlays** — `WallFocusModel`'s zone graph is unchanged, so the no-trap invariants hold and the navigation tests pass unmodified. Curation is pushed into the running `HelperTickerSource` via `setCuration(hiddenLeagues, newsEnabled, news)` from a `WallScreen` effect that re-fires when settings or the feed change.

## ⚠️ FEEL-TEST-CAVEAT items — build now, revisit after hands-on use

The operator is cranking this pre-hardware deliberately. These are built and configurable, but the *right* answer is clearer after using the wall:

1. **Sports curation granularity — league vs team.** League-level toggles ship now. **Team-level favorites** (the operator's Chicago context: Blackhawks / Cubs / White Sox / Bears / Bulls) are deferred — building a team-picker before seeing scores actually flow risks the wrong granularity. *Revisit:* after a session watching the sports ticker, decide whether league toggles suffice or favorite-teams pinning is wanted.
2. **News in the ticker — whether, and from where.** Default OFF. *Revisit:* after using the wall, confirm the operator wants news riding the ticker at all, the rotation cadence feels calm, and which sources feed it (currently the same set as the feed pane — a dedicated, narrower ticker-news subset is a possible follow-on if a 13-source ticker reads as noisy).
3. **Rotation dwell times** (markets 22s / sports 14s / news 18s) — tuned by guess; confirm they read calm-not-frantic at 10 ft.

## What's deferred (BACKLOG)

- Team-level sports curation (FEEL-TEST follow-on).
- True breaking-news / urgency detection — needs a real signal source; not faked.
- A dedicated ticker-news source subset distinct from the feed denylist.

## Standing rules at this stage

- A1 held (curation operates on already-fetched inert plain text; no new fetch/web/markup).
- C3 held (honest "no games"/"no headlines"; no faked/urgency data).
- unrelated host services never touched; `.182`/WyzeGrid untouched; no secrets/absolute-paths.
