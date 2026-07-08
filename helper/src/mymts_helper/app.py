"""FastAPI application factory.

Wires:
  - SQLite (migrated on startup),
  - the RSS poller + retention sweep,
  - the channel prober,
  - /health (now with feeds + channels freshness),
  - /api/feed[/sources], /api/channels.

Both the poller and the prober live as asyncio tasks via FastAPI's
lifespan context. Phantom mode runs everything through fixtures, never
touching the network.
"""

from __future__ import annotations

import logging
import time
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI

from . import db, phantom
from .channels.api import get_router as channels_router
from .channels.override import seed_lineup
from .channels.presets import get_router as presets_router
from .channels.prober import ChannelProber
from .config import Config
from .discord.token_api import get_router as discord_router
from .feeds.api import get_router as feed_router
from .feeds.poller import FeedPoller
from .feeds.seeder import seed_from_file as seed_feeds_from_file
from .fetcher import Resolver
from .health import FreshnessSnapshotter, HealthState
from .log import configure_logging
from .playlist.api import get_router as playlist_router
from .playlist.profiles import load_profiles
from .stream.api import get_router as stream_router
from .ticker.api import get_router as ticker_router
from .ticker.pollers import MarketsPoller, SportsPoller
from .wall.api import get_router as wall_router
from .wall.outputs_api import get_router as outputs_router
from .weather.api import get_router as weather_router
from .weather.cache import RadarFrameCache

log = logging.getLogger("mymts_helper")


def _resolve_db_path(cfg: Config) -> Path:
    return Path(cfg.data_dir) / "mymts-helper.db"


def _resolve_seed_path() -> Path:
    # Channels seed ships with the package. We materialise the seed at
    # boot in case it's been updated since last deploy — upserts are
    # idempotent and operator-curated edits still flow through.
    from importlib.resources import files
    pkg = files("mymts_helper.channels").joinpath("seed.json")
    return Path(str(pkg))


def _resolve_feed_seed_path() -> Path:
    # Same shape as the channel seed: ships with the package, applied
    # idempotently on every boot.
    from importlib.resources import files
    pkg = files("mymts_helper.feeds").joinpath("seed.json")
    return Path(str(pkg))


def _resolve_lineup_override_path(cfg: Config) -> Path:
    # OPTIONAL per-deployment override (gitignored), in the writable data dir —
    # NOT the shipped seed. Absent → the shipped lineup loads unchanged.
    return Path(cfg.data_dir) / "lineup.local.json"


