"""Pure load / validate / persist for the server-side wall config.

The wall config is the headless version's source of truth for the rendered
wall. Shape (JSON, ``wall.local.json`` in the data dir — seedless, gitignored):

    {
      "schema_version": 1,
      "layout": {"rows": 2, "cols": 2},
      "preset": "news",                       # informational: last-applied preset
      "audible_cell": 0 | null,               # single-audible-cell model
      "reload_epoch": 0,                      # whole-wall force-reload counter
      "render": {"resolution": "1080p"},      # renderer canvas: a 16:9 ladder rung
      "feed_pct": 32.0,                       # feed-column width (% of the wall)
      "feed_font": 1.0,                       # feed text scale (proportional)
      "ticker_scale": 1.0,                    # ticker height + content scale
      "cells": [                              # length == rows*cols (by index)
        {"channel": "bbc-news" | null, "subtitles": false, "reload": 0},
        ...
      ]
    }

**Force-reload epochs** are monotonic counters a control surface bumps to tell
the rendered wall (`/app/`) to re-attach a tile's player without a channel change:
``reload_epoch`` (whole wall) and per-cell ``reload``. ``/app/`` reattaches when it
sees a value INCREASE. They are additive to schema v1 (default 0), so an old
stored file without them loads fine.

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

import copy
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

# Render resolution presets (the renderer's Xvfb/ffmpeg canvas) — a fine 16:9
# ladder. The helper only stores/validates the CHOSEN NAME; the renderer maps each
# name → (w, h) + a SUSTAINABLE per-resolution fps + bitrate (supervisor.py), and
# the web wall scales to any of them via its --ux unit. 1080p is the default + the
# smooth ceiling on the CPU-only renderer; ≥1440p is heavier (lower sustainable
# fps — see ARCHITECTURE §31). Keep in lockstep with supervisor.RENDER_RESOLUTIONS
# (renderer) and RENDER_RESOLUTIONS (wallConfig.mjs).
RENDER_RESOLUTIONS = (
    "720p", "900p", "1080p", "1260p", "1440p", "1620p", "1800p", "2160p",
)
DEFAULT_RESOLUTION = "1080p"

# Fine-grained, proportional view tunables (bounds chosen so nothing collapses or
# overflows). All are CLAMPED on read (a slider stays in range; a malformed PUT is
# pulled to a sane value rather than rejected — these are continuous knobs, not the
# strict enums/counters elsewhere).
FEED_PCT_MIN, FEED_PCT_MAX, FEED_PCT_DEFAULT = 18.0, 58.0, 32.0
FEED_FONT_MIN, FEED_FONT_MAX, FEED_FONT_DEFAULT = 0.7, 1.6, 1.0
TICKER_SCALE_MIN, TICKER_SCALE_MAX, TICKER_SCALE_DEFAULT = 0.6, 2.0, 1.0


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
        cells.append({"channel": channel, "subtitles": False, "reload": 0})
    return {
        "schema_version": WALL_SCHEMA_VERSION,
        "layout": {"rows": DEFAULT_ROWS, "cols": DEFAULT_COLS},
        "preset": DEFAULT_PRESET_ID,
        "audible_cell": None,
        "reload_epoch": 0,
        "render": {"resolution": DEFAULT_RESOLUTION},
        "feed_pct": FEED_PCT_DEFAULT,
        "feed_font": FEED_FONT_DEFAULT,
        "ticker_scale": TICKER_SCALE_DEFAULT,
        "cells": cells,
    }


def _validate_scale(value: Any, name: str, lo: float, hi: float, default: float) -> float:
    """A fine-grained, proportional view tunable: a number CLAMPED to [lo, hi]
    (absent → default). Additive to schema v1. Clamped (not rejected) on read so a
    boundary slider value or a slightly-off PUT lands at a sane value — these are
    continuous knobs. A non-number is still a type error (rejected)."""
    if value is None:
        return default
    if not isinstance(value, int | float) or isinstance(value, bool):
        raise WallConfigError(f"{name} must be a number")
    return float(min(hi, max(lo, value)))


def _validate_render(value: Any) -> dict[str, Any]:
    """The render block: a resolution name from :data:`RENDER_RESOLUTIONS`.
    Additive to schema v1 — absent ``render`` defaults to 1080p (an old stored
    file loads unchanged). Returns the normalised ``{"resolution": name}``."""
    if value is None:
        return {"resolution": DEFAULT_RESOLUTION}
    if not isinstance(value, dict):
        raise WallConfigError("render must be an object with a resolution")
    res = value.get("resolution", DEFAULT_RESOLUTION)
    if res not in RENDER_RESOLUTIONS:
        allowed = ", ".join(RENDER_RESOLUTIONS)
        raise WallConfigError(f"render.resolution must be one of {allowed} (got {res!r})")
    return {"resolution": res}


def _validate_reload(value: Any, name: str) -> int:
    """A force-reload epoch: a non-negative integer (default 0 when absent).
    Additive to schema v1, so a missing field is the un-bumped default, not an
    error."""
    if value is None:
        return 0
    if not isinstance(value, int) or isinstance(value, bool):
        raise WallConfigError(f"{name} must be a non-negative integer")
    if value < 0:
        raise WallConfigError(f"{name} must be a non-negative integer")
    return value


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
        reload = _validate_reload(cell.get("reload"), f"cells[{i}].reload")
        cells.append({"channel": channel, "subtitles": subtitles, "reload": reload})

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

    reload_epoch = _validate_reload(raw.get("reload_epoch"), "reload_epoch")
    render = _validate_render(raw.get("render"))
    feed_pct = _validate_scale(
        raw.get("feed_pct"), "feed_pct", FEED_PCT_MIN, FEED_PCT_MAX, FEED_PCT_DEFAULT
    )
    feed_font = _validate_scale(
        raw.get("feed_font"), "feed_font", FEED_FONT_MIN, FEED_FONT_MAX, FEED_FONT_DEFAULT
    )
    ticker_scale = _validate_scale(
        raw.get("ticker_scale"), "ticker_scale",
        TICKER_SCALE_MIN, TICKER_SCALE_MAX, TICKER_SCALE_DEFAULT,
    )

    return {
        "schema_version": WALL_SCHEMA_VERSION,
        "layout": {"rows": rows, "cols": cols},
        "preset": preset,
        "audible_cell": audible,
        "reload_epoch": reload_epoch,
        "render": render,
        "feed_pct": feed_pct,
        "feed_font": feed_font,
        "ticker_scale": ticker_scale,
        "cells": cells,
    }


def _merge_cells(
    stored_cells: list[Any], incoming_cells: list[Any]
) -> list[dict[str, Any]]:
    """Merge an incoming cells array onto the stored one BY INDEX: the incoming
    length wins (a grid resize), and each incoming cell's PROVIDED fields override
    the stored cell at that index while its OMITTED fields are preserved. A new
    index (grid grew) has no stored counterpart, so its omitted fields fall to the
    validator's defaults. A non-dict incoming cell passes through untouched so the
    post-merge validator rejects it with a clear message. Pure."""
    out: list[dict[str, Any]] = []
    for i, cell in enumerate(incoming_cells):
        if not isinstance(cell, dict):
            out.append(cell)
            continue
        stored_cell = stored_cells[i] if i < len(stored_cells) else None
        base = dict(stored_cell) if isinstance(stored_cell, dict) else {}
        base.update(cell)
        out.append(base)
    return out


def merge_wall_config(stored: dict[str, Any], incoming: Any) -> dict[str, Any]:
    """**PATCH / partial-merge** an incoming wall config onto the stored one, the
    structural defence against the partial-write clobber class: a write that OMITS
    a field PRESERVES the stored value; a write that PROVIDES a field overrides it.

    Two levels, so no client can clobber a field it didn't send at EITHER level:
      - top-level: a key present in ``incoming`` overrides; an ABSENT key keeps the
        stored value (so ``reload_epoch`` / ``render`` / any future field survive an
        ``/app/`` edit that doesn't mention them);
      - ``cells`` (when provided as a list): merged per-index by :func:`_merge_cells`
        (so a per-cell field like ``reload`` survives a cells write that omits it).

    Semantics: **absent = preserve, explicit null = set** (e.g. ``audible_cell:
    null`` to mute, ``channel: null`` to clear a cell — both are PRESENT keys, so
    they override). The merged result is returned UNVALIDATED; the caller validates
    it (post-merge), so an inconsistent merge (e.g. a layout change without matching
    cells) is still rejected by the normal rules. A non-dict ``incoming`` is
    returned as-is for the validator to reject. Pure — never mutates the inputs.
    """
    if not isinstance(incoming, dict):
        return incoming
    # Deep-copy so the result never ALIASES the stored config's nested objects
    # (an omitted `layout` / `render` / `cells` would otherwise share the stored
    # object). The caller validates the result into a fresh config anyway, but a
    # pure function shouldn't hand back structure aliased to its input.
    merged = copy.deepcopy(stored)
    for key, value in incoming.items():
        if key == "cells" and isinstance(value, list):
            merged["cells"] = _merge_cells(stored.get("cells") or [], value)
        else:
            merged[key] = copy.deepcopy(value)
    return merged


def clamp_reload_monotonic(
    new_config: dict[str, Any], old_config: dict[str, Any]
) -> dict[str, Any]:
    """Enforce the **monotonic** contract of the force-reload counters at the
    authoritative layer: a write must never DECREASE ``reload_epoch`` or any
    per-cell ``reload`` below the currently-stored value.

    A client that echoes a stale-lower value — e.g. an ``/app/`` edit that fired
    its PUT before its first config hydrate seeded the local baseline — must not
    be able to rewind a force-reload another surface (``/control/``) already
    bumped. Per-cell counters clamp **by index** (the same slot model the rest of
    the config uses); a grid resize simply has no old counterpart for new indices
    (→ floor 0). Pure; returns a new dict (never mutates the inputs).
    """
    old_cells = old_config.get("cells") or []
    cells: list[dict[str, Any]] = []
    for i, cell in enumerate(new_config.get("cells") or []):
        old_reload = old_cells[i].get("reload", 0) if i < len(old_cells) else 0
        cells.append({**cell, "reload": max(cell.get("reload", 0), old_reload)})
    return {
        **new_config,
        "reload_epoch": max(
            new_config.get("reload_epoch", 0), old_config.get("reload_epoch", 0)
        ),
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
