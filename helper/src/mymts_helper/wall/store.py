"""Pure load / validate / persist for the server-side wall config.

The wall config is the headless version's source of truth for the rendered
wall. Shape (JSON, ``wall.local.json`` in the data dir — seedless, gitignored):

    {
      "schema_version": 1,
      "layout": {"rows": 2, "cols": 2},
      "preset": "news",                       # informational: last-applied preset
      "audible_cell": 0 | null,               # single-audible-cell model
      "cells": [                              # length == rows*cols (by index)
        {"channel": "bbc-news" | null, "subtitles": false},
        ...
      ]
    }

**Per-cell model mirrors the native app**, not invented controls:
  - per-cell ``channel`` (the slot's assigned channel slug, or null = empty),
  - per-cell ``subtitles`` on/off (native ``captionsOnSlots`` is per-slot),
  - one ``audible_cell`` (native ``audibleSlot`` single-audible model; null =
    the whole wall is muted).

All validation is **pure + testable here** (no FastAPI); the router calls
:func:`validate_wall_config` with the live channel registry's slug set so a
write can never name a channel that doesn't exist / is disabled, or make an
empty cell audible. A bad/absent stored file degrades to a server-computed
default (so ``/app/`` is never blank) — the same honest-degradation discipline
as the playlist profiles loader.
"""

from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path
from typing import Any

from ..channels.presets import DEFAULT_PRESET_ID, PRESETS

WALL_SCHEMA_VERSION = 1
WALL_FILE = "wall.local.json"

GRID_DIM_MIN = 1
GRID_DIM_MAX = 3
DEFAULT_ROWS = 2
DEFAULT_COLS = 2


class WallConfigError(ValueError):
    """A wall config failed validation. The message is operator-facing (the
    router surfaces it as a 422 detail) so it must name the offending field."""


def _news_preset_slugs() -> list[str]:
    """The ``news`` preset's slugs — the source of the server-computed default
    wall (kept in sync with channels/presets.py by referencing it directly)."""
    for p in PRESETS:
        if p["id"] == DEFAULT_PRESET_ID:
            return list(p.get("slugs", []))
    return []


def default_wall_config(valid_slugs: list[str]) -> dict[str, Any]:
    """A sensible default wall when none is stored: a 2×2 grid filled with the
    ``news`` preset's channels that actually exist (so ``/app/`` renders the
    news wall, never blank), audio muted, subtitles off. ``valid_slugs`` is the
    live registry order so the default never names a vanished channel.

    Order-preserving membership (not just a set) so the filled cells keep the
    preset's intended order.
    """
    valid = set(valid_slugs)
    news = [s for s in _news_preset_slugs() if s in valid]
    count = DEFAULT_ROWS * DEFAULT_COLS
    cells: list[dict[str, Any]] = []
    for i in range(count):
        channel = news[i] if i < len(news) else None
        cells.append({"channel": channel, "subtitles": False})
    return {
        "schema_version": WALL_SCHEMA_VERSION,
        "layout": {"rows": DEFAULT_ROWS, "cols": DEFAULT_COLS},
        "preset": DEFAULT_PRESET_ID,
        "audible_cell": None,
        "cells": cells,
    }


def _validate_dim(value: Any, name: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool):
        raise WallConfigError(f"layout.{name} must be an integer")
    if not (GRID_DIM_MIN <= value <= GRID_DIM_MAX):
        raise WallConfigError(
            f"layout.{name} must be between {GRID_DIM_MIN} and {GRID_DIM_MAX}"
        )
    return value


