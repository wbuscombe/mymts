"""Direct orchestration tests for the RSS `FeedPoller` sweep.

Closes the feed half of review finding API-3: `feeds/store.py` is well
covered (`test_feeds_store.py`) and `feeds/parser.py` too
(`test_feeds_parser.py`), but `FeedPoller.poll_once` / `_poll_one` — the
sweep that ties fetch → parse → store together with ABSOLUTE per-source
error isolation — had no direct test. This module drives a real sweep over
real `sources` rows in a temp sqlite DB with the fetch boundary stubbed,
asserting the isolation + bookkeeping invariants.

Injection idiom (same as test_ticker_pollers.py): the fetcher's injectable
async `resolver` + `respx`. A public-IP resolver (`8.8.8.8`) lets a fetch
reach a respx route whose body we choose (realistic RSS XML, an HTTP error,
or a garbage body); the bodies flow through the REAL `feeds.parser` and
`feeds.store`, so a shape change upstream would change the assertions — these
are not mocked-green stubs. No real network is touched.

Honesty note (API-2, separate unfixed finding): a 200 response whose body is
garbage / not a feed parses to ZERO items and the poller records a
fetch_SUCCESS (status < 400, no exception). We pin that CURRENT behavior here
rather than assert the /health-failure the code does not yet do.
"""

from __future__ import annotations

from pathlib import Path

import httpx
import respx

from mymts_helper import db
from mymts_helper.feeds import store
from mymts_helper.feeds.poller import FeedPoller

# ---------------------------------------------------------------------------
# resolvers + realistic bodies
# ---------------------------------------------------------------------------


async def _public_resolver(host: str) -> list[str]:
    """Public IP → the fetcher proceeds to httpx, which respx intercepts."""
    return ["8.8.8.8"]


def _rss(items: list[tuple[str, str]]) -> bytes:
    """A minimal-but-real RSS 2.0 body (the shape feedparser + the real parser
    consume — title/link/guid/description/pubDate per item)."""
    rows = "".join(
        f"<item><title>{title}</title><link>{link}</link>"
        f"<guid>{link}</guid><description>Summary for {title}</description>"
        f"<pubDate>Wed, 11 Jun 2026 10:00:00 GMT</pubDate></item>"
        for title, link in items
    )
    return (
        b'<?xml version="1.0" encoding="UTF-8"?>'
        b'<rss version="2.0"><channel><title>Test Feed</title>'
        + rows.encode("utf-8")
        + b"</channel></rss>"
    )


GOOD_RSS = _rss([("First headline", "https://a.test/1"), ("Second headline", "https://a.test/2")])
GARBAGE_BODY = b"\xff\xfe this is not a feed at all <<< broken &nope; >>>"


def _make_db(tmp_path: Path) -> Path:
    p = tmp_path / "feeds.db"
    db.migrate(p)
    return p


def _add_source(p: Path, url: str, label: str) -> int:
    conn = db.connect(p)
    try:
        return store.upsert_source(conn, url=url, label=label)
    finally:
        conn.close()


def _source(p: Path, source_id: int) -> store.SourceRow:
    conn = db.connect(p)
    try:
        return next(s for s in store.list_sources(conn) if s.id == source_id)
    finally:
        conn.close()


# ===========================================================================
# (a) good RSS → items stored (with dedup) + record_fetch_success
# ===========================================================================


@respx.mock
async def test_good_rss_stores_items_and_records_success(tmp_path: Path) -> None:
    p = _make_db(tmp_path)
    url = "https://a.test/feed"
    sid = _add_source(p, url, "A")
    respx.get(url).mock(return_value=httpx.Response(200, content=GOOD_RSS))

    poller = FeedPoller(p, resolver=_public_resolver)
    await poller.poll_once()

    conn = db.connect(p)
    try:
        rows = store.recent_items(conn)
        assert {r["title"] for r in rows} == {"First headline", "Second headline"}
        assert store.total_items(conn) == 2
    finally:
        conn.close()

    s = _source(p, sid)
    assert s.last_success_at is not None
    assert s.last_error is None
    assert s.error_count == 0
    assert poller.last_poll_at is not None


@respx.mock
async def test_good_rss_dedups_across_two_cycles(tmp_path: Path) -> None:
    """A second sweep over the same feed inserts nothing new (per-(source,guid)
    dedup in `insert_items`) — the orchestration doesn't double-store."""
    p = _make_db(tmp_path)
    url = "https://a.test/feed"
    _add_source(p, url, "A")
    respx.get(url).mock(return_value=httpx.Response(200, content=GOOD_RSS))

    poller = FeedPoller(p, resolver=_public_resolver)
    await poller.poll_once()
    await poller.poll_once()  # identical body — every item is a dedup no-op

    conn = db.connect(p)
    try:
        assert store.total_items(conn) == 2  # still 2, not 4
    finally:
        conn.close()


# ===========================================================================
# (b) per-source isolation: one source fails, others unaffected, sweep continues
# ===========================================================================


@respx.mock
async def test_http_error_records_failure_on_that_source_only(tmp_path: Path) -> None:
    """One source returns HTTP >=400 → record_fetch_failure on THAT source
    only (reason 'http_<code>'); the healthy source in the same sweep stores
    normally and the sweep is not aborted. Absolute per-source isolation."""
    p = _make_db(tmp_path)
    bad_url = "https://bad.test/feed"
    good_url = "https://good.test/feed"
    bad_id = _add_source(p, bad_url, "Bad")
    good_id = _add_source(p, good_url, "Good")

    respx.get(bad_url).mock(return_value=httpx.Response(500))
    respx.get(good_url).mock(return_value=httpx.Response(200, content=GOOD_RSS))

    poller = FeedPoller(p, resolver=_public_resolver)
    await poller.poll_once()

    bad = _source(p, bad_id)
    good = _source(p, good_id)
    # Failing source: failure recorded, no success, reason carries the status.
    assert bad.error_count == 1
    assert bad.last_error == "http_500"
    assert bad.last_success_at is None
    # Healthy source in the SAME sweep is untouched by the neighbour's failure.
    assert good.error_count == 0
    assert good.last_success_at is not None
    assert good.last_error is None
    conn = db.connect(p)
    try:
        assert store.total_items(conn) == 2  # only the good feed's items
    finally:
        conn.close()


async def test_fetch_error_records_failure_and_does_not_abort_sweep(tmp_path: Path) -> None:
    """A FetchError (here: SSRF guard blocks the host) is caught and recorded
    as a 'fetch:' failure on that source; a later source in the sweep still
    runs. Uses a per-host resolver so one host is blocked and one is allowed."""
    p = _make_db(tmp_path)
    blocked_url = "https://blocked.test/feed"
    ok_url = "https://ok.test/feed"
    blocked_id = _add_source(p, blocked_url, "Blocked")  # lower id → polled first
    ok_id = _add_source(p, ok_url, "OK")

    async def _split_resolver(host: str) -> list[str]:
        # blocked.test → loopback (SSRF guard rejects), ok.test → public.
        return ["127.0.0.1"] if host == "blocked.test" else ["8.8.8.8"]

    with respx.mock:
        respx.get(ok_url).mock(return_value=httpx.Response(200, content=GOOD_RSS))
        poller = FeedPoller(p, resolver=_split_resolver)
        await poller.poll_once()

    blocked = _source(p, blocked_id)
    ok = _source(p, ok_id)
    assert blocked.error_count == 1
    assert blocked.last_error is not None and blocked.last_error.startswith("fetch:")
    assert blocked.last_success_at is None
    # The blocked source did NOT abort the sweep — the next source succeeded.
    assert ok.error_count == 0
    assert ok.last_success_at is not None


# ===========================================================================
# (c) garbage body: no crash, no garbage rows — pin CURRENT behavior
# ===========================================================================


@respx.mock
async def test_garbage_body_stores_no_rows_and_does_not_crash(tmp_path: Path) -> None:
    """A 200 with a non-feed / bozo body → the strict parser yields ZERO items,
    so NO garbage rows are stored and the sweep does not raise.

    CURRENT behavior pinned (API-2 is a separate, unfixed finding): because
    the HTTP status is < 400 and no exception is raised, the poller records a
    fetch_SUCCESS even though the body was unparseable. We assert that real
    contract rather than a /health-failure the code does not yet implement."""
    p = _make_db(tmp_path)
    url = "https://junk.test/feed"
    sid = _add_source(p, url, "Junk")
    respx.get(url).mock(return_value=httpx.Response(200, content=GARBAGE_BODY))

    poller = FeedPoller(p, resolver=_public_resolver)
    await poller.poll_once()  # must not raise

    conn = db.connect(p)
    try:
        assert store.total_items(conn) == 0, "no garbage rows must be stored"
    finally:
        conn.close()

    # Pinned current behavior: 200 + zero items is recorded as a SUCCESS.
    s = _source(p, sid)
    assert s.last_success_at is not None
    assert s.error_count == 0
    assert s.last_error is None


@respx.mock
async def test_html_error_page_body_stores_no_rows(tmp_path: Path) -> None:
    """A common real failure: a 200 whose body is an HTML error page (a bot
    wall / CDN block) rather than a feed. The parser finds no entries → no
    rows stored, no crash. (Like the garbage case, current code records a
    success because the HTTP status was 200.)"""
    p = _make_db(tmp_path)
    url = "https://wall.test/feed"
    sid = _add_source(p, url, "Wall")
    respx.get(url).mock(
        return_value=httpx.Response(
            200, content=b"<html><body><h1>403 Forbidden</h1></body></html>"
        )
    )

    poller = FeedPoller(p, resolver=_public_resolver)
    await poller.poll_once()

    conn = db.connect(p)
    try:
        assert store.total_items(conn) == 0
    finally:
        conn.close()
    s = _source(p, sid)
    assert s.last_success_at is not None  # pinned current behavior


@respx.mock
async def test_disabled_source_is_not_polled(tmp_path: Path) -> None:
    """The sweep only touches enabled sources (`list_sources(enabled_only=True)`),
    so a disabled source is never fetched — no items, no fetch bookkeeping."""
    p = _make_db(tmp_path)
    url = "https://off.test/feed"
    sid = _add_source(p, url, "Off")
    conn = db.connect(p)
    try:
        conn.execute("UPDATE sources SET enabled=0 WHERE id=?", (sid,))
    finally:
        conn.close()
    # No respx route registered → if it WERE polled, the fetch would error and
    # respx would raise, failing the test. Silence proves it was skipped.

    poller = FeedPoller(p, resolver=_public_resolver)
    await poller.poll_once()

    s = _source(p, sid)
    assert s.last_fetch_at is None
    assert s.last_success_at is None
    assert s.error_count == 0
