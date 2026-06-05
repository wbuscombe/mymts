# Finding 11 — Ticker real data: markets + sports modes

> **Status: BUILT + TESTED 2026-06-05.** The ticker no longer shows only SAMPLE data — it now carries REAL keyless markets data (Stooq indices/FX/gold + CoinGecko BTC/ETH) and a REAL sports mode (ESPN public scoreboard JSON for MLB/NFL/NBA/NHL), alternating between the two on a calm timer via `HelperTickerSource` behind the existing `TickerSource` interface. Symbols with no clean free keyless source (Brent, WTI, 10Y UST) stay honest SAMPLE — never faked. **No API key was needed for anything, so the helper holds no new secret.** Both adversarial verifiers (honesty; SSRF/no-secret) returned not-refuted. Helper suite 164 passed, app suite 217 passed. Staged for the at-the-box feel-test.

## Why this chapter happened

**Operator feedback (item D + item G):** the ticker was built in Stage 3 with a `TickerSource` interface and a `SampleTickerSource` that shows plausible-but-clearly-labelled placeholder values behind SAMPLE pills — deliberately, because no real data source was wired yet. Item D asked for the ticker to alternate between a markets mode and a curated sports mode; item G named the missing enabler (a sports-data source). This chapter is the rework the interface was designed to accept.

The chapter prompt mandated an **investigate-first** approach (the channel-resolution pattern): find what's actually free-feedable before building modes, and surface any key-required-vs-keyless tradeoff to the operator as a checkpoint.

## Source investigation (the checkpoint)

Every candidate was probed **live through the helper's real SSRF-safe fetcher** (not a browser, not a mock) so the investigation reflected exactly what the production path would see.

### Markets — what's free + keyless
| Source | Coverage | Verdict |
|---|---|---|
| **Stooq** CSV (`stooq.com/q/l/?s=…&f=sd2t2ohlcvn&e=csv`) | indices (^SPX ^DJI ^NDQ ^FTM ^DAX ^NKX ^HSI), FX (EURUSD GBPUSD USDJPY), gold (XAUUSD) | **USE** — keyless, tiny CSV, direction from open-vs-close |
| **CoinGecko** free (`api.coingecko.com/api/v3/simple/price`) | BTC, ETH spot + 24h change | **USE** — keyless, direction from change sign |
| **Yahoo Finance** chart endpoint | indices | **REJECT** — works today but unofficial/ToS-gray and blocks datacenter IPs over time; Stooq covers the same keylessly |
| Brent, WTI, 10Y UST | — | **SAMPLE-ONLY** — no clean free keyless source verified; stay `is_sample=true`, never faked |

### Sports — what's free + keyless
| Source | Coverage | Verdict |
|---|---|---|
| **ESPN** public scoreboard JSON (`site.api.espn.com/apis/site/v2/sports/SPORT/LEAGUE/scoreboard`) | MLB, NFL, NBA, NHL | **USE** — keyless, public, read-only, rich (teams, scores, status) |
| **TheSportsDB** test-key "3" | leagues by day | evaluated; ESPN richer + cleaner, so not used |

