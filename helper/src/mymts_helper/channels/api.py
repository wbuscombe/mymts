"""/api/channels endpoint."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from fastapi import APIRouter

from .. import db
from ..weather import regions as weather_regions
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
        # UNIFIED REGISTRY (2026-07). Radar — and any future widget-kind source — is a
        # FIRST-CLASS registry entry served to EVERY consumer of /api/channels: the
        # native TV picker, the web /app/ picker, and /control/. The former `?widgets=1`
        # opt-in (which listed radar to the web ONLY, keeping the native picker blind to
        # it) is GONE: that per-surface split was the wrong model. A channel's `kind`
        # tells a renderer HOW to render it (a video vs an animated-image widget); it
        # NEVER gates WHETHER a surface lists it. Any stale `?widgets=1` a cached older
        # web client still sends is now just an unrecognized query param (ignored) — the
        # response is byte-identical. Native learned to render weather-radar tiles in the
        # same release, so there is no "listed-but-unrenderable" entry anywhere. See
        # docs/decisions/0004 + the cross-surface parity guard (scripts/check_channel_parity.py).
        with db.connection_scope(db_path) as conn:
            # enabled_only: a lineup-override `disable` (enabled=0) removes the
            # channel from the picker entirely (not just unprobed). With the
            # shipped lineup every channel is enabled, so this is a no-op there.
            rows = registry.list_channels(conn, enabled_only=True)
        channels: list[dict[str, Any]] = [
                {
                    "slug": c.slug,
                    "label": c.label,
                    "kind": c.kind,
                    # Section BOTH clients group their picker by — the LAN web
                    # client and (since 2026-06) the native TV, which now reads this
                    # server-authoritative category instead of its own compiled map
                    # (ChannelCategory.sectionedByCategory), so a channel added here
                    # groups correctly with no app rebuild. Derived from the slug (a
                    # static taxonomy, not DB state); ALWAYS present (GENERAL for an
                    # unmapped slug) and status-independent — an offline channel
                    # still belongs to its section. See channels/category.py. A
                    # per-deployment override (lineup.local.json) may store an
                    # explicit category (migration 005); NULL → the shipped taxonomy.
                    "category": c.category or category_of(c.slug),
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
        ]
        # Widget-kind pseudo-channels (the NWS weather-radar loops) — appended
        # UNCONDITIONALLY so every surface offers the identical lineup. Their `kind`
        # (weather-radar, a `is_widget_kind`) routes each surface's renderer to the
        # animated-image path; it does not decide membership.
        channels.extend(weather_regions.channel_entries())
        return {"schema_version": API_SCHEMA_VERSION, "channels": channels}

    return router
