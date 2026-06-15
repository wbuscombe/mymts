"""/api/channels endpoint."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from fastapi import APIRouter

from .. import db
from . import registry
from .category import category_of

API_SCHEMA_VERSION = 1


def get_router(db_path: Path) -> APIRouter:
    router = APIRouter(prefix="/api/channels", tags=["channels"])

    # Connection opened + closed inside the route body via
    # `db.connection_scope` (not a `Depends()` yield-dependency) so the
    # sqlite3 connection never crosses an anyio-threadpool thread
    # boundary. See the matching note in feeds/api.py and
    # db.connection_scope's docstring.

    @router.get("")
    def list_channels() -> dict[str, Any]:
        with db.connection_scope(db_path) as conn:
            rows = registry.list_channels(conn)
        return {
            "schema_version": API_SCHEMA_VERSION,
            "channels": [
                {
                    "slug": c.slug,
                    "label": c.label,
                    "kind": c.kind,
                    # Section the LAN web client groups its picker by, mirroring
                    # the native ChannelPickerOverlay. Derived from the slug (a
                    # static taxonomy, not DB state); ALWAYS present (GENERAL for
                    # an unmapped slug) and status-independent — an offline
                    # channel still belongs to its section. Additive field; the
                    # TV ignores it (it has its own copy). See channels/category.py.
                    "category": category_of(c.slug),
                    # current_url is what the TV plays; None on unavailable.
                    "current_url": c.current_url if c.status == "live" else None,
                    "status": c.status,
                    # Web-client hint: True = HTTPS-clean (plays in the
                    # browser), False = http:// sub-resource found (TV-only),
                    # None = unclassified. The TV ignores this (it plays all
                    # live channels); the LAN web client uses it to label
                    # tiles honestly. Only meaningful when live.
                    "browser_playable": c.browser_playable if c.status == "live" else None,
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
