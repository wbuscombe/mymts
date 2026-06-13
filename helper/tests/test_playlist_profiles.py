"""Profile loader + selection tests (pure; file I/O via tmp_path)."""

from __future__ import annotations

import json
from pathlib import Path

from mymts_helper.channels.registry import ChannelRow
from mymts_helper.playlist.profiles import (
    DEFAULT_PROFILE_NAME,
    Profile,
    load_profiles,
    select_channels,
)


def _ch(slug: str, *, status: str = "live") -> ChannelRow:
    return ChannelRow(
        id=1,
        slug=slug,
        label=slug.upper(),
        kind="hls",
        source_url=f"https://x.test/{slug}.m3u8",
        enabled=True,
        current_url=f"https://cdn.test/{slug}/master.m3u8",
        status=status,
        last_check_at=None,
        last_success_at=None,
        last_error=None,
        error_count=0,
        browser_playable=True,
    )


# ---- load_profiles ----------------------------------------------------------


def test_load_none_yields_only_builtin_default() -> None:
    profiles = load_profiles(None)
    assert set(profiles) == {DEFAULT_PROFILE_NAME}
    assert profiles[DEFAULT_PROFILE_NAME].slugs is None


def test_load_missing_file_yields_default_only(tmp_path: Path) -> None:
    profiles = load_profiles(tmp_path / "nope.json")
    assert set(profiles) == {DEFAULT_PROFILE_NAME}


def test_load_object_form(tmp_path: Path) -> None:
    p = tmp_path / "profiles.json"
    p.write_text(json.dumps({"profiles": [{"name": "office", "slugs": ["cnn", "bbc-news"]}]}))
    profiles = load_profiles(p)
    assert set(profiles) == {DEFAULT_PROFILE_NAME, "office"}
    assert profiles["office"].slugs == ("cnn", "bbc-news")


def test_load_bare_list_form(tmp_path: Path) -> None:
    p = tmp_path / "profiles.json"
    p.write_text(json.dumps([{"name": "den", "slugs": ["nasa-tv"]}]))
    profiles = load_profiles(p)
    assert profiles["den"].slugs == ("nasa-tv",)


def test_invalid_entries_are_skipped_valid_kept(tmp_path: Path) -> None:
    p = tmp_path / "profiles.json"
    p.write_text(
        json.dumps(
            [
                {"name": "good", "slugs": ["cnn"]},
                {"name": "BAD NAME", "slugs": ["cnn"]},  # invalid slug-name
                {"name": "badslug", "slugs": ["NOT A SLUG"]},  # invalid slug
                {"slugs": ["cnn"]},  # missing name
                {"name": "notlist", "slugs": "cnn"},  # slugs not a list
                "garbage",  # not an object
            ]
        )
    )
    profiles = load_profiles(p)
    assert set(profiles) == {DEFAULT_PROFILE_NAME, "good"}


def test_reserved_default_name_in_file_is_ignored(tmp_path: Path) -> None:
    p = tmp_path / "profiles.json"
    p.write_text(json.dumps([{"name": "default", "slugs": ["cnn"]}]))
    profiles = load_profiles(p)
    # the built-in default (slugs=None) must win, not the file's narrowed one
    assert profiles[DEFAULT_PROFILE_NAME].slugs is None


def test_malformed_json_yields_default_only(tmp_path: Path) -> None:
    p = tmp_path / "profiles.json"
    p.write_text("{ this is not json ]")
    profiles = load_profiles(p)
    assert set(profiles) == {DEFAULT_PROFILE_NAME}


def test_non_utf8_file_yields_default_only(tmp_path: Path) -> None:
    # A wrong-encoding file (UTF-16/Latin-1/partial write) raises
    # UnicodeDecodeError on read; the loader must fail closed, not crash boot.
    p = tmp_path / "profiles.json"
    p.write_bytes(b"\xff\xfe{\x00\x80\x00")
    profiles = load_profiles(p)
    assert set(profiles) == {DEFAULT_PROFILE_NAME}


# ---- select_channels --------------------------------------------------------


def test_default_profile_returns_all_live_in_order() -> None:
    live = [_ch("a"), _ch("b"), _ch("c")]
    out = select_channels(Profile("default", None), live)
    assert [c.slug for c in out] == ["a", "b", "c"]


def test_named_profile_subsets_and_reorders() -> None:
    live = [_ch("a"), _ch("b"), _ch("c")]
    out = select_channels(Profile("p", ("c", "a")), live)
    assert [c.slug for c in out] == ["c", "a"]


def test_named_profile_drops_slugs_that_are_not_live() -> None:
    # 'b' is requested but not in the live set → omitted, never faked.
    live = [_ch("a"), _ch("c")]
    out = select_channels(Profile("p", ("a", "b", "c")), live)
    assert [c.slug for c in out] == ["a", "c"]


def test_empty_named_profile_selects_nothing() -> None:
    live = [_ch("a")]
    assert select_channels(Profile("p", ()), live) == []
