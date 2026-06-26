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
    assert out["cells"][1] == {"channel": "cnn", "subtitles": True, "reload": 0}
    # extra keys are stripped; subtitles + reload + render defaults present
    assert set(out.keys()) == {
        "schema_version", "layout", "preset", "audible_cell",
        "reload_epoch", "render", "cells",
    }


def test_validate_strips_unknown_cell_keys_and_defaults_subtitles():
    cfg = _good_config(cells=[{"channel": "bbc-news", "bogus": 1}] + [{"channel": None}] * 3)
    out = store.validate_wall_config(cfg, set(VALID))
    assert out["cells"][0] == {"channel": "bbc-news", "subtitles": False, "reload": 0}
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


# --- force-reload epochs (additive to schema v1: default 0, validated >= 0) ---


def test_validate_defaults_reload_epochs_to_zero():
    out = store.validate_wall_config(_good_config(), set(VALID))
    assert out["reload_epoch"] == 0
    assert all(c["reload"] == 0 for c in out["cells"])


def test_validate_preserves_bumped_reload_epochs():
    cfg = _good_config(reload_epoch=4)
    cfg["cells"][0]["reload"] = 2
    out = store.validate_wall_config(cfg, set(VALID))
    assert out["reload_epoch"] == 4
    assert out["cells"][0]["reload"] == 2


@pytest.mark.parametrize("bad", [-1, True, 2.5, "3", []])
def test_validate_rejects_bad_wall_reload_epoch(bad):
    with pytest.raises(store.WallConfigError, match="reload_epoch"):
        store.validate_wall_config(_good_config(reload_epoch=bad), set(VALID))


@pytest.mark.parametrize("bad", [-1, True, 2.5, "3"])
def test_validate_rejects_bad_cell_reload_epoch(bad):
    cfg = _good_config()
    cfg["cells"][0]["reload"] = bad
    with pytest.raises(store.WallConfigError, match=r"cells\[0\].reload"):
        store.validate_wall_config(cfg, set(VALID))


def test_default_config_includes_zero_reload_epochs():
    cfg = store.default_wall_config(VALID)
    assert cfg["reload_epoch"] == 0
    assert all(c["reload"] == 0 for c in cfg["cells"])


def test_clamp_reload_monotonic_never_decreases_counters():
    old = store.validate_wall_config(_good_config(reload_epoch=5), set(VALID))
    old["cells"][1]["reload"] = 3
    old = store.validate_wall_config(old, set(VALID))
    # a stale write echoing LOWER counters (e.g. an /app/ edit before its hydrate)
    new = store.validate_wall_config(_good_config(reload_epoch=0), set(VALID))
    clamped = store.clamp_reload_monotonic(new, old)
    assert clamped["reload_epoch"] == 5            # not rewound to 0
    assert clamped["cells"][1]["reload"] == 3      # per-cell not rewound either


def test_clamp_reload_monotonic_allows_an_increase():
    old = store.validate_wall_config(_good_config(reload_epoch=2), set(VALID))
    new = store.validate_wall_config(_good_config(reload_epoch=3), set(VALID))
    new["cells"][0]["reload"] = 1
    new = store.validate_wall_config(new, set(VALID))
    clamped = store.clamp_reload_monotonic(new, old)
    assert clamped["reload_epoch"] == 3            # a genuine bump goes through
    assert clamped["cells"][0]["reload"] == 1


def test_clamp_reload_monotonic_handles_a_grid_resize():
    # old 2x2 (4 cells), new 1x2 (2 cells) — clamp by index, new cells with no old
    # counterpart floor at their own value (no IndexError).
    old = store.validate_wall_config(_good_config(reload_epoch=4), set(VALID))
    new = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 1, "cols": 2}, "preset": None, "audible_cell": None,
        "reload_epoch": 0,
        "cells": [{"channel": "bbc-news", "subtitles": False, "reload": 0},
                  {"channel": None, "subtitles": False, "reload": 0}],
    }
    clamped = store.clamp_reload_monotonic(new, old)
    assert clamped["reload_epoch"] == 4
    assert len(clamped["cells"]) == 2


def test_put_cannot_rewind_a_reload_counter(tmp_path: Path):
    client = _client(tmp_path)
    base = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 1, "cols": 1}, "preset": "news", "audible_cell": None,
        "cells": [{"channel": "bbc-news", "subtitles": False, "reload": 0}],
    }
    # /control/ bumps the whole-wall reload to 3
    client.put("/api/wall", json={**base, "reload_epoch": 3})
    # a stale /app/ edit echoes reload_epoch 0 — the server must NOT rewind it
    got = client.put("/api/wall", json={**base, "reload_epoch": 0}).json()
    assert got["reload_epoch"] == 3
    assert client.get("/api/wall").json()["reload_epoch"] == 3


