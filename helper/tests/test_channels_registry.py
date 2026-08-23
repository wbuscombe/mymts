"""Tests for the channel registry's validators and storage."""

from __future__ import annotations

from pathlib import Path

import pytest

from mymts_helper import db
from mymts_helper.channels import registry


def test_validate_slug_accepts_canonical() -> None:
    assert registry.validate_slug("redbull-tv") == "redbull-tv"
    assert registry.validate_slug("a1") == "a1"


@pytest.mark.parametrize(
    "bad",
    ["", "A", "Red-Bull", "1leading-digit-still-ok-actually",
     "-leading-dash", "trailing-dash-", "underscore_no", "spaces in slug",
     "../../etc/passwd", "x" * 100],
)
def test_validate_slug_rejects(bad: str) -> None:
    # one of the "bad" cases is actually fine — the leading-digit one;
    # special-case it.
    if bad == "1leading-digit-still-ok-actually":
        assert registry.validate_slug(bad) == bad
        return
    with pytest.raises(registry.RegistryError):
        registry.validate_slug(bad)


def test_validate_hls_url_accepts_m3u8() -> None:
    registry.validate_hls_url("https://example.test/path/master.m3u8")


def test_validate_hls_url_accepts_query_m3u8() -> None:
    registry.validate_hls_url("https://example.test/api?fmt=m3u8&channel=42")


def test_validate_hls_url_rejects_http() -> None:
    with pytest.raises(registry.RegistryError, match="scheme"):
        registry.validate_hls_url("http://example.test/master.m3u8")


def test_validate_hls_url_rejects_userinfo() -> None:
    with pytest.raises(registry.RegistryError, match="userinfo"):
        registry.validate_hls_url("https://u:p@example.test/master.m3u8")


def test_validate_hls_url_rejects_odd_port() -> None:
    with pytest.raises(registry.RegistryError, match="port"):
        registry.validate_hls_url("https://example.test:8443/master.m3u8")


def test_validate_hls_url_rejects_non_m3u8_path() -> None:
    with pytest.raises(registry.RegistryError, match="m3u8"):
        registry.validate_hls_url("https://example.test/index.html")


def test_validate_hls_url_rejects_non_string() -> None:
    with pytest.raises(registry.RegistryError):
        registry.validate_hls_url(None)  # type: ignore[arg-type]


def test_upsert_channel(tmp_path: Path) -> None:
    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)
    cid = registry.upsert_channel(
        conn, slug="redbull-tv", label="Red Bull TV",
        source_url="https://x.test/master.m3u8",
    )
    assert cid > 0
    rows = registry.list_channels(conn)
    assert len(rows) == 1
    assert rows[0].slug == "redbull-tv"
    assert rows[0].kind == "hls"
    assert rows[0].status == "unknown"


def test_upsert_channel_renames(tmp_path: Path) -> None:
    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)
    registry.upsert_channel(conn, slug="x", label="First",
                            source_url="https://x.test/a.m3u8")
    registry.upsert_channel(conn, slug="x", label="Renamed",
                            source_url="https://x.test/b.m3u8")
    rows = registry.list_channels(conn)
    assert len(rows) == 1
    assert rows[0].label == "Renamed"
    assert rows[0].source_url.endswith("b.m3u8")


def test_upsert_channel_repoints_kind(tmp_path: Path) -> None:
    # Re-seeding a channel with a different kind (e.g. a dead direct-HLS source
    # re-pointed to its YouTube live) updates the kind too — seed.json is the
    # full source of truth, so the upsert must change kind, not just label/url.
    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)
    registry.upsert_channel(conn, slug="x", label="X", kind="hls",
                            source_url="https://x.test/a.m3u8")
    registry.upsert_channel(conn, slug="x", label="X", kind="youtube",
                            source_url="https://www.youtube.com/@x/live")
    rows = registry.list_channels(conn)
    assert len(rows) == 1
    assert rows[0].kind == "youtube"
    assert rows[0].source_url.endswith("/live")


