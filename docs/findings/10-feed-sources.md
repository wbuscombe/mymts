# Finding 10 — Feed sources expansion: 13-source balanced set with SQLite cross-thread fix

> **Status: BUILT + TESTED 2026-06-05.** The helper seed now feeds 13 reputable public RSS sources (original 4 + 9 additions) to the MyMTS helper. Every candidate was verified by fetching through the helper's REAL SSRF-safe fetcher and parsing through the REAL defensive parser before seeding; only feeds returning valid plain-text items were kept. The seeded set deliberately balances center-left/international voices (original: BBC, Al Jazeera, Guardian, NPR) with right-of-center and center perspectives (The Dispatch, National Review, Reason; PBS NewsHour, CSM, networks, Politico, Bloomberg Markets) — a defensible "balanced + factual" posture, not a lean. AP (official feed broken; only unofficial mirror works) and Reuters (discontinued public RSS) were rejected on provenance honesty grounds. A concurrent SQLite cross-thread fix resolved the intermittent ProgrammingError ("SQLite objects created in a thread can only be used in that same thread") by moving connection open/close into the sync route body (one thread, no boundary crossing). Full helper suite: 140 passed. Staged for the at-the-box feel-test.

## What this chapter does (and what it doesn't)

**Does:**
- Adds **9 new reputable RSS sources** to `feeds/seed.json`, keeping the original 4. Total seeded set: 13.
- Verifies every candidate by **fetching through `mymts_helper.fetcher.fetch`** (real SSRF-safe fetcher) and **parsing through `mymts_helper.feeds.parser.parse`** (real defensive parser). Only feeds returning valid plain-text items were committed.
- Documents the **balance rationale:** the original 4 lean center-left/international; the additions add explicit right-of-center voices + center/public broadcasters so the sectioned feed is a spread, not a lean.
- Explicitly rejects **AP and Reuters with honest reasons** (AP's official feed is broken and only an unofficial mirror works; Reuters discontinued public RSS).
- Keeps an **omitted-but-working set** (ABC, CNBC, The Hill, Axios, The Atlantic, Vox, MarketWatch, Washington Examiner) as operator-swap candidates; each was verified to parse.
- Fixes the logged **SQLite cross-thread bug** (intermittent HTTP 500 on `/api/feed` and `/api/channels`), folded in because more sources stress the feed path.

**Does NOT:**
- Implement per-source bias tags / bias-labeling UI — a future backlog idea. This chapter chooses a balanced set; tags are not built here.
- Touch the SSRF fetcher, parser, store, or response envelope — no change to the verification path.
- Reopen the Ground News deferral (ruled out on no-API/ToS grounds) — this balanced public-RSS set is the sanctioned alternative.
- Collapse the dual-uvicorn-instance listener — logged separately, independent of the cross-thread fix.

## Why this chapter happened

**Operator feedback (2026-06-04 upstairs session, BACKLOG item F):** add reputable, balanced sources so the feed has more breadth — the sanctioned alternative to the ruled-out Ground News request.

The feed restructure (Stage 7) made sources visible and per-source freshness transparent; the configurable layout (Stage 8) let the operator tune how the feed reads. With the structure in place, the obvious next gap was content breadth: the original 4 sources lean center-left and international. This chapter fills that gap with a deliberately balanced set, each source verified through the helper's own defensive path — no third-party aggregator, no shortcuts.

## The final 13-source set: balance rationale

**Original (4, retained):** BBC World, Al Jazeera, Guardian World, NPR World — center-left / international focus.

**Added (9):**
- **Center-right / right perspective:** The Dispatch (center-right), National Review (right), Reason (libertarian).
- **Center / public / networks:** PBS NewsHour, Christian Science Monitor, CBS News, NBC News.
- **DC politics / markets:** Politico, Bloomberg Markets.

**Why this mix?** The original 4 lean center-left and international. The additions deliberately include reputable right-of-center voices so the sectioned feed presents a *spread*, not a lean. Public broadcasters (PBS, CSM) and networks (CBS, NBC) anchor the center; Politico and Bloomberg Markets cover DC politics and markets — core operator interests. The net tally is roughly center-heavy with explicit left (Al Jazeera, Guardian, NPR) and right (National Review, Reason, The Dispatch) representation. That is a defensible "balanced + factual" posture rather than an editorial lean.

## Verification method: real fetcher, real parser, no shortcuts

Before seeding, each candidate was:
1. Fetched through `mymts_helper.fetcher.fetch` (https-only, DNS-pinned, RFC1918/loopback/CGNAT/ULA rejection, bounded body + time, redirects re-validated).
2. Parsed through `mymts_helper.feeds.parser.parse` (feedparser frontend + defusedxml; HTML stripped, entities decoded, plain text only).
3. Confirmed to return ≥1 valid plain-text item with no fatal parse error.

This is the exact path the live helper uses — if a feed fails here, it fails in production, so it was rejected now rather than seeded broken.

## Rejected sources — honest reasons, not seeded

- **AP (Associated Press):** `apnews.com/index.rss` returns a SAXParseException (broken/empty). The only working AP feed is a third-party mirror (`feedx.net`). Labeling a relay "AP" would misrepresent the source's provenance — a small C3-adjacent honesty call — so it was rejected rather than seeded with a caveat.
- **Reuters:** `reutersagency.com/feed` returns HTML, not RSS (Reuters discontinued public RSS years ago). No clean public feed exists.

If either ships a clean public feed in the future, it can be added in a follow-on.

## Omitted-but-working sources: operator-swap candidates

Verified to parse and return valid items, but left out to avoid over-stuffing the sectioned feed (each source is its own section): **ABC News, CNBC, The Hill, Axios, The Atlantic, Vox, MarketWatch, Washington Examiner.** The operator can swap any in by editing `feeds/seed.json` — each is proven to work.

## The SQLite cross-thread fix

**The bug:** intermittent `sqlite3.ProgrammingError: SQLite objects created in a thread can only be used in that same thread` → HTTP 500 on `/api/feed` and `/api/channels` (latent since the Stage 6 TLS baseline).

**Root cause:** `feeds/api.py` and `channels/api.py` used a FastAPI `Depends()` yield-dependency (`_conn`) to open/close the sqlite connection. A sync route runs in Starlette's anyio threadpool, and FastAPI drives the yield-dependency's setup (open) and teardown (`close()`) through two separate `run_in_threadpool` calls that can land on **different** threadpool threads. A sqlite3 connection closed on a different thread than it was opened on raises ProgrammingError.

**The fix:** added `db.connection_scope(path)` — a `@contextmanager` that opens, yields, and closes the connection in one frame. The routes now `with db.connection_scope(db_path) as conn:` **inside the sync route body**, so the whole connection lifecycle stays on the single threadpool thread that runs the route. The fix keeps sqlite's thread guard ON (it does **not** use `check_same_thread=False`).

**Tests:** 2 regression tests in `tests/test_api.py` hammer `/api/feed` and `/api/channels` from 8 client threads × 64 requests and assert all 200 + stable envelope. 2 shipped-seed guard tests in `tests/test_feeds_seeder.py` assert all-https + unique URLs/labels, every entry seeds, and the originals are retained. Full helper suite: **140 passed**.

## Per-source error isolation and freshness, now at scale

The poller's per-source error isolation (one bad source never stalls the others) and the feed-restructure's per-source freshness chips (Fresh / Warm / NotUpdating) now apply to each of the 13 sources. With the feed sectioned-by-source, a source that goes quiet shows honestly at its own section header rather than dragging the whole feed's freshness down. More sources make these properties more valuable, not riskier.

## Connection to the per-source bias-tags backlog

This chapter chooses a balanced set as the *input*; the future "per-source bias/lean tags" idea (logged in BACKLOG, originally raised alongside the Ground News finding) is the enhancement — a home-grown bias-awareness layer the operator could opt into later. Not built here; this is the foundation it would sit on.

## What's deferred

- **Per-source bias tags / labeling UI** — future backlog idea.
- **Dual-uvicorn-instance tidy-up** — separate low-priority BACKLOG sub-item, independent of the cross-thread fix (which is fixed regardless of listener count).
- **AP / Reuters** — reconsider if either ever ships a clean public RSS/Atom feed.

## A1 boundary — unchanged

The expansion is **data, not a new code path**: 13 seeded URLs flow through the same SSRF-safe fetcher and the same defensive parser as the original 4, served to the TV as inert plain text. No WebView, no markup interpretation, no new external input surface. The SQLite fix is a thread-scope correctness change that does not loosen any boundary (SSRF fetcher, parser, and response envelope all unchanged). Adversarially reverified — see `docs/THREAT-MODEL.md §"Feed-sources expansion + SQLite cross-thread fix (2026-06-05)"`.

## Standing rules at this stage

- **unrelated host services: never touched.** Helper deploy uses its own bridge network, never the VPN container.
- **Helper: non-root, read_only, cap_drop ALL, dedicated bridge** — unchanged.
- **A1:** every source treated as hostile, parsed defensively, served as inert plain text — same parser path, more sources.
- **No secrets, no absolute paths** committed or logged (seed.json is public URLs + labels only).

## Open questions for the at-the-box feel-test

- **Is 13 sections too many to skim?** At the chosen feed width + font scale, does the operator comfortably see a few source headers per screen, or does it feel like a lot of scrolling?
- **Does the balance feel right?** With left, center, and right voices visible, does the mix read as fair, or is any perspective over/under-represented for the operator's taste?
- **Freshness credibility at scale:** with 13 per-source freshness chips, is "which sources are live vs. quiet" clear at a glance?
- **Any obvious gaps?** Reputable sources the operator expected that we omitted (failed verification, or in the omitted-but-working set the operator can swap in).

The feed-restructure feel-test checklist in `docs/OPERATIONS.md` covers the skim experience; this chapter adds no new at-the-box step beyond "does the richer, balanced feed read well." The chapter is closeable once the operator feels the balance and breadth are right.