def test_bumped_reload_epochs_survive_save_load_round_trip(tmp_path: Path):
    cfg = store.validate_wall_config(_good_config(reload_epoch=9), set(VALID))
    cfg["cells"][1]["reload"] = 3
    cfg = store.validate_wall_config(cfg, set(VALID))   # re-validate post-edit
    store.save_wall_config(tmp_path, cfg)
    loaded, stored = store.load_wall_config(tmp_path, VALID)
    assert stored is True
    assert loaded["reload_epoch"] == 9
    assert loaded["cells"][1]["reload"] == 3


def test_put_persists_a_force_reload_bump(tmp_path: Path):
    client = _client(tmp_path)
    payload = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 1, "cols": 1},
        "preset": "news",
        "audible_cell": None,
        "reload_epoch": 1,
        "cells": [{"channel": "bbc-news", "subtitles": False, "reload": 2}],
    }
    put = client.put("/api/wall", json=payload)
    assert put.status_code == 200, put.text
    got = client.get("/api/wall").json()
    assert got["reload_epoch"] == 1
    assert got["cells"][0]["reload"] == 2


# --- render resolution (additive to schema v1: default 1080p, validated enum) ---


def test_validate_defaults_render_to_1080p():
    out = store.validate_wall_config(_good_config(), set(VALID))
    assert out["render"] == {"resolution": "1080p"}


def test_validate_accepts_a_known_resolution():
    out = store.validate_wall_config(_good_config(render={"resolution": "2160p"}), set(VALID))
    assert out["render"] == {"resolution": "2160p"}


@pytest.mark.parametrize("bad", [{"resolution": "720p"}, {"resolution": 1080}, {"resolution": None}])
def test_validate_rejects_unknown_resolution(bad):
    with pytest.raises(store.WallConfigError, match="render.resolution"):
        store.validate_wall_config(_good_config(render=bad), set(VALID))


@pytest.mark.parametrize("bad", ["1080p", 42, []])
def test_validate_rejects_non_object_render(bad):
    with pytest.raises(store.WallConfigError, match="render must be an object"):
        store.validate_wall_config(_good_config(render=bad), set(VALID))


def test_old_stored_file_without_render_loads_and_defaults(tmp_path: Path):
    # an additive-field discipline check: a config written before `render` existed
    cfg = _good_config()
    cfg.pop("render", None)
    store.wall_config_path(tmp_path).write_text(json.dumps(cfg))
    loaded, stored = store.load_wall_config(tmp_path, VALID)
    assert stored is True
    assert loaded["render"] == {"resolution": "1080p"}


def test_default_config_includes_1080p_render():
    cfg = store.default_wall_config(VALID)
    assert cfg["render"] == {"resolution": "1080p"}


def test_clamp_reload_monotonic_passes_render_through():
    old = store.validate_wall_config(_good_config(render={"resolution": "2160p"}), set(VALID))
    new = store.validate_wall_config(_good_config(render={"resolution": "2160p"}), set(VALID))
    clamped = store.clamp_reload_monotonic(new, old)
    assert clamped["render"] == {"resolution": "2160p"}


# --- partial-merge (PATCH) write: absent preserves, present overrides ---


def test_merge_preserves_omitted_top_level_fields():
    stored = store.validate_wall_config(
        _good_config(reload_epoch=5, render={"resolution": "2160p"}), set(VALID)
    )
    # incoming touches only `preset` — everything else must be preserved
    merged = store.merge_wall_config(stored, {"preset": "weather"})
    assert merged["preset"] == "weather"                    # overridden
    assert merged["reload_epoch"] == 5                      # preserved (omitted)
    assert merged["render"] == {"resolution": "2160p"}      # preserved (omitted)
    assert merged["cells"] == stored["cells"]               # preserved (omitted)
    assert merged["layout"] == stored["layout"]             # preserved (omitted)


def test_merge_cells_preserves_omitted_per_cell_fields():
    stored = store.validate_wall_config(_good_config(), set(VALID))
    stored["cells"][0]["reload"] = 4
    # an /app/-style cells write: channel + subtitles only, NO per-cell reload
    incoming = {"cells": [
        {"channel": "cnn", "subtitles": True},
        {"channel": None}, {"channel": None}, {"channel": None},
    ]}
    merged = store.merge_wall_config(stored, incoming)
    assert merged["cells"][0]["channel"] == "cnn"           # overridden
    assert merged["cells"][0]["subtitles"] is True          # overridden
    assert merged["cells"][0]["reload"] == 4                # preserved (omitted per-cell)


def test_merge_explicit_null_clears_present_field():
    # absent = preserve, but an EXPLICIT null is a PRESENT value → it overrides
    stored = store.validate_wall_config(_good_config(audible_cell=0), set(VALID))
    assert store.merge_wall_config(stored, {"audible_cell": None})["audible_cell"] is None


def test_merge_non_dict_passes_through_for_the_validator_to_reject():
    stored = store.validate_wall_config(_good_config(), set(VALID))
    assert store.merge_wall_config(stored, [1, 2, 3]) == [1, 2, 3]
    # and a non-list cells value rides through untouched (validator rejects it)
    assert store.merge_wall_config(stored, {"cells": "nope"})["cells"] == "nope"