def test_upsert_channel_accepts_youtube_kind(tmp_path: Path) -> None:
    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)
    # kind='youtube' is admitted by migration 003 (yt-dlp resolver). The
    # source_url must be a YouTube live URL, not an m3u8.
    cid = registry.upsert_channel(
        conn, slug="yt", label="YT",
        source_url="https://www.youtube.com/@PBSNewsHour/live", kind="youtube",
    )
    assert cid > 0
    rows = registry.list_channels(conn)
    assert rows[0].kind == "youtube"
    assert rows[0].source_url.endswith("/live")


def test_upsert_channel_rejects_unknown_kind(tmp_path: Path) -> None:
    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)
    with pytest.raises(registry.RegistryError, match="unsupported_kind"):
        registry.upsert_channel(
            conn, slug="tw", label="TW",
            source_url="https://www.twitch.tv/foo", kind="twitch",
        )


def test_upsert_youtube_kind_rejects_non_youtube_url(tmp_path: Path) -> None:
    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)
    # A YouTube-kind channel pointed at a non-YouTube host is rejected.
    with pytest.raises(registry.RegistryError, match="youtube_url_host"):
        registry.upsert_channel(
            conn, slug="yt", label="YT",
            source_url="https://evil.test/@x/live", kind="youtube",
        )


@pytest.mark.parametrize(
    "url",
    [
        "https://www.youtube.com/@PBSNewsHour/live",
        "https://youtube.com/@cspan/live",
        "https://www.youtube.com/channel/UC123/live",
        "https://www.youtube.com/live/abcdEFGH012",
        "https://www.youtube.com/watch?v=abcdEFGH012",
    ],
)
def test_validate_youtube_url_accepts(url: str) -> None:
    assert registry.validate_youtube_url(url) == url


@pytest.mark.parametrize(
    "url",
    [
        "http://www.youtube.com/@x/live",          # http
        "https://notyoutube.com/@x/live",          # wrong host
        "https://u:p@www.youtube.com/@x/live",     # userinfo
        "https://www.youtube.com:8443/@x/live",    # odd port
        "https://www.youtube.com/@x/videos",       # not a live path
        "https://www.youtube.com/watch?list=PL1",  # watch without v=
    ],
)
def test_validate_youtube_url_rejects(url: str) -> None:
    with pytest.raises(registry.RegistryError):
        registry.validate_youtube_url(url)


@pytest.mark.parametrize(
    "url",
    [
        "https://www.senate.gov/isvp/?type=live&comm=stv",
        "https://senate.gov/isvp/stv.html?comm=stv&filename=stv061726",
    ],
)
def test_validate_cspan_url_accepts(url: str) -> None:
    assert registry.validate_cspan_url(url) == url


@pytest.mark.parametrize(
    "url",
    [
        "http://www.senate.gov/isvp/?comm=stv",           # http
        "https://www.c-span.org/networks/?channel=c-span-2",  # gated network host
        "https://u:p@www.senate.gov/isvp/?comm=stv",      # userinfo
        "https://www.senate.gov:8443/isvp/?comm=stv",     # odd port
        "https://www.senate.gov/about/contact",           # not an /isvp path
    ],
)
def test_validate_cspan_url_rejects(url: str) -> None:
    with pytest.raises(registry.RegistryError):
        registry.validate_cspan_url(url)


def test_upsert_channel_accepts_cspan_kind(tmp_path: Path) -> None:
    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)
    # kind='cspan' is admitted by migration 004 (free Senate-floor resolver).
    # The source_url must be a senate.gov ISVP URL, not an m3u8.
    cid = registry.upsert_channel(
        conn, slug="sf", label="U.S. Senate Floor",
        source_url="https://www.senate.gov/isvp/?type=live&comm=stv", kind="cspan",
    )
    assert cid > 0
    rows = registry.list_channels(conn)
    assert rows[0].kind == "cspan"
    assert "/isvp" in rows[0].source_url


