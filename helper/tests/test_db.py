"""Tests for the migration runner + schema invariants."""

from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest

from mymts_helper import db


def test_migrate_creates_tables(tmp_path: Path) -> None:
    p = tmp_path / "test.db"
    final = db.migrate(p)
    assert final >= 1
    conn = db.connect(p)
    tables = {
        r["name"] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
        )
    }
    assert {"sources", "feed_items", "channels", "meta"} <= tables


def test_migrate_records_version(tmp_path: Path) -> None:
    p = tmp_path / "test.db"
    db.migrate(p)
    conn = db.connect(p)
    row = conn.execute("SELECT value FROM meta WHERE key='schema_version'").fetchone()
    assert row is not None
    assert int(row["value"]) >= 1


def test_migrate_idempotent(tmp_path: Path) -> None:
    p = tmp_path / "test.db"
    v1 = db.migrate(p)
    v2 = db.migrate(p)
    assert v1 == v2


def test_migrate_survives_partial_additive_apply(tmp_path: Path) -> None:
    """Reproduces the crash window for an additive ALTER migration: the
    column-add commits but the process dies before the schema_version bump,
    so on the next boot migrate() re-runs the same ALTER against a DB that
    ALREADY has the column. SQLite has no `ADD COLUMN IF NOT EXISTS`, so
    that re-run raises 'duplicate column name' — which migrate() must treat
    as already-applied (and let the version bump catch up) rather than
    propagate and wedge boot."""
    p = tmp_path / "test.db"
    final = db.migrate(p)            # fully migrate to head
    conn = db.connect(p)
    # Simulate the partial apply: the column from the LAST migration exists,
    # but schema_version is rewound to before it (as if the bump never ran).
    conn.execute(
        "UPDATE meta SET value=? WHERE key='schema_version'", (str(final - 1),)
    )
    conn.close()
    # Re-running must NOT raise (duplicate column is swallowed) and must
    # finish at head again.
    assert db.migrate(p) == final
    assert db.current_schema_version(db.connect(p)) == final


def test_wal_mode_set(tmp_path: Path) -> None:
    p = tmp_path / "test.db"
    db.migrate(p)
    conn = db.connect(p)
    row = conn.execute("PRAGMA journal_mode").fetchone()
    assert row[0].lower() == "wal"


def test_foreign_keys_enforced(tmp_path: Path) -> None:
    p = tmp_path / "test.db"
    db.migrate(p)
    conn = db.connect(p)
    # Inserting a feed_item without a parent source must fail under FK enforcement.
    with pytest.raises(sqlite3.IntegrityError):
        conn.execute(
            "INSERT INTO feed_items(source_id, guid, title, fetched_at) "
            "VALUES (?, ?, ?, ?)",
            (9999, "g", "t", "2026-01-01T00:00:00.000Z"),
        )


def test_channels_kind_checked(tmp_path: Path) -> None:
    p = tmp_path / "test.db"
    db.migrate(p)
    conn = db.connect(p)
    with pytest.raises(sqlite3.IntegrityError):
        conn.execute(
            "INSERT INTO channels(slug, label, kind, source_url) VALUES (?,?,?,?)",
            ("x", "X", "rtsp", "https://example/x.m3u8"),
        )


def test_channels_kind_youtube_admitted(tmp_path: Path) -> None:
    # migration 003 widened the kind CHECK to admit 'youtube'.
    p = tmp_path / "test.db"
    db.migrate(p)
    conn = db.connect(p)
    conn.execute(
        "INSERT INTO channels(slug, label, kind, source_url) VALUES (?,?,?,?)",
        ("yt", "YT", "youtube", "https://www.youtube.com/@x/live"),
    )
    row = conn.execute("SELECT kind FROM channels WHERE slug='yt'").fetchone()
    assert row["kind"] == "youtube"


def test_migration_003_preserves_rows_and_widens_kind(tmp_path: Path) -> None:
    """The 003 table-recreate must copy every channel row + all state verbatim,
    flip the kind CHECK to admit 'youtube', and still reject a bogus kind."""
    from importlib.resources import files

    p = tmp_path / "test.db"
    conn = sqlite3.connect(p)
    conn.row_factory = sqlite3.Row
    # Build the post-002 channels table (hls-only CHECK + browser_playable),
    # i.e. the schema as it stood right before 003.
    conn.executescript(
        """
        CREATE TABLE channels (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            slug TEXT NOT NULL UNIQUE,
            label TEXT NOT NULL,
            kind TEXT NOT NULL CHECK(kind IN ('hls')),
            source_url TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1,
            current_url TEXT,
            status TEXT NOT NULL DEFAULT 'unknown'
                CHECK(status IN ('live','unavailable','unknown')),
            last_check_at TEXT,
            last_success_at TEXT,
            last_error TEXT,
            error_count INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
            browser_playable INTEGER
        );
        """
    )
    conn.execute(
        "INSERT INTO channels(slug,label,kind,source_url,current_url,status,"
        "error_count,browser_playable) VALUES (?,?,?,?,?,?,?,?)",
        ("bbc", "BBC", "hls", "https://x/master.m3u8",
         "https://x/master.m3u8", "live", 3, 1),
    )
    conn.commit()

    sql003 = files("mymts_helper.migrations").joinpath(
        "003_channel_kind_youtube.sql"
    ).read_text()
    conn.executescript(sql003)

    row = conn.execute(
        "SELECT slug,kind,source_url,current_url,status,error_count,browser_playable "
        "FROM channels WHERE slug='bbc'"
    ).fetchone()
    assert tuple(row) == ("bbc", "hls", "https://x/master.m3u8",
                          "https://x/master.m3u8", "live", 3, 1)
    # New CHECK admits youtube...
    conn.execute(
        "INSERT INTO channels(slug,label,kind,source_url) VALUES (?,?,?,?)",
        ("yt", "YT", "youtube", "https://www.youtube.com/@x/live"),
    )
    # ...but still rejects an unknown kind.
    with pytest.raises(sqlite3.IntegrityError):
        conn.execute(
            "INSERT INTO channels(slug,label,kind,source_url) VALUES (?,?,?,?)",
            ("tw", "TW", "twitch", "https://t/x"),
        )


def test_channels_status_checked(tmp_path: Path) -> None:
    p = tmp_path / "test.db"
    db.migrate(p)
    conn = db.connect(p)
    with pytest.raises(sqlite3.IntegrityError):
        conn.execute(
            "INSERT INTO channels(slug, label, kind, source_url, status) "
            "VALUES (?,?,?,?,?)",
            ("x", "X", "hls", "https://example/x.m3u8", "weird"),
        )


def test_source_url_unique(tmp_path: Path) -> None:
    p = tmp_path / "test.db"
    db.migrate(p)
    conn = db.connect(p)
    conn.execute(
        "INSERT INTO sources(url, label) VALUES (?, ?)",
        ("https://example/feed.xml", "A"),
    )
    with pytest.raises(sqlite3.IntegrityError):
        conn.execute(
            "INSERT INTO sources(url, label) VALUES (?, ?)",
            ("https://example/feed.xml", "B"),
        )