def test_put_round_trips_the_resolution(tmp_path: Path):
    client = _client(tmp_path)
    payload = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 1, "cols": 1}, "preset": "news", "audible_cell": None,
        "render": {"resolution": "2160p"},
        "cells": [{"channel": "bbc-news", "subtitles": False}],
    }
    put = client.put("/api/wall", json=payload)
    assert put.status_code == 200, put.text
    assert put.json()["render"] == {"resolution": "2160p"}
    assert client.get("/api/wall").json()["render"] == {"resolution": "2160p"}


def test_put_omitting_render_preserves_the_stored_resolution(tmp_path: Path):
    # REGRESSION (now via the partial-merge, not a per-field carry-forward): an
    # /app/-style PUT that writes only channels/grid/audio (no render) must PRESERVE
    # the stored resolution, never silently revert a /control/-set 4K wall to 1080p.
    client = _client(tmp_path)
    base = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 1, "cols": 1}, "preset": "news", "audible_cell": None,
        "cells": [{"channel": "bbc-news", "subtitles": False}],
    }
    # /control/ sets 4K
    client.put("/api/wall", json={**base, "render": {"resolution": "2160p"}})
    # an /app/-style edit omits render entirely — must NOT downgrade to 1080p
    got = client.put("/api/wall", json=base).json()
    assert got["render"] == {"resolution": "2160p"}
    assert client.get("/api/wall").json()["render"] == {"resolution": "2160p"}
    # but /control/ can still explicitly downgrade
    back = client.put("/api/wall", json={**base, "render": {"resolution": "1080p"}}).json()
    assert back["render"] == {"resolution": "1080p"}


def test_partial_write_preserves_EVERY_omitted_field(tmp_path: Path):
    """THE CLASS-KILLER, generalized: establish a fully-customized wall, then for
    EVERY top-level field PUT a payload that omits ONLY that field and assert the
    stored value survives. Iterates the config's own keys, so a NEW field added to
    the schema is automatically covered by this invariant — no per-field defence
    needed. This is what makes the reload_epoch / per-cell-reload / render clobber
    class structurally impossible to recur."""
    client = _client(tmp_path)
    full = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 1, "cols": 2},
        "preset": "news",
        "audible_cell": 0,
        "reload_epoch": 7,
        "render": {"resolution": "2160p"},
        "cells": [
            {"channel": "bbc-news", "subtitles": True, "reload": 3},
            {"channel": "cnn", "subtitles": False, "reload": 0},
        ],
    }
    client.put("/api/wall", json=full)
    stored = client.get("/api/wall").json()
    stored.pop("stored", None)
    # sanity: every field round-tripped non-default so a revert WOULD be detectable
    assert stored["render"] == {"resolution": "2160p"} and stored["reload_epoch"] == 7

    for field in list(stored.keys()):
        partial = {k: v for k, v in stored.items() if k != field}
        got = client.put("/api/wall", json=partial)
        assert got.status_code == 200, f"omitting {field!r}: {got.text}"
        body = got.json()
        assert body[field] == stored[field], f"omitting {field!r} clobbered it"
    # NOTE: for `reload_epoch` (and per-cell `reload`) the preservation is ALSO
    # backstopped by clamp_reload_monotonic, so this end-to-end invariant can't
    # alone prove the MERGE preserves them — that merge-isolated coverage is
    # test_merge_preserves_omitted_top_level_fields (pure, no clamp). Every other
    # field here is merge-only.


def test_put_omitting_per_cell_field_preserves_it(tmp_path: Path):
    # the per-cell sub-class, end-to-end. The write sends ONLY `channel`, omitting
    # both `subtitles` and `reload`. `subtitles` preservation proves the MERGE in
    # isolation (the clamp doesn't touch subtitles); `reload` preservation is the
    # merge + the clamp backstop.
    client = _client(tmp_path)
    full = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 1, "cols": 1}, "preset": "news", "audible_cell": None,
        "cells": [{"channel": "bbc-news", "subtitles": True, "reload": 4}],
    }
    client.put("/api/wall", json=full)
    got = client.put("/api/wall", json={**full, "cells": [{"channel": "cnn"}]}).json()
    assert got["cells"][0]["channel"] == "cnn"          # overridden
    assert got["cells"][0]["subtitles"] is True         # PRESERVED (merge-only — clamp-free field)
    assert got["cells"][0]["reload"] == 4               # preserved (merge + clamp)


def test_partial_write_rejects_an_inconsistent_merge(tmp_path: Path):
    # post-merge validation still applies: a layout change with no matching cells
    # leaves the stored (mismatched-count) cells → rejected, not half-applied.
    client = _client(tmp_path)
    base = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 2, "cols": 2}, "preset": "news", "audible_cell": None,
        "cells": [{"channel": None}] * 4,
    }
    client.put("/api/wall", json=base)
    r = client.put("/api/wall", json={"layout": {"rows": 1, "cols": 1}})  # no cells
    assert r.status_code == 422
    assert "rows*cols" in r.json()["detail"]


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
    assert got["cells"][0] == {"channel": "bbc-news", "subtitles": True, "reload": 0}
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
