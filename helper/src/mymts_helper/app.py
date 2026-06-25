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
from .wall.api import get_router as wall_router
from .ticker.pollers import MarketsPoller, SportsPoller

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
