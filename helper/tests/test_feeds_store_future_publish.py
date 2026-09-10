"""Future-date plausibility gate on feed ingest (MYMTS-012).

A feed that advertises a `published_at` in the future sorts first on every
surface — the helper's own `ORDER BY COALESCE(published_at, fetched_at) DESC`,
the web client's sort key, and the native wall's effective-timestamp fallback
all prefer published — and the item then renders as "now" until the wall clock
catches up to it. The confirmed instance was a CBS item fetched
2026-09-06T05:05:13Z carrying published_at 2026-09-14T02:00:00Z: eight days
ahead of its own fetch.

Ingest now clamps an implausibly-future published date to the fetch time and
logs the rejected value verbatim. The item is ALWAYS stored — the gate never
skips, drops, or discards an item, because a wrong timestamp is a smaller
failure than a silently missing story.

Every case here is offline: a tmp_path SQLite file, no network.
"""

from __future__ import annotations

import logging
import sqlite3
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from mymts_helper import db
from mymts_helper.feeds import store
from mymts_helper.feeds.parser import ParsedItem

# The confirmed defect's own values (feed_items row id 11635149 on the running
# helper — CBS "The Gaslighting of Hannah Pettey"). Used verbatim so this
# module starts failing again if the exact reported case ever stops being
# caught.
DEFECT_FETCHED_AT = "2026-09-06T05:05:13.000Z"
DEFECT_PUBLISHED_AT = "2026-09-14T02:00:00.000Z"
DEFECT_GUID = "98b5c547-75a9-4895-8534-4288f344595a"

STORE_LOGGER = "mymts_helper.feeds.store"


def _setup(tmp_path: Path) -> tuple[Path, int]:
    p = tmp_path / "test.db"
    db.migrate(p)
    conn = db.connect(p)
    sid = store.upsert_source(conn, url="https://cbs.test/feed", label="CBS News")
    conn.close()
    return p, sid


def _row(conn: sqlite3.Connection, guid: str) -> sqlite3.Row | None:
    return conn.execute(
        "SELECT guid, published_at, fetched_at FROM feed_items WHERE guid=?", (guid,)
    ).fetchone()


def _shift(base: str, seconds: float) -> str:
    """`base` offset by `seconds`, in the store's own ISO-8601 UTC format."""
    dt = datetime.strptime(base, "%Y-%m-%dT%H:%M:%S.000Z").replace(tzinfo=UTC)
    return (dt + timedelta(seconds=seconds)).strftime("%Y-%m-%dT%H:%M:%S.000Z")


def _pin_fetch_time(monkeypatch: pytest.MonkeyPatch, ts: str) -> None:
    """Pin the fetch timestamp `insert_items` stamps rows with, so the
    published-vs-fetched distance under test is exact rather than clock-relative."""
    monkeypatch.setattr(store, "_utcnow_iso", lambda: ts)


def _store_warnings(caplog: pytest.LogCaptureFixture) -> list[str]:
    return [
        r.getMessage()
        for r in caplog.records
        if r.name == STORE_LOGGER and r.levelno == logging.WARNING
    ]


def test_future_publish_tolerance_is_one_hour() -> None:
    """The tolerance is a named module-level constant, not a buried literal."""
    assert store.FUTURE_PUBLISH_TOLERANCE_SECONDS == 3600


