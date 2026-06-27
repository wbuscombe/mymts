"""``/api/weather/radar/{region}`` — proxy + cache the free public NWS radar loop.

Fetches the NWS RIDGE standard animated loop GIF for a region through the SSRF-
safe fetcher, caches it (:class:`RadarFrameCache`), and serves it same-origin so
the wall's ``<img>`` is CORS-clean. Honest fallback: on an upstream failure it
serves the LAST GOOD frame (marked stale) if we have one, else an honest 5xx —
never a blank or a faked radar. Rate-respectful: a cache hit never touches NWS, so
the upstream is fetched at most once per region per TTL no matter how many tiles
request it (the region key is the cache key; the wall's ``?t=`` cache-buster only
bypasses the BROWSER cache, not ours).
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable

from fastapi import APIRouter, HTTPException
from fastapi.responses import Response

from .. import fetcher
from ..fetcher import FetchResult, Resolver
from . import regions
from .cache import RadarFrame, RadarFrameCache

# Browser/CDN cache hint == the helper cache TTL, so the wall and the helper agree
# on freshness. The wall ALSO cache-busts its <img> on its own refresh timer to
# pull a new scan; that bypasses the browser cache but still hits the helper's
# region-keyed cache (so NWS isn't re-fetched per refresh).
_MAX_AGE_SECONDS = 300

FetchFn = Callable[..., Awaitable[FetchResult]]


def get_router(
    cache: RadarFrameCache,
    *,
    resolver: Resolver | None = None,
    fetch: FetchFn = fetcher.fetch,
) -> APIRouter:
    """Build the weather router. ``resolver`` (the app's active resolver) is passed
    through to the fetcher so phantom mode / the SSRF posture is respected; ``fetch``
    is injectable for tests (a fake avoids any outbound socket)."""
    router = APIRouter(prefix="/api/weather", tags=["weather"])

    def _serve(frame: RadarFrame, *, stale: bool) -> Response:
        headers = (
            {"Cache-Control": "no-store", "X-Radar-Stale": "1"}
            if stale
            else {"Cache-Control": f"public, max-age={_MAX_AGE_SECONDS}"}
        )
        return Response(content=frame.data, media_type=frame.content_type, headers=headers)

    def _last_good_or(status: int, detail: str, region: str) -> Response:
        # Honest fallback: a previously-fetched frame (any age) beats a blank — show
        # the LAST real radar we got, marked stale; only error if we never had one.
        last = cache.get_last_good(region)
        if last is not None:
            return _serve(last, stale=True)
        raise HTTPException(status_code=status, detail=detail)

    @router.get("/radar/{region}")
    async def radar(region: str) -> Response:
        reg = regions.region_for_key(region)
        if reg is None:
            raise HTTPException(status_code=404, detail="unknown_region")

        fresh = cache.get_fresh(region)
        if fresh is not None:
            return _serve(fresh, stale=False)

        url = regions.loop_url_for_region(reg)
        try:
            result = await (fetch(url) if resolver is None else fetch(url, resolver=resolver))
        except fetcher.FetchError:
            return _last_good_or(503, "radar_unavailable", region)

        is_image = result.content_type.startswith("image/")
        if result.status_code != 200 or not is_image or not result.body:
            return _last_good_or(502, "radar_upstream_error", region)

        content_type = result.content_type or "image/gif"
        cache.put(region, result.body, content_type)
        return Response(
            content=result.body,
            media_type=content_type,
            headers={"Cache-Control": f"public, max-age={_MAX_AGE_SECONDS}"},
        )

    return router
