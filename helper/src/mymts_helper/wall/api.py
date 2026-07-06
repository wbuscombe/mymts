"""/api/wall endpoint — read + update the server-side wall config.

GET  /api/wall  → the current config (the stored one, or a server-computed
                  default when none is stored), tagged with `stored`.
PUT  /api/wall  → validate the body against the LIVE channel registry (a write
                  can't name an unknown/disabled channel, exceed the grid, or
                  make an empty cell audible), persist atomically, return it.

The config drives the rendered wall (`/app/` reads it) and is edited by the
picker control surface (`/control/`). Validation lives in `wall/store.py`
(pure + tested); this router only wires the registry slug-set + persistence.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from fastapi import APIRouter, Body, HTTPException

from .. import db
from ..channels import registry
from ..weather import regions as weather_regions
from . import store

WALL_SCHEMA_VERSION = store.WALL_SCHEMA_VERSION


def get_router(db_path: Path, data_dir: str) -> APIRouter:
    router = APIRouter(prefix="/api/wall", tags=["wall"])

    def _valid_slugs() -> list[str]:
        # enabled_only: a disabled (lineup-override) channel is not a legal wall
        # assignment, exactly as it's excluded from the picker.
        with db.connection_scope(db_path) as conn:
            slugs = [c.slug for c in registry.list_channels(conn, enabled_only=True)]
        # Radar pseudo-sources are legal cell assignments too — a cell may hold a
        # `weather-radar-*` slug exactly like a channel slug (the region is encoded
        # in the slug; weather/regions.py is the source of truth).
        # UNIFIED REGISTRY (2026-07): radar is now offered to EVERY surface — the native
        # TV picker lists it (no more `?widgets` gate) and the native app renders the
        # loop in a tile, so a stored radar cell resolves + renders on the TV wall, the
        # web /app/, and the headless renderer alike. This is wall-config validation only.
        return slugs + weather_regions.radar_slugs()

    def _payload(config: dict[str, Any], stored: bool) -> dict[str, Any]:
        # `stored` lets a standalone /app/ know whether to render the server
        # config authoritatively (stored) or fall back to its own default
        # behaviour (a server default that hasn't been customised yet).
        return {**config, "stored": stored}

    @router.get("")
    def get_wall() -> dict[str, Any]:
        config, stored = store.load_wall_config(data_dir, _valid_slugs())
        return _payload(config, stored)

    @router.put("")
    def put_wall(payload: dict[str, Any] = Body(...)) -> dict[str, Any]:
        slugs = _valid_slugs()
        # PARTIAL-MERGE (PATCH) write: merge the incoming fields onto the stored
        # config so a write that OMITS a field PRESERVES it — no client can clobber
        # a field it doesn't know about, and any future field is safe by default.
        # This is the structural fix for the clobber class that bit reload_epoch,
        # per-cell reload, and render one by one. Validation runs on the MERGED
        # result, so an inconsistent merge is still rejected by the normal rules.
        existing, _ = store.load_wall_config(data_dir, slugs)
        merged = store.merge_wall_config(existing, payload)
        try:
            config = store.validate_wall_config(merged, set(slugs))
        except store.WallConfigError as e:
            # 422: the body is well-formed JSON but fails the wall contract.
            raise HTTPException(status_code=422, detail=str(e)) from e
        # The reload counters are MONOTONIC — a separate invariant from the merge:
        # never let a write DECREASE one below the stored value (an explicit
        # stale-lower bump must not undo a /control/ "reload all").
        config = store.clamp_reload_monotonic(config, existing)
        store.save_wall_config(data_dir, config)
        return _payload(config, True)

    return router
