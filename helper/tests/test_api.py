"""Contract tests for /api/feed and /api/channels.

These pin the response envelope shape the TV depends on. Changes here
require a coordinated change in the TV's request code and a bump in
`API_SCHEMA_VERSION` if the change is non-additive.
"""

from __future__ import annotations

import json
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
    """Every channel carries a `category` from the ChannelCategory map, ALWAYS
    present (status-independent), and only ever one of the known sections — so the
    clients can section by it without inventing categories."""
    from mymts_helper.channels.category import CATEGORY_ORDER

    body = phantom_client.get("/api/channels").json()
    by_slug = {c["slug"]: c["category"] for c in body["channels"]}
    # Spot-check the mapping (one per section, incl. the 2026-06 ambient trio + fallback).
    expected = {
        "cbs-sports-hq": "Sports",
        "cnn": "US News",
        "bbc-news": "Global News",
        "bloomberg-tv": "Business",
        "fox-weather": "Weather",
        "earthcam-live": "Cameras",
        "explore-nature-cams": "Nature",
        "nasa-tv": "Space",
        "redbull-tv": "General",   # unmapped slug → GENERAL fallback (as native)
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
    # Weather additions (2026-06-24): WeatherNation re-added after its NAS-prober
    # TLS handshake was fixed (RSA-kx cipher enabled, verification preserved);
    # WeatherSpy added — both free FAST weather, validated from the NAS vantage.
    added_weather = {"weathernation": "Weather", "weatherspy": "Weather"}
    for slug, cat in {**added, **added_youtube, **added_weather}.items():
        assert slug in by_slug, f"new channel {slug} missing from lineup"
        assert by_slug[slug] == cat, f"{slug} should be {cat}, got {by_slug.get(slug)}"

    # Still honestly-omitted (paywall / web-embed / no confirmable free HLS).
    # cnn IS kept (already seeded). cnn-international + c-span (the branded cspan1
    # akamai) were PRUNED 2026-06-24 — no clean free source (DNS-dead / 403); the
    # free C-SPAN path is the us-senate-* gov resolvers, not the branded linear.
    # NOTE: the bare `cbs-news` slug was dropped from this omission list in the
    # 2026-08 fresh-source pass — national CBS News IS now carried, as `cbs-news-247`
    # on its first-party token-free origin (see the fresh-sources test below). The
    # Chicago market feed remains honestly-omitted (all variants 404).
    for slug in ("arirang", "c-span-2", "fox-news", "msnbc",
                 "cnn-international", "c-span", "cbs-news-chicago"):
        assert slug not in by_slug, f"omitted channel {slug} should NOT be seeded"


def test_api_channels_2026_08_fresh_sources(phantom_client: TestClient) -> None:
    """MYMTS-003 fresh-source pass — the four channels validated 2026-08-18 are
    seeded, carry the right `kind`, and group into the EXISTING taxonomy.

    NON-VACUOUS BY CONSTRUCTION: each assertion names a slug that does not exist
    anywhere else in the suite, so this test fails if any of the four is dropped
    from seed.json, mis-typed, or loses its category mapping.

    al-jazeera-en / cgtn-en / trt-world were pruned 2026-06-22 when their old
    DIRECT-HLS origins went dead (~1000 consecutive dns/SSL failures). That was
    superseded-endpoint evidence, so each is re-sourced to its official YouTube
    `/live` HANDLE (never a rotating video id) and rides the is_live-gated
    resolver -> honest-offline. cbs-news-247 is the national CBS News free linear
    feed on its first-party token-free direct-HLS origin.
    """
    body = phantom_client.get("/api/channels").json()
    by_slug = {c["slug"]: c for c in body["channels"]}

    expected = {
        "cbs-news-247":  ("US News",     "hls"),
        "al-jazeera-en": ("Global News", "youtube"),
        "cgtn-en":       ("Global News", "youtube"),
        "trt-world":     ("Global News", "youtube"),
    }
    for slug, (cat, kind) in expected.items():
        assert slug in by_slug, f"fresh source {slug} missing from the lineup"
        assert by_slug[slug]["category"] == cat, (
            f"{slug} should be {cat}, got {by_slug[slug]['category']}")
        assert by_slug[slug]["kind"] == kind, (
            f"{slug} should be kind={kind}, got {by_slug[slug]['kind']}")

    # The three re-sourced feeds must ride the HANDLE, never a pinned video id —
    # a pinned id strands the channel the moment the broadcaster restarts the
    # stream. Guarded here because it is a sourcing RULE, not an accident.
    from importlib.resources import files
    seed = json.loads(files("mymts_helper.channels").joinpath("seed.json").read_text())
    by_seed = {c["slug"]: c["source_url"] for c in seed}
    for slug in ("al-jazeera-en", "cgtn-en", "trt-world"):
        url = by_seed[slug]
        assert url.startswith("https://www.youtube.com/@"), f"{slug} must use an @handle: {url}"
        assert url.endswith("/live"), f"{slug} must use the /live handle path: {url}"
        assert "watch?v=" not in url, f"{slug} must NOT pin a rotating video id: {url}"


def test_every_seeded_channel_has_an_explicit_category(phantom_client: TestClient) -> None:
    """STRUCTURAL GUARD (2026-08): every seeded slug must be EXPLICITLY mapped in
    channels/category.py — the GENERAL fallback exists so an unmapped channel is
    never *hidden*, but silently landing there is a taxonomy bug, not a design.

    This is the non-vacuous half of the picker-parity contract: scripts/
    check_channel_parity.py proves the three surfaces agree on the section LIST,
    and this proves every channel actually lands in one of those sections. Adding
    a channel to seed.json without categorising it now fails the suite.
    """
    from importlib.resources import files

    from mymts_helper.channels.category import _BY_SLUG

    seed = json.loads(files("mymts_helper.channels").joinpath("seed.json").read_text())
    seeded = {c["slug"] for c in seed}

    # The ONE documented exception: a channel that genuinely belongs to no section.
    ALLOWED_GENERAL = {"redbull-tv"}

    unmapped = seeded - set(_BY_SLUG) - ALLOWED_GENERAL
    assert not unmapped, (
        "seeded channels with no explicit category mapping (they would silently "
        f"fall into General): {sorted(unmapped)} — add them to channels/category.py "
        "_BY_SLUG, or to this test's ALLOWED_GENERAL with a reason."
    )
    # And the mapping must not accumulate entries for channels we no longer ship.
    stale = set(_BY_SLUG) - seeded
    assert not stale, f"category map has entries for unseeded slugs: {sorted(stale)}"


def test_lineup_size_and_kind_breakdown_are_what_the_docs_claim(
    phantom_client: TestClient,
) -> None:
    """Anchor for the counts quoted in README / ARCHITECTURE / ONBOARDING.

    A doc that says "56 channels (23 direct-HLS + 31 YouTube-resolved + 2 free-gov
    cspan)" is a claim a reader will check. Pin it here so the docs and the seed
    cannot drift apart silently — if you change the lineup, this fails and points
    at the exact sentences to update.
    """
    from collections import Counter
    from importlib.resources import files

    seed = json.loads(files("mymts_helper.channels").joinpath("seed.json").read_text())
    kinds = Counter(c["kind"] for c in seed)
    assert len(seed) == 56, f"lineup size changed to {len(seed)} — update the docs (README, ARCHITECTURE, ONBOARDING, docs/onboarding/ONBOARD-01-SETUP.md)"
    assert kinds == {"hls": 23, "youtube": 31, "cspan": 2}, f"kind breakdown changed: {dict(kinds)} — update the docs"

    # The served endpoint lists every seeded channel (plus the widget rows, which
    # are appended unconditionally by the unified registry and are NOT in seed.json).
    body = phantom_client.get("/api/channels").json()
    served = {c["slug"] for c in body["channels"]}
    assert {c["slug"] for c in seed} <= served, "a seeded channel is missing from /api/channels"


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
