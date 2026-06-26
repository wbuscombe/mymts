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
from . import store

WALL_SCHEMA_VERSION = store.WALL_SCHEMA_VERSION


def get_router(db_path: Path, data_dir: str) -> APIRouter:
    router = APIRouter(prefix="/api/wall", tags=["wall"])

    def _valid_slugs() -> list[str]:
        # enabled_only: a disabled (lineup-override) channel is not a legal wall
        # assignment, exactly as it's excluded from the picker.
        with db.connection_scope(db_path) as conn:
            return [c.slug for c in registry.list_channels(conn, enabled_only=True)]

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
        try:
            config = store.validate_wall_config(payload, set(slugs))
        except store.WallConfigError as e:
            # 422: the body is well-formed JSON but fails the wall contract.
            raise HTTPException(status_code=422, detail=str(e)) from e
        # Force-reload counters are MONOTONIC — never let a write rewind one below
        # the stored value (e.g. an /app/ edit that echoed a stale-lower epoch
        # before its first hydrate must not undo a /control/ "reload all"). This is
        # the authoritative guard; the clients also avoid sending a stale value.
        existing, _ = store.load_wall_config(data_dir, slugs)
        config = store.clamp_reload_monotonic(config, existing)
        # CARRY-FORWARD the render resolution when the client OMITS it: /app/'s
        # hand-built PUT writes only channels/grid/audio and has no `render` key, so
        # without this the validator's default (1080p) would silently revert a
        # /control/-set 4K wall on any /app/ edit. /control/ always sends render
        # explicitly (so it can still change it); only an OMISSION is preserved.
        if payload.get("render") is None:
            config["render"] = existing["render"]
        store.save_wall_config(data_dir, config)
        return _payload(config, True)

    return router
