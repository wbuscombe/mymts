"""/api/stream — serve the renderer's HLS playlist + segments.

The renderer writes `playlist.m3u8` + `seg_NNNNN.ts` into a shared volume the
helper mounts READ-ONLY; this router serves them with the correct media types so
VLC / Apple TV plays the composited wall. Segment names are matched against a
strict pattern (so a request can never escape the stream dir — no path
traversal), and an absent playlist is an honest 503 ("stream not ready yet")
rather than a 404, so a player retrying while the renderer boots gets the right
signal. GET-only; serves only our own renderer's output — no new hostile-input
surface.
"""

from __future__ import annotations

import re
from pathlib import Path

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse

PLAYLIST_NAME = "playlist.m3u8"
# The renderer's ffmpeg writes `seg_%05d.ts`; accept that exact shape only. The
# `{segment}` path param can't contain a '/', and this regex forbids '.' / '..'
# / separators, so a crafted name can never resolve outside the stream dir.
_SEGMENT_RE = re.compile(r"^seg_\d+\.ts$")

# no-store: HLS is live; never let a cache pin a stale playlist/segment.
_NO_CACHE = {"Cache-Control": "no-store"}


def get_router(stream_dir: str) -> APIRouter:
    router = APIRouter(prefix="/api/stream", tags=["stream"])
    base = Path(stream_dir)

    @router.get("/playlist.m3u8")
    def playlist() -> FileResponse:
        f = base / PLAYLIST_NAME
        if not f.is_file():
            # The renderer hasn't produced a playlist yet (booting / restarting).
            raise HTTPException(status_code=503, detail="stream not ready")
        return FileResponse(
            f, media_type="application/vnd.apple.mpegurl", headers=_NO_CACHE,
        )

    @router.get("/{segment}")
    def segment(segment: str) -> FileResponse:
        if not _SEGMENT_RE.match(segment):
            raise HTTPException(status_code=404, detail="not found")
        f = base / segment
        if not f.is_file():
            raise HTTPException(status_code=404, detail="not found")
        return FileResponse(f, media_type="video/mp2t", headers=_NO_CACHE)

    return router
