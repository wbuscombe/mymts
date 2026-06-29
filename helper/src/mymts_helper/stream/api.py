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
# The renderer's ffmpeg writes `seg_%05d.ts`; accept that exact shape only,
# matched with re.fullmatch (NOT .match, whose `$` would also accept a trailing
# newline — `seg_1.ts\n`). The `{segment}` path param can't contain a '/'.
_SEGMENT_RE = re.compile(r"seg_\d+\.ts")

# no-store: HLS is live; never let a cache pin a stale playlist/segment.
# Access-Control-Allow-Origin: * — the HLS is PUBLIC video (no secret); a browser HLS
# consumer (the Mercury publisher's livekit-client, served from a different local
# origin; any in-browser player) fetches the playlist + segments cross-origin via XHR,
# which CORS otherwise blocks. Read-only GET of our own renderer output → ACAO:* is
# the standard, safe posture for an HLS endpoint.
_STREAM_HEADERS = {"Cache-Control": "no-store", "Access-Control-Allow-Origin": "*"}


def get_router(stream_dir: str) -> APIRouter:
    router = APIRouter(prefix="/api/stream", tags=["stream"])
    base = Path(stream_dir)

    def _serve(name: str, media_type: str) -> FileResponse:
        """Serve a file from the stream dir, refusing anything that isn't a plain
        regular file INSIDE it. The shared volume is written by the renderer
        (rw); the helper only reads it (ro) — but a compromised renderer could
        plant a SYMLINK whose name passes the filter and whose target resolves,
        in the HELPER's mount namespace, to the TLS private key (/etc/ssl/mymts)
        or the SQLite DB (/data). So: reject symlinks, and confirm the resolved
        real path stays within the stream dir, before serving. Defense in depth
        on the new write→read surface; keeps the isolation guarantee real."""
        f = base / name
        try:
            if f.is_symlink():
                raise HTTPException(status_code=404, detail="not found")
            real = f.resolve(strict=True)
            real.relative_to(base.resolve())
            if not real.is_file():
                raise HTTPException(status_code=404, detail="not found")
        except (OSError, ValueError) as e:
            raise HTTPException(status_code=404, detail="not found") from e
        return FileResponse(real, media_type=media_type, headers=_STREAM_HEADERS)

    # GET + HEAD: some strict players (incl. tvOS clients) HEAD-probe a manifest
    # / segment before fetching; a GET-only route 405s the probe and can stall
    # them. Starlette's FileResponse sends headers-only on a HEAD, so one handler
    # serves both correctly (the symlink/traversal guard in _serve applies to
    # HEAD identically).
    @router.api_route("/playlist.m3u8", methods=["GET", "HEAD"])
    def playlist() -> FileResponse:
        f = base / PLAYLIST_NAME
        # Absent (or a broken/symlink shape) → the renderer hasn't produced a
        # real playlist yet: an honest "not ready" rather than a 404.
        if not f.exists() or f.is_symlink():
            raise HTTPException(status_code=503, detail="stream not ready")
        return _serve(PLAYLIST_NAME, "application/vnd.apple.mpegurl")

    @router.api_route("/{segment}", methods=["GET", "HEAD"])
    def segment(segment: str) -> FileResponse:
        if not _SEGMENT_RE.fullmatch(segment):
            raise HTTPException(status_code=404, detail="not found")
        return _serve(segment, "video/mp2t")

    return router
