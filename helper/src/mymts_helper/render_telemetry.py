"""/api/render/telemetry — opt-in per-tile render instrumentation sink (LAN-only).

The render page, when pointed at ``?fpsmeter=1`` (off by default), POSTs a
cumulative-counter snapshot ~1/s — per-tile decode/present fps, drop-%, and the
variant-vs-cell overdraw the bench harness needs to attribute frame loss. This sink
keeps only the LATEST snapshot in memory (no disk, no secrets) and hands it back on
GET; the harness differences two GETs to compute any window.

Posture (deliberate):
  * Mounted ONLY on the full LAN app (``create_app``) — NEVER on the Discord public
    app (``create_public_app`` includes only discord + stream + index). The tunnel
    origin can't see it.
  * Inert until measured: nothing POSTs unless the renderer's HELPER_URL carries
    ``fpsmeter=1`` for a measurement window, so prod holds ``latest: null``.
  * Bounded: the raw body is size-capped and the stored tile list is truncated, so a
    stray/hostile POST can't balloon helper memory. GET/POST only, no egress.
"""

from __future__ import annotations

import json
import time
from typing import Any

from fastapi import APIRouter, HTTPException, Request

# A telemetry snapshot is small (a few tiles × a dozen scalars). Cap generously so a
# malformed/hostile POST can't grow helper memory; real snapshots are < 8 KiB.
MAX_BODY_BYTES = 512 * 1024
# Truncate the stored tile list defensively (the wall tops out at a handful of cells).
MAX_TILES = 64


class TelemetryStore:
    """The single latest render telemetry snapshot + when it arrived. In-memory,
    per-app instance (not a process global) so tests get a clean one and two apps
    never cross-contaminate."""

    def __init__(self) -> None:
        self._latest: dict[str, Any] | None = None
        self._received_at: float | None = None

    def put(self, snapshot: dict[str, Any]) -> None:
        self._latest = snapshot
        self._received_at = time.time()

    def get(self) -> dict[str, Any]:
        if self._latest is None or self._received_at is None:
            return {"latest": None, "age_s": None}
        return {"latest": self._latest, "age_s": round(time.time() - self._received_at, 2)}


def sanitize(payload: dict[str, Any]) -> dict[str, Any]:
    """Keep the snapshot's shape but bound the one unbounded field (``tiles``). Pure
    — unit-tested. Does not validate the numbers (this is a debug surface, not a
    trust boundary); it only stops an oversized list from being retained."""
    tiles = payload.get("tiles")
    if isinstance(tiles, list) and len(tiles) > MAX_TILES:
        payload = dict(payload)
        payload["tiles"] = tiles[:MAX_TILES]
    return payload


def get_router(store: TelemetryStore | None = None) -> APIRouter:
    store = store or TelemetryStore()
    router = APIRouter(prefix="/api/render", tags=["render"])

    @router.post("/telemetry")
    async def post_telemetry(request: Request) -> dict[str, bool]:
        # Reject oversized bodies cheaply (declared length first, then the real read).
        cl = request.headers.get("content-length")
        if cl is not None and cl.isdigit() and int(cl) > MAX_BODY_BYTES:
            raise HTTPException(status_code=413, detail="payload too large")
        raw = await request.body()
        if len(raw) > MAX_BODY_BYTES:
            raise HTTPException(status_code=413, detail="payload too large")
        try:
            payload = json.loads(raw)
        except (ValueError, UnicodeDecodeError) as e:
            raise HTTPException(status_code=422, detail="invalid JSON") from e
        if not isinstance(payload, dict):
            raise HTTPException(status_code=422, detail="expected a JSON object")
        store.put(sanitize(payload))
        return {"ok": True}

    @router.get("/telemetry")
    def get_telemetry() -> dict[str, Any]:
        return store.get()

    # Expose the store so a caller/test can share the same instance.
    router.telemetry_store = store  # type: ignore[attr-defined]
    return router