def create_app(
    config: Config | None = None,
    *,
    resolver: Resolver | None = None,
) -> FastAPI:
    """Build the FastAPI app.

    `resolver` overrides the outbound DNS resolver used by both pollers.
    Production passes None (default resolver). Tests pass a fake that
    returns a private address so the SSRF guard rejects any attempted
    fetch — the lifespan still starts and the routes still wire, but
    nothing leaves the test process.
    """
    cfg = config or Config.from_env()
    configure_logging(cfg.log_level)

    db_path = _resolve_db_path(cfg)
    db_path.parent.mkdir(parents=True, exist_ok=True)
    schema_version = db.migrate(db_path)
    log.info("db_ready", extra={"path": str(db_path), "schema_version": schema_version})

    # Seed channels + feed sources (idempotent). Channels go through the lineup
    # reconcile so an OPTIONAL operator override (lineup.local.json in the data
    # dir) can add/disable/recategorize on top of the shipped seed — absent → the
    # shipped curated lineup, identical to today.
    seed_path = _resolve_seed_path()
    feed_seed_path = _resolve_feed_seed_path()
    override_path = _resolve_lineup_override_path(cfg)
    conn = db.connect(db_path)
    try:
        result = seed_lineup(conn, seed_path, override_path)
        log.info("channels_seeded", extra={"count": result["seeded"], **result})
        seeded_feeds = seed_feeds_from_file(conn, feed_seed_path)
        log.info("feed_sources_seeded_total", extra={"count": seeded_feeds})
    finally:
        conn.close()

    # Playlist profiles (built-in `default` + any operator-defined named
    # profiles from cfg.profiles_file). Loaded once at startup; the loader
    # is tolerant (a bad file degrades to default-only) so the wall boots.
    profiles = load_profiles(cfg.profiles_file)
    log.info("playlist_profiles_loaded", extra={"count": len(profiles)})

    # Resolver precedence: explicit override (test) > phantom mode > default.
    if resolver is not None:
        active_resolver = resolver
    elif cfg.phantom_mode:
        active_resolver = phantom.phantom_resolver
        log.info("phantom_mode_active")
    else:
        from .fetcher import default_resolver as active_resolver  # noqa: F401

    poller = FeedPoller(
        db_path=db_path,
        interval_seconds=cfg.feed_poll_interval_seconds,
        retention_days=cfg.feed_retention_days,
        resolver=active_resolver,
    )
    prober = ChannelProber(
        db_path=db_path,
        interval_seconds=cfg.channel_probe_interval_seconds,
        resolver=active_resolver,
        youtube_resolve_timeout_seconds=cfg.youtube_resolve_timeout_seconds,
    )
    markets_poller = MarketsPoller(
        interval_seconds=cfg.markets_poll_interval_seconds,
        resolver=active_resolver,
    )
    sports_poller = SportsPoller(
        interval_seconds=cfg.sports_poll_interval_seconds,
        resolver=active_resolver,
    )

    snapshotter = FreshnessSnapshotter(db_path=db_path)
    health_state = HealthState(started_at=time.monotonic(), snapshotter=snapshotter)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        # Background pollers run only when not in PHANTOM mode by default —
        # phantom mode preloads fixtures synchronously below and skips
        # outbound traffic entirely.
        if not cfg.phantom_mode:
            await poller.start()
            await prober.start()
            await markets_poller.start()
            await sports_poller.start()
        else:
            await phantom.preload(db_path)
            # Phantom = no outbound traffic: the ticker pollers are NOT
            # started. Markets already holds an all-sample snapshot;
            # seed the sports sample slate so that mode renders too.
            sports_poller.seed_sample_slate()
        try:
            yield
        finally:
            await poller.stop()
            await prober.stop()
            await markets_poller.stop()
            await sports_poller.stop()

    app = FastAPI(
        title="MyMTS Helper",
        version=cfg.build_version,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )

    @app.get("/health")
    def health() -> dict[str, object]:
        return health_state.snapshot(
            cfg,
            last_poll_at=poller.last_poll_at,
            last_probe_at=prober.last_probe_at,
        )

    app.include_router(feed_router(db_path))
    app.include_router(channels_router(db_path))
    app.include_router(presets_router())
    app.include_router(playlist_router(db_path, profiles))
    app.include_router(ticker_router(markets_poller, sports_poller))
    # Server-side wall config (the headless-container version): the rendered
    # wall (`/app/`) reads it; the picker control surface (`/control/`) writes
    # it. Helper-hosted by necessity — a headless wall has no device to hold
    # its lineup. Persisted in the data dir (seedless, gitignored).
    app.include_router(wall_router(db_path, cfg.data_dir))
    # Unified Outputs panel (the multi-output fan-out): runtime per-output status
    # (read from the renderer's status file in the shared stream volume, path-safe)
    # + start/stop/restart controls that edit the wall config through the SAME
    # validated partial-merge path as PUT /api/wall (no out-of-band state).
    app.include_router(
        outputs_router(
            db_path,
            cfg.data_dir,
            cfg.stream_dir,
            discord_client_id=cfg.discord_client_id,
            discord_client_secret=cfg.discord_client_secret,
            discord_public_origin=cfg.discord_activity_public_origin,
            phantom=cfg.phantom_mode,
        )
    )
    # Weather radar widget (free public NWS RIDGE loop GIF): proxy + cache the
    # animated radar so a cell set to a `weather-radar-*` source renders a CORS-
    # clean, rate-respectful, honest-fallback loop. Always mounted (inert until a
    # cell selects it); fetches through the SAME resolver as the pollers, so phantom
    # mode / the SSRF posture is respected (no egress in phantom → honest-offline).
    app.include_router(weather_router(RadarFrameCache(), resolver=active_resolver))
    log.info("weather_radar_route_mounted")
    # Renderer HLS stream (headless-container version, second half): serve the
    # playlist + segments the renderer writes to the shared volume, so VLC /
    # Apple TV opens one URL. Opt-in via STREAM_DIR; absent → no route added.
    if cfg.stream_dir:
        app.include_router(stream_router(cfg.stream_dir))
        log.info("stream_route_mounted", extra={"dir": cfg.stream_dir})

    # LAN web client (optional, off by default). When WEB_CLIENT_DIR is
    # set to an existing directory, serve it as static files at `/app`
    # — same-origin as the API above, so the client needs NO CORS and
    # carries no credentials. Mounted LAST so it can never shadow an
    # `/api/*` or `/health` route. GET-only static serving; no new
    # hostile-input surface (it serves our own bundled files). If the
    # path is unset or missing, nothing is mounted and the helper is
    # byte-for-byte its prior self.
    if cfg.web_client_dir:
        web_dir = Path(cfg.web_client_dir)
        if web_dir.is_dir():
            from fastapi.staticfiles import StaticFiles
            app.mount("/app", StaticFiles(directory=str(web_dir), html=True), name="web-client")
            log.info("web_client_mounted", extra={"dir": str(web_dir)})
            # The picker CONTROL surface (headless-container version): a
            # lightweight, video-free editor for the server-side wall config,
            # served at `/control`. It lives in the same web/ tree so it shares
            # the bundled pure JS at /app/js/* via absolute imports; mounted at
            # its own `/control` prefix (a sibling of `/app`, so neither shadows
            # the other). GET-only static serving of our own bundled files.
            control_dir = web_dir / "control"
            if control_dir.is_dir():
                app.mount(
                    "/control",
                    StaticFiles(directory=str(control_dir), html=True),
                    name="web-control",
                )
                log.info("web_control_mounted", extra={"dir": str(control_dir)})
        else:
            log.warning("web_client_dir_missing", extra={"dir": str(web_dir)})

    log.info(
        "helper_started",
        extra={
            "port": cfg.port,
            "phantom": cfg.phantom_mode,
            "version": cfg.build_version,
            "schema_version": schema_version,
        },
    )
    return app


