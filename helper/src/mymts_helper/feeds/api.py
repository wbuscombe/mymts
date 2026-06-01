"""/api/feed endpoints.

Stage 2 endpoints:
  GET /api/feed          — newest items, paginated by `since` and `limit`
  GET /api/feed/sources  — registered sources + freshness state

Response envelope carries `schema_version: 1`. Additive fields are
backward-compatible; removing/renaming requires a version bump and a
coordinated update on the TV.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, Query

from .. import db
from . import store

API_SCHEMA_VERSION = 1


def get_router(db_path: Path) -> APIRouter:
    router = APIRouter(prefix="/api/feed", tags=["feed"])

    def _conn():
        c = db.connect(db_path)
        try:
            yield c
        finally:
            c.close()

    @router.get("")
    def list_feed(
        conn=Depends(_conn),
        limit: int = Query(default=200, ge=1, le=500),
        since: str | None = Query(default=None, description="ISO 8601 UTC; items after this"),
    ) -> dict[str, Any]:
        items = store.recent_items(conn, limit=limit, since=since)
        return {
            "schema_version": API_SCHEMA_VERSION,
            "items": [
                {
                    "id": int(it["id"]),
                    "guid": it["guid"],
                    "source": it["source_label"],
                    "source_url": it["source_url"],
                    "title": it["title"],
                    "summary": it["summary"],
                    "link": it["link"],
                    "published_at": it["published_at"],
                    "fetched_at": it["fetched_at"],
                }
                for it in items
            ],
        }

    @router.get("/sources")
    def list_sources(conn=Depends(_conn)) -> dict[str, Any]:
        rows = store.list_sources(conn)
        return {
            "schema_version": API_SCHEMA_VERSION,
            "sources": [
                {
                    "id": s.id,
                    "url": s.url,
                    "label": s.label,
                    "enabled": s.enabled,
                    "last_fetch_at": s.last_fetch_at,
                    "last_success_at": s.last_success_at,
                    "last_error": s.last_error,
                    "error_count": s.error_count,
                }
                for s in rows
            ],
        }

    return router
