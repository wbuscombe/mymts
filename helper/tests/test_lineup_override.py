"""Per-deployment lineup override — reconcile (add / disable / override +
precedence), bad-entry skip, malformed-file tolerance, and no-override == shipped.
"""

from __future__ import annotations

import json
from pathlib import Path

from mymts_helper import db
from mymts_helper.channels import registry
from mymts_helper.channels.override import load_override, reconcile, seed_lineup

SHIPPED = [
    {"slug": "cnn", "label": "CNN", "kind": "hls", "source_url": "https://x/cnn.m3u8"},
    {"slug": "bbc-news", "label": "BBC News", "kind": "hls", "source_url": "https://x/bbc.m3u8"},
    {"slug": "cbs-golazo", "label": "CBS Golazo", "kind": "youtube",
     "source_url": "https://www.youtube.com/@cbssportsgolazo/live"},
]


def _shipped_file(tmp_path: Path) -> Path:
    p = tmp_path / "seed.json"
    p.write_text(json.dumps(SHIPPED))
    return p


# ---- reconcile (pure) ----

def test_reconcile_no_override_is_unchanged() -> None:
    out = reconcile(SHIPPED, None)
    assert [c["slug"] for c in out] == ["cnn", "bbc-news", "cbs-golazo"]
    assert all(c["enabled"] for c in out)
    assert all(c.get("category") is None for c in out)   # shipped carries no category


def test_reconcile_disable_sets_enabled_false() -> None:
    by = {c["slug"]: c for c in reconcile(SHIPPED, {"disable": ["cbs-golazo"]})}
    assert by["cbs-golazo"]["enabled"] is False
    assert by["cnn"]["enabled"] is True


def test_reconcile_override_field_merge_wins_others_untouched() -> None:
    ov = {"override": {"bbc-news": {"label": "BBC (UK)", "category": "Global News"}}}
    out = reconcile(SHIPPED, ov)
    by = {c["slug"]: c for c in out}
    assert by["bbc-news"]["label"] == "BBC (UK)"
    assert by["bbc-news"]["category"] == "Global News"
    assert by["bbc-news"]["source_url"] == "https://x/bbc.m3u8"   # untouched field stays


def test_reconcile_add_appends_new_channel() -> None:
    out = reconcile(SHIPPED, {"add": [{"slug": "my-cam", "label": "My Cam", "kind": "youtube",
                                       "source_url": "https://www.youtube.com/@x/live",
                                       "category": "Cameras"}]})
    by = {c["slug"]: c for c in out}
    assert by["my-cam"]["enabled"] is True and by["my-cam"]["category"] == "Cameras"


def test_reconcile_add_collision_with_shipped_is_skipped() -> None:
    out = reconcile(SHIPPED, {"add": [{"slug": "cnn", "label": "Fake CNN", "kind": "hls",
                                       "source_url": "https://x/fake.m3u8"}]})
    cnns = [c for c in out if c["slug"] == "cnn"]
    assert len(cnns) == 1 and cnns[0]["label"] == "CNN"   # shipped wins; the add is dropped


def test_reconcile_disable_beats_override() -> None:
    # A slug both disabled AND overridden stays disabled (removed from the lineup).
    out = reconcile(SHIPPED, {"disable": ["cnn"], "override": {"cnn": {"label": "X"}}})
    by = {c["slug"]: c for c in out}
    assert by["cnn"]["enabled"] is False


# ---- load_override (tolerant) ----

def test_load_override_absent_or_malformed_is_none(tmp_path: Path) -> None:
    assert load_override(None) is None
    assert load_override(tmp_path / "nope.json") is None
    (tmp_path / "bad.json").write_text("{not json")
    assert load_override(tmp_path / "bad.json") is None
    (tmp_path / "arr.json").write_text("[]")            # not an object
    assert load_override(tmp_path / "arr.json") is None
    (tmp_path / "empty.json").write_text("{}")          # no actionable keys
    assert load_override(tmp_path / "empty.json") is None
    (tmp_path / "good.json").write_text(json.dumps({"disable": ["cnn"]}))
    assert load_override(tmp_path / "good.json") == {"add": [], "disable": ["cnn"], "override": {}}


# ---- seed_lineup (DB) ----

def test_seed_lineup_no_override_seeds_all(tmp_path: Path) -> None:
    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)
    res = seed_lineup(conn, _shipped_file(tmp_path), None)
    assert res["seeded"] == 3 and res["override_applied"] is False and res["skipped"] == 0
    assert len(registry.list_channels(conn, enabled_only=True)) == 3
    # No override → category stored NULL (API falls back to the shipped taxonomy).
    assert all(c.category is None for c in registry.list_channels(conn))


