"""/api/outputs — runtime output status + start/stop/restart controls.

Two surfaces for the unified Outputs panel (/control/):

  GET  /api/outputs/status            → per-output runtime state (merged: the wall
                                         config's CONFIGURED outputs + the renderer's
                                         live status file). HLS: running/stopped +
                                         res/bitrate + playlist path. Mercury: the
                                         publisher's honest state + setup checklist.
  POST /api/outputs/{name}/{action}   → start | stop | restart — performed as a
                                         config edit through the SAME validated
                                         partial-merge path as PUT /api/wall (no
                                         out-of-band state): start/stop flip
                                         `enabled`; restart bumps `restart_epoch`.

The renderer writes the status file into the shared stream volume (renderer rw,
helper ro — the SAME trust direction as the HLS stream, no new privileged channel).
The read is path-safe (reject symlink + resolve within the stream dir), mirroring
the hardened stream router.
"""

from __future__ import annotations

import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

from fastapi import APIRouter, HTTPException

from .. import db
from ..channels import registry
from ..discord import status as discord_status_mod
from ..weather import regions as weather_regions
from . import store

# The renderer's per-output status file (renderer.run.STATUS_FILE).
STATUS_FILE = "outputs-status.json"


def _read_status(stream_dir: str | None) -> dict | None:
    """Path-safe read of the renderer's status file: reject a symlink, resolve the
    real path, and confirm it stays within the stream dir (same defence as the
    stream router). None on absent / corrupt / out-of-bounds → /control/ shows a
    "renderer not reporting" state rather than crashing."""
    if not stream_dir:
        return None
    base = Path(stream_dir)
    f = base / STATUS_FILE
    try:
        if f.is_symlink():
            return None
        real = f.resolve(strict=True)
        real.relative_to(base.resolve())
        if not real.is_file():
            return None
        return json.loads(real.read_text(encoding="utf-8"))
    except (OSError, ValueError, json.JSONDecodeError):
        return None


def get_router(
    db_path: Path,
    data_dir: str,
    stream_dir: str | None = None,
    *,
    discord_client_id: str | None = None,
    discord_client_secret: str | None = None,
    discord_public_origin: str | None = None,
    phantom: bool = False,
    discord_probe: Callable[[str], bool | None] | None = None,
) -> APIRouter:
    router = APIRouter(prefix="/api/outputs", tags=["outputs"])

    # The Discord card's reachability check (helper-computed). Cached so the
    # `/control/` status poll doesn't round-trip the tunnel every tick. In phantom
    # mode it is never invoked (discord_status short-circuits before any egress).
    _probe = discord_probe or discord_status_mod.make_cached_origin_probe()

    def _valid_slugs() -> list[str]:
        with db.connection_scope(db_path) as conn:
            slugs = [c.slug for c in registry.list_channels(conn, enabled_only=True)]
        return slugs + weather_regions.radar_slugs()

    @router.get("/status")
    def status() -> dict[str, Any]:
        config, _ = store.load_wall_config(data_dir, _valid_slugs())
        outputs = config.get("outputs", {})
        file = _read_status(stream_dir)
        reporting = file is not None
        runtime = (file or {}).get("outputs", {})

        hls_enabled = bool((outputs.get("hls") or {}).get("enabled"))

        result: dict[str, Any] = {}
        for name, o in outputs.items():
            if name == "discord":
                # Discord is HELPER-computed (the helper holds DISCORD_* + owns the
                # public origin; the renderer is not involved). A special viewer shape:
                # NO resolution/bitrate/audio. The state machine NEVER posts to Discord
                # and NEVER claims a live session (launch-to-start is a platform rule).
                ds = discord_status_mod.discord_status(
                    output=o,
                    client_id=discord_client_id,
                    client_secret=discord_client_secret,
                    public_origin=discord_public_origin,
                    hls_enabled=hls_enabled,
                    phantom=phantom,
                    probe=_probe,
                )
                result[name] = {
                    "enabled": o.get("enabled"),
                    **ds,
                    "public_origin": discord_public_origin or "",
                }
                continue
            entry: dict[str, Any] = {
                "enabled": o.get("enabled"),
                "resolution": o.get("resolution"),
                "bitrate_kbps": o.get("bitrate_kbps"),
                "audio": o.get("audio"),
            }
            r = runtime.get(name, {})
            if name == "mercury":
                # The renderer (which holds the LiveKit env) is the authority for the
                # checklist + state. Without a report, fall back to a config-only view
                # (key/tailnet unknown). NEVER "connected/publishing" in this build.
                entry["channel_guid"] = o.get("channel_guid", "")
                entry["display_name"] = o.get("display_name", "")
                entry["state"] = r.get(
                    "state", ("needs_setup" if o.get("enabled") else "disabled")
                )
                entry["checklist"] = r.get("checklist", {
                    "key_present": None,
                    "channel_set": bool(o.get("channel_guid")),
                    "tailnet_reachable": None,
                })
                entry["detail"] = r.get(
                    "detail", "Renderer not reporting" if not reporting else ""
                )
            else:
                entry["state"] = r.get("state", ("stopped" if o.get("enabled") else "disabled"))
                entry["playlist_path"] = r.get(
                    "playlist_path", "/api/stream/playlist.m3u8" if name == "hls" else None
                )
            result[name] = entry

        return {
            "reporting": reporting,
            "render": (file or {}).get(
                "render", {"resolution": store.derive_render_resolution(outputs)}
            ),
            "outputs": result,
        }

    @router.post("/{name}/{action}")
    def control(name: str, action: str) -> dict[str, Any]:
        if name not in store.OUTPUT_NAMES:
            raise HTTPException(status_code=404, detail=f"unknown output {name!r}")
        if action not in ("start", "stop", "restart"):
            raise HTTPException(status_code=404, detail=f"unknown action {action!r}")
        slugs = _valid_slugs()
        existing, _ = store.load_wall_config(data_dir, slugs)
        cur = (existing.get("outputs") or {}).get(name) or {}
        if action == "start":
            patch = {"outputs": {name: {"enabled": True}}}
        elif action == "stop":
            patch = {"outputs": {name: {"enabled": False}}}
        elif name == "discord":
            # Discord is launch-to-start: there is no helper-owned session/encoder to
            # restart (a user launches the Activity in Discord). Reject honestly
            # rather than pretend a restart did something.
            raise HTTPException(
                status_code=400,
                detail="discord is launch-to-start; there is no session to restart",
            )
        else:  # restart — bump the per-output cycle counter (monotonic)
            patch = {"outputs": {name: {"restart_epoch": cur.get("restart_epoch", 0) + 1}}}
        merged = store.merge_wall_config(existing, patch)
        try:
            config = store.validate_wall_config(merged, set(slugs))
        except store.WallConfigError as e:
            raise HTTPException(status_code=422, detail=str(e)) from e
        config = store.clamp_reload_monotonic(config, existing)
        store.save_wall_config(data_dir, config)
        return {"ok": True, "output": name, "action": action, "outputs": config["outputs"]}

    return router
