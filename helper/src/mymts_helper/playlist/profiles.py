"""Playlist profiles — a named selection/ordering of channel slugs.

Foundation for the cross-platform-profiles fork (BACKLOG item H): a
profile names which channels — and in what order — a given client (the
office wall, a bedroom Apple TV via VLC) should see. v1 is deliberately
minimal: a built-in `default` (every live channel) plus optional named
profiles loaded from a JSON file (path via `PROFILES_FILE` — operator
data, kept out of git). No per-client server-side state, no identity, no
sync — that stays deferred per item H. The endpoint stays stateless: it
returns what is live NOW, narrowed/ordered by the requested profile.
"""

from __future__ import annotations

import json
import logging
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path

from ..channels.registry import ChannelRow, RegistryError, validate_slug

log = logging.getLogger("mymts_helper.playlist.profiles")

DEFAULT_PROFILE_NAME = "default"


class ProfileError(ValueError):
    """Raised by the profile loader's validators."""


@dataclass(frozen=True)
class Profile:
    """A named channel selection.

    `slugs is None` => "every live channel" (the built-in default).
    Otherwise it is an explicit, ordered allow-list of channel slugs; only
    the slugs that are actually live at request time are emitted, in this
    order (honest degradation — a named-but-offline channel is dropped, not
    faked).
    """

    name: str
    slugs: tuple[str, ...] | None


DEFAULT_PROFILE = Profile(name=DEFAULT_PROFILE_NAME, slugs=None)


def select_channels(profile: Profile, live: Iterable[ChannelRow]) -> list[ChannelRow]:
    """Narrow + order the `live` channels for `profile`.

    `live` MUST already be the resolved (status==live) set. The default
    profile returns it unchanged (registry order, by slug). A named profile
    returns only its listed slugs that are live, in the profile's order;
    slugs that aren't live (or don't exist) are dropped honestly.
    """
    live_list = list(live)
    if profile.slugs is None:
        return live_list
    by_slug = {c.slug: c for c in live_list}
    return [by_slug[s] for s in profile.slugs if s in by_slug]


def load_profiles(path: str | Path | None) -> dict[str, Profile]:
    """Build the profile registry: always the built-in `default`, plus any
    valid named profiles from the JSON file at `path`.

    Accepted file shapes::

        {"profiles": [{"name": "office", "slugs": ["cbs-sports-hq", "bbc-news"]}]}
        [{"name": "office", "slugs": ["cbs-sports-hq", "bbc-news"]}]

    Tolerant by design (the wall must boot even with a bad/again-bad config
    file): an unreadable/invalid file logs a warning and yields default-only;
    an invalid individual entry is skipped with a warning while the valid
    ones load. A file entry named `default` is ignored — the built-in wins.
    """
    profiles: dict[str, Profile] = {DEFAULT_PROFILE_NAME: DEFAULT_PROFILE}
    if path is None:
        return profiles
    p = Path(path)
    if not p.exists():
        log.info("profiles_skip_no_file", extra={"path": str(p)})
        return profiles
    try:
        payload = json.loads(p.read_text())
    except (OSError, ValueError) as e:
        # ValueError covers json.JSONDecodeError AND UnicodeDecodeError (a
        # non-UTF-8 file is an ordinary operator mistake) — fail closed to
        # default-only so the wall always boots, never crashes on a bad file.
        log.warning("profiles_file_invalid", extra={"path": str(p), "reason": str(e)})
        return profiles
    entries = payload.get("profiles") if isinstance(payload, dict) else payload
    if not isinstance(entries, list):
        log.warning("profiles_root_not_list", extra={"path": str(p)})
        return profiles
    for entry in entries:
        try:
            prof = _parse_entry(entry)
        except ProfileError as e:
            bad = entry.get("name") if isinstance(entry, dict) else None
            log.warning("profiles_skip_invalid", extra={"profile": bad, "reason": str(e)})
            continue
        if prof.name == DEFAULT_PROFILE_NAME:
            log.warning("profiles_skip_reserved_default")
            continue
        profiles[prof.name] = prof
    return profiles


def _parse_entry(entry: object) -> Profile:
    if not isinstance(entry, dict):
        raise ProfileError("entry_not_object")
    name = entry.get("name")
    if not isinstance(name, str):
        raise ProfileError("name_not_string")
    try:
        validate_slug(name)
    except RegistryError as e:
        raise ProfileError(f"invalid_name: {e}") from None
    raw_slugs = entry.get("slugs")
    if not isinstance(raw_slugs, list):
        raise ProfileError("slugs_not_list")
    slugs: list[str] = []
    for s in raw_slugs:
        if not isinstance(s, str):
            raise ProfileError("slug_not_string")
        try:
            validate_slug(s)
        except RegistryError as e:
            raise ProfileError(f"invalid_slug: {e}") from None
        slugs.append(s)
    return Profile(name=name, slugs=tuple(slugs))
