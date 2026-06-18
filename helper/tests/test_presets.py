"""Server-authoritative wall presets — the /api/presets contract, every preset
slug references a REAL seeded channel (no dead tiles), and the News preset mirrors
the clients' default lineup (the no-regression anchor)."""

from __future__ import annotations

import json
from importlib.resources import files
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from mymts_helper.app import create_app
from mymts_helper.channels.presets import DEFAULT_PRESET_ID, PRESETS
from mymts_helper.config import Config


@pytest.fixture
def client(tmp_path: Path):
    cfg = Config(
        phantom_mode=True, port=8091, log_level="warning",
        build_sha="dev", build_version="0.0.0-dev", data_dir=str(tmp_path),
        feed_poll_interval_seconds=3600, feed_retention_days=14,
        channel_probe_interval_seconds=3600,
    )
    with TestClient(create_app(cfg)) as c:
        yield c


def _seed_slugs() -> set[str]:
    raw = files("mymts_helper.channels").joinpath("seed.json").read_text()
    return {c["slug"] for c in json.loads(raw)}


def test_presets_endpoint_shape(client) -> None:
    body = client.get("/api/presets").json()
    assert body["schema_version"] == 1
    assert body["default"] == "news"
    ids = [p["id"] for p in body["presets"]]
    assert ids == ["news", "nature", "space", "chill"]
    for p in body["presets"]:
        assert p["name"] and isinstance(p["slugs"], list) and p["slugs"]
        assert p["fill"] in ("topup", "exact")
        assert "rows" in p["grid"] and "cols" in p["grid"]


def test_every_preset_slug_is_a_real_seeded_channel() -> None:
    seeded = _seed_slugs()
    for p in PRESETS:
        for slug in p["slugs"]:
            assert slug in seeded, f"preset {p['id']!r} references unseeded slug {slug!r}"


def test_news_preset_is_the_default_and_mirrors_preferred() -> None:
    # The News preset must match the clients' default lineup so 'news' (the default)
    # is a no-op — the wall is identical to today until the user switches. This list
    # mirrors native LineupSelector.PREFERRED and web WEB_DEFAULT_LINEUP.
    assert DEFAULT_PRESET_ID == "news"
    news = next(p for p in PRESETS if p["id"] == "news")
    assert news["slugs"] == [
        "livenow-fox", "fox-weather", "bbc-news", "cbs-sports-hq", "bloomberg-tv", "cnbc", "cnn",
    ]
    assert news["fill"] == "topup"


def test_non_default_presets_are_exact() -> None:
    for p in PRESETS:
        if p["id"] != "news":
            assert p["fill"] == "exact", f"{p['id']} should be a curated exact set"