def test_seed_lineup_applies_add_disable_override(tmp_path: Path) -> None:
    ov = tmp_path / "lineup.local.json"
    ov.write_text(json.dumps({
        "disable": ["cbs-golazo"],
        "override": {"bbc-news": {"category": "Global News"}},
        "add": [{"slug": "my-cam", "label": "My Cam", "kind": "youtube",
                 "source_url": "https://www.youtube.com/@x/live", "category": "Cameras"}],
    }))
    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)
    res = seed_lineup(conn, _shipped_file(tmp_path), ov)
    assert res["override_applied"] is True and res["disabled"] == 1
    enabled = {c.slug for c in registry.list_channels(conn, enabled_only=True)}
    assert "cbs-golazo" not in enabled    # disabled → excluded
    assert "my-cam" in enabled            # added
    by = {c.slug: c for c in registry.list_channels(conn)}
    assert by["bbc-news"].category == "Global News"   # recategorized (stored)
    assert by["my-cam"].category == "Cameras"
    assert by["cnn"].category is None                 # untouched → NULL → category_of


def test_seed_lineup_skips_bad_entry_not_whole_lineup(tmp_path: Path) -> None:
    ov = tmp_path / "lineup.local.json"
    ov.write_text(json.dumps({"add": [
        {"slug": "good-cam", "label": "Good", "kind": "youtube",
         "source_url": "https://www.youtube.com/@x/live"},
        {"slug": "BAD SLUG!", "label": "Bad", "kind": "hls",
         "source_url": "https://x/y.m3u8"},                       # invalid slug
        {"slug": "bad-url", "label": "Bad URL", "kind": "hls",
         "source_url": "http://insecure/x.m3u8"},                 # http rejected
    ]}))
    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)
    res = seed_lineup(conn, _shipped_file(tmp_path), ov)
    assert res["skipped"] == 2 and res["seeded"] == 4   # 3 shipped + 1 good add; 2 bad skipped
    slugs = {c.slug for c in registry.list_channels(conn)}
    assert "good-cam" in slugs and "bad-url" not in slugs


def test_seed_lineup_removing_override_reverts(tmp_path: Path) -> None:
    """Idempotent: applying an override then re-seeding with none fully reverts —
    re-enabled, category NULL, AND the added channel pruned (no stale orphan)."""
    shipped = _shipped_file(tmp_path)
    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)
    ov = tmp_path / "lineup.local.json"
    ov.write_text(json.dumps({
        "disable": ["cnn"],
        "override": {"bbc-news": {"category": "Sports"}},
        "add": [{"slug": "tmp-cam", "label": "Tmp", "kind": "youtube",
                 "source_url": "https://www.youtube.com/@x/live"}],
    }))
    seed_lineup(conn, shipped, ov)
    assert "tmp-cam" in {c.slug for c in registry.list_channels(conn)}
    # Re-seed with NO override (file removed) → shipped state fully restored.
    res = seed_lineup(conn, shipped, None)
    assert res["pruned"] == 1                       # the orphaned add is removed
    slugs = {c.slug for c in registry.list_channels(conn)}
    assert slugs == {"cnn", "bbc-news", "cbs-golazo"}   # exactly the shipped set
    by = {c.slug: c for c in registry.list_channels(conn)}
    assert by["cnn"].enabled is True and by["bbc-news"].category is None


def test_seed_lineup_prune_keeps_disabled_shipped_and_never_wipes(tmp_path: Path) -> None:
    # A DISABLED shipped channel is NOT pruned (it's in the effective set, enabled=0);
    # only true orphans go. And the prune never removes a shipped channel.
    shipped = _shipped_file(tmp_path)
    p = tmp_path / "x.db"
    db.migrate(p)
    conn = db.connect(p)
    ov = tmp_path / "lineup.local.json"
    ov.write_text(json.dumps({"disable": ["cbs-golazo"]}))
    res = seed_lineup(conn, shipped, ov)
    assert res["pruned"] == 0                        # nothing orphaned
    slugs = {c.slug for c in registry.list_channels(conn)}   # all 3 still present
    assert slugs == {"cnn", "bbc-news", "cbs-golazo"}
    assert {c.slug for c in registry.list_channels(conn, enabled_only=True)} == {"cnn", "bbc-news"}
