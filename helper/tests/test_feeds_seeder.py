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


def test_seed_prunes_repointed_source_and_its_items(conn, tmp_path: Path) -> None:
    """seed.json is authoritative: changing a source's URL prunes the OLD url
    (and its items, via cascade) instead of leaving it orphaned + still polled.
    This is the NBC regression — the old Spanish-serving feed must not linger."""
    from mymts_helper.feeds.parser import ParsedItem

    f = _write_seed(
        tmp_path,
        [
            {"url": "https://feeds.nbcnews.com/nbcnews/public/news", "label": "NBC News"},
            {"url": "https://ok.example/rss", "label": "Keep"},
        ],
    )
    seeder.seed_from_file(conn, f)
    old_id = next(
        s.id for s in store.list_sources(conn)
        if s.url == "https://feeds.nbcnews.com/nbcnews/public/news"
    )
    store.insert_items(
        conn,
        old_id,
        [ParsedItem(
            guid="g1", title="¡Vive el Mundial!", summary=None, link=None, published_at=None,
        )],
    )
    conn.commit()
    assert store.total_items(conn) == 1

    # Repoint NBC to the English feed — the old url + its item must be gone.
    f.write_text(
        json.dumps(
            [
                {"url": "https://feeds.nbcnews.com/nbcnews/public/us-news", "label": "NBC News"},
                {"url": "https://ok.example/rss", "label": "Keep"},
            ]
        ),
        encoding="utf-8",
    )
    seeder.seed_from_file(conn, f)

    urls = {s.url for s in store.list_sources(conn)}
    assert urls == {
        "https://feeds.nbcnews.com/nbcnews/public/us-news",
        "https://ok.example/rss",
    }
    assert "https://feeds.nbcnews.com/nbcnews/public/news" not in urls
    assert store.total_items(conn) == 0  # the orphaned (Spanish) item cascaded away


def test_empty_or_broken_seed_does_not_wipe_existing_sources(conn, tmp_path: Path) -> None:
    """Safety guard: a seed that yields no valid URLs must NOT prune the table
    to zero — better to keep the prior sources than to blank the wall."""
    f = _write_seed(tmp_path, [{"url": "https://keep.example/rss", "label": "Keep"}])
    seeder.seed_from_file(conn, f)
    f.write_text(json.dumps([]), encoding="utf-8")
    assert seeder.seed_from_file(conn, f) == 0
    assert {s.url for s in store.list_sources(conn)} == {"https://keep.example/rss"}


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


# ---- shipped seed.json validity (feed-sources expansion) ----


def _load_shipped_seed() -> list[dict[str, str]]:
    from importlib.resources import files

    raw = files("mymts_helper.feeds").joinpath("seed.json").read_text(encoding="utf-8")
    return json.loads(raw)


def test_shipped_seed_is_wellformed_https_and_unique() -> None:
    """Guard the packaged feed seed after the source-set expansion.

    Every shipped source must be a non-empty https URL with a non-empty
    label; URLs and labels must be unique (a dup label would collapse two
    sources into one feed section). The SSRF-safe fetcher rejects non-https
    at runtime, but catching it here keeps a bad edit out of the image.
    """
    entries = _load_shipped_seed()
    assert isinstance(entries, list) and entries, "seed.json must be a non-empty list"

    urls = [e["url"] for e in entries]
    labels = [e["label"] for e in entries]

    for e in entries:
        assert e["url"].startswith("https://"), f"non-https source in seed: {e['url']}"
        assert e["label"].strip(), f"empty label for {e['url']}"

    assert len(urls) == len(set(urls)), "duplicate source URL in seed.json"
    assert len(labels) == len(set(labels)), "duplicate source label in seed.json"


def test_shipped_seed_seeds_every_source(conn) -> None:
    """The shipped seed must upsert every entry — no silent drops — and the
    count must match the file (a regression guard for the expanded set)."""
    from importlib.resources import files

    seed_path = Path(str(files("mymts_helper.feeds").joinpath("seed.json")))
    expected = len(_load_shipped_seed())
    seeded = seeder.seed_from_file(conn, seed_path)
    assert seeded == expected, f"seeded {seeded} but seed.json has {expected} entries"
    assert len(store.list_sources(conn)) == expected

    # The four original sources are still present after the expansion.
    labels = {s.label for s in store.list_sources(conn)}
    for original in ("BBC World", "Al Jazeera", "Guardian World", "NPR World"):
        assert original in labels, f"expansion dropped original source: {original}"


def test_nbc_source_uses_the_english_topic_feed_not_the_mixed_top_stories() -> None:
    """NBC's generic top-stories feed (`/nbcnews/public/news`) aggregates
    Telemundo Spanish-language content (e.g. World Cup "Vive el Mundial"
    items) despite a lying `<language>en-US` tag — the operator wants English
    only. The NBC source must point at the scoped English topic feed, never
    that mixed top-stories endpoint. Static-config guard (no network)."""
    entries = _load_shipped_seed()
    nbc = [e for e in entries if e["label"] == "NBC News"]
    assert len(nbc) == 1, "expected exactly one 'NBC News' source"
    url = nbc[0]["url"]
    assert url == "https://feeds.nbcnews.com/nbcnews/public/us-news"
    # the Spanish-polluted top-stories aggregator must not creep back in
    assert not url.endswith("/public/news"), "reverted to the mixed top-stories feed"
    assert "telemundo" not in url.lower()