def create_stream_app(stream_dir: str) -> FastAPI:
    """A MINIMAL app exposing ONLY the hardened ``/api/stream`` route, for a
    plain-HTTP LAN listener.

    A strict tvOS client (VLC on Apple TV) hangs forever on the helper's
    SELF-SIGNED HTTPS rather than prompting to accept it, and the ``.ts`` segments
    ride the same HTTPS so they stall too — a LAN video stream needs no TLS. So
    the entry point also serves the HLS over plain HTTP via this app. It is
    deliberately MINIMAL: the API, ``/control/`` and ``/app/`` are NOT included —
    they stay HTTPS-only on the main app. It reuses the SAME symlink-safe
    ``stream_router`` (one hardened serving path; no second, divergent one to
    drift / reintroduce the traversal hole the review closed). No DB, no pollers,
    no lifespan — it only reads the shared read-only stream volume.
    """
    app = FastAPI(
        title="MyMTS Stream",
        version="stream",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.include_router(stream_router(stream_dir))

    @app.get("/health")
    def health() -> dict[str, object]:
        return {"status": "ok", "stream": True}

    return app


class _StripProxyPrefix:
    """ASGI middleware that strips a leading ``/.proxy`` from the request path.

    Discord's Activity proxy serves our app at ``…/.proxy/`` and normally strips the
    prefix before forwarding to our origin — but configurations vary, and some
    forward the full ``/.proxy/...`` path. This makes the public origin tolerant of
    BOTH: ``/.proxy/api/stream/playlist.m3u8`` and ``/api/stream/playlist.m3u8``
    resolve identically. Belt-and-suspenders for the documented `/.proxy/` gotcha;
    a no-op for every non-proxied request (the LAN/acceptance paths)."""

    def __init__(self, app: object) -> None:
        self._app = app

    async def __call__(self, scope: dict, receive: object, send: object) -> None:
        if scope.get("type") in ("http", "websocket"):
            path = scope.get("path", "")
            if path == "/.proxy" or path.startswith("/.proxy/"):
                stripped = path[len("/.proxy"):] or "/"
                scope = {**scope, "path": stripped, "raw_path": stripped.encode("latin-1")}
        await self._app(scope, receive, send)  # type: ignore[operator]


# Content types that must NEVER be edge-cached: the Activity's own static assets
# (HTML/CSS/JS). They're tiny, and freshness beats caching — a stale asset (e.g. a
# Cloudflare-cached pre-deploy .css served alongside a fresh index.html/.mjs) is exactly
# the white-frame class of bug this closes. The HLS media (video/mp2t, mpegurl) is NOT
# here — it keeps the stream router's own headers (already no-store).
_NO_STORE_CONTENT_TYPES = ("text/html", "text/css", "text/javascript", "application/javascript")


class _NoStoreStatic:
    """ASGI middleware that stamps ``Cache-Control: no-store`` on the Activity's static
    assets (HTML/CSS/JS) so no edge/proxy cache (Cloudflare, Discord's proxy) can serve
    a stale pre-deploy copy. Keyed on the response content-type, so the HLS passthrough
    (its own headers) and every non-asset response are untouched."""

    def __init__(self, app: object) -> None:
        self._app = app

    async def __call__(self, scope: dict, receive: object, send: object) -> None:
        if scope.get("type") != "http":
            await self._app(scope, receive, send)  # type: ignore[operator]
            return

        from starlette.datastructures import MutableHeaders

        async def _send(message: dict) -> None:
            if message["type"] == "http.response.start":
                headers = MutableHeaders(raw=message["headers"])
                ct = headers.get("content-type", "")
                if any(ct.startswith(p) for p in _NO_STORE_CONTENT_TYPES):
                    headers["cache-control"] = "no-store"
            await send(message)  # type: ignore[operator]

        await self._app(scope, receive, _send)  # type: ignore[operator]


def create_public_app(
    *,
    stream_dir: str,
    activity_dir: str,
    discord_client_id: str | None,
    discord_client_secret: str | None,
    build_sha: str = "dev",
) -> FastAPI:
    """A DEDICATED, MINIMAL public app for the Discord Activity — the only MyMTS
    surface ever exposed to the public internet (behind the operator's Cloudflare
    tunnel). It serves ONLY the Activity and its three supporting routes, plus a
    trivial liveness endpoint:

      1. the Activity static app (``discord-activity/``) at ``/`` (the iframe) — the
         index injected with the build SHA + ``no-store``, its assets no-store too,
      2. ``/api/discord/config`` + ``/api/discord/token`` (the OAuth exchange),
      3. the hardened ``/api/stream`` passthrough (the SAME symlink-safe router the
         LAN serves — the HLS the Activity plays, relayed through the public origin),
      4. ``GET /health`` (a static ``{status, build_sha}`` liveness check — no state,
         no egress).

    The full API, ``PUT /api/wall``, ``/control/`` and ``/app/`` are DELIBERATELY
    NOT here — they stay LAN-only. The raw LAN stream is never exposed; only this
    relay. No DB, no pollers, no lifespan. Mirrors ``create_stream_app``'s
    minimal-surface discipline so there is no second, divergent serving path to
    drift. ``/.proxy/`` tolerance via :class:`_StripProxyPrefix`."""
    from fastapi.responses import HTMLResponse
    from fastapi.staticfiles import StaticFiles

    app = FastAPI(
        title="MyMTS Discord Activity (public)",
        version="public",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )

    # Routers FIRST so they win over the catch-all static mount registered last.
    app.include_router(
        discord_router(client_id=discord_client_id, client_secret=discord_client_secret)
    )
    app.include_router(stream_router(stream_dir))  # the HLS passthrough (path-safe)

    @app.get("/health")
    def health() -> dict[str, object]:
        return {"status": "ok", "activity": True, "build_sha": build_sha}

    # Serve index.html EXPLICITLY (winning over the static mount) so we can inject the
    # running build SHA into its `__MYMTS_BUILD_SHA__` placeholder — a version stamp
    # visible in view-source, answering "which build is Discord running?" forever — and
    # stamp `no-store` so no edge/proxy cache pins a pre-deploy copy. Both "/" and
    # "/index.html" (Discord may request either) map here.
    _index_path = Path(activity_dir) / "index.html"

    def _serve_index() -> HTMLResponse:
        html = _index_path.read_text(encoding="utf-8").replace("__MYMTS_BUILD_SHA__", build_sha)
        return HTMLResponse(content=html, headers={"Cache-Control": "no-store"})

    @app.get("/", include_in_schema=False)
    def index_root() -> HTMLResponse:
        return _serve_index()

    @app.get("/index.html", include_in_schema=False)
    def index_html() -> HTMLResponse:
        return _serve_index()

    # The Activity's OTHER assets (vendored SDK/hls.js, css, activity.mjs). Mounted LAST
    # so it can never shadow an /api/* route or the index routes above.
    app.mount("/", StaticFiles(directory=activity_dir, html=True), name="discord-activity")

    # Outer→inner: no-store the static assets (freshness > cache — kills the stale-asset
    # white-frame class), then tolerate a forwarded /.proxy/ prefix (resolve to the same
    # routes). Both are no-ops for the HLS passthrough (its own headers / path).
    app.add_middleware(_StripProxyPrefix)
    app.add_middleware(_NoStoreStatic)
    log.info("public_activity_app_built",
             extra={"activity_dir": activity_dir, "build_sha": build_sha})
    return app
