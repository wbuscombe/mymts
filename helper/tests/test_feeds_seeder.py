"""Tests for the boot-time feed-source seeder."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from mymts_helper import db
from mymts_helper.feeds import seeder, store


@pytest.fixture
def conn(tmp_path: Path):
    db_path = tmp_path / "test.db"
    db.migrate(db_path)
    c = db.connect(db_path)
    yield c
    c.close()


def _write_seed(path: Path, entries: list[dict[str, str]]) -> Path:
    f = path / "seed.json"
    f.write_text(json.dumps(entries), encoding="utf-8")
    return f


def test_missing_file_is_a_soft_noop(conn, tmp_path: Path) -> None:
    missing = tmp_path / "no-such-seed.json"
    assert seeder.seed_from_file(conn, missing) == 0
    assert store.list_sources(conn) == []


def test_well_formed_entries_seed_idempotently(conn, tmp_path: Path) -> None:
    f = _write_seed(
        tmp_path,
        [
            {"url": "https://feeds.bbci.co.uk/news/world/rss.xml", "label": "BBC World"},
            {"url": "https://www.aljazeera.com/xml/rss/all.xml", "label": "Al Jazeera"},
        ],
    )
    n = seeder.seed_from_file(conn, f)
    assert n == 2
    rows = store.list_sources(conn)
    assert sorted(s.label for s in rows) == ["Al Jazeera", "BBC World"]

    # Re-running the seeder upserts in place — no duplicates.
    again = seeder.seed_from_file(conn, f)
    assert again == 2
    assert len(store.list_sources(conn)) == 2


def test_relabel_via_seed_takes_effect(conn, tmp_path: Path) -> None:
    f = _write_seed(
        tmp_path,
        [{"url": "https://example.test/rss", "label": "Example v1"}],
    )
    seeder.seed_from_file(conn, f)

    f.write_text(
        json.dumps([{"url": "https://example.test/rss", "label": "Example v2"}]),
        encoding="utf-8",
    )
    seeder.seed_from_file(conn, f)

    rows = store.list_sources(conn)
    assert len(rows) == 1
    assert rows[0].label == "Example v2"


def test_invalid_json_raises_seeder_error(conn, tmp_path: Path) -> None:
    f = tmp_path / "seed.json"
    f.write_text("{ this is not json", encoding="utf-8")
    with pytest.raises(seeder.SeederError):
        seeder.seed_from_file(conn, f)


def test_root_not_a_list_raises(conn, tmp_path: Path) -> None:
    f = tmp_path / "seed.json"
    f.write_text(json.dumps({"url": "x", "label": "y"}), encoding="utf-8")
    with pytest.raises(seeder.SeederError, match="not_list"):
        seeder.seed_from_file(conn, f)


def test_entry_with_missing_url_is_skipped_not_fatal(conn, tmp_path: Path) -> None:
    f = _write_seed(
        tmp_path,
        [
            {"label": "no-url"},
            {"url": "https://ok.example/rss", "label": "ok"},
        ],
    )
    n = seeder.seed_from_file(conn, f)
    assert n == 1
    assert {s.label for s in store.list_sources(conn)} == {"ok"}


def test_entry_with_empty_url_is_skipped(conn, tmp_path: Path) -> None:
    f = _write_seed(
        tmp_path,
        [
            {"url": "", "label": "blank"},
            {"url": "https://ok.example/rss", "label": "ok"},
        ],
    )
    n = seeder.seed_from_file(conn, f)
    assert n == 1
