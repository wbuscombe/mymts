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
from .channels import registry as channels_registry
from .channels.api import get_router as channels_router
from .channels.prober import ChannelProber
from .config import Config
from .feeds.api import get_router as feed_router
from .feeds.poller import FeedPoller
from .feeds.seeder import seed_from_file as seed_feeds_from_file
from .fetcher import Resolver
from .health import FreshnessSnapshotter, HealthState
from .log import configure_logging

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

    # Seed channels + feed sources (idempotent).
    seed_path = _resolve_seed_path()
    feed_seed_path = _resolve_feed_seed_path()
    conn = db.connect(db_path)
    try:
        seeded = channels_registry.seed_from_file(conn, seed_path)
        log.info("channels_seeded", extra={"count": seeded})
        seeded_feeds = seed_feeds_from_file(conn, feed_seed_path)
        log.info("feed_sources_seeded_total", extra={"count": seeded_feeds})
    finally:
        conn.close()

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
        else:
            await phantom.preload(db_path)
        try:
            yield
        finally:
            await poller.stop()
            await prober.stop()

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