def test_upsert_cspan_kind_rejects_gated_network_host(tmp_path: Path) -> None:
    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)
    # A cspan-kind channel pointed at the entitlement-gated c-span.org host
    # (the curated networks) is rejected — only senate.gov free feeds admitted.
    with pytest.raises(registry.RegistryError, match="cspan_url_host"):
        registry.upsert_channel(
            conn, slug="net", label="C-SPAN 2",
            source_url="https://www.c-span.org/networks/?channel=c-span-2",
            kind="cspan",
        )


def test_update_status_live(tmp_path: Path) -> None:
    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)
    cid = registry.upsert_channel(conn, slug="x", label="X",
                                  source_url="https://x.test/a.m3u8")
    registry.update_status(conn, channel_id=cid, status="live",
                           current_url="https://x.test/a.m3u8",
                           error=None, success=True, browser_playable=True)
    rows = registry.list_channels(conn)
    assert rows[0].status == "live"
    assert rows[0].error_count == 0
    assert rows[0].current_url == "https://x.test/a.m3u8"
    assert rows[0].browser_playable is True


def test_browser_playable_roundtrips_true_false_none(tmp_path: Path) -> None:
    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)
    cid = registry.upsert_channel(conn, slug="x", label="X",
                                  source_url="https://x.test/a.m3u8")
    # Unclassified by default → NULL → None.
    assert registry.list_channels(conn)[0].browser_playable is None
    # HTTPS-clean → True.
    registry.update_status(conn, channel_id=cid, status="live",
                           current_url="https://x.test/a.m3u8",
                           error=None, success=True, browser_playable=True)
    assert registry.list_channels(conn)[0].browser_playable is True
    # Mixed-content → False (stored 0, surfaced as bool False not None).
    registry.update_status(conn, channel_id=cid, status="live",
                           current_url="https://x.test/a.m3u8",
                           error=None, success=True, browser_playable=False)
    row = registry.list_channels(conn)[0]
    assert row.browser_playable is False
    # Going unavailable clears the hint to None (no stale "playable").
    registry.update_status(conn, channel_id=cid, status="unavailable",
                           current_url=None, error="http_403", success=False)
    assert registry.list_channels(conn)[0].browser_playable is None


def test_update_status_failure_increments(tmp_path: Path) -> None:
    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)
    cid = registry.upsert_channel(conn, slug="x", label="X",
                                  source_url="https://x.test/a.m3u8")
    registry.update_status(conn, channel_id=cid, status="unavailable",
                           current_url=None, error="http_403", success=False)
    registry.update_status(conn, channel_id=cid, status="unavailable",
                           current_url=None, error="http_403", success=False)
    rows = registry.list_channels(conn)
    assert rows[0].status == "unavailable"
    assert rows[0].error_count == 2


def test_seed_from_file(tmp_path: Path) -> None:
    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)
    seed = tmp_path / "seed.json"
    seed.write_text("""[
        {"slug": "a", "label": "A", "kind": "hls", "source_url": "https://x.test/a.m3u8"},
        {"slug": "b", "label": "B", "kind": "hls", "source_url": "https://x.test/b.m3u8"}
    ]""")
    n = registry.seed_from_file(conn, seed)
    assert n == 2
    assert len(registry.list_channels(conn)) == 2


def test_seed_skips_invalid_entries(tmp_path: Path) -> None:
    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)
    seed = tmp_path / "seed.json"
    seed.write_text("""[
        {"slug": "ok", "label": "OK", "kind": "hls", "source_url": "https://x.test/a.m3u8"},
        {"slug": "BAD!", "label": "X", "kind": "hls", "source_url": "https://x.test/b.m3u8"},
        {"slug": "http", "label": "X", "kind": "hls", "source_url": "http://x.test/b.m3u8"}
    ]""")
    n = registry.seed_from_file(conn, seed)
    assert n == 1
    assert {c.slug for c in registry.list_channels(conn)} == {"ok"}