def validate_wall_config(raw: Any, valid_slugs: set[str]) -> dict[str, Any]:
    """Validate + NORMALISE an incoming wall config against the live channel
    set. Returns a clean config (extra keys stripped, ``subtitles`` defaulted)
    or raises :class:`WallConfigError` with a field-specific message.

    Rules:
      - ``schema_version`` must equal :data:`WALL_SCHEMA_VERSION` (a contract we
        don't understand is rejected, never half-applied);
      - ``layout.rows`` / ``layout.cols`` are ints in 1..3;
      - ``cells`` is a list of length ``rows*cols``; each cell's ``channel`` is
        null or a slug in ``valid_slugs``; ``subtitles`` is a bool (default
        false);
      - ``audible_cell`` is null or a valid cell index whose cell has a channel
        (you can't make an empty cell audible — the single-audible model);
      - ``preset`` is null or a string (informational).
    """
    if not isinstance(raw, dict):
        raise WallConfigError("wall config must be a JSON object")

    version = raw.get("schema_version")
    if version != WALL_SCHEMA_VERSION:
        raise WallConfigError(
            f"schema_version must be {WALL_SCHEMA_VERSION} (got {version!r})"
        )

    layout = raw.get("layout")
    if not isinstance(layout, dict):
        raise WallConfigError("layout must be an object with rows + cols")
    rows = _validate_dim(layout.get("rows"), "rows")
    cols = _validate_dim(layout.get("cols"), "cols")
    count = rows * cols

    cells_raw = raw.get("cells")
    if not isinstance(cells_raw, list):
        raise WallConfigError("cells must be a list")
    if len(cells_raw) != count:
        raise WallConfigError(
            f"cells must have exactly rows*cols = {count} entries (got {len(cells_raw)})"
        )

    cells: list[dict[str, Any]] = []
    for i, cell in enumerate(cells_raw):
        if not isinstance(cell, dict):
            raise WallConfigError(f"cells[{i}] must be an object")
        channel = cell.get("channel")
        if channel is not None:
            if not isinstance(channel, str):
                raise WallConfigError(f"cells[{i}].channel must be a slug string or null")
            if channel not in valid_slugs:
                raise WallConfigError(
                    f"cells[{i}].channel '{channel}' is not a known/enabled channel"
                )
        subtitles = cell.get("subtitles", False)
        if not isinstance(subtitles, bool):
            raise WallConfigError(f"cells[{i}].subtitles must be true/false")
        cells.append({"channel": channel, "subtitles": subtitles})

    audible = raw.get("audible_cell")
    if audible is not None:
        if not isinstance(audible, int) or isinstance(audible, bool):
            raise WallConfigError("audible_cell must be a cell index or null")
        if not (0 <= audible < count):
            raise WallConfigError(
                f"audible_cell must be in 0..{count - 1} (got {audible})"
            )
        if cells[audible]["channel"] is None:
            raise WallConfigError(
                f"audible_cell {audible} points at an empty cell — only a cell with "
                "a channel can be the audio source"
            )

    preset = raw.get("preset")
    if preset is not None and not isinstance(preset, str):
        raise WallConfigError("preset must be a string or null")

    return {
        "schema_version": WALL_SCHEMA_VERSION,
        "layout": {"rows": rows, "cols": cols},
        "preset": preset,
        "audible_cell": audible,
        "cells": cells,
    }


def wall_config_path(data_dir: str | os.PathLike[str]) -> Path:
    return Path(data_dir) / WALL_FILE


def load_wall_config(
    data_dir: str | os.PathLike[str], valid_slugs: list[str]
) -> tuple[dict[str, Any], bool]:
    """Load the stored wall config, or fall back to a server-computed default.

    Returns ``(config, stored)`` where ``stored`` is True iff a valid file was
    read. A missing OR corrupt/invalid file degrades to the default (stored =
    False) so the wall always renders — the same tolerance the playlist
    profiles loader has. ``valid_slugs`` lets a stored config that references a
    now-vanished channel be re-validated; if it fails, we fall back rather than
    serve a stale slug.
    """
    path = wall_config_path(data_dir)
    if not path.is_file():
        return default_wall_config(valid_slugs), False
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
        config = validate_wall_config(raw, set(valid_slugs))
        return config, True
    except (OSError, json.JSONDecodeError, WallConfigError):
        # A bad stored file never crashes the wall — degrade to the default.
        return default_wall_config(valid_slugs), False


def save_wall_config(
    data_dir: str | os.PathLike[str], config: dict[str, Any]
) -> None:
    """Persist a (pre-validated) config atomically: write a temp file in the
    same dir, then rename over the target so a crash mid-write can never leave
    a truncated config on disk."""
    path = wall_config_path(data_dir)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=str(path.parent), prefix=".wall.", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(config, f, indent=2, sort_keys=True)
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise
