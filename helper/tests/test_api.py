"""Contract tests for /api/feed and /api/channels.

These pin the response envelope shape the TV depends on. Changes here
require a coordinated change in the TV's request code and a bump in
`API_SCHEMA_VERSION` if the change is non-additive.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from mymts_helper.app import create_app
from mymts_helper.config import Config


async def _block_all_resolver(host: str) -> list[str]:
    return ["127.0.0.1"]


def _cfg(tmp_path: Path, *, phantom: bool = False) -> Config:
    return Config(
        phantom_mode=phantom,
        port=8091,
        log_level="warning",
        build_sha="dev",
        build_version="0.0.0-dev",
        data_dir=str(tmp_path),
        feed_poll_interval_seconds=3600,
        feed_retention_days=14,
        channel_probe_interval_seconds=3600,
    )


@pytest.fixture
def client(tmp_path: Path):
    with TestClient(create_app(_cfg(tmp_path), resolver=_block_all_resolver)) as c:
        yield c


@pytest.fixture
def phantom_client(tmp_path: Path):
    with TestClient(create_app(_cfg(tmp_path, phantom=True))) as c:
        yield c


# ---- /api/feed ----


def test_api_feed_envelope(client: TestClient) -> None:
    r = client.get("/api/feed")
    assert r.status_code == 200
    body = r.json()
    assert body["schema_version"] == 1
    assert isinstance(body["items"], list)


def test_api_feed_limit_clamp(client: TestClient) -> None:
    # Above the cap (500) — FastAPI should reject with 422 (Query validation).
    r = client.get("/api/feed?limit=999")
    assert r.status_code == 422


def test_api_feed_limit_zero_rejected(client: TestClient) -> None:
    r = client.get("/api/feed?limit=0")
    assert r.status_code == 422


def test_api_feed_sources_envelope(client: TestClient) -> None:
    r = client.get("/api/feed/sources")
    assert r.status_code == 200
    body = r.json()
    assert body["schema_version"] == 1
    assert isinstance(body["sources"], list)


def test_api_feed_items_have_pinned_fields(phantom_client: TestClient) -> None:
    body = phantom_client.get("/api/feed").json()
    assert body["items"], "phantom preload should have items"
    item = body["items"][0]
    for field in ("id", "guid", "source", "source_category", "source_url", "title",
                  "summary", "link", "published_at", "fetched_at"):
        assert field in item, f"missing field: {field}"


def test_api_feed_source_category_groups_outlets(phantom_client: TestClient) -> None:
    """Each feed item carries a `source_category` so the web can group the
    feed-source filter by section (same taxonomy as the channel picker)."""
    from mymts_helper.channels.category import CATEGORY_ORDER
    from mymts_helper.feeds.category import source_category

    body = phantom_client.get("/api/feed").json()
    for it in body["items"]:
        assert it["source_category"] in CATEGORY_ORDER, it["source_category"]
        # The served value matches the pure map for that source label.
        assert it["source_category"] == source_category(it["source"])
    # Spot-check the taxonomy mapping (outlets bucket into the section names).
    assert source_category("BBC World") == "Global News"
    assert source_category("NBC News") == "US News"
    assert source_category("NFL") == "Sports"
    assert source_category("Bloomberg Markets") == "Business"
    assert source_category("Some Unmapped Source") == "General"


# ---- /api/channels ----


def test_api_channels_envelope(client: TestClient) -> None:
    r = client.get("/api/channels")
    assert r.status_code == 200
    body = r.json()
    assert body["schema_version"] == 1
    assert isinstance(body["channels"], list)


def test_api_channels_seeded(client: TestClient) -> None:
    body = client.get("/api/channels").json()
    slugs = {c["slug"] for c in body["channels"]}
    # Seed file ships with at least two known-good channels.
    assert "redbull-tv" in slugs
    assert "dw-news-en" in slugs


def test_api_channels_unavailable_hides_current_url(client: TestClient) -> None:
    # Default state for a freshly seeded channel is "unknown" (the prober
    # hasn't run yet under the blocking resolver). Under that state,
    # current_url MUST be null — the TV should not see a URL until the
    # helper confirms it's live.
    body = client.get("/api/channels").json()
    for c in body["channels"]:
        if c["status"] != "live":
            assert c["current_url"] is None, (
                f"channel {c['slug']} state={c['status']} but exposed current_url; "
                "non-live channels must hide the URL to prevent silent staleness"
            )


def test_api_channels_phantom_marks_seeded_live(phantom_client: TestClient) -> None:
    body = phantom_client.get("/api/channels").json()
    assert body["channels"], "phantom should have at least the seeded channels"
    for c in body["channels"]:
        assert c["status"] == "live"
        assert c["current_url"] is not None


def test_api_channels_pinned_fields(phantom_client: TestClient) -> None:
    body = phantom_client.get("/api/channels").json()
    item = body["channels"][0]
    for field in ("slug", "label", "kind", "current_url", "status", "enabled",
                  "last_check_at", "last_success_at", "last_error", "error_count",
                  "browser_playable", "category"):
        assert field in item, f"missing field: {field}"


def test_api_channels_category_mirrors_native_taxonomy(phantom_client: TestClient) -> None:
    """Every channel carries a `category` from the native ChannelCategory map,
    ALWAYS present (status-independent), and only ever one of the six known
    sections — so the web picker can section by it without inventing categories."""
    from mymts_helper.channels.category import CATEGORY_ORDER

    body = phantom_client.get("/api/channels").json()
    by_slug = {c["slug"]: c["category"] for c in body["channels"]}
    # Spot-check the mapping mirrors native (one channel per section + a fallback).
    expected = {
        "cbs-sports-hq": "Sports",
        "cnn": "US News",
        "bbc-news": "Global News",
        "bloomberg-tv": "Business",
        "fox-weather": "Weather",
        "nasa-tv": "General",   # unmapped slug → GENERAL fallback (as native)
    }
    for slug, cat in expected.items():
        assert by_slug.get(slug) == cat, f"{slug} should be {cat}, got {by_slug.get(slug)}"
    # No channel ever lands outside the known taxonomy.
    for c in body["channels"]:
        assert c["category"] in CATEGORY_ORDER, f"{c['slug']} has unknown category {c['category']}"


def test_api_channels_expanded_lineup(phantom_client: TestClient) -> None:
    """The 2026-06 lineup: the free-direct-HLS expansion AND the YouTube-sourced
    channels (via the yt-dlp resolver) are present + correctly categorized, and
    the un-addable channels are OMITTED. (Ordering is ORDER BY slug at the API;
    within-category prominence is a client concern.)"""
    body = phantom_client.get("/api/channels").json()
    by_slug = {c["slug"]: c["category"] for c in body["channels"]}

    # Direct-HLS additions present + correctly categorized.
    added = {
        "abc-news-live": "US News", "nbc-news-now": "US News",
        "news-nation": "US News", "scripps-news": "US News",
        "abc-news-au": "Global News", "cna": "Global News",
        "gb-news": "Global News", "nhk-world": "Global News",
    }
    # YouTube-sourced additions (kind='youtube') — now carriable via the resolver.
    added_youtube = {
        "pbs-newshour": "US News", "court-tv": "US News", "law-crime": "US News",
        "euronews": "Global News", "wion": "Global News", "ndtv": "Global News",
        "i24news-en": "Global News", "cbs-golazo": "Sports",
    }
    for slug, cat in {**added, **added_youtube}.items():
        assert slug in by_slug, f"new channel {slug} missing from lineup"
        assert by_slug[slug] == cat, f"{slug} should be {cat}, got {by_slug.get(slug)}"

    # Still honestly-omitted (paywall / web-embed / no confirmable live HLS).
    # cnn IS kept — already seeded; c-span main stays direct-HLS (the YouTube
    # /live was a far-future scheduled event, re-adding would duplicate the slug);
    # arirang couldn't be confirmed live from the NAS vantage.
    for slug in ("cbs-news", "arirang", "weathernation", "c-span-2",
                 "fox-news", "msnbc"):
        assert slug not in by_slug, f"omitted channel {slug} should NOT be seeded"


# ---- SQLite cross-thread regression (feed-sources expansion) ----


def test_feed_endpoint_survives_concurrent_threaded_requests(phantom_client: TestClient) -> None:
    """Regression for the intermittent `sqlite3.ProgrammingError: SQLite
    objects created in a thread can only be used in that same thread` on
    /api/feed.

    Root cause was the `Depends(_conn)` yield-dependency: a sync route runs
    in Starlette's anyio threadpool, and FastAPI drove the dependency's
    open and close through two `run_in_threadpool` calls that could land on
    different threadpool threads — closing a sqlite3 connection on a
    different thread than it was opened on raises ProgrammingError. The fix
    opens + closes the connection inside the route body via
    `db.connection_scope` so the lifecycle stays on one thread.

    Hammering the endpoint from many client threads concurrently maximises
    the chance the threadpool reuses/rotates worker threads across a
    request's open/close — the exact condition that used to flake. Every
    response must be a clean 200 with the pinned envelope; a single 500
    (or a connection-thread error bubbling up) fails the test.
    """
    import concurrent.futures

    def hit(_: int) -> tuple[int, bool]:
        r = phantom_client.get("/api/feed?limit=50")
        ok_shape = r.status_code == 200 and r.json().get("schema_version") == 1
        return r.status_code, ok_shape

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(hit, range(64)))

    statuses = [s for s, _ in results]
    assert all(s == 200 for s in statuses), (
        f"expected all 200, got a non-200 (cross-thread sqlite regression?): "
        f"{sorted(set(statuses))}"
    )
    assert all(shape for _, shape in results), "envelope shape drifted under concurrency"


def test_channels_endpoint_survives_concurrent_threaded_requests(phantom_client: TestClient) -> None:
    """Same cross-thread regression guard for /api/channels, which shared
    the identical `Depends(_conn)` pattern before the fix."""
    import concurrent.futures

    def hit(_: int) -> int:
        return phantom_client.get("/api/channels").status_code

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        statuses = list(pool.map(hit, range(64)))

    assert all(s == 200 for s in statuses), (
        f"expected all 200, got: {sorted(set(statuses))}"
    )