def test_seed_idempotent(tmp_path: Path) -> None:
    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)
    seed = tmp_path / "seed.json"
    seed.write_text("""[
        {"slug": "a", "label": "A", "kind": "hls", "source_url": "https://x.test/a.m3u8"}
    ]""")
    registry.seed_from_file(conn, seed)
    registry.seed_from_file(conn, seed)
    assert len(registry.list_channels(conn)) == 1


# ---- the SHIPPED channel seed (parity with the feeds-seeder guards) ----


def _load_shipped_channel_seed() -> list[dict]:
    import json
    from importlib.resources import files

    raw = files("mymts_helper.channels").joinpath("seed.json").read_text(encoding="utf-8")
    return json.loads(raw)


def test_shipped_channel_seed_is_wellformed_and_unique() -> None:
    """Guard the packaged CHANNEL seed the way test_feeds_seeder guards the feed
    seed. Every entry must have a valid slug, a non-empty label, a known `kind`,
    and an https source_url that PASSES ITS KIND'S VALIDATOR — slugs, labels and
    URLs all unique.

    Why this matters: `seed_from_file` (and `override.seed_lineup`) deliberately
    SKIP an entry whose validator rejects it (`log.warning("seed_skip_invalid")`)
    so one bad row can't stop the helper booting. That is right at runtime, but it
    means a typo'd URL in a lineup edit vanishes SILENTLY — the channel just never
    appears. This test turns that silent drop into a loud failure at commit time.
    """
    entries = _load_shipped_channel_seed()
    assert isinstance(entries, list) and entries, "seed.json must be a non-empty list"

    validators = {
        "hls": registry.validate_hls_url,
        "youtube": registry.validate_youtube_url,
        "cspan": registry.validate_cspan_url,
    }

    for e in entries:
        slug = e["slug"]
        registry.validate_slug(slug)                       # raises on a bad slug
        assert e["label"].strip(), f"empty label for {slug}"
        assert e["kind"] in validators, f"{slug} has unknown kind {e['kind']!r}"
        assert e["source_url"].startswith("https://"), f"non-https source for {slug}"
        # The decisive check: the URL survives the validator its kind is stored with.
        validators[e["kind"]](e["source_url"])

    slugs = [e["slug"] for e in entries]
    labels = [e["label"] for e in entries]
    urls = [e["source_url"] for e in entries]
    assert len(slugs) == len(set(slugs)), "duplicate slug in channels seed.json"
    assert len(labels) == len(set(labels)), "duplicate label in channels seed.json"
    assert len(urls) == len(set(urls)), "duplicate source_url in channels seed.json"


def test_shipped_channel_seed_seeds_every_channel(tmp_path: Path) -> None:
    """No silent drops: the shipped seed must upsert EVERY entry.

    `seed_from_file` returns the number it actually stored, and skips (with only a
    log warning) any entry a validator rejects. Asserting stored == file-length is
    the guard that a newly added channel really reaches the database rather than
    being quietly discarded at boot — the failure mode that would otherwise show up
    only as a channel mysteriously missing from the picker on the deployed wall.
    """
    from importlib.resources import files

    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)

    seed_path = Path(str(files("mymts_helper.channels").joinpath("seed.json")))
    expected = len(_load_shipped_channel_seed())
    seeded = registry.seed_from_file(conn, seed_path)
    assert seeded == expected, f"seeded {seeded} but seed.json has {expected} entries"
    assert len(registry.list_channels(conn)) == expected

    # The 2026-08 fresh sources specifically must survive the round-trip.
    stored = {c.slug: c for c in registry.list_channels(conn)}
    for slug in ("cbs-news-247", "al-jazeera-en", "cgtn-en", "trt-world"):
        assert slug in stored, f"fresh source {slug} was silently dropped at seed time"
