# Finding 19 — Sports ticker overhaul: BottomLine game cards, ESPN status blocks, league-cycling flip

> **Status: BUILT + TESTED + DEPLOYED 2026-06-10.** The sports portion of the ticker moved from a run-together `·`-stream to discrete **game cards** with **strong dividers**, a weighted **colour-coded status block** (live / final / upcoming read at a glance), and **league-grouped flip** motion that auto-cycles a configured **pool** of leagues (markets + news keep scrolling). Honest-degradation throughout. Decisions: flip = sports only; pool = the in-menu "Sports leagues" filter (enabled = pool).

## ESPN keyless-endpoint coverage (probed live 2026-06-10)

All 12 of the operator's leagues were probed against `site.api.espn.com/.../scoreboard`:

| League | Endpoint | Shape | Status |
|---|---|---|---|
| NFL | `football/nfl` | 2-competitor score+clock | ✅ full |
| NCAAF | `football/college-football` | 2-competitor | ✅ full |
| UFL | `football/ufl` | 2-competitor | ✅ full |
| NBA | `basketball/nba` | 2-competitor | ✅ full |
| WNBA | `basketball/wnba` | 2-competitor (live game seen) | ✅ full |
| NCAAB | `basketball/mens-college-basketball` | 2-competitor | ✅ full |
| MLB | `baseball/mlb` | 2-competitor | ✅ full |
| NHL | `hockey/nhl` | 2-competitor | ✅ full |
| UFC | `mma/ufc` | 2 fighters, fight-specific fields (weight class / round / KO·decision) | ⚠️ staged |
| PGA | `golf/pga` | **147 competitors** — a leaderboard, not a matchup | ⚠️ staged |
| Tennis | `tennis/atp`, `tennis/wta` | **0 competitors** in the standard field — sets/games nested differently | ⚠️ staged |
| F1 | `racing/f1` | **22 competitors** (drivers) — race/standings | ⚠️ staged |

**Shipped: the 8 team leagues** (they map cleanly onto the game-card + status-block model). **Staged: UFC / PGA / tennis / F1** — they need bespoke card shapes (fight card / leaderboard snippet / match-sets / race) and are in BACKLOG with the data-shape notes above. Honest: the staged leagues are **omitted**, never faked.

## The model

- **Helper** (`ticker/__init__.py`, `ticker/sports.py`): a `GameDTO {league, away, away_score, home, home_score, state, status}` added to `TickerEntryDTO` as an optional `game`. **Additive** — markets/news entries omit it and stay byte-identical to schema v1 (no bump; the TV pins the contract). The sports pool expanded to the 8 team leagues; **live-first** ordering within each league block (`in` → `pre` → `post`, stable; no team favouritism). Empty leagues still omitted. The flat `display` string is kept as a fallback.
- **App** (`HelperClient.parseTicker`, `TickerGame`, `SportsTicker`, `TickerStrip`): parses the structured game defensively (a partial game → `null` → the row falls back to its `display` string, never a half-card). `SportsTicker` (pure, unit-tested) groups games into league blocks, classifies status (`LIVE`/`FINAL`/`UPCOMING`), formats the ESPN-convention text (`5:42 - 1st` → `5:42 1ST`, `Final` → `FINAL`), and advances the flip index.

## The UI

- **Game cards + strong dividers (goal 1):** each game is a discrete bordered, dark card — the boundary is the card itself, not a `·`. Team abbreviations + scores in a mono face; no logos (ESPN dropped them for legibility).
- **ESPN status block (goal 2):** a weighted, **colour-coded** chip clearly separated from the score — **LIVE = bright green**, **FINAL = muted grey**, **UPCOMING = neutral** — showing `5:42 1ST` / `FINAL` / start time. Final-vs-live reads instantly.
- **Flip motion (goal 3 / §4):** the sports mode HOLDS a league's card-set (~6.5 s) then flips (vertical slide + fade) to the next league, cycling the pool; a league marker pill heads each block. Markets + news keep their scrolling marquee unchanged. The sports mode dwell was lengthened (≈42 s) so the flip cycles the pool a lap.
- **Pool config (§1):** the existing in-menu **"Sports leagues…"** filter was expanded to all 8 — the enabled set is the auto-cycling pool (set once, not toggled during use). Filtering is TV-side; the helper serves the slate.

## Honest degradation (C3)

No fabricated score/status/game anywhere. A sports mode with no games falls back to the honest scrolling "scores unavailable" line; a stale/sample entry still carries its flag; the staged leagues are omitted. Keyless public ESPN endpoints only — no API key crosses the helper.

## Tests

Helper: structured-game payload, live-first ordering, the 8-league pool, additive wire-omission (188 green). App: `SportsTickerTest` (blocking, status classification, ESPN formatting, flip-index wrap/guard), `HelperClientTickerParseTest` (game parse, markets-omit, malformed→null fallback). Focus model unaffected (the ticker isn't a focus zone).

## Standing rules

Same release key. `.182`/`.158` devices untouched. unrelated host services never touched. The locked panel-fit config (Fit scale 80% / Vertical stretch 110%) untouched. Keyless endpoints; no new secret.