### The checkpoint outcome
The anticipated blocker (no free sports source without a key) **did not materialize** — ESPN is keyless. The only judgment was ToS posture: Stooq/CoinGecko free tiers are non-commercial (the operator's personal wall qualifies), and ESPN's scoreboard JSON is public-but-undocumented (it could change or vanish; honest staleness handles that). **No source needs an API key, so the helper-holds-a-secret line was deliberately not crossed.** The operator was presented all of this and chose **"build both"** before any mode code was written.

## What got built

- **Helper `mymts_helper/ticker/`** — `markets.py` (Stooq + CoinGecko strict parsers + the real/sample snapshot builder), `sports.py` (ESPN scoreboard parser + sample slate + truthful "no games" state), `pollers.py` (two async pollers holding an in-memory latest snapshot), `api.py` (`/api/ticker/markets` + `/api/ticker/sports`). Wired into the app lifespan; phantom mode seeds sample and starts no pollers.
- **App** — `HelperTickerSource` polls both endpoints and alternates modes on a calm timer (markets ~22 s, sports ~14 s); `HelperClient.parseTicker` pins the schema version and fails safe; `TickerEntry.Direction.NONE` lets sports scores render with no arrow; `WallScreen` swapped to the real source. `SampleTickerSource` stays as the honest fallback.

### Why in-memory, not a DB table
Ticker data is **ephemeral** — only the latest snapshot matters, there's no history to keep. Holding it in the poller object (read on the event loop by the sync endpoint) avoided a schema migration, a retention sweep, and — pointedly — the cross-thread sqlite exposure the feed path had (and which the previous chapter fixed). A restart simply wipes the snapshot; the next poll refills it. Fail-closed by construction.

### The honesty model (C3), end to end
- Each entry carries `is_sample`. The **helper** decides it (real where fetched this cycle, sample for unsupported symbols); the **TV** renders it verbatim and never upgrades a sample entry to live.
- The markets snapshot is always the full canonical symbol list — real entries drop the pill, the rest keep it — so the marquee shape is stable while truthfulness is per-symbol.
- The envelope's `stale` flag fires only once *real* data has aged (15 min); an all-sample pre-poll snapshot is honest sample, not stale.
- **Unreachable ≠ frozen-live.** If the helper is unreachable, markets fall back to the SAMPLE slate (not the last real numbers re-shown as current); sports show a truthful "scores unavailable" line (`is_sample=false` — it's a true statement, not fabricated sample data).
- Stooq occasionally throttles rapid repeats; per-source isolation drops that cycle to honest sample and recovers next cycle — no error surfaced, no frozen data.

## Verification

- **Defensive parser tests** (helper): malformed CSV rows / "N/D" / binary junk → dropped, never raises; CoinGecko junk/non-dict → empty; ESPN malformed events skipped, non-numeric scores sanitized, junk body → `[]`.
- **Endpoint + phantom tests**: envelope shape pinned; **blocked-egress** test asserts `/api/ticker/markets` returns 200 all-sample (never 500, never fabricated) when every fetch is refused; phantom serves all-sample markets + sample sports slate.
- **App tests**: `parseTicker` real-vs-sample fidelity + fail-safe `is_sample` default + unknown-direction→FLAT; `HelperTickerSource.entriesFor` honest-fallback rules in every reachable/unreachable combination.
- **Adversarial verifiers** (workflow, refute-first, both **not refuted**): (1) the ticker never presents sample/stale data as live-real in any path; (2) the new sources go through the unchanged SSRF-safe fetcher, add no secret, and the parsers are strict/fail-closed.

## What's deferred

- **Per-team / per-league curation UI** — a sensible default league set (MLB/NFL/NBA/NHL) + the mechanism ship now; a UI for the operator to pick leagues/teams is a follow-on. Until then the set is edited in `ticker/sports.py::DEFAULT_LEAGUES`.
- **Sample-only market symbols** — Brent, WTI, 10Y UST remain honest SAMPLE; revisit if a keyless source surfaces (Stooq may cover oil/rates under symbols worth re-checking).
- **Paid sources** — out by policy (keyless-or-free-tier only).
- **A keyed source** — if one is ever wanted, the `.env` secret discipline is the documented path (gitignored, runtime-injected, never logged); not taken here.

## Open questions for the at-the-box feel-test

- Does the markets↔sports rotation feel calm (not frantic) at the chosen dwell times, and are the dwell times long enough to read?
- Do the SAMPLE pills on Brent/WTI/10Y read clearly against the real entries — is the mixed real+sample row honest *and* legible at 10 ft?
- Are the sports score lines (`AWY 4–6 HOM · Final`) legible and useful at a glance, or too dense?
- In an off-season / no-games window, does the "no games right now" line read sensibly, or should the rotation skip an empty sports mode?

## Standing rules at this stage

- **unrelated host services: never touched.** Helper deploy uses its own bridge network, never the VPN container.
- **Helper: non-root, read_only, cap_drop ALL, dedicated bridge** — unchanged; no new secret.
- **A1:** every external response (Stooq, CoinGecko, ESPN) treated as hostile — single SSRF-safe fetcher, strict fail-closed parse, bounded.
- **No secrets / no absolute paths** committed or logged.
