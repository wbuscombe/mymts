"""Tests for the feeds store (sources + items + dedup + retention)."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from pathlib import Path

from mymts_helper import db
from mymts_helper.feeds import store
from mymts_helper.feeds.parser import ParsedItem


def _setup(tmp_path: Path) -> tuple[Path, int]:
    p = tmp_path / "test.db"
    db.migrate(p)
    conn = db.connect(p)
    sid = store.upsert_source(conn, url="https://x.test/feed", label="X")
    conn.close()
    return p, sid


def test_upsert_source_idempotent(tmp_path: Path) -> None:
    p, sid = _setup(tmp_path)
    conn = db.connect(p)
    sid2 = store.upsert_source(conn, url="https://x.test/feed", label="X (renamed)")
    assert sid == sid2
    rows = store.list_sources(conn)
    assert len(rows) == 1
    assert rows[0].label == "X (renamed)"


def test_insert_items_basic(tmp_path: Path) -> None:
    p, sid = _setup(tmp_path)
    items = [
        ParsedItem(guid="a", title="A", summary="", link="https://x/a",
                   published_at="2026-06-01T00:00:00.000Z"),
        ParsedItem(guid="b", title="B", summary="", link="https://x/b",
                   published_at="2026-06-01T00:01:00.000Z"),
    ]
    conn = db.connect(p)
    n = store.insert_items(conn, sid, items)
    assert n == 2
    assert store.total_items(conn) == 2


def test_insert_items_dedups_by_source_and_guid(tmp_path: Path) -> None:
    p, sid = _setup(tmp_path)
    items = [
        ParsedItem(guid="a", title="A", summary="", link="", published_at=None),
        ParsedItem(guid="a", title="A v2", summary="updated", link="", published_at=None),
    ]
    conn = db.connect(p)
    n = store.insert_items(conn, sid, items)
    # Only the first insert sticks; second is a dedup no-op.
    assert n == 1
    rows = store.recent_items(conn)
    assert len(rows) == 1
    assert rows[0]["title"] == "A"


def test_record_fetch_success_clears_error(tmp_path: Path) -> None:
    p, sid = _setup(tmp_path)
    conn = db.connect(p)
    store.record_fetch_failure(conn, sid, "boom")
    store.record_fetch_failure(conn, sid, "boom again")
    store.record_fetch_success(conn, sid)
    s = store.list_sources(conn)[0]
    assert s.last_error is None
    assert s.error_count == 0
    assert s.last_success_at is not None


def test_record_fetch_failure_increments(tmp_path: Path) -> None:
    p, sid = _setup(tmp_path)
    conn = db.connect(p)
    store.record_fetch_failure(conn, sid, "1")
    store.record_fetch_failure(conn, sid, "2")
    s = store.list_sources(conn)[0]
    assert s.error_count == 2
    assert s.last_error == "2"


def test_recent_items_orders_by_published(tmp_path: Path) -> None:
    p, sid = _setup(tmp_path)
    conn = db.connect(p)
    items = [
        ParsedItem(guid="old", title="Old", summary="", link="",
                   published_at="2026-05-01T00:00:00.000Z"),
        ParsedItem(guid="mid", title="Mid", summary="", link="",
                   published_at="2026-05-15T00:00:00.000Z"),
        ParsedItem(guid="new", title="New", summary="", link="",
                   published_at="2026-06-01T00:00:00.000Z"),
    ]
    store.insert_items(conn, sid, items)
    rows = store.recent_items(conn, limit=10)
    assert [r["title"] for r in rows] == ["New", "Mid", "Old"]


def test_recent_items_since_filter(tmp_path: Path) -> None:
    p, sid = _setup(tmp_path)
    conn = db.connect(p)
    items = [
        ParsedItem(guid="old", title="Old", summary="", link="",
                   published_at="2026-05-01T00:00:00.000Z"),
        ParsedItem(guid="new", title="New", summary="", link="",
                   published_at="2026-06-01T00:00:00.000Z"),
    ]
    store.insert_items(conn, sid, items)
    rows = store.recent_items(conn, limit=10, since="2026-05-15T00:00:00.000Z")
    assert [r["title"] for r in rows] == ["New"]


def test_retention_sweep_deletes_old_items(tmp_path: Path) -> None:
    p, sid = _setup(tmp_path)
    conn = db.connect(p)
    # Insert two items: one well past the retention window, one fresh. The fresh
    # item's timestamp is anchored to NOW (yesterday) — retention_sweep compares
    # against SQLite datetime('now', ...), so a hardcoded date would age out of
    # the window as the calendar advances (a time-bomb) and delete both items.
    fresh_ts = (datetime.now(UTC) - timedelta(days=1)).strftime("%Y-%m-%dT%H:%M:%S.000Z")
    items = [
        ParsedItem(guid="old", title="Old", summary="", link="",
                   published_at="2020-01-01T00:00:00.000Z"),
        ParsedItem(guid="fresh", title="Fresh", summary="", link="",
                   published_at=fresh_ts),
    ]
    store.insert_items(conn, sid, items)
    deleted = store.retention_sweep(conn, days=14)
    assert deleted == 1
    rows = store.recent_items(conn)
    assert [r["title"] for r in rows] == ["Fresh"]
