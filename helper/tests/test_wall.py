"""Wall-config tests — the headless version's server-side wall state.

Two layers, mirroring the rest of the helper suite:
  - PURE: `wall.store` validation + default + save/load round-trip (no FastAPI);
  - WIRED: `/api/wall` GET (default when unset, stored after a PUT) + PUT
    validation against the live channel registry, via a real seeded DB +
    TestClient (the same harness as test_playlist_api.py).
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from mymts_helper.app import create_app
from mymts_helper.config import Config
from mymts_helper.wall import store


# ---------------------------------------------------------------- pure: store

VALID = ["bbc-news", "cnn", "cbs-sports-hq", "bloomberg-tv", "fox-weather"]


def _good_config(**over):
    base = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 2, "cols": 2},
        "preset": "news",
        "audible_cell": 0,
        "cells": [
            {"channel": "bbc-news", "subtitles": False},
            {"channel": "cnn", "subtitles": True},
            {"channel": None, "subtitles": False},
            {"channel": "cbs-sports-hq", "subtitles": False},
        ],
    }
    base.update(over)
    return base


def test_validate_accepts_and_normalises_a_good_config():
    out = store.validate_wall_config(_good_config(), set(VALID))
    assert out["layout"] == {"rows": 2, "cols": 2}
    assert out["audible_cell"] == 0
    assert out["cells"][1] == {"channel": "cnn", "subtitles": True}
    # extra keys are stripped; subtitles defaults present
    assert set(out.keys()) == {"schema_version", "layout", "preset", "audible_cell", "cells"}


def test_validate_strips_unknown_cell_keys_and_defaults_subtitles():
    cfg = _good_config(cells=[{"channel": "bbc-news", "bogus": 1}] + [{"channel": None}] * 3)
    out = store.validate_wall_config(cfg, set(VALID))
    assert out["cells"][0] == {"channel": "bbc-news", "subtitles": False}
    assert "bogus" not in out["cells"][0]


def test_validate_rejects_wrong_schema_version():
    with pytest.raises(store.WallConfigError, match="schema_version"):
        store.validate_wall_config(_good_config(schema_version=999), set(VALID))


@pytest.mark.parametrize("rows,cols", [(0, 2), (2, 4), (-1, 2), (2, 0)])
def test_validate_rejects_out_of_range_dims(rows, cols):
    cfg = _good_config(layout={"rows": rows, "cols": cols})
    with pytest.raises(store.WallConfigError, match="layout"):
        store.validate_wall_config(cfg, set(VALID))


def test_validate_rejects_cells_length_mismatch():
    cfg = _good_config(cells=[{"channel": None}])  # 1 cell for a 2x2
    with pytest.raises(store.WallConfigError, match="rows\\*cols"):
        store.validate_wall_config(cfg, set(VALID))


def test_validate_rejects_unknown_channel_slug():
    cfg = _good_config()
    cfg["cells"][0]["channel"] = "not-a-real-channel"
    with pytest.raises(store.WallConfigError, match="not a known/enabled channel"):
        store.validate_wall_config(cfg, set(VALID))


def test_validate_rejects_audible_pointing_at_empty_cell():
    cfg = _good_config(audible_cell=2)  # cell 2 has channel None
    with pytest.raises(store.WallConfigError, match="empty cell"):
        store.validate_wall_config(cfg, set(VALID))


def test_validate_rejects_audible_out_of_range():
    cfg = _good_config(audible_cell=9)
    with pytest.raises(store.WallConfigError, match="audible_cell"):
        store.validate_wall_config(cfg, set(VALID))


def test_validate_allows_null_audible_and_null_preset():
    out = store.validate_wall_config(_good_config(audible_cell=None, preset=None), set(VALID))
    assert out["audible_cell"] is None
    assert out["preset"] is None


# --- adversarial negative paths: every malformed shape rejected (not crashed) ---

def test_validate_rejects_non_object_top_level():
    with pytest.raises(store.WallConfigError, match="JSON object"):
        store.validate_wall_config([1, 2, 3], set(VALID))


@pytest.mark.parametrize("layout", [None, "2x2", 42, []])
def test_validate_rejects_layout_not_object(layout):
    with pytest.raises(store.WallConfigError, match="layout"):
        store.validate_wall_config(_good_config(layout=layout), set(VALID))


@pytest.mark.parametrize("bad", [True, False, "2", 2.5, None])
def test_validate_rejects_non_int_dims_including_bool(bad):
    # bool is a subclass of int in Python — the validator must exclude it so a
    # `true` can't sneak through as 1.
    with pytest.raises(store.WallConfigError, match="layout"):
        store.validate_wall_config(_good_config(layout={"rows": bad, "cols": 2}), set(VALID))


def test_validate_rejects_cells_not_a_list():
    with pytest.raises(store.WallConfigError, match="cells must be a list"):
        store.validate_wall_config(_good_config(cells={"0": {"channel": None}}), set(VALID))


def test_validate_rejects_non_object_cell():
    with pytest.raises(store.WallConfigError, match="cells\\[1\\]"):
        store.validate_wall_config(_good_config(cells=[{"channel": None}, "nope", {"channel": None}, {"channel": None}]), set(VALID))


def test_validate_rejects_non_string_channel():
    cfg = _good_config()
    cfg["cells"][0]["channel"] = 123
    with pytest.raises(store.WallConfigError, match="slug string or null"):
        store.validate_wall_config(cfg, set(VALID))


@pytest.mark.parametrize("bad", ["yes", 1, [], {}])
def test_validate_rejects_non_bool_subtitles(bad):
    cfg = _good_config()
    cfg["cells"][0]["subtitles"] = bad
    with pytest.raises(store.WallConfigError, match="subtitles must be"):
        store.validate_wall_config(cfg, set(VALID))


def test_validate_rejects_bool_audible_cell():
    # True is int 1, but the audible cell must be a real index, not a bool.
    with pytest.raises(store.WallConfigError, match="audible_cell"):
        store.validate_wall_config(_good_config(audible_cell=True), set(VALID))


def test_validate_rejects_non_string_preset():
    with pytest.raises(store.WallConfigError, match="preset"):
        store.validate_wall_config(_good_config(preset=7), set(VALID))


def test_default_config_fills_from_news_preset_and_is_valid():
    cfg = store.default_wall_config(VALID)
    assert cfg["layout"] == {"rows": 2, "cols": 2}
    assert len(cfg["cells"]) == 4
    # default fills with news-preset slugs that actually exist; the rest empty.
    filled = [c["channel"] for c in cfg["cells"] if c["channel"]]
    assert all(s in VALID for s in filled)
    # the default must itself pass validation (no self-inconsistent default)
    store.validate_wall_config(cfg, set(VALID))


def test_default_config_omits_vanished_news_slugs():
    # only one news slug exists → only that cell is filled, never a dead slug.
    cfg = store.default_wall_config(["bbc-news"])
    assert [c["channel"] for c in cfg["cells"]] == ["bbc-news", None, None, None]


def test_save_then_load_round_trips(tmp_path: Path):
    cfg = store.validate_wall_config(_good_config(), set(VALID))
    store.save_wall_config(tmp_path, cfg)
    loaded, stored = store.load_wall_config(tmp_path, VALID)
    assert stored is True
    assert loaded == cfg


def test_load_returns_default_when_absent(tmp_path: Path):
    loaded, stored = store.load_wall_config(tmp_path, VALID)
    assert stored is False
    assert loaded == store.default_wall_config(VALID)


def test_load_degrades_to_default_on_corrupt_file(tmp_path: Path):
    store.wall_config_path(tmp_path).write_text("{ not json ]")
    loaded, stored = store.load_wall_config(tmp_path, VALID)
    assert stored is False
    assert loaded["layout"] == {"rows": 2, "cols": 2}


def test_load_degrades_when_stored_slug_vanished(tmp_path: Path):
    # a stored config referencing a now-unknown channel re-validates → falls back
    cfg = _good_config()
    store.wall_config_path(tmp_path).write_text(json.dumps(cfg))
    loaded, stored = store.load_wall_config(tmp_path, ["fox-weather"])  # bbc-news gone
    assert stored is False


# --------------------------------------------------------------- wired: /api/wall

async def _block_all_resolver(host: str) -> list[str]:
    return ["127.0.0.1"]


def _cfg(tmp_path: Path) -> Config:
    return Config(
        phantom_mode=False,
        port=8091,
        log_level="warning",
        build_sha="dev",
        build_version="0.0.0-dev",
        data_dir=str(tmp_path),
        feed_poll_interval_seconds=3600,
        channel_probe_interval_seconds=3600,
    )


def _client(tmp_path: Path) -> TestClient:
    return TestClient(create_app(_cfg(tmp_path), resolver=_block_all_resolver))


def test_get_wall_returns_default_unset(tmp_path: Path):
    r = _client(tmp_path).get("/api/wall")
    assert r.status_code == 200
    body = r.json()
    assert body["stored"] is False
    assert body["schema_version"] == store.WALL_SCHEMA_VERSION
    assert body["layout"] == {"rows": 2, "cols": 2}
    assert len(body["cells"]) == 4


def test_put_then_get_round_trips_and_marks_stored(tmp_path: Path):
    client = _client(tmp_path)
    # the seeded registry has bbc-news; assign it to cell 0 with audio + subs.
    payload = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 1, "cols": 2},
        "preset": "news",
        "audible_cell": 0,
        "cells": [
            {"channel": "bbc-news", "subtitles": True},
            {"channel": None, "subtitles": False},
        ],
    }
    put = client.put("/api/wall", json=payload)
    assert put.status_code == 200, put.text
    assert put.json()["stored"] is True

    got = client.get("/api/wall").json()
    assert got["stored"] is True
    assert got["layout"] == {"rows": 1, "cols": 2}
    assert got["cells"][0] == {"channel": "bbc-news", "subtitles": True}
    assert got["audible_cell"] == 0
    # persisted to the data dir as the gitignored runtime file
    assert store.wall_config_path(tmp_path).is_file()


def test_put_rejects_unknown_channel_with_422(tmp_path: Path):
    payload = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 1, "cols": 1},
        "cells": [{"channel": "totally-made-up", "subtitles": False}],
        "audible_cell": None,
    }
    r = _client(tmp_path).put("/api/wall", json=payload)
    assert r.status_code == 422
    assert "not a known/enabled channel" in r.json()["detail"]


def test_put_rejects_audible_empty_cell_with_422(tmp_path: Path):
    payload = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 1, "cols": 1},
        "cells": [{"channel": None, "subtitles": False}],
        "audible_cell": 0,
    }
    r = _client(tmp_path).put("/api/wall", json=payload)
    assert r.status_code == 422
    assert "empty cell" in r.json()["detail"]