def test_far_future_published_is_clamped_to_fetched_and_warns(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    """(a) The confirmed defect: published eight days past fetch is clamped to
    the fetch time, and the rejected value survives verbatim in one WARNING."""
    p, sid = _setup(tmp_path)
    _pin_fetch_time(monkeypatch, DEFECT_FETCHED_AT)
    conn = db.connect(p)
    with caplog.at_level(logging.WARNING, logger=STORE_LOGGER):
        n = store.insert_items(
            conn,
            sid,
            [
                ParsedItem(
                    guid=DEFECT_GUID,
                    title="The Gaslighting of Hannah Pettey",
                    summary="",
                    link="https://cbs.test/hannah-pettey",
                    published_at=DEFECT_PUBLISHED_AT,
                )
            ],
        )

    # Stored, never skipped.
    assert n == 1
    row = _row(conn, DEFECT_GUID)
    assert row is not None
    assert row["fetched_at"] == DEFECT_FETCHED_AT
    # Clamped to the fetch time — NOT the feed's invented future value, and
    # NOT an invented "real" publication time, which is unrecoverable.
    assert row["published_at"] == DEFECT_FETCHED_AT

    warnings = _store_warnings(caplog)
    assert len(warnings) == 1
    msg = warnings[0]
    assert DEFECT_PUBLISHED_AT in msg  # the original, verbatim
    assert DEFECT_GUID in msg
    assert str(sid) in msg


def test_slightly_future_published_inside_tolerance_is_stored_unchanged(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    """(b) Just inside the tolerance is ordinary publisher clock skew, kept as-is.

    (a) and (b) sit on opposite sides of the distinction the gate draws.
    """
    p, sid = _setup(tmp_path)
    _pin_fetch_time(monkeypatch, DEFECT_FETCHED_AT)
    inside = _shift(DEFECT_FETCHED_AT, store.FUTURE_PUBLISH_TOLERANCE_SECONDS - 1)
    conn = db.connect(p)
    with caplog.at_level(logging.WARNING, logger=STORE_LOGGER):
        n = store.insert_items(
            conn,
            sid,
            [ParsedItem(guid="skewed", title="Skewed", summary="", link="",
                        published_at=inside)],
        )

    assert n == 1
    row = _row(conn, "skewed")
    assert row is not None
    assert row["published_at"] == inside
    assert _store_warnings(caplog) == []


def test_published_exactly_at_tolerance_boundary_is_stored_unchanged(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    """(c) Documented boundary behaviour: the clamp fires only when published
    exceeds fetched by MORE than the tolerance, so a value sitting exactly on
    the boundary is KEPT. The comparison is strictly greater-than."""
    p, sid = _setup(tmp_path)
    _pin_fetch_time(monkeypatch, DEFECT_FETCHED_AT)
    boundary = _shift(DEFECT_FETCHED_AT, store.FUTURE_PUBLISH_TOLERANCE_SECONDS)
    conn = db.connect(p)
    with caplog.at_level(logging.WARNING, logger=STORE_LOGGER):
        n = store.insert_items(
            conn,
            sid,
            [ParsedItem(guid="boundary", title="Boundary", summary="", link="",
                        published_at=boundary)],
        )

    assert n == 1
    row = _row(conn, "boundary")
    assert row is not None
    assert row["published_at"] == boundary
    assert _store_warnings(caplog) == []


def test_past_published_is_stored_unchanged(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    """(d) The normal case — published before fetched — is untouched."""
    p, sid = _setup(tmp_path)
    _pin_fetch_time(monkeypatch, DEFECT_FETCHED_AT)
    past = _shift(DEFECT_FETCHED_AT, -2 * store.FUTURE_PUBLISH_TOLERANCE_SECONDS)
    conn = db.connect(p)
    with caplog.at_level(logging.WARNING, logger=STORE_LOGGER):
        n = store.insert_items(
            conn,
            sid,
            [ParsedItem(guid="past", title="Past", summary="", link="",
                        published_at=past)],
        )

    assert n == 1
    row = _row(conn, "past")
    assert row is not None
    assert row["published_at"] == past
    assert _store_warnings(caplog) == []


def test_absent_and_unparseable_published_survive_while_future_sibling_is_clamped(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    """(e) A missing or unparseable published date keeps its existing behaviour
    — stored as-is, no clamp, no warning — and the gate stays selective: a
    far-future sibling in the SAME batch is still caught."""
    p, sid = _setup(tmp_path)
    _pin_fetch_time(monkeypatch, DEFECT_FETCHED_AT)
    conn = db.connect(p)
    with caplog.at_level(logging.WARNING, logger=STORE_LOGGER):
        n = store.insert_items(
            conn,
            sid,
            [
                ParsedItem(guid="absent", title="Absent", summary="", link="",
                           published_at=None),
                ParsedItem(guid="junk", title="Junk", summary="", link="",
                           published_at="not-a-date"),
                ParsedItem(guid="future", title="Future", summary="", link="",
                           published_at=DEFECT_PUBLISHED_AT),
            ],
        )

    # All three stored — the gate never drops an item.
    assert n == 3

    absent = _row(conn, "absent")
    assert absent is not None
    assert absent["published_at"] is None

    junk = _row(conn, "junk")
    assert junk is not None
    assert junk["published_at"] == "not-a-date"

    future = _row(conn, "future")
    assert future is not None
    assert future["published_at"] == DEFECT_FETCHED_AT

    warnings = _store_warnings(caplog)
    assert len(warnings) == 1
    assert "future" in warnings[0]
