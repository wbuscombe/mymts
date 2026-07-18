"""Wall-config tests — the headless version's server-side wall state.

Two layers, mirroring the rest of the helper suite:
  - PURE: `wall.store` validation + default + save/load round-trip (no FastAPI);
  - WIRED: `/api/wall` GET (default when unset, stored after a PUT) + PUT
    validation against the live channel registry, via a real seeded DB +
    TestClient (the same harness as test_playlist_api.py).

Schema v1 (post-fan-out): the single `audible_cell` pointer is retired in favour
of per-cell `audio`, and the single `render.resolution` is superseded by an
`outputs` map (hls, with its own resolution + bitrate). The
load-time migration upgrades an old stored file in memory.
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

EXPECTED_KEYS = {
    "schema_version", "layout", "preset", "reload_epoch",
    "feed_pct", "feed_font", "ticker_scale", "outputs", "cells",
}


def _good_config(**over):
    base = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 2, "cols": 2},
        "preset": "news",
        "cells": [
            {"channel": "bbc-news", "audio": True, "subtitles": False},
            {"channel": "cnn", "audio": False, "subtitles": True},
            {"channel": None, "audio": False, "subtitles": False},
            {"channel": "cbs-sports-hq", "audio": False, "subtitles": False},
        ],
    }
    base.update(over)
    return base


def test_validate_accepts_and_normalises_a_good_config():
    out = store.validate_wall_config(_good_config(), set(VALID))
    assert out["layout"] == {"rows": 2, "cols": 2}
    assert out["cells"][1] == {"channel": "cnn", "audio": False, "subtitles": True, "reload": 0}
    assert out["cells"][0]["audio"] is True
    # extra keys are stripped; all additive defaults present; render/audible_cell GONE
    assert set(out.keys()) == EXPECTED_KEYS
    assert "render" not in out and "audible_cell" not in out


def test_validate_strips_unknown_cell_keys_and_defaults_flags():
    cfg = _good_config(cells=[{"channel": "bbc-news", "bogus": 1}] + [{"channel": None}] * 3)
    out = store.validate_wall_config(cfg, set(VALID))
    assert out["cells"][0] == {
        "channel": "bbc-news", "audio": False, "subtitles": False, "reload": 0,
    }
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


def test_validate_allows_null_preset():
    out = store.validate_wall_config(_good_config(preset=None), set(VALID))
    assert out["preset"] is None


# --- per-tile audio (default false; ANY combination allowed; no empty-cell rule) ---


def test_validate_defaults_audio_false():
    out = store.validate_wall_config(
        _good_config(cells=[{"channel": "bbc-news"}] + [{"channel": None}] * 3), set(VALID)
    )
    assert all(c["audio"] is False for c in out["cells"])


def test_validate_allows_multiple_audio_cells():
    cfg = _good_config(cells=[
        {"channel": "bbc-news", "audio": True},
        {"channel": "cnn", "audio": True},
        {"channel": "bloomberg-tv", "audio": True},
        {"channel": None, "audio": False},
    ])
    out = store.validate_wall_config(cfg, set(VALID))
    assert [c["audio"] for c in out["cells"]] == [True, True, True, False]


@pytest.mark.parametrize(
    "val,expect", [(True, True), (False, False), (1, True), (0, False), (None, False)]
)
def test_validate_coerces_audio_to_bool(val, expect):
    cfg = _good_config(cells=[{"channel": "bbc-news", "audio": val}] + [{"channel": None}] * 3)
    assert store.validate_wall_config(cfg, set(VALID))["cells"][0]["audio"] is expect


# --- MIGRATION: the retired audible_cell folds into per-cell audio ---


def test_migration_audible_cell_becomes_cell_audio():
    cfg = _good_config(audible_cell=1, cells=[
        {"channel": "bbc-news"}, {"channel": "cnn"}, {"channel": None}, {"channel": None},
    ])
    out = store.validate_wall_config(cfg, set(VALID))
    assert out["cells"][1]["audio"] is True   # the audible cell migrated
    assert out["cells"][0]["audio"] is False
    assert "audible_cell" not in out


def test_migration_explicit_cell_audio_wins_over_audible_cell():
    # a cell that ALSO carries an explicit audio flag keeps it (the explicit value wins)
    cfg = _good_config(audible_cell=0, cells=[
        {"channel": "bbc-news", "audio": False}, {"channel": "cnn"},
        {"channel": None}, {"channel": None},
    ])
    out = store.validate_wall_config(cfg, set(VALID))
    assert out["cells"][0]["audio"] is False   # explicit false beats the legacy pointer


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
    with pytest.raises(store.WallConfigError, match="layout"):
        store.validate_wall_config(_good_config(layout={"rows": bad, "cols": 2}), set(VALID))


def test_validate_rejects_cells_not_a_list():
    with pytest.raises(store.WallConfigError, match="cells must be a list"):
        store.validate_wall_config(_good_config(cells={"0": {"channel": None}}), set(VALID))


def test_validate_rejects_non_object_cell():
    with pytest.raises(store.WallConfigError, match="cells\\[1\\]"):
        cfg = _good_config(cells=[{"channel": None}, "nope", {"channel": None}, {"channel": None}])
        store.validate_wall_config(cfg, set(VALID))


def test_validate_rejects_non_string_channel():
    cfg = _good_config()
    cfg["cells"][0]["channel"] = 123
    with pytest.raises(store.WallConfigError, match="slug string or null"):
        store.validate_wall_config(cfg, set(VALID))


@pytest.mark.parametrize("bad", ["yes", [], {}])
def test_validate_rejects_non_bool_subtitles(bad):
    cfg = _good_config()
    cfg["cells"][0]["subtitles"] = bad
    with pytest.raises(store.WallConfigError, match="subtitles must be"):
        store.validate_wall_config(cfg, set(VALID))


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
    old = store.validate_wall_config(_good_config(reload_epoch=4), set(VALID))
    new = store.validate_wall_config({
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 1, "cols": 2}, "preset": None, "reload_epoch": 0,
        "cells": [{"channel": "bbc-news"}, {"channel": None}],
    }, set(VALID))
    clamped = store.clamp_reload_monotonic(new, old)
    assert clamped["reload_epoch"] == 4
    assert len(clamped["cells"]) == 2


def test_put_cannot_rewind_a_reload_counter(tmp_path: Path):
    client = _client(tmp_path)
    base = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 1, "cols": 1}, "preset": "news",
        "cells": [{"channel": "bbc-news"}],
    }
    client.put("/api/wall", json={**base, "reload_epoch": 3})
    got = client.put("/api/wall", json={**base, "reload_epoch": 0}).json()
    assert got["reload_epoch"] == 3
    assert client.get("/api/wall").json()["reload_epoch"] == 3


def test_bumped_reload_epochs_survive_save_load_round_trip(tmp_path: Path):
    cfg = store.validate_wall_config(_good_config(reload_epoch=9), set(VALID))
    cfg["cells"][1]["reload"] = 3
    cfg = store.validate_wall_config(cfg, set(VALID))
    store.save_wall_config(tmp_path, cfg)
    loaded, stored = store.load_wall_config(tmp_path, VALID)
    assert stored is True
    assert loaded["reload_epoch"] == 9
    assert loaded["cells"][1]["reload"] == 3


# --- outputs: the multi-output fan-out (hls) ---


def test_default_outputs_shape():
    o = store.default_wall_config(VALID)["outputs"]
    assert set(o) == {"hls"}
    assert o["hls"]["enabled"] is True and o["hls"]["resolution"] == "1080p"
    assert "restart_epoch" in o["hls"]


def test_validate_defaults_outputs_when_absent():
    out = store.validate_wall_config(_good_config(), set(VALID))
    assert out["outputs"]["hls"]["resolution"] == "1080p"


@pytest.mark.parametrize("res", store.RENDER_RESOLUTIONS)
def test_validate_accepts_every_ladder_rung_for_hls(res):
    cfg = _good_config(outputs={"hls": {"resolution": res}})
    assert store.validate_wall_config(cfg, set(VALID))["outputs"]["hls"]["resolution"] == res
    assert len(store.RENDER_RESOLUTIONS) == 8


def test_invalid_resolution_clamps_to_default_not_rejected():
    cfg = _good_config(outputs={"hls": {"resolution": "480p"}})
    assert store.validate_wall_config(cfg, set(VALID))["outputs"]["hls"]["resolution"] == "1080p"


def test_bitrate_clamped_to_per_resolution_band():
    cfg = _good_config(outputs={"hls": {"resolution": "1080p", "bitrate_kbps": 9_999_999}})
    br = store.validate_wall_config(cfg, set(VALID))["outputs"]["hls"]["bitrate_kbps"]
    assert br == min(store.BITRATE_KBPS_CEIL, store.RES_BITRATE_KBPS["1080p"] * 3)  # 24000
    cfg = _good_config(outputs={"hls": {"bitrate_kbps": 1}})
    low = store.validate_wall_config(cfg, set(VALID))["outputs"]["hls"]["bitrate_kbps"]
    assert low == store.BITRATE_KBPS_MIN


@pytest.mark.parametrize("field", ["enabled", "audio"])
@pytest.mark.parametrize("val,expect", [(True, True), (False, False), (1, True), (0, False)])
def test_output_flags_coerced_to_bool(field, val, expect):
    cfg = _good_config(outputs={"hls": {field: val}})
    assert store.validate_wall_config(cfg, set(VALID))["outputs"]["hls"][field] is expect


# --- MIGRATION: old render.resolution → outputs.hls.resolution ---


def test_migration_render_resolution_becomes_hls_resolution():
    cfg = _good_config(render={"resolution": "1440p"})
    out = store.validate_wall_config(cfg, set(VALID))
    assert out["outputs"]["hls"]["resolution"] == "1440p"
    assert "render" not in out


def test_outputs_block_wins_over_legacy_render():
    cfg = _good_config(render={"resolution": "2160p"}, outputs={"hls": {"resolution": "900p"}})
    assert store.validate_wall_config(cfg, set(VALID))["outputs"]["hls"]["resolution"] == "900p"


def test_old_stored_file_migrates_on_load(tmp_path: Path):
    # a file written in the OLD shape (render + audible_cell, no outputs/audio)
    old = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 1, "cols": 2}, "preset": "news", "audible_cell": 1,
        "render": {"resolution": "1440p"},
        "cells": [{"channel": "bbc-news", "subtitles": False},
                  {"channel": "cnn", "subtitles": True}],
    }
    store.wall_config_path(tmp_path).write_text(json.dumps(old))
    loaded, stored = store.load_wall_config(tmp_path, VALID)
    assert stored is True
    assert loaded["outputs"]["hls"]["resolution"] == "1440p"
    assert loaded["cells"][1]["audio"] is True
    assert "render" not in loaded and "audible_cell" not in loaded


def test_default_config_includes_outputs():
    cfg = store.default_wall_config(VALID)
    assert cfg["outputs"]["hls"]["resolution"] == "1080p"
    assert "render" not in cfg and "audible_cell" not in cfg


def test_clamp_reload_monotonic_clamps_output_restart_epoch():
    old = store.validate_wall_config(_good_config(), set(VALID))
    old["outputs"]["hls"]["restart_epoch"] = 5
    new = store.validate_wall_config(_good_config(), set(VALID))   # restart_epoch 0
    clamped = store.clamp_reload_monotonic(new, old)
    assert clamped["outputs"]["hls"]["restart_epoch"] == 5      # not rewound
    # a genuine bump goes through
    new2 = store.validate_wall_config(_good_config(), set(VALID))
    new2["outputs"]["hls"]["restart_epoch"] = 6
    assert store.clamp_reload_monotonic(new2, old)["outputs"]["hls"]["restart_epoch"] == 6


# --- fine-grained view tunables (feed_pct / feed_font / ticker_scale) ---


def test_validate_defaults_the_view_tunables():
    out = store.validate_wall_config(_good_config(), set(VALID))
    assert out["feed_pct"] == 32.0 and out["feed_font"] == 1.0 and out["ticker_scale"] == 1.0


def test_view_tunables_clamp_into_range_not_reject():
    out = store.validate_wall_config(
        _good_config(feed_pct=999, feed_font=-5, ticker_scale=100), set(VALID)
    )
    assert out["feed_pct"] == store.FEED_PCT_MAX
    assert out["feed_font"] == store.FEED_FONT_MIN
    assert out["ticker_scale"] == store.TICKER_SCALE_MAX


def test_view_tunables_preserve_an_in_range_value():
    out = store.validate_wall_config(
        _good_config(feed_pct=41, feed_font=1.25, ticker_scale=1.5), set(VALID)
    )
    assert out["feed_pct"] == 41.0 and out["feed_font"] == 1.25 and out["ticker_scale"] == 1.5


@pytest.mark.parametrize("field", ["feed_pct", "feed_font", "ticker_scale"])
def test_view_tunables_reject_a_non_number(field):
    with pytest.raises(store.WallConfigError, match=field):
        store.validate_wall_config(_good_config(**{field: "wide"}), set(VALID))


# --- partial-merge (PATCH) write: absent preserves, present overrides ---


def test_merge_preserves_omitted_top_level_fields():
    stored = store.validate_wall_config(
        _good_config(reload_epoch=5, outputs={"hls": {"resolution": "2160p"}}), set(VALID)
    )
    merged = store.merge_wall_config(stored, {"preset": "weather"})
    assert merged["preset"] == "weather"                            # overridden
    assert merged["reload_epoch"] == 5                              # preserved (omitted)
    assert merged["outputs"]["hls"]["resolution"] == "2160p"        # preserved (omitted)
    assert merged["cells"] == stored["cells"]                       # preserved (omitted)
    assert merged["layout"] == stored["layout"]                     # preserved (omitted)


def test_merge_outputs_per_output_and_per_field_no_clobber():
    stored = store.validate_wall_config(_good_config(), set(VALID))
    # write ONLY outputs.hls.bitrate_kbps — hls.resolution must survive (per-field)
    merged = store.merge_wall_config(stored, {"outputs": {"hls": {"bitrate_kbps": 5000}}})
    mv = store.validate_wall_config(merged, set(VALID))
    assert mv["outputs"]["hls"]["bitrate_kbps"] == 5000             # overridden
    assert mv["outputs"]["hls"]["resolution"] == "1080p"           # preserved (per-field)


def test_merge_cells_preserves_omitted_per_cell_fields():
    stored = store.validate_wall_config(_good_config(), set(VALID))
    stored["cells"][0]["reload"] = 4
    incoming = {"cells": [
        {"channel": "cnn", "subtitles": True},
        {"channel": None}, {"channel": None}, {"channel": None},
    ]}
    merged = store.merge_wall_config(stored, incoming)
    assert merged["cells"][0]["channel"] == "cnn"           # overridden
    assert merged["cells"][0]["subtitles"] is True          # overridden
    assert merged["cells"][0]["reload"] == 4                # preserved (omitted per-cell)
    assert merged["cells"][0]["audio"] is True              # preserved (omitted per-cell)


def test_merge_cells_audio_alone_preserves_channel_and_subtitles():
    stored = store.validate_wall_config(_good_config(), set(VALID))
    merged = store.merge_wall_config(stored, {"cells": [{"audio": False}, {}, {}, {}]})
    assert merged["cells"][0]["audio"] is False             # overridden
    assert merged["cells"][0]["channel"] == "bbc-news"      # preserved
    assert merged["cells"][0]["subtitles"] is False         # preserved


def test_merge_explicit_null_clears_present_field():
    stored = store.validate_wall_config(_good_config(preset="news"), set(VALID))
    assert store.merge_wall_config(stored, {"preset": None})["preset"] is None


def test_merge_non_dict_passes_through_for_the_validator_to_reject():
    stored = store.validate_wall_config(_good_config(), set(VALID))
    assert store.merge_wall_config(stored, [1, 2, 3]) == [1, 2, 3]
    assert store.merge_wall_config(stored, {"cells": "nope"})["cells"] == "nope"


def test_put_round_trips_the_outputs(tmp_path: Path):
    client = _client(tmp_path)
    payload = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 1, "cols": 1}, "preset": "news",
        "outputs": {"hls": {"resolution": "2160p", "bitrate_kbps": 18000}},
        "cells": [{"channel": "bbc-news"}],
    }
    put = client.put("/api/wall", json=payload)
    assert put.status_code == 200, put.text
    assert put.json()["outputs"]["hls"]["resolution"] == "2160p"
    assert client.get("/api/wall").json()["outputs"]["hls"]["bitrate_kbps"] == 18000


def test_put_omitting_outputs_preserves_the_stored_resolution(tmp_path: Path):
    client = _client(tmp_path)
    base = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 1, "cols": 1}, "preset": "news",
        "cells": [{"channel": "bbc-news"}],
    }
    client.put("/api/wall", json={**base, "outputs": {"hls": {"resolution": "2160p"}}})
    got = client.put("/api/wall", json=base).json()   # /app/-style edit, no outputs
    assert got["outputs"]["hls"]["resolution"] == "2160p"
    down = {**base, "outputs": {"hls": {"resolution": "1080p"}}}
    back = client.put("/api/wall", json=down).json()
    assert back["outputs"]["hls"]["resolution"] == "1080p"


def test_partial_write_preserves_EVERY_omitted_field(tmp_path: Path):
    """THE CLASS-KILLER, generalized: establish a fully-customized wall, then for
    EVERY top-level field PUT a payload that omits ONLY that field and assert the
    stored value survives. Iterates the config's own keys, so a NEW field added to
    the schema is automatically covered by this invariant — no per-field defence."""
    client = _client(tmp_path)
    full = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 1, "cols": 2},
        "preset": "news",
        "reload_epoch": 7,
        "feed_pct": 41.0,
        "feed_font": 1.3,
        "ticker_scale": 1.5,
        "outputs": {
            "hls": {"enabled": True, "resolution": "1440p", "bitrate_kbps": 9000, "audio": True},
        },
        "cells": [
            {"channel": "bbc-news", "audio": True, "subtitles": True, "reload": 3},
            {"channel": "cnn", "audio": False, "subtitles": False, "reload": 0},
        ],
    }
    client.put("/api/wall", json=full)
    stored = client.get("/api/wall").json()
    stored.pop("stored", None)
    # sanity: non-default values round-tripped so a revert WOULD be detectable
    assert stored["outputs"]["hls"]["resolution"] == "1440p" and stored["reload_epoch"] == 7

    for field in list(stored.keys()):
        partial = {k: v for k, v in stored.items() if k != field}
        got = client.put("/api/wall", json=partial)
        assert got.status_code == 200, f"omitting {field!r}: {got.text}"
        body = got.json()
        assert body[field] == stored[field], f"omitting {field!r} clobbered it"


def test_partial_write_rejects_an_inconsistent_merge(tmp_path: Path):
    client = _client(tmp_path)
    base = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 2, "cols": 2}, "preset": "news",
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
    filled = [c["channel"] for c in cfg["cells"] if c["channel"]]
    assert all(s in VALID for s in filled)
    store.validate_wall_config(cfg, set(VALID))


def test_default_config_omits_vanished_news_slugs():
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
    assert body["outputs"]["hls"]["enabled"] is True


def test_put_then_get_round_trips_and_marks_stored(tmp_path: Path):
    client = _client(tmp_path)
    payload = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 1, "cols": 2},
        "preset": "news",
        "cells": [
            {"channel": "bbc-news", "audio": True, "subtitles": True},
            {"channel": None, "audio": False, "subtitles": False},
        ],
    }
    put = client.put("/api/wall", json=payload)
    assert put.status_code == 200, put.text
    assert put.json()["stored"] is True

    got = client.get("/api/wall").json()
    assert got["stored"] is True
    assert got["layout"] == {"rows": 1, "cols": 2}
    assert got["cells"][0] == {"channel": "bbc-news", "audio": True, "subtitles": True, "reload": 0}
    assert store.wall_config_path(tmp_path).is_file()


def test_put_rejects_unknown_channel_with_422(tmp_path: Path):
    payload = {
        "schema_version": store.WALL_SCHEMA_VERSION,
        "layout": {"rows": 1, "cols": 1},
        "cells": [{"channel": "totally-made-up", "subtitles": False}],
    }
    r = _client(tmp_path).put("/api/wall", json=payload)
    assert r.status_code == 422
    assert "not a known/enabled channel" in r.json()["detail"]
