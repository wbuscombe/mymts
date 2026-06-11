# 21 — Individual-Sports Ticker Cards: Research + Feasibility (UFC / PGA / Tennis / F1)

> **Status: DONE — ALL 4 SHIPPED (2026-06-11).** Extends findings/19. The 4 structurally-different sports were re-probed live against ESPN's keyless endpoints, all found data-feasible (tennis more so than findings/19 thought — see below), and then **built + deployed + verified on the panel**: PGA `leaderboard`, UFC `fight`, Tennis `match`, F1 `race`. The shared per-sport `card` payload (helper→app, additive to schema v1) + the bespoke per-`kind` composables are in `CHANGELOG` (2026-06-11 individual-sports cards). The per-sport shapes + the architecture sketch below are the as-built record.

## Why a checkpoint (honest)

The ticker's structured payload today is `GameDTO(league, away, away_score, home, home_score, state, status)` — two competitors with scores. None of UFC/PGA/tennis/F1 fit that: a fight card is N fights of 2 athletes, a leaderboard is ~150 players ranked by score-to-par, a tennis match is set linescores, a race is a session/finishing-order. Shipping them properly needs (a) a new tagged per-sport card payload serialized helper→app, (b) classification so each forms its own ticker page (not lumped into news), and (c) 4 bespoke card composables — then deploy + on-device verify each. That's a clean multi-commit build; per the prompt's own rule ("a clean subset beats four forced cards; don't cram into an under-verified push") it gets its own runway.

## Re-verified ESPN shapes (probed live 2026-06-11, keyless `site.api.espn.com`)

ESPN's keyless scoreboard is already NAS-reachable (the 8 team leagues fetch from the same host), so reachability is not the gate here — the data **shape** is.

### UFC — `mma/ufc/scoreboard` — FEASIBLE (fight card)
- 1 `event` = a fight card ("UFC Freedom 250: Topuria vs. Gaethje"); **`competitions[]` = the individual fights** (7 this card), each with **2 `competitors`** carrying `athlete.displayName`, `winner` (bool), and a per-fight `status` (`pre`/`in`/`post`).
- **Card design:** event name as the marker context + per-fight rows — `Topuria def. Gaethje · KO R2` (post) / `Topuria vs Gaethje · LIVE R3` (in) / `Topuria vs Gaethje · Sat 8 PM` (pre). Show the main card, not every prelim.
- **Verifiable now?** The next card is **upcoming** (6/14) — the *upcoming* row is verifiable now; the *result* row (winner/method) is schema-built but verifies once fights run.

### PGA — `golf/pga/scoreboard` — FEASIBLE + **LIVE-VERIFIABLE NOW** (leaderboard)
- 1 `event` = tournament ("RBC Canadian Open"), 1 `competition` with **147 `competitors`** ranked by `order`, each `athlete.displayName` + `score` (to par, e.g. `-6`), event `status` = `Round 1 - In Progress`.
- **Card design:** a leaderboard *snippet* — `RBC Canadian Open · Theegala -6 · Grillo -6 · Cole -6 (R1)` — tournament + leader(s)/top-N + round. A ticker can't show 147; take the top few by `order`.
- **Verifiable now?** **Yes** — tournament is live (Round 1 in progress). **Recommended first build** (build + verify the leaderboard on the panel immediately).

### Tennis — `tennis/atp/scoreboard` (+ `wta`) — FEASIBLE (match card) — *better than findings/19 thought*
- findings/19 saw the top-level `competitions` empty (correct). **The matches live one level deeper: `event.groupings[].competitions[]`** — e.g. Boss Open grouping[0] has 39 match competitions, each with 2 `competitors` (`athlete.displayName`) + **`linescores`** (per-set, with `tiebreak`) + `status` (`Final`/`in`/`pre`).
- **Card design:** a match card — `Huesler d. Basilashvili 6-4 7-6(3)` (final) / `Alcaraz vs Sinner 6-4 2-1 · LIVE` (in). Filter the draw to in-progress + recent finals + notable upcoming (don't dump all 39).
- **Verifiable now?** Yes — there are Final matches in the current draw. More parse work (nested groupings + per-set linescores) than PGA/UFC.

### F1 — `racing/f1/scoreboard` — FEASIBLE (race card), upcoming-only verifiable now
- 1 `event` = race weekend ("…Barcelona-Catalunya Grand Prix"); **`competitions[]` = the 5 sessions** (`FP1`/`FP2`/`FP3`/`Qual`/`Race`), each with its own `status`. The current weekend is `pre` so all sessions have **0 competitors** (results populate once a session runs). No `standings`/`leaders` key in the scoreboard for a championship fallback.
- **Card design:** upcoming → `Barcelona GP · Race Sun 8 AM`; post-session → finishing order/podium `1. Verstappen 2. Norris 3. Leclerc` (from the Race competition's competitors once run).
- **Verifiable now?** Only the **upcoming-schedule** card (no live session in the current scoreboard). Post-race shape is schema-known but verifies during a race weekend.

## Recommended build order (next session)

1. **PGA** — live-verifiable right now; simplest shape (one ranked list). Build the per-sport card payload + a `LeaderboardCard` composable against it, deploy, verify on the panel. This also lays the shared architecture.
2. **UFC** — fight card on the same architecture (upcoming verifiable now; results when fights run).
3. **Tennis** — match card via `groupings[].competitions[].linescores` (verifiable; most parse work).
4. **F1** — race card (verify the upcoming-schedule path now; finishing-order during a GP weekend).

Architecture sketch: add an optional `card` payload to the ticker entry — `{kind: fight|leaderboard|match|race, title, lines[], state}` — serialized helper→app alongside (not replacing) `game`; classify each `kind` into its own `TickerPaging` page so it gets the pinned league marker + curtain like the team leagues; one bespoke composable per `kind` sharing the ticker's card/marker visual language; honest degradation (off-season / no current events → league skipped, never faked). The leagues then move from the picker's "Coming soon" note into the live toggle list (one per sport as it ships) + the helper's `DEFAULT_LEAGUES`.

## Honesty notes
- All 4 use **keyless public ESPN** (same host as the working 8 team leagues) — no keys, NAS-reachable.
- Off-season / no-current-events for a sport → that league is **skipped** in the rotation (like an empty team league), never shown stale/fake (Trust Bar C3).
- Nothing was shipped this turn beyond this research; the picker already shows these as "Coming soon" (Prompt 2), which stays honest until the cards land.
