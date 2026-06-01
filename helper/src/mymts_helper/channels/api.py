"""/api/channels endpoint."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends

from .. import db
from . import registry

API_SCHEMA_VERSION = 1


def get_router(db_path: Path) -> APIRouter:
    router = APIRouter(prefix="/api/channels", tags=["channels"])

    def _conn():
        c = db.connect(db_path)
        try:
            yield c
        finally:
            c.close()

    @router.get("")
    def list_channels(conn=Depends(_conn)) -> dict[str, Any]:
        rows = registry.list_channels(conn)
        return {
            "schema_version": API_SCHEMA_VERSION,
            "channels": [
                {
                    "slug": c.slug,
                    "label": c.label,
                    "kind": c.kind,
                    # current_url is what the TV plays; None on unavailable.
                    "current_url": c.current_url if c.status == "live" else None,
                    "status": c.status,
                    "enabled": c.enabled,
                    "last_check_at": c.last_check_at,
                    "last_success_at": c.last_success_at,
                    "last_error": c.last_error,
                    "error_count": c.error_count,
                }
                for c in rows
            ],
        }

    return router
