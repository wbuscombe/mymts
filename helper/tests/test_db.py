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
