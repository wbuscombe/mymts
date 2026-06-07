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


def test_upsert_channel_rejects_youtube_kind(tmp_path: Path) -> None:
    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)
    # YouTube kind lands in a future migration with a yt-dlp sidecar.
    with pytest.raises(registry.RegistryError, match="unsupported_kind"):
        registry.upsert_channel(
            conn, slug="yt", label="YT",
            source_url="https://x.test/a.m3u8", kind="youtube",
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
