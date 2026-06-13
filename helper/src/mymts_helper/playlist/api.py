"""/api/playlist.m3u — the channel lineup as an M3U playlist (+ per-profile).

A standard playlist a VLC / Apple-TV client can load directly. The helper
stays the resolver/shield: the M3U points at each channel's resolved
upstream URL and the helper never proxies the bytes (the no-proxy
decision). LAN-only, same as the rest of the helper — one more route on
the existing listener, no new public surface. Only live channels are
listed (honest degradation).
"""

from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path

from fastapi import APIRouter, HTTPException, Response

from .. import db
from ..channels import registry
from ..channels.registry import ChannelRow
from .m3u import M3U_MEDIA_TYPE, render_m3u
from .profiles import DEFAULT_PROFILE_NAME, Profile, select_channels


def get_router(db_path: Path, profiles: Mapping[str, Profile]) -> APIRouter:
    router = APIRouter(prefix="/api", tags=["playlist"])

    # Connection opened + closed inside the route body via
    # `db.connection_scope` (not a `Depends()` yield-dependency) so the
    # sqlite3 connection never crosses an anyio-threadpool thread boundary
    # — the same rule channels/feeds follow.

    def _live_channels() -> list[ChannelRow]:
        with db.connection_scope(db_path) as conn:
            rows = registry.list_channels(conn)
        # Honest inclusion: only channels that actually resolve right now.
        # current_url is masked to None unless status==live, so this is the
        # same gate the TV + web clients use — never list a dead endpoint.
        return [c for c in rows if c.status == "live" and c.current_url]

    def _playlist_response(profile: Profile) -> Response:
        selected = select_channels(profile, _live_channels())
        return Response(content=render_m3u(selected), media_type=M3U_MEDIA_TYPE)

    @router.get("/playlist.m3u")
    def default_playlist() -> Response:
        # The built-in `default` profile always exists (load_profiles seeds it).
        return _playlist_response(profiles[DEFAULT_PROFILE_NAME])

    @router.get("/playlist/{name}.m3u")
    def named_playlist(name: str) -> Response:
        profile = profiles.get(name)
        if profile is None:
            raise HTTPException(status_code=404, detail="unknown_profile")
        return _playlist_response(profile)

    return router
